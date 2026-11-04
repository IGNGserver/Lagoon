package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.Normalizer
import java.util.Locale

/**
 * A user-created device group. It only describes how this installation presents several saved
 * servers; it is never uploaded and does not affect authentication, routing or session identity.
 *
 * A profile belongs to at most one group. Group order is the persistence order; member order is
 * the order chosen in the editor. Profiles that are not in any group render as singleton tabs.
 */
data class DeviceGroup(
  val id: String,
  val name: String,
  val memberIds: List<String> = emptyList(),
  val updatedAt: Long = 0
)

data class DeviceGroupState(
  val groups: List<DeviceGroup> = emptyList(),
  val selectedGroupId: String? = null
) {
  fun groupOf(profileId: String): DeviceGroup? = groups.firstOrNull { profileId in it.memberIds }

  /** Removes duplicate/unknown members, empty groups and duplicate ids without throwing. */
  fun sanitized(profileIds: Set<String>): DeviceGroupState {
    val seenGroups = HashSet<String>()
    val seenProfiles = HashSet<String>()
    val clean = ArrayList<DeviceGroup>(groups.size)
    for (group in groups) {
      if (group.id.isBlank() || group.id.length > 128 || group.name.isBlank() || !seenGroups.add(group.id)) continue
      val members = group.memberIds.filter { it in profileIds && seenProfiles.add(it) }
      if (members.isEmpty()) continue
      clean += group.copy(name = group.name.trim().take(128), memberIds = members)
    }
    val selected = selectedGroupId?.takeIf { id -> clean.any { it.id == id } }
    return DeviceGroupState(clean, selected)
  }

  /** All groups still containing this profile after removal. Empty groups are dropped. */
  fun withoutProfile(profileId: String): DeviceGroupState = copy(
    groups = groups.mapNotNull { group ->
      if (profileId !in group.memberIds) group
      else group.copy(memberIds = group.memberIds.filterNot { it == profileId }).takeIf { it.memberIds.isNotEmpty() }
    }
  ).let { next ->
    next.copy(selectedGroupId = next.selectedGroupId?.takeIf { id -> next.groups.any { it.id == id } })
  }

  fun tabs(profiles: List<ServerProfile>): List<DeviceTab> {
    val byId = profiles.associateBy { it.id }
    val used = HashSet<String>()
    val result = ArrayList<DeviceTab>(groups.size + profiles.size)
    groups.forEach { group ->
      val members = group.memberIds.mapNotNull { byId[it] }.filter { used.add(it.id) }
      if (members.isNotEmpty()) result += DeviceTab("group:${group.id}", group.name, group, members)
    }
    profiles.filter { it.id !in used }.forEach { profile ->
      result += DeviceTab("profile:${profile.id}", profile.name, null, listOf(profile))
    }
    return result
  }
}

/** One top-bar entry: an explicit group, or an ungrouped profile rendered as a singleton. */
data class DeviceTab(
  val id: String,
  val label: String,
  val group: DeviceGroup?,
  val profiles: List<ServerProfile>
) {
  val isGroup: Boolean get() = group != null
  val primaryProfile: ServerProfile? get() = profiles.firstOrNull()
}

/** One visible workspace after merging same-named projects of the members of a device group. */
data class DeviceWorkspace(
  val key: String,
  val name: String,
  val members: List<Project>,
  val sessions: List<Session>
) {
  val latest: Long get() = sessions.maxOfOrNull { it.activityAt } ?: 0
}

fun normalizedWorkspaceName(name: String): String =
  Normalizer.normalize(name, Normalizer.Form.NFKC)
    .trim()
    .replace(Regex("\\s+"), " ")
    .lowercase(Locale.ROOT)

private fun workspaceDisplayName(project: Project?): String =
  project?.name?.takeIf { it.isNotBlank() }
    ?: project?.directory?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    ?: "未归入项目"

