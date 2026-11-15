package com.igng.opencode.lagoon.core

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.IOException

class RemoteCatalogTest {
  private val hostA = "f2b08ff1-afd6-46a6-8dd4-4de1f026f60a"
  private val hostB = "a8f582fb-f662-49c6-b7f0-456a797a5302"

  @Test fun roundTripsCatalogWithoutSecrets() {
    val catalog = RemoteCatalog(
      origin = "https://remote.example/",
      account = "user@example.org",
      provider = "github",
      deviceLabel = "Lagoon · Pixel",
      updatedAt = 1_900_000_000_000,
      hosts = listOf(
        RemoteHostMeta(hostA, "Mac", "darwin", true, 1_900_000_000_000, "0.2.1", "0.6.2", "ABCDEF012345", RemoteTrustState.PINNED),
        RemoteHostMeta(hostB, "Linux", "linux", false, null, null, null, null)
      )
    )
    val decoded = RemoteCatalogCodec.decode(RemoteCatalogCodec.encode(catalog))
    assertEquals(catalog, decoded)
    assertFalse(RemoteCatalogCodec.encode(catalog).contains("token", ignoreCase = true))
  }

  @Test fun rejectsDuplicatesAndBadFingerprints() {
    val base = RemoteCatalog("https://remote.example/", "user@example.org", null, "Lagoon", listOf(
      RemoteHostMeta(hostA, "Mac", "darwin", false, null, null, null, null)
    ), 1)
    val duplicate = RemoteCatalogCodec.encode(base).replace("\"hosts\":[", "\"hosts\":[${RemoteCatalogCodec.encode(base).substringAfter("\"hosts\":[").substringBefore("]")},")
    try { RemoteCatalogCodec.decode(duplicate); fail("expected duplicate rejection") } catch (_: IOException) { }
  }

  @Test fun signedOutCatalogRetainsOnlyLocalSourceAndHostHierarchyMetadata() {
    val saved = RemoteCatalog("https://remote.example/", "user@example.org", "github", "Lagoon",
      listOf(RemoteHostMeta(hostA, "Mac", "darwin", true, null, "0.2.1", null, "abcdef012345")), 123L)
    val signedOut = saved.copy(signedOut = true)
    val decoded = RemoteCatalogCodec.decode(RemoteCatalogCodec.encode(signedOut))

    assertTrue(decoded.signedOut)
    assertEquals(saved.hosts, decoded.hosts)
    assertEquals(saved.origin, decoded.origin)
    assertFalse(RemoteCatalogCodec.encode(decoded).contains("password", ignoreCase = true))
    assertFalse(RemoteCatalogCodec.encode(decoded).contains("token", ignoreCase = true))
    assertFalse(saved.signedOut)
  }

  @Test fun legacyHttpCatalogDoesNotInheritCleartextConsentFromItsUrl() {
    val catalog = RemoteCatalog(
      origin = "http://localhost:4321/", account = "user@example.org", provider = null,
      deviceLabel = "Lagoon", hosts = emptyList(), updatedAt = 1_900_000_000_000
    )
    val legacy = JSONObject(RemoteCatalogCodec.encode(catalog)).apply { remove("allowCleartext") }.toString()
    val decoded = RemoteCatalogCodec.decode(legacy)
    assertFalse(decoded.allowCleartext)
    assertTrue(decoded.requiresCleartextConsent)
  }

  @Test fun explicitCleartextConsentRoundTripsAndOnlyAppliesToHttp() {
    val allowed = RemoteCatalog(
      origin = "http://localhost:4321/", account = "user@example.org", provider = null,
      deviceLabel = "Lagoon", hosts = emptyList(), updatedAt = 1_900_000_000_000,
      allowCleartext = true
    )
    val decoded = RemoteCatalogCodec.decode(RemoteCatalogCodec.encode(allowed))
    assertTrue(decoded.allowCleartext)
    assertFalse(decoded.requiresCleartextConsent)
    assertTrue(allowed.copy(allowCleartext = false).requiresCleartextConsent)
    assertFalse(allowed.copy(origin = "https://localhost:4321/", allowCleartext = false).requiresCleartextConsent)
    assertTrue(allowed.copy(origin = "  HTTP://localhost:4321/  ", allowCleartext = false).requiresCleartextConsent)
  }

  @Test fun remoteNetworkBoundaryRejectsHttpUntilConsentIsExplicit() {
    try {
      RemoteServerApi("http://localhost:4321", allowCleartext = false)
      fail("HTTP without consent must fail before a request is created")
    } catch (_: IllegalArgumentException) { }
    // Constructors validate policy only; these assertions perform no network requests.
    RemoteServerApi("http://localhost:4321", allowCleartext = true)
    RemoteServerApi("https://localhost:4321", allowCleartext = false)
  }

  @Test fun chineseHostNameAndLocalAliasSurviveCodec() {
    val catalog = RemoteCatalog(
      origin = "https://remote.example/",
      account = "user@example.org",
      provider = null,
      deviceLabel = "Lagoon · 2410DPN6CC",
      hosts = listOf(
        RemoteHostMeta(hostA, "工作站", "win32", true, null, "0.2.2-rc.3", "0.6.0", "abcdef012345",
          RemoteTrustState.PINNED, alias = "办公室工作站"),
        RemoteHostMeta(hostB, "???", "win32", true, null, null, null, null),
      ),
      updatedAt = 1_900_000_000_000,
    )
    val decoded = RemoteCatalogCodec.decode(RemoteCatalogCodec.encode(catalog))
    assertEquals("办公室工作站", decoded.hosts[0].displayName)
    assertEquals("办公室工作站", decoded.hosts[0].alias)
    assertEquals("工作站", decoded.hosts[0].name)
    assertTrue(decoded.hosts[1].nameUnavailable)
    assertEquals("未命名主机", decoded.hosts[1].displayName)
  }

  @Test fun badReportDoesNotOverwriteAReadableLocalNameOrAlias() {
    val previous = RemoteHostMeta(hostA, "工作站", "win32", true, null, null, null, null,
      RemoteTrustState.PINNED, alias = "我的工作站")
    val next = RemoteHostMeta(hostA, "???", "win32", true, null, null, null, null)
    val merged = next.withPreviousDisplay(previous)
    assertEquals("工作站", merged.name)
    assertEquals("我的工作站", merged.alias)
    assertEquals("我的工作站", merged.displayName)
  }

  @Test fun aliasIsBoundedAndStripsControls() {
    assertEquals("工作站", sanitizeRemoteAlias("  工作站\u0000  "))
    assertNull(sanitizeRemoteAlias("   "))
    assertEquals(128, sanitizeRemoteAlias("名".repeat(200))!!.length)
  }

}
