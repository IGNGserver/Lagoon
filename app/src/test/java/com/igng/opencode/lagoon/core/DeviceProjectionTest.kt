package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProjectionTest {
  private val now = 1_700_000_000_000L

  private fun device(
    id: String = "ssh-1",
    name: String = "arch-dev",
    enabled: Boolean = true,
    harnesses: List<DeviceHarness> = emptyList()
  ) = SshDevice(
    id = id,
    name = name,
    host = "arch.test",
    port = 22,
    user = "me",
    enabled = enabled,
    harnesses = harnesses
  )

  private fun available(observedAt: Long = now - 5_000) = HarnessDiscoveryStatus(
    installed = DiscoveryValue.YES, configured = DiscoveryValue.YES, listening = DiscoveryValue.YES,
    authenticated = DiscoveryValue.YES, compatible = DiscoveryValue.YES, available = DiscoveryValue.YES,
    observedAtMs = observedAt, freshUntilMs = observedAt + 600_000, completeness = DiscoveryCompleteness.COMPLETE
  )

  @Test fun oldDefaultPathObservationReusesTheExistingDefaultHarness() {
    val dev = device(
      harnesses = listOf(DeviceHarness("old-default", "ssh-1", HarnessProtocol.OPENCODE_NATIVE))
    )
    val discovery = DeviceDiscovery("ssh-1", now, listOf(DiscoveredHarness(
      HarnessProtocol.OPENCODE_NATIVE, "/home/me/.local/state/opencode/service.json", "OpenCode", available()
    )))
    val slots = DeviceProjection.devices(listOf(dev), discovery = { discovery }, nowMs = now)
      .single().harnesses.filter { it.protocol == HarnessProtocol.OPENCODE_NATIVE }
    assertEquals(1, slots.size)
    assertEquals("old-default", slots.single().harnessId?.value)
  }

  @Test fun existingDistinctRegistrationPathsRemainSeparate() {
    val dev = device(
      harnesses = listOf(
        DeviceHarness("old-default", "ssh-1", HarnessProtocol.OPENCODE_NATIVE),
        DeviceHarness("explicit", "ssh-1", HarnessProtocol.OPENCODE_NATIVE, config = HarnessConfig.OpenCodeNative("/custom/service.json"))
      )
    )
    val discovery = DeviceDiscovery("ssh-1", now, listOf(DiscoveredHarness(
      HarnessProtocol.OPENCODE_NATIVE, "/custom/service.json", "OpenCode", available()
    )))
    val slots = DeviceProjection.devices(listOf(dev), discovery = { discovery }, nowMs = now)
      .single().harnesses.filter { it.protocol == HarnessProtocol.OPENCODE_NATIVE }
    assertEquals(setOf("old-default", "explicit"), slots.mapNotNull { it.harnessId?.value }.toSet())
  }

  @Test fun expiredSshNegativeIsUnknownAndCanBeRetried() {
    val dev = device(harnesses = listOf(DeviceHarness("h-dsh", "ssh-1", HarnessProtocol.DSH)))
    val stale = HarnessDiscoveryStatus(
      installed = DiscoveryValue.NO, observedAtMs = now - 700_000,
      freshUntilMs = now - 100_000, completeness = DiscoveryCompleteness.COMPLETE
    )
    val discovery = DeviceDiscovery(
      "ssh-1", now - 700_000,
      listOf(DiscoveredHarness(HarnessProtocol.CODEX, null, "Codex", stale))
    )
    val slot = DeviceProjection.devices(listOf(dev), discovery = { discovery }, nowMs = now)
      .single().harnesses.first { it.protocol == HarnessProtocol.CODEX }
    assertTrue(slot.attachable)
    assertEquals("上次发现已过期", slot.reason)
  }

  @Test fun oneDeviceCarriesEveryHarnessOfTheSameHost() {
    val dev = device(
      harnesses = listOf(
        DeviceHarness("h-dsh", "ssh-1", HarnessProtocol.DSH),
        DeviceHarness("h-codex", "ssh-1", HarnessProtocol.CODEX)
      )
    )
    val projected = DeviceProjection.devices(listOf(dev), nowMs = now).single()
    assertEquals(2, projected.attachedCount)
    assertEquals(2, projected.enabledCount)
    assertTrue(projected.effectiveEnabled)
    assertEquals(setOf("h-dsh", "h-codex"), projected.harnesses.mapNotNull { it.harnessId?.value }.toSet())
    assertTrue(projected.harnesses.any { it.name == "DeepSeek Harness" })
    assertTrue(projected.harnesses.any { it.name == "Codex" })
  }

  @Test fun disablingTheDeviceBlocksEveryHarnessWithoutLosingTheirSwitches() {
    val dev = device(
      enabled = false,
      harnesses = listOf(
        DeviceHarness("h-dsh", "ssh-1", HarnessProtocol.DSH, enabled = true),
        DeviceHarness("h-codex", "ssh-1", HarnessProtocol.CODEX, enabled = true)
      )
    )
    val projected = DeviceProjection.devices(listOf(dev), nowMs = now).single()
    assertFalse(projected.effectiveEnabled)
    assertEquals("此设备已停用", projected.blockedReason)
    assertEquals(2, projected.enabledCount)
    assertTrue(dev.toProfiles().all { it.enabled && !it.deviceEnabled && !it.effectiveEnabled })
  }

  @Test fun disablingOneHarnessDoesNotTouchItsSibling() {
    val dev = device(
      harnesses = listOf(
        DeviceHarness("h-dsh", "ssh-1", HarnessProtocol.DSH, enabled = true),
        DeviceHarness("h-codex", "ssh-1", HarnessProtocol.CODEX, enabled = false)
      )
    )
    val projected = DeviceProjection.devices(listOf(dev), nowMs = now).single()
    val dsh = projected.harnesses.first { it.harnessId?.value == "h-dsh" }
    val codex = projected.harnesses.first { it.harnessId?.value == "h-codex" }
    assertTrue(dsh.effectiveEnabled)
    assertFalse(codex.effectiveEnabled)
    assertEquals(1, projected.enabledCount)
  }

  @Test fun discoveryOffersAttachableSlotsAndExplainsTheRest() {
    val dev = device()
    val missing = HarnessDiscoveryStatus(
      installed = DiscoveryValue.NO, observedAtMs = now - 1_000, freshUntilMs = now + 600_000,
      completeness = DiscoveryCompleteness.COMPLETE
    )
    val observed = DeviceDiscovery(
      deviceId = "ssh-1", observedAtMs = now - 1_000,
      harnesses = listOf(
        DiscoveredHarness(HarnessProtocol.CODEX, name = "Codex", status = available()),
        DiscoveredHarness(HarnessProtocol.OPENCODE_NATIVE, locator = "/state/service.json", name = "OpenCode", status = missing)
      )
    )
    val projected = DeviceProjection.devices(listOf(dev), discovery = { observed }, nowMs = now).single()
    val codex = projected.harnesses.first { it.protocol == HarnessProtocol.CODEX }
    assertTrue(codex.attachable)
    assertNull(codex.harnessId)
    val openCode = projected.harnesses.first { it.protocol == HarnessProtocol.OPENCODE_NATIVE }
    assertFalse(openCode.attachable)
    assertEquals("未安装", openCode.reason)
    assertNotNull(projected.discovery)
    assertTrue(projected.discovery!!.isFresh(now))
  }

  @Test fun savedSshDeviceWithoutHarnessIsAlwaysADeviceWithDefaultCandidates() {
    val dev = device()
    val projected = DeviceProjection.devices(listOf(dev), nowMs = now).single()
    assertEquals("me@arch.test", projected.endpoint)
    assertEquals(0, projected.attachedCount)
    assertTrue(projected.harnesses.any { it.protocol == HarnessProtocol.DSH && it.attachable })
    assertTrue(projected.harnesses.any { it.protocol == HarnessProtocol.OPENCODE_NATIVE && it.attachable })
  }

  @Test fun harnessSlotKeyIsProtocolAndLocator() {
    val withPath = ServerProfile(
      "h1", "OC", "ssh://me@arch.test:22",
      protocol = HarnessProtocol.OPENCODE_NATIVE,
      deviceId = "ssh-1",
      config = HarnessConfig.OpenCodeNative("/state/opencode/service.json")
    )
    val defaultPath = withPath.copy(id = "h2", config = HarnessConfig.OpenCodeNative(null))
    assertEquals("opencode-native|/state/opencode/service.json", DeviceProjection.slotKey(withPath))
    assertEquals("opencode-native|", DeviceProjection.slotKey(defaultPath))
  }
}
