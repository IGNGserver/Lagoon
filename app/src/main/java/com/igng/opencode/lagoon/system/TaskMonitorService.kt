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
  override fun onBind(intent: Intent?): IBinder? = null
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val controller = LagoonController.get(this)
    val notifications = TaskNotifications(this)
    val summary = controller.state.value.globalSummary
    val notification = if (summary.isEmpty) notifications.buildConnecting()
      else notifications.buildSummary(TaskNotifications.ALL_PROFILE, summary, controller.state.value.globalSummaryTarget)
    val id = TaskNotifications.summaryId(TaskNotifications.ALL_SERVERS_ID)
    if (Build.VERSION.SDK_INT >= 29) startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    else startForeground(id, notification)
    if (monitor == null) {
      controller.setMonitoring(true)
      monitor = scope.launch {
        controller.state.collect { state ->
          if (LiveUpdateContent.stageOf(state.globalSummary) == LiveUpdateStage.ACTIVE) {
            settle?.cancel(); settle = null
          } else if (settle == null) {
            // Tool-step gaps and reconnect bootstrap must not remove/recreate the island.
            settle = scope.launch {
              delay(4000)
              if (LiveUpdateContent.stageOf(controller.state.value.globalSummary) != LiveUpdateStage.ACTIVE) {
                stopForeground(STOP_FOREGROUND_DETACH)
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
