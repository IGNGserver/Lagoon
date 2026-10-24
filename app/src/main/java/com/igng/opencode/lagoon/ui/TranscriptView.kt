package com.igng.opencode.lagoon.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.miuixCapsuleShape

/**
 * Parsed Markdown per transcript row. Large enough for a long conversation's visible and recent rows –
 * at 32 entries, scrolling back through history re-parsed rows on the main thread as they came into view.
 */
private val markdownCache = object : LinkedHashMap<String, Pair<String, List<MarkdownBlock>>>(256, 0.75f, true) {
  override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, List<MarkdownBlock>>>?) = size > MARKDOWN_CACHE_ROWS
}
private const val MARKDOWN_CACHE_ROWS = 256
private val MARKDOWN_KINDS = setOf("text", "process-output", "reasoning-body")
private fun parsed(key: String, text: String): List<MarkdownBlock> {
  synchronized(markdownCache) { markdownCache[key]?.takeIf { it.first == text }?.let { return it.second } }
  // Parse outside the lock: a background warm-up of a long reply must not block a composition lookup.
  val blocks = MarkdownBlocks.parse(text)
  synchronized(markdownCache) { markdownCache[key] = text to blocks }
  return blocks
}

/**
 * Parses the newest Markdown rows off the main thread right after a rebuild, so composing them – a
 * streamed reply grows on every rebuild – finds the blocks ready instead of parsing in the frame.
 */
private fun warmMarkdown(rows: List<TranscriptRow>) {
  var budget = MARKDOWN_CACHE_ROWS / 2
  for (index in rows.indices.reversed()) {
    val row = rows[index]
    if (row.kind !in MARKDOWN_KINDS || row.detailOf != null) continue
    parsed(row.key, row.text)
    if (--budget == 0) break
  }
}

