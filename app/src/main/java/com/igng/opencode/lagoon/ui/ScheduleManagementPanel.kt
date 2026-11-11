package com.igng.opencode.lagoon.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class SchedulePanelPage { LIST, DETAILS, EDIT, DELETE, HISTORY }

/** Lives inside ChatScreen's sheet. Pages replace one another; no nested sheets or dialogs. */
@Composable
internal fun ScheduleManagementPanel(state: LagoonState, controller: LagoonController) {
  key(state.server, state.sessionId) { SchedulePanelContent(state, controller) }
}

@Composable
private fun SchedulePanelContent(state: LagoonState, controller: LagoonController) {
  var page by remember { mutableStateOf(SchedulePanelPage.LIST) }
  var selected by remember { mutableStateOf<RemoteSchedule?>(null) }
  var target by remember { mutableStateOf<ScheduleMutationTarget?>(null) }
  val pending = state.pending("schedule-mutation")
  fun back() { page = if (page == SchedulePanelPage.DETAILS) SchedulePanelPage.LIST else SchedulePanelPage.DETAILS }
  BackHandler(enabled = page != SchedulePanelPage.LIST) { if (!pending) back() }
  Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (page != SchedulePanelPage.LIST) HyperTextAction("← 返回", { if (!pending) back() }, enabled = !pending)
    when (page) {
      SchedulePanelPage.LIST -> {
        Text("服务器负责定时与状态。当前远程协议仅支持读取、历史、修改和删除，不提供创建、立即运行或启用 / 停用。",
          style = MiuixTheme.textStyles.footnote2)
        HyperButton("刷新", { controller.loadSchedules() }, enabled = state.canManageSchedules() && !state.schedulesLoading && !pending,
          loading = state.schedulesLoading, variant = HyperButtonVariant.TERTIARY)
        ActionError(state, "schedules")
        ActionError(state, "schedule-mutation")
        if (state.schedulesLoading) Text("正在读取定时任务…", style = MiuixTheme.textStyles.footnote2)
        if (state.schedules.isEmpty() && !state.schedulesLoading) Text("此会话没有定时任务。", style = MiuixTheme.textStyles.footnote2)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          itemsIndexed(state.schedules, key = { index, item -> "$index:${item.id}" }) { _, schedule ->
            Column(Modifier.fillMaxWidth().clickable {
              selected = schedule
              target = schedule.record?.let { record -> state.server?.let { profile ->
                schedule.sessionId?.let { owner -> ScheduleMutationTarget(profile, owner, schedule.id, record) }
              } }
              page = SchedulePanelPage.DETAILS
            }.padding(vertical = 12.dp, horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text(schedule.title.ifBlank { schedule.id }, style = MiuixTheme.textStyles.body1)
              Text("${scheduleStatus(schedule.status)} · ${schedule.detail}", style = MiuixTheme.textStyles.footnote2)
              Text(schedule.scheduledAt, style = MiuixTheme.textStyles.footnote2)
              Text(schedule.prompt, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.footnote2)
            }
          }
        }
      }
      SchedulePanelPage.DETAILS -> selected?.let { schedule ->
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(schedule.title, style = MiuixTheme.textStyles.body1)
          Text("${scheduleStatus(schedule.status)}（服务器状态）", style = MiuixTheme.textStyles.footnote2)
          Text("ID：${schedule.id}\n所属会话：${schedule.sessionId ?: "未知"}\n类型：${schedule.kind}\n计划时间：${schedule.scheduledAt}", style = MiuixTheme.textStyles.footnote2)
          Text(schedule.detail, style = MiuixTheme.textStyles.footnote2)
          schedule.record?.let { record ->
            if (record.kind in setOf("daily", "weekly", "cron")) Text(
              "时间：${record.time.orEmpty()}\nIANA 时区：${record.timeZone.orEmpty()}" +
                (if (record.kind == "weekly") "\n星期：${record.weekdays.joinToString(",")}" else "") +
                (if (record.kind == "cron") "\n表达式：${record.expression.orEmpty()}" else ""), style = MiuixTheme.textStyles.footnote2)
          }
          Text(schedule.prompt, style = MiuixTheme.textStyles.body2)
          schedule.lastDelivery?.let { Text("最近投递（非运行完成）", style = MiuixTheme.textStyles.footnote2); ScheduleDeliveryRow(it) }
          val captured = target
          val ownerReady = captured != null && scheduleOwnerRouteMatches(state, captured) && state.canManageSchedules()
          if (captured == null) Text("服务器未提供完整记录或所属会话，无法安全修改、删除或读取历史。", style = MiuixTheme.textStyles.footnote2)
          if (schedule.status != "active") Text("非活跃定时任务不能编辑；仍可查看投递历史或删除。", style = MiuixTheme.textStyles.footnote2)
          if (captured != null && !captured.matches(state)) Text("记录已变化，请返回列表刷新后重新选择；不会自动切换目标。", color = HyperTheme.colors.warning, style = MiuixTheme.textStyles.footnote2)
          HyperButton("投递历史", { page = SchedulePanelPage.HISTORY }, enabled = ownerReady && !pending, modifier = Modifier.fillMaxWidth())
          if (schedule.status == "active") HyperButton("编辑", { page = SchedulePanelPage.EDIT },
            enabled = ownerReady && captured?.matches(state) == true && !pending && !state.schedulesLoading,
            modifier = Modifier.fillMaxWidth(), variant = HyperButtonVariant.SECONDARY)
          HyperButton("删除", { page = SchedulePanelPage.DELETE }, enabled = ownerReady && captured?.matches(state) == true && !pending,
            modifier = Modifier.fillMaxWidth(), variant = HyperButtonVariant.DESTRUCTIVE)
          ActionError(state, "schedule-mutation")
        }
      }
      SchedulePanelPage.EDIT -> target?.let { captured ->
        ScheduleEditPage(state, controller, captured) { page = SchedulePanelPage.LIST; selected = null; target = null }
      }
      SchedulePanelPage.DELETE -> target?.let { captured ->
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("删除「${captured.record.title}」？", style = MiuixTheme.textStyles.body1)
          Text("此定时任务及其投递历史都会被删除。已经排队或已经投递的消息不会撤回。", style = MiuixTheme.textStyles.body2)
          Text("所属会话：${captured.sessionId}\n定时任务：${captured.scheduleId}", style = MiuixTheme.textStyles.footnote2)
          if (!captured.matches(state)) Text("记录或连接已变化，删除已禁用；请返回列表刷新并重新确认。", color = HyperTheme.colors.warning, style = MiuixTheme.textStyles.footnote2)
          ActionError(state, "schedule-mutation")
          HyperButton("确认删除定时任务与历史", {
            controller.deleteSchedule(captured) { page = SchedulePanelPage.LIST; selected = null; target = null }
          }, enabled = state.canManageSchedules() && captured.matches(state) && !pending && !state.schedulesLoading,
            loading = pending, variant = HyperButtonVariant.DESTRUCTIVE, modifier = Modifier.fillMaxWidth())
        }
      }
      SchedulePanelPage.HISTORY -> target?.let { captured -> ScheduleHistoryPanel(state, controller, captured) }
    }
  }
}

