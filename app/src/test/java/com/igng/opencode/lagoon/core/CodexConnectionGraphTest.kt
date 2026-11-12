package com.igng.opencode.lagoon.core

import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexConnectionGraphTest {
  private val remoteHost = "f2b08ff1-afd6-46a6-8dd4-4de1f026f60a"
  private val sshCodex = ServerProfile("ssh-codex", "Codex over SSH", "ssh://alice@build.example:22",
    username = "alice", kind = ServerKind.CODEX, sshHostId = "shared-ssh")
  private val sshDsh = ServerProfile("ssh-dsh", "DSH over SSH", "ssh://alice@build.example:22",
    username = "alice", kind = ServerKind.DSH, sshHostId = "shared-ssh")
  private val remoteCodex = ServerProfile("remote-codex", "Codex over Remote", "https://hub.example/",
    username = "alice@example.test", kind = ServerKind.CODEX, remoteHostId = remoteHost,
    remoteHostFingerprint = "abcdef012345")
  private val remoteDsh = ServerProfile("remote-dsh", "DSH over Remote", "https://hub.example/",
    username = "alice@example.test", kind = ServerKind.DSH_REMOTE, remoteHostId = remoteHost,
    remoteHostFingerprint = "abcdef012345")
  private val remoteOpenCode = ServerProfile("remote-opencode", "OpenCode over Remote", "https://hub.example/",
    username = "alice@example.test", kind = ServerKind.OPENCODE, remoteHostId = remoteHost,
    remoteHostFingerprint = "abcdef012345", remoteServiceId = "opencode-native")
  private val profiles get() = listOf(sshCodex, sshDsh, remoteCodex, remoteDsh, remoteOpenCode)

  @Test fun codexRoutesRoundTripPreservesIdentityProtocolAndCarrier() {
    val graph = MigrationProjection.fromProfiles(profiles,
      compositions = listOf(BindingComposition("devices", "Devices", listOf("ssh-codex", "remote-codex", "remote-dsh"))))
    val decoded = ConnectionGraphCodec.decode(ConnectionGraphCodec.encode(graph))
    assertEquals(graph, decoded)
    assertEquals(listOf("ssh-codex", "remote-codex", "remote-dsh"), decoded.compositionBindings("devices").map { it.id })
    listOf(sshCodex to ConnectionCarrierId.SSH_FORWARD, remoteCodex to ConnectionCarrierId.REMOTE_NOISE)
      .forEach { (profile, carrier) ->
        val binding = decoded.bindings.single { it.id == profile.id }
        assertEquals(HarnessProtocolId.CODEX_APP_SERVER, binding.protocolId)
        assertEquals(carrier, binding.carrierId)
        assertEquals(profile, binding.legacyProfile)
        val route = ConnectionRouteAdmission.requireCurrent(profile, decoded)
        assertEquals(profile.id, route.bindingId)
        assertEquals(HarnessProtocolId.CODEX_APP_SERVER, route.protocolId)
        assertEquals(carrier, route.carrierId)
        assertEquals(profile, route.executionProfile(profile))
      }
  }

  @Test fun sharedHostGroupsHarnessesWithoutSharingTheirEnabledPreferencesOrProtocol() {
    val graph = MigrationProjection.fromProfiles(profiles)
    val ssh = graph.bindings.single { it.id == sshCodex.id }
    val sshSibling = graph.bindings.single { it.id == sshDsh.id }
    val remote = graph.bindings.single { it.id == remoteCodex.id }
    val remoteSiblings = graph.bindings.filter { it.id == remoteDsh.id || it.id == remoteOpenCode.id }
    assertEquals(ssh.hostBindingId, sshSibling.hostBindingId)
    assertTrue(remoteSiblings.all { it.hostBindingId == remote.hostBindingId })
    assertNotEquals(ssh.hostBindingId, remote.hostBindingId)
    assertEquals(HarnessProtocolId.DSH_TYPERT, sshSibling.protocolId)
    assertEquals(setOf(HarnessProtocolId.DSH_TYPERT, HarnessProtocolId.OPENCODE_NATIVE), remoteSiblings.map { it.protocolId }.toSet())
    assertEquals(ssh.credentials.sshSecrets, sshSibling.credentials.sshSecrets)
    assertEquals(remote.credentials.remoteIdentity?.candidates?.first(), remoteSiblings.first().credentials.remoteIdentity?.candidates?.first())

    val disabled = graph.copy(bindings = graph.bindings.map { if (it.id == remoteCodex.id) it.copy(enabled = false) else it })
    assertFalse(disabled.effectiveEnabled(remoteCodex.id))
    assertTrue(disabled.effectiveEnabled(remoteDsh.id))
    assertTrue(disabled.effectiveEnabled(remoteOpenCode.id))
    assertTrue(disabled.effectiveEnabled(sshCodex.id))
    assertEquals(HarnessProtocolId.DSH_TYPERT, ConnectionRouteAdmission.requireCurrent(remoteDsh, disabled).protocolId)
    assertEquals(HarnessProtocolId.OPENCODE_NATIVE, ConnectionRouteAdmission.requireCurrent(remoteOpenCode, disabled).protocolId)
  }

  @Test fun staleCodexRoutesRejectChangesToHostAccountAndProtocol() {
    val graph = MigrationProjection.fromProfiles(profiles)
    val candidates = listOf(
      sshCodex.copy(sshHostId = "other-ssh"),
      sshCodex.copy(username = "mallory"),
      sshCodex.copy(kind = ServerKind.DSH),
      remoteCodex.copy(remoteHostId = "7737be45-6dce-4f14-99ab-77951c9a6eb1"),
      remoteCodex.copy(remoteHostFingerprint = "123456abcdef"),
      remoteCodex.copy(username = "other@example.test"),
      remoteCodex.copy(kind = ServerKind.DSH_REMOTE)
    )
    candidates.forEach { changed ->
      val error = runCatching { ConnectionRouteAdmission.requireCurrent(changed, graph) }.exceptionOrNull()
      assertTrue("Expected stale route for ${changed.id}: $changed", error is StaleConnectionRouteException)
    }
  }

  @Test fun codexRejectsUnboundAndAmbiguousCarrierProfiles() {
    assertTrue(runCatching {
      ServerProfile("direct-codex", "Invalid", "https://example.test", kind = ServerKind.CODEX)
    }.isFailure)
    assertTrue(runCatching {
      sshCodex.copy(remoteHostId = remoteHost, remoteHostFingerprint = "abcdef012345")
    }.isFailure)
    assertTrue(runCatching { remoteCodex.copy(remoteServiceId = "opencode-native") }.isFailure)
  }

  @Test fun unknownWireKindOrProtocolCannotDecodeAsAnotherHarness() {
    val graph = MigrationProjection.fromProfiles(listOf(sshCodex))
    val encoded = ConnectionGraphCodec.encode(graph)
    val unknownProtocol = JSONObject(encoded).apply {
      getJSONArray("bindings").getJSONObject(0).put("protocolId", "codex-future")
    }.toString()
    val unknownKind = JSONObject(encoded).apply {
      getJSONArray("bindings").getJSONObject(0).getJSONObject("legacyProfile").put("kind", "codex-future")
    }.toString()
    val forgedProtocol = JSONObject(encoded).apply {
      getJSONArray("bindings").getJSONObject(0).put("protocolId", HarnessProtocolId.DSH_TYPERT.wire)
    }.toString()
    val forgedCarrier = JSONObject(encoded).apply {
      getJSONArray("bindings").getJSONObject(0).put("carrierId", ConnectionCarrierId.REMOTE_NOISE.wire)
    }.toString()
    listOf(unknownProtocol, unknownKind, forgedProtocol, forgedCarrier).forEach { raw ->
      assertTrue(runCatching { ConnectionGraphCodec.decode(raw) }.exceptionOrNull() is IOException)
    }
  }
}
