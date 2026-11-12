package com.igng.opencode.lagoon.ui

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.ConnectionGraph
import com.igng.opencode.lagoon.core.DeviceView
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.LagoonState
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 创建或编辑一个设备组。**成员按设备选**：一台设备加进来，它上面的 Harness 就都属于这个组，
 * 之后新加的 Harness 也会自动加入。分组只影响本机展示与首页范围，不上传、不改认证或路由。
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
  val graph by controller.connectionGraph.collectAsState()
  val devices = remember(graph, state.profiles, state.discoveryRevision) {
    controller.deviceViews(graph, state.profiles)
  }
  var name by rememberSaveable(initialGroupId) { mutableStateOf(editing?.name.orEmpty()) }
  // 旧分组只有 leaf id：用"成员里有这台设备的任一 Harness"回填勾选，保存后升级为按设备。
  var selected by remember(initialGroupId, devices) {
    mutableStateOf(
      editing?.deviceIds?.takeIf { it.isNotEmpty() }?.toSet()
        ?: editing?.memberIds?.let { members ->
          devices.filter { device -> device.harnesses.any { it.leafId?.value in members } }.map { it.id.value }.toSet()
        }.orEmpty()
    )
  }
  var confirmingDelete by remember { mutableStateOf(false) }

  HyperBottomSheet(
    title = if (editing != null) "编辑设备组" else "新建设备组",
    show = true,
    onDismissRequest = onDismiss
  ) {
    LazyColumn(
      Modifier.fillMaxWidth().heightIn(max = 720.dp),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      item {
        Text(
          "把需要一起使用的设备放进一个组。首页范围面板会列出这些分组；分组只影响本机展示，已停用的设备仍留在组里，重新启用后自动恢复。",
          style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
        )
      }
      item { TextField(value = name, onValueChange = { name = it }, label = "分组名称", modifier = Modifier.fillMaxWidth()) }
      item { HyperGroupTitle("设备") }
      if (devices.isEmpty()) item {
        Text("还没有设备。先添加一台设备，再把它放进分组。",
          style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary))
      }
      items(devices, key = { "device:${it.id.value}" }) { device ->
        DeviceMemberRow(device, device.id.value in selected) {
          selected = if (device.id.value in selected) selected - device.id.value else selected + device.id.value
        }
      }
      item {
        HyperButton(
          text = if (editing == null) "创建设备组" else "保存",
          enabled = name.isNotBlank() && selected.isNotEmpty(),
          onClick = {
            val ids = selected.toList()
            if (editing == null) controller.createDeviceGroup(name, ids)
            else controller.updateDeviceGroup(editing.id, name, ids)
            onDismiss()
          },
          modifier = Modifier.fillMaxWidth()
        )
      }
      if (editing != null) item {
        HyperButton(
          text = "删除设备组",
          variant = HyperButtonVariant.DESTRUCTIVE,
          onClick = { confirmingDelete = true },
          modifier = Modifier.fillMaxWidth()
        )
      }
    }
  }

  if (confirmingDelete && editing != null) {
    top.yukonga.miuix.kmp.extra.SuperDialog(
      title = "删除设备组“${editing.name}”？",
      show = true,
      onDismissRequest = { confirmingDelete = false }
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("只会解除分组，不会删除设备、工作区或会话。", style = MiuixTheme.textStyles.body1)
        DialogActions("取消", { confirmingDelete = false }, "确认删除", danger = true) {
          controller.deleteDeviceGroup(editing.id)
          confirmingDelete = false
          onDismiss()
        }
      }
    }
  }
}

@Composable
private fun DeviceMemberRow(device: DeviceView, checked: Boolean, onToggle: () -> Unit) {
  HyperCard(
    Modifier.fillMaxWidth(),
    insidePadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    borderColor = if (checked) HyperTheme.colors.primary.copy(alpha = 0.4f) else HyperTheme.colors.cardBorder,
    onClick = onToggle
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Checkbox(checked = checked, onCheckedChange = null)
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
          style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium))
        Text(
          "${device.access.label} · ${device.attachedCount} 个 Harness" + if (device.effectiveEnabled) "" else " · 已停用",
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          style = MiuixTheme.textStyles.footnote2.copy(color = HyperTheme.colors.textSecondary)
        )
      }
    }
  }
}
