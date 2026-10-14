package com.igng.opencode.lagoon.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.igng.opencode.lagoon.core.ApiException
import com.igng.opencode.lagoon.core.Diagnostics
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.LiveUpdateStage
import com.igng.opencode.lagoon.core.OpenCodeApi
import com.igng.opencode.lagoon.core.ServerStore
import com.igng.opencode.lagoon.core.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Notification commands use a dedicated, bounded client and the profile origin captured in the
 * PendingIntent. A successful command reconciles only the matching UI connection. */
class NotificationActionReceiver : BroadcastReceiver() {
  companion object {
    /** goAsync() extends the receiver for ~10s; keep the whole action inside that budget. */
    private const val ACTION_TIMEOUT_MILLIS = 8_000L
    /** Delete intent of the server-wide summary: the user swiped it away. */
    const val ACTION_SUMMARY_DISMISSED = "summary_dismissed"
  }

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == ACTION_SUMMARY_DISMISSED) {
      val serverId = intent.getStringExtra("serverId") ?: return
      val stage = LiveUpdateStage.entries.firstOrNull { it.name == intent.getStringExtra("stage") } ?: return
      SummaryDismissals.record(context.applicationContext, serverId, stage)
      return
    }
    val pending = goAsync()
    val appContext = context.applicationContext
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
      try {
        val serverId = intent.getStringExtra("serverId") ?: return@launch
        val sessionId = intent.getStringExtra("sessionId") ?: return@launch
        val directory = intent.getStringExtra("directory") ?: return@launch
        val action = intent.action ?: return@launch
        val permissionId = intent.getStringExtra("permissionId")
        val permissionDirectory = intent.getStringExtra("permissionDirectory") ?: directory
        val succeeded = withTimeoutOrNull(ACTION_TIMEOUT_MILLIS) {
          runAction(appContext, serverId, sessionId, directory, action, permissionId, permissionDirectory, intent.getStringExtra("profileUrl"))
        } ?: false
        if (succeeded) TaskNotifications(appContext).cancel(serverId, sessionId)
        else Diagnostics.warn("NotificationAction", "操作未在时限内确认，保留通知以便重试：$action")
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (error: Exception) {
        Diagnostics.warn("NotificationAction", "通知操作失败", error)
      } finally {
        pending.finish()
      }
    }
  }

  private suspend fun runAction(
    context: Context,
    serverId: String,
    sessionId: String,
    directory: String,
    action: String,
    permissionId: String?,
    permissionDirectory: String,
    profileUrl: String?
  ): Boolean {
    val store = ServerStore(context)
    val profile = store.profiles().firstOrNull { it.id == serverId } ?: return false
    if (profileUrl != profile.url) return false
    val client = OpenCodeApi(profile, store.credentials(serverId), callTimeoutSeconds = 8)
    return try {
      when (action) {
        "abort" -> client.abort(Session(sessionId, directory, "", 0))
        "reject", "once" -> {
          val requestId = permissionId ?: error("缺少权限 ID")
          // Re-read the request from its session so a stale notification can never answer a different one.
          val pendingRequest = client.sessionPermissions(Session(sessionId, permissionDirectory, "", 0)).firstOrNull { it.id == requestId }
          if (pendingRequest != null) {
            try {
              client.replyPermission(pendingRequest, action)
            } catch (e: ApiException) {
              if (e.status != 404) throw e
            }
          }
        }
        else -> return false
      }
      LagoonController.get(context).notificationCompleted(serverId)
      true
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (error: Exception) {
      Diagnostics.warn("NotificationAction", "服务器未确认通知操作：$action", error)
      false
    }
  }
}
