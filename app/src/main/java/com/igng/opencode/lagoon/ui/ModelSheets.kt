package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ContactsCircle
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The composer's Agent / Model / thinking-strength selectors. Each is a compact anchored dropdown
 * (capsule + MIUIX popup), never a half-screen sheet, and none of them expose model management.
 */

/** The only agent choices the phone offers: build and plan, with no descriptions. */
private val AGENT_OPTIONS = listOf("build", "plan")

/** One row of a compact dropdown; a null [value] means "follow the session / default". */
internal data class DropdownOption(val label: String, val value: String?, val selected: Boolean)

/** Capsule plus an anchored MIUIX popup. */
@Composable
private fun Dropdown(
  text: String,
  options: List<DropdownOption>,
  onSelect: (String?) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  leading: (@Composable () -> Unit)? = null,
  maxTextWidth: Dp = 200.dp
) {
  var open by remember { mutableStateOf(false) }
  Box(modifier) {
    CapsuleSelector(text, { open = true }, enabled = enabled, leading = leading, maxTextWidth = maxTextWidth)
    MenuPopup(open, { open = false }, listOf(MenuSection(options.map { option ->
      MenuAction(option.label, selected = option.selected) { onSelect(option.value) }
    })), alignment = PopupPositionProvider.Align.Start)
  }
}

/** Agent selector: 默认 / build / plan. [iconOnly] is the collapsed pill's glyph button. */
@Composable
internal fun AgentSelector(
  state: LagoonState,
  controller: LagoonController,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  iconOnly: Boolean = false
) {
  var open by remember { mutableStateOf(false) }
  // 服务器自报的 agent 优先（自定义 agent 的实例不再只能选 build/plan）；接口不可用时退回内置两项。
  val choices = state.agents.ifEmpty { AGENT_OPTIONS.map { AgentChoice(it, "") } }
  val options = choices.map { DropdownOption(it.name, it.name, state.agentChanged && state.agent == it.name) }
  Box(modifier) {
    if (iconOnly) IconButton(onClick = { open = true }, enabled = enabled) {
      Icon(MiuixIcons.ContactsCircle, "Agent：${state.agent ?: "默认"}", Modifier.size(22.dp),
        tint = if (state.agentChanged) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
    } else CapsuleSelector(state.agent ?: "默认", { open = true }, enabled = enabled, maxTextWidth = 72.dp,
      leading = { Icon(MiuixIcons.ContactsCircle, null, Modifier.size(16.dp), tint = if (state.agentChanged) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary) })
    MenuPopup(open, { open = false }, listOf(MenuSection(options.map { option ->
      MenuAction(option.label, selected = option.selected) { controller.chooseAgent(option.value) }
    })), alignment = PopupPositionProvider.Align.Start)
  }
}

/** Model selector: the session default or one of the server's visible models. */
@Composable
internal fun ModelSelector(state: LagoonState, controller: LagoonController, modifier: Modifier = Modifier, enabled: Boolean = true) {
  var open by remember { mutableStateOf(false) }
  val visible = state.visibleModels
  val currentKey = state.model?.let(::modelKey)
  val options = buildList {
    add(DropdownOption("默认模型", null, !state.modelChanged))
    visible.forEach { model -> add(DropdownOption(model.choice.label, model.key, state.modelChanged && model.key == currentKey)) }
  }
  Box(modifier) {
    CapsuleSelector(state.model?.label ?: "默认模型", { open = true }, enabled = enabled, maxTextWidth = 180.dp)
    MenuPopup(open, { open = false }, listOf(MenuSection(options.map { option ->
      MenuAction(option.label, selected = option.selected) {
        controller.chooseModel(option.value?.let { key -> visible.firstOrNull { it.key == key }?.choice })
      }
    })), alignment = PopupPositionProvider.Align.Start)
  }
}

/** Thinking-strength selector, backed only by the variants the server lists for the chosen model. */
@Composable
internal fun VariantSelector(state: LagoonState, controller: LagoonController, modifier: Modifier = Modifier, enabled: Boolean = true) {
  var open by remember { mutableStateOf(false) }
  val variants = state.modelCatalog.firstOrNull { it.key == state.model?.let(::modelKey) }?.variants.orEmpty()
  val current = state.model?.variant
  val label = variants.firstOrNull { it.id == current }?.label ?: "默认"
  Box(modifier) {
    CapsuleSelector(label, { open = true }, enabled = enabled && variants.isNotEmpty(), maxTextWidth = 84.dp)
    if (variants.isNotEmpty()) MenuPopup(open, { open = false }, listOf(MenuSection(buildList {
      add(MenuAction("默认", selected = current == null) { controller.chooseVariant(null) })
      variants.forEach { variant -> add(MenuAction(variant.label, selected = current == variant.id) { controller.chooseVariant(variant.id) }) }
    })), alignment = PopupPositionProvider.Align.Start)
  }
}

/**
 * Per-server model allow-list.  The first visit is intentionally all-visible; changing one switch
 * materializes that complete list in [LagoonController], after which only checked rows are offered by
 * [ModelSelector].  The reset action removes the local filter and never changes server configuration.
 */
@Composable
internal fun ModelManagementSheet(state: LagoonState, controller: LagoonController, onDismiss: () -> Unit) {
  val catalog = state.modelCatalog
  val selectedCount = catalog.count { !state.modelVisibilityConfigured || state.modelOverrides[it.key] == true }
  SuperBottomSheet(
    title = "管理模型${state.server?.name?.let { " · $it" }.orEmpty()}",
    show = true,
    onDismissRequest = onDismiss
  ) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 620.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
      item {
        ResourceHint(state.resource("models"), "服务器没有可用模型", "模型列表和思考强度均来自服务器。", controller::reload)
      }
      if (catalog.isNotEmpty()) {
        item {
          Text(
            if (state.modelVisibilityConfigured) "已选择 $selectedCount/${catalog.size} 个模型"
            else "尚未配置过滤，当前显示服务器返回的全部 ${catalog.size} 个模型",
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
          )
        }
        item {
          Card(Modifier.fillMaxWidth()) {
            catalog.forEach { info ->
              val checked = !state.modelVisibilityConfigured || state.modelOverrides[info.key] == true
              SuperSwitch(
                title = info.choice.label,
                summary = buildString {
                  append(info.providerName)
                  if (info.variants.isNotEmpty()) append(" · ${info.variants.size} 个服务器变体")
                  append("\n${info.key}")
                },
                checked = checked,
                onCheckedChange = { controller.setModelVisible(info, it) }
              )
            }
          }
        }
        if (state.modelVisibilityConfigured) item {
          TextButton(
            text = "恢复显示全部模型",
            onClick = controller::resetModelVisibility,
            modifier = Modifier.fillMaxWidth()
          )
        }
      }
    }
  }
}