@Composable
internal fun Conversation(state: LagoonState, controller: LagoonController, modifier: Modifier = Modifier, onFile: (String) -> Unit, onModal: (Boolean) -> Unit,
  onOpenChild: (String) -> Unit = {}, onActivityGroup: (TranscriptRow) -> Unit = {}) {
  val list = rememberLazyListState()
  val scope = rememberCoroutineScope()
  val task = state.presentedTasks[state.sessionId]
  val working = task?.active == true
  val revert = state.session?.revertMessageId
  // Throttled, not debounced: a restart-on-change delay starved the transcript while a reply streamed.
  // The first change builds at once; later ones are conflated to the newest and built at most every
  // ROWS_THROTTLE_MS, so streaming keeps updating the list.
  val source by rememberUpdatedState(Triple(state.messages, working, revert))
  val rows by produceState(emptyList<TranscriptRow>()) {
    snapshotFlow { source }.conflate().collect { (messages, running, boundary) ->
      value = withContext(Dispatchers.Default) { TranscriptRows.build(messages, working = running, revertMessageId = boundary).also(::warmMarkdown) }
      delay(TranscriptScroll.ROWS_THROTTLE_MS)
    }
  }
  val mainRows = remember(rows) { rows.filter { it.detailOf == null } }
  val permissions = state.visiblePermissions.filter { it.sessionId == state.sessionId }
  val questions = state.questions.filter { it.sessionId == state.sessionId }

  // The list is reversed (newest item at index 0, at the bottom edge). Older pages are appended at the
  // far end, so loading history never moves what is on screen, and a growing newest item keeps its
  // bottom edge pinned. LazyColumn keeps the bottom-most visible item in place on its own; no manual
  // anchor bookkeeping, and the viewport shrinking for the keyboard keeps the newest message in view.
  val slop = with(LocalDensity.current) { 48.dp.roundToPx() }
  val atBottom by remember(slop) { derivedStateOf { TranscriptScroll.atBottom(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, slop) } }
  // A brand-new conversation opens at the newest message; only the reader moving away opts out of following.
  var follow by rememberSaveable { mutableStateOf(true) }
  var newContent by remember { mutableStateOf(false) }
  // The reader's intent is read only while the list actually moves (drag or fling); content changes never flip it.
  LaunchedEffect(list.isScrollInProgress, atBottom) { if (list.isScrollInProgress) follow = atBottom }
  LaunchedEffect(atBottom) { if (atBottom) newContent = false }
  val sending = state.pending("send")
  LaunchedEffect(sending) {
    if (sending) { follow = true; newContent = false; list.scrollToItem(0) }
  }
  // 行高会因工具状态、附件等变化：键里带上每个 part 的状态，否则“转圈→完成”不会被当作新内容。
  val contentKey = state.messages.lastOrNull()?.let { message ->
    message.id to message.parts.joinToString("|") { part -> "${part.status}:${part.text.length}:${part.output.length}:${part.attachments.size}" }
  }
  val newestKey: Any? = questions.lastOrNull()?.let { "question:${it.id}" } ?: permissions.lastOrNull()?.let { "permission:${it.id}" }
    ?: mainRows.lastOrNull()?.key
  // A row inserted below the newest one would stay out of view (LazyColumn keeps the previous bottom item
  // in place). While following, ask for index 0 before this frame is measured – the list still reports
  // (0, 0) here, the new row is not laid out yet. Never during the reader's own drag or fling, which the
  // request would cancel.
  val pinned = remember { arrayOfNulls<Any>(1) }
  SideEffect {
    val signature = newestKey to contentKey
    if (pinned[0] != signature) {
      if (follow && !list.isScrollInProgress) list.requestScrollToItem(0)
      pinned[0] = signature
    }
  }
  // Only the newest end counts as new content: an older page landing at the far end never lights the
  // button. The first observed signature is the baseline, not news.
  val seen = remember(state.sessionId) { arrayOfNulls<Any>(1) }
  LaunchedEffect(newestKey, contentKey) {
    val signature = newestKey to contentKey
    if (seen[0] != null && seen[0] != signature && !follow && mainRows.isNotEmpty()) newContent = true
    seen[0] = signature
  }

  // History prefetch: once the oldest visible item is within about a screen of the far end, request the
  // next page, whatever the follow state – a short conversation that does not fill the screen loads too.
  // Stored history pages from the device, so it also loads while offline or showing a cached copy.
  val loadingOlder = state.pending("messages-more")
  val nearOldest by remember { derivedStateOf {
    val info = list.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull()
    last != null && TranscriptScroll.nearOldest(last.index, info.visibleItemsInfo.size, info.totalItemsCount)
  } }
  var attemptedCursor by remember(state.sessionId) { mutableStateOf<String?>(null) }
  LaunchedEffect(nearOldest) { if (!nearOldest) attemptedCursor = null }
  val readable = (state.connected && !state.cached) || state.olderFromDevice
  val cursor = state.messagesCursor
  LaunchedEffect(nearOldest, readable, loadingOlder, cursor, mainRows.isNotEmpty()) {
    if (TranscriptScroll.shouldLoadOlder(nearOldest, readable, loadingOlder, cursor, attemptedCursor, mainRows.isNotEmpty())) {
      attemptedCursor = cursor
      controller.loadOlderMessages()
    }
  }
  Box(modifier) {
    // Items are emitted newest first; reverseLayout draws index 0 at the bottom.
    LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true,
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      items(questions.asReversed(), key = { "question:${it.id}" }) { MiuixQuestionCard(it, controller, state.pending("question:${it.id}")) }
      items(permissions.asReversed(), key = { "permission:${it.id}" }) { MiuixPermissionCard(it, controller, state.pending("permission:${it.id}"), onModal) }
      items(mainRows.asReversed(), key = { it.key }, contentType = { it.kind }) { row ->
        TranscriptRowView(row, onFile, onOpenChild, onActivityGroup)
      }
      if (rows.isEmpty()) item(key = "empty-hint") { ResourceHint(state.resource("messages"), "会话尚无消息", "在下方输入第一项任务。", controller::reload) }
      // A fixed height, so the spinner appearing never changes the list's extent.
      if (cursor != null) item(key = "older-loader") {
        Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
          if (loadingOlder) InfiniteProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
        }
      }
      if (state.sessionId in state.backgroundRunning) item(key = "background-running") {
        Text("任务正在后台运行中", color = MiuixTheme.colorScheme.primary, style = MiuixTheme.textStyles.footnote1)
      }
      if (state.cached) item(key = "cache-notice") { Text(if (state.cacheComplete) if (state.connected) "会话缓存 · 消息待同步" else "离线缓存 · 恢复连接后更新" else "离线缓存已截断，部分历史与长输出未保留", color = MiuixColorTokens.Warning, style = MiuixTheme.textStyles.footnote1) }
    }
    Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
      AnimatedVisibility(!atBottom && rows.isNotEmpty()) {
        Box(Modifier.size(36.dp).clip(miuixCapsuleShape()).background(MiuixTheme.colorScheme.secondaryContainer)
          .clickable { follow = true; newContent = false; scope.launch { list.scrollToItem(0) } }, contentAlignment = Alignment.Center) {
          DropdownChevron(18.dp, tint = if (newContent) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            description = if (newContent) "有新消息，回到最新" else "回到底部")
        }
      }
      if (task?.active == true) WorkingIndicator(task)
    }
  }
}

