package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RemoteCapabilitiesTest {
  @Test fun selectsOnlyAuthenticatedAvailableBackends() {
    val capabilities = RemoteCapabilities.parse(JSONObject("""{
      "capabilities":["harness.remote.v3","agent.acp.cursor.v1","agent.acp.pi.v1"],
      "workspaceTypes":[{"id":"cursor","name":"Cursor","capability":"agent.acp.cursor.v1","available":true},
      {"id":"pi","name":"Pi","capability":"agent.acp.pi.v1","available":false}]}
    """))
    assertEquals(3, capabilities.sessionFormat)
    assertTrue(capabilities.supportsWorkspace("cursor", "agent.acp.cursor.v1"))
    assertFalse(capabilities.supportsWorkspace("pi", "agent.acp.pi.v1"))
    assertFalse(capabilities.supportsWorkspace("cursor", "agent.acp.other.v1"))
    assertFalse(capabilities.supportsWorkspaceUpload)
  }

  @Test fun workspaceUploadIsCapabilityGated() {
    val supported = RemoteCapabilities.parse(JSONObject("""{"capabilities":["harness.remote.v3","workspace.upload.v1"]}"""))
    assertTrue(supported.supportsWorkspaceUpload)
    val unsupported = RemoteCapabilities.parse(JSONObject("""{"capabilities":["harness.remote.v3"]}"""))
    assertFalse(unsupported.supportsWorkspaceUpload)
  }

  @Test fun rejectsLegacyMissingAndConflictingCarrier() {
    for (text in listOf("{}", """{"capabilities":["harness.api.v1"]}""",
      """{"capabilities":["harness.remote.v1","harness.remote.v3"]}""",
      """{"capabilities":["harness.remote.v1","harness.remote.v1"]}""")) {
      try { RemoteCapabilities.parse(JSONObject(text)); fail("expected refusal") } catch (_: IOException) { }
    }
  }

  @Test fun missingWorkspaceTypesAllowsOnlyDeclaredCapabilitiesAndDuplicateTypeRefuses() {
    val legacy = RemoteCapabilities.parse(JSONObject("""{"capabilities":["harness.remote.v1","agent.acp.pi.v1"]}"""))
    assertTrue(legacy.supportsWorkspace("pi", "agent.acp.pi.v1"))
    assertFalse(legacy.supportsWorkspace("other", "agent.acp.other.v1"))
    val duplicate = RemoteCapabilities.parse(JSONObject("""{"capabilities":["harness.remote.v1","agent.acp.pi.v1"],
      "workspaceTypes":[{"id":"pi","name":"Pi","capability":"agent.acp.pi.v1","available":true},
      {"id":"pi","name":"Pi","capability":"agent.acp.pi.v1","available":true}]}"""))
    assertFalse(duplicate.supportsWorkspace("pi", "agent.acp.pi.v1"))
  }

  @Test fun malformedWorkspaceTypesNeverEnableFallbackButDoNotHideValidTypes() {
    val types = RemoteCapabilities.parse(JSONObject("""{"capabilities":["harness.remote.v3","agent.acp.pi.v1"],
      "workspaceTypes":[null,{"id":"broken"},{"id":"pi","name":"Pi","capability":"agent.acp.pi.v1","available":true}]}"""))
    assertTrue(types.supportsWorkspace("pi", "agent.acp.pi.v1"))
    val bad = RemoteCapabilities.parse(JSONObject("""{"capabilities":["harness.remote.v3","agent.acp.pi.v1"],"workspaceTypes":{}}"""))
    assertFalse(bad.supportsWorkspace("pi", "agent.acp.pi.v1"))
  }

  @Test fun acceptsAcpOnlyHostWithoutInventingHarnessCarrier() {
    val acp = RemoteCapabilities.parse(JSONObject("""{"capabilities":["agent.acp.pi.v1"]}"""))
    assertNull(acp.sessionFormat)
    assertTrue(acp.supportsWorkspace("pi", "agent.acp.pi.v1"))
    try {
      RemoteCapabilities.parse(JSONObject("""{"capabilities":["agent.acp.pi.v1"],"workspaceTypes":[]}"""))
      fail("unavailable backend must not make host supported")
    } catch (_: IOException) { }
  }
}
