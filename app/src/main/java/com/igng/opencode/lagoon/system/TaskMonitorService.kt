package com.igng.opencode.lagoon.system

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.ServerStore
import com.igng.opencode.lagoon.core.LiveUpdateContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class TaskMonitorService : Service() {
  companion object {
    fun start(context: Context, serverId: String, sessionId: String) {
      val intent = Intent(context, TaskMonitorService::class.java).putExtra("serverId", serverId).putExtra("sessionId", sessionId)
      androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }
    fun startAll(context: Context) {
      val intent = Intent(context, TaskMonitorService::class.java).putExtra("serverId", TaskNotifications.ALL_SERVERS_ID)
        .putExtra("sessionId", TaskNotifications.ALL_SERVERS_ID)
      androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }
  }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var monitor: Job? = null
  private val tracked = linkedSetOf<Pair<String, String>>()
  override fun onBind(intent: Intent?): IBinder? = null
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val serverId = intent?.getStringExtra("serverId") ?: run { stopSelf(); return START_NOT_STICKY }
    val sessionId = intent.getStringExtra("sessionId") ?: run { stopSelf(); return START_NOT_STICKY }
    val all = serverId == TaskNotifications.ALL_SERVERS_ID
    val profile = if (all) TaskNotifications.ALL_PROFILE else ServerStore(this).profiles().firstOrNull { it.id == serverId }
      ?: run { stopSelf(); return START_NOT_STICKY }
    val notifications = TaskNotifications(this)
    tracked += serverId to sessionId
    val controller = LagoonController.get(this)
    // 前台服务复用「服务器总览」通知（实时更新）本身，不再发第二条“任务监控中”常驻通知。
    // 通知 id 与控制器发布总览时相同，二者更新的是同一条通知。
    val foregroundId = TaskNotifications.summaryId(serverId)
    val summary = controller.state.value.summary
    val target = controller.state.value.summaryTarget
    if (Build.VERSION.SDK_INT >= 29) startForeground(foregroundId, notifications.buildSummary(profile, summary, target), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    else startForeground(foregroundId, notifications.buildSummary(profile, summary, target))
    if (all) {
      if (controller.state.value.allServers && profile.notifications && LiveUpdateContent.stageOf(summary) == com.igng.opencode.lagoon.core.LiveUpdateStage.ACTIVE) {
        if (monitor == null) {
          controller.setMonitoring(true)
          monitor = scope.launch {
            controller.state.collect { state ->
              if (!state.allServers || LiveUpdateContent.stageOf(state.summary) != com.igng.opencode.lagoon.core.LiveUpdateStage.ACTIVE) {
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
              }
            }
          }
        }
        return START_REDELIVER_INTENT
      }
      stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
    }
    if (controller.state.value.serverId != serverId || !profile.notifications) {
      tracked.remove(serverId to sessionId)
      if (tracked.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
      return START_NOT_STICKY
    }
    if (monitor == null) {
      controller.setMonitoring(true)
      monitor = scope.launch {
        // Plain collect (not collectLatest): emissions are frequent during streaming, and cancelling
        // the previous handler on every emission needlessly restarted notification work.
        controller.state.collect { state ->
          tracked.toList().forEach { key ->
            val (trackedServerId, trackedSessionId) = key
            // This service monitors only the current connection. Switching or removing a profile
            // drops its tracking.
            if (trackedServerId != state.serverId || state.profiles.none { it.id == trackedServerId && it.notifications } ||
              state.connected && !state.degraded && state.catalogComplete && state.sessions.none { it.id == trackedSessionId }) {
              tracked.remove(key)
              return@forEach
            }
            // Finished runs leave live state (their result moves to the unread ledger), so “no longer
            // active” — not a terminal phase — ends tracking. Keep waiting while offline: state is stale.
            val task = state.tasks[trackedSessionId]
            if (state.connected && task?.active != true) tracked.remove(key)
            // The controller owns task notifications. This service only keeps the SSE monitoring
            // process alive; it must never republish a result.
          }
          if (tracked.isEmpty()) {
            // DETACH would leave the monitoring notification behind; remove it and then stop.
            // minSdk is 26, so STOP_FOREGROUND_REMOVE is always available.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
          }
        }
      }
    }
    return START_REDELIVER_INTENT
  }
  override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }
  override fun onDestroy() {
    tracked.clear(); monitor?.cancel()
    if (monitor != null) LagoonController.get(this).setMonitoring(false)
    super.onDestroy()
  }
}
