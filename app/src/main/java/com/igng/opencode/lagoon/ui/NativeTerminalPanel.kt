package com.igng.opencode.lagoon.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class TerminalPanelPage { LIST, CREATE_CONFIRM, TERMINAL, TERMINATE_CONFIRM }

/** Inline pages inside ChatScreen's existing sheet: no nested sheet, WebView, or agent messages. */
@Composable
internal fun NativeTerminalPanel(state: LagoonState, controller: LagoonController) {
  val profile = state.server
  val sessionId = state.sessionId
  val directory = state.executionDirectory
  if (profile == null || sessionId == null || directory.isNullOrBlank()) {
    Text("当前会话没有可确认的终端位置。", style = MiuixTheme.textStyles.body2)
    return
  }
  // A changed connection, conversation, or directory disposes the entire scope and attachment.
  key(profile, sessionId, directory) {
    val expected = remember { TerminalTarget(profile, sessionId, directory) }
    TerminalPanelContent(state, controller, expected)
  }
}

@Composable
private fun TerminalPanelContent(state: LagoonState, controller: LagoonController, expected: TerminalTarget) {
  val scope = rememberCoroutineScope()
  var page by remember { mutableStateOf(TerminalPanelPage.LIST) }
  var terminals by remember { mutableStateOf<List<NativeTerminal>>(emptyList()) }
  var selected by remember { mutableStateOf<NativeTerminal?>(null) }
  var loaded by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var notice by remember { mutableStateOf<String?>(null) }
  val allowed = expected.matches(state) && state.canUseNativeTerminal()
  fun load() {
    if (busy || !allowed) return
    busy = true; error = null
    scope.launch {
      try { terminals = controller.terminals(expected); loaded = true }
      catch (cancelled: CancellationException) { throw cancelled }
      catch (failure: Exception) { error = failure.message ?: "读取终端列表失败" }
      finally { busy = false }
    }
  }
  fun back() {
    if (busy) return
    error = null
    page = when (page) {
      TerminalPanelPage.TERMINATE_CONFIRM -> TerminalPanelPage.TERMINAL
      else -> TerminalPanelPage.LIST
    }
  }
  LaunchedEffect(expected) { load() } // Read only. Never create or attach as a side effect.
  BackHandler(enabled = page != TerminalPanelPage.LIST) { back() }
  Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (page != TerminalPanelPage.LIST) HyperTextAction("← 返回（不终止进程）", ::back, enabled = !busy)
    Text("文本终端", style = MiuixTheme.textStyles.body1)
    Text("${expected.profile.name} · ${expected.directory}", style = MiuixTheme.textStyles.footnote2)
    if (!allowed) Text("连接或位置已变化 / 不可用，操作已禁用；不会自动切换目标。", color = HyperTheme.colors.warning, style = MiuixTheme.textStyles.footnote2)
    notice?.let { Text(it, style = MiuixTheme.textStyles.footnote2) }
    error?.let { Text(it, color = HyperTheme.colors.error, style = MiuixTheme.textStyles.footnote2) }
    when (page) {
      TerminalPanelPage.LIST -> {
        Text("这是当前位置的原生终端列表，不是此会话专属；可能包含其他客户端创建的终端。打开详情不会自动附加。", style = MiuixTheme.textStyles.footnote2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          HyperButton("刷新列表", ::load, enabled = allowed && !busy, loading = busy, variant = HyperButtonVariant.SECONDARY)
          HyperButton("新建终端…", { error = null; notice = null; page = TerminalPanelPage.CREATE_CONFIRM }, enabled = allowed && !busy)
        }
        if (busy && !loaded) Text("正在读取终端列表…", style = MiuixTheme.textStyles.footnote2)
        if (loaded && terminals.isEmpty() && error == null && !busy) Text("服务器返回的此位置终端列表为空；不会自动新建。", style = MiuixTheme.textStyles.footnote2)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          items(terminals, key = { it.id }) { terminal ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
              Text(terminal.title.ifBlank { terminal.id }, style = MiuixTheme.textStyles.body2)
              Text("${terminal.status} · ID ${terminal.id}" + (terminal.exitCode?.let { " · 退出码 $it" } ?: ""), style = MiuixTheme.textStyles.footnote2)
              Text(terminal.directory, style = MiuixTheme.textStyles.footnote2)
              HyperTextAction("查看 / 选择附加", {
                selected = terminal; error = null; notice = null; page = TerminalPanelPage.TERMINAL
              }, enabled = !busy)
            }
          }
        }
      }
      TerminalPanelPage.CREATE_CONFIRM -> {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("新建终端会启动远程 shell", style = MiuixTheme.textStyles.body1)
          Text("警告：shell 直接执行你的输入，绕过 Agent 的审批和安全确认，可能修改或删除文件、访问凭据和网络。不要粘贴不可信命令。", color = HyperTheme.colors.warning, style = MiuixTheme.textStyles.body2)
          Text("目标：${expected.profile.name}\n目录：${expected.directory}\n终端属于位置，不属于会话；离开面板仅分离连接，不会终止它。创建成功后仍需手动附加。", style = MiuixTheme.textStyles.body2)
          HyperButton("确认启动新终端", {
            if (!allowed || busy) return@HyperButton
            busy = true; error = null
            scope.launch {
              try {
                val created = controller.createTerminal(expected)
                terminals = listOf(created) + terminals.filterNot { it.id == created.id }
                selected = created; page = TerminalPanelPage.TERMINAL
                notice = "终端已创建，尚未附加；需要下方明确操作。"
              } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) {
                error = (failure.message ?: "创建失败") + "。若结果不明，先返回刷新列表核对，勿重复创建。"
              } finally { busy = false }
            }
          }, enabled = allowed && !busy, loading = busy, modifier = Modifier.fillMaxWidth())
        }
      }
      TerminalPanelPage.TERMINAL -> selected?.let { terminal ->
        TerminalDetail(state, controller, expected, terminal, Modifier.weight(1f),
          onTerminate = { error = null; page = TerminalPanelPage.TERMINATE_CONFIRM })
      }
      TerminalPanelPage.TERMINATE_CONFIRM -> selected?.let { terminal ->
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("终止并删除此终端？", style = MiuixTheme.textStyles.body1)
          Text("这不是分离连接：会终止远程进程并删除终端，其他客户端也会受影响。它可能由其他客户端创建，不能根据当前会话判断归属。", color = HyperTheme.colors.warning, style = MiuixTheme.textStyles.body2)
          Text("服务器：${expected.profile.name}\n位置：${expected.directory}\n终端：${terminal.title}\nID：${terminal.id}", style = MiuixTheme.textStyles.body2)
          HyperButton("确认终止并删除", {
            if (!allowed || busy) return@HyperButton
            busy = true; error = null
            scope.launch {
              try {
                controller.closeTerminal(expected, terminal.id)
                terminals = terminals.filterNot { it.id == terminal.id }
                selected = null; page = TerminalPanelPage.LIST; notice = "已终止并删除所选终端。"
              } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) { error = (failure.message ?: "终止失败") + "；结果不明时请刷新核对，不会自动重试。" }
              finally { busy = false }
            }
          }, enabled = allowed && !busy, loading = busy, variant = HyperButtonVariant.DESTRUCTIVE, modifier = Modifier.fillMaxWidth())
        }
      }
    }
  }
}

