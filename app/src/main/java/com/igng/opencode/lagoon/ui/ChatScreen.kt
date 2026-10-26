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
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperListPopup
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.miuixCapsuleShape
import top.yukonga.miuix.kmp.theme.miuixShape

private enum class ChatSheet { CHANGES, FILES, CHILDREN }

@Composable
fun ChatScreen(state: LagoonState, controller: LagoonController, onBack: () -> Unit, onOpenChild: (String) -> Unit, onModal: (Boolean) -> Unit, interactive: Boolean = true) {
  val session = state.session
  var sheet by remember { mutableStateOf<ChatSheet?>(null) }
  var menu by remember { mutableStateOf(false) }
  var rename by rememberSaveable { mutableStateOf(false) }
  var delete by rememberSaveable { mutableStateOf(false) }
  var composerModal by remember { mutableStateOf(false) }
  var requestModal by remember { mutableStateOf(false) }
  var activityGroup by remember(state.serverId, state.sessionId) { mutableStateOf<TranscriptRow?>(null) }
  var activityItem by remember(state.serverId, state.sessionId) { mutableStateOf<TranscriptRow?>(null) }
  val focusManager = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  val modal = sheet != null || menu || rename || delete || composerModal || requestModal || activityGroup != null
  DisposableEffect(modal) { onModal(modal); onDispose { onModal(false) } }
  fun openFile(path: String) { sheet = ChatSheet.FILES; controller.readFile(path) }
  if (session == null) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) { Text("会话尚未加载或已关闭"); TextButton(text = "返回列表", onClick = onBack) }
    return
  }
  // ⋯ 分两段：查看会话的资源，以及对会话本身的操作。整理上下文 / 撤销 / 分享不在手机上提供，已保存权限在设置页；
  // 分叉与删除按 harness 能力显示（DSH 没有消息级撤销与删除，用分叉与归档代替）。
  val menuSections = listOf(
    MenuSection(buildList {
      add(MenuAction("子任务（${state.children.size}）", MiuixIcons.MindMap) { sheet = ChatSheet.CHILDREN })
      if (state.capabilities.diff) add(MenuAction("改动（${state.changes.size}）", MiuixIcons.Merge) { sheet = ChatSheet.CHANGES })
      add(MenuAction("文件", MiuixIcons.Folder) { sheet = ChatSheet.FILES; controller.listFiles() })
    }),
    MenuSection(buildList {
      add(MenuAction(if (session.id in state.pinned) "取消置顶" else "置顶", if (session.id in state.pinned) MiuixIcons.Unpin else MiuixIcons.Pin) { controller.togglePin(session.id) })
      if (state.capabilities.supports(SessionAction.RENAME)) add(MenuAction("重命名", MiuixIcons.Rename) { rename = true })
      if (state.capabilities.fork) add(MenuAction("分叉", MiuixIcons.Copy) { controller.fork() })
      state.capabilities.archive?.let { archive ->
        if (!session.archived) add(MenuAction("归档", ARCHIVE_ICON) { controller.setArchived(session.id, true, onBack) })
        else if (archive.restorable) add(MenuAction("取消归档", UNARCHIVE_ICON) { controller.setArchived(session.id, false) })
      }
      if (state.capabilities.deleteSession) add(MenuAction("删除", MiuixIcons.Delete, danger = true) { delete = true })
    })
  )
  QoderChatTheme {
    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
      Column(Modifier.fillMaxSize()) {
        QoderChatTopBar(state.title(session), onBack, subtitle = { SessionSubtitle(state, session) }) {
          Box {
            QoderCircleButton(QoderGlyph.MORE, "会话操作", { menu = true }, background = Color.Transparent)
            MenuPopup(menu, { menu = false }, menuSections)
          }
        }
        ConnectionStrip(state, controller)
        Conversation(state, controller, Modifier.weight(1f).fillMaxWidth(), ::openFile, { requestModal = it }, onOpenChild,
          onActivityGroup = {
            focusManager.clearFocus(); keyboard?.hide()
            activityGroup = it
            activityItem = it.takeIf { row -> row.kind == "tool" }
          })
        ChatComposer(state, controller, interactive, onModal = { composerModal = it }, onServerFile = { sheet = ChatSheet.FILES; controller.listFiles() })
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
    val resource = when (which) { ChatSheet.CHANGES -> "changes"; ChatSheet.FILES -> "files"; ChatSheet.CHILDREN -> "children" }
    SuperBottomSheet(title = when (which) { ChatSheet.CHANGES -> "代码改动"; ChatSheet.FILES -> "文件"; ChatSheet.CHILDREN -> "子任务" }, show = true, onDismissRequest = { sheet = null }) {
      Column(Modifier.fillMaxWidth().fillMaxHeight(0.72f).padding(vertical = 8.dp)) {
        if (which != ChatSheet.FILES) ResourceHint(state.resource(resource), "暂无记录", retry = controller::reload)
        when (which) {
          // Server lists may repeat an entry; LazyColumn crashes on a duplicate key, so keys never come from data alone.
          ChatSheet.CHANGES -> LazyColumn { itemsIndexed(state.changes, key = { index, change -> "$index:${change.path}" }) { _, change ->
            var expanded by rememberSaveable(change.path) { mutableStateOf(false) }
            BasicComponent(title = change.path, summary = "+${change.additions} −${change.deletions}", onClick = { expanded = !expanded })
            if (expanded) VirtualText(change.patch.ifBlank { change.after })
          } }
          ChatSheet.FILES -> FilesPanel(state, controller) { path -> controller.addReference(path); sheet = null }
          ChatSheet.CHILDREN -> LazyColumn { val tasks = state.presentedTasks; items(state.children.distinctBy { it.id }, key = { it.id }) { child ->
            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp), insideMargin = PaddingValues(14.dp), onClick = { sheet = null; onOpenChild(child.id) }) {
               Text(state.title(child)); tasks[child.id]?.takeIf { it.active }?.let { MiuixStatePill(it.phase) }
            }
          } }
        }
      }
    }
  }
  if (rename) RenameSessionDialog(state, controller, session) { rename = false }
  if (delete) SuperDialog(title = "删除此会话？", show = true, onDismissRequest = { if (!state.pending("delete")) delete = false }) {
    Column { Text("会话消息将从服务器永久删除。\n${state.title(session)}"); ActionError(state, "delete")
      DialogActions("取消", { delete = false }, if (state.pending("delete")) "删除中…" else "永久删除", !state.pending("delete"), danger = true) { controller.deleteSession { delete = false; onBack() } }
    }
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
        val latest = TranscriptRows.build(messages, working = working, revertMessageId = revertMessageId, unverified = unverified).firstOrNull { it.key == group.key }
          ?: if (unverified && group.status in setOf("running", "pending")) group.copy(status = "unverified") else group
        latest to TranscriptRows.details(messages, group.key, working, revertMessageId, unverified)
      }
      delay(TranscriptScroll.ROWS_THROTTLE_MS * 2)
    }
  }
  val (latestGroup, details) = content
  val sections = remember(details) { qoderSections(details) }
  // Kept outside the list/detail branch: returning from a detail must restore the reader's place.
  val activityListState = rememberLazyListState()
  val latestSelected = selected?.let { item ->
    val latest = if (item.key == group.key) latestGroup else details.firstOrNull { it.key == item.key } ?: item
    if (unverified && latest.status in setOf("running", "pending")) latest.copy(status = "unverified") else latest
  }
  // Not while animating out: that back belongs to the conversation underneath.
  BackHandler(enabled = interactive && transition.targetState == EnterExitState.Visible) { if (latestSelected != null && latestSelected.key != group.key) onBack() else onDismiss() }
  Box(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.58f))
      .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.74f).align(Alignment.BottomCenter)
      .animateEnterExit(enter = slideInVertically(tween(280, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))) { it / 3 },
        exit = slideOutVertically(tween(200)) { it / 3 })
      .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
      .background(QoderColors.sheetColor).pointerInput(Unit) { detectTapGestures { } }.navigationBarsPadding()) {
      QoderSheetHandle()
      if (latestSelected == null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
          Spacer(Modifier.width(44.dp))
          Text(latestGroup.title.ifBlank { "操作记录" }, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.Bold))
          QoderCircleButton(QoderGlyph.CLOSE, "关闭操作列表", onDismiss, background = QoderColors.codeColor, foreground = QoderColors.secondary)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 22.dp), state = activityListState, contentPadding = PaddingValues(bottom = 28.dp)) {
          itemsIndexed(sections, key = { _, section -> section.header.key }) { index, section ->
            QoderActivityItem(section.header, section.bodies, onSelected, onFile, hasNext = index < sections.lastIndex)
          }
        }
      } else {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
          QoderCircleButton(QoderGlyph.BACK, "返回操作列表", if (latestSelected.key == group.key) onDismiss else onBack, background = QoderColors.codeColor, foreground = QoderColors.secondary)
          Text(latestSelected.title.ifBlank { "详情" }, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.Bold))
          QoderCircleButton(QoderGlyph.CLOSE, "关闭详情", onDismiss, background = QoderColors.codeColor, foreground = QoderColors.secondary)
        }
        key(latestSelected.key) {
          LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 22.dp), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val bodies = details.filter { it.bodyOf == latestSelected.key }
            // Projected chunks are the sole body source, including reasoning: never repeat source.text.
            items(bodies, key = { it.key }) { body ->
              when (body.kind) {
                "reasoning-body", "process-output" -> DetailMarkdown(body.text)
                "tool-body" -> DetailCode(body.title, body.text, readContent = latestSelected.source?.tool == "read" && body.title != "文件路径", copyText = body.copyText)
                else -> DetailRowView(body)
              }
            }
            items(latestSelected.attachments.distinctBy { it.url }, key = { "attachment:${it.url}" }) { attachment -> AttachmentView(attachment, onFile) }
            if (bodies.isEmpty() && latestSelected.attachments.isEmpty()) item { Text(if (latestSelected.status in setOf("running", "pending")) "等待服务器输出…" else "此操作没有正文", color = QoderColors.secondary) }
            latestSelected.target?.let { child -> item { TextButton(text = "打开子会话 ›", onClick = { onOpenChild(child) }) } }
          }
        }
      }
    }
  }
}

