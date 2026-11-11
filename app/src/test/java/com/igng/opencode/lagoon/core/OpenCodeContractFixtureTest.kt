package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the capability reader against a verbatim excerpt of the real upstream document
 * (`anomalyco/opencode` v2.2.6 `packages/protocol/openapi.json`), not a hand-written guess.
 *
 * The archive contract drifted for two years because the only test of it used a made-up
 * `time.archived` write shape that no release ever published. Anything asserted here must stay
 * traceable to `app/src/test/resources/opencode-2.2.6-contract.json`.
 */
class OpenCodeContractFixtureTest {
  private fun document(): JSONObject {
    val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream(FIXTURE)) { "缺少契约 fixture：$FIXTURE" }
    return stream.bufferedReader().use { JSONObject(it.readText()) }
  }

  @Test fun theFixtureIsThePublishedVersion() {
    assertEquals("2.2.6", document().obj("info").str("version"))
  }

  @Test fun everySessionActionResolvesOnTheRealDocument() {
    val caps = ApiCapabilities.fromDocument(document())
    assertTrue(caps.documented)
    assertEquals(ActionEndpoint("PATCH", "api/session/{sessionID}"), caps.actions[SessionAction.RENAME])
    assertEquals(ActionEndpoint("DELETE", "api/session/{sessionID}/revert"), caps.actions[SessionAction.UNREVERT])
    assertTrue(caps.diff)
    assertEquals(ActionEndpoint("POST", "api/session/{sessionID}/view"), caps.sessionView)
  }

  @Test fun archivingIsAvailableAndRestorableOnTheRealDocument() {
    val archive = ApiCapabilities.fromDocument(document()).archive
    assertNotNull("2.1+ 的会话更新体带顶层 archived，归档必须可用", archive)
    assertEquals(ActionEndpoint("PATCH", "api/session/{sessionID}"), archive!!.endpoint)
    assertTrue("archived: false 就是取消归档", archive.restorable)
  }

  /** Pins the published write shape so nobody re-introduces a `time.archived` body. */
  @Test fun thePublishedArchiveFieldIsATopLevelNullableBoolean() {
    val schema = document().obj("paths").obj("/api/session/{sessionID}").obj("patch")
      .obj("requestBody").obj("content").obj("application/json").obj("schema")
    assertFalse("顶层没有 time 包装", schema.obj("properties").has("time"))
    val archived = schema.obj("properties").obj("archived")
    val branches = archived.arr("anyOf").objects().map { it.optString("type") }
    assertEquals(listOf("boolean", "null"), branches)
  }

  /** The queued-input routes the client writes exist, with the bodies it sends. */
  @Test fun queuedInputRoutesExistAndTakeTheBodiesWeSend() {
    val inbox = document().obj("paths").obj("/api/session/{sessionID}/inbox/{inboxID}")
    val update = inbox.obj("patch").obj("requestBody").obj("content").obj("application/json").obj("schema")
    assertTrue("切换插话/排队需要 delivery", update.obj("properties").has("delivery"))
    assertEquals(listOf("delivery"), update.arr("required").let { required -> (0 until required.length()).map { required.optString(it) } })
    assertNotNull("撤回排队消息用 DELETE", inbox.optJSONObject("delete"))
  }

  @Test fun compactionAdmitsAClientMintedIdAndDelivery() {
    val body = document().obj("paths").obj("/api/session/{sessionID}/compact").obj("post")
      .obj("requestBody").obj("content").obj("application/json").obj("schema")
    val properties = body.obj("properties")
    assertTrue(properties.has("id"))
    assertTrue(properties.has("delivery"))
  }

  @Test fun nativeShellTerminationAndOutputArePublishedOnTheSupportedRelease() {
    val input = requireNotNull(javaClass.classLoader?.getResourceAsStream("opencode-2.2.6-native-services-contract.json"))
    val native = input.bufferedReader().use { JSONObject(it.readText()) }
    assertTrue(ApiCapabilities.fromDocument(native).stopShell)
    val paths = native.getJSONObject("paths")
    assertEquals("shell.get", paths.getJSONObject("/api/shell/{id}").getJSONObject("get").getString("operationId"))
    assertTrue(paths.getJSONObject("/api/shell/{id}/output").has("get"))
    assertEquals("event.subscribe", paths.getJSONObject("/api/event").getJSONObject("get").getString("operationId"))
  }

  private companion object {
    const val FIXTURE = "opencode-2.2.6-contract.json"
  }
}
