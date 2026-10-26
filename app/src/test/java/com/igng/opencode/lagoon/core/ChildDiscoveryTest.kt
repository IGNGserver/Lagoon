package com.igng.opencode.lagoon.core

import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ChildDiscoveryTest {
  private fun session(id: String, parent: String? = null) = Session(id, "/repo", id, 1, parentId = parent)

  private fun client(children: suspend (Session) -> List<Session>): HarnessClient =
    Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HarnessClient::class.java)) { _, method, args ->
      check(method.name == "children") { "Unexpected call: ${method.name}" }
      @Suppress("UNCHECKED_CAST")
      val continuation = args!!.last() as Continuation<List<Session>>
      val call: suspend () -> List<Session> = { children(args[0] as Session) }
      call.startCoroutine(continuation)
      COROUTINE_SUSPENDED
    } as HarnessClient

  @Test fun twentyFiveLeavesCompleteInOneRead() = runBlocking {
    val root = session("root")
    val leaves = (1..25).map { session("child$it", root.id) }
    val calls = mutableListOf<String>()
    val api = client { calls += it.id; if (it.id == root.id) leaves else emptyList() }
    val result = ChildDiscovery().read(api, listOf(root), emptyMap())
    assertEquals(leaves, result.sessions)
    assertTrue(result.incompleteRoots.isEmpty())
    assertEquals(26, calls.size)
    assertEquals(calls.size, calls.distinct().size)
  }

  @Test fun wideTreesResumeWithoutDiscardingIdentitiesOrRequeryingVisitedNodes() = runBlocking {
    val root = session("root")
    val leaves = (1..150).map { session("child$it", root.id) }
    val calls = mutableListOf<String>()
    val api = client { calls += it.id; if (it.id == root.id) leaves else emptyList() }
    val scanner = ChildDiscovery()
    val first = scanner.read(api, listOf(root), emptyMap())
    assertEquals(setOf(root.id), first.incompleteRoots)
    assertEquals(100, calls.size)
    assertEquals(leaves, first.sessions)
    val second = scanner.read(api, listOf(root), emptyMap())
    assertTrue(second.incompleteRoots.isEmpty())
    assertEquals(leaves, second.sessions)
    assertEquals(151, calls.size)
    assertEquals(calls.size, calls.distinct().size)
  }

  @Test fun deepTreesHaveNoDepthCapAndResumeAfterTheRequestBudget() = runBlocking {
    val chain = (0..125).map { session("node$it", if (it > 0) "node${it - 1}" else null) }
    var calls = 0
    val api = client { current ->
      calls++
      chain.getOrNull(current.id.removePrefix("node").toInt() + 1)?.let(::listOf).orEmpty()
    }
    val scanner = ChildDiscovery()
    val first = scanner.read(api, listOf(chain.first()), emptyMap())
    assertEquals(100, calls)
    assertEquals(setOf("node0"), first.incompleteRoots)
    val second = scanner.read(api, listOf(chain.first()), emptyMap())
    assertEquals(chain.drop(1), second.sessions)
    assertTrue(second.incompleteRoots.isEmpty())
    assertEquals(126, calls)
  }

  @Test fun failedEndpointsRetryOnlyOnTheNextPollAndPreserveSuccessfulSiblings() = runBlocking {
    val root = session("root")
    val good = session("good", root.id)
    val flaky = session("flaky", root.id)
    val grandchild = session("grandchild", flaky.id)
    var recovered = false
    val calls = mutableListOf<String>()
    val api = client {
      calls += it.id
      when (it.id) {
        root.id -> listOf(good, flaky)
        flaky.id -> if (recovered) listOf(grandchild) else error("offline")
        else -> emptyList()
      }
    }
    val scanner = ChildDiscovery()
    val first = scanner.read(api, listOf(root), emptyMap())
    assertEquals(listOf(good, flaky), first.sessions)
    assertEquals(setOf(root.id), first.incompleteRoots)
    assertEquals(1, calls.count { it == flaky.id })
    recovered = true
    val second = scanner.read(api, listOf(root), emptyMap())
    assertEquals(listOf(good, flaky, grandchild), second.sessions)
    assertTrue(second.incompleteRoots.isEmpty())
    assertEquals(1, calls.count { it == good.id })
    assertEquals(1, calls.count { it == root.id })
    assertEquals(2, calls.count { it == flaky.id })
  }

  @Test fun cyclesAndDuplicateChildrenDoNotRepeatEndpointsOrReturnTheRoot() = runBlocking {
    val root = session("root")
    val child = session("child", root.id)
    val calls = mutableListOf<String>()
    val api = client {
      calls += it.id
      if (it.id == root.id) listOf(root, child, child, session("")) else listOf(root, child)
    }
    val result = ChildDiscovery().read(api, listOf(root), mapOf(child.id to root.id))
    assertEquals(listOf(child), result.sessions)
    assertTrue(result.incompleteRoots.isEmpty())
    assertEquals(listOf(root.id, child.id), calls)
  }

  @Test fun moreThanOneHundredRootsRotateWithoutStarvingSkippedRoots() = runBlocking {
    val roots = (1..150).map { session("root$it") }
    val calls = mutableListOf<String>()
    val api = client { calls += it.id; emptyList() }
    val scanner = ChildDiscovery()
    val first = scanner.read(api, roots, emptyMap())
    assertEquals(roots.drop(100).map { it.id }.toSet(), first.incompleteRoots)
    assertEquals(100, calls.size)
    scanner.read(api, roots, emptyMap())
    assertEquals(roots.map { it.id }.toSet(), calls.toSet())
    assertEquals(200, calls.size)
  }

  @Test fun aChangedActivityTimestampRestartsAnUnfinishedScan() = runBlocking {
    val root = session("root")
    val old = session("old", root.id)
    val fresh = session("fresh", root.id)
    var changed = false
    var rootCalls = 0
    val api = client {
      when (it.id) {
        root.id -> { rootCalls++; if (changed) listOf(fresh) else listOf(old) }
        old.id -> error("offline")
        else -> emptyList()
      }
    }
    val scanner = ChildDiscovery()
    assertEquals(listOf(old), scanner.read(api, listOf(root), emptyMap()).sessions)
    changed = true
    val result = scanner.read(api, listOf(root.copy(updated = 2)), emptyMap())
    assertEquals(listOf(fresh), result.sessions)
    assertTrue(result.incompleteRoots.isEmpty())
    assertEquals(2, rootCalls)
  }

  @Test fun completedScansAreFreshOnTheNextPollSoDeletedChildrenDisappear() = runBlocking {
    val root = session("root")
    val child = session("child", root.id)
    var deleted = false
    val api = client { if (!deleted && it.id == root.id) listOf(child) else emptyList() }
    val scanner = ChildDiscovery()
    assertEquals(listOf(child), scanner.read(api, listOf(root), emptyMap()).sessions)
    deleted = true
    val result = scanner.read(api, listOf(root), emptyMap())
    assertTrue(result.sessions.isEmpty())
    assertTrue(result.incompleteRoots.isEmpty())
  }

  @Test fun clientsNeverShareUnfinishedIdentitiesOrFrontiers() = runBlocking {
    val root = session("root")
    val child = session("child", root.id)
    val first = client { if (it.id == root.id) listOf(child) else error("offline") }
    val second = client { emptyList() }
    val scanner = ChildDiscovery()
    assertEquals(listOf(child), scanner.read(first, listOf(root), emptyMap()).sessions)
    val result = scanner.read(second, listOf(root), emptyMap())
    assertTrue(result.sessions.isEmpty())
    assertTrue(result.incompleteRoots.isEmpty())
    assertEquals(listOf(child), scanner.read(first, listOf(root), emptyMap()).sessions)
  }

  @Test fun cancellationPropagatesAndLeavesTheEndpointAvailableForRetry() = runBlocking {
    val root = session("root")
    var cancelled = true
    val api = client { if (cancelled) throw CancellationException("cancelled") else emptyList() }
    val scanner = ChildDiscovery()
    try {
      scanner.read(api, listOf(root), emptyMap())
      fail("Expected cancellation")
    } catch (_: CancellationException) {
      // Cancellation is not a failed authoritative read.
    }
    cancelled = false
    assertTrue(scanner.read(api, listOf(root), emptyMap()).incompleteRoots.isEmpty())
  }

  @Test fun concurrentCallersAreSerializedAndRequestsStayWithinFourInFlight() = runBlocking {
    val root = session("root")
    val children = (1..12).map { session("child$it", root.id) }
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var active = 0
    var maximum = 0
    var rootCalls = 0
    val api = client {
      active++
      maximum = maxOf(maximum, active)
      try {
        if (it.id == root.id) {
          rootCalls++
          started.complete(Unit)
          release.await()
          children
        } else {
          yield()
          emptyList()
        }
      } finally {
        active--
      }
    }
    val scanner = ChildDiscovery()
    val first = async { scanner.read(api, listOf(root), emptyMap()) }
    started.await()
    val second = async { scanner.read(api, listOf(root), emptyMap()) }
    yield()
    assertEquals(1, rootCalls)
    release.complete(Unit)
    assertTrue(first.await().incompleteRoots.isEmpty())
    assertTrue(second.await().incompleteRoots.isEmpty())
    assertEquals(2, rootCalls)
    assertEquals(4, maximum)
  }
}
