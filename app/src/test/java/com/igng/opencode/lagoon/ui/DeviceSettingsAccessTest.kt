package com.igng.opencode.lagoon.ui

import com.igng.opencode.lagoon.core.HarnessProtocol
import com.igng.opencode.lagoon.core.ServerProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSettingsAccessTest {
  @Test fun dshSettingsFollowTheNativeProtocol() {
    val dsh = ServerProfile("dsh", "DSH", "ssh://user@host:22", protocol = HarnessProtocol.DSH, deviceId = "ssh-1")
    assertTrue(deviceSettingsAccess(dsh).dshProtocol)
  }

  @Test fun nonDshProtocolsNeverShowDshSettings() {
    val others = listOf(
      HarnessProtocol.OPENCODE_NATIVE,
      HarnessProtocol.OPENCODE_ACP,
      HarnessProtocol.CODEX,
      HarnessProtocol.ANTIGRAVITY_ACP
    )
    for (protocol in others) {
      val profile = ServerProfile("h-${protocol.wire}", protocol.label, "ssh://user@host:22", protocol = protocol, deviceId = "ssh-1")
      assertFalse(deviceSettingsAccess(profile).dshProtocol)
    }
  }

  @Test fun disabledPreferenceIsNotMisreportedAsLackOfNativeProtocolSupport() {
    val profile = ServerProfile(
      "dsh", "DSH", "ssh://user@host:22",
      protocol = HarnessProtocol.DSH,
      deviceId = "ssh-1",
      enabled = false,
      deviceEnabled = false
    )
    assertTrue(deviceSettingsAccess(profile).dshProtocol)
    assertFalse(profile.effectiveEnabled)
  }
}
