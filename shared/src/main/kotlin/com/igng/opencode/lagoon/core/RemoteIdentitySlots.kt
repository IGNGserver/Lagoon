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
   * Collects every non-invalidated slot record belonging to `(origin, account, hostId)` BEFORE
   * local wipe, and deletes each slot belonging exclusively to that scope + invalidates memory state.
   */
  fun wipeHostSlotsReturningTokens(
    store: RemoteDeviceStore,
    origin: String,
    account: String,
    hostId: String,
    legacyProfileSlots: List<String> = emptyList()
  ): List<RemoteDeviceRecord> {
    val canonical = canonicalRemoteOrigin(origin)
    val normAccount = account.trim()
    val scopedSlot = scoped(canonical, normAccount, hostId)
    val seenDeviceIds = mutableSetOf<String>()
    val records = mutableListOf<RemoteDeviceRecord>()
    for (slot in candidateSlots(canonical, normAccount, hostId, legacyProfileSlots)) {
      val loaded = runCatching { store.load(slot) }.getOrNull()
      val belongsToScope = loaded == null || (loaded.origin == canonical && loaded.account.trim() == normAccount)
      if (!belongsToScope && slot != scopedSlot) continue
      if (loaded != null && loaded.tokens != null && !loaded.invalidated && seenDeviceIds.add(loaded.identity.deviceId)) {
        records += loaded
      }
      RemoteDeviceManager.invalidateSlot(slot)
      runCatching { store.delete(slot) }
    }
    return records
  }
}

internal suspend fun drainPendingRemoteRevocations(
  store: RemoteDeviceStore,
  nowMillis: () -> Long = { System.currentTimeMillis() },
  apiFactory: (origin: String, allowCleartext: Boolean) -> RemoteServerApi = { origin, allowCleartext ->
    RemoteServerApi(origin, allowCleartext)
  }
) {
  val items = store.pendingRevocations()
  if (items.isEmpty()) return
  val maxAgeMillis = 30L * 86_400_000L
  val maxAttempts = 12
  for (item in items) {
    val now = nowMillis()
    if (item.attempts >= maxAttempts || (item.createdAt > 0L && now - item.createdAt > maxAgeMillis)) {
      store.removeRevocation(item.id)
      continue
    }
    try {
      val api = apiFactory(item.origin, item.allowCleartext)
      var currentToken = item.accessToken
      var currentRefresh = item.refreshToken
      var refreshed = false

      suspend fun ensureFreshToken(): String {
        val next = api.refresh(item.clientDeviceId, currentRefresh)
        refreshed = true
        currentToken = next.accessToken
        currentRefresh = next.refreshToken
        store.updateRevocation(item.copy(accessToken = currentToken, refreshToken = currentRefresh))
        return currentToken
      }

      if (item.peerHostId.isNotBlank() && RemoteServerApi.validDeviceId(item.peerHostId)) {
        try {
          api.deleteMembership(currentToken, item.peerHostId)
        } catch (error: RemoteServerException) {
          when {
            error.isDeviceRevoked -> {
              store.removeRevocation(item.id)
              continue
            }
            error.isRecoverableTokenRejection && !refreshed -> {
              val fresh = ensureFreshToken()
              try {
                api.deleteMembership(fresh, item.peerHostId)
              } catch (retryErr: RemoteServerException) {
                if (retryErr.isDeviceRevoked || retryErr.status == 404) {
                  store.removeRevocation(item.id)
                  continue
                }
                if (retryErr.retryable || retryErr.status !in 400..499 || retryErr.status == 429) {
                  throw retryErr
                }
              }
            }
            error.retryable -> throw error
            // 403/400 on membership endpoint should still allow revokeSelf.
          }
        }
      }

      try {
        api.revokeSelf(currentToken)
        store.removeRevocation(item.id)
      } catch (error: RemoteServerException) {
        when {
          error.isDeviceRevoked || error.status == 404 -> store.removeRevocation(item.id)
          error.isRecoverableTokenRejection && !refreshed -> {
            val fresh = ensureFreshToken()
            try {
              api.revokeSelf(fresh)
              store.removeRevocation(item.id)
            } catch (retryErr: RemoteServerException) {
              if (retryErr.isDeviceRevoked || retryErr.status == 404 || (!retryErr.retryable && retryErr.status in 400..499)) {
                store.removeRevocation(item.id)
              } else {
                throw retryErr
              }
            }
          }
          !error.retryable && error.status in 400..499 -> store.removeRevocation(item.id)
          else -> throw error
        }
      }
    } catch (cancel: kotlin.coroutines.cancellation.CancellationException) {
      throw cancel
    } catch (error: RemoteServerException) {
      if (!error.retryable && error.status in 400..499) {
        store.removeRevocation(item.id)
      } else {
        store.updateRevocation(item.copy(attempts = item.attempts + 1, lastAttemptAt = nowMillis()))
      }
    } catch (_: Exception) {
      store.updateRevocation(item.copy(attempts = item.attempts + 1, lastAttemptAt = nowMillis()))
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
