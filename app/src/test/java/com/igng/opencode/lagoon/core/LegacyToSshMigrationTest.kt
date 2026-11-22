package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyToSshMigrationTest {
  @Test fun migratesAllSshDevicesHarnessesGroupsDiscoveryAndCleansRetiredRemoteAndDirect() {
    val devicesPrefs = FakeSharedPreferences()
    val sshSecretsPrefs = FakeSharedPreferences()
    val serversPrefs = FakeSharedPreferences()
    val secretsPrefs = FakeSharedPreferences()
    val sshHostsPrefs = FakeSharedPreferences()
    val connectionGraphPrefs = FakeSharedPreferences()
    val summaryDismissalsPrefs = FakeSharedPreferences()
    val groupPrefs = FakeSharedPreferences()
    val discoveryPrefs = FakeSharedPreferences()
    val retiredRemoteCatalog = FakeSharedPreferences().apply { values["hosts"] = """[{"id":"r1"}]""" }
    val retiredPairingRecovery = FakeSharedPreferences().apply { values["pending"] = "yes" }
    val sshKeysPrefs = FakeSharedPreferences().apply {
      values["keys"] = """{"version":1,"keys":[{"id":"key-ed","name":"Work","algorithm":"ED25519","fingerprint":"SHA256:k","publicKeyOpenSsh":"ssh-ed25519 AAAA work"}]}"""
    }

    // Legacy SSH hosts (one enabled, one disabled via connection-graph).
    val hostA = SshHostConfig(
      id = "ssh-mac",
      name = "Mac Studio",
      host = "mac.local",
      port = 2222,
      user = "alice",
      auth = SshAuthMethod.KEY_WITH_PASSWORD_FALLBACK,
      launchCommand = "dsh web --custom",
      workingDirectory = "/Users/alice/dev",
      hostKeyType = "ssh-ed25519",
      hostKeyFingerprint = "SHA256:mac-fp",
      keyId = "key-ed"
    )
    val hostB = SshHostConfig(
      id = "ssh-linux",
      name = "Build Box",
      host = "10.0.0.20",
      port = 22,
      user = "bob",
      auth = SshAuthMethod.PASSWORD,
      hostKeyType = "ssh-ed25519",
      hostKeyFingerprint = "SHA256:linux-fp"
    )
    sshHostsPrefs.values["hosts"] = SshHostCodec.encode(listOf(hostA, hostB))
    sshSecretsPrefs.values["ssh:ssh-mac:password"] = "enc:mac-pw"
    sshSecretsPrefs.values["ssh:ssh-linux:password"] = "enc:linux-pw"

    // Legacy connection-graph: hostB is disabled at host level.
    val legacyHostIdA = LegacyToSshMigration.legacyHostId(hostA.id)
    val legacyHostIdB = LegacyToSshMigration.legacyHostId(hostB.id)
    connectionGraphPrefs.values["graph"] = JSONObject()
      .put("version", 1)
      .put("sources", JSONArray()
        .put(JSONObject().put("id", "src-mac").put("type", "ssh").put("sshHostConfigId", hostA.id).put("enabled", true))
        .put(JSONObject().put("id", "src-linux").put("type", "ssh").put("sshHostConfigId", hostB.id).put("enabled", true))
        .put(JSONObject().put("id", "src-remote").put("type", "dsh-remote").put("enabled", true)))
      .put("hosts", JSONArray()
        .put(JSONObject().put("id", legacyHostIdA).put("sourceId", "src-mac").put("enabled", true))
        .put(JSONObject().put("id", legacyHostIdB).put("sourceId", "src-linux").put("enabled", false)))
      .toString()

    // Simulate an interrupted v5.3.x configuration save journal whose after snapshot has all profiles.
    val legacyProfilesJson = JSONArray()
      .put(JSONObject().put("id", "leaf-dsh").put("name", "Mac Studio").put("kind", "dsh")
        .put("sshHostId", hostA.id).put("enabled", true).put("notifications", true))
      .put(JSONObject().put("id", "leaf-oc").put("name", "Mac OC").put("kind", "opencode")
        .put("sshHostId", hostA.id).put("sshServicePath", "/Users/alice/.opencode/service.json").put("enabled", true).put("notifications", false))
      .put(JSONObject().put("id", "leaf-oc-acp").put("name", "OC ACP").put("kind", "codex")
        .put("protocolId", "opencode-acp").put("sshHostId", hostA.id).put("enabled", true))
      .put(JSONObject().put("id", "leaf-codex").put("name", "Codex").put("kind", "codex")
        .put("sshHostId", hostA.id).put("enabled", false))
      .put(JSONObject().put("id", "leaf-agy").put("name", "AGY").put("kind", "codex")
        .put("protocolId", "antigravity-acp").put("sshHostId", hostB.id).put("enabled", true))
      .put(JSONObject().put("id", "retired-remote").put("name", "Cloud DSH").put("kind", "dsh-remote")
        .put("remoteHostId", "rm-host-1").put("enabled", true))
      .put(JSONObject().put("id", "retired-direct").put("name", "LAN OpenCode").put("kind", "opencode")
        .put("url", "http://192.168.1.9:4096").put("enabled", true))
      .toString()

    serversPrefs.values["profiles"] = "[]" // lagging before crash
    serversPrefs.values["configuration-save-token"] = "tok-crash-42"
    secretsPrefs.values["configuration-save-journal-v1"] = "enc:" + JSONObject()
      .put("afterToken", "tok-crash-42")
      .put("after", legacyProfilesJson)
      .toString()
    serversPrefs.values["selected"] = "retired-remote"
    serversPrefs.values["homeServer"] = "retired-direct"
    secretsPrefs.values["draft:leaf-dsh:s1"] = "enc:keep my ssh draft"
    secretsPrefs.values["draft:retired-remote:s9"] = "enc:delete remote draft"
    secretsPrefs.values["draft:retired-direct:s8"] = "enc:delete direct draft"
    serversPrefs.values["remoteMigration:old"] = "1"
    summaryDismissalsPrefs.values["retired-remote"] = "1"
    summaryDismissalsPrefs.values["leaf-dsh"] = "1"

    // Legacy DeviceGroups (v3 using legacyHostId + harness members, plus a retired-only group).
    groupPrefs.values["state"] = JSONObject()
      .put("version", 3)
      .put("selected", "g-work")
      .put("groups", JSONArray()
        .put(JSONObject().put("id", "g-work").put("name", "Work")
          .put("devices", JSONArray().put(legacyHostIdA).put("host-retired"))
          .put("members", JSONArray().put("leaf-agy").put("retired-direct")))
        .put(JSONObject().put("id", "g-retired").put("name", "Old Remote Only")
          .put("devices", JSONArray().put("host-retired"))))
      .toString()

    // Legacy DeviceDiscovery (v1 keyed by legacyHostId under "devices" object).
    discoveryPrefs.values["discovery"] = JSONObject()
      .put("version", 1)
      .put("devices", JSONObject()
        .put(legacyHostIdA, JSONObject().put("observedAtMs", 1_000L).put("completeness", "complete")
          .put("harnesses", JSONArray().put(JSONObject().put("protocol", "dsh-typert").put("name", "DeepSeek Harness"))))
        .put("host-retired", JSONObject().put("observedAtMs", 1_000L).put("completeness", "complete")
          .put("harnesses", JSONArray())))
      .toString()

    val deviceStore = DeviceStore(devicesPrefs, sshSecretsPrefs, { "enc:$it" }, { it.removePrefix("enc:") })
    val serverStore = ServerStore(serversPrefs, secretsPrefs, { "enc:$it" }, { it.removePrefix("enc:") }) { deviceStore.profiles() }
    val groupStore = DeviceGroupStore(groupPrefs)
    val discoveryStore = DeviceDiscoveryStore(discoveryPrefs)
    var remoteKeystoreDeleted = false

    val report = LegacyToSshMigration.runIfNeeded(
      deviceStore = deviceStore,
      serverStore = serverStore,
      groupStore = groupStore,
      discoveryStore = discoveryStore,
      serversPrefs = serversPrefs,
      secretsPrefs = secretsPrefs,
      sshHostsPrefs = sshHostsPrefs,
      connectionGraphPrefs = connectionGraphPrefs,
      summaryDismissalsPrefs = summaryDismissalsPrefs,
      retiredPrefs = listOf(retiredRemoteCatalog, retiredPairingRecovery),
      decryptSecret = { it.removePrefix("enc:") },
      deleteRemoteKeystoreAlias = { remoteKeystoreDeleted = true }
    )

    assertNotNull(report)
    assertEquals(2, report!!.migratedDevices)
    assertEquals(5, report.migratedHarnesses)
    assertEquals(1, report.retiredRemoteCount)
    assertEquals(1, report.retiredDirectCount)
    assertTrue(remoteKeystoreDeleted)

    // Verify migrated SSH devices & all 5 Harness protocols with exact preserved IDs.
    val mac = deviceStore.findDevice("ssh-mac")!!
    assertTrue(mac.enabled)
    assertEquals("SHA256:mac-fp", mac.hostKeyFingerprint)
    assertEquals("key-ed", mac.keyId)
    assertEquals("mac-pw", deviceStore.secrets("ssh-mac").password)
    assertEquals(listOf("leaf-dsh", "leaf-oc", "leaf-oc-acp", "leaf-codex"), mac.harnesses.map { it.id })

    val dshHarness = mac.harnesses.first { it.id == "leaf-dsh" }
    assertEquals(HarnessProtocol.DSH, dshHarness.protocol)
    assertEquals(HarnessConfig.Dsh("dsh web --custom", "/Users/alice/dev"), dshHarness.config)

    val ocHarness = mac.harnesses.first { it.id == "leaf-oc" }
    assertEquals(HarnessProtocol.OPENCODE_NATIVE, ocHarness.protocol)
    assertEquals(HarnessConfig.OpenCodeNative("/Users/alice/.opencode/service.json"), ocHarness.config)
    assertFalse(ocHarness.notifications)

    assertEquals(HarnessProtocol.OPENCODE_ACP, mac.harnesses.first { it.id == "leaf-oc-acp" }.protocol)
    assertEquals(HarnessProtocol.CODEX, mac.harnesses.first { it.id == "leaf-codex" }.protocol)
    assertFalse(mac.harnesses.first { it.id == "leaf-codex" }.enabled)

    val linux = deviceStore.findDevice("ssh-linux")!!
    assertFalse("Device-level disabled flag from connection-graph must survive", linux.enabled)
    assertEquals(listOf("leaf-agy"), linux.harnesses.map { it.id })
    assertEquals(HarnessProtocol.ANTIGRAVITY_ACP, linux.harnesses.single().protocol)

    // DeviceGroups upgraded to v4 with pure SshDevice.id; retired-only group dropped.
    val groupsLoaded = groupStore.load()
    assertEquals(DeviceGroupCodec.VERSION, DeviceGroupCodec.version(groupPrefs.values["state"] as String))
    assertEquals("g-work", groupsLoaded.state.selectedGroupId)
    assertEquals(1, groupsLoaded.state.groups.size)
    assertEquals(listOf("ssh-mac", "ssh-linux"), groupsLoaded.state.groups.single().deviceIds)

    // DeviceDiscovery upgraded to v2 keyed by SshDevice.id; retired key pruned.
    assertNotNull(discoveryStore.get("ssh-mac"))
    assertNull(discoveryStore.get(legacyHostIdA))
    assertNull(discoveryStore.get("host-retired"))

    // SSH draft & dismissal kept; retired drafts, dismissals, SPs, profiles & journal cleaned.
    assertEquals("keep my ssh draft", serverStore.draft("leaf-dsh", "s1"))
    assertFalse(secretsPrefs.contains("draft:retired-remote:s9"))
    assertFalse(secretsPrefs.contains("draft:retired-direct:s8"))
    assertFalse(serversPrefs.contains("profiles"))
    assertFalse(serversPrefs.contains("configuration-save-token"))
    assertFalse(serversPrefs.contains("remoteMigration:old"))
    assertFalse(secretsPrefs.contains("configuration-save-journal-v1"))
    assertTrue(connectionGraphPrefs.values.isEmpty())
    assertTrue(sshHostsPrefs.values.isEmpty())
    assertTrue(retiredRemoteCatalog.values.isEmpty())
    assertTrue(retiredPairingRecovery.values.isEmpty())
    assertFalse(summaryDismissalsPrefs.contains("retired-remote"))
    assertTrue(summaryDismissalsPrefs.contains("leaf-dsh"))

    // Selection reassigned away from retired profiles to surviving enabled SSH harness.
    assertEquals("leaf-dsh", serversPrefs.values["selected"])
    assertEquals("leaf-dsh", serversPrefs.values["homeServer"])

    // One-time migration banner stored; SSH keys untouched.
    val notice = serverStore.migrationNotice()!!
    assertTrue(notice.contains("2 台 SSH 设备"))
    assertTrue(notice.contains("1 条 DSH Remote"))
    assertTrue(notice.contains("1 条直连"))
    assertTrue((sshKeysPrefs.values["keys"] as String).contains("key-ed"))

    // Idempotent second run does nothing and never re-shows dismissed notice.
    serverStore.dismissMigrationNotice()
    assertNull(
      LegacyToSshMigration.runIfNeeded(
        deviceStore, serverStore, groupStore, discoveryStore,
        serversPrefs, secretsPrefs, sshHostsPrefs, connectionGraphPrefs, summaryDismissalsPrefs
      )
    )
    assertNull(serverStore.migrationNotice())
    assertEquals(2, deviceStore.load().size)
  }

  @Test fun freshInstallMarksMigrationCompleteWithoutEmittingAnyNotice() {
    val deviceStore = DeviceStore(FakeSharedPreferences(), FakeSharedPreferences(), { it }, { it })
    val serverStore = ServerStore(FakeSharedPreferences(), FakeSharedPreferences(), { it }, { it })
    val report = LegacyToSshMigration.runIfNeeded(
      deviceStore = deviceStore,
      serverStore = serverStore,
      groupStore = DeviceGroupStore(FakeSharedPreferences()),
      discoveryStore = DeviceDiscoveryStore(FakeSharedPreferences()),
      serversPrefs = FakeSharedPreferences(),
      secretsPrefs = FakeSharedPreferences(),
      sshHostsPrefs = FakeSharedPreferences(),
      connectionGraphPrefs = FakeSharedPreferences(),
      summaryDismissalsPrefs = FakeSharedPreferences()
    )
    assertNull(report)
    assertNull(serverStore.migrationNotice())
    assertEquals(LegacyToSshMigration.TARGET_MIGRATION_VERSION, deviceStore.migrationVersion())
  }
}
