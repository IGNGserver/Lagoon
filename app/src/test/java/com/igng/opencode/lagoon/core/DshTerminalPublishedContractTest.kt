package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DshTerminalPublishedContractTest {
  @Test fun fixedOfficialNamespaceDistinguishesSessionOwnerAndAgentExecution() {
    val session = JSONObject().put("sessionId", "original")
    val agent = JSONObject().put("agentId", "original")
    OfficialDshContract.assertArgs("terminal/list", session)
    OfficialDshContract.assertArgs("terminal/environment", agent)
    OfficialDshContract.assertArgs("terminal/shells", agent)
    OfficialDshContract.assertArgs("terminal/retain", JSONObject(session.toString()).put("id", "terminal"))
    for (endpoint in listOf("terminal/attach", "terminal/close"))
      OfficialDshContract.assertArgs(endpoint, JSONObject(agent.toString()).put("id", "terminal"))
    OfficialDshContract.assertArgs("terminal/rename", JSONObject(agent.toString()).put("id", "terminal").put("title", "Terminal"))
    OfficialDshContract.assertArgs("terminal/create", JSONObject(agent.toString()).put("request",
      JSONObject().put("id", "caller-generated").put("cols", 80).put("rows", 24)))
    assertTrue(runCatching { OfficialDshContract.assertArgs("terminal/list", agent) }.isFailure)
    assertTrue(runCatching { OfficialDshContract.assertArgs("terminal/attach", JSONObject(session.toString()).put("id", "terminal")) }.isFailure)
    assertTrue(runCatching { OfficialDshContract.assertArgs("terminal/create", JSONObject(agent.toString()).put("request",
      JSONObject().put("id", "caller-generated").put("cols", 80).put("rows", 24).put("command", "unreviewed"))) }.isFailure)
  }

  @Test fun fixtureRetainsPinnedGeneratedSourceInsteadOfInventedRemoteTerminalCapability() {
    val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("dsh-0.2.1-rc.5-terminal-contract.json"))
    val fixture = stream.bufferedReader().use { JSONObject(it.readText()) }
    assertEquals("0.2.1-rc.5", fixture.getJSONObject("upstream").getString("version"))
    assertEquals(8, fixture.getJSONObject("methods").length())
    val source = fixture.getJSONArray("sources").getJSONObject(0)
    assertEquals("@deepseek-ai/dsh-api-terminal-controller", source.getString("package"))
    assertTrue(source.getString("sha256").matches(Regex("[0-9a-f]{64}")))
    assertTrue(DshApi.TUNNEL_CAPABILITIES.terminal)
    assertFalse(ApiCapabilities.BASELINE.terminal)
  }
}
