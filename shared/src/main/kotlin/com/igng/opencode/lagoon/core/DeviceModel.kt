package com.igng.opencode.lagoon.core

/**
 * 设备的稳定身份（`HostBinding.id`）。它和 Harness 的 [LeafId] 属于不同命名空间：不要互相赋值或比较。
 * 见 `docs/DEVICE_MODEL.md`。
 */
@JvmInline
value class DeviceId(val value: String) {
  override fun toString(): String = value
}

/** 一条 Harness 连接的稳定身份（`ServerProfile.id`）。永不重编号，会话/草稿/缓存都挂在它上面。 */
@JvmInline
value class LeafId(val value: String) {
  override fun toString(): String = value
}

/** 设备通过哪种接入源到达；这不是 Harness 协议。 */
enum class DeviceAccess(val label: String) {
  HUB("中枢"),
  SSH("SSH"),
  DIRECT("直连");

  companion object {
    fun of(type: ConnectionSourceType): DeviceAccess = when (type) {
      ConnectionSourceType.HUB -> HUB
      ConnectionSourceType.SSH -> SSH
      ConnectionSourceType.DIRECT -> DIRECT
    }
  }
}

/** 一个可挂载的 Harness 槽位：协议 + locator 唯一确定，最多对应一条 leaf。 */
data class HarnessSlot(
  val key: String,
  val protocol: HarnessProtocolId,
  val locator: String? = null,
  val name: String,
  val leafId: LeafId? = null,
  /** leaf 自己的开关；没有 leaf 时恒为 false。 */
  val attached: Boolean = false,
  val enabled: Boolean = false,
  val availability: HarnessDiscoveryStatus = HarnessDiscoveryStatus(),
  /** 用户能不能现在把它打开：已挂载（可关）或观测到可用；中枢/SSH 的 DSH 恒可挂载。 */
  val attachable: Boolean = false,
  /** 一句话解释不可用原因；可用时为空。 */
  val reason: String = ""
) {
  val effectiveEnabled: Boolean get() = attached && enabled
}

/**
 * 一台设备的展示投影。**界面里的“设备”永远是它**，不是任何一条 Harness。
 */
data class DeviceView(
  val id: DeviceId,
  val name: String,
  val access: DeviceAccess,
  val endpoint: String,
  val fingerprint: String? = null,
  val sourceId: String,
  val sourceName: String? = null,
  /** 设备开关（`HostBinding.enabled`）。 */
  val enabled: Boolean,
  /** 父级接入源开关；中枢退出登录时为 false。SSH 恒为 true。 */
  val sourceEnabled: Boolean = true,
  val harnesses: List<HarnessSlot> = emptyList(),
  val discovery: DeviceDiscovery? = null,
  val online: Boolean = false
) {
  val attachedCount: Int get() = harnesses.count { it.attached }
  val enabledCount: Int get() = harnesses.count { it.effectiveEnabled }
  val effectiveEnabled: Boolean get() = enabled && sourceEnabled

  /** 设备级不可用原因；空串表示设备本身允许连接。 */
  val blockedReason: String
    get() = when {
      !sourceEnabled -> "接入源已暂停或账号已退出"
      !enabled -> "此设备已停用"
      else -> ""
    }

  fun slot(protocol: HarnessProtocolId, locator: String? = null): HarnessSlot? =
    harnesses.firstOrNull { it.protocol == protocol && it.locator == locator }
}

/** 一台设备上的一条发现结果；观测，不是连接许可。 */
data class DiscoveredHarness(
  val protocol: HarnessProtocolId,
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

  fun harness(protocol: HarnessProtocolId, locator: String? = null): DiscoveredHarness? =
    harnesses.firstOrNull { it.protocol == protocol && it.locator == locator }

  companion object {
    const val DEFAULT_TTL_MS: Long = 10 * 60 * 1000
  }
}

/**
 * 纯投影：连接图 + leaf 资料 + 采纳证据 + 发现缓存 → 设备列表。
 *
 * 不读存储、不拨号、不写任何东西。[adoptedDeviceIds] 为 null 表示"还没有可信的采纳目录"（例如中枢目录
 * 不可读），此时按旧行为把图里的 Host 全部当设备，宁可多显示也不让设备凭空消失。
 */
