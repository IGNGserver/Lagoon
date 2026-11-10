package com.igng.opencode.lagoon.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import java.io.File
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperListPopup
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.miuixCapsuleShape
import top.yukonga.miuix.kmp.theme.miuixShape

private enum class ChatSheet { CHANGES, FILES, SCHEDULES, ACTIVITY }

internal fun conversationActivityCount(activity: SessionActivity): Int =
  activity.subSessions.size + activity.shells.size

/** The ⋯ entry and the sheet title share one label; the count only shows while there is something to open. */
internal fun conversationActivityLabel(activity: SessionActivity): String {
  val count = conversationActivityCount(activity)
  return if (count > 0) "子会话与后台（$count）" else "子会话与后台"
}

@Composable
fun ChatScreen(state: LagoonState, controller: LagoonController, onBack: () -> Unit, onOpenChild: (String) -> Unit, onModal: (Boolean) -> Unit, interactive: Boolean = true) {
  val session = state.session
  var sheet by remember { mutableStateOf<ChatSheet?>(null) }
  var menu by remember { mutableStateOf(false) }
  var rename by rememberSaveable { mutableStateOf(false) }
  var permission by rememberSaveable { mutableStateOf(false) }
  var delete by rememberSaveable { mutableStateOf(false) }
  var composerModal by remember { mutableStateOf(false) }
  var requestModal by remember { mutableStateOf(false) }
  var activityGroup by remember(state.serverId, state.sessionId) { mutableStateOf<TranscriptRow?>(null) }
  var activityItem by remember(state.serverId, state.sessionId) { mutableStateOf<TranscriptRow?>(null) }
  var queuedAction by remember(state.serverId, state.sessionId) { mutableStateOf<TranscriptRow?>(null) }
  var queuedEdit by remember(state.serverId, state.sessionId) { mutableStateOf<TranscriptRow?>(null) }
  var queuedText by remember(state.serverId, state.sessionId) { mutableStateOf("") }
  var childContinue by remember(state.serverId, state.sessionId) { mutableStateOf(false) }
  var childText by remember(state.serverId, state.sessionId) { mutableStateOf("") }
  var goalDialog by remember(state.serverId, state.sessionId) { mutableStateOf(false) }
  var goalText by remember(state.serverId, state.sessionId) { mutableStateOf("") }
  val activity = remember(state.sessions, state.presentedTasks, state.shells, state.parents, state.sessionId) { state.sessionActivity() }
  val transcriptList = rememberLazyListState()
  LaunchedEffect(sheet, state.serverId, state.sessionId) {
    if (sheet == ChatSheet.CHANGES || sheet == ChatSheet.ACTIVITY) controller.loadAncillary()
  }
  val focusManager = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  // 后台活动是半屏抽屉：收起键盘，免得抽屉顶在输入法上；抽屉里的子会话再整页打开。
  fun showActivitySheet() {
    focusManager.clearFocus()
    keyboard?.hide()
    menu = false
    sheet = ChatSheet.ACTIVITY
  }
  val modal = sheet != null || menu || rename || delete || composerModal || requestModal || activityGroup != null || state.shellOutput != null
  DisposableEffect(modal) { onModal(modal); onDispose { onModal(false) } }
  fun openFile(path: String) { sheet = ChatSheet.FILES; controller.readFile(path) }
  if (session == null) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) { Text("会话尚未加载或已关闭"); HyperButton(text = "返回列表", onClick = onBack) }
    return
  }
  // ⋯ 分两段：查看会话的资源，以及对会话本身的操作。整理上下文 / 撤销 / 分享不在手机上提供，已保存权限在设置页；
  // 分叉与删除按 harness 能力显示（DSH 没有消息级撤销与删除，用分叉与归档代替）。
  val menuSections = listOf(
    MenuSection(buildList {
      add(MenuAction(conversationActivityLabel(activity), MiuixIcons.MindMap) { showActivitySheet() })
      if (state.capabilities.diff) add(MenuAction("改动（${state.changes.size}）", MiuixIcons.Merge) { sheet = ChatSheet.CHANGES })
      add(MenuAction("文件", MiuixIcons.Folder) { sheet = ChatSheet.FILES; controller.listFiles() })
      if (state.server?.kind == ServerKind.DSH || state.server?.kind == ServerKind.DSH_REMOTE) {
        add(MenuAction("定时任务", MiuixIcons.Info) { sheet = ChatSheet.SCHEDULES; controller.loadSchedules() })
        // DSH 允许用新目标替换已完成的目标；进行中/暂停中必须先继续或清除。
        if (state.goal == null || state.goal.complete) {
          add(MenuAction("设定长期目标", MiuixIcons.Ok) { goalDialog = true })
        }
      }
    }),
    MenuSection(buildList {
      add(MenuAction(if (session.id in state.pinned) "取消置顶" else "置顶", if (session.id in state.pinned) MiuixIcons.Unpin else MiuixIcons.Pin) { controller.togglePin(session.id) })
      if (state.capabilities.supports(SessionAction.RENAME)) add(MenuAction("重命名", MiuixIcons.Rename) { rename = true })
      if (state.capabilities.fork) add(MenuAction("分叉", MiuixIcons.Copy) { controller.fork() })
      // 只有服务端真的带权限投影时才显示入口（OpenCode 没有这个语义）。
      if (session.permission.isNotBlank()) add(MenuAction("权限模式", MiuixIcons.Settings) { permission = true })
      state.capabilities.archive?.let { archive ->
        if (!session.archived) add(MenuAction("归档", ARCHIVE_ICON) { controller.setArchived(session.id, true, onBack) })
        else if (archive.restorable) add(MenuAction("取消归档", UNARCHIVE_ICON) { controller.setArchived(session.id, false) })
      }
      if (state.capabilities.deleteSession) add(MenuAction("删除", MiuixIcons.Delete, danger = true) { delete = true })
    })
  )
  HyperLagoonTheme {
    Box(Modifier.fillMaxSize().background(HyperLagoonColors.canvas)) {
      Column(Modifier.fillMaxSize()) {
        PageTopBar(state.title(session), onBack, subtitle = { SessionSubtitle(state, session) }) {
          Box {
            HyperIconButton(QoderGlyph.MORE, "会话操作", { menu = true }, backgroundColor = Color.Transparent)
            MenuPopup(menu, { menu = false }, menuSections)
          }
        }
        ConnectionStrip(state, controller)
        val transcriptStatus = state.resource("messages")
        if (transcriptStatus.state == ResourceState.STALE || transcriptStatus.state == ResourceState.ERROR) {
          Text(
            if (transcriptStatus.state == ResourceState.STALE) "保留上次内容 · 正在后台重试" else (transcriptStatus.error ?: "加载失败"),
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
            style = MiuixTheme.textStyles.footnote2,
            color = if (transcriptStatus.state == ResourceState.STALE) HyperTheme.colors.warning else HyperTheme.colors.error
          )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
          Conversation(state, controller, Modifier.fillMaxSize(), ::openFile, { requestModal = it }, onOpenChild,
            onActivityGroup = {
              focusManager.clearFocus(); keyboard?.hide()
              activityGroup = it
              activityItem = it.takeIf { row -> row.kind == "tool" }
            }, onQueuedAction = { queuedAction = it },
            activity = activity, onOpenActivity = ::showActivitySheet, listState = transcriptList)
        }
        if (session.parentId != null) ChildSessionBar(state, session, onBack,
          onContinue = if ((state.server?.kind == ServerKind.DSH || state.server?.kind == ServerKind.DSH_REMOTE) &&
            session.subagentMode == "continuable") {
            { childContinue = true }
          } else null)
        else {
          val goalSupported = state.server?.kind == ServerKind.DSH || state.server?.kind == ServerKind.DSH_REMOTE
          LaunchedEffect(state.sessionId, state.connected, goalSupported) {
            if (state.connected && goalSupported) controller.loadGoal()
          }
          // 状态轨只承载 DSH 的会话级目标与 todo 投影；OpenCode 没有这两项，组件自身不占位。
          if (goalSupported) ConversationStatusRail(state, controller)
          HyperPendingActionDock(state, controller) { requestModal = it }
          ChatComposer(state, controller, interactive, onModal = { composerModal = it }, onServerFile = { sheet = ChatSheet.FILES; controller.listFiles() })
        }
      }
      // The last group stays composed while the overlay animates out.
      val shownGroup = remember(state.serverId, state.sessionId) { arrayOfNulls<TranscriptRow>(1) }
      activityGroup?.let { shownGroup[0] = it }
      AnimatedVisibility(activityGroup != null, enter = fadeIn(tween(180)), exit = fadeOut(tween(200))) {
        val group = shownGroup[0] ?: return@AnimatedVisibility
        QoderActivityOverlay(
          messages = state.messages,
          group = group,
          selected = activityItem,
          working = state.livePresentedTasks[state.sessionId]?.active == true,
          unverified = state.livePresentedTasks[state.sessionId]?.active != true || state.cached || !state.connected,
          revertMessageId = session.revertMessageId,
          interactive = interactive,
          onOpenChild = { activityItem = null; activityGroup = null; onOpenChild(it) },
          onFile = { activityItem = null; activityGroup = null; openFile(it) },
          onSelected = { activityItem = it },
          onBack = { activityItem = null },
          onDismiss = { activityItem = null; activityGroup = null }
        )
      }
    }
  }
  sheet?.let { which ->
    val title = when (which) {
      ChatSheet.CHANGES -> "代码改动"
      ChatSheet.FILES -> "文件"
      ChatSheet.SCHEDULES -> "定时任务"
      ChatSheet.ACTIVITY -> conversationActivityLabel(activity)
    }
    HyperBottomSheet(title = title, show = true, onDismissRequest = { sheet = null }) {
      Column(Modifier.fillMaxWidth().fillMaxHeight(0.72f).padding(vertical = 8.dp)) {
        when (which) {
          ChatSheet.CHANGES -> {
            ResourceHint(state.resource("changes"), "暂无记录", retry = { controller.loadAncillary() })
            // Server lists may repeat an entry; LazyColumn crashes on a duplicate key, so keys never come from data alone.
            LazyColumn { itemsIndexed(state.changes, key = { index, change -> "$index:${change.path}" }) { _, change ->
              var expanded by rememberSaveable(change.path) { mutableStateOf(false) }
              HyperActionRow(title = change.path, summary = "+${change.additions} −${change.deletions}", trailing = { ExpandChevron(expanded) }, onClick = { expanded = !expanded })
              if (expanded) VirtualText(change.patch.ifBlank { change.after })
            } }
          }
          ChatSheet.FILES -> FilesPanel(state, controller) { path -> controller.addReference(path); sheet = null }
          ChatSheet.SCHEDULES -> SchedulesPanel(state, controller)
          // 子会话整页打开；后台命令有自己的输出抽屉，这一层先收起，不把两个抽屉叠在一起。
          ChatSheet.ACTIVITY -> ActivityPanel(state, activity, state.messages.filter { it.isBackgroundActivityMessage }, controller,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp),
            onOpenChild = { child -> sheet = null; onOpenChild(child) },
            onOpenShell = { job -> sheet = null; controller.openShellOutput(job) })
        }
      }
    }
  }
  ShellOutputSheet(state, controller)
  queuedAction?.let { row ->
    val itemId = row.key.removePrefix("user:")
    HyperBottomSheet(title = "排队消息", show = true, onDismissRequest = { queuedAction = null }) {
      Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(row.text, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.body2)
        HyperButton(text = "立即插话", onClick = { controller.updateQueued(itemId, QueueAction.Steer); queuedAction = null }, modifier = Modifier.fillMaxWidth())
        HyperButton(text = "编辑", variant = HyperButtonVariant.SECONDARY, onClick = { queuedAction = null; queuedEdit = row; queuedText = row.text }, modifier = Modifier.fillMaxWidth())
        HyperButton(text = "撤回", variant = HyperButtonVariant.DESTRUCTIVE, onClick = { controller.updateQueued(itemId, QueueAction.Remove); queuedAction = null }, modifier = Modifier.fillMaxWidth())
      }
    }
  }
  queuedEdit?.let { row ->
    SuperDialog(title = "编辑排队消息", show = true, onDismissRequest = { queuedEdit = null }) {
      Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(value = queuedText, onValueChange = { queuedText = it }, label = "消息内容")
        DialogActions("取消", { queuedEdit = null }, "保存", queuedText.isNotBlank()) {
          controller.updateQueued(row.key.removePrefix("user:"), QueueAction.Edit(queuedText.trim()))
          queuedEdit = null
        }
      }
    }
  }
  if (childContinue) {
    SuperDialog(title = "继续子代理", show = true, onDismissRequest = { childContinue = false }) {
      Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("消息会通过父会话发送给这个可继续的子代理。", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        TextField(value = childText, onValueChange = { childText = it }, label = "消息内容")
        DialogActions("取消", { childContinue = false }, "发送", childText.isNotBlank()) {
          val text = childText.trim()
          childContinue = false; childText = ""
          controller.promptChild(text)
        }
      }
    }
  }
  if (goalDialog) {
    SuperDialog(title = "设定长期目标", show = true, onDismissRequest = { goalDialog = false }) {
      Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("目标是会话级的；完成或清除前，Host 可能自动续跑。之后可在目标面板里暂停、继续或标记完成。", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        TextField(value = goalText, onValueChange = { goalText = it }, label = "目标")
        DialogActions("取消", { goalDialog = false }, "创建", goalText.isNotBlank()) {
          val text = goalText.trim()
          goalDialog = false; goalText = ""
          controller.createGoal(text)
        }
      }
    }
  }
  if (rename) RenameSessionDialog(state, controller, session) { rename = false }
  if (permission) PermissionDialog(state, controller, session) { permission = false }
  if (delete) SuperDialog(title = "删除此会话？", show = true, onDismissRequest = { if (!state.pending("delete")) delete = false }) {
    Column { Text("会话消息将从服务器永久删除。\n${state.title(session)}"); ActionError(state, "delete")
      DialogActions("取消", { delete = false }, if (state.pending("delete")) "删除中…" else "永久删除", !state.pending("delete"), danger = true) { controller.deleteSession { delete = false; onBack() } }
    }
  }
}

