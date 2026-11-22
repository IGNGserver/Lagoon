package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * 设备的稳定身份（等于 [SshDevice.id]，形如 `ssh-<uuid>`）。
 * 它和 Harness 的 [HarnessId] 属于不同命名空间：不要互相赋值或比较。见 `docs/DEVICE_MODEL.md`。
 */
@JvmInline
value class DeviceId(val value: String) {
  override fun toString(): String = value
}

/** 一条 Harness 连接的稳定身份（等于 [DeviceHarness.id] / [ServerProfile.id]）。永不重编号。 */
@JvmInline
value class HarnessId(val value: String) {
  override fun toString(): String = value
}

/** 受支持的 5 种原生 Harness 协议。 */
enum class HarnessProtocol(val wire: String, val label: String) {
  DSH("dsh-typert", "DeepSeek Harness"),
  OPENCODE_NATIVE("opencode-native", "OpenCode"),
  OPENCODE_ACP("opencode-acp", "OpenCode ACP"),
  CODEX("codex-app-server", "Codex"),
  ANTIGRAVITY_ACP("antigravity-acp", "Antigravity");

  val sortOrder: Int
    get() = when (this) {
      DSH -> 0
      OPENCODE_NATIVE -> 1
      OPENCODE_ACP -> 2
      CODEX -> 3
      ANTIGRAVITY_ACP -> 4
    }

  val isStdioRpc: Boolean
    get() = this == CODEX || this == ANTIGRAVITY_ACP || this == OPENCODE_ACP

  val isAcp: Boolean
    get() = this == ANTIGRAVITY_ACP || this == OPENCODE_ACP

  companion object {
    fun fromWire(value: String): HarnessProtocol = fromWireOrNull(value)
      ?: throw IllegalArgumentException("Unknown harness protocol: $value")

    fun fromWireOrNull(value: String?): HarnessProtocol? {
      val trimmed = value?.trim()?.lowercase() ?: return null
      return entries.firstOrNull { it.wire == trimmed }
    }
  }
}

enum class DiscoveryValue(val wire: String) {
  UNKNOWN("unknown"),
  YES("yes"),
  NO("no"),
  UNSUPPORTED("unsupported");

  companion object {
    fun fromWire(value: String): DiscoveryValue = entries.firstOrNull { it.wire == value }
      ?: throw IllegalArgumentException("Unknown discovery value: $value")
  }
}

enum class DiscoveryCompleteness(val wire: String) {
  UNKNOWN("unknown"),
  PARTIAL("partial"),
  COMPLETE("complete");

  companion object {
    fun fromWire(value: String): DiscoveryCompleteness = entries.firstOrNull { it.wire == value }
      ?: throw IllegalArgumentException("Unknown discovery completeness: $value")
  }
}

enum class DiscoveryFreshness { UNKNOWN, FRESH, STALE }

/** 只读探测观测结果，不代表用户开关，也不自动安装或启动远端服务。 */
data class HarnessDiscoveryStatus(
  val installed: DiscoveryValue = DiscoveryValue.UNKNOWN,
  val configured: DiscoveryValue = DiscoveryValue.UNKNOWN,
  val listening: DiscoveryValue = DiscoveryValue.UNKNOWN,
  val authenticated: DiscoveryValue = DiscoveryValue.UNKNOWN,
  val compatible: DiscoveryValue = DiscoveryValue.UNKNOWN,
  val available: DiscoveryValue = DiscoveryValue.UNKNOWN,
  val observedAtMs: Long? = null,
  val freshUntilMs: Long? = null,
  val completeness: DiscoveryCompleteness = DiscoveryCompleteness.UNKNOWN,
  val reason: String? = null
) {
  init {
    require((observedAtMs == null) == (freshUntilMs == null)) { "Observation timestamps must be paired" }
    require(observedAtMs == null || (observedAtMs >= 0 && freshUntilMs!! >= observedAtMs)) { "Invalid observation timestamps" }
    require(completeness == DiscoveryCompleteness.UNKNOWN || observedAtMs != null) { "A scan needs an observation time" }
    require(available != DiscoveryValue.YES || (observedAtMs != null &&
      listening == DiscoveryValue.YES && authenticated == DiscoveryValue.YES && compatible == DiscoveryValue.YES &&
      installed != DiscoveryValue.NO && configured != DiscoveryValue.NO)) {
      "Availability requires verified listening/authentication/compatibility"
    }
  }

  fun freshness(nowMs: Long): DiscoveryFreshness = when {
    observedAtMs == null || nowMs < observedAtMs -> DiscoveryFreshness.UNKNOWN
    nowMs > freshUntilMs!! -> DiscoveryFreshness.STALE
    else -> DiscoveryFreshness.FRESH
  }

  fun availableNow(nowMs: Long): Boolean = available == DiscoveryValue.YES && freshness(nowMs) == DiscoveryFreshness.FRESH
}

