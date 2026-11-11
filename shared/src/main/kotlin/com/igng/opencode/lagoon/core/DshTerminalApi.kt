package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.UUID

/** Official rc.5 Session terminals. The caller owns RPC envelopes, auth and the physical MUX. */
internal class DshTerminalApi(
  private val call: suspend (String, JSONObject) -> Any?,
  private val open: suspend (String, JSONObject, (JSONObject) -> Unit, (Throwable?) -> Unit) -> WebSocket,
  private val ownerClosed: () -> Boolean
) : Closeable {
  private val lock = Any()
  private var closed = false
  private val attachments = mutableSetOf<Attachment>()
  private val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  suspend fun terminals(scope: TerminalScope.Session): List<NativeTerminal> {
    checkScope(scope)
    val rows = request("terminal/list", JSONObject().put("sessionId", scope.sessionId)) as? JSONArray
      ?: invalid("终端列表必须为数组")
    val result = (0 until rows.length()).map { terminal(obj(rows.opt(it)), scope) }
    if (result.map { it.id }.toSet().size != result.size) invalid("终端列表身份重复")
    return result
  }

  suspend fun createTerminal(scope: TerminalScope.Session): NativeTerminal {
    val env = environment(scope)
    val id = UUID.randomUUID().toString()
    val createRequest = JSONObject().put("id", id).put("cols", minOf(80, env.maxCols)).put("rows", minOf(24, env.maxRows))
    return terminal(obj(request("terminal/create", agent(scope).put("request", createRequest))), scope, id)
  }

  suspend fun renameTerminal(scope: TerminalScope.Session, id: String, title: String) {
    require(title.isNotBlank() && title.length <= 120) { "终端标题必须为 1–120 个字符" }
    request("terminal/rename", agent(scope).put("id", identity(id)).put("title", title))
  }

  /** The only operation that terminates a remote process; detach and disposal never call this. */
  suspend fun closeTerminal(scope: TerminalScope.Session, id: String) {
    request("terminal/close", agent(scope).put("id", identity(id)))
  }

  suspend fun attachTerminal(scope: TerminalScope.Session, id: String): TerminalAttachment {
    identity(id)
    val attachment = Attachment(scope, id, environment(scope))
    synchronized(lock) { checkOpen(); attachments += attachment }
    try {
      // The hold must acknowledge before attach, and remains open for this attachment's lifetime.
      attachment.install(open("terminal/retain", JSONObject().put("sessionId", scope.sessionId).put("id", id),
        attachment::retained, attachment::holdEnded), hold = true)
      withTimeout(TIMEOUT_MS) { attachment.retentionReady.await() }
      attachment.requireActive()
      attachment.install(open("terminal/attach", agent(scope).put("id", id),
        attachment::frame, attachment::streamEnded), hold = false)
      withTimeout(TIMEOUT_MS) { attachment.snapshotReady.await() }
      currentCoroutineContext().ensureActive()
      attachment.requireActive()
      return attachment
    } catch (cancel: CancellationException) {
      attachment.close()
      throw cancel
    } catch (_: Exception) {
      attachment.close()
      // No raw transport exceptions: they can contain credentials or sensitive URLs.
      throw ApiException(502, "无法连接 DSH 终端；没有重试或重放输入")
    }
  }

  override fun close() {
    val active = synchronized(lock) {
      if (closed) return
      closed = true
      attachments.toList().also { attachments.clear() }
    }
    active.forEach { it.close() }
    lifetime.cancel()
  }

  private suspend fun request(method: String, args: JSONObject): Any? {
    synchronized(lock) { checkOpen() }
    val result = call(method, args) // Named args ONLY, not an RPC envelope. Never retry here.
    currentCoroutineContext().ensureActive()
    synchronized(lock) { checkOpen() }
    return result
  }

  private suspend fun environment(scope: TerminalScope.Session): Environment {
    val value = obj(request("terminal/environment", agent(scope)))
    string(value, "cwd") // A runtime hint, deliberately not compared to scope.directory.
    integer(value, "scrollback")
    return Environment(integer(value, "maxInputBytes", 1), integer(value, "maxBufferedBytes", 1),
      integer(value, "maxCols", 2, Int.MAX_VALUE.toLong()).toInt(),
      integer(value, "maxRows", 1, Int.MAX_VALUE.toLong()).toInt())
  }

  private fun agent(scope: TerminalScope.Session): JSONObject {
    checkScope(scope)
    return JSONObject().put("agentId", scope.sessionId)
  }

  private fun checkScope(scope: TerminalScope.Session) { identity(scope.sessionId) }
  private fun checkOpen() { check(!closed && !ownerClosed()) { "DSH 终端连接已关闭" } }

  private data class Environment(val maxInputBytes: Long, val maxBufferedBytes: Long, val maxCols: Int, val maxRows: Int)
  private data class Pending(val inputBytes: Long, val frameBytes: Long, val since: Long)

  private inner class Attachment(
    private val scope: TerminalScope.Session, private val id: String, private val env: Environment
  ) : TerminalAttachment {
    private val stateLock = Any()
    private val sendLock = Any()
    private val mutableState = MutableStateFlow(TerminalStreamState(writable = false))
    override val state: StateFlow<TerminalStreamState> = mutableState.asStateFlow()
    val retentionReady = CompletableDeferred<Unit>()
    val snapshotReady = CompletableDeferred<Unit>()
    private var hold: WebSocket? = null
    private var socket: WebSocket? = null
    private var ended = false
    private var retained = false
    private var snapshotSeen = false
    private var outputSequence = 0L
    private var inputSequence = 0L
    private var acknowledged = 0L
    private var inputBytes = 0L
    private var bufferedBytes = 0L
    private var windowBytes = minOf(env.maxBufferedBytes, FALLBACK_WINDOW_BYTES)
    // No advertised window: reserve a deliberately conservative Remote/MUX envelope allowance.
    private var overheadBytes = FALLBACK_OVERHEAD_BYTES
    private val pending = linkedMapOf<Long, Pending>()
    private var timeout: Job? = null

    /** Never enter the carrier while holding stateLock: carrier callbacks have their own gate. */
    fun install(value: WebSocket, hold: Boolean) {
      val reject = synchronized(stateLock) {
        if (ended || ownerClosed()) true
        else { if (hold) this.hold = value else socket = value; false }
      }
      if (reject) value.cancel()
    }

    fun requireActive() {
      synchronized(stateLock) { check(!ended && !ownerClosed()) { "终端连接已结束" } }
      synchronized(lock) { checkOpen() }
    }

    fun retained(value: JSONObject) {
      try {
        val accepted = synchronized(stateLock) {
          if (ended) return
          if (retained || string(value, "type") != "retained") invalid("终端 retain 确认无效")
          retained = true
          true
        }
        if (accepted) retentionReady.complete(Unit)
      } catch (_: Exception) { finish("DSH 终端保留协议无效") }
    }

    fun holdEnded(error: Throwable?) { finish("DSH 终端保留流已结束；没有自动重连") }
    fun streamEnded(error: Throwable?) {
      val processEnded = synchronized(stateLock) { mutableState.value.info?.status in setOf("failed", "exited") }
      finish(if (error == null && processEnded) null else "DSH 终端连接已结束；没有自动重连")
    }

    fun frame(value: JSONObject) {
      try {
        var snapshot = false
        synchronized(stateLock) {
          if (ended) return
          if (ownerClosed()) invalid("DSH 终端所有者已关闭")
          val type = string(value, "type")
          if (!snapshotSeen && type != "snapshot") invalid("终端首帧必须为 snapshot")
          when (type) {
            "snapshot" -> {
              if (snapshotSeen) invalid("终端 snapshot 重复")
              val sequence = integer(value, "sequence")
              val screen = string(value, "screen")
              val info = terminal(obj(value.opt("info")), scope, id)
              val writable = boolean(value, "writable")
              if (value.has("inputWindow")) {
                val window = obj(value.opt("inputWindow"))
                windowBytes = integer(window, "maxBytes", 1)
                overheadBytes = integer(window, "frameOverheadBytes")
              }
              outputSequence = sequence
              snapshotSeen = true
              snapshot = true
              val (tail, dropped) = boundedTail(screen)
              mutableState.value = mutableState.value.copy(ready = true, connected = true, output = tail,
                cursor = sequence, writable = writable && info.status == "running", info = info, truncated = dropped)
            }
            "output" -> {
              val sequence = integer(value, "sequence")
              if (outputSequence == MAX_SAFE_INTEGER || sequence != outputSequence + 1) invalid("终端输出序号不连续")
              val (tail, dropped) = boundedTail(string(value, "data"))
              val old = mutableState.value
              val (prefix, prefixDropped) = boundedTail(old.output, OUTPUT_BYTES - utf8Bytes(tail).toInt())
              outputSequence = sequence
              mutableState.value = old.copy(output = prefix + tail, cursor = sequence,
                truncated = old.truncated || dropped || prefixDropped)
            }
            "state" -> {
              val info = terminal(obj(value.opt("info")), scope, id)
              val writable = boolean(value, "writable")
              val old = mutableState.value
              mutableState.value = old.copy(info = info,
                writable = writable && info.status == "running" && old.inputRefusal == null)
            }
            "ack" -> {
              val sequence = integer(value, "sequence", 1)
              val entry = pending[sequence]
              if (acknowledged == MAX_SAFE_INTEGER || sequence != acknowledged + 1 || entry == null) invalid("终端 ACK 序号无效")
              val refusal = if (value.has("refusal")) string(value, "refusal").also {
                if (it != "read-only" && it != "not-running") invalid("终端 ACK 拒绝原因无效")
              } else null
              pending.remove(sequence)
              acknowledged = sequence
              inputBytes -= entry.inputBytes
              bufferedBytes -= entry.frameBytes
              val old = mutableState.value
              mutableState.value = old.copy(pendingInputs = pending.size, acknowledgedSequence = sequence,
                writable = old.writable && refusal == null, inputRefusal = refusal ?: old.inputRefusal)
              if (pending.isEmpty()) { timeout?.cancel(); timeout = null }
            }
            else -> invalid("未知终端帧")
          }
        }
        if (snapshot) snapshotReady.complete(Unit)
      } catch (_: Exception) { finish("DSH 终端帧无效；没有重试或重放输入") }
    }

    override fun sendText(text: String): Boolean = send("write", text, 0, 0)
    override fun resize(cols: Int, rows: Int): Boolean = send("resize", null, cols, rows)

    private fun send(type: String, text: String?, cols: Int, rows: Int): Boolean = synchronized(sendLock) {
      val prepared = synchronized(stateLock) {
        val old = mutableState.value
        if (ended || ownerClosed() || !old.ready || !old.writable || socket == null) return@synchronized null
        if (type == "write" && text.isNullOrEmpty()) return@synchronized null
        if (type == "resize" && (cols !in 2..env.maxCols || rows !in 1..env.maxRows)) {
          mutableState.value = old.copy(error = "终端尺寸超出服务器范围；未发送")
          return@synchronized null
        }
        if (inputSequence == MAX_SAFE_INTEGER) {
          mutableState.value = old.copy(writable = false, error = "终端输入序号已耗尽")
          return@synchronized null
        }
        val sequence = inputSequence + 1
        val command = JSONObject().put("type", type).put("sequence", sequence)
        if (type == "write") command.put("data", text) else command.put("cols", cols).put("rows", rows)
        val json = command.toString()
        val raw = text?.let(::utf8Bytes) ?: 0L
        val encoded = utf8Bytes(json)
        if (raw > env.maxInputBytes - inputBytes || overheadBytes > MAX_SAFE_INTEGER - encoded) {
          mutableState.value = old.copy(error = "终端输入缓冲区已满；未发送")
          return@synchronized null
        }
        val frameBytes = encoded + overheadBytes
        // No unsent queue. Everything accepted fits both encoded-byte windows immediately.
        if (frameBytes > env.maxBufferedBytes - bufferedBytes || frameBytes > windowBytes - bufferedBytes) {
          mutableState.value = old.copy(error = "终端输入缓冲区已满；未发送")
          return@synchronized null
        }
        inputSequence = sequence
        inputBytes += raw
        bufferedBytes += frameBytes
        // Reserve BEFORE send: fake/fast carriers may synchronously ACK inside send().
        pending[sequence] = Pending(raw, frameBytes, System.nanoTime())
        mutableState.value = old.copy(pendingInputs = pending.size, error = null)
        armTimeout()
        socket!! to json
      } ?: return@synchronized false
      val accepted = try { prepared.first.send(prepared.second) } catch (_: Exception) { false }
      if (!accepted) finish("终端输入交付状态未知；没有重试或重放", forceUnknown = true)
      accepted
    }

    /** Called under stateLock; timers only inspect state and never call the carrier under that lock. */
    private fun armTimeout() {
      if (timeout != null) return
      timeout = lifetime.launch {
        while (true) {
          val due = synchronized(stateLock) {
            if (ended || pending.isEmpty()) return@launch
            pending.keys.first() to (TIMEOUT_MS - (System.nanoTime() - pending.values.first().since) / 1_000_000L)
          }
          if (due.second <= 0) {
            finish("终端输入 ACK 超时；交付状态未知，没有重放", expiredSequence = due.first)
            return@launch
          }
          delay(due.second)
        }
      }
    }

    private fun finish(message: String? = null, forceUnknown: Boolean = false, expiredSequence: Long? = null) {
      val handles = synchronized(stateLock) {
        if (ended) return
        // An ACK can win between the timer's observation and this lock acquisition.
        if (expiredSequence != null && pending.keys.firstOrNull() != expiredSequence) {
          timeout = null
          if (pending.isNotEmpty()) armTimeout()
          return
        }
        ended = true
        timeout?.cancel()
        timeout = null
        val old = mutableState.value
        mutableState.value = old.copy(connected = false, ready = false, writable = false, ended = true,
          error = message ?: old.error, inputDeliveryUnknown = old.inputDeliveryUnknown || forceUnknown || pending.isNotEmpty())
        listOfNotNull(hold, socket).also { hold = null; socket = null }
      }
      retentionReady.completeExceptionally(ApiException(502, "终端保留流已结束"))
      snapshotReady.completeExceptionally(ApiException(502, "终端连接已结束"))
      handles.forEach { runCatching { it.cancel() } }
      synchronized(lock) { attachments.remove(this) }
    }

    override fun close() = finish()
  }

  companion object {
    private const val TIMEOUT_MS = 10_000L
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private const val OUTPUT_BYTES = 256 * 1024
    private const val FALLBACK_WINDOW_BYTES = 64 * 1024L
    private const val FALLBACK_OVERHEAD_BYTES = 1024L

    private fun terminal(value: JSONObject, scope: TerminalScope.Session, expected: String? = null): NativeTerminal {
      val id = identity(string(value, "id"))
      if (expected != null && id != expected) invalid("终端身份不匹配")
      val shell = obj(value.opt("shell"))
      val args = shell.opt("args") as? JSONArray ?: invalid("终端 shell.args 必须为数组")
      string(shell, "name")
      val status = string(value, "state")
      if (status !in setOf("running", "failed", "exited")) invalid("终端状态无效")
      if (!value.has("exitCode")) invalid("终端缺少 exitCode")
      val exit = if (value.isNull("exitCode")) null else integer(value, "exitCode", Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
      if (value.has("error")) string(value, "error")
      return NativeTerminal(id, string(value, "title"), scope.directory, status, string(shell, "path"),
        (0 until args.length()).map { args.opt(it) as? String ?: invalid("终端 shell.args 条目必须为字符串") },
        0L, exit, string(value, "cwd"), scope.sessionId,
        integer(value, "cols", 2, Int.MAX_VALUE.toLong()).toInt(),
        integer(value, "rows", 1, Int.MAX_VALUE.toLong()).toInt(), pidKnown = false)
    }

    private fun identity(value: String): String {
      require(value.isNotBlank() && value.length <= 1024 && value.none { it.code < 0x20 || it.code == 0x7f }) { "终端或 Session ID 无效" }
      return value
    }
    private fun obj(value: Any?): JSONObject = value as? JSONObject ?: invalid("终端响应必须为对象")
    private fun string(value: JSONObject, name: String): String = value.opt(name) as? String ?: invalid("终端 $name 必须为字符串")
    private fun boolean(value: JSONObject, name: String): Boolean = value.opt(name) as? Boolean ?: invalid("终端 $name 必须为布尔值")
    private fun integer(value: JSONObject, name: String, min: Long = 0, max: Long = MAX_SAFE_INTEGER): Long {
      val number = value.opt(name) as? Number ?: invalid("终端 $name 必须为整数")
      val result = try { java.math.BigDecimal(number.toString()).longValueExact() } catch (_: Exception) { invalid("终端 $name 必须为安全整数") }
      if (result !in min..max) invalid("终端 $name 超出安全范围")
      return result
    }
    private fun utf8Bytes(value: String): Long = value.toByteArray(Charsets.UTF_8).size.toLong()
    private fun invalid(message: String): Nothing = throw ApiException(502, message)

    /** Keep a code-point-aligned raw VT/ANSI tail, never a synthesized screen or split surrogate. */
    private fun boundedTail(value: String, maxBytes: Int = OUTPUT_BYTES): Pair<String, Boolean> {
      var start = value.length
      var bytes = 0
      while (start > 0) {
        val point = value.codePointBefore(start)
        val width = when { point <= 0x7f -> 1; point <= 0x7ff -> 2; point <= 0xffff -> 3; else -> 4 }
        if (bytes + width > maxBytes) break
        bytes += width
        start -= Character.charCount(point)
      }
      return value.substring(start) to (start > 0)
    }
  }
}