/**
 * 置顶于输入框上方的「待你拍板」快捷交互底座（HyperOS 4 Action Dock）：
 * 当当前会话有待审批权限或待回答问题时，常驻吸附于拇指热区，支持一键允许/拒绝而无需翻找长列表。
 */
@Composable
private fun HyperPendingActionDock(
  state: LagoonState,
  controller: LagoonController,
  onModal: (Boolean) -> Unit = {}
) {
  val permissions = state.visiblePermissions.filter { it.sessionId == state.sessionId }
  val questions = state.questions.filter { it.sessionId == state.sessionId }
  val count = pendingRequestCount(permissions.size, questions.size)
  if (count == 0) return
  var review by remember(state.sessionId) { mutableStateOf(false) }
  DisposableEffect(review) { onModal(review); onDispose { onModal(false) } }
  LaunchedEffect(count) { if (count == 0) review = false }

  HyperPill(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    background = if (permissions.isNotEmpty()) HyperTheme.colors.warningSubtle else HyperTheme.colors.primarySubtle
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        text = "待你处理 · $count 项",
        style = MiuixTheme.textStyles.footnote1.copy(
          fontWeight = FontWeight.SemiBold,
          color = if (permissions.isNotEmpty()) HyperTheme.colors.warning else HyperTheme.colors.primary
        )
      )
      Text(
        text = when {
          permissions.isNotEmpty() && questions.isNotEmpty() -> "包含操作授权和需要回答的问题"
          permissions.isNotEmpty() -> "查看完整命令、路径与风险后再决定"
          else -> "查看全部选项后提交回答"
        },
        style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )
    }
    HyperButton(text = "查看并处理", variant = HyperButtonVariant.SECONDARY, onClick = { review = true })
  }

  if (review) {
    HyperBottomSheet(title = "待你处理 · $count 项", show = true, onDismissRequest = { review = false }) {
      LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 720.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        if (permissions.isNotEmpty()) item { HyperGroupTitle("操作授权") }
        val permissionKeys = uniqueListKeys(permissions) { "permission:${it.id}" }
        itemsIndexed(permissions, key = { index, _ -> permissionKeys[index] }) { _, request ->
          MiuixPermissionCard(
            request = request,
            controller = controller,
            pending = state.pending("permission:${request.id}"),
            onModal = { childModal -> onModal(review || childModal) }
          )
        }
        if (questions.isNotEmpty()) item { HyperGroupTitle("需要回答") }
        val questionKeys = uniqueListKeys(questions) { "question:${it.id}" }
        itemsIndexed(questions, key = { index, _ -> questionKeys[index] }) { _, request ->
          MiuixQuestionCard(
            request = request,
            controller = controller,
            pending = state.pending("question:${request.id}")
          )
        }
      }
    }
  }
}



