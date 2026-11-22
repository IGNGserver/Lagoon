package com.igng.opencode.lagoon.ui

/**
 * Pages of the app. There is no tab bar: 会话 is the only root, and 设置 is pushed over it
 * from its ⋯ menu like any other page.
 *
 * 信息架构主线：SSH 设备 → Harness → 项目/目录 → 会话 → 对话。设备是用户操作的单位，Harness 只是设备上的
 * 一个能力（见 `docs/DEVICE_MODEL.md`）。
 */
internal enum class RootPage(val label: String) {
  HOME("会话"),
  DEVICES("设备"),
  SETTINGS("设置")
}

/**
 * SSH 设备整页编辑器的一层。
 *
 * 设备子树里的每一级都是路由上的一层，所以「列表 → 设备 → 编辑表单」与首页进设备页
 * 走的是同一套整页过渡，返回手势也能逐级预览上一页：
 * - [deviceId] 为 null 或空串表示新建 SSH 设备；
 * - [deviceId] 非空表示编辑对应 [com.igng.opencode.lagoon.core.SshDevice.id]。
 */
internal data class DeviceEditorRoute(
  val deviceId: String? = null,
  /**
   * 本次打开编辑器的实例号。[key] 带上它，saveable 作用域才只属于这一次打开；否则两次
   * “添加 SSH 设备”的 key 完全相同，上一次的地址、密钥选择和已固定指纹会被恢复进新表单。
   * 旋转/进程重建时随路由一起编解码，所以同一个实例的草稿仍然会保留。
   */
  val instance: String = ""
) {
  val isNew: Boolean get() = deviceId.isNullOrBlank()

  /** 页面身份：整页过渡的 contentKey 与 saveable 作用域都靠它区分两个相邻的编辑器页。 */
  val key: String
    get() = buildString {
      append("ssh:").append(deviceId.orEmpty())
      if (instance.isNotEmpty()) append('|').append(instance)
    }
}

/** 给一次新打开盖章；已经带实例号（例如从 back stack 恢复）的路由原样返回。 */
internal fun DeviceEditorRoute.freshInstance(): DeviceEditorRoute =
  if (instance.isNotEmpty()) this else copy(instance = java.util.UUID.randomUUID().toString())

/**
 * The single in-app back stack shared by the UI and its unit tests.
 *
 * Order: chat (and the children opened from it) → the page it was opened from → 会话 → system back
 * (leave the app). 设备详情与 Harness 详情是「设备」页之上的两层，编辑器再压在最上面；
 * 返回时先退编辑器、再退 Harness、再退设备。Keeping the transitions pure makes the "侧滑只回上一级、
 * 不回桌面" contract verifiable without a device.
 */
internal data class SessionNavigation(
  val page: RootPage = RootPage.HOME,
  val sessions: List<String> = emptyList(),
  val parentPages: List<RootPage> = emptyList(),
  /** 设备详情：`SshDevice.id`，不是任何一条 Harness。 */
  val device: String? = null,
  /** Harness 详情：`DeviceHarness.id` / `ServerProfile.id`，挂在设备详情之上。 */
  val harness: String? = null,
  /** 整页 SSH 设备编辑器栈（新建或编辑单台 SSH 设备）。 */
  val editors: List<DeviceEditorRoute> = emptyList()
) {
  val canGoBack get() = sessions.isNotEmpty() || parentPages.isNotEmpty() || page != RootPage.HOME ||
    device != null || harness != null || editors.isNotEmpty()

  val route get() = NavRoute(
    page = page,
    session = sessions.lastOrNull(),
    depth = sessions.size + parentPages.size + (if (page == RootPage.HOME) 0 else 1) +
      (if (device != null) 1 else 0) + (if (harness != null) 1 else 0) + editors.size,
    device = device,
    harness = harness,
    editors = editors
  )

  fun open(id: String, child: Boolean = false) = copy(
    sessions = if (child && sessions.isNotEmpty()) sessions.takeWhile { it != id } + id else listOf(id),
    device = null,
    harness = null,
    editors = emptyList()
  )
  /** Replace the chat stack with an authoritative parent chain (siblings share the same depth). */
  fun openLineage(lineage: List<String>) = copy(
    sessions = lineage.filter(String::isNotBlank),
    device = null,
    harness = null,
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
  fun openDevice(deviceId: String) = copy(device = deviceId, harness = null, editors = emptyList())
  /** 推入 Harness 详情；它永远在设备详情之上。 */
  fun openHarness(leafId: String) = copy(harness = leafId, editors = emptyList())
  /** 推入一层整页编辑器。 */
  fun openEditor(route: DeviceEditorRoute) = copy(editors = editors + route)
  fun back() = when {
    editors.isNotEmpty() -> copy(editors = editors.dropLast(1))
    harness != null -> copy(harness = null)
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
 * What the root AnimatedContent shows: a page, a device detail, a harness detail or editor on top of it,
 * or a chat [depth] levels above 会话. Every full-screen surface is one of these routes — that is what
 * makes 设备管理 subtree animate exactly like the rest of the app.
 */
internal data class NavRoute(
  val page: RootPage,
  val session: String?,
  val depth: Int = if (session == null) 0 else 1,
  val serverId: String? = null,
  val device: String? = null,
  val harness: String? = null,
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
 * 编辑器层的落盘编码（旋转、重建后仍停在同一页）。每层是 `ssh:<deviceId>|<instance>`，空 deviceId 表示新建。
 * 兼容读取升级前留下的旧版 4/5 段格式（忽略已下线的直连资料页，SSH 项照常恢复）。
 */
internal fun encodeDeviceEditors(editors: List<DeviceEditorRoute>): ArrayList<String> =
  ArrayList(editors.map { route ->
    "ssh:${route.deviceId.orEmpty()}|${route.instance}"
  })

internal fun decodeDeviceEditors(raw: List<String>?): List<DeviceEditorRoute> = raw.orEmpty().mapNotNull { line ->
  val parts = line.split("|")
  when (parts.size) {
    2 -> {
      val id = sshDeviceOf(parts[0]) ?: return@mapNotNull null
      DeviceEditorRoute(deviceId = id.ifBlank { null }, instance = parts[1])
    }
    5, 4 -> {
      val mode = parts[0]
      val instance = parts.getOrNull(4).orEmpty()
      when (mode) {
        "SSH_HOST" -> DeviceEditorRoute(deviceId = sshDeviceOf(parts[3])?.ifBlank { null }, instance = instance)
        "ADD_DEVICE" -> DeviceEditorRoute(deviceId = null, instance = instance)
        else -> null
      }
    }
    3 -> {
      val id = sshDeviceOf(parts[2])
      if (id != null) DeviceEditorRoute(deviceId = id.ifBlank { null }) else null
    }
    else -> null
  }
}

private fun sshDeviceOf(segment: String): String? =
  segment.removePrefix("ssh:").takeIf { segment.startsWith("ssh:") }

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
