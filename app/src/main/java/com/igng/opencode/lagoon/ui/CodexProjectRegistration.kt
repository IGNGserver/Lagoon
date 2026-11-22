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
import com.igng.opencode.lagoon.core.HarnessProtocol
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.LagoonState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** ACP harnesses (Antigravity / OpenCode ACP) can open a session in any existing remote directory. */
@Composable
internal fun AgyProjectRegistrationRow(state: LagoonState, controller: LagoonController) {
  val server = state.server ?: return
  if (!state.connected || !server.protocol.isAcp) return
  val title = if (server.protocol == HarnessProtocol.OPENCODE_ACP) "添加 OpenCode ACP 工作目录" else "添加 Antigravity 工作目录"
  var visible by remember(server.id) { mutableStateOf(false) }
  var path by remember(server.id) { mutableStateOf("") }
  ChoiceRow("$title…", "选择开发机上已存在的目录", false) { visible = true }
  if (visible) SuperDialog(title = title, show = true, onDismissRequest = { visible = false }) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
      Text("填写开发机上的绝对目录。Lagoon 会验证目录存在，并将它保存到本机。", style = MiuixTheme.textStyles.footnote2)
      TextField(value = path, onValueChange = { path = it }, label = "开发机绝对目录", modifier = Modifier.fillMaxWidth())
      DialogActions("取消", { visible = false }, "添加", enabled = path.isNotBlank()) {
        controller.addProject(path.trim())
        visible = false
      }
    }
  }
}