/** The child-session footer: live status and a way back, with no composer (official subagents are read-only). */
@Composable
private fun ChildSessionBar(state: LagoonState, session: Session, onBack: () -> Unit, onContinue: (() -> Unit)? = null) {
  val task = state.livePresentedTasks[session.id]
  val waiting = task?.phase in TaskState.WAITING_PHASES
  val phase = when {
    task?.active == true && task.uncertain -> "等待同步"
    task?.active == true -> when (task.phase) {
      TaskPhase.WAITING_PERMISSION -> "等待授权"
      TaskPhase.WAITING_QUESTION -> "等待回答"
      TaskPhase.WAITING_SERVER -> "等待服务器处理"
      else -> "运行中"
    }
    session.outcome == "failed" -> "失败"
    session.outcome == "interrupted" -> "已中断"
    else -> "已完成"
  }
  val started = task?.since?.takeIf { it > 0 } ?: session.created
  val ended = task?.finishedAt?.takeIf { it > 0 } ?: session.idle.takeIf { it > 0 }
  val now by produceState(System.currentTimeMillis(), session.id, task?.since) {
    while (true) { value = System.currentTimeMillis(); delay(1_000) }
  }
  val elapsed = ((ended ?: now) - started).coerceAtLeast(0)
  Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
    .clip(miuixSquircleShape(16.dp)).background(HyperTheme.colors.container).padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically) {
    val active = task?.active == true
    if (active) InfiniteProgressIndicator(color = HyperTheme.colors.primary, size = 14.dp, strokeWidth = 1.5.dp)
    else Icon(MiuixIcons.Info, phase, Modifier.size(16.dp),
      tint = if (phase == "失败") HyperTheme.colors.error else HyperTheme.colors.textSecondary)
    Spacer(Modifier.width(8.dp))
    Column(Modifier.weight(1f)) {
      Text("$phase · ${durationText(elapsed)}", style = MiuixTheme.textStyles.footnote2.copy(
        color = if (waiting) HyperTheme.colors.warning else HyperTheme.colors.textPrimary))
      Text(listOfNotNull(session.agent, session.model?.label).joinToString(" · ").ifBlank { "子会话" },
        style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    onContinue?.let { HyperTextAction(text = "继续", onClick = it) }
    HyperTextAction(text = "返回父会话", onClick = onBack)
  }
}

/** 「子会话与后台」抽屉的正文：代理消息、后台命令和整棵子会话家族（含历史）。 */
@Composable
private fun ActivityPanel(state: LagoonState, activity: SessionActivity, backgroundMessages: List<Message>, controller: LagoonController,
  modifier: Modifier = Modifier, onOpenChild: (String) -> Unit, onOpenShell: (ShellJob) -> Unit) {
  var now by remember(activity) { mutableStateOf(System.currentTimeMillis()) }
  LaunchedEffect(activity) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
  Column(modifier.verticalScroll(rememberScrollState()).semantics(mergeDescendants = false) {
    contentDescription = "后台活动列表"
  }) {
    if (backgroundMessages.isNotEmpty()) {
      Text("代理消息", Modifier.padding(horizontal = 4.dp, vertical = 8.dp), style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      backgroundMessages.sortedBy { it.created }.forEach { message ->
        val notice = message.notice
        val title = notice?.label ?: if (message.role == "relay") "代理来信" else "后台任务"
        val detail = notice?.detail.orEmpty().ifBlank { message.parts.joinToString("\n") { it.text }.trim() }
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp).semantics(mergeDescendants = true) {
          contentDescription = "$title${if (detail.isBlank()) "" else "，$detail"}"
        }) {
          Text(title, style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.Medium))
          if (detail.isNotBlank()) Text(detail, style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary), maxLines = 8, overflow = TextOverflow.Ellipsis)
        }
      }
    }
    if (activity.shells.isNotEmpty()) {
      Text("后台命令", Modifier.padding(horizontal = 4.dp, vertical = 8.dp), style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      activity.shells.forEach { job ->
        Row(Modifier.fillMaxWidth().clip(miuixShape(12.dp)).clickable { onOpenShell(job) }
          .semantics(mergeDescendants = true) {
            role = Role.Button
            contentDescription = "后台命令，${job.command.lineSequence().firstOrNull().orEmpty().ifBlank { "shell" }}"
            stateDescription = if (job.uncertain) "状态待核实" else "运行中"
          }
          .padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(MiuixIcons.Info, "后台命令", Modifier.size(18.dp), tint = MiuixTheme.colorScheme.primary)
          Spacer(Modifier.width(10.dp))
          Column(Modifier.weight(1f)) {
            Text(job.command.lineSequence().firstOrNull().orEmpty().ifBlank { "shell" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
              style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace))
            Text(if (job.uncertain) "状态待核实 · 保留上次任务" else "运行中 · ${durationText(now - job.started)}", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
          }
          ForwardChevron(16.dp, HyperTheme.colors.textSecondary)
        }
      }
    }
    if (activity.subSessions.isNotEmpty()) {
      Text("子会话", Modifier.padding(horizontal = 4.dp, vertical = 8.dp), style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      activity.subSessions.forEach { entry ->
        val session = entry.session
        val label = session.displayTitle().let { if (it == "子会话" && session.agent != null) "${session.agent} 子会话" else it }
          Row(Modifier.fillMaxWidth().clip(miuixShape(12.dp)).clickable { onOpenChild(session.id) }
            .semantics(mergeDescendants = true) {
              role = Role.Button
              contentDescription = "子会话，$label"
              stateDescription = subSessionLabel(entry.phase)
            }
            .padding(start = (10 + (entry.depth - 1).coerceAtLeast(0) * 14).dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
          verticalAlignment = Alignment.CenterVertically) {
          if (entry.active) InfiniteProgressIndicator(color = if (entry.phase in setOf(SubSessionPhase.WAITING_PERMISSION, SubSessionPhase.WAITING_QUESTION, SubSessionPhase.WAITING_SERVER)) MiuixColorTokens.Warning else MiuixTheme.colorScheme.primary, size = 14.dp, strokeWidth = 1.5.dp)
          else Icon(MiuixIcons.MindMap, "子会话", Modifier.size(16.dp), tint = if (entry.phase == SubSessionPhase.FAILED) MiuixColorTokens.Error else MiuixTheme.colorScheme.onSurfaceVariantSummary)
          Spacer(Modifier.width(8.dp))
          Column(Modifier.weight(1f)) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.body2.copy(
              fontWeight = if (entry.active) FontWeight.Medium else FontWeight.Normal))
            val elapsed = ((entry.ended ?: now) - entry.started).coerceAtLeast(0)
            Text("${subSessionLabel(entry.phase)} · ${durationText(elapsed)}${entry.session.agent?.let { " · $it" } ?: ""}",
              style = MiuixTheme.textStyles.footnote2.copy(color = when (entry.phase) {
                SubSessionPhase.FAILED -> MiuixColorTokens.Error
                SubSessionPhase.WAITING_PERMISSION, SubSessionPhase.WAITING_QUESTION, SubSessionPhase.WAITING_SERVER -> MiuixColorTokens.Warning
                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
              }), maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
          ForwardChevron(16.dp, HyperTheme.colors.textSecondary)
        }
      }
    }
    if (activity.shells.isEmpty() && activity.subSessions.isEmpty()) {
      ResourceHint(state.resource("children"), "暂无后台任务或子会话", "后台命令和子代理会话会显示在这里", controller::reload)
    }
  }
}

private fun subSessionLabel(phase: SubSessionPhase): String = when (phase) {
  SubSessionPhase.RUNNING -> "运行中"
  SubSessionPhase.WAITING_PERMISSION -> "等待授权"
  SubSessionPhase.WAITING_QUESTION -> "等待回答"
  SubSessionPhase.WAITING_SERVER -> "等待服务器处理"
  SubSessionPhase.COMPLETED -> "已完成"
  SubSessionPhase.FAILED -> "失败"
  SubSessionPhase.INTERRUPTED -> "已中断"
}

/** Live output for one background shell, polled from the server's byte-cursor output route. */
@Composable
private fun ShellOutputSheet(state: LagoonState, controller: LagoonController) {
  val output = state.shellOutput ?: return
  var stopCandidate by remember(output.shellId) { mutableStateOf<ShellJob?>(null) }
  val scroll = rememberScrollState()
  LaunchedEffect(output.text) { scroll.animateScrollTo(scroll.maxValue) }
  stopCandidate?.let { job ->
    SuperDialog(title = "停止后台命令？", show = true, onDismissRequest = { stopCandidate = null }) {
      Column(Modifier.padding(top = 8.dp)) {
        Text("进程会被终止，已产生的输出仍可查看。此操作不能撤销。", style = MiuixTheme.textStyles.body1)
        DialogActions("取消", { stopCandidate = null }, "停止命令", danger = true) {
          controller.stopShell(job)
          stopCandidate = null
        }
      }
    }
  }
  HyperBottomSheet(title = output.command.lineSequence().firstOrNull().orEmpty().ifBlank { "后台命令" },
    show = true, onDismissRequest = { controller.closeShellOutput() }) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.78f).padding(vertical = 8.dp)) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val snapshot = output.snapshot
        Text(when {
          output.loading -> "正在读取输出…"
          snapshot == null -> "暂时无法读取输出"
          snapshot.running -> "运行中"
          snapshot.status == "timeout" -> "已超时"
          snapshot.status == "killed" -> "已停止"
          snapshot.exit != null -> "已结束 · 退出码 ${snapshot.exit}"
          else -> "已结束"
        }, Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        if (snapshot?.running == true && state.capabilities.stopShell) state.shells[output.shellId]?.let { job ->
          HyperTextAction(text = "停止", danger = true, onClick = { stopCandidate = job })
        }
      }
      output.error?.let { Text(it, color = MiuixColorTokens.Error, style = MiuixTheme.textStyles.footnote2) }
      if (output.omitted) Text("仅显示最近 64KB 输出", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp).clip(miuixSquircleShape(12.dp)).background(HyperTheme.colors.codeSurface)) {
        SelectionContainer {
          Text(output.text.ifBlank { if (output.loading) "正在读取输出…" else "暂无输出" },
            Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp),
            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            color = HyperTheme.colors.textPrimary)
        }
      }
    }
  }
}

