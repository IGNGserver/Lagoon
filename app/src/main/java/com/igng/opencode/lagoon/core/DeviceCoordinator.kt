package com.igng.opencode.lagoon.core

import androidx.compose.runtime.Immutable
import com.igng.opencode.lagoon.core.ssh.SshDeviceDiscovery
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Immutable
data class DeviceDomainState(
  val devices: List<SshDevice> = emptyList(),
  val profiles: List<ServerProfile> = emptyList(),
  val deviceGroups: List<DeviceGroup> = emptyList(),
  val selectedGroupId: String? = null,
  val deviceGroupReset: Boolean = false,
  val discoveryRevision: Int = 0,
  val storeError: String? = null
)

/**
 * 唯一真源的设备领域协调器：拥有 [DeviceStore]、[DeviceDiscoveryStore] 与 [DeviceGroupStore]。
 *
 * 所有设备、Harness、TOFU 主机指纹、设备发现缓存与设备组变更均通过本类原子落盘，
 * 分组清理始终按设备 ID 集合（[SshDevice.id]）校准，彻底消除跨 Store 漂移与分组误删问题。
 */
internal class DeviceCoordinator(
  private val deviceStore: DeviceStore,
  private val discoveryStore: DeviceDiscoveryStore,
  private val groupStore: DeviceGroupStore
) {
  private val mutableState = MutableStateFlow(loadInitial())
  val state: StateFlow<DeviceDomainState> = mutableState.asStateFlow()

  private fun loadInitial(): DeviceDomainState {
    val devices = deviceStore.load()
    val validDeviceIds = devices.mapTo(HashSet()) { it.id }
    val groupLoad = groupStore.load()
    val cleanGroups = groupLoad.state.sanitized(validDeviceIds)
    if (cleanGroups != groupLoad.state) {
      runCatching { groupStore.save(cleanGroups) }
        .onFailure { Diagnostics.warn("DeviceCoordinator", "启动时清理设备分组失败", it) }
    }
    return DeviceDomainState(
      devices = devices,
      profiles = devices.flatMap { it.toProfiles() },
      deviceGroups = cleanGroups.groups,
      selectedGroupId = cleanGroups.selectedGroupId,
      deviceGroupReset = groupLoad.resetLegacy,
      discoveryRevision = 0,
      storeError = deviceStore.loadError
    )
  }

  private fun publish(
    devices: List<SshDevice> = deviceStore.load(),
    groupState: DeviceGroupState? = null,
    bumpDiscovery: Boolean = false,
    resetFlag: Boolean = mutableState.value.deviceGroupReset
  ): DeviceDomainState {
    val validDeviceIds = devices.mapTo(HashSet()) { it.id }
    val currentGroups = groupState ?: DeviceGroupState(mutableState.value.deviceGroups, mutableState.value.selectedGroupId)
    val cleanGroups = currentGroups.sanitized(validDeviceIds)
    if (cleanGroups != currentGroups) {
      runCatching { groupStore.save(cleanGroups) }
        .onFailure { Diagnostics.warn("DeviceCoordinator", "同步设备分组失败", it) }
    }
    val next = DeviceDomainState(
      devices = devices,
      profiles = devices.flatMap { it.toProfiles() },
      deviceGroups = cleanGroups.groups,
      selectedGroupId = cleanGroups.selectedGroupId,
      deviceGroupReset = resetFlag,
      discoveryRevision = mutableState.value.discoveryRevision + if (bumpDiscovery) 1 else 0,
      storeError = deviceStore.loadError
    )
    mutableState.value = next
    return next
  }

  fun devices(): List<SshDevice> = mutableState.value.devices
  fun profiles(): List<ServerProfile> = mutableState.value.profiles
  fun findDevice(deviceId: String): SshDevice? = mutableState.value.devices.firstOrNull { it.id == deviceId }
  fun findHarness(harnessId: String): Pair<SshDevice, DeviceHarness>? {
    for (device in mutableState.value.devices) {
      val harness = device.harnesses.firstOrNull { it.id == harnessId }
      if (harness != null) return device to harness
    }
    return null
  }
  fun secrets(deviceId: String): SshSecrets = deviceStore.secrets(deviceId)
  fun devicesUsingKey(keyId: String): List<SshDevice> = mutableState.value.devices.filter { it.keyId == keyId }

  fun saveDevice(device: SshDevice, secrets: SshSecrets? = null): SshDevice {
    val existing = deviceStore.findDevice(device.id)
    val merged = if (existing != null && device.harnesses.isEmpty() && existing.harnesses.isNotEmpty()) {
      device.copy(harnesses = existing.harnesses)
    } else device
    deviceStore.saveDevice(merged, secrets)
    publish()
    return deviceStore.findDevice(merged.id) ?: merged
  }

  fun deleteDevice(deviceId: String): SshDevice? {
    val removed = deviceStore.deleteDevice(deviceId) ?: return null
    runCatching { discoveryStore.clear(deviceId) }
    val nextGroups = DeviceGroupState(mutableState.value.deviceGroups, mutableState.value.selectedGroupId)
      .withoutDevice(deviceId)
    runCatching { groupStore.save(nextGroups) }
    publish(groupState = nextGroups, bumpDiscovery = true)
    return removed
  }

  fun setDeviceEnabled(deviceId: String, enabled: Boolean): SshDevice? {
    val updated = deviceStore.setDeviceEnabled(deviceId, enabled) ?: return null
    publish()
    return updated
  }

  fun pinHostKey(deviceId: String, key: PresentedSshHostKey): SshDevice? {
    val updated = deviceStore.pinHostKey(deviceId, key) ?: return null
    publish()
    return updated
  }

  fun pinHostKeyIfUnchanged(expected: SshHostConfig, key: PresentedSshHostKey): Boolean {
    val ok = deviceStore.pinHostKeyIfUnchanged(expected, key)
    if (ok) publish()
    return ok
  }

  fun resetHostKey(deviceId: String): SshDevice? {
    val updated = deviceStore.resetHostKey(deviceId) ?: return null
    publish()
    return updated
  }

  fun upsertHarness(deviceId: String, harness: DeviceHarness): Pair<SshDevice, DeviceHarness> {
    val updatedDevice = deviceStore.upsertHarness(deviceId, harness)
    publish()
    return updatedDevice to (updatedDevice.harnesses.firstOrNull { it.id == harness.id } ?: harness)
  }

  fun setHarnessEnabled(harnessId: String, enabled: Boolean): Pair<SshDevice, DeviceHarness>? {
    val result = deviceStore.setHarnessEnabled(harnessId, enabled) ?: return null
    publish()
    return result
  }

  fun updateHarness(harnessId: String, transform: (DeviceHarness) -> DeviceHarness): Pair<SshDevice, DeviceHarness>? {
    val (device, current) = deviceStore.findHarness(harnessId) ?: return null
    val updated = transform(current)
    val savedDevice = deviceStore.upsertHarness(device.id, updated)
    publish()
    return savedDevice to updated
  }

  fun removeHarness(harnessId: String): Pair<SshDevice, DeviceHarness>? {
    val removed = deviceStore.removeHarness(harnessId) ?: return null
    publish()
    return removed
  }

  fun deviceDiscovery(deviceId: String): DeviceDiscovery? = discoveryStore.get(deviceId)

  fun recordDiscovery(discovery: DeviceDiscovery) {
    discoveryStore.put(discovery)
    publish(bumpDiscovery = true)
  }

  fun clearDiscovery(deviceId: String) {
    discoveryStore.clear(deviceId)
    publish(bumpDiscovery = true)
  }

  fun saveGroups(groups: List<DeviceGroup>, selectedGroupId: String?): DeviceGroupState {
    val validDeviceIds = mutableState.value.devices.mapTo(HashSet()) { it.id }
    val clean = DeviceGroupState(groups, selectedGroupId).sanitized(validDeviceIds)
    groupStore.save(clean)
    publish(groupState = clean)
    return clean
  }

  fun createGroup(name: String, deviceIds: List<String>): String {
    val validIds = mutableState.value.devices.mapTo(HashSet()) { it.id }
    val selected = deviceIds.filter { it in validIds }.distinct()
    val label = name.trim().take(128)
    if (label.isBlank() || selected.isEmpty()) return ""
    val id = UUID.randomUUID().toString()
    val groups = mutableState.value.deviceGroups + DeviceGroup(
      id = id,
      name = label,
      deviceIds = selected,
      updatedAt = System.currentTimeMillis()
    )
    saveGroups(groups, mutableState.value.selectedGroupId)
    return id
  }

  fun updateGroup(id: String, name: String, deviceIds: List<String>): Boolean {
    val validIds = mutableState.value.devices.mapTo(HashSet()) { it.id }
    val selected = deviceIds.filter { it in validIds }.distinct()
    val label = name.trim().take(128)
    if (label.isBlank() || selected.isEmpty() || mutableState.value.deviceGroups.none { it.id == id }) return false
    val groups = mutableState.value.deviceGroups.map { group ->
      if (group.id == id) group.copy(name = label, deviceIds = selected, updatedAt = System.currentTimeMillis()) else group
    }
    saveGroups(groups, mutableState.value.selectedGroupId)
    return true
  }

  fun deleteGroup(id: String) {
    if (mutableState.value.deviceGroups.none { it.id == id }) return
    val groups = mutableState.value.deviceGroups.filterNot { it.id == id }
    saveGroups(groups, mutableState.value.selectedGroupId?.takeIf { it != id })
  }

  fun dismissGroupReset() {
    if (mutableState.value.deviceGroupReset) {
      publish(resetFlag = false)
    }
  }

  companion object {
    /** 将底层 SSH 探测结果映射为 [DeviceDiscovery]（只读观测，绝不自动挂载）。 */
    fun buildSshDiscovery(device: SshDevice, found: SshDeviceDiscovery, now: Long): DeviceDiscovery {
      val files = found.files
      val codex = found.codex
      val agy = found.agy
      val dsh = found.dsh
      val observedPath = files.openCode.registrationPath
      val existingOpenCode = device.harnesses.filter { it.protocol == HarnessProtocol.OPENCODE_NATIVE }
      val locator = if (existingOpenCode.any { it.locator == null }) null
        else observedPath?.takeIf { path -> existingOpenCode.any { it.locator == path } }

      fun value(v: Boolean?): DiscoveryValue = when (v) {
        true -> DiscoveryValue.YES
        false -> DiscoveryValue.NO
        null -> DiscoveryValue.UNKNOWN
      }

      fun explanation(issue: String?): String? = when (issue) {
        null -> null
        "probe_failed", "invalid_output" -> "检测未完成，请重试"
        "version_unavailable" -> "已找到程序，但无法确认版本"
        "acp_unavailable" -> "此版本不支持 ACP，请更新后重试"
        "acp_probe_failed" -> "无法确认 ACP 是否可用，请检查设备上的 Antigravity"
        "unsupported_system" -> "当前 SSH 模式支持 Linux 和 macOS；Windows 请使用 WSL"
        "system_detection_failed" -> "无法识别远端系统，请检查 SSH 的 shell 设置"
        "custom_command_unverified" -> "自定义启动命令尚未验证；启用后再连接"
        else -> issue
      }

      val openCodeDetail = openCodeDetail(files)
      val binaryLabel = files.openCode.binaryVersion?.takeIf { it.isNotBlank() }?.let { "已安装 $it" }.orEmpty()
      val harnesses = mutableListOf(
        DiscoveredHarness(
          HarnessProtocol.OPENCODE_NATIVE,
          locator,
          "OpenCode",
          files.openCodeStatus(DeviceDiscovery.DEFAULT_TTL_MS).copy(reason = openCodeDetail.takeIf { it.isNotBlank() }),
          listOf(openCodeDetail, binaryLabel).filter(String::isNotBlank).joinToString(" · ")
        ),
        DiscoveredHarness(
          HarnessProtocol.DSH,
          null,
          "DeepSeek Harness",
          HarnessDiscoveryStatus(
            installed = value(dsh.installed),
            available = DiscoveryValue.UNKNOWN,
            observedAtMs = now,
            freshUntilMs = now + DeviceDiscovery.DEFAULT_TTL_MS,
            completeness = if (dsh.issue == null) DiscoveryCompleteness.COMPLETE else DiscoveryCompleteness.PARTIAL,
            reason = explanation(dsh.issue) ?: if (dsh.installed == false)
              "未发现 DSH，请先在电脑安装或在 DSH 详情页配置自定义启动命令" else "连接时自动启动或复用 DSH"
          )
        ),
        DiscoveredHarness(
          HarnessProtocol.ANTIGRAVITY_ACP,
          null,
          "Antigravity",
          HarnessDiscoveryStatus(
            installed = value(agy.installed),
            compatible = value(agy.compatible),
            available = DiscoveryValue.UNKNOWN,
            observedAtMs = now,
            freshUntilMs = now + DeviceDiscovery.DEFAULT_TTL_MS,
            completeness = if (agy.installed != null && (agy.issue == null || agy.issue == "acp_unavailable")) DiscoveryCompleteness.COMPLETE else DiscoveryCompleteness.PARTIAL,
            reason = explanation(agy.issue)
          ),
          agy.version.orEmpty()
        ),
        DiscoveredHarness(
          HarnessProtocol.CODEX,
          null,
          "Codex",
          HarnessDiscoveryStatus(
            installed = value(codex.installed),
            available = DiscoveryValue.UNKNOWN,
            observedAtMs = now,
            freshUntilMs = now + DeviceDiscovery.DEFAULT_TTL_MS,
            completeness = if (codex.installed != null && codex.issue == null) DiscoveryCompleteness.COMPLETE else DiscoveryCompleteness.PARTIAL,
            reason = explanation(codex.issue)
          ),
          codex.version.orEmpty()
        )
      )
      found.openCodeAcp?.takeIf { it.supported != false || it.issue != null }?.let { acp ->
        harnesses += DiscoveredHarness(
          HarnessProtocol.OPENCODE_ACP,
          null,
          "OpenCode ACP",
          HarnessDiscoveryStatus(
            installed = value(acp.supported),
            compatible = value(acp.supported),
            available = DiscoveryValue.UNKNOWN,
            observedAtMs = now,
            freshUntilMs = now + DeviceDiscovery.DEFAULT_TTL_MS,
            completeness = if (acp.supported == null) DiscoveryCompleteness.PARTIAL else DiscoveryCompleteness.COMPLETE,
            reason = when {
              acp.issue == "acp_unavailable" -> "此版本的 opencode 不支持 ACP，请升级"
              acp.issue != null -> "ACP 检测未完成，请重试"
              acp.supported == true -> null
              else -> "未安装 opencode"
            }
          ),
          acp.version.orEmpty()
        )
      }
      found.localOpenCode?.takeIf { it.openCode.state == SshDiscoveryState.PRESENT }?.let { local ->
        harnesses += DiscoveredHarness(
          HarnessProtocol.OPENCODE_NATIVE,
          local.openCode.registrationPath,
          "OpenCode（开发版）",
          local.openCodeStatus(DeviceDiscovery.DEFAULT_TTL_MS)
        )
      }
      val environmentNote = when (found.environmentIssue) {
        "python_unavailable" -> "设备缺少 Python 3，部分服务无法检测或安全连接"
        "unsupported_system" -> "当前 SSH 模式支持 Linux 和 macOS；Windows 请使用 WSL"
        "system_detection_failed" -> "无法识别远端系统，请检查 SSH 的 shell 设置"
        else -> null
      }
      return DeviceDiscovery(device.id, found.observedAtMs, harnesses, found.completeness, environmentNote)
    }

    private fun openCodeDetail(files: SshHarnessDiscoveryResult): String {
      val binary = files.openCode.binaryVersion?.takeIf { it.isNotBlank() }
      return when (files.reasonCode()) {
        SshDiscoveryReason.NOT_FOUND ->
          if (binary == null) "设备上未发现 OpenCode；请安装 OpenCode 2.x 后运行 opencode service start"
          else "已安装 $binary，但服务未运行；可点击「启动服务」或刷新检测"
        SshDiscoveryReason.PERMISSIONS_TOO_OPEN ->
          "注册文件允许同组或其他用户改写，已拒绝使用；请在设备上执行 chmod 600 注册文件"
        SshDiscoveryReason.SERVICE_UNREACHABLE ->
          "OpenCode 服务没有响应（注册可能已过期）；重启 opencode service 后刷新检测"
        SshDiscoveryReason.AUTHENTICATION_FAILED ->
          "OpenCode 拒绝了注册文件里的凭据；重启服务重新生成注册"
        SshDiscoveryReason.REGISTRATION_STALE ->
          "注册文件与运行中的服务不一致（服务可能已重启）；刷新检测会重新绑定"
        SshDiscoveryReason.VERSION_MISMATCH -> "注册文件版本与运行中的服务不一致；重启服务后刷新"
        SshDiscoveryReason.PROTOCOL_MISMATCH -> "该端口不是 OpenCode 2.x 服务（需要 2.0 以上）"
        SshDiscoveryReason.PYTHON_UNAVAILABLE -> "设备缺少 Python 3：状态检查和安全连接都需要它，请安装 python3"
        SshDiscoveryReason.UNREADABLE -> "无法读取 OpenCode 注册文件，请检查路径与权限"
        SshDiscoveryReason.INVALID_DATA, SshDiscoveryReason.TOO_LARGE ->
          "OpenCode 注册文件无效，请在设备上重启服务重新生成"
        SshDiscoveryReason.VERSION_UNKNOWN -> "已连接，但无法确认 OpenCode 版本"
        else -> ""
      }
    }
  }
}
