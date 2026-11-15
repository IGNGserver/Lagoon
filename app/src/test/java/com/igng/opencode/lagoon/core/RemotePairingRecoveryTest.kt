package com.igng.opencode.lagoon.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.bouncycastle.math.ec.rfc7748.X25519
import java.io.IOException
import java.util.Base64

class RemotePairingRecoveryTest {
  private val sessionId = "019a0000-0000-7000-8000-000000000001"
  private val privateBytes = ByteArray(32) { (it + 1).toByte() }
  private val privateKey = Base64.getUrlEncoder().withoutPadding().encodeToString(privateBytes)
  private val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also {
    X25519.scalarMultBase(privateBytes, 0, it, 0)
  })
  private val serverKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 41).toByte() })
  private val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 31).toByte() })
  private val hostKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 11).toByte() })

  private fun pending(): PendingRemotePairing {
    val identity = RemoteIdentity("019a0000-0000-7000-8000-000000000003", publicKey, privateKey)
    val device = RemotePairingDevice(identity.deviceId, "Phone", "client", "android", identity.publicKey, "1.0")
    val host = RemotePairingDevice("019a0000-0000-7000-8000-000000000002", "Computer", "host", "linux", hostKey)
    // Recovery must remain readable even after the QR itself has expired.
    val offer = RemotePairingOffer("https://custom.example/", sessionId, System.currentTimeMillis() - 600_000L, serverKey, ticket, host)
    return PendingRemotePairing(offer, identity, device, pairingClaimBody(ticket, device), false, System.currentTimeMillis() + 60_000L)
  }

  private fun store(prefs: FakeSharedPreferences) = RemotePairingRecoveryStore(
    prefs,
    { Base64.getEncoder().encodeToString(it.toByteArray()) },
    { String(Base64.getDecoder().decode(it)) },
  )

  @Test fun encryptedStoreRestoresSameIdentityAndExactClaimAfterOfferExpiry() {
    val prefs = FakeSharedPreferences()
    prefs.values["an-existing-identity"] = "untouched"
    val original = pending()
    // Whitespace is significant to the proof; preserve the original bytes across recreation.
    val frozen = original.copy(claimBody = " \n" + original.claimBody + "\n")
    store(prefs).save(frozen)
    val persisted = prefs.getString(RemotePairingRecoveryStore.KEY, null)!!
    assertFalse(persisted.contains(privateKey))
    assertFalse(persisted.contains(ticket))
    assertFalse(frozen.toString().contains(privateKey))
    assertFalse(frozen.toString().contains(ticket))

    val restored = store(prefs).load()!!
    assertEquals(frozen.offer, restored.offer)
    assertEquals(frozen.identity.deviceId, restored.identity.deviceId)
    assertEquals(frozen.identity.privateKey, restored.identity.privateKey)
    assertEquals(frozen.claimBody, restored.claimBody)
    assertEquals(frozen.recoverUntilMillis, restored.recoverUntilMillis)
    assertEquals("untouched", prefs.values["an-existing-identity"])
    assertFalse(AndroidRemoteDeviceStore(prefs, { it }, { it }).listSlots().contains(RemotePairingRecoveryStore.KEY))
  }

  @Test fun corruptedOrDifferentRecoveryCannotReplaceExistingRecord() {
    val prefs = FakeSharedPreferences()
    val recovery = store(prefs)
    val original = pending()
    recovery.save(original)
    val before = prefs.values[RemotePairingRecoveryStore.KEY]
    val another = original.copy(offer = original.offer.copy(sessionId = "019a0000-0000-7000-8000-000000000099"))
    assertTrue(runCatching { recovery.save(another) }.exceptionOrNull() is IOException)
    recovery.clear(another.offer.sessionId)
    assertEquals(before, prefs.values[RemotePairingRecoveryStore.KEY])
    prefs.values[RemotePairingRecoveryStore.KEY] = "not valid ciphertext"
    assertTrue(runCatching { recovery.load() }.exceptionOrNull() is IOException)
    assertTrue(runCatching { recovery.save(original) }.exceptionOrNull() is IOException)
    assertEquals("not valid ciphertext", prefs.values[RemotePairingRecoveryStore.KEY])
  }

  @Test fun malformedClaimOrMismatchedPrivateKeyCannotBeSaved() {
    val original = pending()
    val recovery = store(FakeSharedPreferences())
    assertTrue(runCatching { recovery.save(original.copy(claimBody = "{}")) }.isFailure)
    val otherIdentity = RemoteIdentity(original.identity.deviceId, original.identity.publicKey, serverKey)
    assertTrue(runCatching { recovery.save(original.copy(identity = otherIdentity)) }.isFailure)
    assertNull(recovery.load())
  }

  @Test fun serverOriginMustBeConfirmedBeforeAnyResolveRequest() = runBlocking {
    val original = pending()
    val qr = "https://custom.example/pair#v1.$ticket"
    var sends = 0
    val send: suspend (RemotePairingScan) -> RemotePairingOffer = { sends++; original.offer }
    assertTrue(runCatching { resolveConfirmedRemotePairing(qr, false, null, send) }.isFailure)
    assertTrue(runCatching { resolveConfirmedRemotePairing(qr, false, "https://other.example/", send) }.isFailure)
    assertEquals(0, sends)
    assertEquals(original.offer, resolveConfirmedRemotePairing(qr, false, "https://custom.example/", send))
    assertEquals(1, sends)
    assertTrue(runCatching {
      resolveConfirmedRemotePairing(qr.replace("https:", "http:"), false, "http://custom.example/", send)
    }.isFailure)
    assertEquals(1, sends)
  }

  @Test fun closedClaimStillQueriesCompletedStatusWithOriginalIdentity() = runBlocking {
    val original = pending()
    val prefs = FakeSharedPreferences()
    store(prefs).save(original)
    val restored = store(prefs).load()!!
    val calls = mutableListOf<String>()
    val completed = RemotePairingStatus(RemotePairingState.COMPLETED, original.recoverUntilMillis, null, null)
    val attempt = recoverRemotePairingStatus(restored, true, claim = {
      calls += "claim"
      assertEquals(original.claimBody, it.claimBody)
      assertEquals(original.identity.privateKey, it.identity.privateKey)
      throw RemotePairingException("PAIRING_CLOSED", "closed")
    }, status = {
      calls += "status"
      assertEquals(original.identity.deviceId, it.identity.deviceId)
      completed
    })
    assertEquals(listOf("claim", "status"), calls)
    assertEquals(RemotePairingState.COMPLETED, attempt.status.state)
    assertEquals("PAIRING_CLOSED", (attempt.claimError as RemotePairingException).code)
  }

  @Test fun cancellationNeverFallsThroughToStatusOrClearsRecovery() = runBlocking {
    val prefs = FakeSharedPreferences()
    val recovery = store(prefs)
    recovery.save(pending())
    var statusCalled = false
    val thrown = runCatching {
      recoverRemotePairingStatus(recovery.load()!!, true,
        claim = { throw CancellationException("paused") },
        status = { statusCalled = true; error("must not query") })
    }.exceptionOrNull()
    assertTrue(thrown is CancellationException)
    assertFalse(statusCalled)
    assertNotNull(recovery.load())
  }

  @Test fun cancelIntentSurvivesRestartAndDoesNotResendClaim() = runBlocking {
    val prefs = FakeSharedPreferences()
    val original = pending()
    store(prefs).save(original)
    store(prefs).save(original.copy(cancelRequested = true))
    val reopened = store(prefs).load()!!
    assertTrue(reopened.cancelRequested)
    assertTrue(runCatching { store(prefs).save(original) }.exceptionOrNull() is IOException)
    var claimed = false
    val attempt = recoverRemotePairingStatus(reopened, recoverCode = true,
      claim = { claimed = true; error("must not claim") },
      status = { RemotePairingStatus(RemotePairingState.COMPLETED, null, null, null) })
    assertFalse(claimed)
    assertEquals(RemotePairingState.COMPLETED, attempt.status.state)
    store(prefs).clear(sessionId)
    assertTrue(runCatching { store(prefs).save(reopened) }.exceptionOrNull() is IOException)
    assertNull(store(prefs).load())
  }
}
