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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.LagoonState
import com.igng.opencode.lagoon.core.RemoteGoal
import com.igng.opencode.lagoon.core.RemoteTodo
import com.igng.opencode.lagoon.core.todoProgress
import top.yukonga.miuix.kmp.basic.Text
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
  if (goal == null && todos.isEmpty()) return
  var sheet by remember(state.serverId, state.sessionId) { mutableStateOf<ConversationStatusSheet?>(null) }
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

/** 完整目标、阻塞原因与目标操作；轮次不再展示，只保留用户能决策的状态。 */
@Composable
private fun GoalSheet(state: LagoonState, controller: LagoonController, onDismiss: () -> Unit) {
  val goal = state.goal
  var confirmClear by remember(goal?.id) { mutableStateOf(false) }
  // 目标在别处被清除/完成时，面板跟随关闭，避免停在已经不存在的目标上。
  LaunchedEffect(goal?.id, goal?.phase) { if (goal == null || goal.complete) onDismiss() }
  if (goal == null || goal.complete) return
  val tint = when {
    goal.blocked -> HyperTheme.colors.error
    goal.paused -> HyperTheme.colors.warning
    else -> HyperTheme.colors.primary
  }
  HyperBottomSheet(title = "长期目标", show = true, onDismissRequest = onDismiss) {
    Column(
      Modifier.fillMaxWidth().fillMaxHeight(0.72f).padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        QoderIcon(QoderGlyph.GOAL, Modifier.size(16.dp), tint)
        Spacer(Modifier.width(8.dp))
        Text(goal.label, style = MiuixTheme.textStyles.footnote1.copy(color = tint, fontWeight = FontWeight.SemiBold))
      }
      SelectionContainer {
        Text(goal.objective, style = MiuixTheme.textStyles.body2.copy(color = HyperTheme.colors.textPrimary))
      }
      goal.blockedReason?.let { reason ->
        Text(reason, style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.error))
      }
      if (goal.active && goal.activation == "disarmed") {
        Text(
          "Host 进程目前不会自动续跑（例如重启后需要恢复）；继续会重新开启自动续跑。",
          style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
        )
      }
      Spacer(Modifier.weight(1f))
      when {
        goal.active && goal.activation == "disarmed" -> HyperButton(text = "恢复自动续跑", variant = HyperButtonVariant.SECONDARY,
          onClick = { controller.goalAction("resume") }, modifier = Modifier.fillMaxWidth())
        goal.active -> HyperButton(text = "暂停目标", variant = HyperButtonVariant.SECONDARY,
          onClick = { controller.goalAction("pause") }, modifier = Modifier.fillMaxWidth())
        else -> HyperButton(text = "继续目标", variant = HyperButtonVariant.SECONDARY,
          onClick = { controller.goalAction("resume") }, modifier = Modifier.fillMaxWidth())
      }
      HyperButton(text = "标记完成", variant = HyperButtonVariant.SECONDARY,
        onClick = { controller.goalAction("complete") }, modifier = Modifier.fillMaxWidth())
      HyperButton(text = "清除目标", variant = HyperButtonVariant.DESTRUCTIVE,
        onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth())
      ActionError(state, "goal")
      Spacer(Modifier.height(4.dp))
    }
  }
  if (confirmClear) {
    SuperDialog(title = "清除长期目标？", show = true, onDismissRequest = { confirmClear = false }) {
      Column {
        Text("只会清除当前目标，不会删除会话和消息。")
        DialogActions("取消", { confirmClear = false }, "确认清除", danger = true) {
          controller.goalAction("clear")
          confirmClear = false
        }
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
