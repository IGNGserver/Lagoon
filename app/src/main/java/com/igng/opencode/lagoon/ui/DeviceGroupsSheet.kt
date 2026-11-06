package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.LagoonState
import com.igng.opencode.lagoon.core.ServerKind
import com.igng.opencode.lagoon.core.endpointSummary
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Create or edit one manual device group. Devices (DSH Remote / SSH hosts) and harnesses
 * (OpenCode) are listed in separate sections but stored as ordinary profile ids.
 */
@Composable
internal fun DeviceGroupsSheet(
  state: LagoonState,
  controller: LagoonController,
  initialGroupId: String? = null,
  onDismiss: () -> Unit
) {
  val editingId = initialGroupId?.takeIf { it.isNotBlank() }.orEmpty()
  val editing = state.deviceGroups.firstOrNull { it.id == editingId }
  var name by rememberSaveable(initialGroupId) { mutableStateOf(editing?.name.orEmpty()) }
  var selected by remember(initialGroupId) { mutableStateOf(editing?.memberIds?.toSet().orEmpty()) }
  var connectAll by remember(initialGroupId) {
    mutableStateOf(
      editing?.memberIds?.mapNotNull { id -> state.profiles.firstOrNull { it.id == id && it.enabled } }?.let { members ->
        members.isNotEmpty() && members.all { it.autoConnect }
      } ?: true
    )
  }
  val opencodeMembers = state.profiles.filter { it.kind == ServerKind.OPENCODE }
  val dshMembers = state.profiles.filter { it.kind != ServerKind.OPENCODE }

  SuperBottomSheet(
    title = if (editing != null) "编辑设备组" else "新建设备组",
    show = true,
    onDismissRequest = onDismiss
  ) {
    LazyColumn(
      Modifier.fillMaxWidth().heightIn(max = 720.dp),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      item {
        Text("把同一台电脑上的不同连接放进一个组。首页左上角范围面板会列出「全部设备」和这些分组；分组只影响本机展示。已禁用的成员仍保留在组里，重新启用后自动恢复。",
          style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      }
      item { TextField(value = name, onValueChange = { name = it }, label = "分组名称", modifier = Modifier.fillMaxWidth()) }
      item {
        SuperSwitch(
          title = "同时连接全部成员",
          summary = "进入这个分组时保持每个成员的连接；关闭后按需连接",
          checked = connectAll,
          onCheckedChange = { connectAll = it }
        )
      }
      if (opencodeMembers.isNotEmpty()) {
        item { SmallTitle("OpenCode", insideMargin = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) }
        items(opencodeMembers, key = { "oc:${it.id}" }) { profile ->
          MemberRow(profile.name, profile.endpointSummary + if (profile.enabled) "" else " · 已禁用", profile.id in selected, profile.kind) {
            selected = if (profile.id in selected) selected - profile.id else selected + profile.id
          }
        }
      }
      if (dshMembers.isNotEmpty()) {
        item { SmallTitle("DeepSeek Harness", insideMargin = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) }
        items(dshMembers, key = { "dsh:${it.id}" }) { profile ->
          MemberRow(profile.name, profile.endpointSummary + if (profile.enabled) "" else " · 已禁用", profile.id in selected, profile.kind) {
            selected = if (profile.id in selected) selected - profile.id else selected + profile.id
          }
        }
      }
      item {
        TextButton(
          text = if (editing == null) "创建设备组" else "保存",
          enabled = name.isNotBlank() && selected.isNotEmpty(),
          onClick = {
            val ids = selected.toList()
            val id = if (editing == null) controller.createDevice(name, ids)
            else {
              controller.updateDevice(editing.id, name, ids)
              editing.id
            }
            controller.setDeviceAutoConnect(id, connectAll)
            onDismiss()
          },
          modifier = Modifier.fillMaxWidth()
        )
      }
      if (editing != null) item {
        TextButton(
          text = "删除设备组（只解除分组）",
          onClick = {
            controller.deleteDevice(editing.id)
            onDismiss()
          },
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.textButtonColors(textColor = MiuixColorTokens.Error)
        )
      }
    }
  }
}

@Composable
private fun MemberRow(
  name: String,
  subtitle: String,
  checked: Boolean,
  kind: ServerKind,
  onToggle: () -> Unit
) {
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onToggle)
      .padding(horizontal = 8.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Checkbox(checked = checked, onCheckedChange = null)
    Spacer(Modifier.width(8.dp))
    HarnessBadge(kind)
    Spacer(Modifier.width(8.dp))
    Column(Modifier.weight(1f)) {
      Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.body1)
      Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    }
  }
}