private fun durationText(millis: Long): String {
  val seconds = (millis / 1000).coerceAtLeast(0)
  return when {
    seconds < 60 -> "${seconds} 秒"
    seconds < 3600 -> "${seconds / 60} 分 ${seconds % 60} 秒"
    else -> "${seconds / 3600} 时 ${(seconds % 3600) / 60} 分"
  }
}

/** Chat-level overlay: it covers the composer as in Qoder, while preserving the transcript behind it. */
@Composable
private fun AnimatedVisibilityScope.QoderActivityOverlay(
  messages: List<Message>,
  group: TranscriptRow,
  selected: TranscriptRow?,
  working: Boolean,
  unverified: Boolean,
  revertMessageId: String?,
  interactive: Boolean,
  onOpenChild: (String) -> Unit,
  onFile: (String) -> Unit,
  onSelected: (TranscriptRow) -> Unit,
  onBack: () -> Unit,
  onDismiss: () -> Unit
) {
  // Throttled like the transcript: restarting on every streamed change cancelled the build before it
  // finished, so an open activity list stopped updating while the turn was running.
  val source by rememberUpdatedState(Triple(messages, working to unverified, revertMessageId))
  val content by produceState(group to emptyList<TranscriptRow>(), group.key) {
    snapshotFlow { source }.conflate().collect { (messages, activity, revertMessageId) ->
      val (working, unverified) = activity
      value = withContext(Dispatchers.Default) {
        val rows = TranscriptRows.build(messages, working = working, revertMessageId = revertMessageId, unverified = unverified)
        val resolved = TranscriptRows.resolveGroup(rows, group)
        val latest = resolved ?: group.copy(title = "此操作记录已不可用", status = "unverified")
        latest to if (resolved == null) emptyList() else
          TranscriptRows.details(messages, resolved.key, working, revertMessageId, unverified)
      }
      delay(TranscriptScroll.ROWS_THROTTLE_MS * 2)
    }
  }
  val (latestGroup, details) = content
  val sections = remember(details) { qoderSections(details) }
  // Kept outside the list/detail branch: returning from a detail must restore the reader's place.
  val activityListState = rememberLazyListState()
  val latestSelected = selected?.let { item ->
    val latest = if (item.key == group.key) latestGroup else details.firstOrNull { it.key == item.key }
      ?: item.copy(title = "此操作记录已不可用", status = "unverified", source = null, target = null, attachments = emptyList())
    if (unverified && latest.status in setOf("running", "pending")) latest.copy(status = "unverified") else latest
  }
  // Not while animating out: that back belongs to the conversation underneath.
  BackHandler(enabled = interactive && transition.targetState == EnterExitState.Visible) { if (latestSelected != null && latestSelected.key != group.key) onBack() else onDismiss() }
  Box(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize().background(HyperTheme.colors.scrim)
      .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.74f).align(Alignment.BottomCenter)
      .animateEnterExit(enter = slideInVertically(HyperMotion.sheetOffsetSpring) { it / 2 },
        exit = slideOutVertically(HyperMotion.sheetOffsetSpring) { it / 2 })
      .clip(miuixSquircleShape(28.dp))
      .background(HyperTheme.colors.sheetSurface)
      .border(0.6.dp, HyperTheme.colors.cardBorder, miuixSquircleShape(28.dp))
      .pointerInput(Unit) { detectTapGestures { } }.navigationBarsPadding()) {
      QoderSheetHandle()
      if (latestSelected == null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
          Spacer(Modifier.width(44.dp))
          Text(latestGroup.title.ifBlank { "操作记录" }, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.Bold))
          HyperIconButton(QoderGlyph.CLOSE, "关闭操作列表", onDismiss, backgroundColor = QoderColors.codeColor, tint = QoderColors.secondary)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 22.dp), state = activityListState, contentPadding = PaddingValues(bottom = 28.dp)) {
          itemsIndexed(sections, key = { _, section -> section.header.key }) { index, section ->
            QoderActivityItem(section.header, section.bodies, onSelected, onFile, hasNext = index < sections.lastIndex)
          }
        }
      } else {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
          HyperIconButton(QoderGlyph.BACK, "返回操作列表", if (latestSelected.key == group.key) onDismiss else onBack, backgroundColor = QoderColors.codeColor, tint = QoderColors.secondary)
          Text(latestSelected.title.ifBlank { "详情" }, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.Bold))
          HyperIconButton(QoderGlyph.CLOSE, "关闭详情", onDismiss, backgroundColor = QoderColors.codeColor, tint = QoderColors.secondary)
        }
        key(latestSelected.key) {
          LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 22.dp), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val bodies = details.filter { it.bodyOf == latestSelected.key }
            // Projected chunks are the sole body source, including reasoning: never repeat source.text.
            val bodyKeys = uniqueListKeys(bodies) { it.key }
            itemsIndexed(bodies, key = { index, _ -> bodyKeys[index] }) { _, body ->
              when (body.kind) {
                "reasoning-body", "process-output" -> DetailMarkdown(body.text)
                "tool-body" -> DetailCode(body.title, body.text, readContent = latestSelected.source?.tool == "read" && body.title != "文件路径", copyText = body.copyText)
                else -> DetailRowView(body)
              }
            }
            val attachments = latestSelected.attachments.distinctBy { it.url }
            val attachmentKeys = uniqueListKeys(attachments) { "attachment:${it.url}" }
            items(attachments.size, key = { attachmentKeys[it] }) { index ->
              AttachmentView(attachments[index], onFile)
            }
            if (bodies.isEmpty() && latestSelected.attachments.isEmpty()) item { Text(if (latestSelected.status in setOf("running", "pending")) "等待服务器输出…" else "此操作没有正文", color = QoderColors.secondary) }
            latestSelected.target?.let { child -> item { HyperTextAction(text = "打开子会话", onClick = { onOpenChild(child) }) } }
          }
        }
      }
    }
  }
}