object DeviceProjection {
  fun devices(
    graph: ConnectionGraph?,
    profiles: List<ServerProfile>,
    adoptedDeviceIds: Set<String>? = null,
    discovery: (String) -> DeviceDiscovery? = { null },
    isOnline: (String) -> Boolean = { false },
    nowMs: Long = System.currentTimeMillis()
  ): List<DeviceView> {
    val effectiveGraph = graph ?: runCatching { MigrationProjection.fromProfiles(profiles) }.getOrNull() ?: return emptyList()
    val profilesById = profiles.associateBy { it.id }
    val bindingsByHost = effectiveGraph.bindings.groupBy { it.hostBindingId }
    val sourcesById = effectiveGraph.sources.associateBy { it.id }
    return effectiveGraph.hosts.mapNotNull { host ->
      val source = sourcesById[host.sourceId] ?: return@mapNotNull null
      val access = DeviceAccess.of(source.type)
      val attached = bindingsByHost[host.id].orEmpty().mapNotNull { profilesById[it.id] }
      if (access == DeviceAccess.HUB && adoptedDeviceIds != null &&
        host.id !in adoptedDeviceIds && attached.isEmpty()
      ) return@mapNotNull null
      val observed = discovery(host.id)
      val slots = slots(access, attached, observed, nowMs)
      DeviceView(
        id = DeviceId(host.id),
        name = host.name.ifBlank { attached.firstOrNull()?.name ?: host.externalHostId ?: "设备" },
        access = access,
        endpoint = source.endpoint,
        fingerprint = host.fingerprint,
        sourceId = source.id,
        sourceName = source.account ?: source.name,
        // SSH 源的开关就是设备开关：一台配置就是一台设备，不再暴露第二层。
        enabled = host.enabled,
        sourceEnabled = access == DeviceAccess.SSH || source.enabled,
        harnesses = slots,
        discovery = observed,
        online = attached.any { isOnline(it.id) }
      )
    }.sortedWith(compareBy({ it.access.ordinal }, { it.name.lowercase() }, { it.id.value }))
  }

  /** leaf 在设备页上的槽位键：协议 + locator。 */
  fun slotKey(protocol: HarnessProtocolId, locator: String?): String = "${protocol.wire}|${locator.orEmpty()}"

  fun slotKey(profile: ServerProfile): String = slotKey(MigrationProjection.protocol(profile.kind), locatorOf(profile))

  /** 中枢 OpenCode 用 serviceId，SSH OpenCode 用注册路径；其余协议没有 locator。 */
  fun locatorOf(profile: ServerProfile): String? = when {
    profile.remoteServiceId != null -> profile.remoteServiceId
    profile.kind == ServerKind.OPENCODE -> profile.sshServicePath
    else -> null
  }

  fun protocolOf(profile: ServerProfile): HarnessProtocolId = MigrationProjection.protocol(profile.kind)

