package com.igng.opencode.lagoon.core

/**
 * Virtualized display item for [com.igng.opencode.lagoon.ui.Conversation]'s LazyColumn.
 *
 * Expands multi-block Markdown rows (`text`, `process-output`, `reasoning-body`, `relay`) into
 * individual LazyColumn items with deterministic keys (`${row.key}#b:$index`), grouped GFM tables,
 * incremental streaming prefix caching, and background pre-computed [CodeHighlight] fragments so
 * UI-thread composition never parses CommonMark or tokenizes diffs/code at 144Hz.
 */
data class TranscriptBlockItem(
  val key: String,
  val contentType: String,
  val row: TranscriptRow,
  val block: MarkdownBlock? = null,
  val tableBlocks: List<MarkdownBlock>? = null,
  val codeFragments: List<CodeFragment>? = null,
  val subdued: Boolean = false
)

object TranscriptBlocks {
  private const val CACHE_CAPACITY = 256
  private val MARKDOWN_KINDS = setOf("text", "process-output", "reasoning-body")

  internal data class CachedParse(
    val fullText: String,
    val sealedPrefixLength: Int,
    val sealedPrefixText: String,
    val sealedBlocks: List<MarkdownBlock>,
    val allBlocks: List<MarkdownBlock>,
    val codeHighlights: Map<String, List<CodeFragment>>
  )

