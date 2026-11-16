package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.SshFailure
import com.igng.opencode.lagoon.core.SshFailureKind
import com.igng.opencode.lagoon.core.SshHostConfig
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Server-provided prompt text is untrusted. Answers never enter this observable state. */
internal data class SshKeyboardPrompt(val text: String, val echo: Boolean)
internal data class SshKeyboardChallenge(
  val id: String,
  val destination: String,
  val name: String,
  val instruction: String,
  val prompts: List<SshKeyboardPrompt>
)

/**
 * Bridges JSch's blocking callback on an IO thread to a foreground-only Compose dialog.
 * Responses are transient, bounded, and never stored with an SSH profile. A backgrounded app,
 * cancellation, or timeout releases the caller and prevents a later dialog answer being reused.
 */
internal class SshKeyboardInteractiveBroker(private val timeoutMillis: Long = 90_000) {
  private class Pending(val challenge: SshKeyboardChallenge) {
    val latch = CountDownLatch(1)
    var result: Array<String>? = null
    var error: SshFailure? = null
    var finished = false
  }

  private val lock = Any()
  private val waiting = LinkedHashMap<String, Pending>()
  private val mutableChallenges = MutableStateFlow<List<SshKeyboardChallenge>>(emptyList())
  val challenges: StateFlow<List<SshKeyboardChallenge>> = mutableChallenges.asStateFlow()
  private var foreground = false

  fun setForeground(active: Boolean) = synchronized(lock) {
    foreground = active
    if (!active) waiting.values.toList().forEach {
      finish(it, null, failure("SSH 需要额外验证，请回到应用后重试"))
    }
  }

  fun request(
    config: SshHostConfig,
    name: String?,
    instruction: String?,
    prompts: Array<out String>?,
    echo: BooleanArray?
  ): Array<String> {
    val raw = prompts ?: emptyArray()
    if (raw.isEmpty()) return emptyArray()
    if (raw.size > MAX_PROMPTS || echo == null || echo.size != raw.size)
      throw failure("SSH 服务器要求的验证格式无法识别，请在电脑上检查认证设置")
    val challenge = SshKeyboardChallenge(UUID.randomUUID().toString(), clean(config.address, 512),
      clean(name, 512), clean(instruction, 2048), raw.mapIndexed { index, text ->
        SshKeyboardPrompt(clean(text, 512).ifBlank { "请输入验证信息" }, echo[index])
      })
    val pending = Pending(challenge)
    synchronized(lock) {
      if (!foreground) throw failure("SSH 需要额外验证，请回到应用后重试")
      if (waiting.size >= MAX_WAITING) throw failure("有其他 SSH 验证正在等待，请稍后重试")
      waiting[challenge.id] = pending
      publish()
    }
    try {
      val completed = try { pending.latch.await(timeoutMillis, TimeUnit.MILLISECONDS) }
      catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw java.util.concurrent.CancellationException("SSH 验证已取消").apply { initCause(error) }
      }
      if (!completed) throw failure("SSH 验证等待超时，请重试")
      synchronized(lock) {
        pending.error?.let { throw it }
        return pending.result ?: throw failure("SSH 验证已取消，请重试")
      }
    } finally {
      synchronized(lock) {
        waiting.remove(challenge.id, pending)
        pending.result = null
        pending.finished = true
        publish()
      }
    }
  }

  /** Returns false for an expired, cancelled, or invalid response. */
  fun respond(id: String, answers: List<String>?): Boolean = synchronized(lock) {
    val pending = waiting[id] ?: return false
    if (answers != null && (answers.size != pending.challenge.prompts.size || answers.any { it.length > MAX_ANSWER })) return false
    finish(pending, answers?.toTypedArray(), if (answers == null) failure("SSH 验证已取消，请重试") else null)
    true
  }

  private fun finish(pending: Pending, result: Array<String>?, error: SshFailure?) {
    if (pending.finished) return
    pending.finished = true
    pending.result = result
    pending.error = error
    waiting.remove(pending.challenge.id, pending)
    publish()
    pending.latch.countDown()
  }

  private fun publish() { mutableChallenges.value = waiting.values.map { it.challenge } }

  private fun failure(message: String) = SshFailure(SshFailureKind.AUTH_INTERACTION_REQUIRED, message, retryable = false)

  private fun clean(text: String?, max: Int): String = text.orEmpty()
    .filter { (!it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt()) || it == '\n' || it == '\t' }
    .take(max)

  private companion object {
    const val MAX_PROMPTS = 8
    const val MAX_WAITING = 8
    const val MAX_ANSWER = 4096
  }
}
