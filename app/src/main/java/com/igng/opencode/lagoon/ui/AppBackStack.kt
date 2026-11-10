package com.igng.opencode.lagoon.ui

/**
 * Pages of the app. There is no tab bar: 会话 is the only root, and 设置 is pushed over it
 * from its ⋯ menu like any other page.
 *
 * 信息架构主线：Server → Project/Directory → Session → Conversation。
 */
internal enum class RootPage(val label: String) {
  HOME("会话"),
  DEVICES("设备"),
  SETTINGS("设置")
}

/** Direct compatibility entry and host-based Hub/SSH sources. Enum keys retain saved-route identity. */
internal enum class AddDeviceTab(val label: String) {
  OPENCODE("OpenCode 直连"),
  DSH("中枢与 SSH")
}

/**
 * 设备整页编辑器的一层。
 *
 * 设备子树里的每一级都是路由上的一层，所以「列表 → 详情 → 编辑 → SSH 表单」与首页进设备页
 * 走的是同一套整页过渡，返回手势也能逐级预览上一页：
 * - [profileId] 非空：编辑已有连接；OpenCode 直连、旧版直连 DSH、DSH 远程中枢共用同一个表单；
 * - [sshHostId] 非空：SSH 主机表单，空串表示新建（与 `ServerProfile.sshHostId` 的约定一致）；
 * - 两者都空：添加设备的入口页，[tab] 决定先落在哪一段。
 */
internal data class DeviceEditorRoute(
  val tab: AddDeviceTab = AddDeviceTab.OPENCODE,
  val profileId: String? = null,
  val sshHostId: String? = null
) {
  /** 页面身份：整页过渡的 contentKey 与 saveable 作用域都靠它区分两个相邻的编辑器页。 */
  val key get() = "${tab.name}|${profileId.orEmpty()}|${sshHostId?.let { "ssh:$it" } ?: ""}"
}

/**
 * The single in-app back stack shared by the UI and its unit tests.
 *
 * Order: chat (and the children opened from it) → the page it was opened from → 会话 → system back
 * (leave the app). 设备详情与设备编辑器是「设备」页之上的两层，返回时先退编辑器、再退详情。
 * Keeping the transitions pure makes the "侧滑只回上一级、不回桌面" contract verifiable without a device.
 */
internal data class SessionNavigation(
  val page: RootPage = RootPage.HOME,
  val sessions: List<String> = emptyList(),
  val parentPages: List<RootPage> = emptyList(),
  /** 设备详情：挂在「设备」页上的下一级。 */
  val device: String? = null,
  /** 整页编辑器栈：添加设备入口 + 可选的一层 SSH 主机表单。 */
  val editors: List<DeviceEditorRoute> = emptyList()
) {
  val canGoBack get() = sessions.isNotEmpty() || parentPages.isNotEmpty() || page != RootPage.HOME ||
    device != null || editors.isNotEmpty()

  val route get() = NavRoute(
    page = page,
    session = sessions.lastOrNull(),
    depth = sessions.size + parentPages.size + (if (page == RootPage.HOME) 0 else 1) +
      (if (device != null) 1 else 0) + editors.size,
    device = device,
    editors = editors
  )

  fun open(id: String, child: Boolean = false) = copy(
    sessions = if (child && sessions.isNotEmpty()) sessions.takeWhile { it != id } + id else listOf(id),
    device = null,
    editors = emptyList()
  )
  /** Replace the chat stack with an authoritative parent chain (siblings share the same depth). */
  fun openLineage(lineage: List<String>) = copy(
    sessions = lineage.filter(String::isNotBlank),
    device = null,
    editors = emptyList()
  )
  /** Opening a page from the home ⋯ menu starts from 会话; opening DEVICES from SETTINGS remembers SETTINGS on the stack. */
  fun push(next: RootPage) = when {
    next == RootPage.HOME -> SessionNavigation()
    sessions.isEmpty() && page != RootPage.HOME && page != next ->
      SessionNavigation(next, parentPages = (parentPages + page).filter { it != RootPage.HOME && it != next })
    else -> SessionNavigation(next)
  }
  /** 推入设备详情；同一台设备再次进入不会叠栈。 */
  fun openDevice(id: String) = copy(device = id, editors = emptyList())
  /** 推入一层整页编辑器；添加入口之上的 SSH 表单是第二层，返回只退这一层。 */
  fun openEditor(route: DeviceEditorRoute) = copy(editors = editors + route)
  fun back() = when {
    editors.isNotEmpty() -> copy(editors = editors.dropLast(1))
    device != null -> copy(device = null)
    sessions.isNotEmpty() -> copy(sessions = sessions.dropLast(1))
    parentPages.isNotEmpty() -> copy(page = parentPages.last(), parentPages = parentPages.dropLast(1))
    else -> SessionNavigation()
  }
}

