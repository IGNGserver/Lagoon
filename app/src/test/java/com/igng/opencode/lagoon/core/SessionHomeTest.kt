package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionHomeTest {
  @Test fun canonicalAndLegacyProjectsRetainTheirSandboxIdentity() {
    val modern = JSONObject("""{"id":"p","canonical":"/repo","name":"Project","sandboxes":["/trees/feature"]}""").toProject()
    val legacy = JSONObject("""{"id":"q","worktree":"C:\\work\\repo","sandboxes":["C:\\trees\\feature"]}""").toProject()
    assertEquals("/repo", modern.directory)
    assertEquals("p", resolveSessionProject(Session("s", "/trees/feature", "", 1), listOf(modern))?.id)
    assertEquals("q", resolveSessionProject(Session("s", "c:/trees/FEATURE/", "", 1), listOf(legacy))?.id)
    assertNull(resolveSessionProject(Session("s", "/trees/features", "", 1), listOf(modern)))
  }

  @Test fun homeKeepsEmptyAndScriptCreatedRootsButExcludesChildrenAndAnyNumericArchive() {
    val flat = JSONObject("""{"id":"flat","directory":"/repo","title":"","time":{"updated":5}}""").toSession()
    val archived = JSONObject("""{"id":"archive","directory":"/repo","time":{"archived":0}}""").toSession()
    val script = Session("script", "/repo", "Background automation", 10)
    val child = Session("child", "/repo", "Child", 100, parentId = "flat")
    assertEquals(listOf("script", "flat"), groupSessions(listOf(flat, archived, script, child), emptyList()).single().sessions.map { it.id })
    assertFalse(JSONObject("""{"id":"s","time":{"archived":null}}""").toSession().archived)
  }

  @Test fun titlesUseOnlyServerMetadataAndNormalizeOnlyOfficialPlaceholders() {
    fun title(value: String, parent: String? = null) = Session("s", "/repo", value, 1, parent).displayTitle()
    assertEquals("新会话", title("New session - 2026-10-03T00:00:00.123Z"))
    assertEquals("子会话", title("Child session - 2026-10-03T00:00:00.123Z"))
    assertEquals("子会话", title("", "root"))
    assertEquals("New session - 2026-10-03T00:00:00Z", title("New session - 2026-10-03T00:00:00Z"))
    assertEquals("User's exact title", title("User's exact title"))
  }

  @Test fun groupingUsesProjectIdentityWithStableTimeOrderAndNoAttentionPromotion() {
    val projects = listOf(Project("a", "/repo", "Same", listOf("/trees/task")), Project("b", "/other", "Same"))
    val sessions = listOf(Session("old", "/repo", "Needs attention", 1, projectId = "a"),
      Session("b", "/trees/task", "Worktree", 20, projectId = "a"),
      Session("a", "/repo", "Created fallback", 0, projectId = "a", created = 20),
      Session("other", "/other", "Other", 10, projectId = "b"))
    val groups = groupSessions(sessions, projects)
    assertEquals(listOf("a", "b"), groups.map { it.key })
    assertEquals(listOf("a", "b", "old"), groups.first().sessions.map { it.id })
    assertEquals(2, groups.size)
  }

  @Test fun activeChildrenRollUpButTheirTerminalResultsDoNotChangeRootStatus() {
    val root = Session("root", "/repo", "Root", 1, outcome = "succeeded", viewed = 0, idle = 100)
    val child = Session("child", "/repo", "Child", 1, parentId = "root")
    val idle = LagoonState(sessions = listOf(root, child), tasks = mapOf("child" to TaskState("child", TaskPhase.FAILED)))
    // A historical outcome and a child's result are not “已完成”: only an unread root result is.
    assertEquals(SessionStatus.NONE, idle.sessionStatus(root))
    val running = idle.copy(tasks = mapOf("child" to TaskState("child", TaskPhase.THINKING)))
    assertEquals(SessionStatus.NONE, running.sessionStatus(root))
    val waiting = running.copy(tasks = mapOf("child" to TaskState("child", TaskPhase.WAITING_QUESTION)))
    assertEquals(SessionStatus.WAITING_QUESTION, waiting.sessionStatus(root))
    val unseen = idle.copy(notices = listOf(SessionNotice("result", "root", System.currentTimeMillis(), false)))
    assertEquals(SessionStatus.COMPLETED, unseen.sessionStatus(root))
    assertEquals(SessionStatus.FAILED, idle.copy(notices = listOf(SessionNotice("boom", "root", System.currentTimeMillis(), true))).sessionStatus(root))
    assertEquals(SessionStatus.NONE, idle.copy(notices = unseen.notices.map { it.copy(viewed = true) }).sessionStatus(root))
  }

  @Test fun aRunningChildIsNotTheRootStatusButItsQuestionStillIs() {
    val root = Session("root", "/repo", "Root", 1)
    val child = Session("child", "/repo", "Child", 1, parentId = "root")
    val childRunning = LagoonState(sessions = listOf(root, child), tasks = mapOf("child" to TaskState("child", TaskPhase.THINKING)),
      backgroundRunning = setOf("root"))
    // Background activity is a marker; the root has no live execution of its own.
    assertEquals(SessionStatus.NONE, childRunning.sessionStatus(root))
    val childWaiting = childRunning.copy(tasks = mapOf("child" to TaskState("child", TaskPhase.WAITING_QUESTION)))
    assertEquals(SessionStatus.WAITING_QUESTION, childWaiting.sessionStatus(root))
  }

  @Test fun modernEventsRetainExecutionTimestampAndInterruptedRunsCreateNoUnreadResult() {
    val event = """{"id":"event","type":"session.execution.failed","created":200,"data":{"sessionID":"root","error":{"message":"failed"}}}""".toServerEvent("")
    val root = Session("root", "/repo", "Root", 1)
    assertEquals(200L, TaskReducer.event("root", event.type, event.properties, TaskState("root", TaskPhase.THINKING, since = 100), event.created)?.finishedAt)
    assertTrue(event.resultNotice(root)!!.error)
    assertNull(event.resultNotice(root.copy(parentId = "parent")))
    assertNull(event.copy(type = "session.execution.interrupted").resultNotice(root))
    assertNull(event.copy(type = "session.created").resultNotice(root))
    assertNull(event.copy(type = "session.error").resultNotice(root))
  }

  @Test fun anAuthoritativeReadRecordsOnlyAKnownSucceededOrFailedOutcome() {
    val idle = Session("root", "/repo", "Root", 1, idle = 200)
    assertNull(idle.transitionNotice(100))
    assertNull(idle.copy(outcome = "interrupted").transitionNotice(100))
    assertNull(idle.copy(outcome = "unknown").transitionNotice(100))
    assertNull(idle.copy(parentId = "parent", outcome = "failed").transitionNotice(100))
    assertFalse(idle.copy(outcome = "succeeded").transitionNotice(100)!!.error)
    val failed = idle.copy(outcome = "failed").transitionNotice(100)!!
    assertTrue(failed.error)
    // A later successful turn must not inherit the previous failure.
    assertFalse(idle.copy(outcome = "succeeded").transitionNotice(300)!!.error)
    assertEquals("错误", SessionStatus.FAILED.label)
    assertEquals("状态待确认", SessionStatus.UNKNOWN.label)
  }

  @Test fun sessionCreatedAcceptsNestedInfoAndKeepsTheChildParentChain() {
    val event = """{"type":"session.created","created":123,"location":{"directory":"/repo"},"data":{"info":{"id":"child","parentID":"root","title":"Child","location":{"directory":"/repo"},"time":{"created":100,"updated":110}}}}""".toServerEvent("")
    assertEquals("child", event.properties.sessionEventId(event.type))
    val session = event.properties.toSession()
    assertEquals("child", session.id)
    assertEquals("root", session.parentId)
    assertEquals("Child", session.title)
    assertEquals(110L, session.updated)
    assertEquals(100L, session.created)
    assertEquals("direct", JSONObject("""{"id":"direct","parentID":"root"}""").sessionEventId("session.created"))
  }

  @Test fun lineageUsesKnownParentsAndFallsBackToTheSessionItself() {
    val state = LagoonState(sessions = listOf(Session("root", "/repo", "Root", 1), Session("child", "/repo", "Child", 2, parentId = "root")),
      knownParents = mapOf("child" to "root", "grandchild" to "child"))
    assertEquals(listOf("root", "child"), state.lineage("child"))
    assertEquals(listOf("root", "child", "grandchild"), state.lineage("grandchild"))
    assertEquals(listOf("missing"), LagoonState().lineage("missing"))
  }

  @Test fun persistedUnreadLedgerDeduplicatesReplayAndOnlyReadsTheObservedResult() {
    val prefs = memoryPreferences()
    fun store() = ServerStore(prefs, memoryPreferences(), { it }, { it })
    val now = System.currentTimeMillis()
    val first = SessionNotice("first", "s", now, false)
    val second = SessionNotice("second", "s", now + 1, true)
    store().rememberNotice("server", first)
    val observed = setOf(first.id)
    store().rememberNotice("server", second)
    store().viewNotices("server", "s", observed)
    store().rememberNotice("server", first)
    val restored = store().sessionNotices("server")
    assertEquals(2, restored.size)
    assertTrue(restored.first().viewed)
    assertFalse(restored.last().viewed)
    assertTrue(store().sessionNotices("other").isEmpty())
    store().rememberCollapsedSections("server", setOf("project:p"))
    assertEquals(setOf("project:p"), store().collapsedSections("server"))
    assertTrue(store().collapsedSections("other").isEmpty())
  }

  @Test fun ledgerPrunesToTheOfficialAgeAndCountBounds() {
    val now = System.currentTimeMillis()
    val stale = SessionNotice("stale", "s", now - 31L * 24 * 60 * 60 * 1000, false)
    val notices = listOf(stale) + (1..510).map { SessionNotice("$it", "s", now + it, false) }
    assertEquals(500, notices.pruned(now).size)
    assertEquals("11", notices.pruned(now).first().id)
    assertFalse(notices.pruned(now).any { it.id == "stale" })
  }

  @Test fun offlineCatalogPreservesProjectWorktreesAndExecutionMetadata() = runBlocking {
    val cache = OfflineCache(memoryPreferences(), { it }, { it })
    val project = Project("p", "/repo", "Project", listOf("/trees/task"))
    val session = Session("s", "/trees/task", "Server title", 50, projectId = "p", viewed = 10, idle = 40, outcome = "failed")
    cache.saveCatalog("server", listOf(project), listOf(session)); cache.awaitWrites()
    val restored = cache.catalog("server")!!
    assertEquals(project, restored.first.single())
    assertEquals(session, restored.second.single())
  }
}