/** 协议专属配置，按 Harness 单独归属，不再污染 SSH 设备级配置。 */
sealed interface HarnessConfig {
  data class Dsh(
    val launchCommand: String = DEFAULT_DSH_LAUNCH_COMMAND,
    val workingDirectory: String? = null
  ) : HarnessConfig {
    val usesDefaultCommand: Boolean get() = launchCommand.trim() == DEFAULT_DSH_LAUNCH_COMMAND
  }

  data class OpenCodeNative(
    /** OpenCode 注册文件路径；null 表示使用远端默认路径。 */
    val registrationPath: String? = null
  ) : HarnessConfig

  data object OpenCodeAcp : HarnessConfig
  data object Codex : HarnessConfig
  data object AntigravityAcp : HarnessConfig

  val locator: String?
    get() = (this as? OpenCodeNative)?.registrationPath

  companion object {
    fun defaultFor(protocol: HarnessProtocol): HarnessConfig = when (protocol) {
      HarnessProtocol.DSH -> Dsh()
      HarnessProtocol.OPENCODE_NATIVE -> OpenCodeNative()
      HarnessProtocol.OPENCODE_ACP -> OpenCodeAcp
      HarnessProtocol.CODEX -> Codex
      HarnessProtocol.ANTIGRAVITY_ACP -> AntigravityAcp
    }
  }
}

/** 一台 SSH 设备上挂载的单个 Harness。 */
data class DeviceHarness(
  val id: String,
  val deviceId: String,
  val protocol: HarnessProtocol,
  /** 可选自定义展示名；留空时由设备名与协议派生。 */
  val label: String? = null,
  val enabled: Boolean = true,
  val notifications: Boolean = true,
  val config: HarnessConfig = HarnessConfig.defaultFor(protocol)
) {
  init {
    require(id.isNotBlank() && id.length <= 512 && '\u0000' !in id) { "Invalid Harness ID" }
    require(deviceId.isNotBlank() && deviceId.length <= 128 && '\u0000' !in deviceId) { "Invalid Device ID" }
    when (protocol) {
      HarnessProtocol.DSH -> require(config is HarnessConfig.Dsh) { "DSH Harness requires Dsh config" }
      HarnessProtocol.OPENCODE_NATIVE -> require(config is HarnessConfig.OpenCodeNative) { "OpenCode Native requires OpenCodeNative config" }
      HarnessProtocol.OPENCODE_ACP -> require(config is HarnessConfig.OpenCodeAcp) { "OpenCode ACP requires OpenCodeAcp config" }
      HarnessProtocol.CODEX -> require(config is HarnessConfig.Codex) { "Codex requires Codex config" }
      HarnessProtocol.ANTIGRAVITY_ACP -> require(config is HarnessConfig.AntigravityAcp) { "Antigravity requires AntigravityAcp config" }
    }
  }

  val locator: String? get() = config.locator
  val slotKey: String get() = DeviceProjection.slotKey(protocol, locator)

  fun displayName(deviceName: String): String =
    label?.takeIf(String::isNotBlank) ?: deviceName.ifBlank { protocol.label }

  fun toProfile(device: SshDevice): ServerProfile = ServerProfile(
    id = id,
    name = displayName(device.name),
    url = device.profileUrl(),
    username = device.user,
    notifications = notifications,
    allowCleartext = false,
    protocol = protocol,
    deviceId = device.id,
    deviceName = device.name,
    enabled = enabled,
    deviceEnabled = device.enabled,
    config = config
  )

  fun toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("protocol", protocol.wire)
    .putOpt("label", label?.takeIf(String::isNotBlank))
    .put("enabled", enabled)
    .put("notifications", notifications)
    .put("config", configJson())

  private fun configJson(): JSONObject = when (val c = config) {
    is HarnessConfig.Dsh -> JSONObject()
      .put("launchCommand", c.launchCommand)
      .putOpt("workingDirectory", c.workingDirectory?.takeIf(String::isNotBlank))
    is HarnessConfig.OpenCodeNative -> JSONObject()
      .putOpt("registrationPath", c.registrationPath?.takeIf(String::isNotBlank))
    HarnessConfig.OpenCodeAcp, HarnessConfig.Codex, HarnessConfig.AntigravityAcp -> JSONObject()
  }

  companion object {
    fun newId(): String = UUID.randomUUID().toString()

    fun fromJson(deviceId: String, raw: JSONObject): DeviceHarness? {
      val id = raw.optString("id").takeIf { it.isNotBlank() && it.length <= 512 && '\u0000' !in it } ?: return null
      val protocol = HarnessProtocol.fromWireOrNull(raw.optString("protocol")) ?: return null
      val label = (raw.opt("label") as? String)?.trim()?.takeIf { it.isNotBlank() && it.length <= 128 }
      val enabled = raw.optBoolean("enabled", true)
      val notifications = raw.optBoolean("notifications", true)
      val cfgObj = raw.optJSONObject("config") ?: JSONObject()
      val config: HarnessConfig = when (protocol) {
        HarnessProtocol.DSH -> HarnessConfig.Dsh(
          launchCommand = (cfgObj.opt("launchCommand") as? String)?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= 512 } ?: DEFAULT_DSH_LAUNCH_COMMAND,
          workingDirectory = (cfgObj.opt("workingDirectory") as? String)?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= 1024 }
        )
        HarnessProtocol.OPENCODE_NATIVE -> HarnessConfig.OpenCodeNative(
          registrationPath = (cfgObj.opt("registrationPath") as? String)?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= 4096 }
        )
        HarnessProtocol.OPENCODE_ACP -> HarnessConfig.OpenCodeAcp
        HarnessProtocol.CODEX -> HarnessConfig.Codex
        HarnessProtocol.ANTIGRAVITY_ACP -> HarnessConfig.AntigravityAcp
      }
      return DeviceHarness(id, deviceId, protocol, label, enabled, notifications, config)
    }
  }
}