/**
 * 会话底部常驻的运行指示：只要该会话有任务在跑就一直显示，即使消息本身没有变化；已用时间每秒
 * 刷新，避免用户把静态的「用时」当成“已结束”。
 */
@Composable
private fun WorkingIndicator(task: TaskState) {
  val waiting = task.phase in TaskState.WAITING_PHASES
  var now by remember(task.sessionId, task.since) { mutableStateOf(System.currentTimeMillis()) }
  LaunchedEffect(task.sessionId, task.since) {
    while (true) { now = System.currentTimeMillis(); delay(1_000) }
  }
  val elapsed = ((now - task.since) / 1000).coerceAtLeast(0)
  Row(Modifier.clip(miuixCapsuleShape()).background(MiuixTheme.colorScheme.secondaryContainer)
    .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    if (!waiting) {
      InfiniteProgressIndicator(color = MiuixTheme.colorScheme.primary, size = 14.dp, strokeWidth = 1.5.dp)
      Spacer(Modifier.width(6.dp))
    }
    Text(if (waiting) "等待你的操作" else "运行中 · ${elapsedText(elapsed)}",
      style = MiuixTheme.textStyles.footnote2.copy(color = if (waiting) MiuixColorTokens.Warning else MiuixTheme.colorScheme.primary))
  }
}

private fun elapsedText(seconds: Long): String = when {
  seconds < 60 -> "${seconds} 秒"
  else -> "${seconds / 60} 分 ${seconds % 60} 秒"
}

@Composable
private fun TranscriptRowView(row: TranscriptRow, onFile: (String) -> Unit, onOpenChild: (String) -> Unit, onActivityGroup: (TranscriptRow) -> Unit) {
  when (row.kind) {
    "time" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
      Text(row.text, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    }
    "user" -> UserMessageRow(row, onFile)
    // One selection container per reply so a long press can select across paragraphs; the turn's final
    // answer is also copyable as a whole from the meta row below it.
    "text" -> SelectionContainer { Column { parsed(row.key, row.text).forEach { block -> MarkdownBlockView(block, selectable = false) } } }
    "meta" -> Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
      row.copyText?.let { text ->
        val clipboard = LocalClipboardManager.current
        IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }, minWidth = 32.dp, minHeight = 32.dp) {
          Icon(MiuixIcons.Copy, "复制回复", Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Spacer(Modifier.width(6.dp))
      }
      if (row.meta.isNotBlank()) Text(row.meta, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    }
    "divider" -> DividerRow(row.text)
    "thinking" -> ThinkingRow(row.text)
    "error" -> Column(Modifier.fillMaxWidth().background(MiuixColorTokens.ErrorSubtle, miuixSquircleShape(10.dp)).padding(12.dp)) {
      Text(row.text, color = MiuixColorTokens.Error, style = MiuixTheme.textStyles.body2)
    }
    "note" -> NoticeRow(row, onOpenChild)
    "tool" -> Column {
      GroupRow(row) { onActivityGroup(row) }
      row.target?.let { child -> TextButton(text = "打开子会话 ›", onClick = { onOpenChild(child) }, modifier = Modifier.padding(start = 16.dp)) }
    }
    "tool-group" -> Column {
      QoderSummaryRow(row) { onActivityGroup(row) }
      row.target?.let { child -> TextButton(text = "打开子会话 ›", onClick = { onOpenChild(child) }, modifier = Modifier.padding(start = 16.dp)) }
    }
    "process-output" -> SelectionContainer { Column(Modifier.fillMaxWidth()) { parsed(row.key, row.text).forEach { block -> MarkdownBlockView(block, selectable = false) } } }
    "reasoning-body" -> SelectionContainer { Column(Modifier.fillMaxWidth().padding(start = 24.dp)) { parsed(row.key, row.text).forEach { block -> MarkdownBlockView(block, subdued = true, selectable = false) } } }
    "diff-summary" -> GroupRow(row) { onActivityGroup(row) }
    "context-item" -> Row(Modifier.fillMaxWidth().padding(start = 24.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(row.title, style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium))
      if (row.subtitle.isNotBlank()) {
        Spacer(Modifier.width(8.dp))
        Text(row.subtitle, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      } else Spacer(Modifier.weight(1f))
      row.args.forEach { arg -> ArgChip(arg) }
    }
    "tool-body" -> Column(Modifier.fillMaxWidth().padding(start = 24.dp, top = 4.dp)) {
      if (row.title.isNotBlank()) Text(row.title, modifier = Modifier.padding(bottom = 4.dp), style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      CodePanel(row.text)
    }
    "diff-file" -> Row(Modifier.fillMaxWidth().padding(start = 24.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(row.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace))
    }
    "attachment" -> row.attachments.forEach { attachment -> AttachmentView(attachment, onFile) }
  }
}

@Composable
private fun UserMessageRow(row: TranscriptRow, onFile: (String) -> Unit) {
  val bubbleMax = LocalConfiguration.current.screenWidthDp.dp * 0.86f
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
    if (row.attachments.isNotEmpty()) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
      row.attachments.forEach { attachment -> AttachmentView(attachment, onFile) }
    }
    if (row.text.isNotBlank()) {
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.widthIn(max = bubbleMax).background(MiuixTheme.colorScheme.secondaryContainer, miuixSquircleShape(22.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
          SelectionContainer {
            BasicText(row.text, style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface, lineHeight = 24.sp))
          }
        }
      }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      Spacer(Modifier.weight(1f))
      if (row.meta.isNotBlank()) Text(row.meta, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    }
  }
}

