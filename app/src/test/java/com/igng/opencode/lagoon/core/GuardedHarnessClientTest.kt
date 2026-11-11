package com.igng.opencode.lagoon.core

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GuardedHarnessClientTest {
  private val surface get() = HarnessClient::class.java.methods.filter { !Modifier.isStatic(it.modifiers) }
  private fun signature(method: Method) = method.name + method.parameterTypes.joinToString { it.name }

  @Test fun everyInterfaceSignatureIncludingDefaultsHasAnExplicitForwarder() {
    val declared = GuardedHarnessClient::class.java.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }
    assertEquals(surface.map(::signature).toSet(), declared.map(::signature).toSet())
    // Older Kotlin modes synthesize DefaultImpls bridges: those must not hide a missing source override.
    val source = File("shared/src/main/kotlin/com/igng/opencode/lagoon/core/GuardedHarnessClient.kt").readText()
    val functions = Regex("override\\s+(?:suspend\\s+)?fun\\s+(\\w+)\\(").findAll(source).map { it.groupValues[1] }.toList()
    val properties = Regex("override\\s+val\\s+(\\w+)").findAll(source).map {
      it.groupValues[1].let { name -> if (name == "isClosed") name else "get" + name.replaceFirstChar(Char::uppercaseChar) }
    }.toList()
    assertEquals(surface.map { it.name }.groupingBy { it }.eachCount(), (functions + properties).groupingBy { it }.eachCount())
    assertFalse(source.contains("HarnessClient by"))
    assertFalse(source.contains("java.lang.reflect"))
  }

  @Test fun everyClosedOperationRejectsBeforeTouchingOwnerIncludingColdStreams() = runBlocking {
    val access = Access { name, _ -> error("Owner reached through closed lease: $name") }
    val client: HarnessClient = GuardedHarnessClient(access)
    client.close()
    for (method in surface.filterNot { it.name in setOf("close", "awaitClosed", "isClosed") }) {
      try {
        val result = invoke(client, method)
        if (result is Flow<*>) result.collect { }
        fail("Closed operation admitted: ${signature(method)}")
      } catch (expected: IllegalStateException) {
        assertEquals("closed", expected.message)
      }
    }
    assertTrue(client.isClosed)
    client.close()
    client.awaitClosed()
    assertEquals(1, access.closes)
    assertEquals(1, access.awaits)
    assertTrue(access.calls.isEmpty())
  }

  @Test fun syncSuspendDefaultArgumentsAndOptionalMethodsUseTheOwnerResult() = runBlocking {
    val capabilities = ApiCapabilities(terminal = true)
    val projects = listOf(Project("workspace", "/repo", "Workspace"))
    val access = Access { name, _ -> when (name) {
      "capabilities", "discoverCapabilities" -> capabilities
      "health" -> "host-version"
      "projectsForDisplay" -> projects
      "getCacheableTranscripts", "getRecoverableQuestions", "createGoalWithLimit", "hasPendingInput" -> true
      "transportStatus" -> "relay ready"
      "rootSessionsPage" -> ApiPage<Session>(emptyList(), "next")
      "send" -> Unit
      else -> error("Unexpected owner method $name")
    } }
    val client: HarnessClient = GuardedHarnessClient(access)
    assertSame(capabilities, client.capabilities())
    assertSame(capabilities, client.discoverCapabilities())
    assertEquals("host-version", client.health())
    assertSame(projects, client.projectsForDisplay())
    assertTrue(client.cacheableTranscripts)
    assertTrue(client.recoverableQuestions)
    assertEquals("relay ready", client.transportStatus())
    assertEquals("next", client.rootSessionsPage().next)
    assertEquals(listOf(null, null, 100), access.calls.last().second)
    assertTrue(client.createGoalWithLimit("session", "objective", Long.MAX_VALUE))
    assertEquals(listOf("session", "objective", Long.MAX_VALUE), access.calls.last().second)
    assertTrue(client.hasPendingInput("session"))
    val session = Session("session", "/repo", "Title", 1)
    client.send(session, "message")
    val sent = access.calls.last().second
    assertEquals(listOf(session, "message", null, null, emptyList<Any>(), emptyList<Any>()), sent.take(6))
    assertTrue((sent[6] as String).isNotBlank())
    assertEquals(Delivery.STEER, sent[7])
    assertEquals(4, access.syncCalls)
    assertEquals(7, access.suspendCalls)
  }

  @Test fun presentationAndContentControlsBelongToAccessNotOwner() = runBlocking {
    val access = Access { name, _ -> error("Control escaped to owner: $name") }
    val client: HarnessClient = GuardedHarnessClient(access)
    client.setContentWatching(true)
    client.questionPresentation("session", true)
    client.questionFocus("question", true)
    client.questionEngage("question")
    client.questionTakeTime("question")
    assertEquals(listOf("content:true", "presentation:session:true", "focus:question:true", "engage:question", "time:question"), access.controls)
    client.close()
    client.awaitClosed()
    assertTrue(access.calls.isEmpty())
  }

  @Test fun eventsAdmitAtCollectionAndSuppressRetainedOpenCallbackAfterClose() = runBlocking {
    var retainedOpen: (() -> Unit)? = null
    var opens = 0
    val event = ServerEvent(directory = "/repo", type = "updated", properties = JSONObject())
    val access = Access { name, args ->
      check(name == "events")
      @Suppress("UNCHECKED_CAST")
      val onOpen = args.single() as () -> Unit
      retainedOpen = onOpen
      flow { onOpen(); emit(event) }
    }
    val client: HarnessClient = GuardedHarnessClient(access)
    val events = client.events { opens++ }
    assertTrue(access.calls.isEmpty())
    assertEquals(listOf(event), events.toList())
    assertEquals(1, opens)
    assertEquals(1, access.streamCollections)
    client.close()
    retainedOpen!!()
    assertEquals(1, opens)
    try { events.toList(); fail("Closed stream collected") } catch (expected: IllegalStateException) { assertEquals("closed", expected.message) }
    assertEquals(1, access.calls.size)
  }

  @Test fun workspaceUpdatesUseGuardedCollectionAndKeepUnsupportedNull() = runBlocking {
    var collections = 0
    val projects = listOf(Project("workspace", "/repo", "Workspace"))
    val source = flow { collections++; emit(projects) }
    val access = Access { name, _ -> check(name == "getWorkspaceUpdates"); source }
    val client: HarnessClient = GuardedHarnessClient(access)
    val updates = client.workspaceUpdates!!
    assertEquals(0, collections)
    assertEquals(listOf(projects), updates.toList())
    assertEquals(1, collections)
    client.close()
    try { updates.toList(); fail("Closed workspace stream collected") } catch (expected: IllegalStateException) { assertEquals("closed", expected.message) }
    assertEquals(1, collections)
    assertNull(GuardedHarnessClient(Access { _, _ -> null }).workspaceUpdates)
  }

  @Test fun followJobOutputForwardsResultAndSuppressesRetainedUpdatesAfterClose() = runBlocking {
    var retained: ((String, Boolean, Boolean) -> Unit)? = null
    val received = mutableListOf<Triple<String, Boolean, Boolean>>()
    val access = Access { name, args ->
      check(name == "followJobOutput")
      assertEquals(listOf("session", "job"), args.take(2))
      @Suppress("UNCHECKED_CAST")
      val update = args[2] as (String, Boolean, Boolean) -> Unit
      retained = update
      update("live", true, false)
      true
    }
    val client: HarnessClient = GuardedHarnessClient(access)
    assertTrue(client.followJobOutput("session", "job") { text, running, finished -> received += Triple(text, running, finished) })
    client.close()
    retained!!("late", false, true)
    assertEquals(listOf(Triple("live", true, false)), received)
  }

  private class Access(answer: (String, List<Any?>) -> Any?) : HarnessLeaseAccess {
    override var isClosed = false
      private set
    var closes = 0; var awaits = 0; var syncCalls = 0; var suspendCalls = 0; var streamCollections = 0
    val calls = mutableListOf<Pair<String, List<Any?>>>()
    val controls = mutableListOf<String>()
    private val owner = Proxy.newProxyInstance(HarnessClient::class.java.classLoader, arrayOf(HarnessClient::class.java)) { _, method, arguments ->
      val args = arguments.orEmpty().toList().let { if (it.lastOrNull() is Continuation<*>) it.dropLast(1) else it }
      calls += method.name to args
      answer(method.name, args)
    } as HarnessClient
    override fun close() { if (!isClosed) { isClosed = true; closes++ } }
    override suspend fun awaitClosed() { check(isClosed); awaits++ }
    override fun <T> use(block: (HarnessClient) -> T): T { check(!isClosed) { "closed" }; syncCalls++; return block(owner) }
    override suspend fun <T> useSuspending(block: suspend (HarnessClient) -> T): T { check(!isClosed) { "closed" }; suspendCalls++; return block(owner) }
    override fun <T> stream(block: (HarnessClient) -> Flow<T>): Flow<T> = flow {
      check(!isClosed) { "closed" }; streamCollections++
      block(owner).collect { if (!isClosed) emit(it) }
    }
    private fun control(value: String) { check(!isClosed) { "closed" }; controls += value }
    override fun setContentWatching(enabled: Boolean) = control("content:$enabled")
    override fun questionPresentation(sessionId: String?, foreground: Boolean) = control("presentation:$sessionId:$foreground")
    override fun questionFocus(id: String, focused: Boolean) = control("focus:$id:$focused")
    override fun questionEngage(id: String) = control("engage:$id")
    override fun questionTakeTime(id: String) = control("time:$id")
  }

  private suspend fun invoke(client: HarnessClient, method: Method): Any? = suspendCoroutineUninterceptedOrReturn { continuation ->
    val arguments = method.parameterTypes.map { type -> if (Continuation::class.java.isAssignableFrom(type)) continuation else dummy(type) }.toTypedArray()
    try { method.invoke(client, *arguments) } catch (failure: InvocationTargetException) { throw failure.targetException }
  }

  private fun dummy(type: Class<*>): Any? = when (type) {
    String::class.java -> "id"
    Int::class.javaPrimitiveType, Int::class.javaObjectType -> 1
    Long::class.javaPrimitiveType, Long::class.javaObjectType -> 1L
    Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> false
    List::class.java -> emptyList<Any>()
    Session::class.java -> Session("session", "/repo", "Title", 1)
    TerminalScope::class.java -> TerminalScope.Location("/repo")
    QueueAction::class.java -> QueueAction.Remove
    ModelChoice::class.java -> ModelChoice("provider", "model", "Model")
    Delivery::class.java -> Delivery.STEER
    PermissionRequest::class.java -> PermissionRequest("permission", "session", "/repo", "read", "file")
    QuestionRequest::class.java -> QuestionRequest("question", "session", "/repo", emptyList())
    ScheduleRecord::class.java -> ScheduleRecord("schedule", "at", "Title", "Prompt", "2026-01-01T00:00:00Z")
    ScheduleTimingChange::class.java -> null
    kotlin.jvm.functions.Function0::class.java -> ({ Unit } as Any)
    kotlin.jvm.functions.Function3::class.java -> ({ _: String, _: Boolean, _: Boolean -> Unit } as Any)
    else -> error("Add reflection fixture for ${type.name}")
  }
}
