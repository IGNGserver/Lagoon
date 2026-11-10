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

  @Test fun aDocumentWithoutPathsMeansTheCurrentContract() {
    assertEquals(ApiCapabilities.BASELINE, ApiCapabilities.fromDocument(JSONObject("{}")))
  }
}