@Composable
private fun TerminalDetail(
  state: LagoonState, controller: LagoonController, expected: TerminalTarget, terminal: NativeTerminal,
  modifier: Modifier, onTerminate: () -> Unit
) {
  val scope = rememberCoroutineScope()
  val clipboard = LocalClipboardManager.current
  var attachment by remember(terminal.id) { mutableStateOf<TerminalAttachment?>(null) }
  var stream by remember(terminal.id) { mutableStateOf(TerminalStreamState()) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var notice by remember { mutableStateOf<String?>(null) }
  var enableInputPrompt by remember { mutableStateOf(false) }
  var inputEnabled by remember { mutableStateOf(false) }
  var deliveryUncertain by remember { mutableStateOf(false) }
  var draft by remember { mutableStateOf("") }
  var lastSubmitted by remember { mutableStateOf<String?>(null) }
  var cols by remember { mutableStateOf("80") }
  var rows by remember { mutableStateOf("24") }
  val allowed = expected.matches(state) && state.canUseNativeTerminal()
  val running = terminal.status == "running" && terminal.exitCode == null
  val channel = attachment
  // Read the current attachment at disposal, including one assigned before its first recomposition.
  DisposableEffect(Unit) { onDispose { attachment?.close() } } // Detach only, never DELETE.
  LaunchedEffect(channel) {
    if (channel != null) channel.state.collect { stream = it }
  }
  LaunchedEffect(allowed, stream.connected, stream.ended, stream.inputDeliveryUnknown) {
    if (!allowed || !stream.connected || stream.ended || stream.inputDeliveryUnknown) inputEnabled = false
    if (stream.inputDeliveryUnknown) {
      deliveryUncertain = true
      if (draft.isEmpty()) lastSubmitted?.let { draft = it }
    }
    if (!allowed) { attachment?.close(); attachment = null; enableInputPrompt = false }
  }
  val sendAllowed = allowed && inputEnabled && stream.ready && stream.connected && !stream.ended &&
    !stream.inputDeliveryUnknown && !deliveryUncertain && channel != null
  fun send() {
    if (!sendAllowed || channel == null || draft.isEmpty()) return
    val submitted = draft
    try {
      if (channel.sendText(submitted + "\r")) {
        lastSubmitted = submitted
        draft = ""
        notice = "已交给连接发送（不是远程执行确认）。"
      } else {
        deliveryUncertain = true; inputEnabled = false
        notice = "输入未确认送达，已保留草稿。请手动核对远程结果；不会自动重试。"
      }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) {
      deliveryUncertain = true; inputEnabled = false
      notice = "投递结果不明，已保留草稿。请手动核对，勿直接重复发送。"
      error = failure.message
    }
  }
  Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("${terminal.title.ifBlank { terminal.id }} · ${terminal.status}", style = MiuixTheme.textStyles.body2)
    Text("ID：${terminal.id} · PID：${terminal.pid}\n${terminal.directory}", style = MiuixTheme.textStyles.footnote2)
    if (terminal.exitCode != null) Text("退出码：${terminal.exitCode}。已结束终端不会自动重建。", style = MiuixTheme.textStyles.footnote2)
    Text("仅 ANSI 纯文本投影，不是完整 VT / 全屏终端；进度和光标布局可能不完整。输出不进入 Agent 聊天。", style = MiuixTheme.textStyles.footnote2)
    error?.let { Text(it, color = HyperTheme.colors.error, style = MiuixTheme.textStyles.footnote2) }
    notice?.let { Text(it, style = MiuixTheme.textStyles.footnote2) }
    if (attachment == null) {
      Text("附加只读取此终端；OpenCode 允许多个客户端同时附加，不提供独占控制。不存在 / 已结束时只报错，不会改附加其他终端或自动创建。", style = MiuixTheme.textStyles.footnote2)
      HyperButton("明确附加此终端", {
        if (busy || !allowed || !running) return@HyperButton
        busy = true; error = null
        scope.launch {
          var opened: TerminalAttachment? = null
          try {
            opened = controller.attachTerminal(expected, terminal.id)
            // Cancelled page changes must not leak an attachment returned at the cancellation edge.
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            stream = opened.state.value; attachment = opened; opened = null
            inputEnabled = false; enableInputPrompt = false
          } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) { error = failure.message ?: "附加失败；未自动创建或切换终端" }
          finally { opened?.close(); busy = false }
        }
      }, enabled = allowed && running && !busy, loading = busy, modifier = Modifier.fillMaxWidth())
    } else {
      Text(when {
        stream.ended -> "终端已结束；不会自动新建。"
        stream.connected && stream.ready -> if (inputEnabled) "已附加 · 已允许本机输入（非独占）" else "已附加 · 本机只读"
        stream.connected -> "连接中，等待就绪…"
        else -> "连接已断开；不会自动重连、重发或回放输入。"
      }, style = MiuixTheme.textStyles.footnote2)
      stream.error?.let { Text(it, color = HyperTheme.colors.error, style = MiuixTheme.textStyles.footnote2) }
      HyperTextAction("分离连接（保留进程）", {
        attachment?.close(); attachment = null; inputEnabled = false; enableInputPrompt = false
      }, enabled = !busy)
    }
    if (stream.truncated) Text("原始输出已截断；仅展示保留片段，行首或转义序列可能不完整。", color = HyperTheme.colors.warning, style = MiuixTheme.textStyles.footnote2)
    val output = remember(stream.output) { TerminalTextProjection.project(stream.output) }
    SelectionContainer(Modifier.height(240.dp).fillMaxWidth().background(HyperTheme.colors.container).padding(8.dp)) {
      Text(output.ifEmpty { "（尚无文本输出）" },
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
        style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace))
    }
    if (deliveryUncertain) {
      Text("上次输入是否送达不明：草稿保留。先核对输出或其他客户端，再决定是否手动重新发送。", color = HyperTheme.colors.warning, style = MiuixTheme.textStyles.footnote2)
      HyperTextAction("我已手动核对（不重发）", { deliveryUncertain = false; notice = "已记录核对；仍需重新确认启用输入。" }, enabled = !stream.inputDeliveryUnknown)
    }
    if (enableInputPrompt) {
      Text("允许输入？shell 绕过 Agent 审批；可能有其他客户端同时输入，不保证独占。发送按钮会将这一行原样加回车提交。", color = HyperTheme.colors.warning, style = MiuixTheme.textStyles.footnote2)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HyperButton("确认启用输入", { inputEnabled = true; enableInputPrompt = false }, enabled = allowed && stream.connected && stream.ready && !stream.ended && !deliveryUncertain && !stream.inputDeliveryUnknown)
        HyperTextAction("取消", { enableInputPrompt = false })
      }
    } else if (inputEnabled) HyperTextAction("关闭输入权限", { inputEnabled = false })
    else HyperTextAction("启用输入…", { enableInputPrompt = true }, enabled = allowed && attachment != null && stream.connected && stream.ready && !stream.ended && !deliveryUncertain && !stream.inputDeliveryUnknown)
    TextField(draft, { next ->
      if (next.length <= 16_384 && next.none(Char::isISOControl)) draft = next
      else notice = "仅接受单行文本，最多 16384 字符；含控制符或多行的输入未采用，请检查后手动输入。"
    }, label = "单行输入（仅按发送才提交）", singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      HyperButton("发送 + 回车", ::send, enabled = sendAllowed && draft.isNotEmpty())
      HyperTextAction("复制草稿", { clipboard.setText(AnnotatedString(draft)) }, enabled = draft.isNotEmpty())
    }
    // No automatic layout resize: OpenCode's viewport is shared with other attachments.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      TextField(cols, { cols = it.filter(Char::isDigit).take(4) }, label = "列", singleLine = true, modifier = Modifier.weight(1f))
      TextField(rows, { rows = it.filter(Char::isDigit).take(4) }, label = "行", singleLine = true, modifier = Modifier.weight(1f))
      HyperButton("调整", {
        val width = cols.toIntOrNull(); val height = rows.toIntOrNull()
        if (!allowed || busy || width == null || height == null || width !in 1..512 || height !in 1..200) return@HyperButton
        busy = true; error = null
        scope.launch {
          try { controller.resizeTerminal(expected, terminal.id, width, height); notice = "已提交共享视口尺寸。" }
          catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) { error = failure.message ?: "调整尺寸失败" }
          finally { busy = false }
        }
      }, enabled = allowed && running && !stream.ended && !busy && cols.toIntOrNull() in 1..512 && rows.toIntOrNull() in 1..200, loading = busy, variant = HyperButtonVariant.SECONDARY)
    }
    Text("手动调整共享视口（列 1–512、行 1–200）会影响其他附加客户端；不会自动调整。", style = MiuixTheme.textStyles.footnote2)
    HyperTextAction("终止并删除…", onTerminate, enabled = allowed && !busy, danger = true)
  }
}
