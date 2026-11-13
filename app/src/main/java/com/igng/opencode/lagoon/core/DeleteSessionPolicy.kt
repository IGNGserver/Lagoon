package com.igng.opencode.lagoon.core

/** Bind an irreversible confirmation to the original connection and session. */
internal data class DeleteSessionTarget(
  val profile: ServerProfile,
  val sessionId: String,
  val title: String,
  val requireSelected: Boolean = false
) {
  fun matches(state: LagoonState): Boolean = state.server?.let { current ->
    state.connected && state.capabilities.deleteSession && current.id == profile.id &&
      ConnectionRouteAdmission.sameLeafRoute(profile, current) &&
      (!requireSelected || state.sessionId == sessionId) && state.sessions.any { it.id == sessionId }
  } == true

  fun warning(): String = if (profile.protocol == HarnessProtocolId.CODEX_APP_SERVER) {
    "此会话及其启动的子代理会话将从服务器永久删除，无法恢复。已有分叉可能阻止删除。"
  } else "会话消息将从服务器永久删除。"
}