  private fun slots(
    access: DeviceAccess,
    attached: List<ServerProfile>,
    discovery: DeviceDiscovery?,
    nowMs: Long
  ): List<HarnessSlot> {
    val slots = LinkedHashMap<String, HarnessSlot>()
    fun put(slot: HarnessSlot) {
      // 已挂载的 leaf 永远优先于同槽位的发现结果。
      val existing = slots[slot.key]
      if (existing == null || slot.attached && !existing.attached) slots[slot.key] = slot
    }
    attached.forEach { profile ->
      val protocol = protocolOf(profile)
      val locator = locatorOf(profile)
      put(HarnessSlot(
        key = slotKey(protocol, locator),
        protocol = protocol,
        locator = locator,
        name = harnessName(protocol, profile.name),
        leafId = LeafId(profile.id),
        attached = true,
        enabled = profile.enabled,
        availability = discovery?.harness(protocol, locator)?.status ?: HarnessDiscoveryStatus(),
        attachable = true
      ))
    }
    discovery?.harnesses.orEmpty().forEach { observed ->
      val key = slotKey(observed.protocol, observed.locator)
      if (slots.containsKey(key)) return@forEach
      val fresh = discovery?.isFresh(nowMs) == true
      val canAttach = attachable(access, observed.status, nowMs)
      put(HarnessSlot(
        key = key,
        protocol = observed.protocol,
        locator = observed.locator,
        name = observed.name.ifBlank { harnessName(observed.protocol, "") },
        availability = observed.status,
        attachable = canAttach,
        reason = if (canAttach) "" else observed.detail.ifBlank { unavailableReason(observed.status, fresh) }
      ))
    }
    // 没有发现结果时，仍然给出该接入方式下“恒可挂载”的槽位，避免用户被一次失败的发现锁死。
    // 发现里已有的协议不重复给兜底槽位（SSH 的 OpenCode 注册路径就是一个典型）。
    candidates(access).forEach { (protocol, locator, name) ->
      if (slots.values.none { it.protocol == protocol }) put(HarnessSlot(
        key = slotKey(protocol, locator), protocol = protocol, locator = locator, name = name,
        attachable = true, reason = if (discovery == null) "尚未发现；启用后会按该接入方式连接" else "未检测到此 Harness"
      ))
    }
    return slots.values.sortedWith(compareBy({ protocolOrder(it.protocol) }, { it.name }))
  }

  /** 每种接入方式下不需要发现就能挂载的槽位。中枢 DSH 是 Host 本体通道；SSH DSH/OpenCode 走既有接线。 */
  private fun candidates(access: DeviceAccess): List<Triple<HarnessProtocolId, String?, String>> = when (access) {
    DeviceAccess.HUB -> listOf(Triple(HarnessProtocolId.DSH_TYPERT, null, "DeepSeek Harness"))
    DeviceAccess.SSH -> listOf(
      Triple(HarnessProtocolId.DSH_TYPERT, null, "DeepSeek Harness"),
      Triple(HarnessProtocolId.OPENCODE_NATIVE, null, "OpenCode")
    )
    DeviceAccess.DIRECT -> emptyList()
  }

  /**
   * SSH 的只读发现只能证明“装了/注册了”，认证与版本要连上才知道；因此它按已安装判定可添加，
   * 中枢则由 Host 明确声明的可用性决定。
   */
  private fun attachable(access: DeviceAccess, status: HarnessDiscoveryStatus, nowMs: Long): Boolean =
    if (access == DeviceAccess.SSH) {
      status.installed != DiscoveryValue.NO && status.configured != DiscoveryValue.NO && status.available != DiscoveryValue.NO
    } else {
      status.availableNow(nowMs)
    }

  private fun protocolOrder(protocol: HarnessProtocolId): Int = when (protocol) {
    HarnessProtocolId.DSH_TYPERT -> 0
    HarnessProtocolId.OPENCODE_NATIVE -> 1
    HarnessProtocolId.CODEX_APP_SERVER -> 2
  }

  private fun harnessName(protocol: HarnessProtocolId, fallback: String): String {
    if (fallback.isNotBlank()) return fallback
    return when (protocol) {
      HarnessProtocolId.DSH_TYPERT -> "DeepSeek Harness"
      HarnessProtocolId.OPENCODE_NATIVE -> "OpenCode"
      HarnessProtocolId.CODEX_APP_SERVER -> "Codex"
    }
  }

  private fun unavailableReason(status: HarnessDiscoveryStatus, fresh: Boolean): String = when {
    !fresh -> "上次发现已过期"
    status.completeness == DiscoveryCompleteness.UNKNOWN -> "未检测"
    status.installed == DiscoveryValue.NO -> "未安装"
    status.listening == DiscoveryValue.NO -> "未运行"
    status.authenticated == DiscoveryValue.NO -> "未认证"
    status.compatible == DiscoveryValue.NO -> "版本不兼容"
    status.available == DiscoveryValue.NO -> "不可用"
    else -> status.reason.orEmpty()
  }
}
