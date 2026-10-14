package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存有单条硬上限：超限时必须保留最新后缀并标记不完整，而不是整条丢弃、让旧缓存继续
 * 以 complete=true 冒充完整会话；小缓存则必须原样保留 queued/retry 等状态字段。
 */
class OfflineCacheTruncationTest {
  private fun cache() = OfflineCache(memoryPreferences(), { it }, { it })

  @Test fun oversizedTranscriptsKeepTheNewestSuffixAndMarkThemselvesIncomplete() = runBlocking {
    val cache = cache()
    // 单条 part 会被截到 20k，所以“整条缓存超限”来自条数：100 条 × 8k 字符 ≈ 80 万字符。
    val messages = List(100) { index -> Message("m$index", "assistant", index.toLong(), listOf(MessagePart("p$index", "text", text = "x".repeat(8_000)))) }
    cache.saveMessages("s", "sess", messages, complete = true)
    cache.awaitWrites()
    val restored = cache.messages("s", "sess")
    assertTrue("应保留最新后缀而不是整条丢弃", restored.isNotEmpty())
    assertTrue("应丢弃最旧的部分", restored.size < messages.size)
    assertEquals(messages.last().id, restored.last().id)
    assertFalse("截断后的缓存必须标记为不完整", cache.messagesComplete("s", "sess"))
  }

  @Test fun aSmallTranscriptStaysCompleteAndKeepsQueuedAndRetryFields() = runBlocking {
    val cache = cache()
    val message = Message("m", "user", 1, listOf(MessagePart("p", "text", text = "hi")), retry = "第 1 次重试", queued = true)
    cache.saveMessages("s", "sess", listOf(message), complete = true)
    cache.awaitWrites()
    val restored = cache.messages("s", "sess").single()
    assertEquals("第 1 次重试", restored.retry)
    assertTrue(restored.queued)
    assertTrue(cache.messagesComplete("s", "sess"))
  }
}
