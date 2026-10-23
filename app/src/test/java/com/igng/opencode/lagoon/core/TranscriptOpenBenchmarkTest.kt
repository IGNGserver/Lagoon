package com.igng.opencode.lagoon.core

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Weak-network harness for opening a conversation. Each request waits [LATENCY_MS] before the reply
 * starts, like a slow mobile link; the numbers are printed so changes to the open path can be compared.
 */
class TranscriptOpenBenchmarkTest {
  data class Run(val label: String, val millis: Long, val requests: Int, val bytes: Long, val messages: Int) {
    override fun toString() = "$label: ${millis}ms · 请求 $requests · 下行 ${formatBytes(bytes)} · 消息 $messages"
  }

  /** The open path before the transcript cache: newest page, then older pages until a whole turn starts the window. */
  private suspend fun legacyOpen(api: HarnessClient, sessionId: String): List<Message> = coroutineScope {
    val inbox = async { api.inbox(sessionId) }
    val first = api.messagesPage(sessionId)
    val merged = first.items.toMutableList()
    var cursor = first.next
    var extra = 0
    while (cursor != null && extra < 5 && needsOlderTurnRoot(merged)) {
      val older = api.messagesPage(sessionId, cursor, HarnessClient.MESSAGE_PAGE)
      if (older.items.isEmpty()) break
      merged.addAll(0, older.items)
      cursor = older.next
      extra++
    }
    inbox.await()
    merged
  }

  private fun <T> measure(label: String, server: FakeTimelineServer, block: suspend () -> List<T>): Run = runBlocking {
    val requests = server.messageRequests.get()
    val bytes = HttpMetrics.bytes()
    val started = System.nanoTime()
    val result = block()
    Run(label, (System.nanoTime() - started) / 1_000_000, server.messageRequests.get() - requests, HttpMetrics.bytes() - bytes, result.size)
  }

  @Test fun legacyOpenOfAToolHeavyConversation() {
    FakeTimelineServer(LATENCY_MS).use { server ->
      // 40 turns of one prompt + 30 tool steps: the newest 20 messages start mid-turn.
      server.timeline += FakeTimelineServer.conversation(turns = 40, steps = 30)
      val api = server.api()
      val run = measure("旧路径 · 打开会话", server) { legacyOpen(api, "ses") }
      println(run)
      assertTrue("tool-heavy turns need extra sequential pages: $run", run.requests >= 2)
    }
  }

  companion object {
    const val LATENCY_MS = 300L
  }
}
