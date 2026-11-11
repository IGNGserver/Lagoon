package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject

/** Uses schemas exported from the official generated Typert face; never a Lagoon-authored fake. */
internal object OfficialDshContract {
  private val fixture: JSONObject by lazy {
    val input = requireNotNull(javaClass.classLoader?.getResourceAsStream("dsh-0.2.1-rc.5-contract.json"))
    input.bufferedReader().use { JSONObject(it.readText()) }
  }

  private val goalFixture: JSONObject by lazy {
    val input = requireNotNull(javaClass.classLoader?.getResourceAsStream("dsh-0.2.1-rc.5-goal-contract.json"))
    input.bufferedReader().use { JSONObject(it.readText()) }
  }

  private val scheduleFixture: JSONObject by lazy {
    val input = requireNotNull(javaClass.classLoader?.getResourceAsStream("dsh-0.2.1-rc.5-schedule-contract.json"))
    input.bufferedReader().use { JSONObject(it.readText()) }
  }

  private val terminalFixture: JSONObject by lazy {
    val input = requireNotNull(javaClass.classLoader?.getResourceAsStream("dsh-0.2.1-rc.5-terminal-contract.json"))
    input.bufferedReader().use { JSONObject(it.readText()) }
  }

  private val workspaceFixture: JSONObject by lazy {
    val input = requireNotNull(javaClass.classLoader?.getResourceAsStream("dsh-0.2.1-rc.5-workspace-contract.json"))
    input.bufferedReader().use { JSONObject(it.readText()) }
  }

  private val searchFixture: JSONObject by lazy {
    val input = requireNotNull(javaClass.classLoader?.getResourceAsStream("dsh-0.2.1-rc.5-search-contract.json"))
    input.bufferedReader().use { JSONObject(it.readText()) }
  }

  private val forkFixture: JSONObject by lazy {
    val input = requireNotNull(javaClass.classLoader?.getResourceAsStream("dsh-0.2.1-rc.5-fork-contract.json"))
    input.bufferedReader().use { JSONObject(it.readText()) }
  }

  fun schema(endpoint: String): JSONObject =
    (when { endpoint == "session/fork" -> forkFixture; endpoint == "session/search" -> searchFixture; endpoint in setOf("workspace/create", "workspace/rename", "workspace/delete", "workspace/insertBefore", "workspace/follow") -> workspaceFixture; endpoint.startsWith("goals/") -> goalFixture; endpoint.startsWith("schedule/") -> scheduleFixture; endpoint.startsWith("terminal/") -> terminalFixture; else -> fixture })
      .getJSONObject("methods").getJSONObject(endpoint)
  fun schema(endpoint: String, version: String): JSONObject {
    if (version == "0.2.1-rc.5") return schema(endpoint)
    check(version == "0.2.2-rc.1") { "Unreviewed DSH contract version" }
    val input = requireNotNull(javaClass.classLoader?.getResourceAsStream("dsh-0.2.2-rc.1-session-contract.json"))
    return input.bufferedReader().use { JSONObject(it.readText()) }.getJSONObject("methods").getJSONObject(endpoint)
  }
  fun has(endpoint: String): Boolean = fixture.getJSONObject("methods").has(endpoint) || goalFixture.getJSONObject("methods").has(endpoint) || scheduleFixture.getJSONObject("methods").has(endpoint) || terminalFixture.getJSONObject("methods").has(endpoint) || workspaceFixture.getJSONObject("methods").has(endpoint) || searchFixture.getJSONObject("methods").has(endpoint) || forkFixture.getJSONObject("methods").has(endpoint)
  fun upstream(): JSONObject = fixture.getJSONObject("upstream")

  fun assertResult(endpoint: String, value: Any?) {
    validate(schema(endpoint).getJSONObject("result"), value, "$endpoint:result")
  }

  fun assertArgs(endpoint: String, args: JSONObject) {
    validate(schema(endpoint).getJSONObject("args"), args, endpoint)
  }

  fun assertArgs(endpoint: String, args: JSONObject, version: String) {
    validate(schema(endpoint, version).getJSONObject("args"), args, "$version:$endpoint")
  }

  private fun validate(schema: JSONObject, value: Any?, at: String) {
    val nullable = value == null || value === JSONObject.NULL
    schema.optJSONArray("allOf")?.objects()?.forEach { validate(it, value, at) }
    for (union in listOf("oneOf", "anyOf")) schema.optJSONArray(union)?.let { branches ->
      check(branches.objects().any { runCatching { validate(it, value, at) }.isSuccess }) { "$at: no $union schema accepts value" }
    }
    if (schema.has("const")) check(equalJson(schema.opt("const"), value)) { "$at: unexpected literal" }
    schema.optJSONArray("enum")?.let { values ->
      check((0 until values.length()).any { equalJson(values.opt(it), value) }) { "$at: value not in enum" }
    }
    val type = schema.opt("type")
    if (type is JSONArray) {
      check((0 until type.length()).any { matchesType(type.optString(it), value) }) { "$at: wrong JSON type" }
    } else if (type is String) check(matchesType(type, value)) { "$at: expected $type" }
    if (nullable) return
    if (value is JSONObject) {
      schema.optJSONArray("required")?.strings()?.forEach { key -> check(value.has(key)) { "$at: missing $key" } }
      val properties = schema.optJSONObject("properties") ?: JSONObject()
      val additional = schema.opt("additionalProperties")
      value.keys().forEach { key ->
        when {
          properties.has(key) -> validate(properties.getJSONObject(key), value.opt(key), "$at.$key")
          additional == false -> error("$at: unexpected field $key")
          additional is JSONObject -> validate(additional, value.opt(key), "$at.$key")
        }
      }
    }
    if (value is JSONArray) {
      schema.optJSONObject("items")?.let { item -> (0 until value.length()).forEach { validate(item, value.opt(it), "$at[$it]") } }
      if (schema.has("minItems")) check(value.length() >= schema.getInt("minItems")) { "$at: too few items" }
      if (schema.has("maxItems")) check(value.length() <= schema.getInt("maxItems")) { "$at: too many items" }
    }
  }

  private fun matchesType(type: String, value: Any?): Boolean = when (type) {
    "object" -> value is JSONObject
    "array" -> value is JSONArray
    "string" -> value is String
    "boolean" -> value is Boolean
    "number" -> value is Number && value.toDouble().isFinite()
    "integer" -> value is Number && value.toDouble().isFinite() && value.toDouble() % 1.0 == 0.0
    "null" -> value == null || value === JSONObject.NULL
    else -> error("Unimplemented fixture schema type: $type")
  }

  private fun equalJson(left: Any?, right: Any?): Boolean = when {
    (left == null || left === JSONObject.NULL) && (right == null || right === JSONObject.NULL) -> true
    left is Number && right is Number -> left.toDouble() == right.toDouble()
    else -> left == right
  }
}
