package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApiCapabilitiesTest {
  @Test fun currentRoutesAreReadFromTheDocument() {
    val document = JSONObject("""{"paths":{
      "/api/session/{sessionID}":{"patch":{"requestBody":{"content":{"application/json":{"schema":{"${'$'}ref":"#/components/schemas/Update"}}}}}},
      "/api/session/{sessionID}/revert":{"delete":{}},
      "/api/session/{sessionID}/diff":{"get":{}},
      "/api/session/{sessionID}/view":{"post":{"requestBody":{"content":{"application/json":{"schema":{"type":"object","properties":{"idle":{"type":"number"}},"required":["idle"]}}}}}},
      "/api/permission/saved":{"get":{}}
    },"components":{"schemas":{"Update":{"type":"object","properties":{"title":{"type":"string"},"metadata":{"type":"object"}}}}}}""")
    val caps = ApiCapabilities.fromDocument(document)
    assertEquals(ActionEndpoint("PATCH", "api/session/{sessionID}"), caps.actions[SessionAction.RENAME])
    assertEquals(ActionEndpoint("DELETE", "api/session/{sessionID}/revert"), caps.actions[SessionAction.UNREVERT])
    assertTrue(caps.diff); assertNotNull(caps.sessionView); assertTrue(caps.documented)
  }
  @Test fun unknownRequiredWriteFieldsKeepTheActionUnavailable() {
    val doc = JSONObject("""{"paths":{"/api/session/{id}/rename":{"post":{"requestBody":{"content":{"application/json":{"schema":{"type":"object","properties":{"title":{"type":"string"}},"required":["title","unknown"]}}}}}}}}""")
    assertFalse(ApiCapabilities.fromDocument(doc).supports(SessionAction.RENAME))
  }
  /**
   * Archiving is a top-level `archived` boolean on the session update body from OpenCode 2.1.0 on
   * (`packages/protocol` `session.update`): `true` archives, `false` restores. Earlier releases have
   * no archive field at all, and nothing anywhere uses a `time.archived` *write* shape.
   */
  @Test fun archiveIsOfferedOnlyWhenTheUpdateBodyDocumentsATopLevelArchivedBoolean() {
    fun caps(properties: String) = ApiCapabilities.fromDocument(JSONObject("""{"paths":{"/api/session/{sessionID}":{"patch":{"requestBody":{"content":{"application/json":{"schema":
      {"type":"object","properties":$properties}}}}}}}}}"""))
    assertNotNull(ApiCapabilities.BASELINE.archive)
    // 2.0.x: no archive field.
    assertNull(caps("""{"title":{"type":"string"},"metadata":{"type":"object"},"permissions":{"type":"array"}}""").archive)
    // The real 2.1+/2.2 shape, exactly as published.
    assertEquals(ArchiveEndpoint(ActionEndpoint("PATCH", "api/session/{sessionID}"), restorable = true),
      caps("""{"title":{"anyOf":[{"type":"string"},{"type":"null"}]},"archived":{"anyOf":[{"type":"boolean"},{"type":"null"}]}}""").archive)
    // A plain boolean (no nullable wrapper) restores just as well.
    assertTrue(caps("""{"archived":{"type":"boolean"}}""").archive!!.restorable)
    // A one-way `archived` must not advertise restore.
    assertFalse(caps("""{"archived":{"enum":[true]}}""").archive!!.restorable)
  }
  @Test fun aRequiredSiblingTheClientNeverWritesKeepsArchivingUnavailable() {
    val doc = JSONObject("""{"paths":{"/api/session/{id}":{"patch":{"requestBody":{"content":{"application/json":{"schema":
      {"type":"object","properties":{"archived":{"type":"boolean"},"reason":{"type":"string"}},"required":["archived","reason"]}}}}}}}}""")
    assertNull(ApiCapabilities.fromDocument(doc).archive)
  }
  @Test fun aNullFirstUnionStillResolvesToItsSchema() {
    val doc = JSONObject("""{"paths":{"/api/session/{id}":{"patch":{"requestBody":{"content":{"application/json":{"schema":
      {"anyOf":[{"type":"null"},{"type":"object","properties":{"title":{"type":"string"}}}]}}}}}}}}""")
    assertTrue(ApiCapabilities.fromDocument(doc).supports(SessionAction.RENAME))
  }
  @Test fun shellTerminationDependsOnTheDocumentedDeleteRouteNotTransportKind() {
    val present = ApiCapabilities.fromDocument(JSONObject("""{"paths":{"/api/shell/{shellID}":{"delete":{}}}}"""))
    val absent = ApiCapabilities.fromDocument(JSONObject("""{"paths":{"/api/shell/{shellID}":{"get":{}}}}"""))
    assertTrue(present.stopShell)
    assertFalse(absent.stopShell)
    assertTrue(ApiCapabilities.BASELINE.stopShell)
  }

  @Test fun forkAcceptsThePublishedOptionalBeforeBodyForAnySessionParameterName() {
    // OpenCode v2.2.6 packages/protocol/openapi.json: body required, `before` optional.
    val operation = JSONObject("""{"requestBody":{"required":true,"content":{"application/json":{"schema":{
      "type":"object","properties":{"before":{"anyOf":[{"${'$'}ref":"#/components/schemas/Session.Message.ID"},{"type":"null"}]}},
      "additionalProperties":false}}}}}""")
    for (parameter in listOf("id", "sessionID", "sessionId")) {
      val document = sessionOperation("/fork", operation, parameter = parameter)
        .put("components", JSONObject("""{"schemas":{"Session.Message.ID":{"type":"string","pattern":"^msg_"}}}"""))
      val caps = ApiCapabilities.fromDocument(document)
      assertTrue(parameter, caps.fork)
      assertTrue(parameter, caps.historicalFork)
      assertTrue(caps.documented)
    }
  }
  @Test fun forkNeedsAnExactDocumentedPostRoute() {
    for (document in listOf(
      JSONObject("""{"paths":{}}"""),
      sessionOperation("/fork", JSONObject(), method = "get"),
      sessionOperation("/forked", JSONObject()),
      sessionOperation("/fork/extra", JSONObject()),
      JSONObject("""{"paths":{"/session/{id}/fork":{"post":{}}}}""")
    )) {
      assertFalse(document.toString(), ApiCapabilities.fromDocument(document).fork)
      assertFalse(document.toString(), ApiCapabilities.fromDocument(document).historicalFork)
    }
    assertTrue(ApiCapabilities.fromDocument(sessionOperation("/fork", JSONObject())).fork)
    assertFalse(ApiCapabilities.fromDocument(sessionOperation("/fork", JSONObject())).historicalFork)
  }
  @Test fun forkRejectsEveryRequiredPayloadFieldTheClientDoesNotWrite() {
    for (field in listOf("before", "messageID", "reason")) {
      val operation = jsonBody("""{"type":"object","properties":{"$field":{"type":"string"}},"required":["$field"]}""")
      val caps = ApiCapabilities.fromDocument(sessionOperation("/fork", operation))
      assertFalse(field, caps.fork)
      assertFalse(field, caps.historicalFork)
    }
  }
  @Test fun forkResolvesLocalSchemaReferencesAndNullFirstUnions() {
    for (union in listOf("anyOf", "oneOf")) {
      val document = sessionOperation("/fork", jsonBody("""{"${'$'}ref":"#/components/schemas/Fork"}"""))
        .put("components", JSONObject("""{"schemas":{"Fork":{"$union":[{"type":"null"},
          {"type":"object","properties":{"before":{"type":"string"}}}]}}}"""))
      assertTrue(union, ApiCapabilities.fromDocument(document).fork)
      assertTrue(union, ApiCapabilities.fromDocument(document).historicalFork)
      document.obj("components").obj("schemas").put("Fork", JSONObject("""{
        "type":"object","properties":{"before":{"type":"string"}},"required":["before"]}"""))
      assertFalse(ApiCapabilities.fromDocument(document).fork)
    }
  }
  @Test fun revertResolvesFlatAndReferencedMessageIdBodies() {
    val input = """{"type":"object","properties":{"messageID":{"type":"string"},"files":{"type":"boolean"}},
      "required":["messageID"],"additionalProperties":false}"""
    for (parameter in listOf("id", "sessionID")) {
      val flat = sessionOperation("/revert/stage", jsonBody(input), parameter = parameter)
      val referenced = sessionOperation("/revert/stage", jsonBody("""{"${'$'}ref":"#/components/schemas/Revert"}"""), parameter = parameter)
        .put("components", JSONObject().put("schemas", JSONObject().put("Revert", JSONObject(input))))
      for (document in listOf(flat, referenced)) {
        val caps = ApiCapabilities.fromDocument(document)
        assertTrue(caps.supports(SessionAction.REVERT))
        assertEquals(ActionEndpoint("POST", "api/session/{$parameter}/revert/stage"), caps.actions[SessionAction.REVERT])
      }
    }
  }
  @Test fun revertNeedsAPostRouteWithTheMessageIdWriteShape() {
    for (document in listOf(
      JSONObject("""{"paths":{}}"""),
      sessionOperation("/revert/stage", jsonBody("""{"properties":{"messageID":{}}}"""), method = "get"),
      sessionOperation("/revert", jsonBody("""{"properties":{"messageID":{}}}""")),
      sessionOperation("/revert/stage", jsonBody("""{"properties":{"before":{"type":"string"}}}""")),
      sessionOperation("/revert/stage", jsonBody("""{"type":"object","properties":{"messageID":{},"files":{}},"required":["messageID","files"]}"""))
    )) assertFalse(document.toString(), ApiCapabilities.fromDocument(document).supports(SessionAction.REVERT))
  }
  @Test fun theBaselineIncludesTheCurrentNativeForkAndRevertContract() {
    assertTrue(ApiCapabilities.BASELINE.fork)
    assertFalse(ApiCapabilities.BASELINE.historicalFork)
    assertFalse(ApiCapabilities().historicalFork)
    assertEquals(ActionEndpoint("POST", "api/session/{id}/revert/stage"), ApiCapabilities.BASELINE.actions[SessionAction.REVERT])
  }

  @Test fun historicalForkRejectsUnknownOrNonStringBoundariesAndNonemptyPayloadRequirements() {
    for (schema in listOf(
      """{"type":"object","properties":{"messageID":{"type":"string"}}}""",
      """{"type":"object","properties":{"before":{"type":"integer"}}}""",
      """{"type":"object","properties":{"before":{}}}""",
      """{"type":"object","properties":{"before":{"type":"string"}},"required":["reason"]}""",
      """{"type":"object","properties":{"before":{"type":"string"}},"minProperties":1}""",
      """{"type":"object","properties":{"before":{"type":"string"}},"maxProperties":0}""",
      """{"type":"array","properties":{"before":{"type":"string"}}}"""
    )) assertFalse(schema, ApiCapabilities.fromDocument(sessionOperation("/fork", jsonBody(schema))).historicalFork)
  }

  private fun jsonBody(schema: String) = JSONObject().put("requestBody", JSONObject().put("required", true)
    .put("content", JSONObject().put("application/json", JSONObject().put("schema", JSONObject(schema)))))

  private fun sessionOperation(suffix: String, operation: JSONObject, method: String = "post", parameter: String = "id") =
    JSONObject().put("paths", JSONObject().put("/api/session/{$parameter}$suffix", JSONObject().put(method, operation)))

  @Test fun nativeProjectAndWorktreeManagementRequireTheCompleteInstanceContract() {
    val document = OpenCodeProjectManagementTest.document()
    val caps = ApiCapabilities.fromDocument(document)
    assertTrue(caps.openCodeProjectManagement); assertTrue(caps.openCodeWorktreeManagement)
    assertFalse(ApiCapabilities.BASELINE.openCodeProjectManagement); assertFalse(ApiCapabilities.BASELINE.openCodeWorktreeManagement)
    assertFalse(ApiCapabilities().openCodeProjectManagement); assertFalse(ApiCapabilities().openCodeWorktreeManagement)
    val paths = document.getJSONObject("paths")
    paths.remove("/api/worktree/refresh")
    assertTrue(ApiCapabilities.fromDocument(document).openCodeProjectManagement)
    assertFalse(ApiCapabilities.fromDocument(document).openCodeWorktreeManagement)
    paths.remove("/api/project/{id}")
    assertFalse(ApiCapabilities.fromDocument(document).openCodeProjectManagement)
  }

  @Test fun nativeManagementRejectsRequiredExtrasPartialMetadataAndWrongWorktreeTypes() {
    fun body(document: JSONObject, path: String, method: String) = document.getJSONObject("paths").getJSONObject(path)
      .getJSONObject(method).getJSONObject("requestBody").getJSONObject("content").getJSONObject("application/json").getJSONObject("schema")
    for (change in listOf<(JSONObject) -> Unit>(
      { body(it, "/api/project/{id}", "patch").getJSONObject("properties").getJSONObject("icon").getJSONObject("properties").remove("color") },
      { body(it, "/api/project/{id}", "patch").put("required", org.json.JSONArray().put("name")) },
      { body(it, "/api/project/{id}", "patch").getJSONObject("properties").getJSONObject("commands").put("required", org.json.JSONArray().put("unknown")) }
    )) {
      val document = OpenCodeProjectManagementTest.document(); change(document)
      assertFalse(ApiCapabilities.fromDocument(document).openCodeProjectManagement)
      assertTrue(ApiCapabilities.fromDocument(document).openCodeWorktreeManagement)
    }
    for (change in listOf<(JSONObject) -> Unit>(
      { body(it, "/api/worktree", "delete").getJSONObject("properties").put("force", JSONObject().put("type", "string")) },
      { body(it, "/api/worktree", "delete").put("required", org.json.JSONArray().put("projectID").put("directory")) },
      { body(it, "/api/worktree", "post").put("required", org.json.JSONArray().put("projectID").put("branch")) },
      { body(it, "/api/worktree/refresh", "post").put("required", org.json.JSONArray().put("projectID").put("unknown")) },
      { it.getJSONObject("paths").getJSONObject("/api/worktree").getJSONObject("get").getJSONArray("parameters").getJSONObject(0).put("required", false) }
    )) {
      val document = OpenCodeProjectManagementTest.document(); change(document)
      assertTrue(ApiCapabilities.fromDocument(document).openCodeProjectManagement)
      assertFalse(ApiCapabilities.fromDocument(document).openCodeWorktreeManagement)
    }
  }

  @Test fun aDocumentWithoutPathsMeansTheCurrentContract() {
    assertEquals(ApiCapabilities.BASELINE, ApiCapabilities.fromDocument(JSONObject("{}")))
  }
}