@Composable
private fun QoderChatTopBar(title: String, onBack: () -> Unit, subtitle: @Composable RowScope.() -> Unit, actions: @Composable RowScope.() -> Unit) {
  Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    QoderCircleButton(QoderGlyph.BACK, "返回上一级", onBack, background = Color.Transparent)
    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
      Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.title4.copy(fontWeight = FontWeight.Medium))
      Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, content = subtitle)
    }
    actions()
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
 * 目标项目默认取首页范围里的项目，「全部」时取最近活跃的项目，可在输入框下方切换。
 */
@Composable
fun DraftScreen(state: LagoonState, controller: LagoonController, onBack: () -> Unit, onModal: (Boolean) -> Unit, interactive: Boolean = true) {
  var targetPicker by remember { mutableStateOf(false) }
  var composerModal by remember { mutableStateOf(false) }
  val modal = targetPicker || composerModal
  DisposableEffect(modal) { onModal(modal); onDispose { onModal(false) } }
  Column(Modifier.fillMaxSize()) {
    PageTopBar("新会话", onBack, subtitle = {
      // The subtitle is where the session will be created; tap it to pick another project.
      Row(Modifier.clip(miuixCapsuleShape()).clickable(enabled = state.connected) { targetPicker = true }.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(MiuixIcons.Folder, null, Modifier.size(14.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.width(4.dp))
        Text(listOfNotNull(state.server?.name, state.project?.name ?: "选择项目").joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis,
          style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        DropdownChevron(14.dp, description = "选择项目")
      }
    })
    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("要做点什么？", style = MiuixTheme.textStyles.title3)
        Spacer(Modifier.height(8.dp))
        Text(state.project?.let { "会在「${it.name}」中新建，发出第一条消息后才会创建会话" } ?: "先点标题下方选择一个项目",
          style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
          textAlign = androidx.compose.ui.text.style.TextAlign.Center)
      }
    }
    ChatComposer(state, controller, interactive, { composerModal = it }, busy = state.pending("create"), actionKey = "create", ready = state.project != null,
      // The created session inherits the text as its draft until the first send succeeds; each draft page
      // gets a fresh saveable key from MainActivity, so nothing needs clearing here.
      onSend = { text, _ -> controller.startSession("", text, state.agent.takeIf { state.agentChanged }, state.model.takeIf { state.modelChanged }) })
  }
  if (targetPicker) DraftTargetSheet(state, controller) { targetPicker = false }
}

