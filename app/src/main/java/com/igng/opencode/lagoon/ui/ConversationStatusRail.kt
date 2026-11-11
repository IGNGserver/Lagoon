package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.GoalMutationTarget
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.ResourceState
import com.igng.opencode.lagoon.core.canResumeGoal
import kotlinx.coroutines.launch
import com.igng.opencode.lagoon.core.LagoonState
import com.igng.opencode.lagoon.core.RemoteGoal
import com.igng.opencode.lagoon.core.RemoteTodo
import com.igng.opencode.lagoon.core.todoProgress
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Which detail panel the status rail opened; the panel owns its own content. */
private enum class ConversationStatusSheet { GOAL, TODOS }

/**
 * 输入框上方的会话状态轨：一行目标胶囊（阶段 + 截断的目标）与一个任务进度胶囊（已完成/总数）。
 *
 * 完整目标、阻塞原因和所有目标操作都在点开的面板里；任务清单同理。已完成的目标与空清单不占位，
 * 因此这块区域平时高度为零，只有真正有事时才出现一行。
 */
@Composable
internal fun ConversationStatusRail(state: LagoonState, controller: LagoonController) {
  // 已完成的目标与官方客户端一致：不再常驻，避免一行“已完成”长期占位。
  val goal = state.goal?.takeUnless(RemoteGoal::complete)
  val todos = state.todos
  var sheet by remember(state.serverId, state.sessionId) { mutableStateOf<ConversationStatusSheet?>(null) }
  if (goal == null && todos.isEmpty() && sheet == null) return
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    if (goal != null) {
      GoalCapsule(
        goal = goal,
        modifier = Modifier.weight(1f),
        onClick = {
          // 面板打开前重读一次：阶段与 activation 可能已被其他客户端改动。
          controller.loadGoal()
          sheet = ConversationStatusSheet.GOAL
        }
      )
    }
    if (todos.isNotEmpty()) {
      TodoCapsule(todos, onClick = { sheet = ConversationStatusSheet.TODOS })
    }
  }
  when (sheet) {
    ConversationStatusSheet.GOAL -> GoalSheet(state, controller, onDismiss = { sheet = null })
    ConversationStatusSheet.TODOS -> TodoSheet(todos, onDismiss = { sheet = null })
    null -> Unit
  }
}

/** One compact, tappable capsule. The 38dp surface sits inside a 48dp touch target. */
@Composable
private fun StatusCapsule(
  modifier: Modifier = Modifier,
  background: Color = HyperTheme.colors.cardElevated,
  description: String,
  /** True for the goal capsule, whose caller gives it the row's remaining width. */
  fillWidth: Boolean = false,
  onClick: () -> Unit,
  content: @Composable RowScope.() -> Unit
) {
  val shape = miuixSquircleShape(18.dp)
  Box(
    modifier.heightIn(min = HYPER_TOUCH_TARGET_DP.dp)
      .hyperPress(onClick = onClick)
      .semantics {
        contentDescription = description
        role = Role.Button
      },
    contentAlignment = Alignment.CenterStart
  ) {
    Row(
      Modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
        .height(38.dp)
        .clip(shape)
        .background(background, shape)
        .border(0.6.dp, HyperTheme.colors.cardBorder, shape)
        .padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      content = content
    )
  }
}

@Composable
private fun GoalCapsule(goal: RemoteGoal, modifier: Modifier = Modifier, onClick: () -> Unit) {
  val tint = when {
    goal.blocked -> HyperTheme.colors.error
    goal.paused -> HyperTheme.colors.warning
    else -> HyperTheme.colors.primary
  }
  StatusCapsule(
    modifier = modifier,
    background = if (goal.blocked) HyperTheme.colors.errorSubtle else HyperTheme.colors.cardElevated,
    description = "长期目标，${goal.label}，点按查看完整目标与操作",
    fillWidth = true,
    onClick = onClick
  ) {
    QoderIcon(QoderGlyph.GOAL, Modifier.size(15.dp), tint)
    Text(goal.label, maxLines = 1, style = MiuixTheme.textStyles.footnote2.copy(color = tint, fontWeight = FontWeight.Medium))
    if (goal.objective.isBlank()) {
      Spacer(Modifier.weight(1f))
    } else {
      Text(
        goal.objective,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
      )
    }
    ForwardChevron(14.dp, HyperTheme.colors.textTertiary)
  }
}