private fun qoderSections(rows: List<TranscriptRow>): List<DetailSection> {
  val result = mutableListOf<DetailSection>()
  var header: TranscriptRow? = null
  val bodies = mutableListOf<TranscriptRow>()
  rows.forEach { row ->
    if (row.bodyOf == null) {
      header?.let { result += DetailSection(it, bodies.toList()) }
      header = row
      bodies.clear()
    } else bodies += row
  }
  header?.let { result += DetailSection(it, bodies.toList()) }
  return result
}

/**
 * 新建会话：先进入空白页，发出第一条消息时才在服务器上创建会话（失败时正文和附件都保留）。
 * 目标服务器与工作区都在标题里选择；「服务器」永远可换（离线也保留），工作区随服务器变化。
 */
@Composable
fun DraftScreen(
  state: LagoonState,
  controller: LagoonController,
  onBack: () -> Unit,
  onModal: (Boolean) -> Unit,
  interactive: Boolean = true,
  profileIds: Set<String>? = null,
  onSwitchServer: (ServerProfile) -> Unit = {}
) {
  var targetPicker by remember { mutableStateOf(false) }
  var composerModal by remember { mutableStateOf(false) }
  val modal = targetPicker || composerModal
  DisposableEffect(modal) { onModal(modal); onDispose { onModal(false) } }
  val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
  val connectionHint = when {
    state.connected -> null
    state.loading -> "连接中…"
    else -> "未连接"
  }
  Column(Modifier.fillMaxSize()) {
    PageTopBar("新会话", onBack, subtitle = {
      // Where the session will be created: always tappable, even while connecting or offline, so the
      // reader can switch harness without leaving the blank page.
      Row(
        Modifier.clip(miuixSquircleShape(16.dp)).clickable { targetPicker = true }.padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        StatusDot(
          when {
            state.connected -> HyperTheme.colors.success
            state.loading -> HyperTheme.colors.warning
            else -> HyperTheme.colors.textTertiary
          }
        )
        Spacer(Modifier.width(6.dp))
        Icon(MiuixIcons.Folder, null, Modifier.size(14.dp), tint = HyperTheme.colors.textSecondary)
        Spacer(Modifier.width(4.dp))
        state.server?.let {
          HarnessBadge(it.kind)
          Spacer(Modifier.width(4.dp))
        }
        Text(
          listOfNotNull(state.server?.name, state.project?.name ?: "选择工作区", connectionHint).joinToString(" · "),
          maxLines = 1, overflow = TextOverflow.Ellipsis,
          style = MiuixTheme.textStyles.footnote1.copy(color = HyperTheme.colors.textSecondary)
        )
        DropdownChevron(14.dp, description = "选择服务器与工作区")
      }
    })
    ConnectionStrip(state, controller)
    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("要做点什么？", style = MiuixTheme.textStyles.title3)
        Spacer(Modifier.height(8.dp))
        Text(
          when {
            state.project != null -> "会在「${state.project!!.name}」中新建，发出第一条消息后才会创建会话"
            !state.connected -> "正在等待服务器连接；连上后点标题选择工作区"
            else -> "先点标题选择服务器与工作区"
          },
          style = MiuixTheme.textStyles.footnote1.copy(color = muted),
          textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
      }
    }
    ChatComposer(state, controller, interactive, { composerModal = it }, busy = state.pending("create"), actionKey = "create", ready = state.project != null,
      // Switching harness from this page must not reset the composer: the text belongs to the
      // draft, not to one server. The page key (draft nonce) still isolates separate drafts.
      resetKey = DRAFT_SESSION,
      // The created session inherits the text as its draft until the first send succeeds; each draft page
      // gets a fresh saveable key from MainActivity, so nothing needs clearing here.
      onSend = { text, _ -> controller.startSession("", text, state.agent.takeIf { state.agentChanged }, state.model.takeIf { state.modelChanged }) })
  }
  if (targetPicker) DraftDestinationSheet(state, controller, profileIds, onSwitchServer) { targetPicker = false }
}

/** “服务器 · 工作区” under a chat title, led by the session's live state (running spinner / waiting / failed). */
@Composable
private fun SessionSubtitle(state: LagoonState, session: Session) {
  // 子会话不在根聚合里：优先用该会话自己的实时任务，再退回根聚合 / 未读账本。
  val own = state.livePresentedTasks[session.id]
  val status = if (own != null) state.sessionStatus(session, mapOf(session.id to own)) else state.sessionStatus(session)
  
  when (status) {
    SessionStatus.RUNNING -> { InfiniteProgressIndicator(color = HyperTheme.colors.primary, size = 12.dp, strokeWidth = 1.5.dp, orbitingDotSize = 1.5.dp); Spacer(Modifier.width(6.dp)) }
    SessionStatus.WAITING_PERMISSION, SessionStatus.WAITING_QUESTION, SessionStatus.FAILED ->
      Text("${status.label} · ", style = MiuixTheme.textStyles.footnote1.copy(color = if (status == SessionStatus.FAILED) HyperTheme.colors.error else HyperTheme.colors.warning))
    SessionStatus.WAITING_SERVER ->
      Text("${status.label} · ", style = MiuixTheme.textStyles.footnote1.copy(color = HyperTheme.colors.textSecondary))
    else -> {}
  }
  val profile = session.serverId?.let { id -> state.profiles.firstOrNull { it.id == id } } ?: state.server
  if (state.multiServerView) profile?.let { HarnessBadge(it.kind); Spacer(Modifier.width(4.dp)) }
  // 与首页行使用同一套解析，切进会话后工作区名不会变。
  val workspace = resolveSessionWorkspace(session, state.projects, state.workspaceCatalogFor(session))?.name
  Text(listOfNotNull(profile?.name, workspace).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis,
    style = MiuixTheme.textStyles.footnote1.copy(color = HyperTheme.colors.textSecondary))
}

/** MIUIX dialog footer: two equal-width buttons, the confirming one in the primary (or error) color. */
@Composable
internal fun DialogActions(cancel: String, onCancel: () -> Unit, confirm: String, enabled: Boolean = true, danger: Boolean = false, onConfirm: () -> Unit) {
  Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    HyperButton(text = cancel, variant = HyperButtonVariant.SECONDARY, onClick = onCancel, modifier = Modifier.weight(1f))
    HyperButton(
      text = confirm,
      variant = if (danger) HyperButtonVariant.DESTRUCTIVE else HyperButtonVariant.PRIMARY,
      onClick = onConfirm,
      enabled = enabled,
      modifier = Modifier.weight(1f)
    )
  }
}

@Composable
internal fun ActionError(state: LagoonState, action: String) { state.resource("action:$action").takeIf { it.state == ResourceState.ERROR }?.error?.let { Text(it, color = MiuixColorTokens.Error) } }