/**
 * 唯一真源的 SSH 设备模型。密码/私钥等机密仅存放在加密的 `ssh-secrets` 与 `ssh-key-secrets` 中。
 */
data class SshDevice(
  val id: String,
  val name: String,
  val host: String,
  val port: Int = SSH_DEFAULT_PORT,
  val user: String,
  val auth: SshAuthMethod = SshAuthMethod.PASSWORD,
  val keyId: String? = null,
  val hostKeyType: String? = null,
  val hostKeyFingerprint: String? = null,
  val enabled: Boolean = true,
  val harnesses: List<DeviceHarness> = emptyList()
) {
  init {
    require(id.isNotBlank() && id.length <= 128 && '\u0000' !in id) { "Invalid device ID" }
    require(host.isNotBlank() && host.length <= 512) { "Invalid SSH host" }
    require(port in 1..65535) { "Invalid SSH port" }
    require(user.isNotBlank() && user.length <= 128) { "Invalid SSH user" }
    require(harnesses.all { it.deviceId == id }) { "Harness deviceId mismatch" }
    require(harnesses.distinctBy { it.id }.size == harnesses.size) { "Duplicate Harness IDs on device" }
  }

  private val displayHost: String
    get() = host.trim().let { if (it.contains(':') && !it.startsWith("[")) "[$it]" else it }

  val address: String
    get() = if (port == SSH_DEFAULT_PORT) "${user.trim()}@$displayHost" else "${user.trim()}@$displayHost:$port"

  /** 稳定的 `ssh://user@host:port` 展示与缓存身份，永不作为 HTTP origin 拨号。 */
  fun profileUrl(): String = "ssh://${user.trim()}@$displayHost:$port"

  /**
   * 底层 SSH 传输连接键：只包含地址、端口、用户、认证方式与密钥 ID。
   * 刻意不含任何 Harness 专属配置（如 DSH 启动命令），避免改一个 Harness 踢掉同设备上其他 SSH 会话。
   */
  val transportKey: String
    get() = listOf(host.trim(), port, user.trim(), auth.name, keyId.orEmpty()).joinToString("\u0000")

  fun dshConfig(harnessId: String? = null): HarnessConfig.Dsh {
    val target = if (harnessId != null) harnesses.firstOrNull { it.id == harnessId }
    else harnesses.firstOrNull { it.protocol == HarnessProtocol.DSH }
    return (target?.config as? HarnessConfig.Dsh) ?: HarnessConfig.Dsh()
  }

  /** 投影为底层 SSH 传输层使用的 [SshHostConfig]。 */
  fun toHostConfig(dsh: HarnessConfig.Dsh = dshConfig()): SshHostConfig = SshHostConfig(
    id = id,
    name = name,
    host = host,
    port = port,
    user = user,
    auth = auth,
    launchCommand = dsh.launchCommand,
    workingDirectory = dsh.workingDirectory,
    hostKeyType = hostKeyType,
    hostKeyFingerprint = hostKeyFingerprint,
    keyId = keyId
  )

  fun toProfiles(): List<ServerProfile> = harnesses.map { it.toProfile(this) }

  fun copyEndpoint(
    name: String? = null,
    host: String? = null,
    port: Int? = null,
    user: String? = null,
    auth: SshAuthMethod? = null,
    keyId: String? = this.keyId,
    clearKeyId: Boolean = false,
    hostKeyType: String? = this.hostKeyType,
    hostKeyFingerprint: String? = this.hostKeyFingerprint,
    clearHostKey: Boolean = false
  ): SshDevice {
    val moved = (host != null && host != this.host) || (port != null && port != this.port)
    return copy(
      name = name ?: this.name,
      host = host ?: this.host,
      port = port ?: this.port,
      user = user ?: this.user,
      auth = auth ?: this.auth,
      keyId = if (clearKeyId) null else keyId,
      hostKeyType = if (clearHostKey || moved) null else hostKeyType,
      hostKeyFingerprint = if (clearHostKey || moved) null else hostKeyFingerprint
    )
  }

  fun toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("host", host)
    .put("port", port)
    .put("user", user)
    .put("auth", auth.name)
    .putOpt("keyId", keyId)
    .putOpt("hostKeyType", hostKeyType)
    .putOpt("hostKeyFingerprint", hostKeyFingerprint)
    .put("enabled", enabled)
    .put("harnesses", JSONArray().apply { harnesses.forEach { put(it.toJson()) } })

  companion object {
    fun newId(): String = "ssh-${UUID.randomUUID()}"

    fun fromJson(raw: JSONObject): SshDevice? {
      val id = raw.optString("id").takeIf { it.isNotBlank() && it.length <= 128 && '\u0000' !in it } ?: return null
      val host = raw.optString("host").trim().takeIf { it.isNotBlank() && it.length <= 512 } ?: return null
      val user = raw.optString("user").trim().takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
      val auth = SshAuthMethod.entries.firstOrNull { it.name == raw.optString("auth") } ?: SshAuthMethod.PASSWORD
      val port = raw.optInt("port", SSH_DEFAULT_PORT).takeIf { it in 1..65535 } ?: SSH_DEFAULT_PORT
      val name = raw.optString("name").trim().takeIf { it.isNotBlank() && it.length <= 128 } ?: host
      val enabled = raw.optBoolean("enabled", true)
      val harnessArr = raw.optJSONArray("harnesses") ?: JSONArray()
      if (harnessArr.length() > 64) return null
      val harnesses = (0 until harnessArr.length()).mapNotNull { index ->
        harnessArr.optJSONObject(index)?.let { DeviceHarness.fromJson(id, it) }
      }.distinctBy { it.id }
      return SshDevice(
        id = id,
        name = name,
        host = host,
        port = port,
        user = user,
        auth = auth,
        keyId = (raw.opt("keyId") as? String)?.trim()?.takeIf { it.isNotBlank() && it.length <= 128 },
        hostKeyType = (raw.opt("hostKeyType") as? String)?.trim()?.takeIf { it.isNotBlank() && it.length <= 64 },
        hostKeyFingerprint = (raw.opt("hostKeyFingerprint") as? String)?.trim()?.takeIf { it.isNotBlank() && it.length <= 128 },
        enabled = enabled,
        harnesses = harnesses
      )
    }
  }
}

