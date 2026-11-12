package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProjectionTest {
  private val now = 1_700_000_000_000L

  private fun hubDsh(id: String, name: String = "ubuntu-dev", host: String = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa") = ServerProfile(
    id = id, name = name, url = "https://hub.example.test", username = "me@example.test",
    kind = ServerKind.DSH_REMOTE, remoteHostId = host, remoteHostFingerprint = "0123456789ab"
  )

  private fun hubCodex(id: String, host: String = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa") = ServerProfile(
    id = id, name = "Codex", url = "https://hub.example.test", username = "me@example.test",
    kind = ServerKind.CODEX, remoteHostId = host, remoteHostFingerprint = "0123456789ab"
  )

  private fun sshLeaf(id: String, hostId: String, kind: ServerKind = ServerKind.DSH) = ServerProfile(
    id = id, name = "arch-dev", url = "ssh://me@arch.test:22", username = "me", kind = kind, sshHostId = hostId
  )

  private fun graphOf(profiles: List<ServerProfile>, sourceEnabled: Boolean = true, hostEnabled: Boolean = true) =
    MigrationProjection.fromProfiles(profiles).let { graph ->
      graph.copy(
        sources = graph.sources.map { it.copy(enabled = sourceEnabled) },
        hosts = graph.hosts.map { it.copy(enabled = hostEnabled) }
      )
    }

  private fun available(observedAt: Long = now - 5_000) = HarnessDiscoveryStatus(
    installed = DiscoveryValue.YES, configured = DiscoveryValue.YES, listening = DiscoveryValue.YES,
    authenticated = DiscoveryValue.YES, compatible = DiscoveryValue.YES, available = DiscoveryValue.YES,
    observedAtMs = observedAt, freshUntilMs = observedAt + 600_000, completeness = DiscoveryCompleteness.COMPLETE
  )

  @Test fun oneDeviceCarriesEveryHarnessOfTheSameHost() {
    val profiles = listOf(hubDsh("leaf-dsh"), hubCodex("leaf-codex"))
    val graph = graphOf(profiles)
    val devices = DeviceProjection.devices(graph, profiles, adoptedDeviceIds = null, nowMs = now)

    assertEquals(1, devices.size)
    val device = devices.single()
    assertEquals(DeviceAccess.HUB, device.access)
    assertEquals(2, device.attachedCount)
    assertEquals(2, device.enabledCount)
    assertTrue(device.effectiveEnabled)
    assertEquals(setOf("leaf-dsh", "leaf-codex"), device.harnesses.mapNotNull { it.leafId?.value }.toSet())
  }

  @Test fun disablingTheDeviceBlocksEveryHarnessWithoutLosingTheirSwitches() {
    val profiles = listOf(hubDsh("leaf-dsh"), hubCodex("leaf-codex"))
    val devices = DeviceProjection.devices(graphOf(profiles, hostEnabled = false), profiles, nowMs = now)

    val device = devices.single()
    assertFalse(device.effectiveEnabled)
    assertEquals("此设备已停用", device.blockedReason)
    assertTrue(device.harnesses.all { it.attached })
    assertTrue(device.harnesses.all { it.enabled })
    // The device gate is separate from the Harness preferences: turning the device back on restores both.
    assertEquals(2, device.enabledCount)
  }

  @Test fun disablingOneHarnessDoesNotTouchItsSibling() {
    val profiles = listOf(hubDsh("leaf-dsh"), hubCodex("leaf-codex").copy(enabled = false))
    val device = DeviceProjection.devices(graphOf(profiles), profiles, nowMs = now).single()

    val dsh = device.harnesses.first { it.leafId?.value == "leaf-dsh" }
    val codex = device.harnesses.first { it.leafId?.value == "leaf-codex" }
    assertTrue(dsh.effectiveEnabled)
    assertFalse(codex.effectiveEnabled)
    assertEquals(1, device.enabledCount)
  }

  @Test fun hubHostWithoutHarnessIsADeviceButOnlyWhenAdopted() {
    val host = HostBinding("host-1", "source-1", "windows-vm", externalHostId = "host-1", fingerprint = "0123456789ab")
    val graph = ConnectionGraph(
      sources = listOf(ConnectionSource("source-1", ConnectionSourceType.HUB, "hub", "https://hub.example.test", account = "me")),
      hosts = listOf(host)
    )

    assertTrue(DeviceProjection.devices(graph, emptyList(), adoptedDeviceIds = emptySet(), nowMs = now).isEmpty())

    val adopted = DeviceProjection.devices(graph, emptyList(), adoptedDeviceIds = setOf("host-1"), nowMs = now)
    val device = adopted.single()
    assertEquals(0, device.attachedCount)
    assertTrue(device.harnesses.any { it.protocol == HarnessProtocolId.DSH_TYPERT && it.attachable })
  }

  @Test fun sshSourceFlagIsNotASecondGate() {
    val profiles = listOf(sshLeaf("leaf-ssh", "ssh-config"))
    val devices = DeviceProjection.devices(graphOf(profiles, sourceEnabled = false), profiles, nowMs = now)

    val device = devices.single()
    assertEquals(DeviceAccess.SSH, device.access)
    assertTrue(device.sourceEnabled)
    assertTrue(device.effectiveEnabled)
  }

  @Test fun discoveryOffersAttachableSlotsAndExplainsTheRest() {
    val host = HostBinding("host-1", "source-1", "ubuntu-dev", externalHostId = "host-1")
    val graph = ConnectionGraph(
      sources = listOf(ConnectionSource("source-1", ConnectionSourceType.HUB, "hub", "https://hub.example.test", account = "me")),
      hosts = listOf(host)
    )
    val missing = HarnessDiscoveryStatus(
      installed = DiscoveryValue.NO, observedAtMs = now - 1_000, freshUntilMs = now + 600_000,
      completeness = DiscoveryCompleteness.COMPLETE
    )
    val observed = DeviceDiscovery(
      deviceId = "host-1", observedAtMs = now - 1_000,
      harnesses = listOf(
        DiscoveredHarness(HarnessProtocolId.CODEX_APP_SERVER, name = "Codex", status = available()),
        DiscoveredHarness(HarnessProtocolId.OPENCODE_NATIVE, locator = "svc-1", name = "OpenCode", status = missing)
      )
    )

    val device = DeviceProjection.devices(graph, emptyList(), adoptedDeviceIds = setOf("host-1"), discovery = { observed }, nowMs = now).single()

    val codex = device.harnesses.first { it.protocol == HarnessProtocolId.CODEX_APP_SERVER }
    assertTrue(codex.attachable)
    assertNull(codex.leafId)
    val openCode = device.harnesses.first { it.protocol == HarnessProtocolId.OPENCODE_NATIVE }
    assertFalse(openCode.attachable)
    assertEquals("未安装", openCode.reason)
    assertNotNull(device.discovery)
    assertTrue(device.discovery!!.isFresh(now))
  }

  @Test fun staleDiscoveryIsNotAttachable() {
    val host = HostBinding("host-1", "source-1", "ubuntu-dev", externalHostId = "host-1")
    val graph = ConnectionGraph(
      sources = listOf(ConnectionSource("source-1", ConnectionSourceType.HUB, "hub", "https://hub.example.test", account = "me")),
      hosts = listOf(host)
    )
    val observed = DeviceDiscovery(
      deviceId = "host-1", observedAtMs = now - DeviceDiscovery.DEFAULT_TTL_MS - 1,
      harnesses = listOf(DiscoveredHarness(HarnessProtocolId.CODEX_APP_SERVER, name = "Codex", status = available(now - DeviceDiscovery.DEFAULT_TTL_MS - 1)))
    )

    val device = DeviceProjection.devices(graph, emptyList(), adoptedDeviceIds = setOf("host-1"), discovery = { observed }, nowMs = now).single()
    val codex = device.harnesses.first { it.protocol == HarnessProtocolId.CODEX_APP_SERVER }
    assertFalse(codex.attachable)
    assertEquals("上次发现已过期", codex.reason)
  }

  @Test fun missingGraphFallsBackToTheSavedProfiles() {
    val profiles = listOf(sshLeaf("leaf-ssh", "ssh-config"))
    val devices = DeviceProjection.devices(null, profiles, nowMs = now)
    assertEquals(1, devices.size)
    assertEquals(DeviceAccess.SSH, devices.single().access)
  }

  @Test fun harnessSlotKeyIsProtocolAndLocator() {
    val profile = sshLeaf("leaf-ssh", "ssh-config", ServerKind.OPENCODE).copy(sshServicePath = "/state/opencode/service.json")
    assertEquals("opencode-native|/state/opencode/service.json", DeviceProjection.slotKey(profile))
    assertEquals("opencode-native|", DeviceProjection.slotKey(sshLeaf("other", "cfg", ServerKind.OPENCODE)))
  }
}
