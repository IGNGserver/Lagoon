package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.LagoonState

/**
 * SSH 设备整页编辑器。新建与编辑共用这一页，由根 `AnimatedContent` 按 [DeviceEditorRoute] 驱动。
 */
@Composable
internal fun DeviceEditorScreen(
  state: LagoonState,
  controller: LagoonController,
  route: DeviceEditorRoute,
  onBack: () -> Unit,
  onSshDeviceSaved: (String, Boolean) -> Unit
) {
  val initial = route.deviceId?.takeIf(String::isNotBlank)?.let { id ->
    state.devices.firstOrNull { it.id == id }
  }
  val creating = route.isNew
  Column(Modifier.fillMaxSize().background(HyperTheme.colors.canvas)) {
    PageTopBar(title = if (creating) "添加 SSH 设备" else "编辑 SSH 设备", onBack = onBack)
    Box(Modifier.weight(1f).fillMaxWidth()) {
      SshHostForm(
        initial = initial,
        state = state,
        controller = controller,
        onCancel = onBack,
        onSaved = onSshDeviceSaved
      )
    }
  }
}
