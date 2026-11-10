package com.igng.opencode.lagoon.core

import org.json.JSONObject
import java.io.IOException

/** An authenticated Host's advertised capabilities, not a list inferred from version strings. */
internal data class RemoteWorkspaceType(val id: String, val name: String, val capability: String, val available: Boolean)

internal data class RemoteCapabilities(val capabilities: Set<String>, val workspaceTypes: List<RemoteWorkspaceType>?) {
  val supportsWorkspaceUpload: Boolean get() = "workspace.upload.v1" in capabilities
  val hasNativeServices: Boolean get() = "harness.services.v1" in capabilities

  val sessionFormat: Int? get() = when {
    "harness.remote.v3" in capabilities -> 3
    "harness.remote.v1" in capabilities -> 1
    else -> null
  }

  /** A workspace type is usable only when both capability and readiness agree. */
  fun supportsWorkspace(id: String, capability: String): Boolean {
    if (capability !in capabilities) return false
    val types = workspaceTypes ?: return true
    val matching = types.filter { it.id == id }
    return matching.size == 1 && matching.single().capability == capability && matching.single().available
  }

  companion object {
    fun parse(body: JSONObject): RemoteCapabilities {
      val raw = body.optJSONArray("capabilities") ?: throw IOException("Host 未提供 Remote 能力列表")
      if (raw.length() > 128) throw IOException("Host Remote 能力列表超过上限")
      val capabilities = (0 until raw.length()).map { index ->
        (raw.opt(index) as? String)?.takeIf { it.isNotBlank() && it.length <= 256 }
          ?: throw IOException("Host Remote 能力列表无效")
      }
      if (capabilities.distinct().size != capabilities.size) throw IOException("Host Remote 能力重复")
      val set = capabilities.toSet()
      val v1 = "harness.remote.v1" in set
      val v3 = "harness.remote.v3" in set
      if (v1 && v3) throw IOException("Host 提供了冲突的 Typert Remote 版本")
      val workspaceTypes = if (!body.has("workspaceTypes")) null else {
        // Match upstream parseRemoteWorkspaceTypes: an invalid container or oversized list is
        // present-but-empty (never fall back to capability-only availability); malformed entries
        // are skipped while valid unrelated backends remain usable.
        val types = body.optJSONArray("workspaceTypes")
        if (types == null || types.length() > 32) emptyList() else (0 until types.length()).mapNotNull { index ->
          val item = types.optJSONObject(index) ?: return@mapNotNull null
          fun field(name: String): String? = (item.opt(name) as? String)?.takeIf { it.isNotEmpty() }
          val id = field("id") ?: return@mapNotNull null
          val name = field("name") ?: return@mapNotNull null
          val capability = field("capability") ?: return@mapNotNull null
          val available = item.opt("available") as? Boolean ?: return@mapNotNull null
          RemoteWorkspaceType(id, name, capability, available)
        }
      }
      val result = RemoteCapabilities(set, workspaceTypes)
      if (!v1 && !v3 && !result.hasNativeServices && listOf(
          "codex" to "codex.appserver.v1", "cursor" to "agent.acp.cursor.v1",
          "antigravity" to "agent.acp.antigravity.v1", "pi" to "agent.acp.pi.v1",
          "opencode" to "agent.acp.opencode.v1", "claude" to "agent.acp.claude.v1",
          "hermes" to "agent.acp.hermes.v1"
        ).none { (id, capability) -> result.supportsWorkspace(id, capability) })
        throw IOException("Host 未提供受支持的 Remote 后端")
      return result
    }
  }
}
