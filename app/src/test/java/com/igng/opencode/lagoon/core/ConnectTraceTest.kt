package com.igng.opencode.lagoon.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ConnectTraceTest {
  private var now = 0L
  private val counters = HttpCounters()
  private val trace = ConnectTrace(clock = { now }, metrics = counters, keep = 2)

  @Test fun recordsOnlyTheFirstArrivalOfEachMilestone() {
    trace.begin(1, "连接")
    now = 120
    assertEquals(120L, trace.mark(1, ConnectMilestone.CATALOG))
    now = 400
    assertNull(trace.mark(1, ConnectMilestone.CATALOG))
    assertEquals(400L, trace.mark(1, ConnectMilestone.INTERACTIVE))
    val report = trace.reports().single()
    assertEquals(mapOf(ConnectMilestone.CATALOG to 120L, ConnectMilestone.INTERACTIVE to 400L), report.elapsed)
    assertTrue(report.line(), report.line().contains("会话列表 120ms") && report.line().contains("可交互 400ms"))
  }

  @Test fun aSupersededAttemptCannotWriteIntoTheCurrentOne() {
    trace.begin(1, "连接")
    trace.begin(2, "重连")
    now = 50
    assertNull(trace.mark(1, ConnectMilestone.STREAM))
    trace.fail(1, "旧的失败")
    val current = trace.reports().first()
    assertEquals("重连", current.reason)
    assertTrue(current.elapsed.isEmpty())
    assertNull(current.failure)
    assertEquals("连接", trace.reports()[1].reason)
  }

  @Test fun historyIsBoundedAndNewestFirst() {
    (1..5).forEach { trace.begin(it.toLong(), "第 $it 次") }
    assertEquals(listOf("第 5 次", "第 4 次", "第 3 次"), trace.reports().map { it.reason })
  }

  @Test fun countsRequestsAndPeakConcurrencyPerAttempt() {
    counters.track { }
    trace.begin(1, "连接")
    counters.track { counters.track { counters.track { } } }
    val report = trace.reports().single()
    assertEquals(3, report.requests)
    assertEquals(3, report.peakInFlight)
  }

  /**
   * Latency harness: a server with a fixed per-request delay shows how much of a fan-out actually
   * runs in parallel. The shared process-wide counters are read before and after.
   */
  @Test fun apiCallsAreCountedAndFanOutRunsConcurrently() = runBlocking {
    MockWebServer().use { server ->
      repeat(4) { server.enqueue(MockResponse().setBody("""{"data":[]}""").setBodyDelay(150, TimeUnit.MILLISECONDS)) }
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), "opencode", allowCleartext = true), "secret")
      val before = HttpMetrics.total()
      HttpMetrics.resetPeak()
      val started = System.nanoTime()
      listOf("/a", "/b", "/c", "/d").map { directory -> async(kotlinx.coroutines.Dispatchers.IO) { api.forms(directory) } }.awaitAll()
      val elapsed = (System.nanoTime() - started) / 1_000_000
      assertEquals(4, HttpMetrics.total() - before)
      assertTrue("peak=${HttpMetrics.peak()}", HttpMetrics.peak() >= 2)
      assertTrue("elapsed=${elapsed}ms", elapsed < 4 * 150)
    }
  }

  @Test fun payloadBytesAreCountedPerAttempt() = runBlocking {
    MockWebServer().use { server ->
      val body = """{"data":[],"padding":"${"x".repeat(5_000)}"}"""
      server.enqueue(MockResponse().setBody(body))
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), "opencode", allowCleartext = true), "secret")
      val trace = ConnectTrace(clock = { now }, metrics = HttpMetrics)
      trace.begin(1, "打开会话")
      api.forms("/repo")
      val report = trace.reports().first()
      assertEquals(body.length.toLong(), report.bytes)
      assertTrue(report.line(), report.line().contains("下行 4 KB"))
    }
  }
}