internal object SshDeviceCodec {
  const val VERSION = 2

  fun encode(devices: List<SshDevice>): String = JSONObject()
    .put("version", VERSION)
    .put("devices", JSONArray().apply { devices.forEach { put(it.toJson()) } })
    .toString()

  fun version(text: String): Int? = runCatching { JSONObject(text).optInt("version", 0) }.getOrNull()

  fun decode(text: String): List<SshDevice> {
    try {
      val json = JSONObject(text)
      if (json.optInt("version") != VERSION) throw IOException("设备配置版本无效")
      val array = json.optJSONArray("devices") ?: JSONArray()
      if (array.length() > 256) throw IOException("设备配置过多")
      val devices = (0 until array.length()).map { index ->
        val obj = array.optJSONObject(index) ?: throw IOException("设备记录格式无效")
        SshDevice.fromJson(obj) ?: throw IOException("设备记录字段无效")
      }
      if (devices.distinctBy { it.id }.size != devices.size) throw IOException("设备 ID 重复")
      val allHarnessIds = devices.flatMap { d -> d.harnesses.map { it.id } }
      if (allHarnessIds.distinct().size != allHarnessIds.size) throw IOException("Harness ID 重复")
      return devices
    } catch (error: IOException) {
      throw error
    } catch (error: Exception) {
      throw IOException("设备配置损坏", error)
    }
  }