/** 分组行：点击后打开半屏窗口查看明细（不再内联展开）。 */
@Composable
private fun GroupRow(row: TranscriptRow, onClick: () -> Unit) {
  val running = row.status in setOf("running", "pending")
  Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 2.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
    if (row.kind in setOf("tool", "tool-group")) { ToolStatusDot(row.status); Spacer(Modifier.width(10.dp)) }
    Column(Modifier.weight(1f)) {
      Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium, color = if (row.status == "error") MiuixColorTokens.Error else MiuixTheme.colorScheme.onSurface))
      if (row.subtitle.isNotBlank()) Text(row.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    }
    if (running) { InfiniteProgressIndicator(color = MiuixColorTokens.Primary, size = 15.dp, strokeWidth = 1.5.dp); Spacer(Modifier.width(8.dp)) }
    Text("›", style = MiuixTheme.textStyles.title4.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
  }
}

@Composable
private fun QoderSummaryRow(row: TranscriptRow, onClick: () -> Unit) {
  val running = row.status in setOf("running", "pending")
  val error = row.status == "error"
  Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 2.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(row.title, style = MiuixTheme.textStyles.body1.copy(color = if (error) MiuixColorTokens.Error else MiuixTheme.colorScheme.onSurfaceVariantSummary))
      if (row.subtitle.isNotBlank()) Text(row.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = MiuixTheme.textStyles.footnote1.copy(color = if (error) MiuixColorTokens.Error else MiuixTheme.colorScheme.onSurfaceVariantSummary))
    }
    if (running) { InfiniteProgressIndicator(color = QoderColors.successColor, size = 16.dp, strokeWidth = 1.5.dp); Spacer(Modifier.width(8.dp)) }
    QoderIcon(QoderGlyph.CHEVRON, Modifier.size(18.dp), MiuixTheme.colorScheme.onSurfaceVariantSummary)
  }
}

@Composable
internal fun QoderActivityItem(
  header: TranscriptRow,
  bodies: List<TranscriptRow>,
  onOpenActivity: (TranscriptRow) -> Unit,
  onFile: (String) -> Unit,
  hasNext: Boolean = true
) {
  val isProcess = header.kind == "process-output"
  val clickable = !isProcess && (header.kind in setOf("reasoning", "tool", "tool-group", "diff-file") || bodies.isNotEmpty())
  Column(Modifier.fillMaxWidth()) {
    Row(
      Modifier.fillMaxWidth()
        .height(IntrinsicSize.Min)
        .then(if (clickable) Modifier.clickable { onOpenActivity(header) } else Modifier),
      verticalAlignment = Alignment.Top
    ) {
      TimelineMarker(header.status, header.kind, hasNext)
      Spacer(Modifier.width(12.dp))
      if (isProcess) {
        SelectionContainer(Modifier.weight(1f).padding(top = 7.dp, bottom = 20.dp)) {
          Column {
            parsed(header.key, header.text).forEach { block -> MarkdownBlockView(block, selectable = false) }
          }
        }
      } else if (header.kind == "attachment") {
        Column(Modifier.weight(1f).padding(top = 7.dp, bottom = 20.dp)) {
          header.attachments.forEach { attachment -> AttachmentView(attachment, onFile) }
        }
      } else {
        Column(Modifier.weight(1f).padding(top = 7.dp, bottom = 22.dp)) {
          Text(
            header.title.ifBlank { "操作" },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium)
          )
          if (header.subtitle.isNotBlank()) Text(
            header.subtitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
          )
        }
        if (clickable) QoderIcon(QoderGlyph.CHEVRON, Modifier.padding(top = 9.dp).size(18.dp), MiuixTheme.colorScheme.onSurfaceVariantSummary)
      }
    }
  }
}

