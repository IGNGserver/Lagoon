package com.igng.opencode.lagoon.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class OpenCodeManagementTarget(val profile: ServerProfile) {
  fun matches(state: LagoonState): Boolean = state.server?.sameRoute(profile) == true
}

internal fun LagoonState.canManageOpenCodeProjects(): Boolean = connected && !cached && !degraded && !multiServerView &&
  server?.effectiveEnabled == true && server?.protocol == HarnessProtocol.OPENCODE_NATIVE &&
  (capabilities.openCodeProjectManagement || capabilities.openCodeWorktreeManagement)

/** Project metadata reads alone cannot reconcile a possibly-created or removed worktree. */
internal class OpenCodeManagementGate {
  private data class Unknown(val profile: ServerProfile, val project: String, val inventory: Boolean)
  private val mutex = Mutex()
  private val unknown = mutableListOf<Unknown>()
  suspend fun <T> catalog(profile: ServerProfile, block: suspend () -> T): T = mutex.withLock {
    block().also { unknown.removeAll { !it.inventory && it.profile.sameRoute(profile) } }
  }
  suspend fun <T> inventory(profile: ServerProfile, project: String, block: suspend () -> T): T = mutex.withLock {
    block().also { unknown.removeAll { it.project == project && it.profile.sameRoute(profile) } }
  }
  suspend fun <T> write(profile: ServerProfile, project: String, inventory: Boolean, block: suspend () -> T): T = mutex.withLock {
    check(unknown.none { it.project == project && it.profile.sameRoute(profile) }) {
      "此项目上次操作结果未确认，请先只读核对项目和 worktree 清单；没有自动重试"
    }
    try { block() } catch (error: Throwable) {
      unknown += Unknown(profile, project, inventory)
      throw error
    }
  }
}
