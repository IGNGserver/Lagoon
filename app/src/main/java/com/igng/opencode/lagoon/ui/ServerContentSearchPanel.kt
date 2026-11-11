package com.igng.opencode.lagoon.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

/** Read-only search stays bound to the Host captured by the explicit menu action. */
@Composable
internal fun ServerContentSearchPanel(
  state: LagoonState,
  controller: LagoonController,
  target: SessionContentSearchTarget,
  onDismiss: () -> Unit,
  onOpen: (SessionTarget) -> Unit
) {
  val scope = rememberCoroutineScope()
  val currentState by rememberUpdatedState(state)
  val currentDismiss by rememberUpdatedState(onDismiss)
  val currentOpen by rememberUpdatedState(onOpen)
  var draft by remember(target) { mutableStateOf("") }
  var page by remember(target) { mutableStateOf<SessionContentSearchPage?>(null) }
  var error by remember(target) { mutableStateOf<String?>(null) }
  var pending by remember(target) { mutableStateOf(false) }
  var opening by remember(target) { mutableStateOf(false) }
  var closing by remember(target) { mutableStateOf(false) }
  var sequence by remember(target) { mutableStateOf(0L) }
  var readJob by remember(target) { mutableStateOf<Job?>(null) }
  var openJob by remember(target) { mutableStateOf<Job?>(null) }
  val normalized = draft.trim()
  val queryValid = normalized.isNotEmpty() && normalized.length <= 500 && '\u0000' !in draft
  val routeValid = target.matches(state) && state.canSearchSessionContent()
  fun stillValid() = !closing && target.matches(currentState) && currentState.canSearchSessionContent()
  fun close() {
    if (closing) return
    closing = true
    sequence++
    readJob?.cancel()
    openJob?.cancel()
    currentDismiss()
  }
  fun search() {
    // Read live state here as rapid taps can precede recomposition of button enabled state.
    if (pending || !stillValid()) return
    val submittedQuery = draft.trim()
    if (submittedQuery.isEmpty() || submittedQuery.length > 500 || '\u0000' in draft) return
    readJob?.cancel()
    val request = ++sequence
    pending = true
    opening = false
    page = null
    error = null
    readJob = scope.launch {
      try {
        val fresh = controller.searchSessionContent(target, submittedQuery)
        if (request == sequence && stillValid() && draft.trim() == submittedQuery && fresh.target == target &&
          fresh.result.query == submittedQuery) page = fresh
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (request == sequence && stillValid()) error = "服务器搜索未完成，请检查连接后重试。"
      } finally {
        if (request == sequence && !closing) pending = false
      }
    }
  }
  fun open(resultPage: SessionContentSearchPage, sessionId: String) {
    if (pending || !stillValid() || page !== resultPage || resultPage.result.query != draft.trim()) return
    val request = ++sequence
    pending = true
    opening = true
    error = null
    openJob = scope.launch {
      try {
        controller.openSearchHit(resultPage, sessionId) { destination ->
          if (request == sequence && stillValid() && page === resultPage && resultPage.result.query == draft.trim()) {
            close()
            currentOpen(destination)
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (request == sequence && stillValid()) error = "无法打开此命中：会话可能已不可见、不存在，或结果已过期。请重新搜索。"
      } finally {
        if (request == sequence && !closing) { pending = false; opening = false }
      }
    }
  }
  DisposableEffect(target) {
    onDispose { closing = true; sequence++; readJob?.cancel(); openJob?.cancel() }
  }
  LaunchedEffect(routeValid) {
    if (!routeValid) {
      sequence++
      readJob?.cancel()
      openJob?.cancel()
      pending = false
      opening = false
    }
  }
  BackHandler { close() }
  HyperBottomSheet(title = "服务器内容搜索", show = true, onDismissRequest = ::close) {
    Column(
      Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Text("Host：${target.profile.name}")
      Text("搜索此Host可见消息正文，最多20个会话；不受本机项目筛选约束，不启动Agent。", color = HyperTheme.colors.textSecondary)
      if (!routeValid) Text("连接、路由或能力已变化，已停用所有请求。请关闭后从原 Host 重新打开。", color = HyperTheme.colors.error)
      TextField(
        draft, { if (!pending && !closing && routeValid) { draft = it; error = null } },
        label = "正文中的原文（按字面搜索）", useLabelAsPlaceholder = true,
        modifier = Modifier.fillMaxWidth()
      )
      Text("去除首尾空白后 ${normalized.length}/500 个 UTF-16 单元；不允许 NUL 字符。仅点按搜索时发送。", color = HyperTheme.colors.textSecondary)
      if (normalized.length > 500 || '\u0000' in draft) Text("查询需不超过 500 个 UTF-16 单元，且不能含 NUL 字符。", color = HyperTheme.colors.error)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HyperButton("搜索", enabled = routeValid && !closing && !pending && queryValid, onClick = ::search)
        HyperTextAction("关闭", onClick = ::close)
      }
      if (pending) Text(if (opening) "正在核对会话权限与所属 Host…" else "正在搜索服务器正文…")
      error?.let { Text(it, color = HyperTheme.colors.error) }
      page?.let { resultPage ->
        val result = resultPage.result
        val queryCurrent = result.query == normalized && queryValid
        Text("本次已执行查询：${result.query}", maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (!queryCurrent) Text("查询已变更，请重新搜索", color = HyperTheme.colors.error)
        if (result.hasMore) Text("还有未展示结果，尝试更具体的查询；接口无下一页游标，权限过滤可能使当前列表为空", color = HyperTheme.colors.textSecondary)
        else Text("服务器未报告更多结果。", color = HyperTheme.colors.textSecondary)
        if (result.items.isEmpty() && !result.hasMore) Text("此次服务器搜索没有返回可见命中")
        if (queryCurrent) result.items.forEach { hit ->
          HyperCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Text("会话 ID：${hit.sessionId}", maxLines = 2, overflow = TextOverflow.Ellipsis)
              Text(hit.snippet, maxLines = 4, overflow = TextOverflow.Ellipsis, color = HyperTheme.colors.textSecondary)
              HyperTextAction("打开会话", enabled = routeValid && !closing && !pending && queryCurrent,
                onClick = { open(resultPage, hit.sessionId) })
            }
          }
        }
      }
      Spacer(Modifier.height(12.dp))
    }
  }
}
