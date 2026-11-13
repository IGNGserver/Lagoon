package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.RemoteExecResult
import java.io.InputStream
import java.io.OutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SshAgyCatalogTest {
  private class FakeSsh : SshTransport {
    override val isClosed = false
    var command = ""
    var result = RemoteExecResult(0, "", "")
    override fun exec(script: String, timeoutMillis: Long): RemoteExecResult { command = script; return result }
    override fun openTunnel(host: String, port: Int, input: InputStream, output: OutputStream): TunnelConnection = error("unused")
    override fun probeTunnel(host: String, port: Int, timeoutMillis: Long) = false
    override fun ping(timeoutMillis: Long) = true
    override fun close() = Unit
  }
  @Test fun workspaceLookupIsReadOnlyAndDoesNotInterpolateSessionIdsIntoShell() {
    val id = "session'; echo unsafe; #"
    val ssh = FakeSsh()
    ssh.result = RemoteExecResult(0, "LAGOON_AGY_WORKSPACES " + JSONObject().put(id, JSONArray().put("/home/dev").put("/repo")) + "\n", "")
    val response = JSONObject().put("sessions", JSONArray().put(JSONObject().put("sessionId", id).put("cwd", "/home/dev")))
    val enriched = SshAgyCatalog.enrich(ssh, response)
    assertTrue(enriched.getBoolean("_lagoonWorkspaceLookupComplete"))
    assertEquals("/repo", enriched.getJSONArray("sessions").getJSONObject(0).getJSONArray("_lagoonWorkspaces").getString(1))
    assertFalse(ssh.command.contains(id))
    assertTrue(ssh.command.contains("mode=ro"))
    assertTrue(ssh.command.contains("con.execute(query, chunk)"))
  }
  @Test fun unavailableOrMalformedMetadataIsNotReportedComplete() {
    val ssh = FakeSsh()
    val response = { JSONObject().put("sessions", JSONArray().put(JSONObject().put("sessionId", "one").put("cwd", "/repo"))) }
    ssh.result = RemoteExecResult(1, "", "")
    assertFalse(SshAgyCatalog.enrich(ssh, response()).getBoolean("_lagoonWorkspaceLookupComplete"))
    ssh.result = RemoteExecResult(0, "LAGOON_AGY_WORKSPACES {}\n", "")
    assertFalse(SshAgyCatalog.enrich(ssh, response()).getBoolean("_lagoonWorkspaceLookupComplete"))
  }
}