private fun workspaceKey(scope: String, project: Project?, fallback: String): String {
  val normalized = normalizedWorkspaceName(workspaceDisplayName(project))
  val id = normalized.ifBlank { fallback.ifBlank { "unassigned" } }
  return "$scope\u0000$id"
}

/**
 * Projects and sessions of [scope] grouped by normalized workspace name. Same name on two harnesses
 * merges into one workspace; [members] keeps the server-scoped project records so a new session can
 * still choose the exact harness/directory.
 */
fun deviceWorkspaces(
  sessions: List<Session>,
  projects: List<Project>,
  scope: String
): List<DeviceWorkspace> {
  data class Acc(var name: String, val members: MutableList<Project> = mutableListOf(), val sessions: MutableList<Session> = mutableListOf())
  val byKey = LinkedHashMap<String, Acc>()
  fun acc(project: Project?): Acc {
    val key = workspaceKey(scope, project, project?.id.orEmpty())
    return byKey.getOrPut(key) { Acc(workspaceDisplayName(project)) }
  }
  projects.forEach { project ->
    val entry = acc(project)
    if (entry.members.none { it.serverId == project.serverId && it.id == project.id && it.directory == project.directory }) {
      entry.members += project
    }
  }
  sessions.forEach { session ->
    val project = resolveSessionProject(session, projects)
    val entry = acc(project)
    entry.sessions += session
  }
  return byKey.map { (key, value) ->
    DeviceWorkspace(
      key = key,
      name = value.name,
      members = value.members.distinctBy { Triple(it.serverId, it.id, it.directory) },
      sessions = value.sessions.distinctBy { it.key }.sortedWith(sessionActivityOrder)
    )
  }.sortedWith(compareByDescending<DeviceWorkspace> { it.latest }.thenBy { it.name })
}

/** Key of the workspace a session belongs to, using the same normalization as [deviceWorkspaces]. */
fun deviceWorkspaceKey(session: Session, projects: List<Project>, scope: String): String {
  val project = resolveSessionProject(session, projects)
  return workspaceKey(scope, project, session.directory.ifBlank { "unassigned" })
}

internal object DeviceGroupCodec {
  fun encode(state: DeviceGroupState): String = JSONObject()
    .put("version", 1)
    .put("selected", state.selectedGroupId ?: JSONObject.NULL)
    .put("groups", JSONArray().apply {
      state.groups.forEach { group ->
        put(JSONObject()
          .put("id", group.id)
          .put("name", group.name)
          .put("updatedAt", group.updatedAt)
          .put("members", JSONArray(group.memberIds)))
      }
    }).toString()

  fun decode(text: String): DeviceGroupState {
    try {
      val json = JSONObject(text)
      if (json.optInt("version") != 1) throw IOException("设备分组版本无效")
      val rawGroups = json.optJSONArray("groups") ?: JSONArray()
      if (rawGroups.length() > 64) throw IOException("设备分组过多")
      val groups = (0 until rawGroups.length()).mapNotNull { index ->
        val item = rawGroups.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id").takeIf { it.isNotBlank() && it.length <= 128 } ?: return@mapNotNull null
        val name = item.optString("name").takeIf { it.isNotBlank() && it.length <= 128 } ?: return@mapNotNull null
        val members = item.optJSONArray("members") ?: JSONArray()
        if (members.length() > 64) throw IOException("设备分组成员过多")
        val ids = (0 until members.length()).mapNotNull { members.optString(it).takeIf(String::isNotBlank) }.distinct()
        DeviceGroup(id, name, ids, item.optLong("updatedAt", 0))
      }
      val selected = json.opt("selected") as? String
      // Keep members whose profiles are not loaded yet; the controller sanitizes again with the
      // current profile ids before publishing state.
      return DeviceGroupState(groups, selected?.takeIf { id -> groups.any { it.id == id } })
    } catch (error: IOException) {
      throw error
    } catch (error: Exception) {
      throw IOException("设备分组损坏", error)
    }
  }
}
