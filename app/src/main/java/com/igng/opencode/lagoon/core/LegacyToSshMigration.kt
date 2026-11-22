package com.igng.opencode.lagoon.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest

/** 升级迁移完成后的统计，用于在首次进入时向用户展示一次性下线提示。 */
data class SshMigrationReport(
  val migratedDevices: Int,
  val migratedHarnesses: Int,
  val retiredRemoteCount: Int,
  val retiredDirectCount: Int
) {
  val hasRetiredConnections: Boolean get() = retiredRemoteCount > 0 || retiredDirectCount > 0

  fun summaryText(): String = buildString {
    val retiredParts = buildList {
      if (retiredRemoteCount > 0) add("$retiredRemoteCount 条 DSH Remote")
      if (retiredDirectCount > 0) add("$retiredDirectCount 条直连")
    }.joinToString("和")
    append("Lagoon 现已全面切换为纯 SSH 设备模式。")
    if (migratedDevices > 0) {
      append("已自动迁移保留 $migratedDevices 台 SSH 设备")
      if (migratedHarnesses > 0) append("（$migratedHarnesses 个 Harness）")
      append("与全部 SSH 密钥。")
    }
    if (retiredParts.isNotEmpty()) {
      append("已清理不再支持的 $retiredParts 连接记录。")
    }
  }
}

/**
 * 幂等、崩溃安全的 v5.3.x → v6 纯 SSH 升级迁移器。
 *
 * 不变量：
 * 1. 先宽容恢复中断的 `configuration-save-journal-v1`，再读旧 `profiles`；
 * 2. 读取旧 `connection-graph` 的 SSH 设备停用开关（`source.enabled && host.enabled`），保留用户停用选择；
 * 3. 完整保留 `SshHostConfig.id`（`ssh-<uuid>`）、`SshKeyProfile.id`、全部 SSH `leafId`、
 *    `ssh-secrets` 与 `keystore-envelopes`，不重新编号，确保草稿/项目/缓存/SQLite 转录不丢失；
 * 4. 确定性将旧 `DeviceGroup`（v2/v3）中的 `host-<sha256>` / `members` 重映射到 `SshDevice.id` 并升级到 v4；
 * 5. 将 `device-discovery`（v1）键从旧 `host-<sha256>` 重映射到 `SshDevice.id` 并升级到 v2；
 * 6. 仅在 [DeviceStore] 原子落盘成功后，才清理已下线的 Remote / Direct 残留与旧 SP/Keystore 别名。
 */
internal object LegacyToSshMigration {
  const val TARGET_MIGRATION_VERSION = 2