/** True when a back navigation leaves the outermost conversation for its originating page. */
internal fun leavesSession(from: SessionNavigation, to: SessionNavigation): Boolean =
  from.sessions.isNotEmpty() && to.sessions.isEmpty()

/** Route id of the blank "新会话" page; it becomes the real session id after the first send. */
internal const val DRAFT_SESSION = "\u0000draft"

/**
 * What the root AnimatedContent shows: a page, a device detail or editor on top of it, or a chat
 * [depth] levels above 会话. Every full-screen surface is one of these routes — that is what makes
 * 设备管理 subtree animate exactly like the rest of the app.
 */
internal data class NavRoute(
  val page: RootPage,
  val session: String?,
  val depth: Int = if (session == null) 0 else 1,
  val serverId: String? = null,
  val device: String? = null,
  val editors: List<DeviceEditorRoute> = emptyList()
) {
  /** 当前最上层的整页编辑器；null 表示这一页不是编辑器。 */
  val editor: DeviceEditorRoute? get() = editors.lastOrNull()
}

/**
 * Horizontal direction of a route change: 1 = the new page enters from the right, -1 = from the left.
 * Going deeper enters from the right and going back enters from the left, whatever the entry point
 * (menu, back gesture, notification).
 */
internal fun slideDirection(from: NavRoute, to: NavRoute): Int = if (to.depth < from.depth) -1 else 1

/**
 * 编辑器层的落盘编码（旋转、重建后仍停在同一页）。每层是 `tab|profileId|ssh:hostId`，空段表示 null。
 */
internal fun encodeDeviceEditors(editors: List<DeviceEditorRoute>): ArrayList<String> =
  ArrayList(editors.map { route ->
    listOf(
      route.tab.name,
      route.profileId.orEmpty(),
      route.sshHostId?.let { "ssh:$it" }.orEmpty()
    ).joinToString("|")
  })

internal fun decodeDeviceEditors(raw: List<String>?): List<DeviceEditorRoute> = raw.orEmpty().mapNotNull { line ->
  val parts = line.split("|")
  if (parts.size != 3) return@mapNotNull null
  val tab = runCatching { AddDeviceTab.valueOf(parts[0]) }.getOrNull() ?: return@mapNotNull null
  DeviceEditorRoute(
    tab = tab,
    profileId = parts[1].ifBlank { null },
    sshHostId = parts[2].removePrefix("ssh:").takeIf { parts[2].startsWith("ssh:") }
  )
}

/**
 * 预测式返回时页面跟随手指的方向：+1 表示内容向右移动（从左侧边缘滑入的返回手势），
 * -1 表示内容向左移动（从右侧边缘滑入）。[edge] 取 `BackEventCompat.EDGE_LEFT`(0) /
 * `EDGE_RIGHT`(1)；无法判定方向时用 [touchX] 相对屏幕中点兜底。
 */
internal fun backSwipeDirection(edge: Int, touchX: Float, width: Int): Int = when (edge) {
  1 -> -1
  0 -> 1
  else -> if (width > 0 && touchX > width / 2f) -1 else 1
}
