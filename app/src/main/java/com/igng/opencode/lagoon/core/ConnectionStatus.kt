package com.igng.opencode.lagoon.core

/** Where the selected server's connection currently is, in the terms the user sees. */
enum class ConnectStage {
  /** No server selected. */
  IDLE,
  /** Probing the server (version, contract, credentials). */
  CONNECTING,
  /** Reading the session catalog. */
  READING,
  /** Catalog is on screen; activity, permissions and forms are still being read. */
  SYNCING,
  /** Control plane read; the app is interactive. */
  READY,
  /** The last attempt failed; another one is scheduled at [ConnectionStatus.retryAt]. */
  RETRYING
}

/**
 * The connection stage plus when it began, so the UI can say what it is waiting for and for how
 * long, instead of a bare indeterminate bar.
 */
data class ConnectionStatus(
  val stage: ConnectStage = ConnectStage.IDLE,
  /** Wall clock when [stage] was entered. */
  val since: Long = 0,
  /** Wall clock of the next automatic attempt while [ConnectStage.RETRYING]; 0 when unknown. */
  val retryAt: Long = 0,
  /** Why the last attempt failed, shown while retrying. */
  val reason: String? = null
) {
  val busy: Boolean get() = stage == ConnectStage.CONNECTING || stage == ConnectStage.READING || stage == ConnectStage.SYNCING
}

/** One line of connection feedback above the page. */
data class ConnectionBanner(val text: String, val tone: Tone, val retry: Boolean = false) {
  enum class Tone { PROGRESS, WARNING, ERROR }

  companion object {
    /** Below this a stage change is quick enough that announcing it would only flicker. */
    const val QUIET_MILLIS = 600L
    /** After this long in one stage the copy says the server is slow and offers a retry. */
    const val SLOW_MILLIS = 8_000L
    /** A live-stream drop shorter than this is a routine reconnect and is not announced. */
    const val STREAM_GRACE_MILLIS = 4_000L

    /**
     * The banner for the current moment, or null when nothing needs saying. Pure so the copy and the
     * thresholds are unit-tested; the UI only re-evaluates it once a second.
     */
    fun of(
      status: ConnectionStatus,
      serverName: String?,
      now: Long,
      streamConnected: Boolean,
      streamDownSince: Long,
      transcriptSyncing: Boolean = false,
      transcriptSince: Long = 0
    ): ConnectionBanner? {
      val name = serverName?.takeIf { it.isNotBlank() } ?: "服务器"
      val elapsed = (now - status.since).coerceAtLeast(0)
      fun progress(text: String, slow: String): ConnectionBanner? = when {
        elapsed < QUIET_MILLIS -> null
        elapsed >= SLOW_MILLIS -> ConnectionBanner("$slow · 已等待 ${elapsed / 1000} 秒", Tone.WARNING, retry = true)
        else -> ConnectionBanner(text, Tone.PROGRESS)
      }
      return when (status.stage) {
        ConnectStage.IDLE -> null
        ConnectStage.CONNECTING -> progress("正在连接 $name…", "$name 响应较慢，仍在尝试")
        ConnectStage.READING -> progress("正在读取会话…", "会话列表读取较慢")
        ConnectStage.SYNCING -> progress("正在同步任务状态…", "任务状态同步较慢")
        ConnectStage.RETRYING -> {
          val seconds = ((status.retryAt - now + 999) / 1000).coerceAtLeast(0)
          val reason = status.reason?.takeIf { it.isNotBlank() } ?: "连接失败"
          ConnectionBanner(if (status.retryAt > now) "$reason · ${seconds} 秒后重试" else "$reason · 正在重试", Tone.ERROR, retry = true)
        }
        ConnectStage.READY -> when {
          !streamConnected && streamDownSince > 0 && now - streamDownSince >= STREAM_GRACE_MILLIS ->
            ConnectionBanner("实时同步恢复中…", Tone.WARNING, retry = true)
          transcriptSyncing && now - transcriptSince >= QUIET_MILLIS -> ConnectionBanner("正在同步最新消息…", Tone.PROGRESS)
          else -> null
        }
      }
    }
  }
}
