package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.HubAccessKey
import com.igng.opencode.lagoon.core.HubImportLink
import com.igng.opencode.lagoon.core.HubImportResult
import com.igng.opencode.lagoon.core.HubImportTarget
import com.igng.opencode.lagoon.core.ImportAction
import com.igng.opencode.lagoon.core.ImportItem
import com.igng.opencode.lagoon.core.ImportPlan
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.core.userFacingError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 从中枢导入：填中枢地址与接入密钥（或粘贴网页给出的导入链接）→ 预览与本机的差异并勾选 → 导入。
 * 再次打开时沿用上次的中枢，等同「从中枢同步」。
 */
@Composable
fun HubImportModal(controller: LagoonController, onDismiss: () -> Unit) {
  val clipboard = LocalClipboardManager.current
  val scope = rememberCoroutineScope()
  val remembered = remember { controller.hubTarget() }
  var address by remember { mutableStateOf(remembered?.hubUrl.orEmpty()) }
  var key by remember { mutableStateOf(remembered?.key?.encoded.orEmpty()) }
  var target by remember { mutableStateOf<HubImportTarget?>(null) }
  var plan by remember { mutableStateOf<ImportPlan?>(null) }
  var selected by remember { mutableStateOf(emptySet<Int>()) }
  var result by remember { mutableStateOf<HubImportResult?>(null) }
  var error by remember { mutableStateOf("") }
  var working by remember { mutableStateOf(false) }

  /** 粘贴整条导入链接时拆成地址与密钥。 */
  fun acceptLink(text: String): Boolean {
    val link = HubImportLink.parse(text) ?: return false
    address = link.hubUrl
    key = link.key.encoded
    return true
  }

  LaunchedEffect(Unit) {
    if (remembered == null) acceptLink(clipboard.getText()?.text.orEmpty())
  }

  fun read() {
    val hub = HubImportLink.hubAddress(address)
    val parsed = HubAccessKey.parse(key)
    error = when {
      hub == null || !(hub.startsWith("http://", true) || hub.startsWith("https://", true)) -> "请填写以 http:// 或 https:// 开头的中枢地址"
      parsed == null -> "接入密钥格式不对：应以 lgk1. 开头，可在中枢网页「设置 → 客户端接入」生成"
      else -> ""
    }
    if (error.isNotEmpty() || hub == null || parsed == null) return
    val next = HubImportTarget(hub, parsed)
    working = true
    scope.launch {
      try {
        val read = controller.previewHubImport(next)
        target = next
        plan = read
        selected = read.items.indices.filter { read.items[it].selectedByDefault }.toSet()
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (failure: Exception) {
        error = userFacingError(failure, "读取中枢配置失败")
      } finally {
        working = false
      }
    }
  }

  fun apply() {
    val current = plan ?: return
    val hub = target ?: return
    working = true
    error = ""
    scope.launch {
      try {
        result = controller.applyHubImport(hub, current, selected)
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (failure: Exception) {
        error = userFacingError(failure, "导入失败")
      } finally {
        working = false
      }
    }
  }

  SuperBottomSheet(
    title = when {
      result != null -> "导入完成"
      plan != null -> "确认导入"
      else -> "从中枢导入"
    },
    show = true,
    onDismissRequest = { if (!working) onDismiss() }
  ) {
    Box(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
      LazyColumn(
        modifier = Modifier.fillMaxSize().overScrollVertical(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        val done = result
        val preview = plan
        when {
          done != null -> {
            item { Text(summaryOf(done), style = MiuixTheme.textStyles.body1) }
            if (done.unreachable.isNotEmpty()) item {
              Hint("手机暂时连不上：${done.unreachable.joinToString("、")}。已照常保存，网络可达后会自动连接；若地址只在中枢那边能用，请在服务器设置里改地址。",
                MiuixColorTokens.Warning)
            }
            item { DialogActions("关闭", onDismiss, "完成", onConfirm = onDismiss) }
          }
          preview != null -> {
            val actionable = preview.items.any { it.selectable }
            item {
              Hint(if (actionable) "勾选要导入的服务器。已有的服务器按地址或导入来源合并，不会重复添加；本机的「自动连接」「通知」设置保持不变。"
                else "本机的服务器已与中枢一致，没有需要导入的内容。", MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            itemsIndexed(preview.items) { index, line ->
              ImportLine(line, checked = index in selected, enabled = line.selectable && !working) { on ->
                selected = if (on) selected + index else selected - index
              }
            }
            if (error.isNotBlank()) item { Text(error, color = MiuixColorTokens.Error, style = MiuixTheme.textStyles.body2) }
            item {
              DialogActions("返回", { plan = null; error = "" }, if (working) "正在导入…" else "导入所选（${selected.size}）",
                enabled = !working && selected.isNotEmpty(), onConfirm = ::apply)
            }
          }
          else -> {
            item {
              Hint("在中枢网页「设置 → 客户端接入」新建接入密钥，把导入链接粘贴到下面（地址和密钥会自动拆开）。导入会带上各服务器的凭据，内容经密钥加密传输。",
                MiuixTheme.colorScheme.primary)
            }
            item {
              TextField(value = address, onValueChange = { text: String -> if (!acceptLink(text)) address = text },
                label = if (address.isBlank()) "中枢地址或导入链接" else "中枢地址", useLabelAsPlaceholder = address.isBlank(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            }
            item {
              TextField(value = key, onValueChange = { text: String -> if (!acceptLink(text)) key = text.trim() },
                label = "接入密钥（lgk1. 开头）", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
            if (error.isNotBlank()) item { Text(error, color = MiuixColorTokens.Error, style = MiuixTheme.textStyles.body2) }
            item {
              DialogActions("取消", onDismiss, if (working) "正在读取…" else "读取中枢配置",
                enabled = !working && address.isNotBlank() && key.isNotBlank(), onConfirm = ::read)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ImportLine(line: ImportItem, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp)) {
    Row(Modifier.fillMaxWidth().let { if (enabled) it.clickable { onChange(!checked) } else it }, verticalAlignment = Alignment.CenterVertically) {
      Checkbox(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
      Spacer(Modifier.width(10.dp))
      Column(Modifier.weight(1f)) {
        Text("${line.name} · ${line.action.label}", style = MiuixTheme.textStyles.body2)
        val url = line.incoming?.profile?.url ?: line.local?.url
        val detail = listOfNotNull(
          url,
          line.incoming?.profile?.kind?.label,
          line.changes.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "将更新：")
        ).joinToString(" · ")
        Text(detail, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        line.note?.let {
          val color = if (line.action == ImportAction.CONFLICT || line.incoming?.localOnly == true) MiuixColorTokens.Warning
            else MiuixTheme.colorScheme.onSurfaceVariantSummary
          Text(it, style = MiuixTheme.textStyles.footnote2.copy(color = color), modifier = Modifier.padding(top = 2.dp))
        }
      }
    }
  }
}

@Composable
private fun Hint(text: String, color: androidx.compose.ui.graphics.Color) {
  Text(text, style = MiuixTheme.textStyles.footnote1.copy(color = color))
}

private fun summaryOf(result: HubImportResult): String {
  val parts = listOfNotNull("新增 ${result.added} 台".takeIf { result.added > 0 }, "更新 ${result.updated} 台".takeIf { result.updated > 0 },
    "移除 ${result.removed} 台".takeIf { result.removed > 0 })
  return if (parts.isEmpty()) "没有改动。" else "已${parts.joinToString("，")}。之后在设置里点「从中枢导入」即可再次同步。"
}
