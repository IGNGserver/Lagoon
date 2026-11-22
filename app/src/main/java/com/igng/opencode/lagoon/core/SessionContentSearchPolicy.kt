package com.igng.opencode.lagoon.core

/** One explicit Host, independent of the local project filter and current conversation. */
internal data class SessionContentSearchTarget(val profile: ServerProfile) {
  fun matches(state: LagoonState): Boolean = state.server?.sameRoute(profile) == true
}

internal fun LagoonState.canSearchSessionContent(): Boolean = connected && !cached && !degraded &&
  !multiServerView && server?.effectiveEnabled == true && capabilities.sessionContentSearch

/** Issued by the controller. A hit cannot be reinterpreted on a replacement connection. */
internal class SessionContentSearchPage internal constructor(
  val target: SessionContentSearchTarget,
  val result: SessionContentSearchResult,
  internal val client: HarnessClient,
  internal val generation: Int
)
