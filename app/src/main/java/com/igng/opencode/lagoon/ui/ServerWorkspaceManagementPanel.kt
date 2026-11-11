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

private enum class WorkspacePage { LIST, REGISTER, RENAME, DELETE, MOVE }

/** The captured Host never follows subsequent connection or conversation selection changes. */
@Composable
internal fun ServerWorkspaceManagementPanel(
  state: LagoonState,
  controller: LagoonController,
  target: WorkspaceManagementTarget,
  onDismiss: () -> Unit
) {
  val scope = rememberCoroutineScope()
  val currentState by rememberUpdatedState(state)
  var items by remember(target) { mutableStateOf(emptyList<ServerWorkspace>()) }
  var loaded by remember(target) { mutableStateOf(false) }
  var error by remember(target) { mutableStateOf<String?>(null) }
  var feedback by remember(target) { mutableStateOf<String?>(null) }
  var pending by remember(target) { mutableStateOf(false) }
  var writing by remember(target) { mutableStateOf(false) }
  var readJob by remember(target) { mutableStateOf<Job?>(null) }
  var writeLocked by remember(target) { mutableStateOf(false) }
  var reconfirm by remember(target) { mutableStateOf(false) }
  var page by remember(target) { mutableStateOf(WorkspacePage.LIST) }
  var selected by remember(target) { mutableStateOf<ServerWorkspace?>(null) }
  var path by remember(target) { mutableStateOf("") }
  var title by remember(target) { mutableStateOf("") }
  var beforeId by remember(target) { mutableStateOf<String?>(null) }
  val routeValid = target.matches(state) && state.canManageServerWorkspaces()
  val canRequest = routeValid && !pending
  val canWrite = canRequest && loaded && error == null && !writeLocked && !reconfirm
  fun stillValid() = target.matches(currentState) && currentState.canManageServerWorkspaces()

  suspend fun readCatalog(committed: Boolean = false) {
    if (!stillValid()) return
    try {
      val fresh = controller.serverWorkspaces(target)
      if (!stillValid()) return
      items = fresh
      loaded = true
      error = null
      writeLocked = false
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      error = if (committed) "操作已确认提交，但目录刷新失败；下方仍是上次记录。请只刷新核对，不要重提操作。"
      else if (loaded) "刷新失败；下方为上次读取的登记，可能已过期。"
      else "无法读取 Host 工作区登记。请检查连接后刷新。"
    }
  }
  fun refresh() {
    if (pending || !canRequest || !stillValid()) return
    pending = true
    readJob = scope.launch { try { readCatalog() } finally { pending = false } }
  }
  fun submit(serverMutation: Boolean = true, action: suspend () -> String) {
    if (pending || !canWrite || !stillValid()) return
    pending = true
    writing = true
    feedback = null
    scope.launch {
      try {
        feedback = action()
        if (stillValid()) page = WorkspacePage.LIST
        // A committed mutation and a failed reconciliation read are different outcomes.
        readCatalog(committed = true)
      } catch (cancelled: CancellationException) {
        if (serverMutation) { writeLocked = true; reconfirm = true }
        throw cancelled
      } catch (_: Exception) {
        if (serverMutation) {
          writeLocked = true
          reconfirm = true
          feedback = "操作可能已提交，请先刷新核对；未自动重试。编辑内容已保留。"
        } else feedback = "未确认保存到本机。请刷新登记后重新核对；不会修改 Host 登记。"
      } finally { writing = false; pending = false }
    }
  }
  val close = { if (!writing) { readJob?.cancel(); onDismiss() } }
  val back = { if (!pending && page != WorkspacePage.LIST) page = WorkspacePage.LIST else close() }
  BackHandler { back() }
  LaunchedEffect(target) {
    if (stillValid()) {
      pending = true
      try { readCatalog() } finally { pending = false }
    }
  }

  HyperBottomSheet(title = "Host 工作区", show = true, onDismissRequest = close) {
    Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("Host：${target.profile.name}")
      Text("这是 Host 原生共享登记，其他客户端也会看到改动。登记、改名、移除和排序不会自动保存到本机。多客户端可能同时修改，请提交前核对。",
        color = HyperTheme.colors.textSecondary)
      if (!routeValid) Text("连接或路由已变化，已停用所有请求。请关闭后从原 Host 重新打开。", color = HyperTheme.colors.error)
      if (pending) Text(if (writing) "正在提交，请等待结果后关闭；不会自动重试。" else "正在读取登记…")
      error?.let { Text(it, color = HyperTheme.colors.error) }
      feedback?.let { Text(it, color = HyperTheme.colors.textSecondary) }
      if (writeLocked) Text("写入已锁定：先成功刷新，再由你重新确认操作。", color = HyperTheme.colors.error)
      if (reconfirm && !writeLocked && error == null) HyperTextAction("已核对登记，重新确认操作", enabled = canRequest,
        onClick = { reconfirm = false; feedback = "请再次核对下面的 Host、路径与操作，然后明确提交。" })
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HyperTextAction("刷新登记", enabled = canRequest, onClick = ::refresh)
        HyperTextAction(if (page == WorkspacePage.LIST) "关闭" else "返回列表", enabled = !writing, onClick = back)
      }
      if (page == WorkspacePage.LIST) {
        HyperButton("登记已有目录", enabled = canWrite, onClick = { page = WorkspacePage.REGISTER })
        if (loaded && items.isEmpty()) Text("此 Host 尚无工作区登记。")
        items.forEach { workspace ->
          HyperCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text(workspace.title.ifBlank { "未命名工作区" })
              Text(workspace.path, color = HyperTheme.colors.textSecondary)
              Text("ID：${workspace.id} · ${workspace.sessionIds.size} 个会话", color = HyperTheme.colors.textSecondary)
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HyperTextAction("改名", enabled = canWrite, onClick = { selected = workspace; title = workspace.title; page = WorkspacePage.RENAME })
                HyperTextAction("排序", enabled = canWrite, onClick = { selected = workspace; beforeId = null; page = WorkspacePage.MOVE })
                HyperTextAction("移除", enabled = canWrite, danger = true, onClick = { selected = workspace; page = WorkspacePage.DELETE })
              }
              HyperTextAction("保存到本机", enabled = canWrite, onClick = {
                submit(serverMutation = false) {
                  controller.saveWorkspaceOnDevice(target, workspace)
                  "已保存到本机项目；Host 登记未变更。"
                }
              })
            }
          }
        }
      } else if (page == WorkspacePage.REGISTER) {
        Text("登记已有目录")
        Text("只登记此 Host 已存在的绝对目录路径；重复登记是幂等操作。不会新建文件夹、创建会话，也不会打开目录选择器。",
          color = HyperTheme.colors.textSecondary)
        TextField(path, { if (!pending) path = it }, label = "Host 已有目录的完整绝对路径", useLabelAsPlaceholder = true,
          modifier = Modifier.fillMaxWidth())
        Text("确认登记到 ${target.profile.name}：\n$path")
        val absolute = path.startsWith("/") || Regex("^[A-Za-z]:[\\\\/].*").matches(path) || path.startsWith("\\\\")
        HyperButton("确认登记已有目录", enabled = canWrite && path.isNotBlank() && absolute, onClick = {
          val submittedPath = path
          submit {
            val registration = controller.registerWorkspace(target, submittedPath)
            if (registration.created) "Host 已确认新增登记。" else "该目录已登记；Host 已返回现有工作区。"
          }
        })
      } else selected?.let { workspace ->
        Text("${workspace.title}\n${workspace.path}\nID：${workspace.id}")
        when (page) {
          WorkspacePage.RENAME -> {
            TextField(title, { if (!pending) title = it }, label = "工作区名称", useLabelAsPlaceholder = true,
              modifier = Modifier.fillMaxWidth())
            Text("仅修改 ${target.profile.name} 上此登记的显示名称，不会更改目录路径。")
            HyperButton("确认修改名称", enabled = canWrite && title.isNotBlank(), onClick = {
              val submittedTitle = title.trim()
              submit { controller.renameWorkspace(target, workspace, submittedTitle); "Host 已确认修改名称。" }
            })
          }
          WorkspacePage.DELETE -> {
            Text("仅移除 ${target.profile.name} 的此项工作区登记。文件和会话都会保留，本机项目偏好不受影响。",
              color = HyperTheme.colors.error)
            HyperButton("确认移除 Host 登记", enabled = canWrite, variant = HyperButtonVariant.DESTRUCTIVE,
              onClick = { submit { controller.deleteWorkspace(target, workspace); "Host 已确认移除登记；文件、会话和本机项目未清理。" } })
          }
          WorkspacePage.MOVE -> {
            Text("将此工作区移到以下登记之前（按 ID 区分同名项）；也可移到末尾。以 Host 返回顺序为准，不做本机乐观排序。")
            HyperTextAction(if (beforeId == null) "✓ 移到末尾" else "移到末尾", enabled = canRequest,
              onClick = { beforeId = null })
            items.filter { it.id != workspace.id }.forEach { anchor ->
              HyperTextAction("${if (beforeId == anchor.id) "✓ " else ""}${anchor.title}\n${anchor.path}\nID：${anchor.id}",
                enabled = canRequest, onClick = { beforeId = anchor.id })
            }
            val anchor = items.firstOrNull { it.id == beforeId }
            Text("确认在 ${target.profile.name} ${if (anchor == null) "移到末尾" else "移到 ID：${anchor.id} 之前"}")
            HyperButton("确认调整 Host 顺序", enabled = canWrite && (beforeId == null || anchor != null), onClick = {
              submit { controller.moveWorkspace(target, workspace, anchor); "Host 已确认排序，正在读取权威顺序。" }
            })
          }
          else -> Unit
        }
      }
      Spacer(Modifier.height(12.dp))
    }
  }
}
