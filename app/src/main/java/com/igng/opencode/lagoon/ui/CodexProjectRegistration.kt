package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.LagoonState
import com.igng.opencode.lagoon.core.ServerKind
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** A Remote Codex Host requires an explicit project root before it can authorize new threads. */
@Composable
internal fun CodexProjectRegistrationRow(state: LagoonState, controller: LagoonController) {
  val server = state.server ?: return
  if (!state.connected || server.kind != ServerKind.CODEX || server.remoteHostId == null) return
  var visible by remember(server.id) { mutableStateOf(false) }
  var path by remember(server.id) { mutableStateOf("") }
  ChoiceRow("登记 Codex 项目目录…", "在 Host 上登记已有目录", false) { visible = true }
  if (visible) SuperDialog(title = "登记 Codex 项目", show = true, onDismissRequest = { visible = false }) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
      Text("填写 Host 上已存在的绝对目录。登记后该目录会成为 Codex 可访问的项目范围，不会创建目录。",
        style = MiuixTheme.textStyles.footnote2)
      TextField(value = path, onValueChange = { path = it }, label = "Host 绝对目录", modifier = Modifier.fillMaxWidth())
      DialogActions("取消", { visible = false }, "登记", enabled = path.isNotBlank()) {
        controller.createCodexProject(path.trim(), server)
        visible = false
      }
    }
  }
}