@Composable
private fun TodoCapsule(todos: List<RemoteTodo>, onClick: () -> Unit) {
  val progress = todos.todoProgress()
  val tint = when {
    progress.complete -> HyperTheme.colors.success
    todos.any { it.active } -> HyperTheme.colors.primary
    else -> HyperTheme.colors.textSecondary
  }
  StatusCapsule(
    description = "任务清单，${progress.done}/${progress.total} 已完成，点按查看清单",
    onClick = onClick
  ) {
    QoderIcon(QoderGlyph.LIST, Modifier.size(15.dp), tint)
    Text("${progress.done}/${progress.total}", maxLines = 1,
      style = MiuixTheme.textStyles.footnote2.copy(color = tint, fontWeight = FontWeight.Medium))
  }
}

/** Complete objective, known budget and explicit actions; editing never resumes continuation. */
@Composable
private fun GoalSheet(state: LagoonState, controller: LagoonController, onDismiss: () -> Unit) {
  val goal = state.goal
  var editTarget by remember { mutableStateOf<GoalMutationTarget?>(null) }
  var editSource by remember { mutableStateOf<RemoteGoal?>(null) }
  var clearTarget by remember { mutableStateOf<GoalMutationTarget?>(null) }
  // Keep an open editor intact even if another client clears/completes/replaces the goal.
  LaunchedEffect(goal?.id, goal?.phase, editTarget, clearTarget) {
    if (editTarget == null && clearTarget == null && (goal == null || goal.complete)) onDismiss()
  }
  editTarget?.let { captured ->
    GoalEditDialog(state, controller, captured, editSource ?: return, onDismiss = { editTarget = null })
    return
  }
  clearTarget?.let { captured ->
    SuperDialog(title = "清除长期目标？", show = true, onDismissRequest = { if (!state.pending("goal")) clearTarget = null }) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("只会清除打开确认框时的目标，不会删除会话和消息。")
        if (!captured.matches(state)) Text("目标或连接已变化，请取消后重新确认。", color = HyperTheme.colors.warning)
        ActionError(state, "goal")
        DialogActions("取消", { if (!state.pending("goal")) clearTarget = null },
          if (state.pending("goal")) "清除中…" else "确认清除",
          enabled = state.goalControlsAvailable() && captured.matches(state), danger = true) {
          controller.goalAction("clear", expected = captured)
          clearTarget = null
        }
      }
    }
    return
  }
  if (goal == null || goal.complete) return
  val target = state.server?.let { profile -> state.sessionId?.let { GoalMutationTarget(profile, it, goal.id, goal.revision) } }
  val enabled = state.goalControlsAvailable() && target?.matches(state) == true
  val tint = when {
    goal.blocked -> HyperTheme.colors.error
    goal.paused -> HyperTheme.colors.warning
    else -> HyperTheme.colors.primary
  }
  HyperBottomSheet(title = "长期目标", show = true, onDismissRequest = onDismiss) {
    Column(
      Modifier.fillMaxWidth().fillMaxHeight(0.72f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        QoderIcon(QoderGlyph.GOAL, Modifier.size(16.dp), tint)
        Spacer(Modifier.width(8.dp))
        Text(goal.label, style = MiuixTheme.textStyles.footnote1.copy(color = tint, fontWeight = FontWeight.SemiBold))
      }
      SelectionContainer { Text(goal.objective, style = MiuixTheme.textStyles.body2.copy(color = HyperTheme.colors.textPrimary)) }
      Text(goalRoundSummary(goal), style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary))
      goal.blockedReason?.let { Text(it, style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.error)) }
      when {
        goal.active && goal.activation == "disarmed" -> Text("Host 目前不会自动续跑；恢复自动续跑需要单独确认操作。", style = MiuixTheme.textStyles.footnote2)
        goal.active && goal.activation == null -> Text("自动续跑状态未知，请重新读取 Host 状态。", style = MiuixTheme.textStyles.footnote2)
      }
      HyperButton(text = "编辑目标", variant = HyperButtonVariant.SECONDARY, enabled = enabled,
        onClick = { editSource = goal; editTarget = target }, modifier = Modifier.fillMaxWidth())
      when {
        goal.canResumeGoal() -> HyperButton(text = if (goal.active) "恢复自动续跑" else "继续目标", variant = HyperButtonVariant.SECONDARY,
          enabled = enabled, onClick = { controller.goalAction("resume", expected = target) }, modifier = Modifier.fillMaxWidth())
        goal.active && goal.activation == "armed" -> HyperButton(text = "暂停目标", variant = HyperButtonVariant.SECONDARY,
          enabled = enabled, onClick = { controller.goalAction("pause", expected = target) }, modifier = Modifier.fillMaxWidth())
        else -> Text("自动续跑未就绪：轮次预算耗尽或状态未知。可编辑上限后单独恢复，或重新读取状态。", style = MiuixTheme.textStyles.footnote2)
      }
      HyperButton(text = "重新读取状态", variant = HyperButtonVariant.TERTIARY, enabled = enabled,
        onClick = { controller.loadGoal() }, modifier = Modifier.fillMaxWidth())
      HyperButton(text = "标记完成", variant = HyperButtonVariant.SECONDARY, enabled = enabled,
        onClick = { controller.goalAction("complete", expected = target) }, modifier = Modifier.fillMaxWidth())
      HyperButton(text = "清除目标", variant = HyperButtonVariant.DESTRUCTIVE, enabled = enabled,
        onClick = { clearTarget = target }, modifier = Modifier.fillMaxWidth())
      ActionError(state, "goal")
      ActionError(state, "goal-refresh")
      Spacer(Modifier.height(4.dp))
    }
  }
}

