package com.igng.opencode.lagoon.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RemoteDeviceStoreTest {
  /** In-memory equivalent of the platform store: the codec and manager semantics are what matter here. */
  private class TestStore : RemoteDeviceStore {
    val values = mutableMapOf<String, String>()
    override fun load(slot: String): RemoteDeviceRecord? = values[slot]?.let(RemoteDeviceCodec::decode)
    override fun save(slot: String, record: RemoteDeviceRecord) { values[slot] = RemoteDeviceCodec.encode(record) }
    override fun delete(slot: String) { values.remove(slot) }
  }

  private fun tokens(accessExpiry: Long = 200_000) = RemoteTokenPair("device-access-token-123", accessExpiry, "device-refresh-token-123", 900_000)

  @Test fun serializesRefreshAcrossManagersAndPersistsIdentity() = runBlocking {
    val store = TestStore()
    val first = RemoteDeviceManager(store, "phone", "https://hub.example", "user@example.com",
      { _, _ -> tokens(100_000) }, { _, _ -> tokens(300_000) })
    val second = RemoteDeviceManager(store, "phone", "https://hub.example/", "user@example.com",
      { _, _ -> tokens(100_000) }, { _, _ -> tokens(300_000) })
    val registerer: suspend (RemoteClientDescriptor, String) -> RemoteTokenPair = { _, _ -> tokens(100_000) }
    val registered = first.register("Lagoon", "jvm", "1.0", "account-token-123", registerer)
    val stored = store.load("phone")!!
    assertEquals(registered.identity.privateKey, stored.identity.privateKey)
    assertEquals(registered.tokens!!.refreshToken, stored.tokens!!.refreshToken)
    var refreshes = 0
    val results = listOf(first, second).map { manager -> async {
      manager.access(90_000) { _, _ -> refreshes++; delay(30); tokens(300_000) }
    } }.awaitAll()
    assertEquals(1, refreshes)
    assertEquals(results[0].identity.deviceId, results[1].identity.deviceId)
    assertEquals(RemoteDeviceRecord.Transition.NONE, store.load("phone")!!.transition)
  }

  @Test fun unknownRefreshResultNeverReplaysAndRequiresReauthentication() = runBlocking {
    val store = TestStore()
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
    val store = TestStore()
    store.values["hub"] = "invalid"
    try { store.load("hub"); fail() } catch (_: IOException) { }
  }

  private fun seeded(store: TestStore, transition: RemoteDeviceRecord.Transition = RemoteDeviceRecord.Transition.NONE,
    transitionAt: Long = 0): RemoteDeviceRecord {
    val identity = RemoteIdentity.generate()
    val record = RemoteDeviceRecord(canonicalRemoteOrigin("https://hub.example"), "user@example.com",
      identity, tokens(accessExpiry = 100_000), transition, transitionAt)
    store.save("phone", record)
    return record
  }

  /** 进程被杀留下的 REFRESHING 不能在重启后永久卡死：超过阈值就重试刷新。 */
  @Test fun staleRefreshingRecoversOnNextAccess() = runBlocking {
    val store = TestStore()
    seeded(store, RemoteDeviceRecord.Transition.REFRESHING, transitionAt = 1_000)
    var refreshes = 0
    val manager = RemoteDeviceManager(store, "phone", "https://hub.example", "user@example.com")
    val record = manager.access(now = 200_000) { _, _ -> refreshes++; tokens(300_000) }
    assertEquals(1, refreshes)
    assertEquals(300_000, record.tokens!!.accessTokenExpiresAt)
    assertEquals(RemoteDeviceRecord.Transition.NONE, store.load("phone")!!.transition)
  }

  /** 正在进行中的刷新不并发重试，但必须是“稍后重试”而不是永久失败。 */
  @Test fun freshRefreshingIsNotRetriedConcurrently() = runBlocking {
    val store = TestStore()
    seeded(store, RemoteDeviceRecord.Transition.REFRESHING, transitionAt = 195_000)
    var refreshes = 0
    val manager = RemoteDeviceManager(store, "phone", "https://hub.example", "user@example.com")
    val error = runCatching { manager.access(now = 200_000) { _, _ -> refreshes++; tokens(300_000) } }.exceptionOrNull()
    assertTrue(error is IOException)
    assertTrue(error!!.message!!.contains("稍后重试"))
    assertEquals(0, refreshes)
  }

  /** 刷新失败保留“结果未知”标记与旧令牌，但标记带时间戳，冷却后自动重试而不是永久卡死。 */
  @Test fun failedRefreshKeepsUnknownStateThenRecovers() = runBlocking {
    val store = TestStore()
    seeded(store)
    val manager = RemoteDeviceManager(store, "phone", "https://hub.example", "user@example.com")
    val failure = runCatching { manager.access(now = 200_000) { _, _ -> throw IOException("network down") } }.exceptionOrNull()
    assertTrue(failure is IOException)
    val stored = store.load("phone")!!
    assertEquals(RemoteDeviceRecord.Transition.REFRESHING, stored.transition)
    assertEquals("device-refresh-token-123", stored.tokens!!.refreshToken)
    assertTrue(stored.transitionAt > 0)
    var refreshes = 0
    val recovered = manager.access(now = 200_000 + 120_000) { _, _ -> refreshes++; tokens(300_000) }
    assertEquals(1, refreshes)
    assertEquals(RemoteDeviceRecord.Transition.NONE, recovered.transition)
    assertEquals(300_000, recovered.tokens!!.accessTokenExpiresAt)
  }

  /** 服务端明确拒绝才提示重新登录。 */
  @Test fun rejectedRefreshAsksForLogin() = runBlocking {
    val store = TestStore()
    seeded(store)
    val manager = RemoteDeviceManager(store, "phone", "https://hub.example", "user@example.com")
    val failure = runCatching { manager.access(now = 200_000) { _, _ -> throw RemoteServerException(401, "AUTH_INVALID", false) } }.exceptionOrNull()
    assertTrue(failure is IOException)
    assertTrue(failure!!.message!!.contains("重新登录"))
    assertEquals(RemoteDeviceRecord.Transition.NONE, store.load("phone")!!.transition)
  }

  @Test fun unstructuredAuthorizationFailureKeepsUnknownRefreshAndCredentials() = runBlocking {
    for (status in listOf(401, 403)) {
      val store = TestStore()
      val before = seeded(store)
      val manager = RemoteDeviceManager(store, "phone", "https://hub.example", "user@example.com")
      val failure = runCatching { manager.access(now = 200_000) { _, _ ->
        throw RemoteServerException(status, null, true)
      } }.exceptionOrNull()
      assertTrue(failure is IOException)
      assertFalse(failure!!.message.orEmpty().contains("重新登录"))
      assertEquals(RemoteDeviceRecord.Transition.REFRESHING, store.load("phone")!!.transition)
      assertEquals(before.tokens, store.load("phone")!!.tokens)
    }
  }

  /** 注册失败同样恢复记录：同一 identity 重新注册是幂等的。 */
  @Test fun failedRegistrationRestoresRecord() = runBlocking {
    val store = TestStore()
    val manager = RemoteDeviceManager(store, "phone", "https://hub.example", "user@example.com")
    runCatching { manager.register("Lagoon", "jvm", "1.0", "account-token-123") { _, _ -> throw IOException("offline") } }
    val stored = store.load("phone")
    assertTrue(stored == null || stored.transition == RemoteDeviceRecord.Transition.NONE)
  }
}
