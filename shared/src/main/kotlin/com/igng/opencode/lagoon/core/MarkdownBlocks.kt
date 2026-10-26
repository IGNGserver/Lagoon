package com.igng.opencode.lagoon.core

import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.tables.*
import org.commonmark.ext.gfm.strikethrough.*

/** UI-independent CommonMark/GFM projection. Every rendered text block has a bounded size. */
data class MarkdownSpan(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false, val strike: Boolean = false, val href: String? = null)
data class MarkdownBlock(val kind: String, val spans: List<MarkdownSpan> = emptyList(), val level: Int = 0, val prefix: String = "", val cells: List<List<MarkdownSpan>> = emptyList(), val copyText: String? = null) {
  val text get() = spans.joinToString("") { it.text }
}
object MarkdownBlocks {
  const val CHUNK = 4096
  private val parser = Parser.builder().extensions(listOf(TablesExtension.builder().maxCells(10_000).build(), StrikethroughExtension.create())).build()
  private fun children(node: Node): Sequence<Node> = sequence { var child = node.firstChild; while (child != null) { yield(child); child = child.next } }
  private fun inline(node: Node, style: MarkdownSpan = MarkdownSpan("")): List<MarkdownSpan> = when (node) {
    is Text -> listOf(style.copy(text = node.literal))
    is Code -> listOf(style.copy(text = node.literal, code = true))
    is SoftLineBreak, is HardLineBreak -> listOf(style.copy(text = "\n"))
    is StrongEmphasis -> children(node).flatMap { inline(it, style.copy(bold = true)) }.toList()
    is Emphasis -> children(node).flatMap { inline(it, style.copy(italic = true)) }.toList()
    is Strikethrough -> children(node).flatMap { inline(it, style.copy(strike = true)) }.toList()
    is Link -> children(node).flatMap { inline(it, style.copy(href = node.destination.takeIf { url -> url.startsWith("https://") || url.startsWith("http://") || url.startsWith("mailto:") })) }.toList()
    is Image -> listOf(style.copy(text = "[图片] ")) + children(node).flatMap { inline(it, style.copy(href = node.destination.takeIf { url -> url.startsWith("https://") || url.startsWith("http://") })) }.toList()
    is HtmlInline -> listOf(style.copy(text = node.literal))
    else -> children(node).flatMap { inline(it, style) }.toList()
  }
  fun chunks(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val result = mutableListOf<String>(); var start = 0
    while (start < text.length) {
      var end = (start + CHUNK).coerceAtMost(text.length)
      var line = 0; var at = start
      while (at < end) { if (text[at] == '\n' && ++line >= 80) { end = at + 1; break }; at++ }
      if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
      result += text.substring(start, end); start = end
    }
    return result
  }
  private fun bounded(block: MarkdownBlock): List<MarkdownBlock> {
    if (block.spans.sumOf { it.text.length } <= CHUNK) return listOf(block)
    val blocks = mutableListOf<MarkdownBlock>(); var current = mutableListOf<MarkdownSpan>(); var size = 0
    block.spans.forEach { span ->
      var start = 0
      while (start < span.text.length) {
        if (size == CHUNK) { blocks += block.copy(spans = current, prefix = if (blocks.isEmpty()) block.prefix else "", copyText = if (blocks.isEmpty()) block.copyText else null); current = mutableListOf(); size = 0 }
        var end = (start + CHUNK - size).coerceAtMost(span.text.length)
        if (end < span.text.length && Character.isHighSurrogate(span.text[end - 1])) end--
        if (end == start) { blocks += block.copy(spans = current, prefix = if (blocks.isEmpty()) block.prefix else "", copyText = if (blocks.isEmpty()) block.copyText else null); current = mutableListOf(); size = 0; continue }
        current += span.copy(text = span.text.substring(start, end)); size += end - start; start = end
      }
    }
    if (current.isNotEmpty()) blocks += block.copy(spans = current, prefix = if (blocks.isEmpty()) block.prefix else "", copyText = if (blocks.isEmpty()) block.copyText else null)
    return blocks
  }
  fun parse(markdown: String): List<MarkdownBlock> = try {
    parseDocument(markdown)
  } catch (_: IllegalArgumentException) {
    chunks(markdown).map { MarkdownBlock("code", listOf(MarkdownSpan(it, code = true))) }
  } catch (_: StackOverflowError) {
    chunks(markdown).map { MarkdownBlock("code", listOf(MarkdownSpan(it, code = true))) }
  }
  private fun parseDocument(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    fun add(block: MarkdownBlock) {
      if (block.kind == "code") chunks(block.text).forEachIndexed { index, chunk -> blocks += block.copy(spans = listOf(MarkdownSpan(chunk, code = true)), prefix = if (index == 0) block.prefix else "", copyText = if (index == 0) block.copyText else null) }
      else blocks += bounded(block)
    }
    fun visit(node: Node, prefix: String = "", depth: Int = 0) {
      when (node) {
        is Heading -> add(MarkdownBlock("heading", inline(node), node.level, prefix))
        is Paragraph -> add(MarkdownBlock("paragraph", inline(node), prefix = prefix))
        is FencedCodeBlock -> add(MarkdownBlock("code", listOf(MarkdownSpan(node.literal, code = true)), prefix = node.info.orEmpty(), copyText = node.literal))
        is IndentedCodeBlock -> add(MarkdownBlock("code", listOf(MarkdownSpan(node.literal, code = true)), copyText = node.literal))
        is ThematicBreak -> add(MarkdownBlock("rule"))
        is HtmlBlock -> add(MarkdownBlock("code", listOf(MarkdownSpan(node.literal, code = true)), copyText = node.literal))
        is BlockQuote -> children(node).forEach { visit(it, "> " + prefix, depth) }
        is BulletList -> children(node).forEach { item -> children(item).forEachIndexed { i, child -> visit(child, if (i == 0) "  ".repeat(depth) + "• " else "  ".repeat(depth + 1), depth + 1) } }
        is OrderedList -> children(node).forEachIndexed { n, item -> children(item).forEachIndexed { i, child -> visit(child, if (i == 0) "  ".repeat(depth) + "${node.markerStartNumber + n}. " else "  ".repeat(depth + 1), depth + 1) } }
        is TableRow -> {
          val cells = children(node).map { inline(it) }.toList()
          // Very large cells remain bounded; extra rows carry the remaining content.
          val pieces = cells.map { spans -> bounded(MarkdownBlock("paragraph", spans)).map { it.spans } }
          repeat(pieces.maxOfOrNull { it.size } ?: 0) { index -> blocks += MarkdownBlock(if (node.parent is TableHead) "table-head" else "table-row", cells = pieces.map { it.getOrNull(index).orEmpty() }) }
        }
        else -> children(node).forEach { visit(it, prefix, depth) }
      }
    }
    val root = parser.parse(markdown)
    // Bound recursive rendering before walking deeply nested lists/quotes/inline nodes.
    val pending = ArrayDeque<Pair<Node, Int>>(); pending.add(root to 0)
    var count = 0
    while (pending.isNotEmpty()) {
      val (node, depth) = pending.removeLast()
      require(depth <= 32 && ++count <= 100_000)
      children(node).forEach { pending.add(it to depth + 1) }
    }
    visit(root)
    return blocks
  }
}