@Composable
private fun TimelineMarker(status: String, kind: String, hasNext: Boolean) {
  val railColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.42f)
  Box(Modifier.width(26.dp).fillMaxHeight()) {
    if (hasNext) Canvas(Modifier.align(Alignment.TopCenter).padding(top = 34.dp).width(2.dp).fillMaxHeight()) {
      drawLine(railColor, androidx.compose.ui.geometry.Offset(size.width / 2, 0f),
        androidx.compose.ui.geometry.Offset(size.width / 2, size.height), strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())))
    }
    Box(Modifier.padding(top = 6.dp).size(26.dp), contentAlignment = Alignment.Center) {
      when {
        status == "error" -> QoderIcon(QoderGlyph.ERROR, color = QoderColors.error)
        status in setOf("running", "pending") -> InfiniteProgressIndicator(color = QoderColors.successColor, size = 20.dp, strokeWidth = 2.dp)
        kind == "reasoning" -> QoderIcon(QoderGlyph.THINK)
        else -> QoderIcon(QoderGlyph.CHECK, color = QoderColors.successColor)
      }
    }
  }
}

@Composable
internal fun QoderSheetHandle() {
  Box(
    Modifier.fillMaxWidth().padding(top = 9.dp),
    contentAlignment = Alignment.Center
  ) {
    Box(Modifier.width(64.dp).height(4.dp).background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.45f), miuixCapsuleShape()))
  }
}

@Composable
internal fun DetailMarkdown(text: String) {
  SelectionContainer { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { parsed("detail:$text", text).forEach { block -> MarkdownBlockView(block, selectable = false) } } }
}

@Composable
internal fun DetailCode(title: String, text: String, readContent: Boolean = false, copyText: String? = null) {
  val clipboard = LocalClipboardManager.current
  Column(Modifier.fillMaxWidth()) {
    if (title.isNotBlank()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(title, Modifier.weight(1f), style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.Medium))
      if (copyText != null) IconButton(onClick = { clipboard.setText(AnnotatedString(copyText)) }, minWidth = 32.dp, minHeight = 32.dp) {
        Icon(MiuixIcons.Copy, "复制$title", Modifier.size(16.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
      }
    }
    Spacer(Modifier.height(4.dp))
    CodePanel(text, background = if (readContent) QoderColors.readContent else QoderColors.codeColor)
  }
}

/** 半屏窗口里的一行明细：代码/输出走带高亮的等宽面板，其余是文件头、上下文项或分组小标题。 */
@Composable
internal fun DetailRowView(row: TranscriptRow) {
  when (row.kind) {
    "tool-body" -> Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
      if (row.title.isNotBlank()) Text(row.title, Modifier.padding(bottom = 4.dp),
        style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      CodePanel(row.text)
    }
    "context-item" -> Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(row.title, style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium))
      if (row.subtitle.isNotBlank()) {
        Spacer(Modifier.width(8.dp))
        Text(row.subtitle, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
          style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      } else Spacer(Modifier.weight(1f))
      row.args.forEach { arg -> ArgChip(arg) }
    }
    "diff-file" -> Text(row.title, Modifier.fillMaxWidth().padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
      style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace))
    "attachment" -> row.attachments.forEach { attachment -> AttachmentView(attachment) {} }
    "reasoning" -> Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(row.title.ifBlank { "思考" }, style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium))
      if (row.subtitle.isNotBlank()) {
        Spacer(Modifier.width(8.dp))
        Text(row.subtitle, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
          style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      } else Spacer(Modifier.weight(1f))
    }
    "reasoning-body" -> SelectionContainer { Column(Modifier.fillMaxWidth().padding(start = 24.dp)) {
      parsed(row.key, row.text).forEach { block -> MarkdownBlockView(block, subdued = true, selectable = false) }
    } }
    "tool", "tool-group", "diff-summary" -> Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      ToolStatusDot(row.status)
      Spacer(Modifier.width(8.dp))
      Column(Modifier.weight(1f)) {
        Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium))
        if (row.subtitle.isNotBlank()) Text(row.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
          style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      }
    }
    else -> Unit
  }
}

/** Chronological operation header and its second-level detail bodies. */
internal data class DetailSection(val header: TranscriptRow, val bodies: List<TranscriptRow>)

