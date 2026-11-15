package com.igng.opencode.lagoon.core

import java.security.MessageDigest

/**
 * Storage slots for Remote client identities.
 *
 * New slots include the Hub origin, account and Host ID, so the same Host ID on two
 * accounts can never alias one local identity. Existing host-stable and profile slots
 * are still selected when their record belongs to the requested scope.
 */
internal object RemoteIdentitySlots {
  fun scoped(origin: String, account: String, hostId: String): String {
    val canonicalOrigin = canonicalRemoteOrigin(origin)
    val normalizedAccount = account.trim().also {
      require(it.isNotBlank() && it.length <= 320 && '\u0000' !in it) { "Remote 账号无效" }
    }
    require(RemoteServerApi.validDeviceId(hostId)) { "Remote Host ID 无效" }
    val input = "$canonicalOrigin\u0000$normalizedAccount\u0000$hostId".toByteArray(Charsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(input)
      .joinToString("") { "%02x".format(it) }
    return "host-$digest"
  }

  /**
   * Resolve an identity slot without swallowing corrupt records. The scoped slot wins;
   * then an old host-stable slot, followed by explicitly supplied legacy profile slots,
   * but only when the stored record is for the same origin and account.
   */
  fun resolve(
    store: RemoteDeviceStore,
    origin: String,
    account: String,
    hostId: String,
    legacyProfileSlots: List<String> = emptyList()
  ): String {
    val canonicalOrigin = canonicalRemoteOrigin(origin)
    val normalizedAccount = account.trim()
    val scoped = scoped(canonicalOrigin, normalizedAccount, hostId)
    val candidates = buildList {
      add(scoped)
      add(legacyHostSlot(hostId))
      addAll(legacyProfileSlots)
    }.distinct()
    for (slot in candidates) {
      val record = store.load(slot) ?: continue
      if (record.origin == canonicalOrigin && record.account == normalizedAccount) return slot
      if (slot == scoped) throw IllegalStateException("Remote 设备身份槽位属于其他 Hub 或账号")
    }
    return scoped
  }

  /** Null preserves the uncertainty of identities written before the enrollment-method field existed. */
  fun pairingStatus(store: RemoteDeviceStore, origin: String, account: String, hostId: String): Boolean? {
    val record = store.load(resolve(store, origin, account, hostId)) ?: return null
    return when (record.enrollmentMethod) {
      "pairing" -> true
      "account" -> false
      else -> if (record.accountKind == "device_only") true else null
    }
  }

  /** Slot used by versions before origin/account scoping was introduced. */
  fun legacyHostSlot(hostId: String): String =
    "host-${hostId.replace(Regex("[^A-Za-z0-9_-]"), "_")}"

  fun candidateSlots(
    origin: String,
    account: String,
    hostId: String,
    legacyProfileSlots: List<String> = emptyList()
  ): List<String> {
    val canonical = canonicalRemoteOrigin(origin)
    return (listOf(scoped(canonical, account.trim(), hostId), legacyHostSlot(hostId)) + legacyProfileSlots).distinct()
  }

  /**
   * Persist a revocation before deleting each matching slot. Re-entering after a crash must not
   * overwrite a queued token that was already rotated by an earlier drain.
   */
  fun queueAndWipeHostSlots(
    store: RemoteDeviceStore,
    origin: String,
    account: String,
    hostId: String,
    allowCleartext: Boolean,
    legacyProfileSlots: List<String> = emptyList()
  ): Int {
    val canonical = canonicalRemoteOrigin(origin)
    val normalizedAccount = account.trim()
    var wiped = 0
    val targetSlots = candidateSlots(canonical, normalizedAccount, hostId, legacyProfileSlots).toSet()
    for (slot in targetSlots) {
      val record = store.load(slot) ?: continue
      if (record.origin != canonical || record.account.trim() != normalizedAccount) continue
      val shared = isIdentityUsedOutside(store, record, targetSlots)
      if (queueAndWipeSlot(store, slot, canonical, normalizedAccount, hostId, allowCleartext, shared)) wiped++
    }
    return wiped
  }

  private fun isIdentityUsedOutside(store: RemoteDeviceStore, record: RemoteDeviceRecord, excludedSlots: Set<String>): Boolean =
    store.listSlots().asSequence().filterNot { it in excludedSlots }.any { slot ->
      val other = store.load(slot) ?: return@any false
      other.origin == record.origin && other.account == record.account && other.identity.deviceId == record.identity.deviceId
    }

  /** Queue an old identity before replacing it; preserve a legacy identity shared by other Hosts. */
  fun queueReplacedIdentity(store: RemoteDeviceStore, slot: String, hostId: String,
    record: RemoteDeviceRecord, allowCleartext: Boolean) {
    enqueueRecord(store, record, hostId, allowCleartext, isIdentityUsedOutside(store, record, setOf(slot)))
  }

  private fun enqueueRecord(store: RemoteDeviceStore, record: RemoteDeviceRecord, peerHostId: String,
    allowCleartext: Boolean, membershipOnly: Boolean) {
    val tokens = record.tokens ?: return
    if (membershipOnly && !RemoteServerApi.validDeviceId(peerHostId)) throw java.io.IOException("共享设备缺少主机授权关系")
    val alreadyQueued = store.pendingRevocations().any {
      it.origin == record.origin && it.account == record.account.trim() && it.clientDeviceId == record.identity.deviceId &&
        it.membershipOnly == membershipOnly && (!membershipOnly || it.peerHostId == peerHostId)
    }
    if (!alreadyQueued) store.enqueueRevocation(PendingRemoteRevocation(
      id = java.util.UUID.randomUUID().toString(), origin = record.origin, account = record.account.trim(),
      peerHostId = peerHostId.ifBlank { "unknown" }, clientDeviceId = record.identity.deviceId,
      accessToken = tokens.accessToken, refreshToken = tokens.refreshToken, allowCleartext = allowCleartext,
      membershipOnly = membershipOnly
    ))
  }

  fun queueAndWipeSlot(
    store: RemoteDeviceStore,
    slot: String,
    origin: String,
    account: String,
    peerHostId: String,
    allowCleartext: Boolean,
    membershipOnly: Boolean = false
  ): Boolean {
    val canonical = canonicalRemoteOrigin(origin)
    val normalizedAccount = account.trim()
    val before = store.load(slot) ?: return false
    if (before.origin != canonical || before.account.trim() != normalizedAccount) return false
    // Prevent an older in-flight refresh from repopulating the identity after it has been removed.
    // Do not invalidate unrelated accounts encountered by the legacy-slot scan.
    RemoteDeviceManager.invalidateSlot(slot)
    val record = store.load(slot) ?: return false
    if (record.origin != canonical || record.account.trim() != normalizedAccount) return false
    enqueueRecord(store, record, peerHostId, allowCleartext, membershipOnly)
    // If enqueue fails, the slot remains intact and the caller must not report successful removal.
    store.delete(slot)
    return true
  }

}

/** Earliest safe retry; negative clock jumps retry immediately rather than parking a task forever. */
internal fun remoteRevocationRetryDelay(item: PendingRemoteRevocation, now: Long): Long {
  if (item.attempts <= 0 || item.lastAttemptAt <= 0L || now < item.lastAttemptAt) return 0L
  val interval = (1_000L * (1L shl item.attempts.coerceIn(0, 12))).coerceAtMost(3_600_000L)
  return (interval - (now - item.lastAttemptAt)).coerceAtLeast(0L)
}

internal suspend fun drainPendingRemoteRevocations(
  store: RemoteDeviceStore,
  nowMillis: () -> Long = { System.currentTimeMillis() },
  apiFactory: (origin: String, allowCleartext: Boolean) -> RemoteServerApi = { origin, allowCleartext ->
    RemoteServerApi(origin, allowCleartext)
  }
) {
  for (item in store.pendingRevocations()) {
    val now = nowMillis()
    if (remoteRevocationRetryDelay(item, now) > 0L) continue
    var current = item
    try {
      val api = apiFactory(current.origin, current.allowCleartext)
      var refreshed = false
      // A membership-only revocation may share the phone identity with a surviving Host. Refresh
      // through that slot's single-flight manager so rotating its token cannot strand the sibling.
      val sharedSlot = if (current.membershipOnly) store.listSlots().firstOrNull { slot ->
        val record = store.load(slot)
        record != null && record.origin == current.origin && record.account == current.account &&
          record.identity.deviceId == current.clientDeviceId
      } else null
      val sharedManager = sharedSlot?.let { slot -> RemoteDeviceManager(store, slot, current.origin, current.account,
        refresher = { id, token -> api.refresh(id, token) }) }
      if (sharedManager != null) {
        val live = sharedManager.access().tokens ?: throw java.io.IOException("共享设备凭据尚未就绪")
        if (live.accessToken != current.accessToken || live.refreshToken != current.refreshToken) {
          current = current.copy(accessToken = live.accessToken, refreshToken = live.refreshToken)
          store.updateRevocation(current)
        }
      }

      suspend fun refreshRejectedToken(): String {
        val next = if (sharedManager != null) sharedManager.refreshAfterRejected(current.accessToken).tokens
          ?: throw java.io.IOException("共享设备凭据尚未就绪")
        else api.refresh(current.clientDeviceId, current.refreshToken)
        refreshed = true
        // Keep the newest pair even if a later membership/self request fails.
        current = current.copy(accessToken = next.accessToken, refreshToken = next.refreshToken)
        store.updateRevocation(current)
        return current.accessToken
      }

      if (current.membershipOnly && !RemoteServerApi.validDeviceId(current.peerHostId))
        throw java.io.IOException("共享设备缺少主机授权关系")
      if (RemoteServerApi.validDeviceId(current.peerHostId)) {
        try {
          api.deleteMembership(current.accessToken, current.peerHostId)
        } catch (error: RemoteServerException) {
          when {
            error.isDeviceRevoked -> { store.removeRevocation(current.id); continue }
            error.isRecoverableTokenRejection && !refreshed -> {
              val fresh = refreshRejectedToken()
              try { api.deleteMembership(fresh, current.peerHostId) }
              catch (retry: RemoteServerException) {
                if (retry.isDeviceRevoked) { store.removeRevocation(current.id); continue }
                if (current.membershipOnly && retry.code != "MEMBERSHIP_REQUIRED") throw retry
                if (retry.status == 401 || retry.retryable || retry.status !in 400..499) throw retry
                // Self-revocation can still settle an unsupported or already absent membership.
              }
            }
            current.membershipOnly && error.code != "MEMBERSHIP_REQUIRED" -> throw error
            error.status == 401 || error.retryable || error.status !in 400..499 -> throw error
            // Self-revocation can still settle an unsupported or already absent membership.
          }
        }
      }

      if (current.membershipOnly) {
        store.removeRevocation(current.id)
        continue
      }
      try {
        api.revokeSelf(current.accessToken)
      } catch (error: RemoteServerException) {
        when {
          error.isDeviceRevoked -> { store.removeRevocation(current.id); continue }
          error.isRecoverableTokenRejection && !refreshed -> {
            val fresh = refreshRejectedToken()
            try { api.revokeSelf(fresh) }
            catch (retry: RemoteServerException) {
              if (!retry.isDeviceRevoked) throw retry
            }
          }
          else -> throw error
        }
      }
      store.removeRevocation(current.id)
    } catch (cancel: kotlin.coroutines.cancellation.CancellationException) {
      throw cancel
    } catch (error: Exception) {
      // Do not erase a task on unknown 401/403, exhausted retries or age. It remains visible and
      // bounded by the queue capacity so the user can settle it at the server if needed.
      val classification = (error as? RemoteServerException)?.let { it.code ?: "HTTP_${it.status}" } ?: "NETWORK"
      store.updateRevocation(current.copy(attempts = (current.attempts.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        lastAttemptAt = nowMillis(), lastError = classification))
    }
  }
}

internal fun scopedRemoteDeviceSlot(origin: String, account: String, hostId: String): String =
  RemoteIdentitySlots.scoped(origin, account, hostId)

internal fun resolveRemoteDeviceSlot(
  store: RemoteDeviceStore,
  origin: String,
  account: String,
  hostId: String,
  legacyProfileSlots: List<String> = emptyList()
): String = RemoteIdentitySlots.resolve(store, origin, account, hostId, legacyProfileSlots)
