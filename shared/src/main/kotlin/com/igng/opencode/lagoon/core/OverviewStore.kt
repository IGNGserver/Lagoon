package com.igng.opencode.lagoon.core

/**
 * The persistent state [AllServersOverview] reads and writes: credentials, the device-registered
 * project list, the task ledger and the unread-result ledger. Android implements it with
 * SharedPreferences ([ServerStore]).
 */
interface OverviewStore {
  /** Admit a fresh saved route before returning even a cached client; Android checks committed graph. */
  fun runtimeProfile(expected: ServerProfile): ServerProfile {
    check(expected.effectiveEnabled) { "连接或其所属接入源/主机已停用" }
    return expected
  }
  fun credentials(id: String): ServerCredentials
  /** Owner-issued cookie rotations are not new auth inputs; explicit username/password edits are. */
  fun requireCurrentAuthentication(id: String, captured: ServerCredentials) = Unit
  /** Persists a session cookie a client minted for [url]; ignored once the profile moved to another address. */
  fun rememberCookie(id: String, url: String, cookie: String)
  fun localProjects(id: String): List<Project>
  fun permissionPolicy(): PermissionPolicy
  fun refreshMode(): RefreshMode
  fun taskStates(id: String): Map<String, TaskState>
  fun taskParents(id: String): Map<String, String>
  fun rememberParents(id: String, sessions: List<Session>)
  fun rememberTask(id: String, state: TaskState, parent: String? = null, observedAt: Long = System.currentTimeMillis(), durable: Boolean = false): TaskState
  fun migrateTaskLedger(id: String, now: Long = System.currentTimeMillis())
  fun sessionNotices(server: String, now: Long = System.currentTimeMillis()): List<SessionNotice>
  fun rememberNotice(server: String, notice: SessionNotice, now: Long = System.currentTimeMillis()): List<SessionNotice>
  /** Retires task, parent and result ledgers without deleting local session drafts or queued input. */
  fun forgetTask(server: String, session: String)
  fun forgetSession(server: String, session: String)
}

/** The last catalog read of a server, painted while a cold monitor reads the live one. */
fun interface CatalogCache {
  fun catalog(serverId: String): Pair<List<Project>, List<Session>>?
}
