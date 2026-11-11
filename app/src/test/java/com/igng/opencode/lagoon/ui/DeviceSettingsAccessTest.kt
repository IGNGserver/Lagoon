package com.igng.opencode.lagoon.ui

import com.igng.opencode.lagoon.core.*
import org.junit.Assert.*
import org.junit.Test

class DeviceSettingsAccessTest {
  @Test fun dshSettingsFollowTheNativeProtocolNotTheCarrier() {
    val direct = ServerProfile("direct", "Direct", "https://dsh.example/", kind = ServerKind.DSH)
    val ssh = direct.copy(id = "ssh", url = "ssh://user@host:22", sshHostId = "source")
    val remote = direct.copy(id = "remote", url = "https://hub.example/", kind = ServerKind.DSH_REMOTE, remoteHostId = "host")
    for (profile in listOf(direct, ssh, remote)) assertTrue(deviceSettingsAccess(profile).dshProtocol)
    assertFalse(deviceSettingsAccess(direct).remoteHub)
    assertFalse(deviceSettingsAccess(ssh).remoteHub)
    assertTrue(deviceSettingsAccess(remote).remoteHub)
    assertTrue(deviceSettingsAccess(remote).remoteDiagnostics)
  }

  @Test fun nativeOpenCodeHasHubManagementButNeverDshSettings() {
    val direct = ServerProfile("direct", "Direct", "https://native.example/")
    val ssh = direct.copy(id = "ssh", url = "ssh://user@host:22", sshHostId = "source")
    val remote = direct.copy(id = "remote", url = "https://hub.example/", remoteHostId = "host", remoteServiceId = "native")
    for (profile in listOf(direct, ssh, remote)) {
      assertFalse(deviceSettingsAccess(profile).dshProtocol)
      assertFalse(deviceSettingsAccess(profile).remoteDiagnostics)
    }
    assertTrue(deviceSettingsAccess(remote).remoteHub)
    assertFalse(deviceSettingsAccess(ssh).remoteHub)
    assertFalse(deviceSettingsAccess(direct).remoteHub)
  }

  @Test fun disabledPreferenceIsNotMisreportedAsLackOfNativeProtocolSupport() {
    val profile = ServerProfile("dsh", "DSH", "https://dsh.example/", kind = ServerKind.DSH,
      enabled = false, parentEnabled = false)
    assertTrue(deviceSettingsAccess(profile).dshProtocol)
    assertFalse(profile.effectiveEnabled) // Controls must check the runtime gate separately.
  }
}
