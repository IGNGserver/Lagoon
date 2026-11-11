package com.igng.opencode.lagoon.core

import java.lang.reflect.Proxy
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class DshClientRegistryTest {
  private val profile = ServerProfile("saved", "DSH", "https://dsh.example", kind = ServerKind.DSH)
  private val credentials = ServerCredentials("dsh", "private-test-token")
  private class Owner(val cookie: (String) -> Unit) {
    val closes = AtomicInteger(); val calls = AtomicInteger(); val streamStarts = AtomicInteger()
    var closed = false
    var drain = CompletableDeferred(Unit)
    var health: (suspend () -> String)? = null
    var attachment: TerminalAttachment? = null
    var acquireAttachment: (suspend () -> TerminalAttachment)? = null
    val values = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 8)
    val started = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    val content = CopyOnWriteArrayList<Boolean>()
    val presentation = CopyOnWriteArrayList<Pair<String?, Boolean>>()
    val client = Proxy.newProxyInstance(HarnessClient::class.java.classLoader, arrayOf(HarnessClient::class.java)) { _, method, args ->
      when (method.name) {
        "isClosed", "getIsClosed" -> closed
        "close" -> { closes.incrementAndGet(); closed = true; Unit }
        "awaitClosed" -> {
          @Suppress("UNCHECKED_CAST") val continuation = args!!.last() as kotlin.coroutines.Continuation<Unit>
          val block: suspend () -> Unit = { drain.await() }
          block.startCoroutineUninterceptedOrReturn(continuation)
        }
        "health" -> {
          calls.incrementAndGet()
          @Suppress("UNCHECKED_CAST") val continuation = args!!.last() as kotlin.coroutines.Continuation<String>
          val block: suspend () -> String = health ?: { "healthy" }
          block.startCoroutineUninterceptedOrReturn(continuation)
        }
        "capabilities" -> ApiCapabilities()
        "events" -> flow { streamStarts.incrementAndGet(); started.send(Unit); emitAll(values) }
        "getWorkspaceUpdates" -> flow<List<Project>> { awaitCancellation() }
        "setContentWatching" -> { content += args!![0] as Boolean; Unit }
        "questionPresentation" -> { presentation += (args!![0] as String?) to (args[1] as Boolean); Unit }
        "attachTerminal" -> {
          @Suppress("UNCHECKED_CAST") val continuation = args!!.last() as kotlin.coroutines.Continuation<TerminalAttachment>
          val block: suspend () -> TerminalAttachment = acquireAttachment ?: { checkNotNull(attachment) }
          block.startCoroutineUninterceptedOrReturn(continuation)
        }
        "questionFocus", "questionEngage", "questionTakeTime" -> { calls.incrementAndGet(); Unit }
        else -> error("Unexpected fake method ${method.name}")
      }
    } as HarnessClient
  }

  @Test fun twoExplicitFactoryAcquiresShareOneOwnerAndIndependentLeaseClose() = runBlocking {
    val owners = mutableListOf<Owner>()
    val registry = DshClientRegistry { _, _, _, cookie -> Owner(cookie).also(owners::add).client }
    val one = createHarnessClient(profile, credentials, dshRegistry = registry)
    val two = createHarnessClient(profile.copy(name = "Cosmetic"), credentials, dshRegistry = registry)
    assertEquals(1, owners.size)
    assertEquals("healthy", one.health()); assertEquals("healthy", two.health())
    one.close(); one.awaitClosed()
    assertTrue(one.isClosed); assertFalse(two.isClosed); assertEquals(0, owners.single().closes.get())
    assertTrue(runCatching { one.health() }.isFailure)
    assertTrue(runCatching { one.capabilities() }.isFailure)
    assertEquals("healthy", two.health())
    two.close(); two.close(); withTimeout(2000) { two.awaitClosed() }
    assertEquals(1, owners.single().closes.get()); registry.close()
  }

  @Test fun exactRouteCredentialsAuthAndTimeoutDifferencesNeverShareOwner() = runBlocking {
    val owners = mutableListOf<Owner>()
    val registry = DshClientRegistry { _, _, _, cookie -> Owner(cookie).also(owners::add).client }
    val leases = listOf(registry.acquire(profile, credentials), registry.acquire(profile.copy(id = "other"), credentials),
      registry.acquire(profile.copy(url = "https://other.example"), credentials), registry.acquire(profile, credentials.copy(password = "rotated")),
      registry.acquire(profile, credentials.copy(cookie = "other-cookie")), registry.acquire(profile, credentials, 8),
      registry.acquire(profile.copy(dshAuth = DshAuthMode.SECRET), credentials), registry.acquire(profile.copy(allowCleartext = true), credentials))
    assertEquals(leases.size, owners.size)
    leases.forEach { it.close(); it.awaitClosed() }; registry.close()
  }

  @Test fun cookieCallbacksAreLeaseFencedAndClosedOwnerCannotPublishIntoReplacement() = runBlocking {
    val owners = mutableListOf<Owner>(); val a = mutableListOf<String>(); val b = mutableListOf<String>(); val replacement = mutableListOf<String>()
    val registry = DshClientRegistry { _, _, _, cookie -> Owner(cookie).also(owners::add).client }
    val one = registry.acquire(profile, credentials, onCookie = a::add)
    val two = registry.acquire(profile, credentials, onCookie = b::add)
    owners[0].cookie("first"); assertEquals(listOf("first"), a); assertEquals(a, b)
    one.close(); owners[0].cookie("second"); assertEquals(listOf("first"), a); assertEquals(listOf("first", "second"), b)
    two.close(); two.awaitClosed()
    val next = registry.acquire(profile, credentials, onCookie = replacement::add)
    owners[0].cookie("stale"); assertTrue(replacement.isEmpty())
    owners[1].cookie("new"); assertEquals(listOf("new"), replacement)
    next.close(); next.awaitClosed(); registry.close()
  }

  @Test fun successorBusinessWaitsForPreviousOwnerHandoffWithoutHoldingRegistryLock() = runBlocking {
    val owners = mutableListOf<Owner>()
    val registry = DshClientRegistry { _, _, _, cookie -> Owner(cookie).also(owners::add).client }
    val old = registry.acquire(profile, credentials)
    owners[0].drain = CompletableDeferred()
    old.close()
    val next = registry.acquire(profile, credentials)
    val pending = async(start = CoroutineStart.UNDISPATCHED) { next.health() }
    assertFalse(pending.isCompleted); assertEquals(0, owners[1].calls.get())
    val other = registry.acquire(profile.copy(id = "unrelated"), credentials)
    assertEquals("healthy", other.health())
    owners[0].drain.complete(Unit)
    assertEquals("healthy", withTimeout(2000) { pending.await() })
    next.close(); other.close(); next.awaitClosed(); other.awaitClosed(); old.awaitClosed(); registry.close()
  }

  @Test fun failedOwnerHandoffNeverBecomesPermissionForAReplacementConnection() = runBlocking {
    val owners = mutableListOf<Owner>()
    val registry = DshClientRegistry { _, _, _, cookie -> Owner(cookie).also(owners::add).client }
    val old = registry.acquire(profile, credentials)
    owners[0].drain = CompletableDeferred<Unit>().also { it.completeExceptionally(IllegalStateException("unconfirmed owner handoff")) }
    old.close()
    assertTrue(runCatching { withTimeout(2000) { old.awaitClosed() } }.isFailure)
    val next = registry.acquire(profile, credentials)
    assertTrue(runCatching { next.health() }.isFailure)
    assertEquals(0, owners[1].calls.get())
    next.close(); assertTrue(runCatching { next.awaitClosed() }.isFailure)
    val later = registry.acquire(profile, credentials)
    assertTrue(runCatching { later.health() }.isFailure)
    assertEquals(0, owners[2].calls.get())
    later.close(); assertTrue(runCatching { later.awaitClosed() }.isFailure); registry.close()
  }

  @Test fun leaseCloseCancelsOnlyItsFlowsAndRejectsDeferredCollections() = runBlocking {
    lateinit var owner: Owner
    val registry = DshClientRegistry { _, _, _, cookie -> Owner(cookie).also { owner = it }.client }
    val one = registry.acquire(profile, credentials); val two = registry.acquire(profile, credentials)
    val deferred = one.events()
    val a = launch(start = CoroutineStart.UNDISPATCHED) { one.events().collect {} }
    val b = launch(start = CoroutineStart.UNDISPATCHED) { two.events().collect {} }
    withTimeout(2000) { repeat(2) { owner.started.receive() } }
    one.close(); withTimeout(2000) { a.join() }
    assertTrue(a.isCompleted); assertTrue(b.isActive); assertFalse(owner.closed)
    assertTrue(runCatching { deferred.collect {} }.isFailure)
    two.close(); withTimeout(2000) { b.join(); two.awaitClosed() }; registry.close()
  }

  @Test fun lateBusinessResultAndAcquiredTerminalAreNotLeakedAfterLeaseClose() = runBlocking {
    lateinit var owner: Owner
    val registry = DshClientRegistry { _, _, _, cookie -> Owner(cookie).also { owner = it }.client }
    val lease = registry.acquire(profile, credentials)
    val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
    owner.health = { entered.complete(Unit); withContext(NonCancellable) { release.await() }; "late" }
    val pending = async { runCatching { lease.health() } }
    withTimeout(2000) { entered.await() }; lease.close(); release.complete(Unit)
    assertTrue(pending.await().isFailure); withTimeout(2000) { lease.awaitClosed() }; registry.close()
  }

  @Test fun attachmentAndRetainedStateAreGuardedAndCloseOnlyTheirOwnLease() = runBlocking {
    lateinit var owner: Owner; var detached = 0; var sent = 0
    val raw = object : TerminalAttachment {
      override val state = MutableStateFlow(TerminalStreamState(ready = true, connected = true))
      override fun sendText(text: String): Boolean { sent++; return true }
      override fun resize(cols: Int, rows: Int) = true
      override fun close() { detached++ }
    }
    val registry = DshClientRegistry { _, _, _, cookie -> Owner(cookie).also { owner = it }.client }
    val lease = registry.acquire(profile, credentials); val sibling = registry.acquire(profile, credentials)
    owner.attachment = raw
    val attached = lease.attachTerminal(TerminalScope.Session("session", "/repo"), "terminal")
    val state = attached.state
    assertTrue(attached.sendText("first")); assertTrue(attached.resize(80, 24))
    assertEquals(0, detached) // Admission must not resolve to Closeable.use and detach after sending.
    lease.close(); lease.awaitClosed()
    assertEquals(1, detached); assertEquals(1, sent)
    assertTrue(runCatching { attached.sendText("must-not-send") }.isFailure)
    assertTrue(runCatching { state.value }.isFailure)
    assertTrue(runCatching { state.first() }.isFailure)
    assertEquals("healthy", sibling.health())
    sibling.close(); sibling.awaitClosed(); registry.close()
  }

  @Test fun leaseCloseAtTheAttachmentAcquisitionBoundaryDetachesTheUnreturnedHandle() = runBlocking {
    lateinit var owner: Owner; var detached = 0
    val registry = DshClientRegistry { _, _, _, cookie -> Owner(cookie).also { owner = it }.client }
    val lease = registry.acquire(profile, credentials); val sibling = registry.acquire(profile, credentials)
    val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
    owner.acquireAttachment = {
      entered.complete(Unit); withContext(NonCancellable) { release.await() }
      object : TerminalAttachment {
        override val state = MutableStateFlow(TerminalStreamState())
        override fun sendText(text: String) = false
        override fun close() { detached++ }
      }
    }
    val opening = async { runCatching { lease.attachTerminal(TerminalScope.Session("session", "/repo"), "terminal") } }
    withTimeout(2000) { entered.await() }; lease.close(); release.complete(Unit)
    assertTrue(withTimeout(2000) { opening.await() }.isFailure)
    assertEquals(1, detached); assertFalse(sibling.isClosed)
    sibling.close(); sibling.awaitClosed(); lease.awaitClosed(); registry.close()
  }

  @Test fun backgroundCloseCannotClearNewForegroundOwnerOrUsePresentationControls() = runBlocking {
    lateinit var owner: Owner
    val registry = DshClientRegistry { _, _, _, cookie -> Owner(cookie).also { owner = it }.client }
    val one = registry.acquire(profile, credentials); val two = registry.acquire(profile, credentials)
    one.questionPresentation("first", true); two.questionPresentation("second", true)
    one.questionPresentation(null, false); one.questionFocus("question", true)
    assertEquals(0, owner.calls.get())
    one.setContentWatching(false); one.close()
    assertEquals("second" to true, owner.presentation.last())
    two.questionFocus("question", true); assertEquals(1, owner.calls.get())
    two.close(); two.awaitClosed(); registry.close()
  }
}
