package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DshPublishedContractTest {
  @Test fun fixtureIsPinnedToSupportedOfficialRelease() {
    assertEquals("0.2.1-rc.5", OfficialDshContract.upstream().getString("version"))
    assertEquals("b830ec950faba0a46dda564be3e2d854afed94f1", OfficialDshContract.upstream().getString("commit"))
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
