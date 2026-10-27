package com.igng.opencode.lagoon.system

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.LiveUpdateContent
import com.igng.opencode.lagoon.core.LiveUpdateStage
import com.igng.opencode.lagoon.core.SessionTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keeps the global monitor alive; navigation never changes the foreground notification's owner. */
class TaskMonitorService : Service() {
  companion object {
    fun start(context: Context, serverId: String, sessionId: String) = startAll(context)
    fun startAll(context: Context) {
      androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, TaskMonitorService::class.java))
    }
  }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var monitor: Job? = null
  private var settle: Job? = null
  private var lastBackgroundTargets: List<SessionTarget> = emptyList()
  override fun onBind(intent: Intent?): IBinder? = null
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val controller = LagoonController.get(this)
    val notifications = TaskNotifications(this)
    val initial = controller.state.value
    val summary = initial.globalSummary
    val background = initial.globalBackgroundTargets
    lastBackgroundTargets = background
    val notification = when {
      summary.isEmpty && background.isEmpty() -> notifications.buildConnecting()
      LiveUpdateContent.stageOf(summary) == LiveUpdateStage.ACTIVE ->
        notifications.buildSummary(TaskNotifications.ALL_PROFILE, summary, initial.globalSummaryTarget)
      background.isNotEmpty() -> notifications.buildBackgroundSummary(TaskNotifications.ALL_PROFILE, background)
      else -> notifications.buildConnecting()
    }
    var foregroundId = if (LiveUpdateContent.stageOf(summary) == LiveUpdateStage.ACTIVE)
      TaskNotifications.summaryId(TaskNotifications.ALL_SERVERS_ID)
    else TaskNotifications.backgroundId(TaskNotifications.ALL_SERVERS_ID)
    if (Build.VERSION.SDK_INT >= 29) startForeground(foregroundId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    else startForeground(foregroundId, notification)
    if (monitor == null) {
      controller.setMonitoring(true)
      monitor = scope.launch {
        controller.state.collect { state ->
          val active = LiveUpdateContent.stageOf(state.globalSummary) == LiveUpdateStage.ACTIVE
          val background = state.globalBackgroundCount > 0
          if (active || background) {
            val desired = if (active) TaskNotifications.summaryId(TaskNotifications.ALL_SERVERS_ID)
              else TaskNotifications.backgroundId(TaskNotifications.ALL_SERVERS_ID)
            if (background && !active && state.globalBackgroundTargets != lastBackgroundTargets) {
              notifications.updateBackground(TaskNotifications.ALL_PROFILE, state.globalBackgroundTargets)
              lastBackgroundTargets = state.globalBackgroundTargets
            }
            if (desired != foregroundId) {
              val next = if (active) notifications.buildSummary(TaskNotifications.ALL_PROFILE, state.globalSummary, state.globalSummaryTarget)
                else notifications.buildBackgroundSummary(TaskNotifications.ALL_PROFILE, state.globalBackgroundTargets)
              if (Build.VERSION.SDK_INT >= 29) startForeground(desired, next, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
              else startForeground(desired, next)
              if (active) notifications.cancelBackground(TaskNotifications.ALL_SERVERS_ID)
              else notifications.cancelSummary(TaskNotifications.ALL_SERVERS_ID)
              foregroundId = desired
            }
          }
          if (active || background) {
            settle?.cancel(); settle = null
          } else if (settle == null) {
            // Tool-step gaps and reconnect bootstrap must not remove/recreate the island.
            settle = scope.launch {
              delay(4000)
              val current = controller.state.value
              if (LiveUpdateContent.stageOf(current.globalSummary) != LiveUpdateStage.ACTIVE && current.globalBackgroundCount == 0) {
                // Remove the foreground notification as well: a background-only monitor must not
                // leave a low-key notification behind after its work ends.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
              }
            }
          }
        }
      }
    }
    return START_REDELIVER_INTENT
  }
  override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }
  override fun onDestroy() {
    settle?.cancel(); monitor?.cancel()
    if (monitor != null) LagoonController.get(this).setMonitoring(false)
    super.onDestroy()
  }
}
