package com.igng.opencode.lagoon.core

import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual loadSession reads with explicit gates, not timing-dependent response delays. */
class TranscriptReadRegressionTest {
  private val session = Session("s", "/repo", "Task", 1)
  private val prompt = Message("u", "user", 1, listOf(MessagePart("u:text", "text", text = "prompt")))
  private val running = Message("a", "assistant", 2, listOf(
    MessagePart("a:text:0", "text", text = "partial"), MessagePart("call", "tool", tool = "read", status = "running")))

  private fun client(read: suspend (String?) -> ApiPage<MessageRecord>): HarnessClient = object : HarnessClient by
    OpenCodeApi(ServerProfile("server", "Server", "http://localhost", allowCleartext = true), "fixture") {
    override val cacheableTranscripts = false
    override suspend fun messageRecords(id: String, cursor: String?, size: Int) = read(cursor)
    override suspend fun inbox(id: String) = emptyList<Message>()
  }
  private fun page(messages: List<Message>, next: String? = null) = ApiPage(messages.map { MessageRecord(it, null) }, next)

  private fun controller(messages: List<Message>, client: HarnessClient, provisional: Set<String> = emptySet(),
    cache: OfflineCache = OfflineCache(memoryPreferences(), { it }, { it }), dispatcher: CoroutineDispatcher = Dispatchers.Unconfined
  ): Pair<LagoonController, MutableStateFlow<LagoonState>> {
    // Match ControllerRegressionTest's constructor-free fixture: no Android UI or background jobs.
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    val controller = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, LagoonController::class.java) as LagoonController
    fun put(name: String, value: Any?) = LagoonController::class.java.getDeclaredField(name).apply { isAccessible = true }.set(controller, value)
    val state = MutableStateFlow(LagoonState(serverId = "server", connected = true, sessions = listOf(session), sessionId = session.id,
      messages = messages, provisionalMessageIds = provisional))
    put("mutable", state); put("state", state); put("generation", 1); put("api", client)
    put("store", ServerStore(memoryPreferences(), memoryPreferences(), { it }, { it })); put("cache", cache)
    put("storeDispatcher", dispatcher); put("queuedSends", mutableListOf<Any>())
    put("connectTrace", ConnectTrace()); put("openTrace", ConnectTrace()); put("transcriptMemory", TranscriptMemory())
    return controller to state
  }

  private suspend fun read(controller: LagoonController, client: HarnessClient) = suspendCoroutine<Unit> { continuation ->
    try {
      val method = LagoonController::class.java.getDeclaredMethod("loadSession", Session::class.java, Boolean::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, HarnessClient::class.java, Continuation::class.java).apply { isAccessible = true }
      val result = method.invoke(controller, session, false, 1, client, continuation)
      if (result !== COROUTINE_SUSPENDED) continuation.resume(Unit)
    } catch (error: Throwable) { continuation.resumeWithException(error.cause ?: error) }
  }
  private fun live(state: MutableStateFlow<LagoonState>) {
    state.update { snapshot ->
      var messages = snapshot.messages
      listOf(
        "session.text.delta" to """{"sessionID":"s","assistantMessageID":"a","delta":" live"}""",
        "session.tool.success" to """{"sessionID":"s","assistantMessageID":"a","id":"call","content":[{"type":"text","text":"done"}]}"""
      ).forEachIndexed { index, (type, data) ->
        messages = TranscriptProjection.apply(messages, ServerEvent("evt_$index", "/repo", type, JSONObject(data), created = 10))!!.messages
      }
      snapshot.copy(messages = messages)
    }
  }
  private fun assertLive(state: LagoonState) {
    val message = state.messages.single { it.id == "a" }
    assertEquals("partial live", message.parts[0].text)
    assertEquals("completed", message.parts[1].status)
    assertEquals("done", message.parts[1].output)
  }

  @Test fun delayedHttpKeepsLiveSameIdUpdatesAndReplacesOnlyUnchangedCacheRows() = runBlocking {
    val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
    val corrected = prompt.copy(parts = listOf(MessagePart("u:text", "text", text = "server correction")))
    val discovered = Message("new", "user", 3, emptyList())
    val client = client { started.complete(Unit); release.await(); page(listOf(corrected, running, discovered)) }
    val obsolete = Message("cached-only", "user", 0, emptyList())
    val start = listOf(obsolete, prompt, running)
    val (controller, state) = controller(start, client, start.mapTo(mutableSetOf()) { it.id })
    val loading = async { read(controller, client) }
    withTimeout(3_000) { started.await() }
    live(state)
    release.complete(Unit)
    withTimeout(3_000) { loading.await() }
    assertLive(state.value)
    assertEquals(listOf("u", "a", "new"), state.value.messages.map { it.id })
    assertEquals(corrected, state.value.messages.first())
    assertTrue(state.value.provisionalMessageIds.isEmpty())
  }

  @Test fun initialPageWideningDoesNotOverwriteEventsForIdsInTheFirstPage() = runBlocking {
    val widening = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
    val client = client { cursor ->
      if (cursor == null) page(listOf(running), "older") else {
        assertEquals("older", cursor)
        widening.complete(Unit); release.await(); page(listOf(prompt))
      }
    }
    val (controller, state) = controller(emptyList(), client)
    val loading = async { read(controller, client) }
    withTimeout(3_000) { widening.await() }
    state.update { it.copy(messages = listOf(running)) }
    live(state)
    release.complete(Unit)
    withTimeout(3_000) { loading.await() }
    assertLive(state.value)
    assertEquals(listOf("u", "a"), state.value.messages.map { it.id })
  }

  @Test fun cachePaintAfterTheReadStartsDoesNotMasqueradeAsALiveEdit() = runBlocking {
    val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
    val corrected = prompt.copy(parts = listOf(MessagePart("u:text", "text", text = "server correction")))
    val client = client { started.complete(Unit); release.await(); page(listOf(corrected)) }
    val (controller, state) = controller(emptyList(), client)
    val loading = async { read(controller, client) }
    withTimeout(3_000) { started.await() }
    state.update { it.copy(messages = listOf(prompt), provisionalMessageIds = setOf(prompt.id)) }
    release.complete(Unit)
    withTimeout(3_000) { loading.await() }
    assertEquals(listOf(corrected), state.value.messages)
  }

  @Test fun finalCommitAfterSuspendingCacheReadKeepsFurtherLiveEvents() = runBlocking {
    val cache = OfflineCache(memoryPreferences(), { it }, { it })
    cache.saveMessages("server", "s", listOf(prompt, running)); cache.awaitWrites()
    val tasks = Channel<Runnable>(Channel.UNLIMITED)
    val dispatcher = object : CoroutineDispatcher() {
      override fun dispatch(context: CoroutineContext, block: Runnable) { check(tasks.trySend(block).isSuccess) }
    }
    val client = client { throw IOException("offline") }
    val (controller, state) = controller(listOf(prompt, running), client, cache = cache, dispatcher = dispatcher)
    val loading = async { read(controller, client) }
    // First suspension reads the fallback; the second reads cache completeness before state commit.
    withTimeout(3_000) { tasks.receive() }.run()
    val completeness = withTimeout(3_000) { tasks.receive() }
    live(state)
    completeness.run()
    withTimeout(3_000) { loading.await() }
    assertLive(state.value)
    assertEquals(ResourceState.STALE, state.value.resource("messages").state)
  }

  @Test fun anEventMakesACacheRowPaintedAfterReadStartAuthoritative() = runBlocking {
    val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
    val client = client { started.complete(Unit); release.await(); page(listOf(prompt, running)) }
    val (controller, state) = controller(emptyList(), client)
    val loading = async { read(controller, client) }
    withTimeout(3_000) { started.await() }
    state.update { it.copy(messages = listOf(prompt, running), provisionalMessageIds = setOf("u", "a")) }
    val event = ServerEvent("success", "/repo", "session.tool.success",
      JSONObject("""{"sessionID":"s","assistantMessageID":"a","id":"call","content":[{"type":"text","text":"done"}]}"""), 10)
    LagoonController::class.java.getDeclaredMethod("handleEvent", ServerEvent::class.java).apply { isAccessible = true }.invoke(controller, event)
    assertFalse(state.value.provisionalMessageIds.contains("a"))
    release.complete(Unit)
    withTimeout(3_000) { loading.await() }
    assertEquals("completed", state.value.messages.single { it.id == "a" }.parts[1].status)
    assertEquals("done", state.value.messages.single { it.id == "a" }.parts[1].output)
  }
}