  /** 宽容恢复：单条损坏时不清空整张列表。 */
  fun recover(text: String): List<SshDevice> = runCatching {
    val array = JSONObject(text).optJSONArray("devices") ?: return@runCatching emptyList()
    val seenHarness = HashSet<String>()
    (0 until minOf(array.length(), 256)).mapNotNull { index ->
      val device = array.optJSONObject(index)?.let(SshDevice::fromJson) ?: return@mapNotNull null
      device.copy(harnesses = device.harnesses.filter { seenHarness.add(it.id) })
    }.distinctBy { it.id }
  }.getOrDefault(emptyList())
}

/** 一个可挂载的 Harness 槽位：协议 + locator 唯一确定，最多对应一条 Harness。 */
data class HarnessSlot(
  val key: String,
  val protocol: HarnessProtocol,
  val locator: String? = null,
  val name: String,
  val harnessId: HarnessId? = null,
  val attached: Boolean = false,
  val enabled: Boolean = false,
  val online: Boolean = false,
  val linkStatus: SshLinkStatus? = null,
  val availability: HarnessDiscoveryStatus = HarnessDiscoveryStatus(),
  /** 用户能不能现在把它打开：已挂载（可关）、观测到可用，或 DSH/OpenCode 恒可挂载。 */
  val attachable: Boolean = false,
  /** 未验证、过期或不可用时的一句话说明。 */
  val reason: String = ""
) {
  val effectiveEnabled: Boolean get() = attached && enabled
}

/**
 * 一台 SSH 设备的展示投影。**界面里的“设备”永远是它**，不是任何一条 Harness。
 */
data class DeviceView(
  val id: DeviceId,
  val device: SshDevice,
  val name: String,
  val endpoint: String,
  val hostKeyType: String? = null,
  val fingerprint: String? = null,
  val enabled: Boolean,
  val harnesses: List<HarnessSlot> = emptyList(),
  val discovery: DeviceDiscovery? = null,
  val online: Boolean = false,
  val aggregateStatus: SshLinkStatus? = null
) {
  val attachedCount: Int get() = harnesses.count { it.attached }
  val enabledCount: Int get() = harnesses.count { it.effectiveEnabled }
  val effectiveEnabled: Boolean get() = enabled

  val blockedReason: String
    get() = if (!enabled) "此设备已停用" else ""

  fun slot(protocol: HarnessProtocol, locator: String? = null): HarnessSlot? =
    harnesses.firstOrNull { it.protocol == protocol && it.locator == locator }
}

/** 一台设备上的一条发现结果；观测，不是连接许可。 */
data class DiscoveredHarness(
  val protocol: HarnessProtocol,
  val locator: String? = null,
  val name: String,
  val status: HarnessDiscoveryStatus = HarnessDiscoveryStatus(),
  val detail: String = ""
)

