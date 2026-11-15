package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RemoteCompactionTrackerTest {
  private val owner = "0b9d2c4e-1111-4222-8333-444455556666"

  @Test fun lostSubmissionIsNotReplayedAfterReconnect() = runBlocking {
    val store = MemoryRemoteCompactionStore()
    val requests = mutableListOf<String>()
    var online = false
    val call: suspend (String, JSONObject) -> Any? = { method, params ->
      when (method) {
        "harness.remote.compaction.submit" -> {
          requests += params.getString("requestId")
          if (!online) throw IOException("response lost")
          JSONObject().put("operationId", "op-1").put("state", "accepted")
        }
        "harness.remote.compaction.status" -> JSONObject().put("operationId", "op-1")
          .put("sessionId", "session-1").put("state", "succeeded")
        else -> error(method)
      }
    }
    val first = RemoteCompactionTracker("host-a", owner, store, call, now = { 1_000 }, pause = {})
    assertTrue(runCatching { first.compact("session-1") }.exceptionOrNull() is RemoteFailureException)
    assertNull(store.load("host-a", "session-1")?.operationId)
    online = true
    val resumed = RemoteCompactionTracker("host-a", owner, store, call, now = { 2_000 }, pause = {})
    resumed.resumePending()
    assertEquals(1, requests.size)
    assertNotNull(store.load("host-a", "session-1"))
    assertTrue(runCatching { resumed.compact("session-1") }.exceptionOrNull() is RemoteFailureException)
    assertNull(store.load("host-a", "session-1"))
  }

  @Test fun pollingFailureKeepsOperationAndResumesWithoutSubmittingAgain() = runBlocking {
    val store = MemoryRemoteCompactionStore()
    var online = false
    var submissions = 0
    val call: suspend (String, JSONObject) -> Any? = { method, _ ->
      when (method) {
        "harness.remote.compaction.submit" -> {
          submissions++
          JSONObject().put("operationId", "op-2").put("state", "accepted")
        }
        "harness.remote.compaction.status" -> {
          if (!online) throw IOException("disconnected")
          JSONObject().put("operationId", "op-2").put("sessionId", "session-2").put("state", "succeeded")
        }
        else -> error(method)
      }
    }
    val tracker = RemoteCompactionTracker("host-a", owner, store, call, pause = {})
    assertTrue(runCatching { tracker.compact("session-2") }.exceptionOrNull() is RemoteFailureException)
    assertEquals("op-2", store.load("host-a", "session-2")?.operationId)
    online = true
    RemoteCompactionTracker("host-a", owner, store, call, pause = {}).resumePending()
    assertEquals(1, submissions)
    assertNull(store.load("host-a", "session-2"))
  }

  @Test fun unconfirmedIsReportedAndNeverPresentedAsSuccess() = runBlocking {
    val store = MemoryRemoteCompactionStore()
    val tracker = RemoteCompactionTracker("host-a", owner, store, call = { method, _ ->
      when (method) {
        "harness.remote.compaction.submit" -> JSONObject().put("operationId", "op-3").put("state", "accepted")
        "harness.remote.compaction.status" -> JSONObject().put("operationId", "op-3")
          .put("sessionId", "session-3").put("state", "unconfirmed")
        else -> error(method)
      }
    }, pause = {})
    val error = runCatching { tracker.compact("session-3") }.exceptionOrNull()
    assertTrue(error is RemoteFailureException)
    assertTrue(error?.message.orEmpty().contains("无法确认"))
  }

  @Test fun cancellationDoesNotCancelHostOperationAndKeepsRecord() = runBlocking {
    val store = MemoryRemoteCompactionStore()
    val polling = CompletableDeferred<Unit>()
    val calls = mutableListOf<String>()
    val tracker = RemoteCompactionTracker("host-a", owner, store, call = { method, _ ->
      calls += method
      JSONObject().put("operationId", "op-4").put("state", "accepted")
    }, pause = { polling.complete(Unit); awaitCancellation() })
    val job = launch { tracker.compact("session-4") }
    polling.await()
    job.cancel()
    job.join()
    assertEquals("op-4", store.load("host-a", "session-4")?.operationId)
    assertFalse(calls.contains("harness.remote.compaction.cancel"))
  }

  @Test fun concurrentRequestsDoNotSubmitTwice() = runBlocking {
    val store = MemoryRemoteCompactionStore()
    val waiting = CompletableDeferred<Unit>()
    var submissions = 0
    val tracker = RemoteCompactionTracker("host-a", owner, store, call = { method, _ ->
      assertEquals("harness.remote.compaction.submit", method)
      submissions++
      JSONObject().put("operationId", "op-5").put("state", "accepted")
    }, pause = { waiting.complete(Unit); awaitCancellation() })
    val first = launch { tracker.compact("session-5") }
    waiting.await()
    assertTrue(runCatching { tracker.compact("session-5") }.exceptionOrNull() is RemoteFailureException)
    assertEquals(1, submissions)
    first.cancel()
    first.join()
  }

  @Test fun replacementIdentityNeverRecoversPriorOwnersOperation() = runBlocking {
    val store = MemoryRemoteCompactionStore()
    store.save(RemoteCompactionRecord("host-a", "session-6", "req", "op-6", 1, owner))
    var calls = 0
    val replacement = RemoteCompactionTracker("host-a", "11111111-1111-4111-8111-111111111111", store,
      call = { _, _ -> calls++; JSONObject() }, pause = {})
    replacement.resumePending()
    assertEquals(0, calls)
    val error = runCatching { replacement.compact("session-6") }.exceptionOrNull()
    assertTrue(error?.message.orEmpty().contains("身份已更换"))
    assertEquals(0, calls)
  }

  @Test fun explicitlyRejectedSubmissionIsNotRetriedInBackground() = runBlocking {
    val store = MemoryRemoteCompactionStore()
    var calls = 0
    val tracker = RemoteCompactionTracker("host-a", owner, store, call = { _, _ ->
      calls++
      throw RemoteRpcException("AGENT_BUSY")
    }, pause = {})
    assertTrue(runCatching { tracker.compact("session-7") }.exceptionOrNull() is RemoteRpcException)
    tracker.resumePending()
    assertEquals(1, calls)
    assertNull(store.load("host-a", "session-7"))
  }

  @Test fun explicitStopWaitsForHostToConfirmCancellation() = runBlocking {
    val store = MemoryRemoteCompactionStore()
    store.save(RemoteCompactionRecord("host-a", "session-8", "req", "op-8", 1, owner))
    val calls = mutableListOf<String>()
    val tracker = RemoteCompactionTracker("host-a", owner, store, call = { method, _ ->
      calls += method
      when (method) {
        "harness.remote.compaction.cancel" -> JSONObject().put("state", "cancelling")
          .put("sessionId", "session-8").put("operationId", "op-8")
        "harness.remote.compaction.status" -> JSONObject().put("state", "cancelled")
          .put("sessionId", "session-8").put("operationId", "op-8")
        else -> error(method)
      }
    }, pause = {})
    assertTrue(tracker.cancel("session-8"))
    assertEquals(listOf("harness.remote.compaction.cancel", "harness.remote.compaction.status"), calls)
    assertNull(store.load("host-a", "session-8"))
  }

  @Test fun encryptedCachePersistsAndCorruptionFailsClosed() {
    val preferences = FakeSharedPreferences()
    val first = AndroidRemoteCompactionStore(preferences, { "sealed:$it" }, { it.removePrefix("sealed:") })
    val record = RemoteCompactionRecord("host-a", "session-1", "request-1", "op-1", 10, owner)
    first.save(record)
    val second = AndroidRemoteCompactionStore(preferences, { "sealed:$it" }, { it.removePrefix("sealed:") })
    assertEquals(record, second.load("host-a", "session-1"))
    assertTrue(preferences.getString("remote-compactions-v1", "")!!.startsWith("sealed:"))
    preferences.edit().putString("remote-compactions-v1", "broken").commit()
    assertTrue(runCatching { second.save(record) }.exceptionOrNull() is IOException)
    assertEquals("broken", preferences.getString("remote-compactions-v1", null))
  }

  @Test fun removingDeviceWhileSubmitIsInFlightDoesNotResurrectItsRecord() = runBlocking {
    val store = MemoryRemoteCompactionStore()
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val tracker = RemoteCompactionTracker("host-a", owner, store, call = { method, _ ->
      assertEquals("harness.remote.compaction.submit", method)
      started.complete(Unit)
      release.await()
      JSONObject().put("operationId", "op-1").put("state", "accepted")
    })
    val result = async { runCatching { tracker.compact("session-1") } }
    withTimeout(3_000) { started.await() }
    store.clearScope("host-a")
    release.complete(Unit)
    assertTrue(withTimeout(3_000) { result.await() }.isFailure)
    assertNull(store.load("host-a", "session-1"))
  }
}