@Composable
fun MiuixPermissionCard(request: PermissionRequest, controller: LagoonController, pending: Boolean, onModal: (Boolean) -> Unit = {}) {
  var confirmSave by rememberSaveable(request.id) { mutableStateOf(false) }
  DisposableEffect(confirmSave) { if (confirmSave) onModal(true); onDispose { if (confirmSave) onModal(false) } }
  if (confirmSave) {
    SuperDialog(
      title = "保存项目权限规则",
      show = confirmSave,
      onDismissRequest = { confirmSave = false }
    ) {
      Column(Modifier.padding(top = 8.dp)) {
        Text(
          "规则将保存在服务器项目中，适用于后续任务。\n" + request.always.joinToString("\n"),
          style = MiuixTheme.textStyles.body2
        )
        DialogActions("取消", { confirmSave = false }, if (pending) "正在提交…" else "保存并允许", !pending) {
          confirmSave = false
          controller.replyPermission(request, "always")
        }
      }
    }
  }

  val isDangerous = request.action.contains("shell", true) || request.action.contains("write", true) || request.action.contains("delete", true)

  HyperCard(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    backgroundColor = if (isDangerous) HyperTheme.colors.warningSubtle else HyperTheme.colors.cardElevated,
    borderColor = if (isDangerous) HyperTheme.colors.warning.copy(alpha = 0.35f) else HyperTheme.colors.cardBorder,
    insidePadding = PaddingValues(16.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      MiuixStatePill(TaskPhase.WAITING_PERMISSION, if (isDangerous) "需确认关键操作" else "等待操作授权")
      Spacer(Modifier.weight(1f))
      Text(
        request.action.ifBlank { "操作" },
        style = MiuixTheme.textStyles.footnote2.copy(
          color = HyperTheme.colors.primary,
          fontWeight = FontWeight.Bold
        )
      )
    }
    Spacer(Modifier.height(10.dp))
    Text("服务器正在请求执行动作：", style = MiuixTheme.textStyles.headline2.copy(fontWeight = FontWeight.SemiBold))
    Spacer(Modifier.height(8.dp))
    Text(
      text = request.detail,
      fontFamily = FontFamily.Monospace,
      style = MiuixTheme.textStyles.body2,
      modifier = Modifier
        .fillMaxWidth()
        .background(HyperTheme.colors.codeSurface, miuixSquircleShape(8.dp))
        .padding(10.dp)
    )
    Spacer(Modifier.height(12.dp))
    if (request.always.isNotEmpty()) {
      Text(
        text = "选择“始终允许”将在服务器保存规则：" + request.always.joinToString(", "),
        style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
      )
      Spacer(Modifier.height(10.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      HyperButton(text = "拒绝", variant = HyperButtonVariant.SECONDARY, onClick = { controller.replyPermission(request, "reject") }, enabled = !pending, modifier = Modifier.weight(1f))
      HyperButton(text = "允许一次", onClick = { controller.replyPermission(request, "once") }, enabled = !pending, loading = pending, modifier = Modifier.weight(1f))
    }
    if (request.always.isNotEmpty()) {
      HyperTextAction(text = "始终允许并保存规则…", enabled = !pending, onClick = { confirmSave = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
  }
}

@Composable
fun MiuixQuestionCard(request: QuestionRequest, controller: LagoonController, pending: Boolean) {
  val answerSaver = remember(request.id) { Saver<List<List<String>>, String>(save = { rows -> org.json.JSONArray().apply { rows.forEach { put(org.json.JSONArray(it)) } }.toString() }, restore = { json ->
    val rows = org.json.JSONArray(json); (0 until rows.length()).map { i -> val row = rows.getJSONArray(i); (0 until row.length()).map { row.optString(it) } }
  }) }
  var answers by rememberSaveable(request.id, request.questions.hashCode(), stateSaver = answerSaver) { mutableStateOf(request.questions.map { question -> question.defaultAnswers().filter { value -> question.options.any { it.value == value } } }) }
  var custom by rememberSaveable(request.id, request.questions.hashCode()) { mutableStateOf(request.questions.map { question ->
    question.defaultAnswers().firstOrNull()?.takeIf { value -> question.options.none { it.value == value } }.orEmpty()
  }) }
  val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
  val answerRows = answers.mapIndexed { index, list -> custom[index].trim().takeIf { it.isNotEmpty() }?.let { if (request.questions[index].multiple) list + it else listOf(it) } ?: list }
  // 服务器字段是外部数据：畸形 JSON 只让该字段退化为“无 key”，不能让组合期崩溃。
  val fieldValues = if (request.form) request.questions.mapIndexedNotNull { i, q ->
    runCatching { org.json.JSONObject(q.field).str("key") }.getOrNull()?.let { it to answerRows[i] }
  }.toMap() else emptyMap()
  // Form fields with a `when` condition can vanish as earlier answers change, so page over what is visible now.
  fun questionField(index: Int) = request.questions[index].field.takeIf { it.isNotBlank() }
    ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
  val visibleIndices = request.questions.indices.filter { index ->
    val field = questionField(index)
    field == null || formVisible(field, fieldValues)
  }
  val lastPage = visibleIndices.lastIndex.coerceAtLeast(0)
  var page by rememberSaveable(request.id, request.questions.hashCode()) { mutableIntStateOf(0) }
  val currentPage = page.coerceIn(0, lastPage)
  fun answered(index: Int): Boolean {
    val question = request.questions[index]
    val field = questionField(index)
    if (field?.str("type") == "external") return true
    val chosen = answers.getOrElse(index) { emptyList() }.isNotEmpty()
    val text = custom.getOrElse(index) { "" }.trim().isNotEmpty()
    val type = field?.str("type")
    val customAllowed = question.custom || type == "string" || type == "number" || type == "integer"
    return chosen || (customAllowed && text)
  }
  fun required(index: Int): Boolean = if (request.form) questionField(index)?.optBoolean("required") == true else true
  val currentIndex = visibleIndices.getOrNull(currentPage)
  val currentReady = currentIndex == null || !required(currentIndex) || answered(currentIndex)
  val submitEnabled = !pending && (if (request.form) runCatching { formAnswer(request, answerRows) }.isSuccess
    else answers.indices.all { answers[it].isNotEmpty() || (request.questions[it].custom && custom[it].isNotBlank()) })

  HyperCard(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    backgroundColor = HyperTheme.colors.cardElevated,
    borderColor = HyperTheme.colors.cardBorder,
    insidePadding = PaddingValues(16.dp)
  ) {
    MiuixStatePill(TaskPhase.WAITING_QUESTION, "需要你的回答")
    if (visibleIndices.size > 1) {
      Spacer(Modifier.height(10.dp))
      Text("第 ${currentPage + 1}/${visibleIndices.size} 项", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    }
    val index = currentIndex
    if (index == null) {
      Spacer(Modifier.height(10.dp))
      Text("这个表单暂无可填写的字段。", style = MiuixTheme.textStyles.body2)
    } else {
      val question = request.questions[index]
      val field = questionField(index)
      Spacer(Modifier.height(10.dp))
      Text(question.title, style = MiuixTheme.textStyles.headline2.copy(fontWeight = FontWeight.SemiBold))
      if (field?.str("type") == "external") {
        val url = field.str("url")
        if (url.startsWith("https://")) {
          HyperButton(text = "打开外部表单页面", variant = HyperButtonVariant.SECONDARY, onClick = { uriHandler.openUri(url) })
        } else {
          Text("外部页面仅支持 HTTPS，请在服务器确认此项", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixColorTokens.Warning))
        }
      } else {
        question.options.forEach { option ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable {
                answers = answers.toMutableList().also { list ->
                  list[index] = if (question.multiple) {
                    if (option.value in list[index]) list[index] - option.value else list[index] + option.value
                  } else listOf(option.value)
                }
                if (!question.multiple) custom = custom.toMutableList().also { it[index] = "" }
              }
              .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
              if (question.multiple) {
                Checkbox(checked = option.value in answers[index], onCheckedChange = null)
              } else {
                RadioButton(selected = option.value in answers[index], onClick = null)
              }
            }
            Spacer(Modifier.width(10.dp))
            Column {
              Text(option.label, style = MiuixTheme.textStyles.body2)
              if (option.description.isNotBlank()) {
                Text(option.description, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
              }
            }
          }
        }
        if (question.custom) {
          Spacer(Modifier.height(6.dp))
          TextField(
            value = custom[index],
            onValueChange = { value: String -> custom = custom.toMutableList().also { it[index] = value } },
            useLabelAsPlaceholder = true,
            label = "输入自定义回答…",
            modifier = Modifier.fillMaxWidth()
          )
        }
      }
    }
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      HyperButton(text = "取消", variant = HyperButtonVariant.SECONDARY, enabled = !pending, onClick = { if (!pending) controller.rejectQuestion(request) }, modifier = Modifier.weight(1f))
      if (currentPage > 0) {
        HyperButton(text = "上一页", variant = HyperButtonVariant.SECONDARY, enabled = !pending, onClick = { page = currentPage - 1 }, modifier = Modifier.weight(1f))
      }
      if (currentPage < lastPage) {
        HyperButton(text = "下一页", enabled = !pending && currentReady, onClick = { page = currentPage + 1 }, modifier = Modifier.weight(1f))
      } else {
        HyperButton(text = "提交回答", enabled = submitEnabled, loading = pending,
          onClick = {
            controller.replyQuestion(request, answers.mapIndexed { idx, list ->
              val value = custom[idx].trim()
              if (value.isBlank()) list else if (request.questions[idx].multiple) list + value else listOf(value)
            })
          },
          modifier = Modifier.weight(1f))
      }
    }
  }
}



/**
 * Qoder-style composer. At rest it is one pill — ＋, Agent, “描述你的任务…”, send — and while focused or
 * holding a draft it grows into a card: the text on top, then ＋ / Agent / model / send. The text field
 * keeps its place in both states, so focus and the IME survive the change.
 */
@Composable
private fun ChatComposer(
  state: LagoonState, controller: LagoonController, interactive: Boolean, onModal: (Boolean) -> Unit,
  busy: Boolean = state.pending("send"), actionKey: String = "send", ready: Boolean = true,
  /** Stable identity across a server switch (blank draft); null keeps the server/session identity. */
  resetKey: Any? = null,
  onSend: (String, () -> Unit) -> Unit = { text, clear -> controller.send(text, state.serverId, state.sessionId, clear) },
  onServerFile: (() -> Unit)? = null
) {
  val identity: Any = resetKey ?: (state.serverId to state.sessionId)
  var value by rememberSaveable(identity, stateSaver = TextFieldValue.Saver) {
    mutableStateOf(TextFieldValue(controller.draftFor(state.serverId, state.sessionId, state.draft)))
  }
  // Typing stays local (controller.typeDraft does not re-broadcast state); only an app-side set of the
  // draft – restored, returned from the queue, cleared after a send – is adopted here. The applied
  // signature is saved so a recreated composer keeps its restored text instead of the older state value.
  var applied by rememberSaveable(identity) { mutableStateOf(state.draft to state.draftRevision) }
  var attachMenu by remember { mutableStateOf(false) }
  var focused by remember { mutableStateOf(false) }
  val images = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { controller.addLocalAttachments(it) }
  val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { controller.addLocalAttachments(it) }
  LaunchedEffect(state.draft, state.draftRevision) {
    val signature = state.draft to state.draftRevision
    if (signature == applied) return@LaunchedEffect
    applied = signature
    if (value.text != state.draft) value = TextFieldValue(state.draft, androidx.compose.ui.text.TextRange(state.draft.length))
  }
  DisposableEffect(attachMenu) { onModal(attachMenu); onDispose { onModal(false) } }
  val task = state.presentedTasks[state.sessionId]
  val sending = busy
  val hasContent = value.text.isNotBlank() || state.references.isNotEmpty() || state.attachments.isNotEmpty()
  val expanded = focused || value.text.isNotEmpty() || state.attachments.isNotEmpty() || state.references.isNotEmpty()
  // Like the official composer, a prompt typed while the agent works steers the running turn.
  // While the server is connecting or retrying, a typed prompt is held and delivered once it answers.
  val queueing = actionKey == "send" && state.canQueueSend
  val canSend = ready && !sending && hasContent && (state.connected || queueing && value.text.isNotBlank())
  val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
  val hint = when {
    queueing -> "未连接 · 现在发送，连接恢复后会自动发出"
    !state.connected -> if (state.loading) "正在连接…草稿会保留" else "离线 · 草稿保留，恢复连接后可发送"
    sending -> "正在发送…"
    state.attachments.any { it.image } && state.modelCatalog.firstOrNull { it.choice.modelId == state.model?.modelId && it.choice.providerId == state.model?.providerId }?.imageInput == false -> "当前模型不支持图片，发送前请换一个模型"
    else -> null
  }
  val attach: @Composable () -> Unit = {
    Box {
      HyperIconButton(QoderGlyph.PLUS, "添加图片或文件", { attachMenu = true }, enabled = !sending && interactive, backgroundColor = Color.Transparent)
      MenuPopup(attachMenu, { attachMenu = false }, listOf(MenuSection(listOfNotNull(
        MenuAction("图片", MiuixIcons.Image) { images.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        MenuAction("手机文件", MiuixIcons.File) { documents.launch(arrayOf("*/*")) },
        onServerFile?.let { MenuAction("服务器文件", MiuixIcons.Folder, onClick = it) }
      ))), alignment = PopupPositionProvider.Align.Start)
    }
  }
  val stopAction: @Composable () -> Unit = {
    if (shouldShowGenerationStop(task?.phase in TaskState.RUNNING_PHASES)) {
      HyperIconButton(
        glyph = QoderGlyph.STOP,
        description = "停止当前任务",
        onClick = { controller.abort() },
        enabled = !state.pending("abort") && interactive,
        backgroundColor = HyperTheme.colors.errorSubtle,
        tint = HyperTheme.colors.error
      )
    }
  }
  val sendAction: @Composable () -> Unit = {
    HyperIconButton(
      glyph = QoderGlyph.SEND,
      description = if (task?.phase in TaskState.RUNNING_PHASES) "发送并引导当前任务" else "发送",
      onClick = {
        val draft = value.text
        onSend(draft) { if (value.text == draft) { controller.updateDraft(""); value = TextFieldValue("") } }
      },
      enabled = canSend && interactive,
      backgroundColor = if (canSend) HyperTheme.colors.primary else HyperTheme.colors.container,
      tint = if (canSend) HyperTheme.colors.onPrimary else HyperTheme.colors.textTertiary
    )
  }
  val composerActions: @Composable () -> Unit = {
    Row(verticalAlignment = Alignment.CenterVertically) {
      stopAction()
      sendAction()
    }
  }
  Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)) {
    Column(Modifier.fillMaxWidth().clip(miuixSquircleShape(if (expanded) 24.dp else 28.dp)).background(HyperTheme.colors.cardElevated)
      .border(0.6.dp, HyperTheme.colors.cardBorder, miuixSquircleShape(if (expanded) 24.dp else 28.dp))
      .animateContentSize().padding(6.dp)) {
      hint?.let { Text(it, Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp), color = if (sending) muted else HyperTheme.colors.warning, style = MiuixTheme.textStyles.footnote2) }
      if (state.attachments.isNotEmpty() || state.references.isNotEmpty()) Box(Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp)) { AttachmentTray(state, controller) }
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (!expanded) { attach(); AgentSelector(state, controller, enabled = !sending && interactive, iconOnly = true) }
        Box(Modifier.weight(1f).heightIn(min = 48.dp, max = 168.dp).padding(horizontal = if (expanded) 10.dp else 2.dp, vertical = 11.dp),
          contentAlignment = Alignment.CenterStart) {
          if (value.text.isEmpty()) Text("描述你的任务…", color = muted, style = MiuixTheme.textStyles.body1, maxLines = 1)
          BasicTextField(value, { next -> value = next; controller.typeDraft(next.text, state.serverId, state.sessionId) },
            Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            textStyle = MiuixTheme.textStyles.body1.copy(color = HyperTheme.colors.textPrimary), cursorBrush = SolidColor(HyperTheme.colors.primary), maxLines = 6, readOnly = !interactive)
        }
        if (!expanded) { composerActions(); Spacer(Modifier.width(2.dp)) }
      }
      if (expanded) Row(verticalAlignment = Alignment.CenterVertically) {
        attach()
        // Three compact dropdowns: agent, model, thinking strength. They take the remaining width;
        // send stays at the end.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
          AgentSelector(state, controller, enabled = !sending && interactive)
          Spacer(Modifier.width(6.dp))
          ModelSelector(state, controller, Modifier.weight(1f, fill = false), enabled = !sending && interactive)
          Spacer(Modifier.width(6.dp))
          VariantSelector(state, controller, enabled = !sending && interactive)
        }
        Spacer(Modifier.width(6.dp))
        composerActions()
        Spacer(Modifier.width(2.dp))
      }
    }
    ActionError(state, actionKey)
  }
}

