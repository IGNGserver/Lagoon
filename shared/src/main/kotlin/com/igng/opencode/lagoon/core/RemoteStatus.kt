package com.igng.opencode.lagoon.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Observable Remote state. This is deliberately separate from [Session]/[Message] projections:
 * a connection failure must never be rendered as "this session has no content".
 */
enum class RemotePhase { IDLE, CREDENTIALS, CONNECTING, HANDSHAKING, READY, FAILED, CLOSED }

data class RemoteFailureSnapshot(
  val kind: RemoteFailureKind?,
  val message: String,
  val retryable: Boolean,
  val code: String? = null,
  val at: Long = System.currentTimeMillis()
)

data class RemoteConnectionStatus(
  val phase: RemotePhase = RemotePhase.IDLE,
  val generation: Int = 0,
  val startedAt: Long = 0,
  val readyAt: Long = 0,
  val hostId: String = "",
  val hostName: String = "",
  val serverVersion: String? = null,
  val pluginVersion: String? = null,
  val harnessVersion: String? = null,
  val transport: String = "relay",
  val relayAssignment: Boolean = false,
  val capabilities: Set<String> = emptySet(),
  val lastError: RemoteFailureSnapshot? = null
) {
  val connected: Boolean get() = phase == RemotePhase.READY

  fun phaseLabel(): String = when (phase) {
    RemotePhase.IDLE -> "未连接"
    RemotePhase.CREDENTIALS -> "验证设备凭据"
    RemotePhase.CONNECTING -> "连接中枢"
    RemotePhase.HANDSHAKING -> "协商加密通道"
    RemotePhase.READY -> "已连接"
    RemotePhase.FAILED -> "连接失败"
    RemotePhase.CLOSED -> "已关闭"
  }
}

/** Narrow source for the doctor report; kept off the public [HarnessClient] surface. */
internal interface RemoteDoctorSource {
  fun remoteDoctor(appVersion: String): RemoteDoctor?
}

/** A credential summary that never contains token material; only state and expiry. */
data class RemoteCredentialSummary(
  val state: String,
  val accessExpiresAt: Long = 0,
  val refreshExpiresAt: Long = 0,
  val transitionSince: Long = 0
) {
  val needsReauth: Boolean get() = state == "需要重新登录" || state == "凭据已过期"
}

data class RemoteSnapshotStats(
  val sessionId: String,
  val records: Int,
  val messages: Int,
  val cursor: Long,
  val hasMore: Boolean,
  val at: Long = System.currentTimeMillis(),
  val error: String? = null
)

data class RemoteFollowStats(
  val sessionId: String,
  val open: Boolean,
  val lastSeq: Long = 0,
  val lastFrameAt: Long = 0,
  val reconnects: Int = 0,
  val error: String? = null
)

data class RemoteEventStats(
  val ready: Boolean = false,
  val clientId: String? = null,
  val generation: Int = 0,
  val lastEventAt: Long = 0,
  val reconnects: Int = 0,
  val error: String? = null
)

/**
 * Everything needed to answer "which hop failed?" without reading any message body.
 * Copied from the connection dialog into bug reports.
 */
