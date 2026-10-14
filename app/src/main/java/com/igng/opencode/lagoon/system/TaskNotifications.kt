package com.igng.opencode.lagoon.system

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.igng.opencode.lagoon.R
import com.igng.opencode.lagoon.core.LiveUpdateContent
import com.igng.opencode.lagoon.core.LiveUpdateBucket
import com.igng.opencode.lagoon.core.LiveUpdateStage
import com.igng.opencode.lagoon.core.ServerStore
import com.igng.opencode.lagoon.core.displayTitle
import com.igng.opencode.lagoon.core.PermissionRequest
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.Session
import com.igng.opencode.lagoon.core.SessionTarget
import com.igng.opencode.lagoon.core.TaskPhase
import com.igng.opencode.lagoon.core.TaskState
import com.igng.opencode.lagoon.core.TaskSummary
import com.igng.opencode.lagoon.ui.MainActivity

class TaskNotifications(private val context: Context) {
  private val manager = context.getSystemService(NotificationManager::class.java)
  private val appIcon by lazy(LazyThreadSafetyMode.NONE) {
    BitmapFactory.decodeResource(context.resources, R.drawable.ic_app)
  }
  companion object {
    const val RUNNING = "task_running"
    const val ATTENTION = "task_attention"
    const val COMPLETED = "task_completed"
    const val SUMMARY = "task_summary"
    const val ALL_SERVERS_ID = "__all_servers__"
    val ALL_PROFILE = ServerProfile(ALL_SERVERS_ID, "全部服务器", "")
    fun notificationId(serverId: String, sessionId: String): Int = "${serverId}:$sessionId".hashCode() and 0x7fffffff
    /** One server-wide island/summary notification per server. */
    fun summaryId(serverId: String): Int = "summary:$serverId".hashCode() and 0x7fffffff
  }
  init {
    manager.createNotificationChannel(NotificationChannel(RUNNING, "正在运行", NotificationManager.IMPORTANCE_DEFAULT))
    manager.createNotificationChannel(NotificationChannel(ATTENTION, "需要处理", NotificationManager.IMPORTANCE_HIGH))
    manager.createNotificationChannel(NotificationChannel(COMPLETED, "任务结果", NotificationManager.IMPORTANCE_DEFAULT))
    manager.createNotificationChannel(NotificationChannel(SUMMARY, "任务总览", NotificationManager.IMPORTANCE_DEFAULT))
  }
  private fun allowed(): Boolean = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
  private fun open(serverId: String, sessionId: String): PendingIntent {
    val uri = Uri.parse("lagoon://server/${Uri.encode(serverId)}/session/${Uri.encode(sessionId)}")
    val intent = Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    return PendingIntent.getActivity(context, notificationId(serverId, sessionId), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }
  private fun action(name: String, profile: ServerProfile, session: Session, permission: PermissionRequest? = null): PendingIntent {
    val intent = Intent(context, NotificationActionReceiver::class.java).apply {
      this.action = name
      data = Uri.parse("lagoon://action/${Uri.encode(profile.id)}/${Uri.encode(session.id)}/$name")
      putExtra("serverId", profile.id)
      putExtra("profileUrl", profile.url)
      putExtra("sessionId", session.id)
      putExtra("directory", session.directory)
      permission?.let { putExtra("permissionId", it.id); putExtra("permissionDirectory", it.directory) }
      addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
    }
    return PendingIntent.getBroadcast(context, notificationId(profile.id, session.id) xor name.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }
  fun build(profile: ServerProfile, session: Session, state: TaskState, permission: PermissionRequest? = null): Notification {
    val waiting = state.phase in TaskState.WAITING_PHASES
    val running = state.phase in TaskState.RUNNING_PHASES
    val channel = if (waiting) ATTENTION else if (running) RUNNING else COMPLETED
    val title = when (state.phase) {
      TaskPhase.COMPLETED -> "已完成 · ${session.displayTitle()}"
      TaskPhase.FAILED -> "执行失败 · ${session.displayTitle()}"
      TaskPhase.ABORTED -> "已停止 · ${session.displayTitle()}"
      TaskPhase.WAITING_PERMISSION -> "需要授权 · ${session.displayTitle()}"
      TaskPhase.WAITING_QUESTION -> "需要回答 · ${session.displayTitle()}"
      else -> "运行中 · ${session.displayTitle()}"
    }
    // When a permission is pending, show what the agent actually wants to run (action + patterns)
    // instead of the fixed "等待权限确认" so the user can decide knowingly (A16).
    val body = permission?.let(::permissionSummary) ?: state.detail
    val builder = NotificationCompat.Builder(context, channel)
      .setSmallIcon(R.drawable.ic_notification).setLargeIcon(appIcon)
      .setContentTitle(title).setContentText(body.take(200))
      .setStyle(NotificationCompat.BigTextStyle().bigText(body))
      .setContentIntent(open(profile.id, session.id)).setAutoCancel(!state.active)
      .setOnlyAlertOnce(state.active).setOngoing(running)
      .setCategory(if (waiting) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_PROGRESS)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
    // Per-session notifications stay ordinary: only the server-wide summary owns the Live Update /
    // island slot, otherwise every running session would compete for the status bar chip.
    if (state.phase == TaskPhase.WAITING_PERMISSION && permission != null && permission.action.isNotBlank() && permission.detail !in setOf("", "{}", "[]")) {
      builder.addAction(0, "拒绝", action("reject", profile, session, permission))
      builder.addAction(0, "允许一次", action("once", profile, session, permission))

    }
    if (state.phase == TaskPhase.WAITING_QUESTION) {
      builder.addAction(0, "回答", open(profile.id, session.id))
    }
    return builder.build()
  }
  private fun permissionSummary(permission: PermissionRequest): String = buildString {
    append(permission.action.ifBlank { "操作请求" })
    if (permission.detail.isNotBlank()) append("\n").append(permission.detail.take(600))
    if (permission.always.isNotEmpty()) append("\n记住规则：").append(permission.always.joinToString(", ").take(200))
  }

  fun show(profile: ServerProfile, session: Session, state: TaskState, permission: PermissionRequest? = null, dedupeId: String? = null) {
    if (!allowed()) return
    if (!profile.notifications) return
    if (state.phase == TaskPhase.IDLE || state.phase == TaskPhase.DISCONNECTED || state.phase == TaskPhase.WAITING_SERVER) return
    // 只有「需要处理」与终态结果发普通通知；运行中一律交给灵动岛总览，避免通知中心堆积。
    if (state.phase in TaskState.RUNNING_PHASES) return
    val store = ServerStore(context)
    if (state.phase == TaskPhase.WAITING_PERMISSION && store.permissionPolicy().hide) return
    val previous = store.taskStates(profile.id)[session.id]
    val next = store.rememberTask(profile.id, if (previous != null && previous.phase == state.phase) state.copy(since = previous.since) else state, session.parentId)
    if (next.phase in setOf(TaskPhase.COMPLETED, TaskPhase.FAILED) && session.id in store.acknowledgedTasks(profile.id)) return
    // 去重键必须区分同一次运行里的不同请求：提问没有 permission id，只靠 since+phase 会让
    // 同一轮的第二次提问被当成重复而不再通知。
    val signature = dedupeId ?: permission?.id.orEmpty()
    if (!store.claimNotification(profile.id, next, signature)) return
    manager.notify(notificationId(profile.id, session.id), build(profile, session, next, permission))
  }
  fun cancel(serverId: String, sessionId: String) {
    manager.cancel(notificationId(serverId, sessionId))
  }

  /**
   * Server-wide summary. While tasks run or wait for the user it is an ongoing, promoted
   * notification (Android 16 Live Update: status bar chip / lock screen card); once everything has
   * settled it is demoted to an ordinary, dismissible notification. The expanded Android 16 card
   * uses the platform's segmented progress rail; the status chip remains intentionally compact.
   */
  fun buildSummary(profile: ServerProfile, summary: TaskSummary, target: SessionTarget?): Notification {
    val content = LiveUpdateContent.of(summary)
    val active = content.stage == LiveUpdateStage.ACTIVE
    val builder = NotificationCompat.Builder(context, SUMMARY)
      .setSmallIcon(R.drawable.ic_notification).setLargeIcon(appIcon)
      .setContentTitle(content.title).setContentText(content.text)
      .setSubText(profile.name)
      .setOngoing(active).setAutoCancel(!active)
      .setOnlyAlertOnce(true).setShowWhen(false)
      // 内容级更新不应触发声音/横幅；同一内容重复下发时只应平滑更新，而不是重放“收到通知”动画。
      .setSilent(true)
      .setCategory(if (active) NotificationCompat.CATEGORY_PROGRESS else NotificationCompat.CATEGORY_STATUS)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setRequestPromotedOngoing(active)
      .setDeleteIntent(summaryDismissed(profile.id, content.stage))
    if (active) {
      builder.setShortCriticalText(content.shortCriticalText)
      if (Build.VERSION.SDK_INT >= 36) {
        builder.setStyle(progressStyle(content))
      } else {
        // Older Android versions have no Live Update progress rail. Show an indeterminate bar
        // instead of implying a completion percentage the server does not provide.
        builder.setProgress(0, 0, true)
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(content.expandedText))
      }
    } else {
      builder.setStyle(NotificationCompat.BigTextStyle().bigText(content.expandedText))
    }
    (target ?: content.headlineSessionId?.let { SessionTarget(profile.id, it) })?.let { targetSession ->
      builder.setContentIntent(open(targetSession.serverId, targetSession.sessionId))
    }
    return builder.build()
  }

  /**
   * 前台服务被系统重投递（START_REDELIVER_INTENT）时控制器可能还没有任何汇总：
   * 必须先给出一条可读的占位通知，而不是空白的“0跑·0完”。
   */
  fun buildConnecting(): Notification = NotificationCompat.Builder(context, SUMMARY)
    .setSmallIcon(R.drawable.ic_notification).setLargeIcon(appIcon)
    .setContentTitle("Lagoon").setContentText("正在恢复任务监控…")
    .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).setSilent(true)
    .setCategory(NotificationCompat.CATEGORY_STATUS)
    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
    .build()

