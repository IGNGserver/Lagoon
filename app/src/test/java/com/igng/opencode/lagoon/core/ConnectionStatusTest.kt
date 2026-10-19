package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStatusTest {
  private fun banner(stage: ConnectStage, elapsed: Long, retryIn: Long = 0, stream: Boolean = true, streamDownFor: Long = 0,
    transcriptFor: Long = -1): ConnectionBanner? {
    val now = 100_000L
    return ConnectionBanner.of(ConnectionStatus(stage, now - elapsed, if (retryIn > 0) now + retryIn else 0, "无法连接服务器"), "家里", now,
      stream, if (streamDownFor > 0) now - streamDownFor else 0,
      transcriptSyncing = transcriptFor >= 0, transcriptSince = if (transcriptFor >= 0) now - transcriptFor else 0)
  }

  @Test fun quickStagesStayQuiet() {
    assertNull(banner(ConnectStage.CONNECTING, 300))
    assertNull(banner(ConnectStage.READING, 599))
    assertNull(banner(ConnectStage.READY, 60_000))
    assertNull(banner(ConnectStage.IDLE, 60_000))
  }

  @Test fun eachStageNamesWhatItWaitsFor() {
    assertEquals("正在连接 家里…", banner(ConnectStage.CONNECTING, 1_000)?.text)
    assertEquals("正在读取会话…", banner(ConnectStage.READING, 1_000)?.text)
    assertEquals("正在同步任务状态…", banner(ConnectStage.SYNCING, 1_000)?.text)
    assertEquals(ConnectionBanner.Tone.PROGRESS, banner(ConnectStage.READING, 1_000)?.tone)
  }

  @Test fun aSlowStageSaysSoAndOffersARetry() {
    val slow = banner(ConnectStage.CONNECTING, 9_500)!!
    assertTrue(slow.text, slow.text.startsWith("家里 响应较慢") && slow.text.contains("已等待 9 秒"))
    assertTrue(slow.retry)
    assertEquals(ConnectionBanner.Tone.WARNING, slow.tone)
  }

  @Test fun retryingCountsDownWithTheReason() {
    val retrying = banner(ConnectStage.RETRYING, 0, retryIn = 3_200)!!
    assertEquals("无法连接服务器 · 4 秒后重试", retrying.text)
    assertTrue(retrying.retry)
    assertEquals("无法连接服务器 · 正在重试", banner(ConnectStage.RETRYING, 0)?.text)
  }

  @Test fun aBriefStreamDropIsNotAnnouncedButALongOneIs() {
    assertNull(banner(ConnectStage.READY, 0, stream = false, streamDownFor = 2_000))
    assertEquals("实时同步恢复中…", banner(ConnectStage.READY, 0, stream = false, streamDownFor = 5_000)?.text)
  }

  @Test fun cachedTranscriptBeingRefreshedIsAnnounced() {
    assertNull(banner(ConnectStage.READY, 0, transcriptFor = 100))
    assertEquals("正在同步消息…", banner(ConnectStage.READY, 0, transcriptFor = 1_000)?.text)
  }

  @Test fun transcriptMemoryIsBoundedAndKeepsTheNewestRows() {
    val memory = TranscriptMemory(capacity = 2, maxMessages = 2)
    val rows = (1..3).map { Message("m$it", "user", it.toLong(), emptyList()) }
    memory.put("a", "s1", rows); memory.put("a", "s2", rows); memory.get("a", "s1"); memory.put("b", "s3", rows)
    assertEquals(listOf("m2", "m3"), memory.get("a", "s1")?.map { it.id })
    assertNull(memory.get("a", "s2"))
    memory.forgetServer("a")
    assertNull(memory.get("a", "s1"))
    assertEquals(2, memory.get("b", "s3")?.size)
  }
}