internal fun goalRoundSummary(goal: RemoteGoal): String =
  "自动续跑轮次：${goal.roundsStarted?.toString() ?: "未知"} / 上限 ${goal.maxGoalRounds?.toString() ?: "未知"}"

@Composable
private fun GoalEditDialog(state: LagoonState, controller: LagoonController, captured: GoalMutationTarget, source: RemoteGoal, onDismiss: () -> Unit) {
  var expected by remember { mutableStateOf(captured) }
  var objective by remember { mutableStateOf(source.objective) }
  var capText by remember { mutableStateOf(source.maxGoalRounds?.toString().orEmpty()) }
  var metadataNotice by remember { mutableStateOf<String?>(null) }
  var reloading by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val pending = state.pending("goal") || reloading
  val cap = parseGoalRoundCap(capText)
  val stale = !expected.matches(state)
  SuperDialog(title = "编辑长期目标", show = true, onDismissRequest = { if (!pending) onDismiss() }) {
    Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("保存仅修改目标与轮次上限，保留阶段和自动续跑状态；不会自动恢复。恢复须返回目标面板单独操作。", style = MiuixTheme.textStyles.footnote2)
      TextField(value = objective, onValueChange = { if (!pending) objective = it }, label = "完整目标", singleLine = false,
        modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp, max = 220.dp))
      TextField(value = capText, onValueChange = { if (!pending) capText = it }, label = "轮次上限（可选）", singleLine = true,
        modifier = Modifier.fillMaxWidth())
      Text("留空保留 Host 当前上限；正整数，最大 $MAX_GOAL_ROUND_CAP。", style = MiuixTheme.textStyles.footnote2)
      cap.exceptionOrNull()?.message?.let { Text(it, color = HyperTheme.colors.error, style = MiuixTheme.textStyles.footnote2) }
      Text("编辑基于版本 ${expected.revision} · ${state.goal?.let(::goalRoundSummary) ?: "当前目标不存在"}", style = MiuixTheme.textStyles.footnote2)
      if (stale) Text("目标版本或连接已变化，保存已禁用。重新读取元数据后再次确认；草稿不会被覆盖。", color = HyperTheme.colors.warning, style = MiuixTheme.textStyles.footnote2)
      metadataNotice?.let { Text(it, style = MiuixTheme.textStyles.footnote2) }
      HyperButton(text = if (reloading) "读取中…" else "重新读取元数据（保留草稿）", variant = HyperButtonVariant.TERTIARY,
        enabled = !pending && state.goalControlsAvailable(), modifier = Modifier.fillMaxWidth(), onClick = {
          reloading = true
          scope.launch {
            try {
              controller.loadGoal().join()
              val refreshed = controller.state.value
              val latest = refreshed.goal
              val rebased = latest?.let { expected.copy(revision = it.revision) }
              if (refreshed.resource("action:goal-refresh").state == ResourceState.READY && rebased?.matches(refreshed) == true) {
                expected = rebased
                metadataNotice = "已读取版本 ${rebased.revision}，保留了你的目标与上限草稿；请核对后保存。"
              } else metadataNotice = "未能确认同一连接上的同一目标；保留草稿，不会自动切换目标。"
            } finally { reloading = false }
          }
        })
      ActionError(state, "goal-refresh")
      ActionError(state, "goal")
      DialogActions("取消", { if (!pending) onDismiss() }, if (state.pending("goal")) "保存中…" else "保存修改",
        enabled = !pending && goalEditorCanSave(state, expected, objective, capText)) {
        controller.editGoal(expected, objective.trim(), cap.getOrNull(), onEdited = onDismiss)
      }
    }
  }
}