private fun scheduleStatus(status: String?): String = when (status) {
  "active" -> "活跃"
  "inactive" -> "非活跃"
  null -> "状态未知"
  else -> status
}

/** History may survive a changed record, but never a changed route or owner. */
private fun scheduleOwnerRouteMatches(state: LagoonState, target: ScheduleMutationTarget): Boolean =
  state.server == target.profile && state.sessionId == target.sessionId

@Composable
private fun ScheduleEditPage(state: LagoonState, controller: LagoonController, captured: ScheduleMutationTarget, onSaved: () -> Unit) {
  var expected by remember(captured) { mutableStateOf(captured) }
  var title by remember(captured) { mutableStateOf(captured.record.title) }
  var prompt by remember(captured) { mutableStateOf(captured.record.prompt) }
  var changeTiming by remember(captured) { mutableStateOf(false) }
  var draft by remember(captured) { mutableStateOf(ScheduleTimingDraft.from(captured.record)) }
  var notice by remember(captured) { mutableStateOf<String?>(null) }
  var refreshing by remember(captured) { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val busy = state.pending("schedule-mutation") || refreshing
  val parsed = parseScheduleTiming(changeTiming, draft)
  Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text("编辑定时任务", style = MiuixTheme.textStyles.body1)
    Text("只修改内容时，原定时保持不变。草稿绑定原连接、所属会话与完整记录；失败时保留输入。", style = MiuixTheme.textStyles.footnote2)
    TextField(title, { if (!busy) title = it }, label = "标题", singleLine = false, modifier = Modifier.fillMaxWidth())
    TextField(prompt, { if (!busy) prompt = it }, label = "完整提示词", singleLine = false,
      modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 220.dp))
    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { changeTiming = !changeTiming }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      Checkbox(checked = changeTiming, onCheckedChange = null, enabled = !busy)
      Spacer(Modifier.width(8.dp)); Text("明确修改定时", style = MiuixTheme.textStyles.body2)
    }
    if (changeTiming) {
      Text("保持原定时类型；after 仅能明确改为绝对时间 at。所有时间原样提交，不转换为手机时区。", style = MiuixTheme.textStyles.footnote2)
      when (draft.kind) {
        "at", "after" -> TextField(draft.at, { if (!busy) draft = draft.copy(at = it) }, label = "RFC 3339 时间（含 Z 或偏移）", modifier = Modifier.fillMaxWidth())
        "every" -> TextField(draft.seconds, { if (!busy) draft = draft.copy(seconds = it) }, label = "间隔秒数（正整数）", modifier = Modifier.fillMaxWidth())
        "daily", "weekly", "cron" -> {
          if (draft.kind != "cron") TextField(draft.time, { if (!busy) draft = draft.copy(time = it) }, label = "时间 HH:mm[:ss]", modifier = Modifier.fillMaxWidth())
          TextField(draft.timeZone, { if (!busy) draft = draft.copy(timeZone = it) }, label = "明确的 IANA 时区", modifier = Modifier.fillMaxWidth())
          if (draft.kind == "weekly") TextField(draft.weekdays, { if (!busy) draft = draft.copy(weekdays = it) }, label = "星期 1–7，逗号分隔（周一为 1）", modifier = Modifier.fillMaxWidth())
          if (draft.kind == "cron") TextField(draft.expression, { if (!busy) draft = draft.copy(expression = it) }, label = "五字段 Cron 表达式", modifier = Modifier.fillMaxWidth())
        }
      }
      parsed.exceptionOrNull()?.message?.let { Text(it, color = HyperTheme.colors.error, style = MiuixTheme.textStyles.footnote2) }
    }
    if (!expected.matches(state)) Text("服务器记录或路由已变化，不能保存旧快照。显式重新读取元数据后再确认；不会覆盖草稿。", color = HyperTheme.colors.warning, style = MiuixTheme.textStyles.footnote2)
    notice?.let { Text(it, style = MiuixTheme.textStyles.footnote2) }
    HyperButton("重新读取元数据（保留草稿）", {
      refreshing = true
      scope.launch {
        try {
          controller.loadSchedules().join()
          val refreshed = controller.state.value
          val record = refreshed.schedules.firstOrNull { it.id == captured.scheduleId && it.sessionId == captured.sessionId }?.record
          val rebased = record?.let { captured.copy(record = it) }
          if (scheduleOwnerRouteMatches(refreshed, captured) && refreshed.resource("action:schedules").state == ResourceState.READY && rebased?.matches(refreshed) == true) {
            val merged = rebaseScheduleDraft(expected.record, rebased.record, title, prompt, draft)
            title = merged.title; prompt = merged.prompt; draft = merged.timing
            expected = rebased
            notice = "已读取最新记录；保留你修改过的字段，未修改字段跟随服务器更新。请核对后保存。"
          } else notice = "未能确认同一连接上的同一定时任务；保留草稿，不自动切换。"
        } finally { refreshing = false }
      }
    }, enabled = !busy && !state.schedulesLoading && state.canManageSchedules() && scheduleOwnerRouteMatches(state, captured),
      loading = refreshing, variant = HyperButtonVariant.TERTIARY, modifier = Modifier.fillMaxWidth())
    ActionError(state, "schedules")
    ActionError(state, "schedule-mutation")
    HyperButton("保存修改", {
      controller.updateSchedule(expected, title = title.takeIf { it != expected.record.title }, prompt = prompt.takeIf { it != expected.record.prompt },
        change = parsed.getOrNull(), onUpdated = onSaved)
    }, enabled = !busy && scheduleEditorCanSave(state, expected, title, prompt, changeTiming, draft) &&
      (title != expected.record.title || prompt != expected.record.prompt || changeTiming),
      loading = state.pending("schedule-mutation"), modifier = Modifier.fillMaxWidth())
  }
}

