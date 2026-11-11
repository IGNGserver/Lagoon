package com.igng.opencode.lagoon.core

import java.io.File
import java.net.URI
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in; creates and removes one terminal only in a caller-owned isolated official service. */
class OfficialOpenCodeTerminalTest {
  @Test fun nativePtyUsesRawInputCursorMetadataAndDetachWithoutTermination() = runBlocking {
    val path = System.getenv("LAGOON_OFFICIAL_PTY_REGISTRATION")
    assumeTrue("Set LAGOON_OFFICIAL_PTY_REGISTRATION and LAGOON_OFFICIAL_PTY_DIRECTORY for isolated acceptance", path != null)
    val directory = requireNotNull(System.getenv("LAGOON_OFFICIAL_PTY_DIRECTORY"))
    val file = File(requireNotNull(path)).canonicalFile
    check(file.isFile && file.length() <= OpenCodeSshRegistration.MAX_BYTES)
    val root = file.parentFile.parentFile.parentFile.parentFile
    check(File(directory).canonicalFile.toPath().startsWith(root.toPath())) { "PTY acceptance directory must stay in its isolated root" }
    val registration = checkNotNull(OpenCodeSshRegistration.parse(file.path, file.readText()))
    val uri = URI(registration.url)
    check(uri.host == "127.0.0.1" && uri.port > 0 && uri.port != 3080)
    val api = OpenCodeApi(ServerProfile("official-pty", "Isolated native PTY", registration.url, allowCleartext = true),
      ServerCredentials("opencode", registration.password.orEmpty()))
    var created: NativeTerminal? = null
    var attachment: TerminalAttachment? = null
    try {
      assertEquals("2.2.6", api.health())
      assertTrue(api.discoverCapabilities().terminal)
      created = api.createTerminal(directory)
      assertEquals("running", created.status)
      attachment = api.attachTerminal(directory, created.id)
      withTimeout(15_000) { attachment.state.first { it.ready } }
      assertTrue(attachment.sendText("printf 'LAGOON_%s\\n' JVM_PTY_ACCEPTANCE\r"))
      withTimeout(15_000) { attachment.state.first { it.output.contains("LAGOON_JVM_PTY_ACCEPTANCE") } }
      assertNotNull(attachment.state.value.cursor)
      attachment.close(); attachment = null
      assertEquals("running", api.terminals(directory).single { it.id == created.id }.status)
      assertEquals(created.id, api.resizeTerminal(directory, created.id, 90, 30).id)
      attachment = api.attachTerminal(directory, created.id) // A new single-use ticket; no input replay.
      withTimeout(15_000) { attachment.state.first { it.ready } }
      assertTrue(attachment.state.value.output.contains("LAGOON_JVM_PTY_ACCEPTANCE"))
      attachment.close(); attachment = null
      api.closeTerminal(directory, created.id)
      assertTrue(api.terminals(directory).none { it.id == created.id })
      created = null
    } finally {
      attachment?.close()
      try { created?.let { api.closeTerminal(directory, it.id) } } finally { api.close() }
    }
  }
}
