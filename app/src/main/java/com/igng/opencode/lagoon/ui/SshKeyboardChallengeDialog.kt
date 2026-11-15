package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.ssh.SshKeyboardChallenge
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Challenge answers are intentionally not saveable: a one-time code is never persisted. */
@Composable
internal fun SshKeyboardChallengeDialog(
  challenge: SshKeyboardChallenge,
  onCancel: () -> Unit,
  onSubmit: (List<String>) -> Unit
) {
  val answers = remember(challenge.id) {
    mutableStateListOf<String>().apply { repeat(challenge.prompts.size) { add("") } }
  }
  SuperDialog(title = "完成 SSH 验证", show = true, onDismissRequest = onCancel) {
    Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("电脑 ${challenge.destination} 需要额外验证", style = MiuixTheme.textStyles.body2)
      if (challenge.name.isNotBlank()) Text(challenge.name, style = MiuixTheme.textStyles.footnote1)
      if (challenge.instruction.isNotBlank()) Text(challenge.instruction, style = MiuixTheme.textStyles.footnote2)
      challenge.prompts.forEachIndexed { index, prompt ->
        TextField(
          value = answers[index],
          onValueChange = { if (it.length <= 4096) answers[index] = it },
          label = prompt.text,
          keyboardOptions = KeyboardOptions(keyboardType = if (prompt.echo) KeyboardType.Text else KeyboardType.Password),
          visualTransformation = if (prompt.echo) VisualTransformation.None else PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth()
        )
      }
      Text("这些回答只用于本次连接，不会保存。", style = MiuixTheme.textStyles.footnote2)
      DialogActions("取消", onCancel, "继续") { onSubmit(answers.toList()) }
    }
  }
}