/** Official notice row: a one-line label (agent change, instructions updated, subagent result…), never a reply bubble. */
@Composable
private fun NoticeRow(row: TranscriptRow, onOpenChild: (String) -> Unit) {
  val color = when (row.status) {
    "error" -> MiuixColorTokens.Error
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
  }
  val target = row.target
  Row(Modifier.fillMaxWidth().then(if (target != null) Modifier.clickable { onOpenChild(target) } else Modifier).padding(horizontal = 10.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically) {
    Text(row.title, style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium, color = color))
    if (row.text.isNotBlank()) {
      Spacer(Modifier.width(8.dp))
      Text(row.text, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    } else Spacer(Modifier.weight(1f))
    if (target != null) Text("›", style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
  }
}

@Composable
private fun ToolStatusDot(status: String) {
  val color = when (status) {
    "running", "pending" -> MiuixColorTokens.Primary
    "completed" -> MiuixColorTokens.Success
    "error" -> MiuixColorTokens.Error
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
  }
  Box(Modifier.size(7.dp).background(color, androidx.compose.foundation.shape.CircleShape))
}

@Composable
private fun ArgChip(text: String) {
  Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis,
    style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
    modifier = Modifier.background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f), miuixSquircleShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
}

private data class CodeColors(val plain: Color, val header: Color, val hunk: Color, val add: Color, val remove: Color, val command: Color)

@Composable
private fun rememberCodeColors(): CodeColors = CodeColors(
  plain = MiuixTheme.colorScheme.onSurface,
  header = MiuixTheme.colorScheme.onSurfaceVariantSummary,
  hunk = MiuixTheme.colorScheme.primary,
  add = MiuixColorTokens.Success,
  remove = MiuixColorTokens.Error,
  command = MiuixTheme.colorScheme.primary
)

@Composable
private fun CodePanel(text: String, highlight: Boolean = true, background: Color = MiuixTheme.colorScheme.secondaryContainer) {
  val colors = rememberCodeColors()
  val annotated = remember(text, colors, highlight) {
    buildAnnotatedString {
      if (!highlight) { append(text); return@buildAnnotatedString }
      CodeHighlight.split(text).forEach { fragment ->
        val color = when (fragment.token) {
          CodeToken.PLAIN -> colors.plain
          CodeToken.HEADER -> colors.header
          CodeToken.HUNK -> colors.hunk
          CodeToken.ADD -> colors.add
          CodeToken.REMOVE -> colors.remove
          CodeToken.COMMAND -> colors.command
        }
        withStyle(SpanStyle(color = color)) { append(fragment.text) }
      }
    }
  }
  Column(Modifier.fillMaxWidth().background(background, miuixSquircleShape(12.dp)).padding(12.dp)) {
    SelectionContainer {
      BasicText(annotated, Modifier.horizontalScroll(rememberScrollState()),
        style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp))
    }
  }
}

@Composable
private fun DividerRow(label: String) {
  Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    HorizontalDivider(Modifier.weight(1f))
    Text(label, modifier = Modifier.padding(horizontal = 10.dp), style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    HorizontalDivider(Modifier.weight(1f))
  }
}

@Composable
private fun ThinkingRow(text: String) {
  val transition = rememberInfiniteTransition(label = "thinking")
  val alpha = transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
  // The pulse is read in the draw phase: reading it during composition recomposed the row every frame.
  Text(text, style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary), modifier = Modifier.graphicsLayer { this.alpha = alpha.value }.padding(top = 4.dp))
}

@Composable
internal fun ResourceHint(status: ResourceStatus, empty: String, explanation: String = "", retry: () -> Unit) {
  when (status.state) {
    ResourceState.LOADING, ResourceState.NOT_LOADED -> LoadingSkeleton()
    ResourceState.ERROR -> Column { Text(status.error ?: "加载失败", color = MiuixColorTokens.Error); TextButton(text = "重试", onClick = retry) }
    ResourceState.UNSUPPORTED -> Text("此服务器不提供该功能", style = MiuixTheme.textStyles.footnote1)
    ResourceState.STALE -> Column { Text("保留上次数据 · ${status.error.orEmpty()}", color = MiuixColorTokens.Warning); TextButton(text = "重新同步", onClick = retry) }
    ResourceState.EMPTY -> Column { Text(empty, style = MiuixTheme.textStyles.headline2); if (explanation.isNotBlank()) Text(explanation, style = MiuixTheme.textStyles.footnote1) }
    else -> Unit
  }
}

