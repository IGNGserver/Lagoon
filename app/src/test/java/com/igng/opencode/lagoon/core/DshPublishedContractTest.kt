package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DshPublishedContractTest {
  @Test fun fixtureIsPinnedToSupportedOfficialRelease() {
    assertEquals("0.2.1-rc.5", OfficialDshContract.upstream().getString("version"))
    assertEquals("b830ec950faba0a46dda564be3e2d854afed94f1", OfficialDshContract.upstream().getString("commit"))
  }

  @Test fun sessionContentSearchIsLiteralHostQueryWithoutClientPaginationOrScopeFields() {
    OfficialDshContract.assertArgs("session/search", JSONObject("""{"request":{"query":"literal [a.*] + text"}}"""))
    OfficialDshContract.assertResult("session/search", JSONObject("""{"items":[{"sessionId":"s","snippet":"native content"}],"hasMore":false}"""))
    OfficialDshContract.assertResult("session/search", JSONObject("""{"items":[],"hasMore":true}"""))
    for (field in listOf("directory", "workspaceId", "limit", "cursor", "regex")) {
      val invalid = JSONObject().put("request", JSONObject().put("query", "text").put(field, "not accepted"))
      assertTrue(runCatching { OfficialDshContract.assertArgs("session/search", invalid) }.isFailure)
    }
    assertTrue(runCatching { OfficialDshContract.assertResult("session/search", JSONObject("""{"items":[]}""")) }.isFailure)
  }

  @Test fun nativeWorkspaceContractUsesOpaqueIdsAndRetainsFilesWithoutInventedCasFields() {
    OfficialDshContract.assertArgs("workspace/create", JSONObject("""{"request":{"path":"/existing"}}"""))
    OfficialDshContract.assertArgs("workspace/rename", JSONObject("""{"request":{"workspaceId":"w","title":"Renamed"}}"""))
    OfficialDshContract.assertArgs("workspace/delete", JSONObject("""{"request":{"workspaceId":"w"}}"""))
    OfficialDshContract.assertArgs("workspace/insertBefore", JSONObject("""{"request":{"workspaceId":"w"}}"""))
    OfficialDshContract.assertArgs("workspace/insertBefore", JSONObject("""{"request":{"workspaceId":"w","beforeWorkspaceId":"anchor"}}"""))
    OfficialDshContract.assertResult("workspace/delete", JSONObject("""{"deleted":true}"""))
    OfficialDshContract.assertResult("workspace/insertBefore", JSONObject("""{"workspaceIds":["w","anchor"]}"""))
    for (extra in listOf("deleteFiles", "deleteSessions", "expected", "revision")) {
      val invalid = JSONObject().put("request", JSONObject().put("workspaceId", "w").put(extra, true))
      assertTrue(runCatching { OfficialDshContract.assertArgs("workspace/delete", invalid) }.isFailure)
    }
    assertTrue(runCatching { OfficialDshContract.assertArgs("workspace/create", JSONObject("""{"request":{"path":"/existing","title":"Not a create parameter"}}""")) }.isFailure)
  }

  @Test fun scheduleUpdateHasCompleteClosedExpectedRecordAndNoCatalogMetadata() {
    val expected = JSONObject("""{"id":"task","kind":"daily","title":"Task","prompt":"Review","scheduledAt":"2099-01-01T15:00:00.000Z","time":"23:00:00.000","timeZone":"Asia/Shanghai"}""")
    val request = JSONObject().put("sessionId", "owner").put("id", "task").put("expected", expected).put("title", "Edited")
    OfficialDshContract.assertArgs("schedule/update", JSONObject().put("request", request))
    for (extra in listOf("sessionId", "status", "lastDelivery", "enabled")) {
      val invalid = JSONObject(request.toString()).apply { obj("expected").put(extra, "not a record field") }
      assertTrue(extra, runCatching { OfficialDshContract.assertArgs("schedule/update", JSONObject().put("request", invalid)) }.isFailure)
    }
    assertTrue(OfficialDshContract.schema("schedule/update").has("resultSchemaError"))
    assertFalse(OfficialDshContract.schema("schedule/update").has("result"))
  }

  @Test fun scheduleDeleteAndHistoryUseOriginalOwnerAndExclusiveMessageCursor() {
    OfficialDshContract.assertArgs("schedule/delete", JSONObject("""{"request":{"sessionId":"owner","id":"task"}}"""))
    OfficialDshContract.assertArgs("schedule/history", JSONObject("""{"request":{"sessionId":"owner","id":"task","limit":20,"before":"message-cursor"}}"""))
    assertTrue(runCatching { OfficialDshContract.assertArgs("schedule/delete", JSONObject("""{"request":{"id":"task"}}""")) }.isFailure)
    assertTrue(runCatching { OfficialDshContract.assertArgs("schedule/update", JSONObject("""{"request":{"sessionId":"owner","id":"task","enabled":false}}""")) }.isFailure)
    assertFalse(OfficialDshContract.has("schedule/create")); assertFalse(OfficialDshContract.has("schedule/run"))
  }

  @Test fun goalEditUsesAnExactRefAndOptionalReplacementFieldsNotExpectedOrToolArguments() {
    val args = JSONObject("""{"agentId":"s","ref":{"id":"g","revision":2},"request":{"objective":"Edited","maxGoalRounds":9007199254740991}}""")
    OfficialDshContract.assertArgs("goals/edit", args)
    val wrongReference = JSONObject(args.toString()).apply { put("expected", remove("ref")) }
    assertTrue(runCatching { OfficialDshContract.assertArgs("goals/edit", wrongReference) }.isFailure)
    val wrongToolArgument = JSONObject(args.toString()).apply { obj("request").put("max_goal_rounds", 20) }
    assertTrue(runCatching { OfficialDshContract.assertArgs("goals/edit", wrongToolArgument) }.isFailure)
    val nullObjective = JSONObject(args.toString()).apply { obj("request").put("objective", JSONObject.NULL) }
    assertTrue(runCatching { OfficialDshContract.assertArgs("goals/edit", nullObjective) }.isFailure)
  }

  @Test fun goalCreateAckAndEditFullViewAreDifferentOfficialResults() {
    val reference = JSONObject("""{"ref":{"id":"g","revision":1}}""")
    val view = JSONObject("""{"id":"g","revision":2,"objective":"Edited","phase":"paused","maxGoalRounds":20,"roundsStarted":3,"createdAt":1,"updatedAt":2,"activation":"disarmed"}""")
    OfficialDshContract.assertResult("goals/create", reference)
    OfficialDshContract.assertResult("goals/edit", view)
    assertTrue(runCatching { OfficialDshContract.assertResult("goals/edit", reference) }.isFailure)
    assertTrue(runCatching { OfficialDshContract.assertResult("goals/create", view) }.isFailure)
  }

  @Test fun attachmentContractChangedToVerifiedReferenceIn022() {
    val old = JSONObject("""{"request":{"sessionId":"s","attachmentId":"image-id"}}""")
    val current = JSONObject("""{"request":{"sessionId":"s","attachment":{"attachmentId":"image-id","mediaType":"image/png","bytes":8,"width":1,"height":1}}}""")
    OfficialDshContract.assertArgs("session/attachment", old, "0.2.1-rc.5")
    OfficialDshContract.assertArgs("session/attachment", current, "0.2.2-rc.1")
    assertTrue(runCatching { OfficialDshContract.assertArgs("session/attachment", old, "0.2.2-rc.1") }.isFailure)
    assertTrue(runCatching { OfficialDshContract.assertArgs("session/attachment", current, "0.2.1-rc.5") }.isFailure)
  }

  @Test fun creationCannotSmuggleATitleIntoTheStrictRequest() {
    OfficialDshContract.assertArgs("session/create", JSONObject("""{"request":{"workspaceId":"w","sessionId":"s","agentPreset":"ptc"}}"""))
    assertTrue(runCatching {
      OfficialDshContract.assertArgs("session/create", JSONObject("""{"request":{"cwd":"/repo","title":"not a create parameter"}}"""))
    }.isFailure)
  }

  @Test fun bytesRangeIsNestedInOptionsAndRequiredAtTheNamedArgumentLevel() {
    OfficialDshContract.assertArgs("workspaceFiles/readBytes", JSONObject("""{"workspaceFileScopeId":"s","path":"a.pdf","options":{"range":{"offset":0,"length":65536}}}"""))
    assertTrue(runCatching {
      OfficialDshContract.assertArgs("workspaceFiles/readBytes", JSONObject("""{"workspaceFileScopeId":"s","path":"a.pdf","range":{"offset":0,"length":65536}}"""))
    }.isFailure)
  }

  @Test fun queueEditIsAContentActionNotAnOpenCodeBody() {
    OfficialDshContract.assertArgs("session/updateQueue", JSONObject("""{"request":{"sessionId":"s","itemId":"i","action":{"kind":"edit","content":[{"type":"text","text":"new"}]}}}"""))
    assertTrue(runCatching {
      OfficialDshContract.assertArgs("session/updateQueue", JSONObject("""{"request":{"sessionId":"s","itemId":"i","text":"wrong"}}}"""))
    }.isFailure)
  }

  @Test fun questionClaimAndAnswerUseAgentAndCallIdentity() {
    OfficialDshContract.assertArgs("userQuestions/attachWait", JSONObject("""{"agentId":"s","callId":"q"}"""))
    OfficialDshContract.assertArgs("userQuestions/answer", JSONObject("""{"agentId":"s","callId":"q","answer":{"answers":[{"id":"field","selected":["yes"]}]}}"""))
    assertTrue(runCatching {
      OfficialDshContract.assertArgs("userQuestions/answer", JSONObject("""{"sessionId":"s","eventId":"q","answers":[]}"""))
    }.isFailure)
  }

  @Test fun jobKillCarriesBothOwnerAndJobRatherThanOnlyAJobId() {
    OfficialDshContract.assertArgs("job/kill", JSONObject("""{"request":{"sessionId":"s","jobId":"j"}}"""))
    assertTrue(runCatching {
      OfficialDshContract.assertArgs("job/kill", JSONObject("""{"jobId":"j"}"""))
    }.isFailure)
  }

  @Test fun archiveAndUnarchiveAreDistinctOfficialMethods() {
    for (endpoint in listOf("workspace/archiveSession", "workspace/unarchiveSession")) {
      OfficialDshContract.assertArgs(endpoint, JSONObject("""{"request":{"sessionId":"s"}}"""))
    }
  }

  @Test fun commandsAcceptSubmittedAttachmentsNotPromptContent() {
    OfficialDshContract.assertArgs("commands/execute", JSONObject("""{"agentId":"s","line":"/compact","submittedAttachments":[]}"""))
    assertTrue(runCatching {
      OfficialDshContract.assertArgs("commands/execute", JSONObject("""{"agentId":"s","line":"/compact","content":[]}"""))
    }.isFailure)
  }
}