/** “服务器 · 项目” under a chat title, led by the session's live state (running spinner / waiting / failed). */
@Composable
private fun SessionSubtitle(state: LagoonState, session: Session) {
  // 子会话不在根聚合里：优先用该会话自己的实时任务，再退回根聚合 / 未读账本。
  val own = state.livePresentedTasks[session.id]
  val status = if (own != null) state.sessionStatus(session, mapOf(session.id to own)) else state.sessionStatus(session)
  val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
  when (status) {
    SessionStatus.RUNNING -> { InfiniteProgressIndicator(color = MiuixTheme.colorScheme.primary, size = 12.dp, strokeWidth = 1.5.dp, orbitingDotSize = 1.5.dp); Spacer(Modifier.width(6.dp)) }
    SessionStatus.WAITING_PERMISSION, SessionStatus.WAITING_QUESTION, SessionStatus.FAILED ->
      Text("${status.label} · ", style = MiuixTheme.textStyles.footnote1.copy(color = if (status == SessionStatus.FAILED) MiuixColorTokens.Error else MiuixColorTokens.Warning))
    SessionStatus.BACKGROUND_RUNNING ->
      Text("${status.label} · ", style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.primary))
    SessionStatus.WAITING_SERVER, SessionStatus.UNKNOWN ->
      Text("${status.label} · ", style = MiuixTheme.textStyles.footnote1.copy(color = muted))
    else -> {}
  }
  Text(listOfNotNull(state.server?.name, resolveSessionProject(session, state.projects)?.name).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis,
    style = MiuixTheme.textStyles.footnote1.copy(color = muted))
}

