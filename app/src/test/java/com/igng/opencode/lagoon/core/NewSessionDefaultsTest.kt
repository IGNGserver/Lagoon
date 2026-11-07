package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewSessionDefaultsTest {
  private fun store() = ServerStore(FakeSharedPreferences(), FakeSharedPreferences(), { "enc:$it" }, { it.removePrefix("enc:") })

  @Test fun storeRoundTripsAgentAndModelWithoutFreezingAVariantInTheDefault() {
    val store = store()
    store.rememberNewSessionDefaults("srv", NewSessionDefaults("standard", ModelChoice("deepseek", "flash", "Flash")))
    val loaded = store.newSessionDefaults("srv")
    assertEquals("standard", loaded.agent)
    assertEquals("deepseek/flash", modelKey(loaded.model!!))
    assertNull(loaded.model!!.variant)
  }

  @Test fun clearingDefaultsRemovesBothFields() {
    val store = store()
    store.rememberNewSessionDefaults("srv", NewSessionDefaults("standard", ModelChoice("deepseek", "flash", "Flash")))
    store.rememberNewSessionDefaults("srv", NewSessionDefaults())
    assertNull(store.newSessionDefaults("srv").agent)
    assertNull(store.newSessionDefaults("srv").model)
  }

  @Test fun deletingTheConnectionClearsItsDefaults() {
    val preferences = FakeSharedPreferences()
    val store = ServerStore(preferences, FakeSharedPreferences(), { "enc:$it" }, { it.removePrefix("enc:") })
    store.rememberNewSessionDefaults("srv", NewSessionDefaults("standard", ModelChoice("deepseek", "flash", "Flash")))
    store.delete("srv")
    assertNull(store.newSessionDefaults("srv").agent)
    assertNull(store.newSessionDefaults("srv").model)
  }

  @Test fun resolverAppliesThePerModelStrengthAndKeepsAValidPreset() {
    val agents = listOf(AgentChoice("standard", ""), AgentChoice("plan", ""))
    val catalog = listOf(ModelInfo(ModelChoice("deepseek", "flash", "Flash"),
      variants = listOf(ModelVariant("low", "Low"), ModelVariant("high", "High"))))
    val resolved = resolveDraftDefaults("plan", ModelChoice("deepseek", "flash", "Flash"), agents, catalog,
      mapOf("deepseek/flash" to "high"))
    assertEquals("plan", resolved.agent)
    assertEquals("high", resolved.model?.variant)
  }

  @Test fun resolverFallsBackWhenTheServerNoLongerOffersThePresetOrModel() {
    val agents = listOf(AgentChoice("standard", ""))
    val catalog = listOf(ModelInfo(ModelChoice("deepseek", "flash", "Flash")))
    val resolved = resolveDraftDefaults("removed", ModelChoice("other", "gone", "Gone"), agents, catalog, emptyMap())
    assertNull(resolved.agent)
    assertNull(resolved.model)
  }

  @Test fun resolverDropsAnUnknownVariantButKeepsTheModel() {
    val catalog = listOf(ModelInfo(ModelChoice("deepseek", "flash", "Flash"),
      variants = listOf(ModelVariant("low", "Low"))))
    val resolved = resolveDraftDefaults(null, ModelChoice("deepseek", "flash", "Flash", "removed"), emptyList(), catalog, emptyMap())
    assertEquals("deepseek/flash", modelKey(resolved.model!!))
    assertNull(resolved.model!!.variant)
  }

  @Test fun anEmptyCatalogKeepsTheStoredChoiceForTheOfflineSend() {
    val model = ModelChoice("deepseek", "flash", "Flash")
    val resolved = resolveDraftDefaults("standard", model, emptyList(), emptyList(), emptyMap())
    assertEquals("standard", resolved.agent)
    assertEquals("deepseek/flash", modelKey(resolved.model!!))
  }

  @Test fun nullDefaultsMeanFollowTheServer() {
    val resolved = resolveDraftDefaults(null, null, listOf(AgentChoice("standard", "")),
      listOf(ModelInfo(ModelChoice("deepseek", "flash", "Flash"))), emptyMap())
    assertNull(resolved.agent)
    assertNull(resolved.model)
  }
}
