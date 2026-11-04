package com.igng.opencode.lagoon.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class RemoteDeviceStoreTest {
  private fun tokens(accessExpiry: Long = 200_000) = RemoteTokenPair("device-access-token-123", accessExpiry, "device-refresh-token-123", 900_000)

  @Test fun storesSealedIdentityAndSerializesRefreshAcrossManagers() = runBlocking {
    val directory = Files.createTempDirectory("remote-device-test")
    val store = HubRemoteDeviceStore(directory, SecretBox(ByteArray(32) { it.toByte() }))
    val first = RemoteDeviceManager(store, "phone", "https://hub.example", "user@example.com",
      { _, _ -> tokens(100_000) }, { _, _ -> tokens(300_000) })
    val second = RemoteDeviceManager(store, "phone", "https://hub.example/", "user@example.com",
      { _, _ -> tokens(100_000) }, { _, _ -> tokens(300_000) })
    val registerer: suspend (com.igng.opencode.lagoon.core.RemoteClientDescriptor, String) -> RemoteTokenPair = { _, _ -> tokens(100_000) }
    val registered = first.register("Lagoon", "jvm", "1.0", "account-token-123", registerer)
    val stored = Files.readString(directory.resolve("remote-devices/phone.json"))
    assertFalse(stored.contains(registered.identity.privateKey))
    assertFalse(stored.contains(registered.tokens!!.refreshToken))
    var refreshes = 0
    val results = listOf(first, second).map { manager -> async {
      manager.access(90_000) { _, _ -> refreshes++; delay(30); tokens(300_000) }
    } }.awaitAll()
    assertEquals(1, refreshes)
    assertEquals(results[0].identity.deviceId, results[1].identity.deviceId)
    assertEquals(RemoteDeviceRecord.Transition.NONE, store.load("phone")!!.transition)
  }

  @Test fun unknownRefreshResultNeverReplaysAndRequiresReauthentication() = runBlocking {
    val directory = Files.createTempDirectory("remote-device-test")
    val store = HubRemoteDeviceStore(directory, SecretBox(ByteArray(32)))
    val manager = RemoteDeviceManager(store, "hub", "https://hub.example", "user@example.com",
      { _, _ -> tokens(100_000) }, { _, _ -> tokens() })
    val before = manager.register("Lagoon", "jvm", "1.0", "account-token-123") { _, _ -> tokens(100_000) }
    try { manager.access(90_000) { _, _ -> throw IOException("response lost") }; fail() } catch (_: IOException) { }
    assertEquals(RemoteDeviceRecord.Transition.REFRESHING, store.load("hub")!!.transition)
    try { manager.access(90_000) { _, _ -> fail("must not replay"); tokens() }; fail() } catch (_: IOException) { }
    val recovered = manager.register("Lagoon", "jvm", "1.0", "account-token-123") { descriptor, _ ->
      assertEquals(before.identity.deviceId, descriptor.deviceId)
      tokens()
    }
    assertEquals(RemoteDeviceRecord.Transition.NONE, recovered.transition)
    try { RemoteDeviceManager(store, "hub", "https://other.example", "user@example.com").access { _, _ -> tokens() }; fail() }
    catch (_: IOException) { }
  }

  @Test fun corruptionNeverCreatesANewIdentity() {
    val directory = Files.createTempDirectory("remote-device-test")
    val store = HubRemoteDeviceStore(directory, SecretBox(ByteArray(32)))
    Files.createDirectories(directory.resolve("remote-devices"))
    Files.writeString(directory.resolve("remote-devices/hub.json"), "invalid")
    try { store.load("hub"); fail() } catch (_: IOException) { }
  }
}
