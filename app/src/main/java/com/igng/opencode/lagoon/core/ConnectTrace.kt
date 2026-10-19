package com.igng.opencode.lagoon.core

import okhttp3.Interceptor
import java.util.concurrent.atomic.AtomicInteger

/** 一次连接 / 打开会话里，用户能感知到的里程碑。只记第一次到达的时间。 */
enum class ConnectMilestone(val label: String) {
  /** 缓存内容（会话列表或消息）第一次出现在屏幕上。 */
  CACHE_PAINT("缓存首屏"),
  /** 服务器返回了会话列表首页。 */
  CATALOG("会话列表"),
  /** 控制面读取完成，界面进入「已连接」可操作状态。 */
  INTERACTIVE("可交互"),
  /** 实时事件流握手成功。 */
  STREAM("实时同步"),
  /** 当前会话的消息从服务器读到。 */
  TRANSCRIPT("会话内容")
}

/** 一次已结束或进行中的追踪结果；[elapsed] 为相对开始的毫秒数。 */
data class ConnectReport(
  val reason: String,
  val elapsed: Map<ConnectMilestone, Long>,
  val requests: Int,
  val peakInFlight: Int,
  val failure: String? = null
) {
  /** 单行可读摘要，供诊断信息复制与 logcat。 */
  fun line(): String = buildString {
    append(reason)
    ConnectMilestone.entries.forEach { milestone -> elapsed[milestone]?.let { append(" · ${milestone.label} ${it}ms") } }
    append(" · 请求 $requests / 并发峰值 $peakInFlight")
    failure?.let { append(" · 失败：$it") }
  }
}

/**
 * Field measurements for connection latency: when each user-visible milestone was first reached in one
 * attempt (connect, reconnect, refresh, opening a conversation), plus how many HTTP requests the
 * attempt issued and how many ran at once. Optimisations are judged against these numbers, so the
 * tracker only observes; it never changes what the controller does.
 *
 * An attempt is bound to a [key] (the controller's connection generation or selection revision), so a
 * late callback from a superseded attempt cannot write into the current one.
 */
class ConnectTrace(
  private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
  private val metrics: HttpCounters = HttpMetrics,
  private val keep: Int = 6
) {
  private class Attempt(val key: Long, val reason: String, val startedAt: Long, val requestBase: Int) {
    val elapsed = linkedMapOf<ConnectMilestone, Long>()
    var failure: String? = null
  }

  private var current: Attempt? = null
  private val history = ArrayDeque<ConnectReport>()

  @Synchronized fun begin(key: Long, reason: String) {
    current?.let(::archive)
    metrics.resetPeak()
    current = Attempt(key, reason, clock(), metrics.total())
  }

  /** Records [milestone] for the attempt bound to [key]; returns the elapsed time when it was the first mark. */
  @Synchronized fun mark(key: Long, milestone: ConnectMilestone): Long? {
    val attempt = current?.takeIf { it.key == key } ?: return null
    if (milestone in attempt.elapsed) return null
    val elapsed = (clock() - attempt.startedAt).coerceAtLeast(0)
    attempt.elapsed[milestone] = elapsed
    return elapsed
  }

  @Synchronized fun fail(key: Long, reason: String) {
    val attempt = current?.takeIf { it.key == key } ?: return
    if (attempt.failure == null) attempt.failure = reason
  }

  /** Milliseconds since the attempt bound to [key] began, or null when it is no longer current. */
  @Synchronized fun sinceStart(key: Long): Long? = current?.takeIf { it.key == key }?.let { clock() - it.startedAt }

  /** The current attempt first, then the most recent finished ones. */
  @Synchronized fun reports(): List<ConnectReport> = listOfNotNull(current?.let(::report)) + history.reversed()

  private fun report(attempt: Attempt) = ConnectReport(attempt.reason, attempt.elapsed.toMap(),
    (metrics.total() - attempt.requestBase).coerceAtLeast(0), metrics.peak(), attempt.failure)

  private fun archive(attempt: Attempt) {
    history.addLast(report(attempt))
    while (history.size > keep) history.removeFirst()
  }
}

/**
 * HTTP counters: every API client adds [interceptor], so the trace can report how many requests a
 * connection issued and how far the fan-out actually ran in parallel. Long-lived event streams are
 * not counted (they would pin the in-flight number).
 */
class HttpCounters {
  private val total = AtomicInteger()
  private val inFlight = AtomicInteger()
  private val peak = AtomicInteger()

  val interceptor = Interceptor { chain -> track { chain.proceed(chain.request()) } }

  fun <T> track(block: () -> T): T {
    total.incrementAndGet()
    val now = inFlight.incrementAndGet()
    peak.accumulateAndGet(now, ::maxOf)
    try { return block() } finally { inFlight.decrementAndGet() }
  }

  fun total(): Int = total.get()
  fun peak(): Int = peak.get()
  fun resetPeak() { peak.set(inFlight.get()) }
}

/** The process-wide counters shared by every API client. */
val HttpMetrics = HttpCounters()
