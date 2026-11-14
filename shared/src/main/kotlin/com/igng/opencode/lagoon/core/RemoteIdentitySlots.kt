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
