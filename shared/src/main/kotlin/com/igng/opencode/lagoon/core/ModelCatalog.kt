package com.igng.opencode.lagoon.core

import org.json.JSONObject

/** One server-provided thinking-strength / reasoning variant of a model. */
data class ModelVariant(val id: String, val label: String)

/**
 * One selectable model with the catalog metadata needed to decide its default visibility.
 * [released] is epoch milliseconds; 0 means the catalog carries no release date (custom models).
 */
data class ModelInfo(
  val choice: ModelChoice,
  val providerName: String = choice.providerId,
  val family: String? = null,
  val released: Long = 0,
  /** Whether the model accepts image input; null when the catalog does not say. */
  val imageInput: Boolean? = null,
  val deprecated: Boolean = false,
  /** Thinking-strength variants the server offers for this model; empty when it has none. */
  val variants: List<ModelVariant> = emptyList()
) {
  val key: String get() = modelKey(choice)
}

/** Provider IDs cannot contain `/`, so `provider/model` is unambiguous even for model IDs with `/` or `:`. */
fun modelKey(model: ModelChoice): String = "${model.providerId}/${model.modelId}"

/**
 * Per-server model visibility.
 *
 * The official client stores its model switches locally.  The phone follows the same local-only
 * principle, but deliberately keeps the first-run behaviour simple: until the user configures the
 * list, every model returned by the server is available.  Once configured, only explicitly checked
 * models remain available.  This is important for self-hosted providers whose release metadata does
 * not describe which models the user wants to see.
 */
object ModelVisibility {
  /** Retained for compatibility with older callers that used the official catalog default rule. */
  private const val SIX_MONTHS_MILLIS = 6L * 30 * 24 * 60 * 60 * 1000

  fun latest(models: List<ModelInfo>, now: Long = System.currentTimeMillis()): Set<String> =
    models.asSequence()
      .filter { it.released > 0 && kotlin.math.abs(now - it.released) < SIX_MONTHS_MILLIS }
      .filter { !it.family.isNullOrBlank() }
      .groupBy { it.choice.providerId to it.family }
      .values.mapNotNull { group -> group.maxByOrNull { it.released }?.key }
      .toSet()

  fun isVisible(model: ModelInfo, latest: Set<String>, overrides: Map<String, Boolean>): Boolean {
    overrides[model.key]?.let { return it }
    // [latest] is retained in the signature for source compatibility with the earlier catalog
    // implementation.  Visibility is now controlled only by the explicit per-server list.
    return overrides.isEmpty()
  }

  fun visible(models: List<ModelInfo>, overrides: Map<String, Boolean>, now: Long = System.currentTimeMillis()): List<ModelInfo> {
    // The legacy overload has no separate "configured" bit.  An empty map therefore means the
    // user has not configured filtering yet; a non-empty map is an explicitly configured list.
    return visibleConfigured(models, overrides, overrides.isNotEmpty())
  }

  fun visibleConfigured(models: List<ModelInfo>, overrides: Map<String, Boolean>, configured: Boolean): List<ModelInfo> {
    if (!configured) return models
    return models.filter { overrides[it.key] == true }
  }
}

/** V2 `/api/model` item. Disabled entries are not offered at all, matching the server's catalog rules. */
internal fun JSONObject.toV2ModelInfo(providerNames: Map<String, String>): ModelInfo? {
  if (has("enabled") && !optBoolean("enabled", true)) return null
  val provider = str("providerID")
  val id = str("id")
  if (provider.isBlank() || id.isBlank()) return null
  val inputs = obj("capabilities").arr("input")
  return ModelInfo(
    ModelChoice(provider, id, str("name").ifBlank { id }),
    providerName = providerNames[provider].orEmpty().ifBlank { provider },
    family = str("family").ifBlank { null },
    released = epochMillis(obj("time").optDouble("released", 0.0)),
    imageInput = if (obj("capabilities").has("input")) (0 until inputs.length()).any { inputs.optString(it) == "image" } else null,
    deprecated = str("status") == "deprecated",
    variants = toVariants()
  )
}

/**
 * The server's variant list for one model. Accepts a string array, `{id, ...}` objects, or an
 * object keyed by variant id, and never invents a universal low/medium/high set.
 */
private fun JSONObject.toVariants(): List<ModelVariant> {
  val array = optJSONArray("variants")
  if (array != null) return (0 until array.length()).mapNotNull { index ->
    when (val item = array.opt(index)) {
      is String -> item.takeIf(String::isNotBlank)?.let { ModelVariant(it, it) }
      is JSONObject -> {
        val id = item.str("id").ifBlank { item.str("name") }
        // The selector intentionally displays the server's raw variant id.  OpenCode V2 returns
        // `{id, headers, body}` and older compatible servers may return `{id, name}`; neither
        // shape defines a client-side translation for the id.
        id.takeIf(String::isNotBlank)?.let { ModelVariant(it, it) }
      }
      else -> null
    }
  }.distinctBy { it.id }

  // Some OpenCode-compatible V1/provider responses expose variants as an object keyed by the
  // original variant name.  JSONObject preserves insertion order on Android, so this also keeps
  // the server's order and does not manufacture a low/medium/high set.
  val variantsObject = optJSONObject("variants") ?: return emptyList()
  return variantsObject.keys().asSequence().mapNotNull { it.takeIf(String::isNotBlank)?.let { id -> ModelVariant(id, id) } }.toList()
}

/** Accepts epoch seconds or milliseconds; anything non-positive means "no release date". */
internal fun epochMillis(value: Double): Long = when {
  value.isNaN() || value <= 0 -> 0
  value < 100_000_000_000.0 -> (value * 1000).toLong()
  else -> value.toLong()
}