/** 一次设备发现的结果，带观测时间与完整度。 */
data class DeviceDiscovery(
  val deviceId: String,
  val observedAtMs: Long,
  val harnesses: List<DiscoveredHarness>,
  val completeness: DiscoveryCompleteness = DiscoveryCompleteness.COMPLETE,
  val note: String? = null
) {
  fun isFresh(nowMs: Long, ttlMs: Long = DEFAULT_TTL_MS): Boolean =
    observedAtMs in 1..nowMs && nowMs - observedAtMs <= ttlMs

  fun harness(protocol: HarnessProtocol, locator: String? = null): DiscoveredHarness? =
    harnesses.firstOrNull { it.protocol == protocol && it.locator == locator }

  companion object {
    const val DEFAULT_TTL_MS: Long = 10 * 60 * 1000
  }
}

/**
 * 纯投影：`List<SshDevice>` + 发现缓存 + 运行态 → 设备视图列表。不读存储、不拨号、不写任何东西。
 */
object DeviceProjection {
  fun devices(
    devices: List<SshDevice>,
    discovery: (String) -> DeviceDiscovery? = { null },
    linkStatus: (String) -> SshLinkStatus? = { null },
    isOnline: (String) -> Boolean = { false },
    nowMs: Long = System.currentTimeMillis()
  ): List<DeviceView> = devices.map { device ->
    val observed = discovery(device.id)
    val displayName = device.name.ifBlank { device.address }
    val slots = slots(device, observed, linkStatus, isOnline, nowMs, displayName)
    val enabledStatuses = slots.filter { it.effectiveEnabled }.mapNotNull { it.linkStatus }
    val aggregate = aggregateStatus(device.enabled, enabledStatuses)
    DeviceView(
      id = DeviceId(device.id),
      device = device,
      name = displayName,
      endpoint = device.address,
      hostKeyType = device.hostKeyType,
      fingerprint = device.hostKeyFingerprint,
      enabled = device.enabled,
      harnesses = slots,
      discovery = observed,
      online = device.enabled && slots.any { it.effectiveEnabled && it.online },
      aggregateStatus = aggregate
    )
  }.sortedWith(compareBy({ it.name.lowercase() }, { it.id.value }))

  fun slotKey(protocol: HarnessProtocol, locator: String?): String = "${protocol.wire}|${locator.orEmpty()}"

  fun slotKey(profile: ServerProfile): String = slotKey(profile.protocol, profile.sshServicePath)

  fun aggregateStatus(deviceEnabled: Boolean, statuses: List<SshLinkStatus>): SshLinkStatus? {
    if (!deviceEnabled || statuses.isEmpty()) return null
    statuses.firstOrNull { it.phase == SshLinkPhase.FAILED }?.let { return it }
    statuses.firstOrNull { it.phase == SshLinkPhase.CONNECTING || it.phase == SshLinkPhase.RECONNECTING }?.let { return it }
    statuses.firstOrNull { it.phase == SshLinkPhase.CONNECTED }?.let { return it }
    return statuses.firstOrNull()
  }

