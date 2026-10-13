package com.igng.opencode.lagoon.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PermissionPolicyTest {
  private val request = PermissionRequest("permission", "session", "/repo", "shell", "echo hi", emptyList())
  private val tasks = mapOf("session" to TaskState("session", TaskPhase.WAITING_PERMISSION, "等待权限确认"))

  @Test fun bothSettingsAreIndependentAndPersistedOffByDefault() {
    val store = ServerStore(memoryPreferences(), memoryPreferences(), { it }, { it })
    assertEquals(PermissionPolicy(), store.permissionPolicy())
    for (hide in listOf(false, true)) for (auto in listOf(false, true)) {
      val policy = PermissionPolicy(hide, auto)
      store.rememberPermissionPolicy(policy)
      assertEquals(policy, store.permissionPolicy())
      assertEquals(!hide && !auto, policy.visible(request.id))
      assertEquals(!hide, policy.visible(request.id, setOf(request.id)))
    }
  }

  @Test fun hiddenPermissionIsNeutralButNotFalselyRunningOrFinished() {
    val policy = PermissionPolicy(hide = true)
    val projected = policy.tasks(tasks, listOf(request))
    assertEquals(TaskPhase.WAITING_PERMISSION, tasks.getValue("session").phase)
    assertEquals(TaskPhase.WAITING_SERVER, projected.getValue("session").phase)
    assertTrue(projected.getValue("session").active)
    assertEquals(SessionStatus.WAITING_SERVER, sessionStatus(projected.getValue("session"), emptyList()))
    val summary = TaskSummary.of(projected)
    assertEquals(0, summary.running)
    assertEquals(0, summary.waiting)
    assertEquals(1, summary.processing)
    val content = LiveUpdateContent.of(summary)
    assertEquals(LiveUpdateStage.ACTIVE, content.stage)
    assertTrue(content.expandedText.contains("等待服务器处理"))
    assertFalse(content.expandedText.contains("授权"))
    assertFalse(content.expandedText.contains("待回复"))
  }

  @Test fun questionsAndServerDenialsAreNotAutoApprovalRequests() {
    val question = TaskState("question", TaskPhase.WAITING_QUESTION, "等待你的回答")
    val failed = TaskState("denied", TaskPhase.FAILED, "Permission denied")
    val projected = PermissionPolicy(hide = true, autoApprove = true).tasks(tasks + mapOf("question" to question, "denied" to failed), listOf(request))
    assertEquals(question, projected["question"])
    assertEquals(failed, projected["denied"])
  }

  @Test fun eventsAndSweepsShareOneInFlightReplyAndCompletedTombstone() = runBlocking {
    val gate = CompletableDeferred<Unit>()
    var replies = 0
    val resolved = mutableListOf<String>()
    val approver = PermissionAutoApprover(this, { true }, { replies++; gate.await() }, { resolved += it.id })
    repeat(10) { approver.consider(request) }
    yield()
    assertEquals(1, replies)
    gate.complete(Unit)
    yield()
    repeat(10) { approver.consider(request) }
    yield()
    assertEquals(listOf(request.id), resolved)
    assertTrue(approver.pending(listOf(request)).isEmpty())
    assertEquals(1, replies)
    approver.dispose()
  }

  @Test fun aHiddenChildPermissionProjectsNeutralWaitingOntoItsRunningRoot() {
    val tasks = mapOf("root" to TaskState("root", TaskPhase.THINKING), "child" to TaskState("child", TaskPhase.WAITING_PERMISSION))
    val projected = PermissionPolicy(hide = true).tasks(tasks, listOf(request.copy(sessionId = "child")))
    val summary = TaskSummary.of(projected, parents = mapOf("child" to "root"))
    assertEquals(0, summary.running)
    assertEquals(0, summary.waiting)
    assertEquals(1, summary.processing)
  }

  @Test fun hideAloneNeverRepliesAndEnablingAutoSweepsAlreadyPending() = runBlocking {
    var enabled = false
    var replies = 0
    val approver = PermissionAutoApprover(this, { enabled }, { replies++ }, {})
    approver.consider(request)
    yield()
    assertEquals(0, replies)
    enabled = true
    approver.pending(listOf(request)).forEach(approver::consider)
    yield()
    assertEquals(1, replies)
    approver.dispose()
  }

  @Test fun disablingAutoCancelsRetryAuthorityWithoutGrantingLaterRequests() = runBlocking {
    var enabled = true
    var attempts = 0
    val approver = PermissionAutoApprover(this, { enabled }, { attempts++; enabled = false; error("offline") }, {})
    approver.consider(request)
    delay(1100)
    assertEquals(1, attempts)
    assertEquals(listOf(request), approver.pending(listOf(request)))
    approver.dispose()
  }

  @Test fun failedAutomaticRepliesRemainPendingAndRetriesAreBounded() = runBlocking {
    var attempts = 0
    var failed = emptySet<String>()
    var resolved = false
    val approver = PermissionAutoApprover(this, { true }, { attempts++; error("offline") }, { resolved = true }, { failed = it })
    approver.consider(request)
    delay(3200)
    assertEquals(3, attempts)
    assertEquals(setOf(request.id), failed)
    assertFalse(resolved)
    assertEquals(listOf(request), approver.pending(listOf(request)))
    assertTrue(PermissionPolicy(autoApprove = true).visible(request.id, failed))
    assertFalse(PermissionPolicy(hide = true, autoApprove = true).visible(request.id, failed))
    approver.dispose()
  }
}
