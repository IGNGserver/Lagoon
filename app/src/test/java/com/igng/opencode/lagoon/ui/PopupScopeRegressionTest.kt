package com.igng.opencode.lagoon.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MIUIX 弹层作用域与密码键盘的源码级结构回归测试。
 *
 * SuperDialog/SuperBottomSheet 只注册进 Miuix Scaffold 内部 provide 的弹层列表，
 * 并只由 Scaffold 自带的 MiuixPopupHost 渲染。MainActivity 一旦把整页编辑器（以及半屏抽屉）的
 * 声明点挪出 Scaffold 内容 lambda，弹层会静默落进无人渲染的默认列表，表现就是
 * “点击添加服务器没有任何反应”（#15 修过一次，#21 重构 Dock 时又移了出去）。
 * JVM 单测无法起 Compose 组合，这里用源码扫描守住这两条已付出过代价的回归线。
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

  /** 从 open 处的开括号开始扫描，返回与之配对的闭括号下标；跳过注释、字符串与字符字面量。 */
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

  /**
   * 设备编辑器是路由上的一层，由根 AnimatedContent 渲染——它必须落在 Scaffold 的 content lambda 里，
   * 否则它内部的 SuperDialog/SuperBottomSheet 注册不到 MiuixPopupHost；MIUIX 半屏抽屉仍走同一作用域。
   */
  @Test
  fun deviceEditorAndSheetsStayInsideScaffoldContent() {
    val text = uiSource("MainActivity.kt")
    val scaffold = text.indexOf("Scaffold(")
    assertTrue("MainActivity 已不再使用 MIUIX Scaffold？", scaffold >= 0)
    // Scaffold 的 content 是尾随 lambda，形如 `) { insets ->`
    val marker = text.indexOf(") { insets ->", scaffold)
    assertTrue("找不到 Scaffold 的 content lambda", marker >= 0)
    val open = text.indexOf('{', marker)
    val close = matchDelimited(text, open)

    val editor = text.indexOf("DeviceEditorScreen(", open)
    assertTrue(
      "DeviceEditorScreen 被移出 Scaffold 内容作用域：页面内的 MIUIX 弹层注册不到 MiuixPopupHost，" +
        "点保存/删除会没有任何反应（见 #15/#21 的反复）。请把整页编辑器放回 content lambda 内。",
      editor in open until close
    )
    val sheet = text.indexOf("RemoteEnrollSheet(", open)
    assertTrue("远程中枢接入抽屉必须同样落在 content lambda 内", sheet in open until close)
    // 半屏抽屉确由 MIUIX 机制渲染，而不是被改成了不受此机制保护的自定义浮层。
    assertTrue(uiSource("HyperComponents.kt").contains("SuperBottomSheet("))
  }

  @Test
  fun maskedPasswordFieldsUsePasswordKeyboard() {
    val text = uiSource("Screens.kt")
    var maskedFields = 0
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
          "存在只做了掩码显示却没有 KeyboardType.Password 的密码输入框：$block",
          "keyboardType = KeyboardType.Password" in block
        )
      }
      searchFrom = close + 1
    }
    assertTrue("服务器表单应包含访问密码掩码输入框", maskedFields >= 1)
  }
}
