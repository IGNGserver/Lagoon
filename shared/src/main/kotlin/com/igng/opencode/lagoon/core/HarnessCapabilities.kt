package com.igng.opencode.lagoon.core

/**
 * A route-independent operation or surface exposed by a native Harness.
 *
 * A feature is deliberately not a transport endpoint and is not derived from a
 * legacy profile/server kind.  The same feature may be exposed through more
 * than one route (or through a non-HTTP carrier).
 */
enum class HarnessFeature {
  SESSION_LIST,
  SESSION_READ,
  SESSION_CREATE,
  SESSION_PROMPT,
  SESSION_STEER,
  SESSION_QUEUE,
  SESSION_RENAME,
  SESSION_ARCHIVE,
  SESSION_RESTORE,
  SESSION_DELETE,
  SESSION_FORK,
  SESSION_REVERT,
  SESSION_DIFF,
  SESSION_VIEW,
  SESSION_INTERRUPT,
  WORKSPACE_LIST,
  WORKSPACE_CREATE,
  WORKSPACE_RENAME,
  WORKSPACE_DELETE,
  WORKSPACE_UPLOAD,
  FILE_READ,
  FILE_WRITE,
  ATTACHMENT_IMAGE,
  ATTACHMENT_FILE,
  JOB_LIST,
  JOB_OUTPUT,
  JOB_STOP,
  TERMINAL,
  APPROVAL,
  QUESTION,
  TODO,
  GOAL,
  SCHEDULE,
  PLUGIN,
  SETTINGS,
  NOTIFICATIONS,
  DISCOVERY;

  companion object {
    // Short aliases keep call sites readable without creating route or kind identities.
    val PROMPT = SESSION_PROMPT
    val STEER = SESSION_STEER
    val RENAME = SESSION_RENAME
    val ARCHIVE = SESSION_ARCHIVE
    val RESTORE = SESSION_RESTORE
    val DELETE = SESSION_DELETE
    val FORK = SESSION_FORK
    val REVERT = SESSION_REVERT
    val DIFF = SESSION_DIFF
    val VIEW = SESSION_VIEW
    val SHELL = JOB_OUTPUT
    val JOB_FOLLOW = JOB_OUTPUT
    val FILE_UPLOAD = WORKSPACE_UPLOAD
  }
}

/** The result of one authoritative capability observation. */
enum class HarnessFeatureState {
  SUPPORTED,
  UNSUPPORTED,
  UNKNOWN,
  BLOCKED
}

/** Compatibility spelling for callers that call the four states availability. */
typealias HarnessFeatureAvailability = HarnessFeatureState

/**
 * A feature state plus a human-readable explanation.
 *
 * Reasons are intentionally opaque strings: protocol, carrier, discovery and
 * policy authorities may each have different stable error vocabularies.  The
 * intersection helper preserves all non-empty reasons rather than replacing a
 * useful server explanation with a generic one.
 */
data class HarnessFeatureStatus(
  val state: HarnessFeatureState,
  val reason: String? = null
) {
  val supported: Boolean get() = state == HarnessFeatureState.SUPPORTED
  val available: Boolean get() = supported
  val reasons: List<String> get() = reason?.let(::listOf).orEmpty()

  companion object {
    // Convenience values are useful for maps and also make status reads explicit.
    val SUPPORTED = HarnessFeatureStatus(HarnessFeatureState.SUPPORTED)
    val UNSUPPORTED = HarnessFeatureStatus(HarnessFeatureState.UNSUPPORTED)
    val UNKNOWN = HarnessFeatureStatus(HarnessFeatureState.UNKNOWN)
    val BLOCKED = HarnessFeatureStatus(HarnessFeatureState.BLOCKED)

    fun supported(reason: String? = null) = HarnessFeatureStatus(HarnessFeatureState.SUPPORTED, reason)
    fun unsupported(reason: String? = null) = HarnessFeatureStatus(HarnessFeatureState.UNSUPPORTED, reason)
    fun unknown(reason: String? = null) = HarnessFeatureStatus(HarnessFeatureState.UNKNOWN, reason)
    fun blocked(reason: String? = null) = HarnessFeatureStatus(HarnessFeatureState.BLOCKED, reason)
  }
}

/** A concrete route is evidence for a feature, not the feature's identity. */
data class HarnessEndpointRoute(
  val method: String,
  val path: String
) {
  init {
    require(method.trim().isNotEmpty()) { "Endpoint method is empty" }
    require(path.trim().isNotEmpty()) { "Endpoint path is empty" }
  }

  val normalizedMethod: String get() = method.trim().uppercase()
  val normalizedPath: String get() = path.trim()

  companion object {
    fun of(method: String, path: String) = HarnessEndpointRoute(method, path)
  }
}

typealias HarnessFeatureRoute = HarnessEndpointRoute