/** 当前清单：完成数、逐条状态；清空由 Host 在下一轮开始时自动完成。 */
@Composable
private fun TodoSheet(todos: List<RemoteTodo>, onDismiss: () -> Unit) {
  LaunchedEffect(todos.isEmpty()) { if (todos.isEmpty()) onDismiss() }
  if (todos.isEmpty()) return
  val progress = todos.todoProgress()
  HyperBottomSheet(title = "任务清单", show = true, onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.72f).padding(horizontal = 20.dp, vertical = 8.dp)) {
      Text(
        "${progress.done}/${progress.total} 已完成",
        style = MiuixTheme.textStyles.footnote1.copy(
          color = if (progress.complete) HyperTheme.colors.success else HyperTheme.colors.textPrimary,
          fontWeight = FontWeight.SemiBold
        )
      )
      LazyColumn(
        Modifier.weight(1f).padding(top = 10.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        // The Host list has no stable ids; its order is the identity a reader follows.
        itemsIndexed(todos, key = { index, _ -> index }) { _, todo -> TodoRow(todo) }
      }
      Text(
        "清单由 Host 维护，会在下一轮开始时清空。",
        style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textTertiary),
        modifier = Modifier.padding(top = 10.dp, bottom = 8.dp)
      )
    }
  }
}

@Composable
private fun TodoRow(todo: RemoteTodo) {
  val label = when {
    todo.done -> "已完成"
    todo.active -> "进行中"
    else -> "待处理"
  }
  Row(
    Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$label，${todo.content}" },
    verticalAlignment = Alignment.Top
  ) {
    when {
      todo.done -> QoderIcon(QoderGlyph.CHECK, Modifier.size(16.dp), HyperTheme.colors.success)
      todo.active -> QoderIcon(QoderGlyph.GOAL, Modifier.size(16.dp), HyperTheme.colors.primary)
      else -> QoderIcon(QoderGlyph.RING, Modifier.size(16.dp), HyperTheme.colors.textTertiary)
    }
    Spacer(Modifier.width(10.dp))
    Text(
      todo.content,
      style = MiuixTheme.textStyles.body2.copy(
        color = if (todo.done) HyperTheme.colors.textTertiary else HyperTheme.colors.textPrimary,
        fontWeight = if (todo.active) FontWeight.Medium else FontWeight.Normal,
        textDecoration = if (todo.done) TextDecoration.LineThrough else null
      )
    )
  }
}
