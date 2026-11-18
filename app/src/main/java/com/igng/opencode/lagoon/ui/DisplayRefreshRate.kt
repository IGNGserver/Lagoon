package com.igng.opencode.lagoon.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.abs

enum class HighRefreshPolicy(val label: String) {
  MAX("最高帧率（144Hz 优先）"),
  SYSTEM("跟随系统")
}

internal data class DisplayModeSpec(
  val modeId: Int,
  val physicalWidth: Int,
  val physicalHeight: Int,
  val refreshRate: Float
)

/**
 * Picks the highest refresh-rate display mode while preserving the active physical resolution.
 * Many 120Hz/144Hz phones expose multiple refresh rates per resolution (60/90/120/144Hz) and cap
 * non-whitelisted sideloaded apps at 60Hz unless [android.view.WindowManager.LayoutParams.preferredDisplayModeId]
 * explicitly requests the high-rate mode.
 */
internal fun pickPreferredDisplayMode(
  activeWidth: Int,
  activeHeight: Int,
  modes: List<DisplayModeSpec>,
  policy: HighRefreshPolicy
): DisplayModeSpec? {
  if (policy == HighRefreshPolicy.SYSTEM || modes.isEmpty()) return null
  val sameResolution = modes.filter {
    it.physicalWidth == activeWidth && it.physicalHeight == activeHeight
  }
  val candidates = sameResolution.ifEmpty { modes }
  return candidates.maxWithOrNull(
    compareBy<DisplayModeSpec> { it.refreshRate }.thenBy { it.modeId }
  )
}

internal fun ComponentActivity.applyHighRefreshPolicy(policy: HighRefreshPolicy) {
  val currentDisplay: Display? = if (Build.VERSION.SDK_INT >= 30) {
    runCatching { display }.getOrNull()
  } else {
    @Suppress("DEPRECATION")
    windowManager.defaultDisplay
  }
  val activeMode = currentDisplay?.mode
  val specs = currentDisplay?.supportedModes?.map { mode ->
    DisplayModeSpec(
      modeId = mode.modeId,
      physicalWidth = mode.physicalWidth,
      physicalHeight = mode.physicalHeight,
      refreshRate = mode.refreshRate
    )
  }.orEmpty()
  val preferred = if (activeMode != null) {
    pickPreferredDisplayMode(
      activeWidth = activeMode.physicalWidth,
      activeHeight = activeMode.physicalHeight,
      modes = specs,
      policy = policy
    )
  } else null

  val targetModeId = preferred?.modeId ?: 0
  val targetRefreshRate = preferred?.refreshRate ?: 0f
  val params = window.attributes
  if (params.preferredDisplayModeId != targetModeId || abs(params.preferredRefreshRate - targetRefreshRate) > 0.01f) {
    params.preferredDisplayModeId = targetModeId
    params.preferredRefreshRate = targetRefreshRate
    window.attributes = params
  }

  if (Build.VERSION.SDK_INT >= 35) {
    window.decorView.requestedFrameRate = if (policy == HighRefreshPolicy.MAX) {
      View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
    } else {
      View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
    }
  }
}

@Composable
internal fun ObserveDisplayRefreshPolicy(
  activity: ComponentActivity,
  policy: HighRefreshPolicy
) {
  LaunchedEffect(activity, policy) {
    activity.applyHighRefreshPolicy(policy)
  }
  DisposableEffect(activity, policy) {
    val displayManager = activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    val listener = object : DisplayManager.DisplayListener {
      override fun onDisplayAdded(displayId: Int) = Unit
      override fun onDisplayRemoved(displayId: Int) = Unit
      override fun onDisplayChanged(displayId: Int) {
        activity.applyHighRefreshPolicy(policy)
      }
    }
    displayManager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
    val lifecycleObserver = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        activity.applyHighRefreshPolicy(policy)
      }
    }
    activity.lifecycle.addObserver(lifecycleObserver)
    onDispose {
      activity.lifecycle.removeObserver(lifecycleObserver)
      displayManager?.unregisterDisplayListener(listener)
    }
  }
}
