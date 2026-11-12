package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开关语义（`docs/DEVICE_MODEL.md` §3–§4）：
 * - 设备开关只写 `HostBinding.enabled`，关父级不覆写子项偏好；
 * - 设备停用后运行时投影必须拒绝它的每一条 leaf；
 * - 只关一条 Harness 不影响兄弟。
 */
class DeviceSwitchSemanticsTest {
  private val now = 1_700_000_000_000L

  private fun sshProfiles() = listOf(
    ServerProfile("leaf-dsh", "arch-dev", "ssh://me@arch.test:22", "me", kind = ServerKind.DSH, sshHostId = "ssh-config"),
    ServerProfile("leaf-codex", "arch-dev", "ssh://me@arch.test:22", "me", kind = ServerKind.CODEX, sshHostId = "ssh-config")
  )

  private fun graphOf(profiles: List<ServerProfile>, hostEnabled: Boolean) =
    MigrationProjection.fromProfiles(profiles).let { graph ->
      graph.copy(hosts = graph.hosts.map { it.copy(enabled = hostEnabled) })
    }

  @Test fun disablingTheDeviceDeniesEveryLeafAndKeepsTheirOwnSwitches() {
    val profiles = sshProfiles()
    val graph = graphOf(profiles, hostEnabled = false)

    val runtime = ConnectionRuntimeProjection.apply(profiles, graph)
    assertTrue("设备停用后运行时必须拒绝每一条 leaf", runtime.none { it.effectiveEnabled })
    assertTrue("leaf 自己的开关原样保留", runtime.all { it.enabled })
    assertTrue("parentEnabled 只是运行时叠加，不写回 leaf", profiles.all { it.parentEnabled })

    val device = DeviceProjection.devices(graph, profiles, nowMs = now).single()
    assertFalse(device.effectiveEnabled)
    assertEquals("此设备已停用", device.blockedReason)
    assertEquals(2, device.enabledCount)
  }

  @Test fun disablingOneHarnessDoesNotTouchItsSibling() {
    val profiles = sshProfiles().map { if (it.id == "leaf-codex") it.copy(enabled = false) else it }
    val graph = graphOf(profiles, hostEnabled = true)

    val runtime = ConnectionRuntimeProjection.apply(profiles, graph)
    assertEquals(setOf("leaf-dsh"), runtime.filter { it.effectiveEnabled }.map { it.id }.toSet())

    val device = DeviceProjection.devices(graph, profiles, nowMs = now).single()
    assertEquals(1, device.enabledCount)
    assertTrue(device.harnesses.first { it.leafId?.value == "leaf-dsh" }.effectiveEnabled)
    assertFalse(device.harnesses.first { it.leafId?.value == "leaf-codex" }.effectiveEnabled)
  }

  @Test fun anUnreadableGraphFailsClosedInsteadOfDiallingEverything() {
    val profiles = sshProfiles()
    val runtime = ConnectionRuntimeProjection.apply(profiles, graph = null, unavailable = true)
    assertTrue(runtime.none { it.effectiveEnabled })
    assertTrue(runtime.all { it.enabled })
  }
}
