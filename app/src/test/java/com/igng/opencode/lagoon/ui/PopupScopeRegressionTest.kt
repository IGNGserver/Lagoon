package com.igng.opencode.lagoon.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MIUIX 弹层作用域与密码键盘的源码级结构回归测试。
 *
 * SuperDialog/SuperBottomSheet 只注册进 Miuix Scaffold 内部 provide 的弹层列表，
 * 并只由 Scaffold 自带的 MiuixPopupHost 渲染。MainActivity 一旦把整页编辑器或半屏抽屉的
 * 声明点挪出 Scaffold 内容 lambda，弹层会静默落进无人渲染的默认列表。
 */
class PopupScopeRegressionTest {
  private fun uiSource(name: String): String {
    val candidates = listOf(
      File("app/src/main/java/com/igng/opencode/lagoon/ui/$name"),
      File("src/main/java/com/igng/opencode/lagoon/ui/$name")
    )
    return candidates.firstOrNull { it.isFile }?.readText()
      ?: error("找不到 UI 源文件：$name（工作目录 ${File(".").absolutePath}）")
  }

  private fun matchDelimited(text: String, open: Int): Int {
    val openChar = text[open]
    val closeChar = if (openChar == '(') ')' else '}'
    var depth = 0
    var i = open
    var inLine = false
    var inBlock = false
    var inString = false
    var inChar = false
    var escaped = false
    while (i < text.length) {
      val c = text[i]
      val next = if (i + 1 < text.length) text[i + 1] else ' '
      when {
        inLine -> if (c == '\n') inLine = false
        inBlock -> if (c == '*' && next == '/') { inBlock = false; i++ }
        inString -> when {
          escaped -> escaped = false
          c == '\\' -> escaped = true
          c == '"' -> inString = false
        }
        inChar -> when {
          escaped -> escaped = false
          c == '\\' -> escaped = true
          c == '\'' -> inChar = false
        }
        else -> when {
          c == '/' && next == '/' -> { inLine = true; i++ }
          c == '/' && next == '*' -> { inBlock = true; i++ }
          c == '"' -> inString = true
          c == '\'' -> inChar = true
          c == openChar -> depth++
          c == closeChar -> {
            depth--
            if (depth == 0) return i
          }
        }
      }
      i++
    }
    error("第 $open 行的 '$openChar' 找不到配对闭括号")
  }

  @Test
  fun deviceEditorAndSheetsStayInsideScaffoldContent() {
    val text = uiSource("MainActivity.kt")
    val scaffold = text.indexOf("Scaffold(")
    assertTrue("MainActivity 已不再使用 MIUIX Scaffold？", scaffold >= 0)
    val scaffoldArgsClose = matchDelimited(text, text.indexOf('(', scaffold))
    val open = text.indexOf('{', scaffoldArgsClose)
    assertTrue("找不到 Scaffold 的 content lambda", open >= 0)
    val close = matchDelimited(text, open)

    val editor = text.indexOf("DeviceEditorScreen(", open)
    assertTrue(
      "DeviceEditorScreen 被移出 Scaffold 内容作用域：页面内的 MIUIX 弹层注册不到 MiuixPopupHost。",
      editor in open until close
    )
    val keychain = text.indexOf("SshKeychainManagerSheet(", open)
    assertTrue("SSH 密钥库抽屉必须落在 Scaffold content lambda 内", keychain in open until close)
    val hostKeyDialog = text.indexOf("SshHostKeyDialog(", open)
    assertTrue("SSH 主机指纹确认弹窗必须落在 Scaffold content lambda 内", hostKeyDialog in open until close)
    assertTrue(uiSource("HyperComponents.kt").contains("SuperBottomSheet("))
  }

  @Test
  fun maskedPasswordFieldsUsePasswordKeyboard() {
    val sources = listOf("SshHostForm.kt", "SshKeySheets.kt", "DshHostSettingsDialogs.kt")
    var maskedFields = 0
    for (name in sources) {
      val text = uiSource(name)
      var searchFrom = 0
      while (true) {
        val field = text.indexOf("TextField(", searchFrom)
        if (field < 0) break
        val open = text.indexOf('(', field)
        val close = matchDelimited(text, open)
        val block = text.substring(field, close + 1)
        if ("PasswordVisualTransformation()" in block) {
          maskedFields++
          assertTrue(
            "$name 存在只做了掩码显示却没有 KeyboardType.Password 的密码输入框：$block",
            "keyboardType = KeyboardType.Password" in block
          )
        }
        searchFrom = close + 1
      }
    }
    assertTrue("SSH / DSH 表单应包含密码掩码输入框", maskedFields >= 3)
  }
}