/** MIUIX dialog footer: two equal-width buttons, the confirming one in the primary (or error) color. */
@Composable
internal fun DialogActions(cancel: String, onCancel: () -> Unit, confirm: String, enabled: Boolean = true, danger: Boolean = false, onConfirm: () -> Unit) {
  Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    TextButton(text = cancel, onClick = onCancel, modifier = Modifier.weight(1f))
    TextButton(text = confirm, onClick = onConfirm, enabled = enabled, modifier = Modifier.weight(1f),
      colors = if (danger) ButtonDefaults.textButtonColors(textColor = MiuixColorTokens.Error) else ButtonDefaults.textButtonColorsPrimary())
  }
}

@Composable
private fun ActionError(state: LagoonState, action: String) { state.resource("action:$action").takeIf { it.state == ResourceState.ERROR }?.error?.let { Text(it, color = MiuixColorTokens.Error) } }

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

  Card(
    modifier = Modifier.fillMaxWidth(),
    insideMargin = PaddingValues(16.dp),
    colors = CardDefaults.defaultColors(
      color = if (isDangerous) MiuixColorTokens.WarningSubtle else MiuixTheme.colorScheme.surfaceContainer
    )
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      MiuixStatePill(TaskPhase.WAITING_PERMISSION, if (isDangerous) "需确认关键操作" else "等待操作授权")
      Spacer(Modifier.weight(1f))
      Text(
        request.action.ifBlank { "操作" },
        style = MiuixTheme.textStyles.footnote2.copy(
          color = MiuixTheme.colorScheme.primary,
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
        .background(MiuixTheme.colorScheme.secondaryContainer, miuixSquircleShape(8.dp))
        .padding(10.dp)
    )
    Spacer(Modifier.height(12.dp))
    if (request.always.isNotEmpty()) {
      Text(
        text = "选择“始终允许”将在服务器保存规则：" + request.always.joinToString(", "),
        style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
      )
      Spacer(Modifier.height(10.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      TextButton(text = "拒绝", onClick = { controller.replyPermission(request, "reject") }, enabled = !pending, modifier = Modifier.weight(1f))
      TextButton(text = "允许一次", onClick = { controller.replyPermission(request, "once") }, enabled = !pending, modifier = Modifier.weight(1f),
        colors = ButtonDefaults.textButtonColorsPrimary())
    }
    if (request.always.isNotEmpty()) {
      TextButton(text = "始终允许", enabled = !pending, onClick = { confirmSave = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
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

  Card(
    modifier = Modifier.fillMaxWidth(),
    insideMargin = PaddingValues(16.dp),
    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
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
          TextButton(text = "打开外部表单页面", onClick = { uriHandler.openUri(url) })
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
            if (question.multiple) {
              Checkbox(checked = option.value in answers[index], onCheckedChange = { isChecked ->
                answers = answers.toMutableList().also { list ->
                  list[index] = if (isChecked) list[index] + option.value else list[index] - option.value
                }
              })
            } else {
              RadioButton(selected = option.value in answers[index], onClick = {
                answers = answers.toMutableList().also { list ->
                  list[index] = listOf(option.value)
                }
                custom = custom.toMutableList().also { it[index] = "" }
              })
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
      TextButton(text = "取消", enabled = !pending, onClick = { if (!pending) controller.rejectQuestion(request) }, modifier = Modifier.weight(1f))
      if (currentPage > 0) {
        TextButton(text = "上一页", enabled = !pending, onClick = { page = currentPage - 1 }, modifier = Modifier.weight(1f))
      }
      if (currentPage < lastPage) {
        TextButton(text = "下一页", enabled = !pending && currentReady, onClick = { page = currentPage + 1 }, modifier = Modifier.weight(1f),
          colors = ButtonDefaults.textButtonColorsPrimary())
      } else {
        TextButton(text = if (pending) "正在提交…" else "提交回答", enabled = submitEnabled,
          onClick = {
            controller.replyQuestion(request, answers.mapIndexed { idx, list ->
              val value = custom[idx].trim()
              if (value.isBlank()) list else if (request.questions[idx].multiple) list + value else listOf(value)
            })
          },
          modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
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
  onSend: (String, () -> Unit) -> Unit = { text, clear -> controller.send(text, state.serverId, state.sessionId, clear) },
  onServerFile: (() -> Unit)? = null
) {
  var value by rememberSaveable(state.serverId, state.sessionId, stateSaver = TextFieldValue.Saver) {
    mutableStateOf(TextFieldValue(controller.draftFor(state.serverId, state.sessionId, state.draft)))
  }
  // Typing stays local (controller.typeDraft does not re-broadcast state); only an app-side set of the
  // draft – restored, returned from the queue, cleared after a send – is adopted here. The applied
  // signature is saved so a recreated composer keeps its restored text instead of the older state value.
  var applied by rememberSaveable(state.serverId, state.sessionId) { mutableStateOf(state.draft to state.draftRevision) }
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
  val canSend = ready && !sending && hasContent && (state.connected && !state.cached || queueing && value.text.isNotBlank())
  val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
  val hint = when {
    queueing -> "未连接 · 现在发送，连接恢复后会自动发出"
    !state.connected -> "离线 · 草稿保留，恢复连接后可发送"
    state.cached -> "消息未同步，刷新后可发送"
    sending -> "正在发送…"
    state.attachments.any { it.image } && state.modelCatalog.firstOrNull { it.choice.modelId == state.model?.modelId && it.choice.providerId == state.model?.providerId }?.imageInput == false -> "当前模型不支持图片，发送前请换一个模型"
    else -> null
  }
  val attach: @Composable () -> Unit = {
    Box {
      QoderCircleButton(QoderGlyph.PLUS, "添加图片或文件", { attachMenu = true }, enabled = !sending && interactive, background = Color.Transparent)
      MenuPopup(attachMenu, { attachMenu = false }, listOf(MenuSection(listOfNotNull(
        MenuAction("图片", MiuixIcons.Image) { images.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        MenuAction("手机文件", MiuixIcons.File) { documents.launch(arrayOf("*/*")) },
        onServerFile?.let { MenuAction("服务器文件", MiuixIcons.Folder, onClick = it) }
      ))), alignment = PopupPositionProvider.Align.Start)
    }
  }
  val sendOrStop: @Composable () -> Unit = {
    if (task?.phase in TaskState.RUNNING_PHASES && !hasContent) {
      QoderCircleButton(QoderGlyph.STOP, "停止", { controller.abort() }, enabled = !state.pending("abort") && interactive, background = QoderColors.codeColor)
    } else QoderCircleButton(QoderGlyph.SEND, "发送", {
      val draft = value.text
      onSend(draft) { if (value.text == draft) { controller.updateDraft(""); value = TextFieldValue("") } }
    }, enabled = canSend && interactive, background = if (canSend) MiuixTheme.colorScheme.onSurface else QoderColors.codeColor,
      foreground = if (canSend) MiuixTheme.colorScheme.surface else muted)
  }
  Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)) {
    Column(Modifier.fillMaxWidth().clip(miuixShape(if (expanded) 24.dp else 28.dp)).background(QoderColors.inputColor)
      .animateContentSize().padding(6.dp)) {
      hint?.let { Text(it, Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp), color = if (sending) muted else MiuixColorTokens.Warning, style = MiuixTheme.textStyles.footnote2) }
      if (state.attachments.isNotEmpty() || state.references.isNotEmpty()) Box(Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp)) { AttachmentTray(state, controller) }
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (!expanded) { attach(); AgentSelector(state, controller, enabled = !sending && interactive, iconOnly = true) }
        Box(Modifier.weight(1f).heightIn(min = 44.dp, max = 168.dp).padding(horizontal = if (expanded) 10.dp else 2.dp, vertical = 11.dp),
          contentAlignment = Alignment.CenterStart) {
          if (value.text.isEmpty()) Text("描述你的任务…", color = muted, style = MiuixTheme.textStyles.body1, maxLines = 1)
          BasicTextField(value, { next -> value = next; controller.typeDraft(next.text, state.serverId, state.sessionId) },
            Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface), cursorBrush = SolidColor(MiuixTheme.colorScheme.primary), maxLines = 6, readOnly = !interactive)
        }
        if (!expanded) { sendOrStop(); Spacer(Modifier.width(2.dp)) }
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
        sendOrStop()
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
  Box(Modifier.size(56.dp).clip(miuixShape(12.dp)).background(MiuixTheme.colorScheme.secondaryContainer)) {
    bitmap?.let { Image(it.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    RemoveBadge(Modifier.align(Alignment.TopEnd), onRemove)
  }
}

@Composable
private fun FileChip(name: String, detail: String, onRemove: () -> Unit) {
  Row(Modifier.height(56.dp).clip(miuixShape(12.dp)).background(MiuixTheme.colorScheme.secondaryContainer).padding(start = 12.dp, end = 4.dp),
    verticalAlignment = Alignment.CenterVertically) {
    Icon(MiuixIcons.File, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    Spacer(Modifier.width(8.dp))
    Column(Modifier.widthIn(max = 160.dp)) {
      Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium))
      Text(detail, maxLines = 1, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    }
    RemoveBadge(Modifier, onRemove)
  }
}

@Composable
private fun RemoveBadge(modifier: Modifier, onRemove: () -> Unit) {
  Box(modifier.size(40.dp).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
    Box(Modifier.size(18.dp).clip(miuixCapsuleShape()).background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
      Icon(MiuixIcons.Close, "移除", Modifier.size(12.dp), tint = MiuixTheme.colorScheme.surface)
    }
  }
}

@Composable
private fun FilesPanel(state: LagoonState, controller: LagoonController, onReference: (String) -> Unit) {
  var query by rememberSaveable { mutableStateOf("") }
  val searching = query.trim().length >= 2
  val path = state.filePath
  val viewingFile = state.fileText != null || state.fileBinary
  val crumbs = remember(path) { if (path == "." || path.isBlank()) emptyList() else HomeScope.crumbs(path) }
  val nodes = remember(state.files, state.searchResults, searching) {
    (if (searching) state.searchResults.map { FileNode(it, "file") } else state.files).distinctBy { it.path }
      .sortedWith(compareBy({ it.type != "directory" }, { it.path.lowercase() }))
  }
  Column(Modifier.fillMaxSize()) {
    TextField(query, { query = it; controller.searchFiles(it) }, label = "搜索工程文件", useLabelAsPlaceholder = true, modifier = Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
      if (viewingFile) {
        Text("← 返回列表", Modifier.clickable {
          val parent = path.substringBeforeLast('/', ".").ifBlank { "." }
          controller.listFiles(if (parent == path) "." else parent)
        }.padding(end = 8.dp), style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.primary))
      }
      Text("项目", Modifier.clickable { controller.listFiles(".") },
        style = MiuixTheme.textStyles.footnote2.copy(color = if (path == "." || path.isBlank()) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary))
      crumbs.forEachIndexed { index, (label, target) ->
        Text("/", Modifier.padding(horizontal = 4.dp), style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        Text(label, Modifier.clickable { controller.listFiles(target) },
          style = MiuixTheme.textStyles.footnote2.copy(color = if (index == crumbs.lastIndex) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary))
      }
    }
    ResourceHint(state.resource(if (searching) "search" else "files"), "此目录没有文件", retry = { if (searching) controller.searchFiles(query) else controller.listFiles() })
    if (state.fileBinary && !searching) { Text("当前无法预览此二进制文件"); TextButton(text = "引用到消息", enabled = !state.pending("send"), onClick = { onReference(state.filePath) }) }
    val preview = state.fileText?.takeUnless { searching }
    if (preview != null) {
      TextButton(text = "引用到消息", onClick = { onReference(state.filePath) })
      VirtualText(preview, Modifier.weight(1f), highlight = false)
    } else if (!state.fileBinary || searching) {
      LazyColumn { items(nodes, key = { it.path }) { node ->
        val directory = node.type == "directory"
        BasicComponent(title = node.path.substringAfterLast('/').ifBlank { node.path }, summary = if (directory) "文件夹" else node.path,
          onClick = { if (directory) controller.listFiles(node.path) else controller.readFile(node.path) })
      } }
    }
  }
}

internal fun editDraft(drafts: MutableMap<String, String>, key: String, value: String) {
  drafts[key] = value
  drafts["revision:$key"] = ((drafts["revision:$key"]?.toLongOrNull() ?: 0L) + 1).toString()
}
