package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.*
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperBottomSheet

private data class PickerOption(val label: String, val summary: String? = null)

/**
 * The phone-local defaults for a blank "new session" page, rendered in a HyperOS 4 island card.
 */
@Composable
internal fun NewSessionDefaultsCard(state: LagoonState, controller: LagoonController) {
  val server = state.server
  val connected = state.connected
  val isDsh = server?.kind == ServerKind.DSH || server?.kind == ServerKind.DSH_REMOTE
  val agents = state.agents
  val models = state.visibleModels
  val defaultKey = state.newSessionModel?.let(::modelKey)
  val defaultInfo = defaultKey?.let { key -> state.modelCatalog.firstOrNull { it.key == key } }
  val catalogKnown = state.modelCatalog.isNotEmpty()
  val agentKnown = agents.isNotEmpty()
  val staleAgent = state.newSessionAgent != null && agentKnown && agents.none { it.name == state.newSessionAgent }
  val staleModel = state.newSessionModel != null && catalogKnown && defaultInfo == null

  HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
    if (server == null) {
      HyperRow(
        title = "未连接设备",
        summary = "连接一台设备后，才能读取它支持的 Agent 预设与模型。"
      )
    } else {
      val agentOptions = buildList {
        add(PickerOption("跟随服务器", "由服务器决定"))
        agents.forEach { choice -> add(PickerOption(choice.name, choice.description.ifBlank { null })) }
        if (staleAgent) add(PickerOption("${state.newSessionAgent}（服务器未提供）", "新会话会回退到服务器默认"))
      }
      val agentIndex = when {
        state.newSessionAgent == null -> 0
        else -> agents.indexOfFirst { it.name == state.newSessionAgent }
          .let { if (it >= 0) it + 1 else agentOptions.lastIndex }
      }
      SettingsPickerRow(
        title = if (isDsh) "默认 Agent 预设" else "默认 Agent",
        summary = when {
          !connected -> "连接后读取服务器提供的${if (isDsh) "预设" else " Agent"}"
          !agentKnown && state.resource("models").state == ResourceState.LOADING -> "正在读取 Agent 预设…"
          !agentKnown -> "服务器没有提供可用的${if (isDsh) "预设" else " Agent"}"
          staleAgent -> "服务器已不再提供这个预设；新会话会回退到服务器默认"
          else -> "新建会话时预先选中；只保存在这台手机"
        },
        options = agentOptions,
        selectedIndex = agentIndex,
        enabled = connected,
        onSelect = { index ->
          when {
            index <= 0 -> controller.setNewSessionAgent(null)
            index - 1 < agents.size -> controller.setNewSessionAgent(agents[index - 1].name)
          }
        }
      )
      HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)

      val modelOptions = mutableListOf(PickerOption("跟随服务器", "由服务器决定"))
      val modelChoices = mutableListOf<ModelChoice?>(null)
      models.forEach { info ->
        modelOptions += PickerOption(info.choice.label, info.providerName)
        modelChoices += info.choice
      }
      if (defaultInfo != null && models.none { it.key == defaultInfo.key }) {
        modelOptions += PickerOption("${defaultInfo.choice.label}（不在显示范围）", "服务器仍提供，但被模型显示过滤隐藏")
        modelChoices += defaultInfo.choice
      } else if (staleModel) {
        modelOptions += PickerOption("${state.newSessionModel!!.label}（服务器未提供）", "新会话会回退到服务器默认")
        modelChoices += state.newSessionModel
      }
      val modelIndex = modelChoices.indexOfFirst { choice ->
        if (choice == null) state.newSessionModel == null
        else state.newSessionModel?.let(::modelKey) == modelKey(choice)
      }.let { if (it >= 0) it else 0 }
      SettingsPickerRow(
        title = "默认模型",
        summary = when {
          !connected -> "连接后读取服务器模型目录"
          !catalogKnown && state.resource("models").state == ResourceState.LOADING -> "正在读取模型目录…"
          !catalogKnown -> "服务器没有提供可用模型"
          staleModel -> "服务器已不再提供这个模型；新会话会回退到服务器默认"
          state.newSessionModel == null -> "跟随服务器当前默认模型"
          else -> "新建会话时预先选中；只保存在这台手机"
        },
        options = modelOptions,
        selectedIndex = modelIndex,
        enabled = connected && modelChoices.size > 1,
        onSelect = { index -> controller.setNewSessionModel(modelChoices.getOrNull(index)) }
      )
      HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)

      val variants = defaultInfo?.variants.orEmpty()
      val strengthKey = defaultInfo?.key ?: defaultKey
      val strengthOptions = buildList {
        add(PickerOption("跟随模型", "由模型自身决定"))
        variants.forEach { add(PickerOption(it.label, null)) }
      }
      val currentVariant = strengthKey?.let { state.modelVariants[it] }
      val strengthIndex = if (currentVariant == null) 0 else
        variants.indexOfFirst { it.id == currentVariant }.let { if (it >= 0) it + 1 else 0 }
      SettingsPickerRow(
        title = "默认思考强度",
        summary = when {
          strengthKey == null -> "先选择默认模型后，才能设置该模型的思考强度"
          variants.isEmpty() -> "这个模型没有可调的思考强度"
          else -> "按模型保存；新会话与在会话内选择共用同一个值"
        },
        options = strengthOptions,
        selectedIndex = strengthIndex,
        enabled = connected && strengthKey != null && variants.isNotEmpty(),
        onSelect = { index ->
          if (strengthKey != null) controller.setNewSessionVariant(strengthKey, variants.getOrNull(index - 1)?.id)
        }
      )
      HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)

      HyperRow(
        title = "作用范围",
        summary = "只影响这台手机新建的会话；已有会话保持原设置。服务器不再提供的预设或模型会在发送前自动回退。"
      )
      if (connected && (!agentKnown || !catalogKnown)) {
        TextButton(
          text = if (state.resource("models").state == ResourceState.LOADING) "正在读取…" else "重新读取模型目录",
          enabled = state.resource("models").state != ResourceState.LOADING,
          onClick = { controller.loadDefaultCatalog(force = true) },
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        )
      }
    }
  }
}

/** One settings row plus a scrollable single-choice sheet; works for arbitrarily long catalogs. */
@Composable
private fun SettingsPickerRow(
  title: String,
  summary: String,
  options: List<PickerOption>,
  selectedIndex: Int,
  enabled: Boolean,
  onSelect: (Int) -> Unit
) {
  var open by remember { mutableStateOf(false) }
  val selected = options.getOrNull(selectedIndex)?.label ?: options.firstOrNull()?.label.orEmpty()
  HyperRow(
    title = title,
    summary = "$summary · 当前：$selected",
    value = selected,
    showChevron = true,
    enabled = enabled && options.size > 1,
    onClick = { if (enabled && options.size > 1) open = true }
  )
  if (open) {
    SuperBottomSheet(title = title, show = true, onDismissRequest = { open = false }) {
      LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 560.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
      ) {
        itemsIndexed(options) { index, option ->
          ChoiceRow(
            title = option.label,
            summary = listOfNotNull(option.summary, if (index == selectedIndex) "当前选择" else null)
              .joinToString(" · ").ifBlank { null },
            selected = index == selectedIndex,
            onClick = { onSelect(index); open = false }
          )
        }
      }
    }
  }
}
