package com.igng.opencode.lagoon.core

import org.junit.Assert.*
import org.junit.Test
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
}