  private fun slots(
    device: SshDevice,
    discovery: DeviceDiscovery?,
    linkStatus: (String) -> SshLinkStatus?,
    isOnline: (String) -> Boolean,
    nowMs: Long,
    deviceName: String
  ): List<HarnessSlot> {
    val slots = LinkedHashMap<String, HarnessSlot>()
    val attached = device.harnesses
    val rawObservations = discovery?.harnesses.orEmpty()
    val defaultObservation = rawObservations.filter { it.protocol == HarnessProtocol.OPENCODE_NATIVE }.singleOrNull()
    val normalizeOldDefault = defaultObservation?.locator != null &&
      attached.any { it.protocol == HarnessProtocol.OPENCODE_NATIVE && it.locator == null } &&
      attached.none { it.protocol == HarnessProtocol.OPENCODE_NATIVE && it.locator == defaultObservation.locator }
    val observations = rawObservations.map {
      if (normalizeOldDefault && it === defaultObservation) it.copy(locator = null) else it
    }
    fun put(slot: HarnessSlot) {
      val existing = slots[slot.key]
      if (existing == null || (slot.attached && !existing.attached)) slots[slot.key] = slot
    }
    attached.forEach { harness ->
      val protocol = harness.protocol
      val locator = harness.locator
      val matched = observations.firstOrNull { it.protocol == protocol && it.locator == locator }
      val fresh = discovery?.isFresh(nowMs) == true
      val attachedReason = when {
        discovery == null -> ""
        matched != null && (!fresh || !attachable(matched.status, nowMs) ||
          matched.status.completeness != DiscoveryCompleteness.COMPLETE) ->
          if (!fresh) "上次发现已过期"
          else matched.status.reason?.takeIf(String::isNotBlank)
            ?: matched.detail.ifBlank { unavailableReason(matched.status, true) }
        else -> ""
      }
      val slotName = harness.label?.takeUnless { it.isBlank() || it == deviceName } ?: protocol.label
      put(HarnessSlot(
        key = slotKey(protocol, locator),
        protocol = protocol,
        locator = locator,
        name = slotName,
        harnessId = HarnessId(harness.id),
        attached = true,
        enabled = harness.enabled,
        online = device.enabled && harness.enabled && isOnline(harness.id),
        linkStatus = if (device.enabled && harness.enabled) linkStatus(harness.id) else null,
        availability = matched?.status ?: HarnessDiscoveryStatus(),
        attachable = true,
        reason = attachedReason
      ))
    }
    observations.forEach { observed ->
      val key = slotKey(observed.protocol, observed.locator)
      if (slots.containsKey(key)) return@forEach
      val fresh = discovery?.isFresh(nowMs) == true
      val canAttach = attachable(observed.status, nowMs)
      put(HarnessSlot(
        key = key,
        protocol = observed.protocol,
        locator = observed.locator,
        name = observed.name.ifBlank { observed.protocol.label },
        availability = observed.status,
        attachable = canAttach,
        reason = when {
          !fresh -> "上次发现已过期"
          !canAttach -> observed.status.reason?.takeIf(String::isNotBlank)
            ?: observed.detail.ifBlank { unavailableReason(observed.status, true) }
          observed.status.completeness != DiscoveryCompleteness.COMPLETE -> observed.status.reason?.takeIf(String::isNotBlank)
            ?: "尚未完成检测，可尝试连接"
          observed.status.available == DiscoveryValue.UNKNOWN ->
            observed.status.reason?.takeIf(String::isNotBlank) ?: "已发现，连接后才能确认可用"
          else -> ""
        }
      ))
    }
    ALWAYS_CANDIDATES.forEach { (protocol, locator, name) ->
      if (slots.values.none { it.protocol == protocol }) {
        put(HarnessSlot(
          key = slotKey(protocol, locator),
          protocol = protocol,
          locator = locator,
          name = name,
          attachable = true,
          reason = when {
            protocol == HarnessProtocol.OPENCODE_NATIVE ->
              if (discovery == null) "尚未发现；添加可先保存，再一键启动服务"
              else "未发现运行中的 OpenCode 服务；添加后可一键启动"
            discovery == null -> "尚未发现；启用后会通过 SSH 连接"
            else -> "未检测到此 Harness"
          }
        ))
      }
    }
    return slots.values.sortedWith(compareBy({ it.protocol.sortOrder }, { it.name }))
  }

  private val ALWAYS_CANDIDATES = listOf(
    Triple(HarnessProtocol.DSH, null, HarnessProtocol.DSH.label),
    Triple(HarnessProtocol.OPENCODE_NATIVE, null, HarnessProtocol.OPENCODE_NATIVE.label)
  )

  private fun attachable(status: HarnessDiscoveryStatus, nowMs: Long): Boolean =
    status.freshness(nowMs) != DiscoveryFreshness.FRESH ||
      (status.installed != DiscoveryValue.NO && status.configured != DiscoveryValue.NO &&
        status.compatible != DiscoveryValue.NO && status.available != DiscoveryValue.NO)

  private fun unavailableReason(status: HarnessDiscoveryStatus, fresh: Boolean): String = when {
    !fresh -> "上次发现已过期"
    status.completeness == DiscoveryCompleteness.UNKNOWN -> "未检测"
    status.installed == DiscoveryValue.NO -> "未安装"
    status.listening == DiscoveryValue.NO -> "未运行"
    status.authenticated == DiscoveryValue.NO -> "未认证"
    status.compatible == DiscoveryValue.NO -> "版本不兼容"
    status.available == DiscoveryValue.NO -> status.reason?.takeIf { it.isNotBlank() } ?: "不可用"
    else -> status.reason.orEmpty()
  }
}
