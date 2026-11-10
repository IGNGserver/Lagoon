package com.igng.opencode.lagoon.core

import org.junit.Assert.*
import org.junit.Test

class RemoteProfileScopeTest {
  private val host = "f2b08ff1-afd6-46a6-8dd4-4de1f026f60a"
  private val profile = ServerProfile("p", "Host", "https://hub.example", username = "owner@example.org",
    kind = ServerKind.DSH_REMOTE, remoteHostId = host)
  private val nativeProfile = ServerProfile("native", "OpenCode", "https://hub.example",
    username = "owner@example.org", remoteHostId = host, remoteServiceId = "opencode-main")

  @Test fun sameCanonicalOriginAccountAndHostAreRequired() {
    assertTrue(profile.matchesRemoteHost("https://hub.example/", "owner@example.org", host))
    assertFalse(profile.matchesRemoteHost("https://other.example/", "owner@example.org", host))
    assertFalse(profile.matchesRemoteHost("https://hub.example/", "other@example.org", host))
    assertFalse(profile.matchesRemoteHost("https://hub.example/", "owner@example.org", "other-host"))
    assertFalse(nativeProfile.matchesRemoteHost("https://hub.example/", "owner@example.org", host))
    assertFalse(profile.copy(url = "invalid").matchesRemoteHost("https://hub.example/", "owner@example.org", host))
  }
}