/** Pending phone attachments (thumbnails / file chips) and server file references, each removable. */
@Composable
private fun AttachmentTray(state: LagoonState, controller: LagoonController) {
  Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    state.attachments.forEach { item ->
      if (item.image) ImageThumb(item) { controller.removeLocalAttachment(item.id) }
      else FileChip(item.name, "${(item.size + 1023) / 1024} KB") { controller.removeLocalAttachment(item.id) }
    }
    state.references.forEach { ref -> FileChip(ref.path.substringAfterLast('/'), "服务器文件") { controller.removeReference(ref.path) } }
  }
}

@Composable
private fun ImageThumb(item: LocalAttachment, onRemove: () -> Unit) {
  val bitmap by produceState<android.graphics.Bitmap?>(null, item.path) {
    value = withContext(Dispatchers.IO) { runCatching {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(item.path, bounds)
      BitmapFactory.decodeFile(item.path, BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 256).coerceAtLeast(1) })
    }.getOrNull() }
  }
  Box(Modifier.size(56.dp).clip(miuixSquircleShape(12.dp)).background(HyperTheme.colors.container)) {
    bitmap?.let { Image(it.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    RemoveBadge(Modifier.align(Alignment.TopEnd), onRemove)
  }
}

@Composable
private fun FileChip(name: String, detail: String, onRemove: () -> Unit) {
  Row(Modifier.height(56.dp).clip(miuixSquircleShape(12.dp)).background(HyperTheme.colors.container).padding(start = 12.dp, end = 4.dp),
    verticalAlignment = Alignment.CenterVertically) {
    Icon(MiuixIcons.File, null, Modifier.size(18.dp), tint = HyperTheme.colors.textSecondary)
    Spacer(Modifier.width(8.dp))
    Column(Modifier.widthIn(max = 160.dp)) {
      Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium, color = HyperTheme.colors.textPrimary))
      Text(detail, maxLines = 1, style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary))
    }
    RemoveBadge(Modifier, onRemove)
  }
}