/** One authority's feature observations and any routes it documented. */
data class HarnessCapabilityLayer(
  val features: Map<HarnessFeature, HarnessFeatureStatus> = emptyMap(),
  val routes: Map<HarnessFeature, Set<HarnessEndpointRoute>> = emptyMap()
) {
  init {
    require(routes.keys.all(features::containsKey) || routes.isEmpty()) {
      "A route cannot be documented without a feature status"
    }
  }

  fun status(feature: HarnessFeature): HarnessFeatureStatus =
    features[feature] ?: HarnessFeatureStatus.unknown("Feature was not reported by this authority")

  fun supports(feature: HarnessFeature): Boolean = status(feature).supported
  fun routesFor(feature: HarnessFeature): Set<HarnessEndpointRoute> = routes[feature].orEmpty()

  companion object {
    fun of(
      vararg features: Pair<HarnessFeature, HarnessFeatureStatus>
    ) = HarnessCapabilityLayer(features.toMap())

    fun route(
      feature: HarnessFeature,
      status: HarnessFeatureStatus,
      vararg routes: HarnessEndpointRoute
    ) = HarnessCapabilityLayer(mapOf(feature to status), mapOf(feature to routes.toSet()))
  }
}

// Named aliases make the four inputs self-documenting while keeping one small model.
typealias HarnessProtocolCapabilities = HarnessCapabilityLayer
typealias HarnessCarrierCapabilities = HarnessCapabilityLayer
typealias HarnessDiscoveryCapabilities = HarnessCapabilityLayer
typealias HarnessServerPolicy = HarnessCapabilityLayer

/** The result of intersecting independent authorities for one binding. */
data class HarnessCapabilityIntersection(
  val protocol: HarnessCapabilityLayer,
  val carrier: HarnessCapabilityLayer,
  val discovery: HarnessCapabilityLayer,
  val serverPolicy: HarnessCapabilityLayer,
  val features: Map<HarnessFeature, HarnessFeatureStatus>
) {
  fun status(feature: HarnessFeature): HarnessFeatureStatus =
    features[feature] ?: HarnessFeatureStatus.unknown("Feature was not requested")

  fun supports(feature: HarnessFeature): Boolean = status(feature).supported

  /** Routes remain protocol evidence and are never merged into feature identity. */
  fun routesFor(feature: HarnessFeature): Set<HarnessEndpointRoute> = protocol.routesFor(feature)
}

/** Pure capability operations shared by protocol/carrier/discovery/policy code. */
object HarnessCapabilities {
  /**
   * Intersect all four authorities for every feature mentioned by at least one
   * layer.  Missing observations are UNKNOWN, never UNSUPPORTED.
   *
   * Precedence is BLOCKED, UNSUPPORTED, UNKNOWN, SUPPORTED.  A policy block is
   * therefore visible even when a protocol also does not implement a feature;
   * a definitive negative likewise wins over an observation that is merely
   * unknown.  All reasons are retained in input order.
   */
  fun intersect(
    protocol: HarnessCapabilityLayer,
    carrier: HarnessCapabilityLayer,
    discovery: HarnessCapabilityLayer,
    serverPolicy: HarnessCapabilityLayer
  ): HarnessCapabilityIntersection {
    val allFeatures = linkedSetOf<HarnessFeature>().apply {
      addAll(protocol.features.keys)
      addAll(carrier.features.keys)
      addAll(discovery.features.keys)
      addAll(serverPolicy.features.keys)
    }
    val result = allFeatures.associateWith { feature ->
      intersectFeature(
        feature,
        listOf(
          "protocol" to protocol.status(feature),
          "carrier" to carrier.status(feature),
          "discovery" to discovery.status(feature),
          "server policy" to serverPolicy.status(feature)
        )
      )
    }
    return HarnessCapabilityIntersection(protocol, carrier, discovery, serverPolicy, result)
  }

  fun intersect(
    protocol: Map<HarnessFeature, HarnessFeatureStatus>,
    carrier: Map<HarnessFeature, HarnessFeatureStatus>,
    discovery: Map<HarnessFeature, HarnessFeatureStatus>,
    serverPolicy: Map<HarnessFeature, HarnessFeatureStatus>
  ): HarnessCapabilityIntersection = intersect(
    HarnessCapabilityLayer(protocol),
    HarnessCapabilityLayer(carrier),
    HarnessCapabilityLayer(discovery),
    HarnessCapabilityLayer(serverPolicy)
  )

  fun status(
    feature: HarnessFeature,
    protocol: HarnessCapabilityLayer,
    carrier: HarnessCapabilityLayer,
    discovery: HarnessCapabilityLayer,
    serverPolicy: HarnessCapabilityLayer
  ): HarnessFeatureStatus = intersect(protocol, carrier, discovery, serverPolicy).status(feature)

