package com.igng.lagoon.hub

import com.igng.opencode.lagoon.core.ServerOverviewSnapshot
import com.igng.opencode.lagoon.core.displaySummary
import com.igng.opencode.lagoon.core.displayTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Decides which snapshot changes become a push, with the phone's notification rules:
 * - a root session's unread result (completed / failed) that appeared after the hub started watching;
 * - a permission request or question that is still pending [ATTENTION_DEBOUNCE_MS] after it appeared,
 *   so prompts that are answered (or auto-approved) right away never buzz anyone.
 * Servers whose profile turns notifications off are silent. Runs on the runtime thread.
 */
internal class Notifier(
  private val scope: CoroutineScope,
  private val store: HubStore,
  private val push: PushService?,
  private val current: (String) -> ServerOverviewSnapshot?
) {
  private val attention = mutableMapOf<String, Job>()

  fun consider(previous: ServerOverviewSnapshot?, next: ServerOverviewSnapshot) {
    // The first snapshot of a server is the baseline: historic rows never notify.
    if (push == null || previous == null || !next.profile.notifications) return
    val oldNotices = previous.notices.map { it.id }.toSet()
    next.notices.filter { it.id !in oldNotices && !it.viewed }.forEach { notice ->
      if (next.sessions.none { it.id == notice.sessionId }) return@forEach
      send(next, notice.sessionId, if (notice.error) "执行失败" else "任务已完成", "result", null, "normal")
    }
    val policy = store.permissionPolicy()
    val oldPermissions = previous.permissions.map { it.id }.toSet()
    val newlyFailed = next.autoApprovalFailures - previous.autoApprovalFailures
    next.permissions.filter { (it.id !in oldPermissions || it.id in newlyFailed) && policy.visible(it.id, next.autoApprovalFailures) }.forEach { request ->
      later(next.serverId, request.id) {
        val live = current(next.serverId) ?: return@later
        val pending = live.permissions.firstOrNull { it.id == request.id } ?: return@later
        if (!store.permissionPolicy().visible(pending.id, live.autoApprovalFailures)) return@later
        val detail = pending.detail.lineSequence().firstOrNull().orEmpty().take(120)
        send(live, pending.sessionId, "等待授权：${pending.action}" + (if (detail.isNotBlank()) " · $detail" else ""), "permission", pending.id, "high")
      }
    }
    val oldQuestions = previous.questions.map { it.id }.toSet()
    next.questions.filter { it.id !in oldQuestions }.forEach { request ->
      later(next.serverId, request.id) {
        val live = current(next.serverId) ?: return@later
        val pending = live.questions.firstOrNull { it.id == request.id } ?: return@later
        val title = pending.questions.firstOrNull()?.title.orEmpty().take(120)
        send(live, pending.sessionId, "等待你的回答" + (if (title.isNotBlank()) "：$title" else ""), "question", pending.id, "high")
      }
    }
  }

  fun forget(serverId: String) {
    attention.keys.filter { it.startsWith("$serverId\u0000") }.forEach { attention.remove(it)?.cancel() }
  }

  private fun later(serverId: String, requestId: String, block: () -> Unit) {
    val key = "$serverId\u0000$requestId"
    if (attention[key]?.isActive == true) return
    attention[key] = scope.launch {
      delay(ATTENTION_DEBOUNCE_MS)
      attention.remove(key)
      block()
    }
  }

  private fun send(snapshot: ServerOverviewSnapshot, sessionId: String, body: String, kind: String, requestId: String?, urgency: String) {
    val service = push ?: return
    if (store.subscriptions().isEmpty()) return
    val session = snapshot.sessions.firstOrNull { it.id == sessionId }
    val root = generateSequence(sessionId) { snapshot.parents[it] }.take(32).last()
    val title = (snapshot.sessions.firstOrNull { it.id == root } ?: session)?.displayTitle() ?: "Lagoon"
    val multiServer = store.profiles().size > 1
    val message = JSONObject()
      .put("title", title)
      .put("body", if (multiServer) "${snapshot.profile.name} · $body" else body)
      .put("url", "/s/${encode(snapshot.serverId)}/${encode(root)}")
      .put("tag", "${snapshot.serverId}:$root")
      .put("kind", kind).put("serverId", snapshot.serverId).put("sessionId", root)
      .put("badge", badgeCount())
      .apply { requestId?.let { put("requestId", it) } }
    // Delivery is network I/O to the browser vendors' push services; keep it off the runtime thread.
    scope.launch(Dispatchers.IO) { service.send(message, urgency, topic = topicOf("${snapshot.serverId}:$root")) }
  }

  /** What still needs the user across servers with notifications on; shown as the app icon badge. */
  private fun badgeCount(): Int {
    val policy = store.permissionPolicy()
    return store.profiles().filter { it.notifications }.mapNotNull { current(it.id) }.sumOf { snapshot ->
      val summary = snapshot.displaySummary(policy)
      summary.waiting + summary.completed + summary.failed
    }
  }

  private fun encode(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

  /** Push services collapse undelivered messages with the same topic (at most 32 URL-safe characters). */
  private fun topicOf(key: String): String = B64.url(java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray())).take(32)

  companion object {
    const val ATTENTION_DEBOUNCE_MS = 1_500L
  }
}
