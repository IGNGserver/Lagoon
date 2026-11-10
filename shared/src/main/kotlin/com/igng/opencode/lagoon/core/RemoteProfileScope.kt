package com.igng.opencode.lagoon.core

/** Display/profile lookup must use the same authority scope as trust and encrypted connections. */
internal fun ServerProfile.matchesRemoteHost(origin: String, account: String, hostId: String): Boolean =
  kind == ServerKind.DSH_REMOTE && remoteHostId == hostId && username.trim() == account.trim() &&
    runCatching { canonicalRemoteOrigin(url) == canonicalRemoteOrigin(origin) }.getOrDefault(false)
