package com.igng.opencode.lagoon.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

private enum class OpenCodeProjectPage { LIST, DETAILS, EDIT, CREATE, REMOVE, REFRESH }
private fun ocAbsolute(path: String) = path.startsWith("/") || Regex("^[A-Za-z]:[\\\\/].*").matches(path) || path.startsWith("\\\\")
internal fun ocEdit(project: OpenCodeProjectInfo, values: List<String>) = OpenCodeProjectEdit(
  canonical = values[0].takeIf { it != project.canonical }, name = values[1].takeIf { it != project.name.orEmpty() },
  iconOverride = values[2].takeIf { it != project.iconOverride.orEmpty() }, iconColor = values[3].takeIf { it != project.iconColor.orEmpty() },
  startCommand = values[4].takeIf { it != project.startCommand.orEmpty() })
internal fun ocValues(project: OpenCodeProjectInfo) = listOf(project.canonical, project.name.orEmpty(), project.iconOverride.orEmpty(), project.iconColor.orEmpty(), project.startCommand.orEmpty())

/** Fixed Host target; reads never import local projects or invoke the mutating refresh endpoint. */
@Composable
internal fun OpenCodeProjectManagementPanel(state: LagoonState, controller: LagoonController, target: OpenCodeManagementTarget, onDismiss: () -> Unit) {
  val captured = remember { target }
  val scope = rememberCoroutineScope()
  val currentState by rememberUpdatedState(state)
  var projects by remember { mutableStateOf(emptyList<OpenCodeProjectInfo>()) }
  var selected by remember { mutableStateOf<OpenCodeProjectInfo?>(null) }
  var worktrees by remember { mutableStateOf(emptyList<OpenCodeWorktree>()) }
  var removing by remember { mutableStateOf<Pair<OpenCodeProjectInfo, OpenCodeWorktree>?>(null) }
  var actionProject by remember { mutableStateOf<OpenCodeProjectInfo?>(null) }
  var page by remember { mutableStateOf(OpenCodeProjectPage.LIST) }
  var values by remember { mutableStateOf(List(5) { "" }) }
  var creation by remember { mutableStateOf(List(4) { "" }) }
  var typed by remember { mutableStateOf("") }
  var force by remember { mutableStateOf(false) }
  var confirmed by remember { mutableStateOf(false) }
  var pending by remember { mutableStateOf(false) }
  var writing by remember { mutableStateOf(false) }
  var fresh by remember { mutableStateOf(false) }
  var invalidated by remember { mutableStateOf(false) }
  var recoveryId by remember { mutableStateOf<String?>(null) }
  var recoveryInventory by remember { mutableStateOf(false) }
  var reconfirm by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var feedback by remember { mutableStateOf<String?>(null) }
  var readJob by remember { mutableStateOf<Job?>(null) }
  val routeValid = !invalidated && captured.matches(state) && state.canManageOpenCodeProjects()
  val canRead = routeValid && !pending
  val canWrite = canRead && fresh && error == null && recoveryId == null && !reconfirm
  val projectCap = state.capabilities.openCodeProjectManagement
  val worktreeCap = state.capabilities.openCodeWorktreeManagement
  fun valid() = !invalidated && captured.matches(currentState) && currentState.canManageOpenCodeProjects()
  fun enter(next: OpenCodeProjectPage) {
    confirmed = false; typed = ""; force = false
    actionProject = if (next in setOf(OpenCodeProjectPage.EDIT, OpenCodeProjectPage.CREATE, OpenCodeProjectPage.REMOVE, OpenCodeProjectPage.REFRESH))
      selected?.let { it.copy(sandboxes = it.sandboxes.toList()) } else null
    page = next
  }
  suspend fun readOnly(id: String? = selected?.id, committed: Boolean = false) {
    if (!valid()) return
    fresh = false
    try {
      val catalog = controller.openCodeProjects(captured)
      if (!valid()) return
      val project = id?.let { key -> catalog.firstOrNull { it.id == key } }
      check(id == null || project != null) { "Project no longer listed" }
      val inventory = if (project != null && currentState.capabilities.openCodeWorktreeManagement)
        controller.openCodeWorktrees(captured, project) else emptyList()
      if (!valid()) return
      projects = catalog; selected = project; worktrees = inventory; error = null
      fresh = true; confirmed = false; force = false; typed = ""
      if (recoveryId == id && (!recoveryInventory || currentState.capabilities.openCodeWorktreeManagement)) recoveryId = null
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) {
      if (valid()) error = if (committed) "操作已获 Host 确认，但后续只读清单读取失败。不要重提操作，请读取清单核对。"
        else "读取清单失败；旧记录不可用于提交。请重新读取，不会自动重试操作。"
    }
  }
  fun read(id: String? = selected?.id) {
    if (pending || !valid()) return
    pending = true
    readJob = scope.launch { try { readOnly(id) } finally { pending = false } }
  }
  fun submit(expected: OpenCodeProjectInfo, inventoryMutation: Boolean, action: suspend () -> Unit) {
    if (pending || !fresh || error != null || recoveryId != null || reconfirm || !confirmed || !valid() || selected != expected) return
    if (inventoryMutation && !currentState.capabilities.openCodeWorktreeManagement || !inventoryMutation && !currentState.capabilities.openCodeProjectManagement) return
    pending = true; writing = true; feedback = null
    scope.launch {
      try {
        action()
        if (valid()) { feedback = "Host 已确认操作。以下清单通过 GET 重新读取，不会调用配置刷新。"; enter(OpenCodeProjectPage.DETAILS) }
        readOnly(expected.id, committed = true)
      } catch (cancelled: CancellationException) {
        recoveryId = expected.id; recoveryInventory = inventoryMutation; reconfirm = true; fresh = false
        throw cancelled
      } catch (_: Exception) {
        recoveryId = expected.id; recoveryInventory = inventoryMutation; reconfirm = true; fresh = false; confirmed = false
        feedback = "操作可能已改变 Host，即使返回失败也不能认定未执行。仅可先读取项目与工作树清单核对；没有自动重试或强制升级。"
      } finally { writing = false; pending = false }
    }
  }
  val close = { if (!writing) { readJob?.cancel(); onDismiss() } }
  val back = { if (!writing) { if (page == OpenCodeProjectPage.LIST) close() else { readJob?.cancel(); enter(OpenCodeProjectPage.LIST) } } }
  BackHandler { back() }
  LaunchedEffect(routeValid) { if (!routeValid) { invalidated = true; if (!writing) readJob?.cancel() } }
  LaunchedEffect(Unit) { pending = true; try { readOnly() } finally { pending = false } }
  HyperBottomSheet(title = "管理 OpenCode 项目", show = true, onDismissRequest = close) {
    Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("Host：${captured.profile.name}\n连接 ID：${captured.profile.id}")
      Text("仅管理 Host 原生项目元数据与工作树；不会导入手机项目、选择当前目录或创建会话。", color = HyperTheme.colors.textSecondary)
      if (!routeValid) Text("连接或路由已变化，所有请求已停用；请关闭后重新打开。", color = HyperTheme.colors.error)
      if (pending) Text(if (writing) "正在提交；结果返回前不能关闭。不会自动重试。" else "正在读取清单…")
      error?.let { Text(it, color = HyperTheme.colors.error) }
      feedback?.let { Text(it) }
      if (recoveryId != null) Text("写入锁定：必须成功读取原项目与工作树清单后，再明确重新确认。", color = HyperTheme.colors.error)
      if (reconfirm && recoveryId == null && fresh) HyperTextAction("已核对清单，重新确认操作", enabled = canRead, onClick = {
        if (!pending && valid()) { reconfirm = false; enter(OpenCodeProjectPage.DETAILS) }
      })
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HyperTextAction("读取清单", enabled = canRead, onClick = { read(recoveryId ?: selected?.id) })
        HyperTextAction(if (page == OpenCodeProjectPage.LIST) "关闭" else "返回列表", enabled = !writing, onClick = back)
      }
      if (page == OpenCodeProjectPage.LIST) {
        if (fresh && projects.isEmpty()) Text("Host 没有返回项目。此面板不提供创建项目。")
        projects.forEach { project ->
          HyperCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
              Text(project.name?.takeIf { it.isNotBlank() } ?: project.canonical)
              Text("${project.canonical}\nID：${project.id}${if (project.archived != null) " · 已归档" else ""}")
              HyperTextAction("读取项目详情", enabled = canRead && recoveryId == null && !reconfirm, onClick = {
                if (!pending && valid()) { selected = project; worktrees = emptyList(); enter(OpenCodeProjectPage.DETAILS); read(project.id) }
              })
            }
          }
        }
      } else selected?.let { currentProject ->
        val project = actionProject ?: currentProject
        val formCurrent = project == currentProject
        if (!formCurrent) Text("项目已被只读刷新更新；原确认内容不能提交，请返回详情后重新打开操作。", color = HyperTheme.colors.warning)
        Text("项目：${project.name?.takeIf { it.isNotBlank() } ?: project.canonical}\nID：${project.id}\n根目录：${project.canonical}")
        when (page) {
          OpenCodeProjectPage.DETAILS -> {
            Text("VCS：${project.vcs.orEmpty()}\n创建：${project.created} · 更新：${project.updated}\n活动：${project.active} · 归档：${project.archived ?: "未归档"}")
            Text("图标 URL（仅文本，不加载）：${project.iconUrl.orEmpty()}\n图标覆盖：${project.iconOverride.orEmpty()}\n颜色：${project.iconColor.orEmpty()}")
            Text("已存启动脚本：${project.startCommand.orEmpty()}")
            HyperTextAction("编辑项目元数据", enabled = canWrite && projectCap, onClick = { values = ocValues(project); enter(OpenCodeProjectPage.EDIT) })
            if (worktreeCap) {
              HyperTextAction("创建工作树…", enabled = canWrite, onClick = { creation = List(4) { "" }; enter(OpenCodeProjectPage.CREATE) })
              HyperTextAction("重新加载配置并核对工作树…", enabled = canWrite, onClick = { enter(OpenCodeProjectPage.REFRESH) })
              Text("工作树清单（只读）：")
              worktrees.forEach { worktree ->
                Text("${worktree.directory}\n策略：${worktree.strategy ?: "不可删除的登记"}")
                if (worktree.strategy != null) HyperTextAction("删除工作树…", danger = true, enabled = canWrite, onClick = {
                  removing = project.copy(sandboxes = project.sandboxes.toList()) to worktree.copy(); enter(OpenCodeProjectPage.REMOVE)
                })
              }
              if (fresh && worktrees.isEmpty()) Text("没有工作树。")
            }
          }
          OpenCodeProjectPage.EDIT -> {
            val labels = listOf("canonical 根目录", "名称（空值清除）", "图标覆盖（空值清除）", "图标颜色（空值清除）", "启动脚本（空值清除）")
            labels.forEachIndexed { index, label ->
              TextField(values[index], { if (!pending) { values = values.toMutableList().also { list -> list[index] = it }; confirmed = false } }, label = label, modifier = Modifier.fillMaxWidth())
            }
            Text("canonical 会改变 Host 规范根目录，并影响今后的策略与配置。PATCH 只存储启动脚本；今后创建工作树可能执行它。脚本可含凭据，请核对完整内容。", color = HyperTheme.colors.error)
            val originals = ocValues(project)
            val dirty = values != originals
            Text("完整提交预览（未改字段不发送；空字符串表示清除）：\n" + labels.indices.joinToString("\n") { i -> "${labels[i]}：${if (values[i] == originals[i]) "不发送（保留）" else if (values[i].isEmpty()) "空字符串（清除）" else values[i]}" })
            HyperSwitchRow("确认 Host、项目 ID 与以上提交内容", checked = confirmed, onCheckedChange = { if (!pending) confirmed = it })
            HyperButton("确认保存项目元数据", enabled = canWrite && formCurrent && projectCap && dirty && confirmed && ocAbsolute(values[0]), onClick = {
              val edit = ocEdit(project, values.toList())
              submit(project, false) { controller.updateOpenCodeProject(captured, project, edit) }
            })
          }
          OpenCodeProjectPage.CREATE -> {
            val labels = listOf("from：已有来源绝对路径（可选）", "branch：分支（可选）", "parentDirectory：绝对父目录，非目标路径（可选）", "name：单个目录名（可选）")
            labels.forEachIndexed { index, label ->
              TextField(creation[index], { if (!pending) { creation = creation.toMutableList().also { list -> list[index] = it }; confirmed = false } }, label = label, modifier = Modifier.fillMaxWidth())
            }
            Text("此操作会按 Host 选择的策略创建目录，并可能运行项目已存启动脚本。失败（包括 400）时目录也可能已经创建，只能先读取清单核对，不能直接重试。", color = HyperTheme.colors.error)
            Text("已存启动脚本：${project.startCommand.orEmpty()}\n创建到 Host：${captured.profile.name}\n项目 ID：${project.id}\n" + labels.indices.joinToString("\n") { "${labels[it]}：${creation[it].ifEmpty { "由 Host 决定" }}" })
            val pathsValid = listOf(0, 2).all { creation[it].isEmpty() || ocAbsolute(creation[it]) }
            val nameValid = creation[3].isEmpty() || (creation[3] !in listOf(".", "..") && creation[3].none { it == '/' || it == '\\' || it == '\u0000' })
            HyperSwitchRow("确认创建目录，并接受 Host 策略及脚本执行", checked = confirmed, onCheckedChange = { if (!pending) confirmed = it })
            HyperButton("确认在此 Host 创建工作树", enabled = canWrite && formCurrent && worktreeCap && pathsValid && nameValid && confirmed, onClick = {
              val input = OpenCodeWorktreeCreate(creation[0].takeIf { it.isNotEmpty() }, creation[1].takeIf { it.isNotEmpty() }, creation[2].takeIf { it.isNotEmpty() }, creation[3].takeIf { it.isNotEmpty() })
              submit(project, true) { controller.createOpenCodeWorktree(captured, project, input) }
            })
          }
          OpenCodeProjectPage.REMOVE -> removing?.let { (expected, worktree) ->
            Text("删除 Host：${captured.profile.name}\n项目 ID：${project.id}\n完整目录：${worktree.directory}\n策略：${worktree.strategy}")
            Text("后端可真正删除目录与未提交文件，非本机移除。自定义策略可能还有其他影响；不承诺保留会话或文件。", color = HyperTheme.colors.error)
            TextField(typed, { if (!pending) { typed = it; confirmed = false } }, label = "逐字输入完整目录以确认删除", modifier = Modifier.fillMaxWidth())
            HyperSwitchRow("强制删除可能丢失未提交内容", checked = force, onCheckedChange = { if (!pending) { force = it; confirmed = false } })
            HyperSwitchRow(if (force) "再次确认强制删除上述目录" else "确认删除上述目录", checked = confirmed, onCheckedChange = { if (!pending) confirmed = it })
            HyperButton(if (force) "危险：确认强制删除目录与文件" else "确认删除工作树目录与文件", variant = HyperButtonVariant.DESTRUCTIVE,
              enabled = canWrite && formCurrent && currentProject == expected && worktreeCap && worktree.strategy != null && typed == worktree.directory && confirmed, onClick = {
                val submittedForce = force
                submit(expected, true) { controller.removeOpenCodeWorktree(captured, expected, worktree, submittedForce) }
              })
          }
          OpenCodeProjectPage.REFRESH -> {
            Text("这是写入操作，不是读取清单：会加载 canonical 目录的配置与插件，枚举 Host 策略，并协调工作树库存。仅想看最新状态请使用上方「读取清单」。", color = HyperTheme.colors.error)
            Text("确认 Host：${captured.profile.name}\n项目 ID：${project.id}\ncanonical：${project.canonical}")
            HyperSwitchRow("确认加载配置、插件并协调库存", checked = confirmed, onCheckedChange = { if (!pending) confirmed = it })
            HyperButton("确认重新加载配置并协调库存", enabled = canWrite && formCurrent && worktreeCap && confirmed, onClick = {
              submit(project, true) { controller.refreshOpenCodeWorktrees(captured, project) }
            })
          }
          else -> Unit
        }
      }
    }
  }
}
