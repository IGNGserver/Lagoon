package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.Normalizer
import java.util.Locale

/**
 * 本机设备分组。只持久化 [deviceIds]（[SshDevice.id]），运行时按设备当前挂载的 Harness 动态展开：
 * 给组内设备新增或移除 Harness 时分组自动生效，删掉最后一条 Harness 也不会销毁设备组。
 */
data class DeviceGroup(
  val id: String,
  val name: String,
  val deviceIds: List<String> = emptyList(),
  val updatedAt: Long = 0,
  /** 仅升级迁移读取 v2/v3 旧文档时暂存旧 leaf 列表；v4 写盘时不再输出。 */
  val legacyMemberIds: List<String> = emptyList()
) {
  /** 动态展开该组下所有设备的 Harness ID。 */
  @JvmName("memberHarnessIdsFromDevices")
  fun memberHarnessIds(devices: List<SshDevice>): List<String> {
    if (deviceIds.isEmpty()) return emptyList()
    val byId = devices.associateBy { it.id }
    return deviceIds.distinct().flatMap { id -> byId[id]?.harnesses?.map { it.id }.orEmpty() }.distinct()
  }

  /** 从已投影的 [ServerProfile] 列表动态展开该组的 Harness ID。 */
  fun memberHarnessIds(profiles: List<ServerProfile>): List<String> {
    if (deviceIds.isEmpty()) return emptyList()
    val deviceSet = deviceIds.toSet()
    val byDevice = profiles.filter { it.deviceId != null && it.deviceId in deviceSet }.groupBy { it.deviceId!! }
    return deviceIds.distinct().flatMap { id -> byDevice[id].orEmpty().map { it.id } }.distinct()
  }

  fun memberProfiles(profiles: List<ServerProfile>): List<ServerProfile> {
    if (deviceIds.isEmpty()) return emptyList()
    val byDevice = profiles.filter { it.deviceId != null }.groupBy { it.deviceId!! }
    return deviceIds.distinct().flatMap { id -> byDevice[id].orEmpty() }.distinctBy { it.id }
  }
}

/** 统一连接类型展示标签（例如 "DeepSeek Harness · SSH"）。 */
val ServerProfile.connectionTypeLabel: String
  get() = "${protocol.label} · SSH"

/** 统一连接端点简要描述，例如 "DeepSeek Harness · dev@10.0.0.5:22" */
val ServerProfile.endpointSummary: String
  get() = "${protocol.label} · ${url.removePrefix("ssh://")}"

data class DeviceGroupState(
  val groups: List<DeviceGroup> = emptyList(),
  val selectedGroupId: String? = null
) {
  /** 包含该设备的第一个分组。 */
  fun groupOfDevice(deviceId: String): DeviceGroup? = groups.firstOrNull { deviceId in it.deviceIds }

  /** 包含该 Harness 所属设备的第一个分组。 */
  fun groupOf(profile: ServerProfile): DeviceGroup? = profile.deviceId?.let(::groupOfDevice)

  /**
   * 清理重复/已删除的设备 ID、空分组和重复组 ID。
   * 只要组里仍有已保存的 SSH 设备，即使设备当前没有挂载任何 Harness 也会完整保留。
   */
  fun sanitized(validDeviceIds: Set<String>): DeviceGroupState {
    val seenGroups = HashSet<String>()
    val clean = ArrayList<DeviceGroup>(groups.size)
    for (group in groups) {
      if (group.id.isBlank() || group.id.length > 128 || group.name.isBlank() || !seenGroups.add(group.id)) continue
      val devices = group.deviceIds.filter { it in validDeviceIds }.distinct()
      if (devices.isEmpty()) continue
      clean += group.copy(name = group.name.trim().take(128), deviceIds = devices, legacyMemberIds = emptyList())
    }
    val selected = selectedGroupId?.takeIf { id -> clean.any { it.id == id } }
    return DeviceGroupState(clean, selected)
  }

  /** 移除一台已删除的设备；仅当某组不再含任何设备时才移除该组。 */
  fun withoutDevice(deviceId: String): DeviceGroupState = copy(
    groups = groups.mapNotNull { group ->
      if (deviceId !in group.deviceIds) group
      else group.copy(deviceIds = group.deviceIds.filterNot { it == deviceId })
        .takeIf { it.deviceIds.isNotEmpty() }
    }
  ).let { next ->
    next.copy(selectedGroupId = next.selectedGroupId?.takeIf { id -> next.groups.any { it.id == id } })
  }

  /** 首页顶栏 / 范围选择器用的分组标签，按设备动态展开出已投影的 Harness。 */
  fun tabs(profiles: List<ServerProfile>): List<DeviceTab> {
    val result = ArrayList<DeviceTab>(groups.size)
    groups.forEach { group ->
      val members = group.memberProfiles(profiles)
      if (members.isNotEmpty()) result += DeviceTab("group:${group.id}", group.name, group, members)
    }
    return result
  }

  /** 未归入任何分组的设备，保持原顺序。 */
  fun ungroupedDevices(devices: List<SshDevice>): List<SshDevice> {
    val grouped = groups.flatMapTo(HashSet()) { it.deviceIds }
    return devices.filter { it.id !in grouped }
  }
}

/** One top-bar entry for an explicit group. */
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
  sessions.filterNot { it.blank }.forEach { session ->
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
  const val VERSION = 4

  fun encode(state: DeviceGroupState): String = JSONObject()
    .put("version", VERSION)
    .put("selected", state.selectedGroupId ?: JSONObject.NULL)
    .put("groups", JSONArray().apply {
      state.groups.forEach { group ->
        put(JSONObject()
          .put("id", group.id)
          .put("name", group.name)
          .put("updatedAt", group.updatedAt)
          .put("devices", JSONArray(group.deviceIds)))
      }
    }).toString()

  /** The stored schema version, or null when the text is not a device-group document. */
  fun version(text: String): Int? = runCatching { JSONObject(text).optInt("version", 0) }.getOrNull()

  fun decode(text: String): DeviceGroupState {
    try {
      val json = JSONObject(text)
      val version = json.optInt("version")
      if (version !in setOf(2, 3, VERSION)) throw IOException("设备分组版本无效")
      val rawGroups = json.optJSONArray("groups") ?: JSONArray()
      if (rawGroups.length() > 64) throw IOException("设备分组过多")
      val groups = (0 until rawGroups.length()).mapNotNull { index ->
        val item = rawGroups.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id").takeIf { it.isNotBlank() && it.length <= 128 } ?: return@mapNotNull null
        val name = item.optString("name").takeIf { it.isNotBlank() && it.length <= 128 } ?: return@mapNotNull null
        val devices = item.optJSONArray("devices")
        val deviceIds = devices?.let { array ->
          (0 until minOf(array.length(), 64)).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.distinct()
        }.orEmpty()
        val legacyMembers = if (version < VERSION) {
          val members = item.optJSONArray("members") ?: JSONArray()
          if (members.length() > 64) throw IOException("设备分组成员过多")
          (0 until members.length()).mapNotNull { members.optString(it).takeIf(String::isNotBlank) }.distinct()
        } else emptyList()
        DeviceGroup(
          id = id,
          name = name,
          deviceIds = deviceIds,
          updatedAt = item.optLong("updatedAt", 0),
          legacyMemberIds = legacyMembers
        )
      }
      val selected = json.opt("selected") as? String
      return DeviceGroupState(groups, selected?.takeIf { id -> groups.any { it.id == id } })
    } catch (error: IOException) {
      throw error
    } catch (error: Exception) {
      throw IOException("设备分组损坏", error)
    }
  }
}
