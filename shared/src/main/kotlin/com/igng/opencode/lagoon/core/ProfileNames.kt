package com.igng.opencode.lagoon.core

/**
 * Display-name rules for saved profiles.
 *
 * Devices and Harnesses are rendered in separate lists, so a device name never competes with an
 * OpenCode/Harness name. Only a second *device* with the same name needs a ` (2)` suffix. Older
 * builds compared every saved profile instead, which is why an OpenCode "ubuntu-dev" harness could
 * force a DSH Remote host to be stored as "ubuntu-dev (2)".
 */
internal object ProfileNames {
  private val generatedSuffix = Regex("""^(.+) \((\d+)\)$""")

  /** A Host name, disambiguated only against other devices (never Harnesses). */
  fun uniqueDeviceName(hostName: String, profiles: List<ServerProfile>): String {
    val base = hostName.ifBlank { "远程主机" }
    val taken = profiles.asSequence().filter { it.deviceMemberRole == DeviceMemberRole.DEVICE }
      .mapTo(HashSet()) { it.name }
    if (base !in taken) return base
    var suffix = 2
    while ("$base ($suffix)" in taken) suffix++
    return "$base ($suffix)"
  }

  /**
   * Removes a suffix that only existed because of the old all-profile comparison. The base name
   * must equal the Hub's current name for this Host and no other device may own it, so a name the
   * user typed themselves is never rewritten. Catalogs are authoritative for Host names; without
   * one the profile is left untouched.
   */
  fun repairGeneratedSuffixes(profiles: List<ServerProfile>, catalogs: List<RemoteCatalog>): List<ServerProfile> {
    if (profiles.isEmpty() || catalogs.isEmpty()) return profiles
    val repaired = profiles.toMutableList()
    for (index in profiles.indices) {
      val profile = profiles[index]
      val base = generatedSuffix.matchEntire(profile.name)?.groupValues?.get(1) ?: continue
      if (base != hostName(profile, catalogs)) continue
      val occupied = repaired.withIndex().any { (otherIndex, candidate) ->
        otherIndex != index && candidate.deviceMemberRole == DeviceMemberRole.DEVICE && candidate.name == base
      }
      if (!occupied) repaired[index] = profile.copy(name = base)
    }
    return repaired
  }

  /** Repairs an already-saved profile during re-enrollment; null means its name should stay as is. */
  fun adoptHostName(profile: ServerProfile, hostName: String, profiles: List<ServerProfile>): ServerProfile? {
    if (profile.kind != ServerKind.DSH_REMOTE) return null
    val base = generatedSuffix.matchEntire(profile.name)?.groupValues?.get(1) ?: return null
    if (base != hostName) return null
    val occupied = profiles.any {
      it.id != profile.id && it.deviceMemberRole == DeviceMemberRole.DEVICE && it.name == base
    }
    return if (occupied) null else profile.copy(name = base)
  }

  private fun hostName(profile: ServerProfile, catalogs: List<RemoteCatalog>): String? {
    if (profile.kind != ServerKind.DSH_REMOTE) return null
    val hostId = profile.remoteHostId ?: return null
    val origin = runCatching { canonicalRemoteOrigin(profile.url) }.getOrNull() ?: return null
    val catalog = catalogs.firstOrNull { canonicalRemoteOrigin(it.origin) == origin && it.account == profile.username }
      ?: catalogs.firstOrNull { canonicalRemoteOrigin(it.origin) == origin }
      ?: return null
    return catalog.hosts.firstOrNull { it.hostId == hostId }?.name
  }
}