@Composable
private fun annotated(spans: List<MarkdownSpan>): AnnotatedString {
  val primary = MiuixTheme.colorScheme.primary
  val chip = MiuixTheme.colorScheme.secondaryContainer
  // Remembered per span list: rebuilt on every recomposition of every visible paragraph before.
  return remember(spans, primary, chip) { buildAnnotatedString {
    spans.forEach { span ->
      val style = SpanStyle(
        fontWeight = if (span.bold) FontWeight.Bold else null,
        fontStyle = if (span.italic) FontStyle.Italic else null,
        fontFamily = if (span.code) FontFamily.Monospace else null,
        background = if (span.code) chip else Color.Transparent,
        textDecoration = if (span.strike) TextDecoration.LineThrough else null
      )
      if (span.href != null) withLink(LinkAnnotation.Url(span.href, TextLinkStyles(SpanStyle(color = primary, textDecoration = TextDecoration.Underline)))) { withStyle(style) { append(span.text) } }
      else withStyle(style) { append(span.text) }
    }
  } }
}

@Composable
internal fun MarkdownBlockView(block: MarkdownBlock, subdued: Boolean = false, selectable: Boolean = true) {
  // Nested SelectionContainers would split selection per paragraph; callers wrapping a whole reply pass false.
  val select: @Composable (@Composable () -> Unit) -> Unit = { inner -> if (selectable) SelectionContainer { inner() } else inner() }
  val base = MiuixTheme.textStyles.body1.copy(
    color = if (subdued) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.onSurface,
    lineHeight = 26.sp, fontSize = if (subdued) 14.sp else 16.sp
  )
  val quote = block.prefix.trimStart().startsWith(">")
  val displayPrefix = if (quote) block.prefix.trimStart().trimStart('>').trimStart() else block.prefix
  val content: @Composable () -> Unit = {
    when (block.kind) {
      "rule" -> HorizontalDivider()
      "code" -> Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.secondaryContainer, miuixSquircleShape(10.dp)).padding(10.dp)) {
        if (block.prefix.isNotBlank()) Text(block.prefix, modifier = Modifier.padding(bottom = 4.dp), style = MiuixTheme.textStyles.footnote2)
        select { BasicText(block.text, Modifier.horizontalScroll(rememberScrollState()), style = base.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp)) }
      }
      "table-head", "table-row" -> Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(MiuixTheme.colorScheme.secondaryContainer).padding(8.dp)) {
        block.cells.forEach { cell -> select { BasicText(annotated(cell), Modifier.width(180.dp).padding(end = 12.dp), style = base.copy(fontWeight = if (block.kind == "table-head") FontWeight.SemiBold else FontWeight.Normal)) } }
      }
      else -> {
        val task = taskMarker(block)
        select { BasicText(buildAnnotatedString { append(displayPrefix); append(task.first); append(annotated(task.second)) },
          style = if (block.kind == "heading") base.copy(fontSize = when (block.level) { 1 -> 25.sp; 2 -> 21.sp; else -> 18.sp }, fontWeight = FontWeight.SemiBold) else base) }
      }
    }
  }
  if (quote) Column(Modifier.fillMaxWidth().padding(vertical = 2.dp).background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.05f), miuixSquircleShape(8.dp)).padding(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)) {
    content()
  } else content()
}

/** Renders `- [ ]` / `- [x]` task list markers as checkboxes. */
private fun taskMarker(block: MarkdownBlock): Pair<AnnotatedString, List<MarkdownSpan>> {
  val first = block.spans.firstOrNull()?.text ?: return AnnotatedString("") to block.spans
  val marker = when {
    first.startsWith("[ ] ") -> "☐ "
    first.startsWith("[x] ") || first.startsWith("[X] ") -> "☑ "
    else -> return AnnotatedString("") to block.spans
  }
  val head = block.spans.first()
  val rest = if (marker == "☑ ") listOf(head.copy(text = head.text.substring(4), strike = true)) + block.spans.drop(1) else listOf(head.copy(text = head.text.substring(4))) + block.spans.drop(1)
  return AnnotatedString(marker) to rest
}

@Composable
internal fun VirtualText(text: String, modifier: Modifier = Modifier, highlight: Boolean = true) {
  val chunks by produceState(emptyList<String>(), text) { value = withContext(Dispatchers.Default) { MarkdownBlocks.chunks(text) } }
  LazyColumn(modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    items(chunks.size) { i -> CodePanel(chunks[i], highlight) }
  }
}

/**
 * Inline (`data:image/…`) attachments, decoded off the main thread. The image's aspect ratio is read
 * from a short prefix of the payload (the header), synchronously, so the row reserves its final height
 * on first layout instead of growing when the decode lands – a row growing above the viewport shifted
 * everything below it. Decoded bitmaps live in a small LRU keyed by a digest of the url, so scrolling
 * back to an image does not decode it again.
 */
