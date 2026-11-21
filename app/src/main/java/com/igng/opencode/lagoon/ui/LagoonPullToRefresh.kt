package com.igng.opencode.lagoon.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首页下拉刷新。
 *
 * 不使用 MIUIX 的 `PullToRefresh`：它在 `Refreshing`/`RefreshComplete` 时会消费 nested scroll 的
 * 全部位移，列表在刷新期间完全不能滚动。这里只在「列表已经在顶部、且没有在刷新」时消费下拉，
 * 刷新中把所有滚动原样交给 LazyColumn；刷新指示器是悬浮胶囊，不占列表布局高度。
 *
 * [onRefresh] 由调用方负责真正的数据刷新；本组件只负责手势、阈值和视觉状态。
 */
@Composable
internal fun LagoonPullToRefresh(
  isRefreshing: Boolean,
  onRefresh: () -> Unit,
  listState: LazyListState,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit
) {
  val density = LocalDensity.current
  val thresholdPx = with(density) { 64.dp.toPx() }
  val maxPullPx = with(density) { 112.dp.toPx() }
  val scope = rememberCoroutineScope()

  var pullOffset by remember { mutableFloatStateOf(0f) }
  var animation by remember { mutableStateOf<Job?>(null) }
  var completed by remember { mutableStateOf(false) }
  var wasRefreshing by remember { mutableStateOf(false) }
  val latestRefreshing by rememberUpdatedState(isRefreshing)
  val latestOnRefresh by rememberUpdatedState(onRefresh)

  fun animateTo(target: Float) {
    animation?.cancel()
    animation = scope.launch {
      animate(
        initialValue = pullOffset,
        targetValue = target,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
      ) { value, _ -> pullOffset = value }
    }
  }

  fun release() {
    animation?.cancel()
    if (latestRefreshing) {
      pullOffset = 0f
      return
    }
    if (pullOffset >= thresholdPx) {
      pullOffset = 0f
      latestOnRefresh()
    } else {
      animateTo(0f)
    }
  }

  val latestRelease by rememberUpdatedState { release() }

  LaunchedEffect(isRefreshing) {
    if (isRefreshing) {
      wasRefreshing = true
      completed = false
      animation?.cancel()
      pullOffset = 0f
    } else if (wasRefreshing) {
      completed = true
      delay(900)
      completed = false
      wasRefreshing = false
    }
  }

  val connection = remember(listState) {
    object : NestedScrollConnection {
      override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // 刷新期间绝不消费：这是旧组件把列表冻住的根本原因。
        if (latestRefreshing) return Offset.Zero
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        if (available.y != 0f) animation?.cancel()
        if (available.y > 0f && !listState.canScrollBackward) {
          // 已经在顶部继续下拉：吃掉这次位移，换算成刷新手势的位移。
          pullOffset = (pullOffset + available.y * PULL_GAIN).coerceIn(0f, maxPullPx)
          return Offset(0f, available.y)
        }
        if (available.y < 0f && pullOffset > 0f) {
          // 下拉后反向推回：先把刷新手势收回，再让列表正常滚动。
          val consumed = maxOf(available.y, -pullOffset)
          pullOffset = (pullOffset + consumed).coerceAtLeast(0f)
          return Offset(0f, consumed)
        }
        return Offset.Zero
      }
    }
  }

  val pointer = Modifier.pointerInput(Unit) {
    awaitEachGesture {
      awaitFirstDown(requireUnconsumed = false)
      while (true) {
        val event = awaitPointerEvent(PointerEventPass.Final)
        if (event.changes.none { it.pressed }) break
      }
      latestRelease()
    }
  }

  Box(modifier.fillMaxSize().nestedScroll(connection).then(pointer)) {
    Box(Modifier.fillMaxSize().graphicsLayer { translationY = pullOffset }) { content() }
    PullRefreshIndicator(
      offset = { pullOffset },
      isRefreshing = isRefreshing,
      completed = completed,
      thresholdPx = thresholdPx,
      modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
    )
  }
}

@Composable
private fun PullRefreshIndicator(
  offset: () -> Float,
  isRefreshing: Boolean,
  completed: Boolean,
  thresholdPx: Float,
  modifier: Modifier = Modifier
) {
  val value = offset()
  val visible = isRefreshing || completed || value > 0f
  if (!visible) return
  val progress = (value / thresholdPx).coerceIn(0f, 1f)
  // 慢刷新时把话说清楚：刷新还没结束，只是比预期久；不要在这时冒出一句“已完成/仍在后台”。
  var slow by remember { mutableStateOf(false) }
  LaunchedEffect(isRefreshing) {
    slow = false
    if (!isRefreshing) return@LaunchedEffect
    delay(5_000)
    slow = true
  }
  val label = when {
    isRefreshing -> if (slow) "仍在刷新…" else "正在刷新…"
    completed -> "已刷新"
    value >= thresholdPx -> "松开刷新"
    else -> "下拉刷新"
  }
  HyperPill(
    modifier = modifier.graphicsLayer {
      alpha = if (isRefreshing || completed) 1f else progress
    },
    background = HyperTheme.colors.cardElevated
  ) {
    if (isRefreshing) {
      InfiniteProgressIndicator(color = HyperTheme.colors.primary, size = 14.dp, strokeWidth = 2.dp)
    } else {
      Icon(
        MiuixIcons.Refresh,
        contentDescription = null,
        modifier = Modifier.size(16.dp).rotate(progress * 180f),
        tint = if (completed) MiuixColorTokens.Success else HyperTheme.colors.primary
      )
    }
    Spacer(Modifier.width(8.dp))
    Text(
      label,
      style = MiuixTheme.textStyles.footnote1,
      color = if (completed) MiuixColorTokens.Success else HyperTheme.colors.textSecondary
    )
  }
}

private const val PULL_GAIN = 0.55f
