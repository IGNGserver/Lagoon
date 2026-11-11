package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ProfileNamesTest {
  private val hostId = "01a197f8-0000-4000-8000-000000000001"
  private val hub = "https://hub.example/"

  private fun device(id: String, name: String, host: String? = hostId, url: String = hub) =
    ServerProfile(id = id, name = name, url = url, username = "lviwang", kind = ServerKind.DSH_REMOTE,
      remoteHostId = host)

  private fun harness(id: String, name: String) =
    ServerProfile(id = id, name = name, url = "http://127.0.0.1:4096", username = "opencode",
      kind = ServerKind.OPENCODE)

  private fun catalog(hostName: String = "ubuntu-dev", id: String = hostId) = RemoteCatalog(
    origin = hub, account = "lviwang", provider = null, deviceLabel = "Lagoon",
    hosts = listOf(RemoteHostMeta(id, hostName, "linux", true, null, null, null, null)), updatedAt = 1
  )

  @Test fun harnessWithTheSameNameDoesNotPushADeviceToSuffix() {
    val profiles = listOf(harness("oc", "ubuntu-dev"))
    assertEquals("ubuntu-dev", ProfileNames.uniqueDeviceName("ubuntu-dev", profiles))
  }

  @Test fun onlyAnotherDeviceForcesASuffix() {
    val profiles = listOf(harness("oc", "ubuntu-dev"), device("one", "ubuntu-dev"))
    assertEquals("ubuntu-dev (2)", ProfileNames.uniqueDeviceName("ubuntu-dev", profiles))
  }

  @Test fun suffixSkipsNamesAlreadyTakenByOtherDevices() {
    val profiles = listOf(device("one", "ubuntu-dev"), device("two", "ubuntu-dev (2)"))
    assertEquals("ubuntu-dev (3)", ProfileNames.uniqueDeviceName("ubuntu-dev", profiles))
  }

  @Test fun repairDropsASuffixCausedOnlyByAHarness() {
    val profiles = listOf(harness("oc", "ubuntu-dev"), device("dsh", "ubuntu-dev (2)"))
    val repaired = ProfileNames.repairGeneratedSuffixes(profiles, listOf(catalog()))
    assertEquals(listOf("ubuntu-dev", "ubuntu-dev"), repaired.map { it.name })
    assertEquals("oc", repaired[0].id)
  }

  @Test fun repairKeepsTheSuffixWhenAnotherDeviceOwnsTheBaseName() {
    val secondHost = "01a197f8-0000-4000-8000-000000000002"
    val profiles = listOf(device("one", "ubuntu-dev"), device("two", "ubuntu-dev (2)", host = secondHost))
    val catalogs = listOf(RemoteCatalog(hub, "lviwang", null, "Lagoon", listOf(
      RemoteHostMeta(hostId, "ubuntu-dev", "linux", true, null, null, null, null),
      RemoteHostMeta(secondHost, "ubuntu-dev", "linux", true, null, null, null, null)
    ), 1))
    val repaired = ProfileNames.repairGeneratedSuffixes(profiles, catalogs)
    assertEquals(listOf("ubuntu-dev", "ubuntu-dev (2)"), repaired.map { it.name })
  }

  @Test fun repairNeverRewritesAUserNameThatIsNotTheHostName() {
    val profiles = listOf(device("dsh", "my workstation (2)"))
    assertEquals(profiles, ProfileNames.repairGeneratedSuffixes(profiles, listOf(catalog())))
  }

  @Test fun repairDoesNothingWithoutACatalog() {
    val profiles = listOf(device("dsh", "ubuntu-dev (2)"))
    assertSame(profiles, ProfileNames.repairGeneratedSuffixes(profiles, emptyList()))
  }

  @Test fun anotherAccountsCatalogCannotRenameThisProfilesDevice() {
    val profiles = listOf(device("dsh", "ubuntu-dev (2)"))
    val other = catalog().copy(account = "another@example.org")
    assertEquals(profiles, ProfileNames.repairGeneratedSuffixes(profiles, listOf(other)))
  }

  @Test fun adoptHostNameOnlyRepairsTheMatchingHost() {
    val profiles = listOf(device("dsh", "ubuntu-dev (2)"))
    assertEquals("ubuntu-dev", ProfileNames.adoptHostName(profiles.single(), "ubuntu-dev", profiles)?.name)
    assertNull(ProfileNames.adoptHostName(profiles.single(), "other-host", profiles))
    assertNull(ProfileNames.adoptHostName(profiles.single(), "ubuntu-dev", listOf(
      device("other", "ubuntu-dev"), profiles.single()
    )))
  }
}