@Composable
private fun ScheduleHistoryPanel(state: LagoonState, controller: LagoonController, expected: ScheduleMutationTarget) {
  var records by remember(expected) { mutableStateOf<List<ScheduleDelivery>>(emptyList()) }
  var metadata by remember(expected) { mutableStateOf<ScheduleHistoryPage?>(null) }
  var nextBefore by remember(expected) { mutableStateOf<String?>(null) }
  var consumed by remember(expected) { mutableStateOf<Set<String>>(emptySet()) }
  var pending by remember(expected) { mutableStateOf(false) }
  var loaded by remember(expected) { mutableStateOf(false) }
  var error by remember(expected) { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()
  fun load(before: String? = null) {
    if (pending || !state.canManageSchedules() || !scheduleOwnerRouteMatches(state, expected)) return
    if (before != null && (before != nextBefore || before in consumed)) return
    pending = true; error = null
    scope.launch {
      try {
        val page = controller.scheduleHistory(expected, before)
        if (!scheduleOwnerRouteMatches(controller.state.value, expected)) return@launch
        check(page.id == expected.scheduleId) { "服务器返回了不同定时任务的历史" }
        // Append only the exact requested cursor. Initial reads replace, never append.
        if (before != null && before != nextBefore) return@launch
        records = if (before == null) page.records else records + page.records
        if (before != null) consumed = consumed + before
        metadata = page; loaded = true
        nextBefore = page.nextBefore?.takeUnless { it == before || it in consumed }
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (failure: Exception) { error = failure.message ?: "读取投递历史失败" }
      finally { pending = false }
    }
  }
  LaunchedEffect(expected) { load() }
  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
  Text("投递历史", style = MiuixTheme.textStyles.body1)
  Text("以下仅是服务器投递回执，不代表 Agent 已运行、任务成功或完成。", style = MiuixTheme.textStyles.footnote2)
  metadata?.let { page ->
    Text("保留策略：${page.retentionDays} 天 / ${page.retentionRecords} 条。", style = MiuixTheme.textStyles.footnote2)
    if (page.earlierRecordsUnavailable) Text("更早的投递记录不可用。", style = MiuixTheme.textStyles.footnote2)
    if (page.earlierRecordsPruned) Text("更早的投递记录已按保留策略清理。", style = MiuixTheme.textStyles.footnote2)
  }
  if (pending) Text("正在读取投递记录…", style = MiuixTheme.textStyles.footnote2)
  error?.let { Text(it, color = HyperTheme.colors.error, style = MiuixTheme.textStyles.footnote2) }
  LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (loaded && records.isEmpty()) item { Text("暂无保留的投递记录。", style = MiuixTheme.textStyles.footnote2) }
    itemsIndexed(records, key = { index, record -> "$index:${record.messageId}:${record.deliveredAt}" }) { _, delivery -> ScheduleDeliveryRow(delivery) }
    item {
      val cursor = nextBefore
      if (cursor != null || !loaded) HyperButton(if (loaded) "读取更早记录" else "重试读取", { load(cursor) }, enabled = !pending &&
        !state.pending("schedule-mutation") && state.canManageSchedules() && scheduleOwnerRouteMatches(state, expected),
        loading = pending, variant = HyperButtonVariant.TERTIARY, modifier = Modifier.fillMaxWidth())
    }
  }
  }
}

@Composable
private fun ScheduleDeliveryRow(delivery: ScheduleDelivery) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text("计划：${delivery.scheduledAt}\n投递：${delivery.deliveredAt}\n消息 ID：${delivery.messageId}", style = MiuixTheme.textStyles.footnote2)
    delivery.prompt?.let { Text(it, style = MiuixTheme.textStyles.body2) }
  }
}