private object InlineImages {
  /** Base64 characters read for the header: PNG/GIF/WebP sizes sit in the first bytes, JPEG's after any EXIF block. */
  private const val HEADER_CHARS = 64 * 1024
  private val bitmaps = object : android.util.LruCache<String, android.graphics.Bitmap>(
    (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(32L shl 20).toInt()) {
    override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
  }
  private val aspects = object : LinkedHashMap<String, Float>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?) = size > 256
  }

  /**
   * A digest of [url] that does not walk a multi-megabyte payload on the main thread: its length, a
   * strided sample of its characters and its tail.
   */
  fun key(url: String): String {
    var hash = url.length.toLong()
    val step = (url.length / 256).coerceAtLeast(1)
    var index = 0
    while (index < url.length) { hash = hash * 31 + url[index].code; index += step }
    return "${url.length}:${java.lang.Long.toHexString(hash)}:${url.takeLast(32)}"
  }

  fun cached(key: String): android.graphics.Bitmap? = bitmaps.get(key)

  /** Width / height, from the cache or the payload's header; null when the header cannot be read. */
  fun aspect(key: String, url: String): Float? {
    synchronized(aspects) { aspects[key] }?.let { return it }
    val start = url.indexOf(',') + 1
    if (start <= 0) return null
    val length = (minOf(url.length - start, HEADER_CHARS) / 4) * 4
    val ratio = runCatching {
      val bytes = android.util.Base64.decode(url.substring(start, start + length), android.util.Base64.DEFAULT)
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
      if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth.toFloat() / bounds.outHeight else null
    }.getOrNull() ?: return null
    synchronized(aspects) { aspects[key] = ratio }
    return ratio
  }

  /** Decodes [url] (blocking; call off the main thread), downsampled to about 1024 px, and caches it. */
  fun decode(key: String, url: String): android.graphics.Bitmap? = runCatching {
    val bytes = android.util.Base64.decode(url.substringAfter(','), android.util.Base64.DEFAULT)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    synchronized(aspects) { aspects[key] = bounds.outWidth.toFloat() / bounds.outHeight }
    val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 1024).coerceAtLeast(1) }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
  }.getOrNull()?.also { bitmaps.put(key, it) }
}

@Composable
internal fun AttachmentView(attachment: Attachment, onFile: (String) -> Unit) {
  if (attachment.url == OMITTED_INLINE_DATA) {
    // The device cache never keeps inline payloads; say so instead of rendering a broken attachment.
    Text("${attachment.name.ifBlank { "附件" }} · 未缓存，联网刷新后显示",
      style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    return
  }
  val uriHandler = LocalUriHandler.current
  val dataImage = attachment.url.startsWith("data:image/") && attachment.url.length < 8_000_000
  val key = remember(attachment.url) { if (dataImage) InlineImages.key(attachment.url) else null }
  val aspect = remember(key) { key?.let { InlineImages.aspect(it, attachment.url) } }
  val bitmap by produceState(key?.let(InlineImages::cached), key) {
    if (key != null && value == null) value = withContext(Dispatchers.IO) { InlineImages.decode(key, attachment.url) }
  }
  val image = remember(bitmap) { bitmap?.asImageBitmap() }
  val description = attachment.name.ifBlank { "图片附件" }
  Column(Modifier.fillMaxWidth()) {
    if (aspect != null) BoxWithConstraints(Modifier.fillMaxWidth()) {
      // The height the decoded image will take (full width, capped at 280dp), reserved up front.
      val height = minOf(maxWidth / aspect, 280.dp)
      if (image != null) Image(image, description, Modifier.fillMaxWidth().height(height))
      else Box(Modifier.fillMaxWidth().height(height).background(MiuixTheme.colorScheme.secondaryContainer, miuixSquircleShape(10.dp)))
    } else image?.let { Image(it, description, Modifier.fillMaxWidth().heightIn(max = 280.dp)) }
    TextButton(text = attachment.name.ifBlank { if (dataImage) "图片附件" else attachment.url.substringAfterLast('/').take(100).ifBlank { "附件" } }, onClick = {
      when { attachment.url.startsWith("http://") || attachment.url.startsWith("https://") -> runCatching { uriHandler.openUri(attachment.url) }
        !attachment.url.startsWith("data:") -> onFile(runCatching { java.net.URI(attachment.url).path }.getOrNull() ?: attachment.url) }
    })
  }
}