@Composable
private fun RemoveBadge(modifier: Modifier, onRemove: () -> Unit) {
  Box(modifier.size(48.dp).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
    Box(Modifier.size(18.dp).clip(miuixSquircleShape(9.dp)).background(HyperTheme.colors.textTertiary), contentAlignment = Alignment.Center) {
      Icon(MiuixIcons.Close, "移除", Modifier.size(12.dp), tint = HyperTheme.colors.canvas)
    }
  }
}

@Composable
private fun SchedulesPanel(state: LagoonState, controller: LagoonController) {
  if (state.schedulesLoading) { Text("正在读取定时任务…", style = MiuixTheme.textStyles.footnote2); return }
  if (state.schedules.isEmpty()) {
    Text("此会话没有定时任务。可让 Agent 创建定时任务。",
      style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    HyperTextAction(text = "刷新", onClick = { controller.loadSchedules() })
    return
  }
  LazyColumn(Modifier.fillMaxWidth()) {
    val scheduleKeys = uniqueListKeys(state.schedules) { it.id }
    itemsIndexed(state.schedules, key = { index, _ -> scheduleKeys[index] }) { _, schedule ->
      HyperActionRow(title = schedule.title, summary = "${schedule.detail} · ${schedule.scheduledAt}",
        onClick = { controller.loadSchedules() })
      Text(schedule.prompt, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary), maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
    }
  }
}

@Composable
private fun FilesPanel(state: LagoonState, controller: LagoonController, onReference: (String) -> Unit) {
  var query by rememberSaveable { mutableStateOf("") }
  val searching = query.trim().length >= 2
  val path = state.filePath
  val previewBytes = state.filePreview.takeUnless { searching }
  val previewMime = state.filePreviewMime
  val viewingFile = state.fileText != null || state.fileBinary || previewBytes != null
  val crumbs = remember(path) { if (path == "." || path.isBlank()) emptyList() else HomeScope.crumbs(path) }
  val nodes = remember(state.files, state.searchResults, searching) {
    (if (searching) state.searchResults.map { FileNode(it, "file") } else state.files).distinctBy { it.path }
      .sortedWith(compareBy({ it.type != "directory" }, { it.path.lowercase() }))
  }
  Column(Modifier.fillMaxSize()) {
    TextField(query, { query = it; controller.searchFiles(it) }, label = "搜索工程文件", useLabelAsPlaceholder = true, modifier = Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
      if (viewingFile) {
        Text("← 返回列表", Modifier.heightIn(min = 48.dp).clickable {
          val parent = path.substringBeforeLast('/', ".").ifBlank { "." }
          controller.listFiles(if (parent == path) "." else parent)
        }.wrapContentHeight(Alignment.CenterVertically).padding(end = 8.dp), style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.primary))
      }
      Text("项目", Modifier.heightIn(min = 48.dp).clickable { controller.listFiles(".") }.wrapContentHeight(Alignment.CenterVertically),
        style = MiuixTheme.textStyles.footnote2.copy(color = if (path == "." || path.isBlank()) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary))
      crumbs.forEachIndexed { index, (label, target) ->
        Text("/", Modifier.padding(horizontal = 4.dp), style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        Text(label, Modifier.heightIn(min = 48.dp).clickable { controller.listFiles(target) }.wrapContentHeight(Alignment.CenterVertically),
          style = MiuixTheme.textStyles.footnote2.copy(color = if (index == crumbs.lastIndex) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary))
      }
    }
    ResourceHint(state.resource(if (searching) "search" else "files"), "此目录没有文件", retry = { if (searching) controller.searchFiles(query) else controller.listFiles() })
    if (previewBytes != null) {
      HyperButton(text = "引用到消息", onClick = { onReference(state.filePath) })
      FilePreviewView(previewMime, previewBytes, Modifier.weight(1f))
    } else if (state.fileBinary && !searching) {
      Text("当前无法预览此二进制文件")
      HyperButton(text = "引用到消息", enabled = !state.pending("send"), onClick = { onReference(state.filePath) })
    } else if (state.fileText?.takeUnless { searching } != null) {
      HyperButton(text = "引用到消息", onClick = { onReference(state.filePath) })
      VirtualText(state.fileText!!, Modifier.weight(1f), highlight = false)
    } else if (!state.fileBinary || searching) {
      val nodeKeys = uniqueListKeys(nodes) { it.path }
      LazyColumn { itemsIndexed(nodes, key = { index, _ -> nodeKeys[index] }) { _, node ->
        val directory = node.type == "directory"
        HyperActionRow(title = node.path.substringAfterLast('/').ifBlank { node.path }, summary = if (directory) "文件夹" else node.path,
          showChevron = true,
          onClick = { if (directory) controller.listFiles(node.path) else controller.readFile(node.path) })
      } }
    }
  }
}

@Composable
private fun FilePreviewView(mime: String?, bytes: ByteArray, modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val bitmap by produceState<android.graphics.Bitmap?>(null, mime, bytes) {
    value = withContext(Dispatchers.IO) {
      runCatching {
        when {
          mime?.startsWith("image/") == true -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
          mime == "application/pdf" -> renderPdfFirstPage(context, bytes)
          else -> null
        }
      }.getOrNull()
    }
  }
  Column(modifier.fillMaxWidth()) {
    if (mime == "application/pdf") Text("仅预览第 1 页", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    val image = bitmap
    if (image != null) Image(image.asImageBitmap(), contentDescription = "文件预览", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
    else Text("预览渲染失败", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
  }
}

private fun renderPdfFirstPage(context: android.content.Context, bytes: ByteArray): android.graphics.Bitmap? {
  val file = File.createTempFile("lagoon-preview", ".pdf", context.cacheDir)
  return try {
    file.writeBytes(bytes)
    android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
      android.graphics.pdf.PdfRenderer(descriptor).use { renderer ->
        if (renderer.pageCount <= 0) return null
        renderer.openPage(0).use { page ->
          val scale = (1200f / page.width.coerceAtLeast(1)).coerceIn(1f, 3f)
          val bitmap = android.graphics.Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
            (page.height * scale).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
          bitmap.eraseColor(android.graphics.Color.WHITE)
          page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
          bitmap
        }
      }
    }
  } finally { runCatching { file.delete() } }
}

internal fun editDraft(drafts: MutableMap<String, String>, key: String, value: String) {
  drafts[key] = value
  drafts["revision:$key"] = ((drafts["revision:$key"]?.toLongOrNull() ?: 0L) + 1).toString()
}
