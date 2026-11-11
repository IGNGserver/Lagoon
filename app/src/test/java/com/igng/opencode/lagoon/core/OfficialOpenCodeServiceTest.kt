package com.igng.opencode.lagoon.core

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read-only live contract test. The caller owns the isolated official 2.2.6 daemon.
 * Opt in with LAGOON_OFFICIAL_OPENCODE_REGISTRATION=/absolute/path/to/service.json.
 * Registration contents and credentials remain in memory and never enter assertion output. */
class OfficialOpenCodeServiceTest {
  @Test fun officialServiceSupportsNativeReadsAndCancellableEvents() = runBlocking {
    val path = System.getenv("LAGOON_OFFICIAL_OPENCODE_REGISTRATION")
    assumeTrue("Set LAGOON_OFFICIAL_OPENCODE_REGISTRATION to enable the official daemon test", path != null)
    val file = File(requireNotNull(path))
    check(file.isAbsolute && file.isFile) { "Official registration must be an absolute regular file" }
    check(file.length() <= OpenCodeSshRegistration.MAX_BYTES) { "Official registration exceeds size limit" }
    val registration = checkNotNull(OpenCodeSshRegistration.parse(path, file.readText())) {
      "Invalid official service registration"
    }
    check(!registration.password.isNullOrBlank()) { "Official test requires password-protected service" }
    val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
      .callTimeout(10, TimeUnit.SECONDS).build()
    val profile = ServerProfile("official-live", "Official test", registration.url, allowCleartext = true)
    val api = OpenCodeApi(profile, ServerCredentials("opencode", registration.password.orEmpty()), callTimeoutSeconds = 10)
    try {
      // Official 2.2.6 authenticates /api/info too; do not mistake a public-fixture policy for the binary.
      http.newCall(Request.Builder().url("${registration.url.trimEnd('/')}/api/info").get().build())
        .execute().use { response -> assertEquals(401, response.code) }
      http.newCall(Request.Builder().url("${registration.url.trimEnd('/')}/api/project").get().build())
        .execute().use { response -> assertTrue("Projects must require authentication", response.code in setOf(401, 403)) }
      assertEquals("2.2.6", api.health())
      assertTrue("Capabilities must come from the actual OpenAPI", api.discoverCapabilities().documented)
      assertTrue(api.projects().all { it.id.isNotBlank() && it.directory.isNotBlank() })
      val page = api.rootSessionsPage(directory = null, cursor = null, size = 10)
      assertTrue(page.items.all { it.id.isNotBlank() && it.parentId == null }) // Empty isolated catalog is valid.
      val opened = CompletableDeferred<Unit>()
      val stream = api.events { opened.complete(Unit) }.launchIn(this)
      try { withTimeout(10_000) { opened.await() } } finally { stream.cancelAndJoin() }
      assertTrue("Event collector must terminate after cancellation", stream.isCompleted)
      // Cancellation closes only the client stream; the caller's service remains healthy.
      http.newCall(Request.Builder().url("${registration.url.trimEnd('/')}/api/info").get().build())
        .execute().use { response -> assertEquals(401, response.code) }
    } finally {
      api.close()
      http.dispatcher.cancelAll()
      http.connectionPool.evictAll()
      http.dispatcher.executorService.shutdown()
    }
    assertTrue(api.isClosed)
  }
}
