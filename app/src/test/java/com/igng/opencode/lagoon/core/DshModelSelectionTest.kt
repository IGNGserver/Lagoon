package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DshModelSelectionTest {
  @Test fun projectionPrefersNextSelectionThenLastUsed() {
    val next = JSONObject("""{"next":{"provider":"p","model":"m-next","reasoningEffort":"high"},"lastUsed":{"provider":"p","model":"m-old"}}""")
    val last = JSONObject("""{"lastUsed":{"provider":"p","model":"m-old","reasoningEffort":"low"}}""")
    fun parse(selection: JSONObject): ModelChoice? {
      val chosen = selection.obj("next").takeIf { it.length() > 0 } ?: selection.obj("lastUsed")
      val provider = chosen.str("provider"); val model = chosen.str("model")
      return if (provider.isBlank() || model.isBlank()) null else ModelChoice(provider, model, model, chosen.str("reasoningEffort").takeIf(String::isNotBlank))
    }
    assertEquals("m-next", parse(next)?.modelId)
    assertEquals("low", parse(last)?.variant)
  }

  @Test fun durableEventUsesFlatProviderAndModel() {
    val event = JSONObject("""{"provider":"provider","model":"model","reasoningEffort":"medium"}""")
    assertEquals("provider", event.str("provider"))
    assertEquals("model", event.str("model"))
    assertFalse(event.has("next"))
  }
}
