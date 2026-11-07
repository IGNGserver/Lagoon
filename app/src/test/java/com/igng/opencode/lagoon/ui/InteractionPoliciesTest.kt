package com.igng.opencode.lagoon.ui

import com.igng.opencode.lagoon.core.ModelChoice
import com.igng.opencode.lagoon.core.ModelInfo
import org.junit.Assert.*
import org.junit.Test

class InteractionPoliciesTest {
  @Test fun blankSshReplacementRetainsSavedSecretWithoutExposingIt() {
    assertEquals("saved-password", resolveSshSecret("", "saved-password", clearSaved = false))
    assertEquals("saved-key", resolveSshSecret("", "saved-key", clearSaved = false))
    assertNull(resolveSshSecret("", null, clearSaved = false))
  }

  @Test fun nonBlankSshReplacementSupersedesSavedSecret() {
    assertEquals("new-password", resolveSshSecret("new-password", "saved-password", clearSaved = false))
    assertEquals("new-password", resolveSshSecret("new-password", null, clearSaved = false))
  }

  @Test fun confirmedClearWinsAndTypingCanCancelItAtTheCallSite() {
    assertNull(resolveSshSecret("", "saved-password", clearSaved = true))
    // The form removes the clear marker when the user types; resolution then uses the replacement.
    assertEquals("replacement", resolveSshSecret("replacement", "saved-password", clearSaved = false))
    assertFalse(hasUsableSshSecret("", "saved-password", clearSaved = true))
    assertFalse(hasUsableSshSecret("", " ", clearSaved = false))
    assertTrue(hasUsableSshSecret("", "saved-password", clearSaved = false))
    assertTrue(hasUsableSshSecret("replacement", null, clearSaved = false))
  }

  @Test fun pendingReviewAggregatesBothKindsAndRejectsNegativeCounts() {
    assertEquals(0, pendingRequestCount(0, 0))
    assertEquals(5, pendingRequestCount(2, 3))
    assertEquals(4, pendingRequestCount(-2, 4))
    assertEquals(0, pendingRequestCount(-2, -3))
  }

  @Test fun generationStopDependsOnlyOnRunningState() {
    assertTrue(shouldShowGenerationStop(running = true))
    assertFalse(shouldShowGenerationStop(running = false))
  }

  @Test fun deviceStatusKeepsLoadingAndErrorForEveryVisibleDevice() {
    assertEquals("已停用", resolveDeviceStatusLabel(enabled = false, online = false, loading = true, hasError = true))
    assertEquals("连接中", resolveDeviceStatusLabel(enabled = true, online = false, loading = true, hasError = false))
    assertEquals("在线", resolveDeviceStatusLabel(enabled = true, online = true, loading = false, hasError = true))
    assertEquals("需检查", resolveDeviceStatusLabel(enabled = true, online = false, loading = false, hasError = true))
    assertEquals("离线", resolveDeviceStatusLabel(enabled = true, online = false, loading = false, hasError = false))
  }

  @Test fun cleartextConsentDoesNotFollowAChangedOrParsedUrl() {
    assertTrue(cleartextConsentAfterUrlEdit("http://192.168.1.2:3080", " http://192.168.1.2:3080 ", explicitlyAllowed = true))
    assertFalse(cleartextConsentAfterUrlEdit("http://192.168.1.2:3080", "http://192.168.1.3:3080", explicitlyAllowed = true))
    assertFalse(cleartextConsentAfterUrlEdit("", "http://192.168.1.2:3080/?token=secret", explicitlyAllowed = false))
    assertFalse(cleartextConsentAfterUrlEdit("https://example.com", "https://example.com", explicitlyAllowed = false))
  }

  @Test fun buttonLoadingBlocksDuplicateSubmissionWithoutChangingDisabledRules() {
    assertTrue(hyperButtonActionable(enabled = true, loading = false))
    assertFalse(hyperButtonActionable(enabled = true, loading = true))
    assertFalse(hyperButtonActionable(enabled = false, loading = false))
    assertFalse(hyperButtonActionable(enabled = false, loading = true))
  }

  @Test fun sharedTouchAndVisualContractsMeetAccessibilityMinimums() {
    assertTrue(HYPER_TOUCH_TARGET_DP >= 48)
    assertTrue(HYPER_ICON_VISUAL_DP >= 40)
    assertTrue(HYPER_ICON_VISUAL_DP <= HYPER_TOUCH_TARGET_DP)
  }

  @Test fun attachmentOpeningAcceptsOnlySupportedValidTargets() {
    listOf(
      "https://example.com/file.pdf",
      " HTTP://example.com/image.png ",
      "file:///tmp/result.txt",
      "/workspace/result.txt",
      "relative/result.txt"
    ).forEach { assertTrue("expected openable: $it", attachmentCanOpen(it)) }

    listOf(
      "", "   ", "data:image/png;base64,AAAA", "javascript:alert(1)",
      "intent://scan/#Intent;scheme=zxing;end", "content://provider/1", "mailto:user@example.com",
      "https://", "https://exa mple.com/file", "file:", "file:relative"
    ).forEach { assertFalse("expected rejected: $it", attachmentCanOpen(it)) }
  }

  @Test fun modelSearchTrimsMatchesCaseAndPreservesServerOrder() {
    val models = listOf(
      ModelInfo(ModelChoice("deepseek", "deepseek-chat", "DeepSeek V3"), providerName = "深度求索"),
      ModelInfo(ModelChoice("openai", "gpt-5.2", "GPT-5.2"), providerName = "OpenAI"),
      ModelInfo(ModelChoice("anthropic", "claude-sonnet-4", "Claude Sonnet"), providerName = "Anthropic")
    )
    assertSame(models, filterModelCatalog(models, "  "))
    assertEquals(listOf(models[0]), filterModelCatalog(models, " deepseek "))
    assertEquals(listOf(models[1]), filterModelCatalog(models, "openai"))
    assertEquals(listOf(models[1]), filterModelCatalog(models, "GPT-5"))
    assertEquals(listOf(models[2]), filterModelCatalog(models, "CLAUDE"))
    assertEquals(emptyList<ModelInfo>(), filterModelCatalog(models, "missing"))
  }
}
