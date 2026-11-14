package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

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

  @Test fun cancelledCallerDoesNotLoseRotatedRefreshTokenForSecondCaller() = runBlocking {
    val store = TestStore()
    val slot = "cancel-slot"
    val now = 100_000L
    val identity = RemoteIdentity.generate()
    store.save(slot, RemoteDeviceRecord("https://hub.example/", "user@example.com", identity, tokens(accessExpiry = now + 1_000L)))

    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val refreshes = AtomicInteger(0)
    val rotated = RemoteTokenPair("rotated-access-123", now + 3_600_000L, "rotated-refresh-123", now + 86_400_000L)

    val m1 = RemoteDeviceManager(store, slot, "https://hub.example", "user@example.com")
    val m2 = RemoteDeviceManager(store, slot, "https://hub.example", "user@example.com")

    val caller1 = launch {
      m1.access(now = now) { _, _ ->
        refreshes.incrementAndGet()
        started.complete(Unit)
        release.await()
        rotated
      }
    }
    withTimeout(3_000) { started.await() }
    caller1.cancelAndJoin()

    val caller2 = async { m2.access(now = now) { _, _ -> fail("should join in-flight refresh"); rotated } }
    release.complete(Unit)
    val recovered = withTimeout(3_000) { caller2.await() }

    assertEquals(1, refreshes.get())
    assertEquals("rotated-access-123", recovered.tokens?.accessToken)
    assertEquals("rotated-refresh-123", store.load(slot)?.tokens?.refreshToken)
  }

  @Test fun fiveManagersDeduplicateToSingleflightRefresh() = runBlocking {
    val store = TestStore()
    val slot = "singleflight-5"
    val now = System.currentTimeMillis()
    store.save(slot, RemoteDeviceRecord("https://hub.example/", "user@example.com", RemoteIdentity.generate(),
      RemoteTokenPair("old-access-123456", now + 1_000L, "old-refresh-123456", now + 86_400_000L)))

    val refreshes = AtomicInteger(0)
    val next = RemoteTokenPair("singleflight-access-999", now + 3_600_000L, "singleflight-refresh-999", now + 86_400_000L)
    val managers = (1..5).map {
      RemoteDeviceManager(store, slot, "https://hub.example", "user@example.com", refresher = { _, _ ->
        refreshes.incrementAndGet()
        delay(40)
        next
      })
    }
    val results = managers.map { async { it.access(now = now) } }.awaitAll()
    assertEquals(1, refreshes.get())
    assertTrue(results.all { it.tokens?.accessToken == "singleflight-access-999" })
  }

  @Test fun invalidateSlotPreventsInFlightRefreshFromResurrectingDeletedSlot() = runBlocking {
    val store = TestStore()
    val slot = "invalidate-slot"
    val now = System.currentTimeMillis()
    store.save(slot, RemoteDeviceRecord("https://hub.example/", "user@example.com", RemoteIdentity.generate(),
      RemoteTokenPair("old-access-123456", now + 1_000L, "old-refresh-123456", now + 86_400_000L)))

    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val manager = RemoteDeviceManager(store, slot, "https://hub.example", "user@example.com")
    val job = async {
      runCatching {
        manager.access(now = now) { _, _ ->
          started.complete(Unit)
          release.await()
          RemoteTokenPair("zombie-access-123", now + 3_600_000L, "zombie-refresh-123", now + 86_400_000L)
        }
      }
    }
    withTimeout(3_000) { started.await() }
    RemoteDeviceManager.invalidateSlot(slot)
    store.delete(slot)
    release.complete(Unit)
    val result = withTimeout(3_000) { job.await() }
    assertTrue(result.isFailure)
    assertNull(store.load(slot))
  }

  @Test fun expiredRecoveredAccessTokenPerformsSecondHopRotation() = runBlocking {
    val store = TestStore()
    val slot = "double-hop"
    val now = System.currentTimeMillis()
    store.save(slot, RemoteDeviceRecord("https://hub.example/", "user@example.com", RemoteIdentity.generate(),
      RemoteTokenPair("expired-access-123456", now - 10_000L, "refresh-hop-0-123456", now + 86_400_000L)))

    val seenRefreshTokens = mutableListOf<String>()
    val manager = RemoteDeviceManager(store, slot, "https://hub.example", "user@example.com")
    val resolved = manager.access(now = now) { _, presentedRefresh ->
      seenRefreshTokens += presentedRefresh
      when (seenRefreshTokens.size) {
        1 -> RemoteTokenPair("recovered-expired-123", now - 1_000L, "refresh-hop-1-123456", now + 86_400_000L)
        2 -> RemoteTokenPair("fresh-hop2-access-123", now + 3_600_000L, "refresh-hop-2-123456", now + 86_400_000L)
        else -> throw AssertionError("unexpected 3rd refresh")
      }
    }
    assertEquals(listOf("refresh-hop-0-123456", "refresh-hop-1-123456"), seenRefreshTokens)
    assertEquals("fresh-hop2-access-123", resolved.tokens?.accessToken)
    assertEquals("refresh-hop-2-123456", store.load(slot)?.tokens?.refreshToken)
  }

  @Test fun refreshAfterRejectedDeduplicatesAlreadyRotatedToken() = runBlocking {
    val store = TestStore()
    val slot = "reactive-slot"
    val now = System.currentTimeMillis()
    store.save(slot, RemoteDeviceRecord("https://hub.example/", "user@example.com", RemoteIdentity.generate(),
      RemoteTokenPair("rejected-access-123", now + 3_600_000L, "reactive-refresh-123", now + 86_400_000L)))

    val refreshes = AtomicInteger(0)
    val rotated = RemoteTokenPair("rotated-access-777", now + 3_600_000L, "rotated-refresh-777", now + 86_400_000L)
    val m1 = RemoteDeviceManager(store, slot, "https://hub.example", "user@example.com", refresher = { _, _ ->
      refreshes.incrementAndGet()
      delay(30)
      rotated
    })
    val m2 = RemoteDeviceManager(store, slot, "https://hub.example", "user@example.com", refresher = { _, _ ->
      refreshes.incrementAndGet()
      rotated
    })

    val (r1, r2) = listOf(m1, m2).map { async { it.refreshAfterRejected("rejected-access-123", now = now) } }.awaitAll()
    val r3 = m2.refreshAfterRejected("rejected-access-123", now = now)
    assertEquals(1, refreshes.get())
    assertEquals("rotated-access-777", r1.tokens?.accessToken)
    assertEquals("rotated-access-777", r2.tokens?.accessToken)
    assertEquals("rotated-access-777", r3.tokens?.accessToken)
  }

  @Test fun registerRotatesIdentityOnceOnDeviceRevokedOrConflict() = runBlocking {
    for ((status, code) in listOf(403 to "DEVICE_REVOKED", 409 to "PEER_IDENTITY_MISMATCH")) {
      val store = TestStore()
      val slot = "revoked-slot-$status"
      val initialIdentity = RemoteIdentity.generate()
      store.save(slot, RemoteDeviceRecord("https://hub.example/", "user@example.com", initialIdentity, tokens()))

      val seenDeviceIds = mutableListOf<String>()
      val freshPair = tokens(accessExpiry = System.currentTimeMillis() + 3_600_000L)
      val manager = RemoteDeviceManager(store, slot, "https://hub.example", "user@example.com")
      val saved = manager.register("Lagoon", "android", "1.0", "account-token-123") { descriptor, _ ->
        seenDeviceIds += descriptor.deviceId
        if (seenDeviceIds.size == 1) throw RemoteServerException(status, code, false)
        freshPair
      }
      assertEquals(2, seenDeviceIds.size)
      assertEquals(initialIdentity.deviceId, seenDeviceIds[0])
      assertNotEquals(initialIdentity.deviceId, saved.identity.deviceId)
      assertFalse(saved.invalidated)
      assertEquals(saved.identity.deviceId, store.load(slot)?.identity?.deviceId)
    }
  }

  @Test fun definitiveCredentialRejectionMarksInvalidatedAndStopsStorm() = runBlocking {
    val store = TestStore()
    val slot = "invalidated-storm"
    val now = System.currentTimeMillis()
    store.save(slot, RemoteDeviceRecord("https://hub.example/", "user@example.com", RemoteIdentity.generate(),
      RemoteTokenPair("old-access-123456", now + 1_000L, "old-refresh-123456", now + 86_400_000L)))

    val calls = AtomicInteger(0)
    val manager = RemoteDeviceManager(store, slot, "https://hub.example", "user@example.com")
    val firstErr = runCatching {
      manager.access(now = now) { _, _ ->
        calls.incrementAndGet()
        throw RemoteServerException(401, "TOKEN_REUSED", false)
      }
    }.exceptionOrNull()
    assertTrue(firstErr is IOException)
    assertTrue(store.load(slot)?.invalidated == true)

    val secondErr = runCatching {
      manager.access(now = now) { _, _ ->
        calls.incrementAndGet()
        tokens()
      }
    }.exceptionOrNull()
    assertTrue(secondErr is IOException)
    assertEquals(1, calls.get())
  }
}
