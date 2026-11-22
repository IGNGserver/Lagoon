package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.ApiException
import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshFailureKind
import com.igng.opencode.lagoon.core.SshHostConfig
import com.igng.opencode.lagoon.core.SshLinkPhase
import com.igng.opencode.lagoon.core.SshLinkStatus
import com.igng.opencode.lagoon.core.SshLinkStep
import com.igng.opencode.lagoon.core.SshSecrets
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 统一的 SSH stdio JSONL / JSON-RPC 传输内核，被 Codex App Server、Antigravity ACP 与 OpenCode ACP 共用。
 *
 * 契约：
 * - 懒连接、单代次、永不自动重放写请求（超时或取消写入即终止整条通道，防止状态未知的重发）；
 * - 强制校验已固定 SSH 主机指纹（TOFU）；
 * - 实时发布 [status]（IDLE / CONNECTING / CONNECTED / FAILED），使全部 5 种 Harness 在设备视图与状态栏有一致的可观测性；
 * - 所有诊断文案统一使用调用方传入的 [label]，绝不硬编码特定 Harness 名称。
 */
internal class SshStdioRpcTransport(
  private val config: SshHostConfig,
  private val secrets: SshSecrets,
  private val factory: SshTransportFactory,
  /** Fixed app-owned executable command; never user profile text. */
  private val command: String,
  /** Short human-readable label for local diagnostics, e.g. "Codex", "Antigravity ACP", "OpenCode ACP". */
  val label: String,
  private val timeoutMillis: Long = 60_000L,
  private val onStatusChanged: (SshLinkStatus) -> Unit = {},
  private val onEvent: suspend (message: JSONObject, byteSize: Int) -> Unit,
  private val onTerminate: (IOException) -> Unit = {}
) : Closeable {
  init {
    require(command.isNotBlank() && command.length <= 256 && '\n' !in command && '\r' !in command) { "Invalid stdio command" }
    require(label.isNotBlank() && label.length <= 32) { "Invalid stdio transport label" }
  }

  private val gate = Mutex()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val closed = AtomicBoolean(false)
  private val nextId = AtomicLong(0)
  private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
  private val writerLock = Any()
  private val lifecycleLock = Any()

  @Volatile private var connection: SshTransport? = null
  @Volatile private var channel: SshExecChannel? = null
  @Volatile private var ready = false

  private val mutableStatus = MutableStateFlow(SshLinkStatus())
  val status: StateFlow<SshLinkStatus> = mutableStatus.asStateFlow()

  val isClosed: Boolean get() = closed.get()

  fun nextRequestId(): Long = nextId.incrementAndGet()

  fun activeConnection(): SshTransport =
    connection ?: throw IOException("$label SSH 连接已关闭")

  suspend fun ensureReady(handshake: suspend () -> Unit) = gate.withLock {
    check(!closed.get()) { "$label SSH 传输已关闭" }
    if (ready) return@withLock
    withContext(Dispatchers.IO) {
      val pin = config.hostKeyFingerprint?.takeIf { it.isNotBlank() }
        ?: throw SshFailure(SshFailureKind.HOST_KEY_UNKNOWN, "请先确认 SSH 主机指纹", retryable = false)
      publish(SshLinkPhase.CONNECTING, SshLinkStep.CONNECT, config.address)
      try {
        val lease = factory.connectInterruptibly(config, secrets, SshHostKeyVerifier { key ->
          if (key.fingerprint != pin) {
            throw SshFailure(
              SshFailureKind.HOST_KEY_CHANGED,
              "SSH 主机指纹已变化",
              retryable = false,
              presentedKey = key
            )
          }
          true
        })
        synchronized(lifecycleLock) {
          if (closed.get()) {
            lease.close()
            throw IOException("$label SSH 传输已关闭")
          }
          connection = lease
        }
        publish(SshLinkPhase.CONNECTING, SshLinkStep.TUNNEL, "正在启动 $label")
        // Fixed command only. No user-provided path or arguments are interpreted as shell code.
        val exec = lease.openExecChannel(SshExecutableSearch.prologue + "\nexec " + command)
        synchronized(lifecycleLock) {
          if (closed.get()) {
            exec.close()
            throw IOException("$label SSH 传输已关闭")
          }
          channel = exec
        }
        scope.launch { readMessages(exec) }
        // Drain stderr independently so diagnostics cannot stall stdout JSONL. Never log stderr.
        scope.launch {
          try {
            val buffer = ByteArray(8192)
            while (exec.stderr.read(buffer) >= 0) { /* discard */ }
          } catch (_: Exception) { /* ignore */ }
        }
        publish(SshLinkPhase.CONNECTING, SshLinkStep.LOGIN, "正在初始化 $label")
        handshake()
        check(!closed.get()) { "$label SSH 传输在初始化期间已关闭" }
        ready = true
        publish(SshLinkPhase.CONNECTED, SshLinkStep.CONNECTED, "$label 已连接 · SSH ${config.address}")
      } catch (error: Throwable) {
        val typed = extractSshFailure(error)
        terminate(IOException("$label SSH 连接失败", error), typed)
        throw error
      }
    }
  }

  suspend fun request(
    envelope: (id: Long) -> JSONObject,
    timeout: Long = timeoutMillis
  ): JSONObject {
    val id = nextId.incrementAndGet()
    val waiter = CompletableDeferred<JSONObject>()
    pending[id] = waiter
    try {
      return deadline(timeout) {
        send(envelope(id))
        waiter.await()
      }
    } finally {
      pending.remove(id, waiter)
    }
  }

  suspend fun beginRequest(envelope: (id: Long) -> JSONObject): CompletableDeferred<JSONObject> {
    val id = nextId.incrementAndGet()
    val waiter = CompletableDeferred<JSONObject>()
    pending[id] = waiter
    waiter.invokeOnCompletion { pending.remove(id, waiter) }
    try {
      deadline { send(envelope(id)) }
    } catch (error: Throwable) {
      pending.remove(id, waiter)
      waiter.completeExceptionally(error)
      throw error
    }
    return waiter
  }

  /** Deadline covers writing and awaiting a response. Timeout invalidates the whole channel. */
  suspend fun <T> deadline(timeout: Long = timeoutMillis, block: suspend () -> T): T = try {
    withTimeout(timeout) { block() }
  } catch (error: TimeoutCancellationException) {
    val failure = IOException("$label SSH 请求超时，结果未知；请重新连接并检查状态", error)
    terminate(failure, SshFailure(SshFailureKind.UNRESPONSIVE, "$label SSH 请求超时", retryable = true))
    throw failure
  }

  suspend fun send(value: JSONObject) {
    val bytes = (value.toString() + "\n").toByteArray(Charsets.UTF_8)
    if (bytes.size > MAX_MESSAGE_BYTES) throw IOException("$label request exceeds size limit")
    // Blocking socket write runs in transport scope so caller deadline can close the SSH channel.
    val write = scope.async {
      synchronized(writerLock) {
        check(!closed.get()) { "$label SSH 传输已关闭" }
        val out = channel?.stdin ?: throw IOException("$label SSH 通道不可用")
        try {
          out.write(bytes)
          out.flush()
        } catch (error: Exception) {
          terminate(IOException("$label SSH 写入失败", error), SshFailure(SshFailureKind.DISCONNECTED, "$label SSH 写入失败", retryable = true))
          throw error
        }
      }
    }
    try {
      write.await()
    } catch (error: CancellationException) {
      terminate(IOException("$label SSH 写入中断，结果未知", error), SshFailure(SshFailureKind.DISCONNECTED, "$label SSH 连接已中断", retryable = true))
      if (currentCoroutineContext().isActive) throw IOException("$label SSH 通道已关闭，结果未知", error)
      throw error
    }
  }

  private suspend fun readMessages(exec: SshExecChannel) {
    try {
      val input = exec.stdout.buffered()
      while (!closed.get()) {
        val bytes = ByteArrayOutputStream()
        while (true) {
          val next = input.read()
          if (next < 0) throw EOFException("$label SSH stream ended")
          if (next == 10) break
          if (bytes.size() >= MAX_MESSAGE_BYTES) throw IOException("$label message exceeds size limit")
          bytes.write(next)
        }
        val decoder = Charsets.UTF_8.newDecoder()
        val line = decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        val tokener = JSONTokener(line)
        val message = tokener.nextValue() as? JSONObject ?: throw IOException("Invalid $label JSON frame")
        if (tokener.nextClean() != '\u0000') throw IOException("Invalid $label JSON frame")
        val method = message.opt("method") as? String
        if (method != null) {
          onEvent(message, bytes.size())
        } else {
          val id = (message.opt("id") as? Number)?.toLong() ?: continue
          val waiter = pending[id] ?: continue // late replies after timeout are ignored
          val error = message.optJSONObject("error")
          if (error != null) {
            val code = error.optInt("code")
            waiter.completeExceptionally(ApiException(code, error.optString("message", "$label request failed"), code.toString()))
          } else {
            val result = message.optJSONObject("result")
            if (result == null) waiter.completeExceptionally(IOException("Invalid $label response"))
            else waiter.complete(result)
          }
        }
      }
    } catch (error: Exception) {
      val sshError = extractSshFailure(error)
        ?: SshFailure(SshFailureKind.DISCONNECTED, "$label SSH 连接已断开", error.message.orEmpty(), retryable = true)
      terminate(IOException("$label SSH stream closed", error), sshError)
    }
  }

  fun terminate(error: IOException, sshFailure: SshFailure? = null, explicitClose: Boolean = false) {
    val owned = synchronized(lifecycleLock) {
      if (!closed.compareAndSet(false, true)) return
      ready = false
      val pair = channel to connection
      channel = null
      connection = null
      pair
    }
    runCatching { owned.first?.close() }
    runCatching { owned.second?.close() }
    pending.values.forEach { it.completeExceptionally(error) }
    pending.clear()
    runCatching { onTerminate(error) }
    if (explicitClose) {
      publish(SshLinkPhase.IDLE, SshLinkStep.CONNECT, "")
    } else {
      val failure = sshFailure ?: extractSshFailure(error)
        ?: SshFailure(SshFailureKind.DISCONNECTED, "$label 连接已断开", error.message.orEmpty(), retryable = true)
      publish(SshLinkPhase.FAILED, mutableStatus.value.step, failure.display(), failure)
    }
    scope.cancel()
  }

  override fun close() = terminate(IOException("$label SSH transport closed"), explicitClose = true)

  private fun publish(phase: SshLinkPhase, step: SshLinkStep, detail: String, error: SshFailure? = null) {
    val next = SshLinkStatus(phase, step, detail, error)
    mutableStatus.value = next
    onStatusChanged(next)
  }

  private fun extractSshFailure(error: Throwable): SshFailure? {
    var cur: Throwable? = error
    while (cur != null) {
      if (cur is SshFailure) return cur
      cur = cur.cause
    }
    return null
  }

  companion object {
    const val MAX_MESSAGE_BYTES = 32 * 1024 * 1024
  }
}