  private val cache = object : LinkedHashMap<String, CachedParse>(CACHE_CAPACITY, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedParse>?): Boolean =
      size > CACHE_CAPACITY
  }

  /**
   * Expands main-timeline [TranscriptRow]s into block-virtualized [TranscriptBlockItem]s.
   * Safe to run on `Dispatchers.Default`.
   */
  fun expandMainRows(rows: List<TranscriptRow>): List<TranscriptBlockItem> {
    if (rows.isEmpty()) return emptyList()
    val items = ArrayList<TranscriptBlockItem>(rows.size * 2)
    val seenKeys = HashMap<String, Int>(rows.size * 3)
    fun emitUnique(item: TranscriptBlockItem) {
      val count = seenKeys.merge(item.key, 1, Int::plus)!!
      items += if (count == 1) item else item.copy(key = "${item.key}~$count")
    }

    for (row in rows) {
      if (row.detailOf != null) continue
      when (row.kind) {
        "relay" -> {
          emitUnique(
            TranscriptBlockItem(
              key = "${row.key}#header",
              contentType = "note",
              row = row.copy(kind = "note", text = "")
            )
          )
          appendMarkdownItems(row, subdued = false, ::emitUnique)
        }
        in MARKDOWN_KINDS -> {
          appendMarkdownItems(row, subdued = row.kind == "reasoning-body", ::emitUnique)
        }
        "tool-body" -> {
          emitUnique(
            TranscriptBlockItem(
              key = row.key,
              contentType = "tool-body",
              row = row,
              codeFragments = CodeHighlight.split(row.text)
            )
          )
        }
        else -> {
          emitUnique(
            TranscriptBlockItem(
              key = row.key,
              contentType = row.kind,
              row = row
            )
          )
        }
      }
    }
    return items
  }

  private fun appendMarkdownItems(
    row: TranscriptRow,
    subdued: Boolean,
    emit: (TranscriptBlockItem) -> Unit
  ) {
    if (row.text.isBlank()) return
    val parsed = parseIncremental(row.key, row.text)
    val blocks = parsed.allBlocks
    var index = 0
    var visualBlockOrdinal = 0
    while (index < blocks.size) {
      val current = blocks[index]
      if (current.kind == "table-head" || current.kind == "table-row") {
        val tableGroup = ArrayList<MarkdownBlock>()
        while (index < blocks.size && (blocks[index].kind == "table-head" || blocks[index].kind == "table-row")) {
          tableGroup += blocks[index]
          index++
        }
        emit(
          TranscriptBlockItem(
            key = "${row.key}#b:$visualBlockOrdinal",
            contentType = "md-table",
            row = row,
            tableBlocks = tableGroup,
            subdued = subdued
          )
        )
      } else {
        val codeFrags = if (current.kind == "code") {
          parsed.codeHighlights[current.text] ?: CodeHighlight.split(current.text)
        } else null
        emit(
          TranscriptBlockItem(
            key = "${row.key}#b:$visualBlockOrdinal",
            contentType = "md-${current.kind}",
            row = row,
            block = current,
            codeFragments = codeFrags,
            subdued = subdued
          )
        )
        index++
      }
      visualBlockOrdinal++
    }
  }

  /**
   * Parses [markdown] for [key], reusing already-sealed prefix blocks during streaming whenever
   * [markdown] extends the previous sealed prefix at a top-level CommonMark boundary.
   */
  fun blocksFor(key: String, markdown: String): List<MarkdownBlock> =
    parseIncremental(key, markdown).allBlocks

  internal fun parseIncremental(key: String, markdown: String): CachedParse {
    val previous = synchronized(cache) { cache[key] }
    if (previous != null && previous.fullText == markdown) return previous

    val splitAt = findLastSealedBoundary(markdown)
    val sealedBlocks: List<MarkdownBlock>
    val sealedPrefixText: String
    val prevHighlights = previous?.codeHighlights.orEmpty()

    if (splitAt > 0) {
      if (previous != null &&
        previous.sealedPrefixLength == splitAt &&
        markdown.regionMatches(0, previous.sealedPrefixText, 0, splitAt)
      ) {
        sealedPrefixText = previous.sealedPrefixText
        sealedBlocks = previous.sealedBlocks
      } else if (previous != null &&
        previous.sealedPrefixLength in 1 until splitAt &&
        markdown.regionMatches(0, previous.sealedPrefixText, 0, previous.sealedPrefixLength)
      ) {
        // Streaming sealed one or more additional blocks: parse only the newly sealed delta!
        val deltaText = markdown.substring(previous.sealedPrefixLength, splitAt)
        sealedPrefixText = markdown.substring(0, splitAt)
        sealedBlocks = previous.sealedBlocks + MarkdownBlocks.parse(deltaText)
      } else {
        sealedPrefixText = markdown.substring(0, splitAt)
        sealedBlocks = MarkdownBlocks.parse(sealedPrefixText)
      }
    } else {
      sealedPrefixText = ""
      sealedBlocks = emptyList()
    }

    val tailBlocks = if (splitAt < markdown.length) {
      MarkdownBlocks.parse(markdown.substring(splitAt))
    } else {
      emptyList()
    }
    val allBlocks = if (sealedBlocks.isEmpty()) tailBlocks
    else if (tailBlocks.isEmpty()) sealedBlocks
    else ArrayList<MarkdownBlock>(sealedBlocks.size + tailBlocks.size).apply {
      addAll(sealedBlocks)
      addAll(tailBlocks)
    }

    val highlights = HashMap<String, List<CodeFragment>>()
    for (block in allBlocks) {
      if (block.kind == "code") {
        val codeText = block.text
        highlights[codeText] = prevHighlights[codeText] ?: CodeHighlight.split(codeText)
      }
    }

    val entry = CachedParse(
      fullText = markdown,
      sealedPrefixLength = splitAt,
      sealedPrefixText = sealedPrefixText,
      sealedBlocks = sealedBlocks,
      allBlocks = allBlocks,
      codeHighlights = highlights
    )
    synchronized(cache) { cache[key] = entry }
    return entry
  }

  /**
   * Finds the character offset of the last top-level CommonMark block boundary in [markdown]
   * that cannot be retroactively altered by appending more characters to the end:
   * - Outside any unclosed fenced code block (` ``` ` or `~~~`);
   * - Preceded by at least one blank line;
   * - Followed by a non-blank top-level paragraph/heading/fence line starting at column 0 that is
   *   NOT indented, NOT a list marker (`-`, `*`, `+`, `1.`), NOT a blockquote (`>`), NOT a table
   *   row (`|`), and NOT a setext underline (`===` / `---`), with at least one newline after it
   *   so an incomplete marker prefix (`1` before `. `) cannot split prematurely.
   */
  internal fun findLastSealedBoundary(markdown: String): Int {
    if (markdown.length < 64) return 0
    var inFence = false
    var fenceChar = '\u0000'
    var fenceLen = 0
    var sawBlankLine = false
    var lastBoundary = 0
    var lineStart = 0
    val len = markdown.length

    while (lineStart < len) {
      val nl = markdown.indexOf('\n', lineStart)
      val lineEnd = if (nl < 0) len else nl
      val hasNewline = nl >= 0

      // Trim trailing \r
      val cleanEnd = if (lineEnd > lineStart && markdown[lineEnd - 1] == '\r') lineEnd - 1 else lineEnd
      var nonSpace = lineStart
      while (nonSpace < cleanEnd && (markdown[nonSpace] == ' ' || markdown[nonSpace] == '\t')) nonSpace++
      val leadingSpaces = nonSpace - lineStart
      val isBlank = nonSpace == cleanEnd

      if (inFence) {
        if (!isBlank && leadingSpaces <= 3 && markdown[nonSpace] == fenceChar) {
          var run = 0
          var p = nonSpace
          while (p < cleanEnd && markdown[p] == fenceChar) { run++; p++ }
          while (p < cleanEnd && (markdown[p] == ' ' || markdown[p] == '\t')) p++
          if (run >= fenceLen && p == cleanEnd && hasNewline) {
            inFence = false
            sawBlankLine = false
          }
        }
      } else {
        if (isBlank) {
          if (lineStart > 0) sawBlankLine = true
        } else {
          if (sawBlankLine && leadingSpaces == 0 && isSafeTopLevelStart(markdown, lineStart, cleanEnd)) {
            lastBoundary = lineStart
          }
          sawBlankLine = false
          // Detect opening code fence (up to 3 leading spaces, >=3 backticks or tildes)
          if (leadingSpaces <= 3 && (markdown[nonSpace] == '`' || markdown[nonSpace] == '~')) {
            val ch = markdown[nonSpace]
            var run = 0
            var p = nonSpace
            while (p < cleanEnd && markdown[p] == ch) { run++; p++ }
            val validBacktickInfo = ch != '`' || markdown.indexOf('`', p).let { it < 0 || it >= cleanEnd }
            if (run >= 3 && validBacktickInfo) {
              inFence = true
              fenceChar = ch
              fenceLen = run
            }
          }
        }
      }

      if (nl < 0) break
      lineStart = nl + 1
    }
    return lastBoundary
  }

  private fun isSafeTopLevelStart(text: String, start: Int, end: Int): Boolean {
    if (start >= end) return false
    val first = text[start]
    // Exclude indented lines, list markers, blockquotes, tables, HTML blocks, and setext underlines.
    if (first == ' ' || first == '\t' || first == '-' || first == '*' || first == '+' ||
      first == '>' || first == '|' || first == '=' || first == '<'
    ) return false
    if (first in '0'..'9') {
      var p = start
      while (p < end && text[p] in '0'..'9') p++
      if (p == end || (p < end && (text[p] == '.' || text[p] == ')'))) return false
    }
    return true
  }
}