  /**
   * A categorical status rail, not a fake completion percentage. Segment lengths represent the
   * current task mix without adding an icon to the center of the rail.
   */
  private fun progressStyle(content: LiveUpdateContent): NotificationCompat.ProgressStyle {
    val style = NotificationCompat.ProgressStyle()
      .setStyledByProgress(false)
      .setProgress(content.progress.position)
      .setProgressSegments(content.progress.segments.map { segment ->
        NotificationCompat.ProgressStyle.Segment(segment.length).setColor(progressColor(segment.bucket))
      })
    return style
  }

  private fun progressColor(bucket: LiveUpdateBucket): Int = when (bucket) {
    LiveUpdateBucket.RUNNING -> Color.rgb(72, 151, 244)
    LiveUpdateBucket.WAITING -> Color.rgb(245, 177, 67)
    LiveUpdateBucket.COMPLETED -> Color.rgb(76, 181, 112)
    LiveUpdateBucket.FAILED -> Color.rgb(224, 100, 105)
  }

  private fun summaryDismissed(serverId: String, stage: LiveUpdateStage): PendingIntent {
    val intent = Intent(context, NotificationActionReceiver::class.java).apply {
      action = NotificationActionReceiver.ACTION_SUMMARY_DISMISSED
      data = Uri.parse("lagoon://summary/${Uri.encode(serverId)}/${stage.name}")
      putExtra("serverId", serverId)
      putExtra("stage", stage.name)
    }
    return PendingIntent.getBroadcast(context, summaryId(serverId), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }

  fun showSummary(profile: ServerProfile, summary: TaskSummary, target: SessionTarget?) {
    val stage = LiveUpdateContent.stageOf(summary)
    // 只有进行中的任务上岛。全部结束后不再保留任何总览通知：结果交给每会话的完成/失败通知，
    // 这样通知中心在空闲时不会留一条“N 已完成”的汇总。
    if (stage != LiveUpdateStage.ACTIVE) {
      SummaryDismissals.clear(context, profile.id)
      manager.cancel(summaryId(profile.id))
      return
    }
    val dismissed = SummaryDismissals.stage(context, profile.id)
    // 用户划掉后，只要阶段没变就不重发；阶段一变（例如此轮结束又重新开始）再展示。
    if (!allowed() || !profile.notifications || !LiveUpdateContent.shouldPost(stage, dismissed)) {
      manager.cancel(summaryId(profile.id))
      return
    }
    manager.notify(summaryId(profile.id), buildSummary(profile, summary, target))
  }
  fun cancelSummary(serverId: String) = manager.cancel(summaryId(serverId))

  /** 打开系统「实时更新 / 提升为常驻通知」授权页（Android 16+）。 */
  fun promotedNotificationSettingsIntent(): Intent? {
    if (Build.VERSION.SDK_INT < 36) return null
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
      .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return if (intent.resolveActivity(context.packageManager) != null) intent else null
  }
}