  fun runIfNeeded(
    context: Context,
    deviceStore: DeviceStore,
    serverStore: ServerStore,
    groupStore: DeviceGroupStore,
    discoveryStore: DeviceDiscoveryStore,
    offlineCache: OfflineCache? = null,
    transcripts: TranscriptStore? = null
  ): SshMigrationReport? = runIfNeeded(
    deviceStore = deviceStore,
    serverStore = serverStore,
    groupStore = groupStore,
    discoveryStore = discoveryStore,
    serversPrefs = context.getSharedPreferences("servers", Context.MODE_PRIVATE),
    secretsPrefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE),
    sshHostsPrefs = context.getSharedPreferences("ssh-hosts", Context.MODE_PRIVATE),
    connectionGraphPrefs = context.getSharedPreferences("connection-graph", Context.MODE_PRIVATE),
    summaryDismissalsPrefs = context.getSharedPreferences("summary_dismissals", Context.MODE_PRIVATE),
    retiredPrefs = RETIRED_PREFS.map { context.getSharedPreferences(it, Context.MODE_PRIVATE) },
    decryptSecret = { value ->
      KeystoreCipher.also { it.install(context) }
        .of("lagoon-server-passwords", legacyAlias = "opencode-lagoon-server-passwords")
        .decrypt(value)
    },
    offlineCache = offlineCache,
    transcripts = transcripts,
    deleteRemoteKeystoreAlias = { deleteAndroidKeystoreAlias("lagoon-remote-device-identities") }
  )

  internal fun runIfNeeded(
    deviceStore: DeviceStore,
    serverStore: ServerStore,
    groupStore: DeviceGroupStore,
    discoveryStore: DeviceDiscoveryStore,
    serversPrefs: SharedPreferences,
    secretsPrefs: SharedPreferences,
    sshHostsPrefs: SharedPreferences,
    connectionGraphPrefs: SharedPreferences,
    summaryDismissalsPrefs: SharedPreferences,
    retiredPrefs: List<SharedPreferences> = emptyList(),
    decryptSecret: (String) -> String = { it },
    offlineCache: OfflineCache? = null,
    transcripts: TranscriptStore? = null,
    deleteRemoteKeystoreAlias: () -> Unit = {}
  ): SshMigrationReport? {
    if (deviceStore.migrationVersion() >= TARGET_MIGRATION_VERSION) return null

    val hasLegacyData = sshHostsPrefs.contains("hosts") ||
      serversPrefs.contains("profiles") ||
      connectionGraphPrefs.contains("graph") ||
      retiredPrefs.any { it.all.isNotEmpty() }

    if (!hasLegacyData && !deviceStore.hasStoredDevices()) {
      deviceStore.markMigrationVersion(TARGET_MIGRATION_VERSION)
      return null
    }

    // 1. 宽容恢复中断的 v5.3.x 跨 SP 保存日志，避免因旧 Remote/Direct 条目导致 fail-closed。
    recoverLegacyConfigurationJournal(serversPrefs, secretsPrefs, decryptSecret)

    // 2. 读取旧 SSH 主机配置。
    val legacyHosts = readLegacySshHosts(sshHostsPrefs)
    val legacyHostsById = legacyHosts.associateBy { it.id }

    // 3. 从旧 connection-graph 提取设备级启用开关和旧 Host ID 映射。
    val graphInfo = readLegacyConnectionGraph(connectionGraphPrefs)
    val oldHostIdToSshId = LinkedHashMap<String, String>()
    oldHostIdToSshId.putAll(graphInfo.oldHostIdToSshId)
    legacyHosts.forEach { host ->
      oldHostIdToSshId.putIfAbsent(legacyHostId(host.id), host.id)
    }

    // 4. 宽容读取旧 profiles，区分 SSH Harness 与已下线的 Remote / Direct 连接。
    val legacyProfiles = readLegacyProfiles(serversPrefs)
    val sshHarnessesByDevice = LinkedHashMap<String, MutableList<DeviceHarness>>()
    val retiredRemoteIds = mutableListOf<String>()
    val retiredDirectIds = mutableListOf<String>()
    val harnessIdToDeviceId = LinkedHashMap<String, String>()

    legacyProfiles.forEach { entry ->
      val sshId = entry.sshHostId?.takeIf { it.isNotBlank() }
      val isRemote = entry.kind == "dsh-remote" || !entry.remoteHostId.isNullOrBlank()
      if (isRemote) {
        retiredRemoteIds += entry.id
      } else if (sshId != null && sshId in legacyHostsById) {
        val host = legacyHostsById.getValue(sshId)
        val protocol = resolveProtocol(entry.kind, entry.protocolId)
        if (protocol == null) {
          retiredDirectIds += entry.id
        } else {
          val config = when (protocol) {
            HarnessProtocol.DSH -> HarnessConfig.Dsh(
              launchCommand = host.launchCommand,
              workingDirectory = host.workingDirectory
            )
            HarnessProtocol.OPENCODE_NATIVE -> HarnessConfig.OpenCodeNative(entry.sshServicePath)
            HarnessProtocol.OPENCODE_ACP -> HarnessConfig.OpenCodeAcp
            HarnessProtocol.CODEX -> HarnessConfig.Codex
            HarnessProtocol.ANTIGRAVITY_ACP -> HarnessConfig.AntigravityAcp
          }
          val customLabel = entry.name.trim().takeIf { it.isNotBlank() && it != host.name }
          val harness = DeviceHarness(
            id = entry.id,
            deviceId = host.id,
            protocol = protocol,
            label = customLabel,
            enabled = entry.enabled,
            notifications = entry.notifications,
            config = config
          )
          sshHarnessesByDevice.getOrPut(host.id) { mutableListOf() }.add(harness)
          harnessIdToDeviceId[harness.id] = host.id
        }
      } else {
        retiredDirectIds += entry.id
      }
    }

    // 5. 构建并原子写入新 DeviceStore（若崩溃重启且已落盘，直接复用已落盘的设备集合继续清理）。
    val devices = if (deviceStore.hasStoredDevices()) {
      deviceStore.readStrict()
    } else {
      legacyHosts.map { host ->
        val attached = sshHarnessesByDevice[host.id].orEmpty().distinctBy { it.id }
        val enabled = graphInfo.sshEnabledByHostId[host.id] ?: true
        SshDevice(
          id = host.id,
          name = host.name,
          host = host.host,
          port = host.port,
          user = host.user,
          auth = host.auth,
          keyId = host.keyId,
          hostKeyType = host.hostKeyType,
          hostKeyFingerprint = host.hostKeyFingerprint,
          enabled = enabled,
          harnesses = attached
        )
      }.also { deviceStore.replaceAll(it) }
    }

    val survivingDeviceIds = devices.mapTo(HashSet()) { it.id }
    val survivingHarnessIds = devices.flatMapTo(HashSet()) { d -> d.harnesses.map { it.id } }
    devices.forEach { d -> d.harnesses.forEach { h -> harnessIdToDeviceId[h.id] = d.id } }

    // 6. 升级 DeviceGroupStore（v2/v3 → v4）：把旧 host-<sha256> 和 members 重映射到 SshDevice.id。
    migrateDeviceGroups(groupStore, oldHostIdToSshId, harnessIdToDeviceId, survivingDeviceIds)

    // 7. 升级 DeviceDiscoveryStore（v1 → v2）：把旧 host-<sha256> 键重映射到 SshDevice.id。
    discoveryStore.migrateFromLegacyHostIds(oldHostIdToSshId, survivingDeviceIds)

    // 8. 清理已下线 Remote / Direct leaf 的运行态与缓存数据（保留全部 SSH Harness 数据）。
    val retiredIds = (retiredRemoteIds + retiredDirectIds).distinct().filterNot { it in survivingHarnessIds }
    retiredIds.forEach { retiredId ->
      serverStore.cleanupRetiredHarness(retiredId)
      runCatching { offlineCache?.delete(retiredId) }
      runCatching { transcripts?.deleteServer(retiredId) }
      summaryDismissalsPrefs.edit().remove(retiredId).apply()
    }

    // 清理旧 profiles/journal/remoteMigration 键，并修正指向已下线 leaf 的选中项。
    val serverEditor = serversPrefs.edit()
      .remove("profiles")
      .remove("configuration-save-token")
    serversPrefs.all.keys.filter { it.startsWith("remoteMigration:") }.forEach(serverEditor::remove)
    val fallbackHarness = devices.filter { it.enabled }.flatMap { it.harnesses }.firstOrNull { it.enabled }?.id
    val currentSelected = serversPrefs.getString("selected", null)
    if (currentSelected.isNullOrBlank() || currentSelected !in survivingHarnessIds) {
      if (fallbackHarness != null) serverEditor.putString("selected", fallbackHarness)
      else serverEditor.remove("selected").remove("selectedProject").remove("selectedSession")
    }
    val currentHome = serversPrefs.getString("homeServer", null)
    if (currentHome.isNullOrBlank() || currentHome !in survivingHarnessIds) {
      if (fallbackHarness != null) serverEditor.putString("homeServer", fallbackHarness)
      else serverEditor.remove("homeServer")
    }
    serverEditor.commit()

    secretsPrefs.edit().remove("configuration-save-journal-v1").commit()

    // 清理已下线的旧 SP 与 Remote Keystore 别名。
    connectionGraphPrefs.edit().clear().commit()
    sshHostsPrefs.edit().clear().commit()
    retiredPrefs.forEach { runCatching { it.edit().clear().commit() } }
    runCatching { deleteRemoteKeystoreAlias() }

    val report = SshMigrationReport(
      migratedDevices = devices.size,
      migratedHarnesses = survivingHarnessIds.size,
      retiredRemoteCount = retiredRemoteIds.size,
      retiredDirectCount = retiredDirectIds.size
    )
    if (report.hasRetiredConnections) {
      serverStore.saveMigrationNotice(report.summaryText())
    }
    deviceStore.markMigrationVersion(TARGET_MIGRATION_VERSION)
    Diagnostics.info(
      "Migration",
      "纯 SSH 迁移完成：保留 ${report.migratedDevices} 台设备 / ${report.migratedHarnesses} 个 Harness，" +
        "清理 ${report.retiredRemoteCount} 条 Remote 与 ${report.retiredDirectCount} 条直连"
    )
    return report
  }

  private data class LegacyProfileEntry(
    val id: String,
    val name: String,
    val kind: String,
    val protocolId: String?,
    val sshHostId: String?,
    val sshServicePath: String?,
    val remoteHostId: String?,
    val enabled: Boolean,
    val notifications: Boolean
  )

  private data class LegacyGraphInfo(
    val sshEnabledByHostId: Map<String, Boolean>,
    val oldHostIdToSshId: Map<String, String>
  )

  private fun recoverLegacyConfigurationJournal(
    serversPrefs: SharedPreferences,
    secretsPrefs: SharedPreferences,
    decryptSecret: (String) -> String
  ) {
    val encrypted = secretsPrefs.getString("configuration-save-journal-v1", null) ?: return
    runCatching {
      val journal = JSONObject(decryptSecret(encrypted))
      val after = journal.opt("after") as? String
      val afterToken = journal.opt("afterToken") as? String
      val currentToken = serversPrefs.getString("configuration-save-token", null)
      val currentProfiles = serversPrefs.getString("profiles", null)
      // 若写入事务已在 serversPrefs 提交 token，但 profiles 滞后或为空，则补全 after 快照。
      if (after != null && afterToken != null && currentToken == afterToken && currentProfiles != after) {
        JSONArray(after) // 验证是合法 JSON 数组
        serversPrefs.edit().putString("profiles", after).commit()
      }
    }.onFailure {
      Diagnostics.warn("Migration", "忽略无法解密的旧配置保存日志，直接读取既有 profiles", it)
    }
  }

  private fun readLegacySshHosts(prefs: SharedPreferences): List<SshHostConfig> {
    val raw = prefs.getString("hosts", null) ?: return emptyList()
    return try {
      SshHostCodec.decode(raw).distinctBy { it.id }
    } catch (_: Exception) {
      runCatching {
        val array = JSONObject(raw).optJSONArray("hosts") ?: return@runCatching emptyList()
        (0 until array.length()).mapNotNull { idx ->
          array.optJSONObject(idx)?.let(SshHostConfig::fromJson)
        }.distinctBy { it.id }
      }.getOrDefault(emptyList())
    }
  }

  private fun readLegacyConnectionGraph(prefs: SharedPreferences): LegacyGraphInfo {
    val raw = prefs.getString("graph", null) ?: return LegacyGraphInfo(emptyMap(), emptyMap())
    return runCatching {
      val root = JSONObject(raw)
      val sourcesById = HashMap<String, Pair<String, Boolean>>() // sourceId -> (sshHostConfigId, enabled)
      val sources = root.optJSONArray("sources") ?: JSONArray()
      for (i in 0 until sources.length()) {
        val item = sources.optJSONObject(i) ?: continue
        if (item.optString("type") != "ssh") continue
        val sourceId = item.optString("id").takeIf(String::isNotBlank) ?: continue
        val sshId = item.optString("sshHostConfigId").takeIf(String::isNotBlank) ?: continue
        sourcesById[sourceId] = sshId to item.optBoolean("enabled", true)
      }
      val enabledMap = HashMap<String, Boolean>()
      val oldIdMap = HashMap<String, String>()
      val hosts = root.optJSONArray("hosts") ?: JSONArray()
      for (i in 0 until hosts.length()) {
        val item = hosts.optJSONObject(i) ?: continue
        val sourceId = item.optString("sourceId")
        val (sshId, sourceEnabled) = sourcesById[sourceId] ?: continue
        val hostBindingId = item.optString("id").takeIf(String::isNotBlank)
        if (hostBindingId != null) oldIdMap[hostBindingId] = sshId
        val hostEnabled = item.optBoolean("enabled", true)
        enabledMap[sshId] = sourceEnabled && hostEnabled
      }
      // 即使没有 HostBinding 行，也保留被停用的 SSH source 开关。
      sourcesById.values.forEach { (sshId, sourceEnabled) ->
        if (sshId !in enabledMap) enabledMap[sshId] = sourceEnabled
      }
      LegacyGraphInfo(enabledMap, oldIdMap)
    }.getOrDefault(LegacyGraphInfo(emptyMap(), emptyMap()))
  }

  private fun readLegacyProfiles(prefs: SharedPreferences): List<LegacyProfileEntry> {
    val raw = prefs.getString("profiles", null) ?: return emptyList()
    return runCatching {
      val array = JSONArray(raw)
      (0 until array.length()).mapNotNull { idx ->
        val item = array.optJSONObject(idx) ?: return@mapNotNull null
        val id = item.optString("id").takeIf { it.isNotBlank() && it.length <= 512 && '\u0000' !in it }
          ?: return@mapNotNull null
        LegacyProfileEntry(
          id = id,
          name = item.optString("name"),
          kind = if (!item.has("kind")) "opencode" else item.optString("kind"),
          protocolId = item.optString("protocolId").takeIf { it.isNotBlank() && it != "null" },
          sshHostId = item.optString("sshHostId").takeIf { it.isNotBlank() && it != "null" },
          sshServicePath = item.optString("sshServicePath").takeIf { it.isNotBlank() && it != "null" },
          remoteHostId = item.optString("remoteHostId").takeIf { it.isNotBlank() && it != "null" },
          enabled = item.optBoolean("enabled", true),
          notifications = item.optBoolean("notifications", true)
        )
      }.distinctBy { it.id }
    }.getOrDefault(emptyList())
  }

  private fun resolveProtocol(kindWire: String, protocolWire: String?): HarnessProtocol? {
    HarnessProtocol.fromWireOrNull(protocolWire)?.let { return it }
    return when (kindWire.trim().lowercase()) {
      "dsh" -> HarnessProtocol.DSH
      "opencode" -> HarnessProtocol.OPENCODE_NATIVE
      "codex" -> HarnessProtocol.CODEX
      "antigravity-acp" -> HarnessProtocol.ANTIGRAVITY_ACP
      "opencode-acp" -> HarnessProtocol.OPENCODE_ACP
      else -> null
    }
  }

  private fun migrateDeviceGroups(
    groupStore: DeviceGroupStore,
    oldHostIdToSshId: Map<String, String>,
    harnessIdToDeviceId: Map<String, String>,
    survivingDeviceIds: Set<String>
  ) {
    val loaded = groupStore.load().state
    if (loaded.groups.isEmpty() && loaded.selectedGroupId == null) return
    val remappedGroups = loaded.groups.map { group ->
      val mappedFromDevices = group.deviceIds.mapNotNull { oldId ->
        oldHostIdToSshId[oldId] ?: oldId.takeIf { it in survivingDeviceIds }
      }
      val mappedFromMembers = group.legacyMemberIds.mapNotNull { harnessIdToDeviceId[it] }
      val merged = (mappedFromDevices + mappedFromMembers).filter { it in survivingDeviceIds }.distinct()
      group.copy(deviceIds = merged, legacyMemberIds = emptyList())
    }
    val next = DeviceGroupState(remappedGroups, loaded.selectedGroupId).sanitized(survivingDeviceIds)
    runCatching { groupStore.save(next) }
      .onFailure { Diagnostics.warn("Migration", "升级设备分组到 v4 失败", it) }
  }

  /** 与旧版 `MigrationProjection.hostId(sshSourceId(sshId), sshId)` 逐字节一致，用于确定性重映射。 */
  internal fun legacyHostId(sshId: String): String {
    val sourceId = stableId("source-ssh", sshId)
    return stableId("host", sourceId, sshId)
  }

  private fun stableId(namespace: String, vararg parts: String): String {
    val input = parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
    return "$namespace-${MessageDigest.getInstance("SHA-256").digest(input).joinToString("") { "%02x".format(it) }}"
  }

  private fun deleteAndroidKeystoreAlias(alias: String) {
    val ks = KeyStore.getInstance("AndroidKeyStore")
    ks.load(null)
    if (ks.containsAlias(alias)) ks.deleteEntry(alias)
  }

  private val RETIRED_PREFS = listOf(
    "remote-catalog",
    "remote-device-identities",
    "remote-host-pins",
    "remote-compactions-v1",
    "remote-pairing-recovery"
  )
}
