package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessCapabilitiesTest {
  private val feature = HarnessFeature.SESSION_RENAME
  private val renamePatch = HarnessEndpointRoute("PATCH", "/api/session/{id}")
  private val renamePost = HarnessEndpointRoute("POST", "/api/session/{id}/rename")

  private fun layer(
    status: HarnessFeatureStatus,
    route: HarnessEndpointRoute? = null
  ): HarnessCapabilityLayer = if (route == null) {
    HarnessCapabilityLayer.of(feature to status)
  } else {
    HarnessCapabilityLayer.route(feature, status, route)
  }

  @Test fun supportedOnlyWhenEveryAuthoritySupportsFeature() {
    val result = HarnessCapabilities.intersect(
      protocol = layer(HarnessFeatureStatus.supported(), renamePatch),
      carrier = layer(HarnessFeatureStatus.supported()),
      discovery = layer(HarnessFeatureStatus.supported()),
      serverPolicy = layer(HarnessFeatureStatus.supported())
    )

    assertEquals(HarnessFeatureState.SUPPORTED, result.status(feature).state)
    assertTrue(result.supports(feature))
  }

  @Test fun unsupportedAndBlockedAuthoritiesWinOverUnknownOrSupported() {
    val unsupported = HarnessCapabilities.intersect(
      layer(HarnessFeatureStatus.supported()),
      layer(HarnessFeatureStatus.unsupported("carrier has no route")),
      layer(HarnessFeatureStatus.unknown("scan pending")),
      layer(HarnessFeatureStatus.supported())
    ).status(feature)
    assertEquals(HarnessFeatureState.UNSUPPORTED, unsupported.state)
    assertTrue(unsupported.reason.orEmpty().contains("carrier has no route"))

    val blocked = HarnessCapabilities.intersect(
      layer(HarnessFeatureStatus.supported()),
      layer(HarnessFeatureStatus.unsupported("carrier has no route")),
      layer(HarnessFeatureStatus.supported()),
      layer(HarnessFeatureStatus.blocked("policy denies mutation"))
    ).status(feature)
    assertEquals(HarnessFeatureState.BLOCKED, blocked.state)
    assertTrue(blocked.reason.orEmpty().contains("policy denies mutation"))
    assertTrue(blocked.reason.orEmpty().contains("carrier has no route"))
  }

  @Test fun omittedAuthorityPropagatesUnknownInsteadOfBecomingUnsupported() {
    val result = HarnessCapabilities.intersect(
      protocol = HarnessCapabilityLayer.of(feature to HarnessFeatureStatus.supported()),
      carrier = HarnessCapabilityLayer(),
      discovery = HarnessCapabilityLayer.of(feature to HarnessFeatureStatus.supported()),
      serverPolicy = HarnessCapabilityLayer.of(feature to HarnessFeatureStatus.supported())
    )

    assertEquals(HarnessFeatureState.UNKNOWN, result.status(feature).state)
    assertFalse(result.supports(feature))
    assertTrue(result.status(feature).reason.orEmpty().contains("carrier"))
  }

  @Test fun endpointRoutesStayDistinctWhileFeatureIdentityRemainsRouteIndependent() {
    val protocol = HarnessCapabilityLayer(
      features = mapOf(feature to HarnessFeatureStatus.supported()),
      routes = mapOf(feature to setOf(renamePatch, renamePost))
    )
    val result = HarnessCapabilities.intersect(
      protocol, layer(HarnessFeatureStatus.supported()), layer(HarnessFeatureStatus.supported()),
      layer(HarnessFeatureStatus.supported())
    )

    assertEquals(HarnessFeatureState.SUPPORTED, result.status(feature).state)
    assertEquals(setOf(renamePatch, renamePost), result.routesFor(feature))
    assertNotEquals(renamePatch, renamePost)
    assertEquals("PATCH", renamePatch.normalizedMethod)
    assertEquals("/api/session/{id}", renamePatch.normalizedPath)
  }

  @Test fun staleObservationDoesNotBecomeSupportedAfterDiscoveryExpires() {
    val observation = HarnessDiscoveryStatus(
      installed = DiscoveryValue.YES,
      configured = DiscoveryValue.YES,
      listening = DiscoveryValue.YES,
      authenticated = DiscoveryValue.YES,
      compatible = DiscoveryValue.YES,
      available = DiscoveryValue.YES,
      observedAtMs = 100,
      freshUntilMs = 200,
      completeness = DiscoveryCompleteness.COMPLETE
    )

    assertEquals(
      HarnessFeatureState.SUPPORTED,
      HarnessCapabilities.fromDiscovery(observation, listOf(feature), nowMs = 150).status(feature).state
    )
    assertEquals(
      HarnessFeatureState.UNKNOWN,
      HarnessCapabilities.fromDiscovery(observation, listOf(feature), nowMs = 201).status(feature).state
    )
  }

  @Test fun bindingSnapshotEqualityAndStalenessUseAllGenerationsAndBindingId() {
    val first = HarnessOperationBindingSnapshot("binding", 7, 11, 13)
    val same = HarnessOperationBindingSnapshot("binding", 7, 11, 13)
    assertEquals(first, same)
    assertTrue(first.sameGenerations(same))
    assertTrue(first.matches(same))
    assertFalse(first.isStaleAgainst(same))

    listOf(
      first.copy(bindingId = "other"),
      first.copy(sourceRevision = 8),
      first.copy(hostGeneration = 12),
      first.copy(carrierGeneration = 14)
    ).forEach { stale ->
      assertFalse(first.matches(stale))
      assertTrue(first.isStaleAgainst(stale))
    }
    // Policy is not a generation: changing retry handling does not make the route stale.
    val policyOnly = first.copy(unknownDeliveryPolicy = HarnessUnknownDeliveryPolicy.NEVER_RETRY)
    assertTrue(first.sameGenerations(policyOnly))
    assertTrue(first.matches(policyOnly))
    assertNotEquals(first, policyOnly)
  }

  @Test fun unknownDeliveryPolicyRequiresReconciliationAndNeverBlindlyRetries() {
    val reconcile = HarnessOperationBindingSnapshot("binding", 1, 2, 3)
    assertTrue(reconcile.requiresReconciliationAfterUnknown())
    assertFalse(reconcile.allowsRetryAfterUnknown(idempotent = true))

    val never = reconcile.copy(unknownDeliveryPolicy = HarnessUnknownDeliveryPolicy.NEVER_RETRY)
    assertFalse(never.requiresReconciliationAfterUnknown())
    assertFalse(never.allowsRetryAfterUnknown(idempotent = true))

    val idempotent = reconcile.copy(unknownDeliveryPolicy = HarnessUnknownDeliveryPolicy.RETRY_IF_IDEMPOTENT)
    assertFalse(idempotent.allowsRetryAfterUnknown(idempotent = false))
    assertTrue(idempotent.allowsRetryAfterUnknown(idempotent = true))
  }

  @Test fun missingFeatureRemainsUnknownWhenQueriedDirectly() {
    val layer = HarnessCapabilityLayer()
    assertEquals(HarnessFeatureState.UNKNOWN, layer.status(HarnessFeature.TERMINAL).state)
    assertNull(layer.routesFor(HarnessFeature.TERMINAL).takeIf { it.isNotEmpty() })
  }
}
