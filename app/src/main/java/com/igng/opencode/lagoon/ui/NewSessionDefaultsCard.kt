package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.CancellationException
import top.yukonga.miuix.kmp.basic.HorizontalDivider

private data class PickerOption(val label: String, val summary: String? = null)

/**
 * The phone-local defaults for a blank "new session" page, rendered in a HyperOS 4 island card.
 * Scoped strictly to [serverId]: reading & saving defaults for any Harness never reads or mutates
 * another currently active Harness's catalog or preferences.
 */
@Composable
internal fun NewSessionDefaultsCard(state: LagoonState, controller: LagoonController, serverId: String? = state.serverId) {
  val targetId = serverId ?: state.serverId
  val targetProfile = targetId?.let { id -> state.profiles.firstOrNull { it.id == id } }
  val isCurrent = targetId != null && targetId == state.serverId
  val connected = if (isCurrent) state.connected else targetId != null && state.allServerSnapshots[targetId]?.connected == true

  var reloadNonce by remember(targetId) { mutableIntStateOf(0) }
  var localRevision by remember(targetId) { mutableIntStateOf(0) }
  var remoteAgents by remember(targetId) { mutableStateOf<List<AgentChoice>>(emptyList()) }
  var remoteCatalog by remember(targetId) { mutableStateOf<List<ModelInfo>>(emptyList()) }
  var remoteLoading by remember(targetId) { mutableStateOf(false) }
  var remoteError by remember(targetId) { mutableStateOf<String?>(null) }

  LaunchedEffect(targetId, isCurrent, connected, reloadNonce) {
    if (targetId == null || isCurrent || !connected) return@LaunchedEffect
    remoteLoading = true
    remoteError = null
    try {
      val (loadedAgents, loadedModels) = controller.loadCatalogForHarness(targetId, force = reloadNonce > 0)
      remoteAgents = loadedAgents
      remoteCatalog = loadedModels
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (error: Exception) {
      remoteError = userFacingError(error, "读取目录失败")
    } finally {
      remoteLoading = false
    }
  }

  val isDsh = targetProfile?.protocol == HarnessProtocol.DSH
  val agents = if (isCurrent) state.agents else remoteAgents
  val catalog = if (isCurrent) state.modelCatalog else remoteCatalog
  val (overrides, visibilityConfigured) = remember(targetId, isCurrent, state.modelOverrides, state.modelVisibilityConfigured, localRevision) {
    if (isCurrent) state.modelOverrides to state.modelVisibilityConfigured
    else targetId?.let(controller::modelVisibilityFor) ?: (emptyMap<String, Boolean>() to false)
  }
  val models = remember(catalog, overrides, visibilityConfigured) {
    ModelVisibility.visibleConfigured(catalog, overrides, visibilityConfigured)
  }
  val defaults = remember(targetId, isCurrent, state.newSessionAgent, state.newSessionModel, localRevision) {
    if (isCurrent) NewSessionDefaults(state.newSessionAgent, state.newSessionModel)
    else targetId?.let(controller::newSessionDefaultsFor) ?: NewSessionDefaults()
  }
  val variantsMap = remember(targetId, isCurrent, state.modelVariants, localRevision) {
    if (isCurrent) state.modelVariants
    else targetId?.let(controller::modelVariantsFor).orEmpty()
  }
  val defaultAgent = defaults.agent
  val defaultModel = defaults.model
  val defaultKey = defaultModel?.let(::modelKey)
  val defaultInfo = defaultKey?.let { key -> catalog.firstOrNull { it.key == key } }
  val catalogKnown = catalog.isNotEmpty()
  val agentKnown = agents.isNotEmpty()
  val staleAgent = defaultAgent != null && agentKnown && agents.none { it.name == defaultAgent }
  val staleModel = defaultModel != null && catalogKnown && defaultInfo == null
  val loadingCatalog = if (isCurrent) state.resource("models").state == ResourceState.LOADING else remoteLoading
  val errorCatalog = if (isCurrent) state.resource("models").state == ResourceState.ERROR else remoteError != null

  HyperCard(Modifier.fillMaxWidth(), insidePadding = PaddingValues(vertical = 4.dp)) {
    if (targetProfile == null || targetId == null) {
      HyperRow(
        title = "未选择 Harness",
        summary = "启用并连接一个 Harness 后，才能读取它支持的 Agent 预设与模型。"
      )
    } else {
      val agentOptions = buildList {
        add(PickerOption("跟随服务器", "由服务器决定"))
        agents.forEach { choice -> add(PickerOption(choice.name, choice.description.ifBlank { null })) }
        if (staleAgent) add(PickerOption("$defaultAgent（服务器未提供）", "新会话会回退到服务器默认"))
      }
      val agentIndex = when {
        defaultAgent == null -> 0
        else -> agents.indexOfFirst { it.name == defaultAgent }
          .let { if (it >= 0) it + 1 else agentOptions.lastIndex }
      }
      SettingsPickerRow(
        title = if (isDsh) "默认 Agent 预设" else "默认 Agent",
        summary = when {
          !connected -> "连接后读取此 Harness 提供的${if (isDsh) "预设" else " Agent"}"
          !agentKnown && loadingCatalog -> "正在读取 Agent 预设…"
          !agentKnown && errorCatalog -> "读取预设失败，可点下方重新读取"
          !agentKnown -> "服务器没有提供可用的${if (isDsh) "预设" else " Agent"}"
          staleAgent -> "服务器已不再提供这个预设；新会话会回退到服务器默认"
          else -> "新建会话时预先选中；只保存在这台手机"
        },
        options = agentOptions,
        selectedIndex = agentIndex,
        enabled = connected,
        onSelect = { index ->
          when {
            index <= 0 -> controller.setNewSessionAgent(null, targetId)
            index - 1 < agents.size -> controller.setNewSessionAgent(agents[index - 1].name, targetId)
          }
          localRevision += 1
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
      } else if (staleModel && defaultModel != null) {
        modelOptions += PickerOption("${defaultModel.label}（服务器未提供）", "新会话会回退到服务器默认")
        modelChoices += defaultModel
      }
      val modelIndex = modelChoices.indexOfFirst { choice ->
        if (choice == null) defaultModel == null
        else defaultModel?.let(::modelKey) == modelKey(choice)
      }.let { if (it >= 0) it else 0 }
      SettingsPickerRow(
        title = "默认模型",
        summary = when {
          !connected -> "连接后读取此 Harness 的模型目录"
          !catalogKnown && loadingCatalog -> "正在读取模型目录…"
          !catalogKnown && errorCatalog -> "读取模型目录失败，可点下方重新读取"
          !catalogKnown -> "服务器没有提供可用模型"
          staleModel -> "服务器已不再提供这个模型；新会话会回退到服务器默认"
          defaultModel == null -> "跟随服务器当前默认模型"
          else -> "新建会话时预先选中；只保存在这台手机"
        },
        options = modelOptions,
        selectedIndex = modelIndex,
        enabled = connected && modelChoices.size > 1,
        onSelect = { index ->
          controller.setNewSessionModel(modelChoices.getOrNull(index), targetId, catalog)
          localRevision += 1
        }
      )
      HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)

      val variants = defaultInfo?.variants.orEmpty()
      val strengthKey = defaultInfo?.key ?: defaultKey
      val strengthOptions = buildList {
        add(PickerOption("跟随模型", "由模型自身决定"))
        variants.forEach { add(PickerOption(it.label, null)) }
      }
      val currentVariant = strengthKey?.let { variantsMap[it] }
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
          if (strengthKey != null) {
            controller.setNewSessionVariant(strengthKey, variants.getOrNull(index - 1)?.id, targetId, catalog)
            localRevision += 1
          }
        }
      )
      HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = HyperTheme.colors.divider)

      HyperRow(
        title = "作用范围",
        summary = "只影响这台手机在 ${targetProfile.name} 上新建的会话；已有会话保持原设置。服务器不再提供的预设或模型会在发送前自动回退。"
      )
      if (connected && (!agentKnown || !catalogKnown)) {
        HyperButton(
          text = "重新读取模型目录",
          variant = HyperButtonVariant.SECONDARY,
          loading = loadingCatalog,
          onClick = {
            if (isCurrent) controller.loadDefaultCatalog(force = true, serverId = targetId)
            else reloadNonce += 1
          },
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
    summary = summary,
    value = selected,
    trailing = { DropdownChevron() },
    enabled = enabled && options.size > 1,
    onClick = { if (enabled && options.size > 1) open = true }
  )
  if (open) {
    var query by remember(open) { mutableStateOf("") }
    val filtered = options.withIndex().filter { indexed ->
      query.isBlank() || indexed.value.label.contains(query.trim(), true) || indexed.value.summary?.contains(query.trim(), true) == true
    }
    HyperBottomSheet(title = title, show = true, onDismissRequest = { open = false }) {
      LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 560.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
      ) {
        if (options.size > 8) item {
          top.yukonga.miuix.kmp.basic.TextField(
            value = query,
            onValueChange = { query = it },
            label = "搜索选项",
            useLabelAsPlaceholder = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
          )
        }
        itemsIndexed(filtered) { _, indexed ->
          val index = indexed.index
          val option = indexed.value
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
