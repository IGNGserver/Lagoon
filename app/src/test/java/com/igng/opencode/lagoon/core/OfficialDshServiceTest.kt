package com.igng.opencode.lagoon.core

import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Paths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read-only contract against a caller-owned, isolated official DSH 0.2.1-rc.5 daemon.
 * Opt in with LAGOON_OFFICIAL_DSH_CONNECTION=/absolute/scratch/connection.json ({url,token}).
 * Never starts a daemon, creates a session, sends a prompt, or touches the user's port 3080. */
class OfficialDshServiceTest {
  @Test fun officialServiceSupportsNativeReadsAndCancellableEvents() = runBlocking {
    val path = System.getenv("LAGOON_OFFICIAL_DSH_CONNECTION")
    assumeTrue("Set LAGOON_OFFICIAL_DSH_CONNECTION to enable the official daemon test", path != null)
    val file = try { Paths.get(requireNotNull(path)) } catch (_: Exception) {
      error("Invalid official connection file path")
    }
    check(file.isAbsolute && Files.isRegularFile(file, NOFOLLOW_LINKS)) {
      "Official connection must be an absolute regular file"
    }
    val connection = try {
      check(Files.size(file) <= 16_384)
      val bytes = Files.newInputStream(file).use { it.readNBytes(16_385) }
      check(bytes.size <= 16_384)
      JSONObject(String(bytes, Charsets.UTF_8))
    } catch (_: Exception) { error("Invalid official connection file (maximum 16 KiB)") }
    val url = connection.opt("url") as? String ?: error("Official connection requires a URL")
    val token = connection.opt("token") as? String ?: error("Official connection requires a token")
    check(token.isNotBlank()) { "Official connection requires a nonblank token" }
    val uri = try { URI(url) } catch (_: Exception) { error("Invalid official connection URL") }
    check(uri.scheme == "http" && uri.host == "127.0.0.1" &&
      uri.port in 1..65535 && uri.port != 3080 && uri.rawUserInfo == null &&
      uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.isNullOrEmpty()) {
      "Official connection URL must be a bare HTTP loopback origin on a non-3080 port"
    }
    val profile = ServerProfile("official-dsh-live", "Official DSH test", url,
      username = "dsh", allowCleartext = true, kind = ServerKind.DSH, dshAuth = DshAuthMode.TOKEN)
    val api = DshApi(profile, ServerCredentials("dsh", token), callTimeoutSeconds = 10)
    try {
      assertEquals("DeepSeek Harness", api.health())
      val page = api.rootSessionsPage(directory = null, cursor = null, size = 10)
      assertEquals("The caller-owned isolated daemon must have no root sessions", 0, page.items.size)
      // Official workspace discovery may include a system baseline without a user-created workspace.
      assertTrue("Project identities must be nonblank", api.projects().all {
        it.id.isNotBlank() && it.directory.isNotBlank()
      })
      withContext(Dispatchers.IO) {
        val opened = CompletableDeferred<Unit>()
        val stream = api.events { opened.complete(Unit) }.launchIn(this)
        try { withTimeout(15_000) { opened.await() } } finally { stream.cancelAndJoin() }
        assertTrue("Event collector must terminate after cancellation", stream.isCompleted)
      }
      // Cancelling our event collector must not terminate the caller's daemon.
      assertEquals("DeepSeek Harness", api.health())
    } finally {
      api.close()
      withTimeout(15_000) { api.awaitClosed() }
    }
    assertTrue("Client close must complete", api.isClosed)
  }
}
