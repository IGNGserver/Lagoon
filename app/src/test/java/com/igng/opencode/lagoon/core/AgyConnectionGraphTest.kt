package com.igng.opencode.lagoon.core

import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgyConnectionGraphTest {
  private val agy = ServerProfile("agy", "AGY", "ssh://alice@host:22", username = "alice", kind = ServerKind.CODEX,
    sshHostId = "host", protocolId = HarnessProtocolId.ANTIGRAVITY_ACP)
  private val codex = ServerProfile("codex", "Codex", "ssh://alice@host:22", username = "alice", kind = ServerKind.CODEX,
    sshHostId = "host")
  @Test fun distinctProtocolsShareHostAndRoundTripWithoutRenumbering() {
    val graph = MigrationProjection.fromProfiles(listOf(agy, codex))
    val decoded = ConnectionGraphCodec.decode(ConnectionGraphCodec.encode(graph))
    assertEquals(graph, decoded)
    assertEquals(decoded.bindings[0].hostBindingId, decoded.bindings[1].hostBindingId)
    assertEquals(HarnessProtocolId.ANTIGRAVITY_ACP, decoded.bindings[0].protocolId)
    assertEquals(HarnessProtocolId.CODEX_APP_SERVER, decoded.bindings[1].protocolId)
    assertEquals(agy, ConnectionRouteAdmission.requireCurrent(agy, decoded).executionProfile(agy))
    assertNotEquals(DeviceProjection.slotKey(agy), DeviceProjection.slotKey(codex))
  }
  @Test fun newKindCannotBeMisreadAsCodexAndConflictingWireFailsClosed() {
    val encoded = ConnectionGraphCodec.encode(MigrationProjection.fromProfiles(listOf(agy)))
    val legacy = JSONObject(encoded).getJSONArray("bindings").getJSONObject(0).getJSONObject("legacyProfile")
    assertEquals("antigravity-acp", legacy.getString("kind"))
    assertEquals(HarnessProtocolId.ANTIGRAVITY_ACP, MigrationProjection.storedProtocol("antigravity-acp", null))
    assertTrue(runCatching { MigrationProjection.storedProtocol("antigravity-acp", "codex-app-server") }.isFailure)
    val forged = JSONObject(encoded).apply { getJSONArray("bindings").getJSONObject(0).put("protocolId", "codex-app-server") }.toString()
    assertTrue(runCatching { ConnectionGraphCodec.decode(forged) }.exceptionOrNull() is IOException)
  }
}
