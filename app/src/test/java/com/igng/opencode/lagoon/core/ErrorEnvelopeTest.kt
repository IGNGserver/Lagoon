package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 失败信封在同一个 reducer 里曾被解析成两种形状（扁平 message 与 data.message），
 * 嵌套的那一种会静默丢掉失败原因。
 */
class ErrorEnvelopeTest {
  @Test fun errorTextReadsStringsObjectsAndNestedData() {
    assertEquals("boom", JSONObject("""{"error":"boom"}""").errorText("error"))
    assertEquals("boom", JSONObject("""{"error":{"message":"boom"}}""").errorText("error"))
    assertEquals("boom", JSONObject("""{"error":{"data":{"message":"boom"}}}""").errorText("error"))
    assertEquals("", JSONObject("{}").errorText("error"))
    assertEquals("", JSONObject("""{"error":null}""").errorText("error"))
    assertEquals("", JSONObject("""{"error":{}}""").errorText("error"))
    assertEquals("", JSONObject("""{"error":[]}""").errorText("error"))
    assertEquals("", JSONObject("{}").errorMessage("error"))
    assertEquals("", JSONObject("""{"error":{}}""").errorMessage("error"))
    assertEquals("boom", JSONObject("""{"error":{"data":{"message":"boom"}}}""").errorMessage("error"))
  }
}
