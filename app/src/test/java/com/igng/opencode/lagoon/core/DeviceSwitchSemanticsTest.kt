package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开关语义（`docs/DEVICE_MODEL.md`）：
 * - 设备开关只写 `SshDevice.enabled`，关父级不覆写子 Harness 偏好；
 * - 设备停用后投影出的 `ServerProfile.effectiveEnabled` 全部为 false；
 * - 只关一条 Harness 不影响同设备兄弟 Harness。
 */
class DeviceSwitchSemanticsTest {
  private val now = 1_700_000_000_000L

  private fun device(enabled: Boolean = true, codexEnabled: Boolean = true) = SshDevice(
    id = "ssh-1",
    name = "arch-dev",
    host = "arch.test",
    port = 22,
    user = "me",
    enabled = enabled,
    harnesses = listOf(
      DeviceHarness("leaf-dsh", "ssh-1", HarnessProtocol.DSH, enabled = true),
      DeviceHarness("leaf-codex", "ssh-1", HarnessProtocol.CODEX, enabled = codexEnabled)
    )
  )

  @Test fun disablingTheDeviceDeniesEveryHarnessAndKeepsTheirOwnSwitches() {
    val dev = device(enabled = false)
    val profiles = dev.toProfiles()
    assertTrue("设备停用后运行时必须拒绝每一条 Harness", profiles.none { it.effectiveEnabled })
    assertTrue("Harness 自己的开关原样保留", profiles.all { it.enabled })
    assertTrue("deviceEnabled 反映父级门控", profiles.none { it.deviceEnabled })

    val projected = DeviceProjection.devices(listOf(dev), nowMs = now).single()
    assertFalse(projected.effectiveEnabled)
    assertEquals("此设备已停用", projected.blockedReason)
    assertEquals(2, projected.enabledCount)
  }

  @Test fun disablingOneHarnessDoesNotTouchItsSibling() {
    val dev = device(enabled = true, codexEnabled = false)
    val profiles = dev.toProfiles()
    assertEquals(setOf("leaf-dsh"), profiles.filter { it.effectiveEnabled }.map { it.id }.toSet())

    val projected = DeviceProjection.devices(listOf(dev), nowMs = now).single()
    assertEquals(1, projected.enabledCount)
    assertTrue(projected.harnesses.first { it.harnessId?.value == "leaf-dsh" }.effectiveEnabled)
    assertFalse(projected.harnesses.first { it.harnessId?.value == "leaf-codex" }.effectiveEnabled)
  }
}
