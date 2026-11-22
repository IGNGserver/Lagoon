package com.igng.opencode.lagoon.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Host scope, not the currently selected conversation or a same-named device group. */
internal data class WorkspaceManagementTarget(val profile: ServerProfile) {
  fun matches(state: LagoonState): Boolean = state.server?.sameRoute(profile) == true
}

internal fun LagoonState.canManageServerWorkspaces(): Boolean = connected && !cached && !degraded &&
  !multiServerView && server?.effectiveEnabled == true &&
  server?.protocol == HarnessProtocol.DSH && capabilities.workspaceManagement

/** Serialize Host mutations with their reconciliation reads. An unknown write is never retried here. */
internal class WorkspaceManagementGate {
  private val mutex = Mutex()
  private val uncertain = mutableListOf<ServerProfile>()
  suspend fun <T> read(profile: ServerProfile, block: suspend () -> T): T = mutex.withLock {
    block().also { uncertain.removeAll { it.sameRoute(profile) } }
  }
  suspend fun <T> write(profile: ServerProfile, block: suspend () -> T): T = mutex.withLock {
    check(uncertain.none { it.sameRoute(profile) }) { "上次工作区写入结果未确认，请先刷新 Host 登记核对；没有自动重试" }
    try { block() } catch (error: Throwable) {
      uncertain += profile
      throw error
    }
  }
}