data class RemoteDoctor(
  val hub: String,
  val account: String,
  val hostName: String,
  val hostId: String,
  val appVersion: String,
  val status: RemoteConnectionStatus,
  val credentials: RemoteCredentialSummary?,
  val snapshots: List<RemoteSnapshotStats>,
  val follows: List<RemoteFollowStats>,
  val events: RemoteEventStats,
  val capturedAt: Long = System.currentTimeMillis()
) {
  fun diagnosticLines(): List<Pair<String, String>> = buildList {
    add("中枢" to hub)
    add("账号" to account.ifBlank { "未知" })
    add("主机" to "${hostName.ifBlank { "未命名" }} · ${hostId.take(8)}")
    add("客户端" to appVersion.ifBlank { "未知" })
    add("连接" to "${status.phaseLabel()} · generation ${status.generation}")
    add("连接耗时" to if (status.readyAt > 0 && status.startedAt > 0) "${status.readyAt - status.startedAt} 毫秒" else "未完成")
    add("路径" to if (status.relayAssignment) "Relay 中继 · 独立数据通道" else "Relay 中继 · 控制通道")
    add("版本" to listOfNotNull(
      status.serverVersion?.let { "中枢 $it" },
      status.pluginVersion?.let { "插件 $it" },
      status.harnessVersion?.let { "DSH $it" }
    ).joinToString(" · ").ifBlank { "未知" })
    add("能力" to if (status.capabilities.isEmpty()) "未知" else status.capabilities.sorted().joinToString(", "))
    add("凭据" to (credentials?.let { credentialLabel(it) } ?: "未知"))
    add("快照" to (snapshots.lastOrNull()?.let { snapshotLabel(it) } ?: "尚未读取"))
    add("会话流" to (follows.maxByOrNull { it.lastFrameAt }?.let { followLabel(it) } ?: "未打开"))
    add("事件流" to eventLabel(events))
    add("最近错误" to (status.lastError?.let { failureLabel(it) } ?: events.error ?: "无"))
    add("采集时间" to time(capturedAt))
  }

  fun asText(): String = buildString {
    appendLine("DSH Remote 诊断")
    diagnosticLines().forEach { (label, value) -> appendLine("$label: $value") }
    if (follows.size > 1) {
      appendLine("活跃会话流:")
      follows.sortedByDescending { it.lastFrameAt }.take(8).forEach { appendLine("  - ${followLabel(it)}") }
    }
  }

  private fun credentialLabel(value: RemoteCredentialSummary): String {
    val access = if (value.accessExpiresAt > 0) "access ${remaining(value.accessExpiresAt)}" else "无 access"
    val refresh = if (value.refreshExpiresAt > 0) "refresh ${remaining(value.refreshExpiresAt)}" else "无 refresh"
    val since = if (value.transitionSince > 0) " · ${ago(value.transitionSince)}进入" else ""
    return "${value.state} · $access · $refresh$since"
  }

  private fun snapshotLabel(value: RemoteSnapshotStats): String = buildString {
    append("records=${value.records} messages=${value.messages} cursor=${value.cursor} hasMore=${value.hasMore}")
    append(" · ${ago(value.at)}")
    value.error?.let { append(" · 失败: $it") }
  }

  private fun followLabel(value: RemoteFollowStats): String = buildString {
    append(value.sessionId.take(24))
    append(if (value.open) " · open" else " · closed")
    if (value.lastSeq > 0) append(" · lastSeq=${value.lastSeq}")
    if (value.lastFrameAt > 0) append(" · ${ago(value.lastFrameAt)}有帧")
    if (value.reconnects > 0) append(" · 重连${value.reconnects}次")
    value.error?.let { append(" · $it") }
  }

  private fun eventLabel(value: RemoteEventStats): String = buildString {
    append(if (value.ready) "ready" else "未就绪")
    if (value.generation > 0) append(" · generation ${value.generation}")
    value.clientId?.take(8)?.let { append(" · client $it") }
    if (value.lastEventAt > 0) append(" · ${ago(value.lastEventAt)}有事件")
    if (value.reconnects > 0) append(" · 重连${value.reconnects}次")
    value.error?.let { append(" · $it") }
  }

  private fun failureLabel(value: RemoteFailureSnapshot): String = buildString {
    value.kind?.let { append("$it · ") }
    append(value.message)
    value.code?.let { append(" · $it") }
    if (value.retryable) append(" · 可重试")
  }

  private fun ago(at: Long): String {
    val delta = capturedAt - at
    if (delta <= 0) return "刚刚"
    val minutes = delta / 60_000
    return when {
      minutes < 1 -> "不到 1 分钟前"
      minutes < 60 -> "${minutes} 分钟前"
      minutes < 60 * 24 -> "${minutes / 60} 小时前"
      else -> "${minutes / (60 * 24)} 天前"
    }
  }

  private fun remaining(target: Long): String {
    val delta = target - capturedAt
    if (delta <= 0) return "已过期"
    val minutes = delta / 60_000
    return when {
      minutes < 1 -> "不到 1 分钟"
      minutes < 60 -> "${minutes} 分钟"
      minutes < 60 * 24 -> "${minutes / 60} 小时"
      else -> "${minutes / (60 * 24)} 天"
    }
  }

  private fun time(at: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(at))
}
