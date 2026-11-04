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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.DeviceGroup
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.LagoonState
import com.igng.opencode.lagoon.core.ServerKind
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Manual device grouping. A group is only a local presentation choice; saving never connects,
 * disconnects, renames or deletes a server.
 */
@Composable
internal fun DeviceGroupsSheet(
  state: LagoonState,
  controller: LagoonController,
  initialGroupId: String? = null,
  onDismiss: () -> Unit
) {
  var editingId by rememberSaveable(initialGroupId) { mutableStateOf(initialGroupId) }
  var name by rememberSaveable(initialGroupId) {
    mutableStateOf(initialGroupId?.let { id -> state.deviceGroups.firstOrNull { it.id == id }?.name }.orEmpty())
  }
  var selected by remember(initialGroupId) {
    mutableStateOf(initialGroupId?.let { id -> state.deviceGroups.firstOrNull { it.id == id }?.memberIds?.toSet() }.orEmpty())
  }
  val editing = editingId?.let { id -> state.deviceGroups.firstOrNull { it.id == id } }

  SuperBottomSheet(title = if (editing != null) "编辑设备" else "设备分组", show = true, onDismissRequest = onDismiss) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 680.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)) {
      if (editingId == null) {
        item {
          Text("把同一台电脑上的 OpenCode / DSH 服务器归到一个设备。顶栏会只显示设备，设备内会话用徽标区分 harness。",
            style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        }
        item {
          Card(Modifier.fillMaxWidth()) {
            TextButton(text = "新建设备", onClick = { editingId = ""; name = ""; selected = emptySet() }, modifier = Modifier.fillMaxWidth())
          }
        }
        if (state.deviceGroups.isNotEmpty()) {
          item { SmallTitle("设备", insideMargin = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) }
          items(state.deviceGroups, key = { it.id }) { group ->
            DeviceRow(group, state) { editingId = group.id; name = group.name; selected = group.memberIds.toSet() }
          }
        }
        val grouped = state.deviceGroups.flatMap { it.memberIds }.toSet()
        val ungrouped = state.profiles.filter { it.id !in grouped }
        if (ungrouped.isNotEmpty()) {
          item { SmallTitle("未分组", insideMargin = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) }
          item {
            Card(Modifier.fillMaxWidth()) {
              ungrouped.forEach { profile ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                  HarnessBadge(profile.kind)
                  Spacer(Modifier.width(8.dp))
                  Text(profile.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MiuixTheme.textStyles.body1)
                }
              }
            }
          }
        }
      } else {
        item {
          TextField(value = name, onValueChange = { name = it }, label = "设备名称", modifier = Modifier.fillMaxWidth())
        }
        if (editing != null) {
          val members = editing.memberIds.mapNotNull { id -> state.profiles.firstOrNull { it.id == id } }
          item {
            SuperSwitch(
              title = "同时连接全部成员",
              summary = "每个 harness 仍独立连接；关闭后仅当前成员按需连接",
              checked = members.isNotEmpty() && members.all { it.autoConnect },
              onCheckedChange = { enabled -> controller.setDeviceAutoConnect(editing.id, enabled) }
            )
          }
        }
        item {
          Text("选择归入这个设备的服务器。一个服务器只能属于一个设备；保存时会从其他设备移出。",
            style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        }
        item { SmallTitle("服务器", insideMargin = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) }
        items(state.profiles, key = { it.id }) { profile ->
          val checked = profile.id in selected
          Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable {
            selected = if (checked) selected - profile.id else selected + profile.id
          }.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { value ->
              selected = if (value) selected + profile.id else selected - profile.id
            })
            Spacer(Modifier.width(8.dp))
            HarnessBadge(profile.kind)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
              Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.body1)
              Text(profileKind(profile.kind), style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
            }
          }
        }
        item {
          TextButton(text = "保存", enabled = name.isNotBlank() && selected.isNotEmpty(), onClick = {
            val ids = selected.toList()
            if (editing == null) controller.createDevice(name, ids) else controller.updateDevice(editing.id, name, ids)
            onDismiss()
          }, modifier = Modifier.fillMaxWidth())
        }
        if (editing != null) item {
          TextButton(text = "删除设备（只解除分组）", onClick = {
            controller.deleteDevice(editing.id)
            onDismiss()
          }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(textColor = MiuixColorTokens.Error))
        }
        item { TextButton(text = "返回设备列表", onClick = { editingId = null }, modifier = Modifier.fillMaxWidth()) }
      }
    }
  }
}

@Composable
private fun DeviceRow(group: DeviceGroup, state: LagoonState, onClick: () -> Unit) {
  val members = group.memberIds.mapNotNull { id -> state.profiles.firstOrNull { it.id == id } }
  val online = members.count { profile ->
    if (profile.id == state.serverId) state.connected else state.allServerSnapshots[profile.id]?.connected == true
  }
  Card(Modifier.fillMaxWidth().clickable(onClick = onClick), insideMargin = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
          style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium))
        Text("${members.size} 个 harness · $online 在线", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      }
      Row { members.take(4).forEach { HarnessBadge(it.kind) } }
    }
  }
}

private fun profileKind(kind: ServerKind): String = kind.label
