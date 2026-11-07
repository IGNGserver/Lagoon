package com.igng.opencode.lagoon.ui

import com.igng.opencode.lagoon.core.ModelInfo

internal const val HYPER_TOUCH_TARGET_DP = 48
internal const val HYPER_ICON_VISUAL_DP = 40

internal fun hyperButtonActionable(enabled: Boolean, loading: Boolean): Boolean = enabled && !loading

/** Consent belongs to the exact edited address; parsing or changing a URL cannot carry it forward. */
internal fun cleartextConsentAfterUrlEdit(previousUrl: String, nextUrl: String, explicitlyAllowed: Boolean): Boolean =
  explicitlyAllowed && previousUrl.trim() == nextUrl.trim()

/** UI-only decisions kept pure so security and pending-action behavior can be regression tested. */
internal enum class SshSecretField { PASSWORD, PRIVATE_KEY, PASSPHRASE }

internal fun resolveSshSecret(replacement: String, saved: String?, clearSaved: Boolean): String? = when {
  clearSaved -> null
  replacement.isNotEmpty() -> replacement
  else -> saved
}

internal fun hasUsableSshSecret(replacement: String, saved: String?, clearSaved: Boolean): Boolean =
  !resolveSshSecret(replacement, saved, clearSaved).isNullOrBlank()

internal fun pendingRequestCount(permissions: Int, questions: Int): Int =
  permissions.coerceAtLeast(0) + questions.coerceAtLeast(0)

/** Stop remains independently visible for the whole running phase, regardless of composer content. */
internal fun shouldShowGenerationStop(running: Boolean): Boolean = running

internal fun resolveDeviceStatusLabel(enabled: Boolean, online: Boolean, loading: Boolean, hasError: Boolean): String = when {
  !enabled -> "已停用"
  loading -> "连接中"
  online -> "在线"
  hasError -> "需检查"
  else -> "离线"
}

internal fun attachmentCanOpen(url: String): Boolean {
  val value = url.trim()
  if (value.isEmpty() || value.startsWith("data:", true)) return false
  if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
    return runCatching { !java.net.URI(value).host.isNullOrBlank() }.getOrDefault(false)
  }
  val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").find(value)?.value
  if (scheme == null) return true
  if (!scheme.equals("file:", ignoreCase = true)) return false
  return runCatching { !java.net.URI(value).path.isNullOrBlank() }.getOrDefault(false)
}

internal fun filterModelCatalog(models: List<ModelInfo>, query: String): List<ModelInfo> {
  val needle = query.trim()
  if (needle.isEmpty()) return models
  return models.filter { model ->
    model.choice.label.contains(needle, ignoreCase = true) ||
      model.choice.modelId.contains(needle, ignoreCase = true) ||
      model.providerName.contains(needle, ignoreCase = true) ||
      model.choice.providerId.contains(needle, ignoreCase = true)
  }
}