  /**
   * Converts the existing read-only discovery observation to a feature layer.
   * A stale or future observation is UNKNOWN; it must not silently become NO.
   */
  fun fromDiscovery(
    status: HarnessDiscoveryStatus,
    features: Iterable<HarnessFeature> = HarnessFeature.entries,
    nowMs: Long? = null
  ): HarnessDiscoveryCapabilities {
    val freshness = nowMs?.let(status::freshness)
    val featureStatus = when {
      freshness == DiscoveryFreshness.UNKNOWN -> HarnessFeatureStatus.unknown("Discovery observation is not current")
      freshness == DiscoveryFreshness.STALE -> HarnessFeatureStatus.unknown("Discovery observation is stale")
      status.available == DiscoveryValue.YES -> HarnessFeatureStatus.supported(status.reason)
      status.available == DiscoveryValue.NO -> HarnessFeatureStatus.unsupported(status.reason)
      status.available == DiscoveryValue.UNSUPPORTED -> HarnessFeatureStatus.unsupported(status.reason)
      else -> HarnessFeatureStatus.unknown(status.reason)
    }
    return HarnessCapabilityLayer(features.associateWith { featureStatus })
  }

  private fun intersectFeature(
    feature: HarnessFeature,
    observations: List<Pair<String, HarnessFeatureStatus>>
  ): HarnessFeatureStatus {
    val state = when {
      observations.any { it.second.state == HarnessFeatureState.BLOCKED } -> HarnessFeatureState.BLOCKED
      observations.any { it.second.state == HarnessFeatureState.UNSUPPORTED } -> HarnessFeatureState.UNSUPPORTED
      observations.any { it.second.state == HarnessFeatureState.UNKNOWN } -> HarnessFeatureState.UNKNOWN
      else -> HarnessFeatureState.SUPPORTED
    }
    val reasons = observations.mapNotNull { (authority, value) ->
      value.reason?.takeIf { it.isNotBlank() }?.let { "$authority: $it" }
    }
    val reason = reasons.takeIf { it.isNotEmpty() }?.joinToString("; ")
      ?: when (state) {
        HarnessFeatureState.SUPPORTED -> null
        HarnessFeatureState.UNSUPPORTED -> "$feature is unsupported by an authority"
        HarnessFeatureState.UNKNOWN -> "$feature has an unknown authority state"
        HarnessFeatureState.BLOCKED -> "$feature is blocked by an authority"
      }
    return HarnessFeatureStatus(state, reason)
  }
}

/** Policy for a mutation whose delivery outcome cannot be observed. */
enum class HarnessUnknownDeliveryPolicy {
  /** Reconcile authoritative state before considering any retry; never blind replay. */
  RECONCILE_BEFORE_RETRY,
  /** Do not retry, even after reconciliation. */
  NEVER_RETRY,
  /** Retry only when the operation is explicitly idempotent. */
  RETRY_IF_IDEMPOTENT;

  fun allowsRetryAfterUnknown(idempotent: Boolean): Boolean = when (this) {
    RECONCILE_BEFORE_RETRY, NEVER_RETRY -> false
    RETRY_IF_IDEMPOTENT -> idempotent
  }

  fun requiresReconciliation(): Boolean = this != NEVER_RETRY

  companion object {
    // Spelling aliases for callers that use shorter policy terminology.
    val RECONCILE = RECONCILE_BEFORE_RETRY
    val NO_RETRY = NEVER_RETRY
    val IDEMPOTENT_RETRY = RETRY_IF_IDEMPOTENT
  }
}

typealias UnknownDeliveryPolicy = HarnessUnknownDeliveryPolicy

/**
 * Immutable route binding captured when an operation starts.
 *
 * Every generation participates in equality.  A response from a different
 * source revision, host connection generation, carrier generation, or binding
 * ID is stale and must not be applied to the current operation target.
 */
data class HarnessOperationBindingSnapshot(
  val bindingId: String,
  val sourceRevision: Long,
  val hostGeneration: Long,
  val carrierGeneration: Long,
  val unknownDeliveryPolicy: HarnessUnknownDeliveryPolicy = HarnessUnknownDeliveryPolicy.RECONCILE_BEFORE_RETRY
) {
  init {
    require(bindingId.isNotBlank()) { "Operation binding ID is empty" }
    require(sourceRevision >= 0) { "Source revision cannot be negative" }
    require(hostGeneration >= 0) { "Host generation cannot be negative" }
    require(carrierGeneration >= 0) { "Carrier generation cannot be negative" }
  }

  fun sameGenerations(other: HarnessOperationBindingSnapshot): Boolean =
    sourceRevision == other.sourceRevision &&
      hostGeneration == other.hostGeneration &&
      carrierGeneration == other.carrierGeneration

  fun matches(other: HarnessOperationBindingSnapshot): Boolean =
    bindingId == other.bindingId && sameGenerations(other)

  fun isCurrent(current: HarnessOperationBindingSnapshot): Boolean = matches(current)
  fun isStaleAgainst(current: HarnessOperationBindingSnapshot): Boolean = !matches(current)

  fun allowsRetryAfterUnknown(idempotent: Boolean): Boolean =
    unknownDeliveryPolicy.allowsRetryAfterUnknown(idempotent)

  fun requiresReconciliationAfterUnknown(): Boolean = unknownDeliveryPolicy.requiresReconciliation()
}

typealias OperationBindingSnapshot = HarnessOperationBindingSnapshot
