package com.igng.opencode.lagoon.core

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One expandable body line of a tool/change row. */
data class ToolSection(val label: String, val text: String, val code: Boolean = true, val copyText: String? = null)

/**
 * UI-independent transcript projection. Ports the official OpenCode session timeline
 * (`packages/session-ui/src/timeline/projection.ts`): turn grouping (user turns, shell turns,
 * leading notices), notice rows, part visibility, context-tool grouping, interruption divider and
 * the last-error rule. Row kinds:
 * time | user | text | reasoning | meta | divider | thinking | error | note |
 * tool | tool-body | tool-group | diff-summary | diff-file | reasoning-body
 */
data class TranscriptRow(
  val key: String,
  val kind: String,
  val title: String = "",
  val subtitle: String = "",
  val args: List<String> = emptyList(),
  val status: String = "",
  val text: String = "",
  val attachments: List<Attachment> = emptyList(),
  val meta: String = "",
  val copyText: String? = null,
  /** A child session this row links to (subagent tool or result notice). */
  val target: String? = null,
  /**
   * 该行是某个分组行的明细，取值即分组行的 key；null 表示主时间线行。主时间线不渲染明细，
   * 点击分组行时由 [TranscriptRows.details] 取同一批行在半屏窗口里滚动查看。
   */
  val detailOf: String? = null,
  /**
   * 该行是半屏窗口里某个条目（工具调用 / 思考 / 改动文件）的正文时，取值即条目头的 key；
   * null 表示条目头或主时间线行。半屏窗口据此在用户点开条目前隐藏正文。
   */
  val bodyOf: String? = null,
  /** Original authoritative part for the second-level detail sheet. */
  val source: MessagePart? = null
)

object TranscriptRows {
  private const val TIME_GAP_MS = 30 * 60 * 1000L
  private const val MAX_DIFF_FILES = 10

  private val HIDDEN_TOOLS = setOf("todowrite")
  private val EDIT_TOOLS = setOf("edit", "write", "patch", "apply_patch")
  private val FINISHED_TOOLS = setOf("completed", "error")
  private val ACTIVE_STATUSES = setOf("running", "pending")
  private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.systemDefault())

  private class Turn(val id: String, val created: Long, val user: Message? = null, val shell: Message? = null) {
    val entries = mutableListOf<Message>()
  }

  /**
   * [revertMessageId] is the session's staged revert boundary: like the official timeline, messages
   * at or after it are hidden until the revert is cleared or committed.
   */
  fun build(messages: List<Message>, expanded: Set<String> = emptySet(), working: Boolean = false, revertMessageId: String? = null): List<TranscriptRow> {
    val visible = messages.filter { it.isDisplayable && (revertMessageId == null || it.id < revertMessageId) }
    val turns = mutableListOf<Turn>()
    val users = mutableSetOf<String>()
    val leading = mutableListOf<Message>()
    var current: Turn? = null
    visible.forEach { message ->
      when (message.role) {
        "notice" -> current?.entries?.add(message) ?: leading.add(message)
        "shell" -> Turn(message.id, message.created, shell = message).also { turns += it; current = it }
        "user" -> if (users.add(message.id)) Turn(message.id, message.created, user = message).also { turns += it; current = it }
        "assistant" -> {
          val turn = current
          if (turn != null && turn.shell == null) turn.entries += message
          else Turn(message.id, message.created).also { it.entries += message; turns += it; current = it }
        }
      }
    }
    val rows = mutableListOf<TranscriptRow>()
    leading.forEach { rows += noticeRow("lead", it) }
    var previousEnd = 0L
    turns.forEachIndexed { index, turn ->
      if (index == 0 || turn.created - previousEnd >= TIME_GAP_MS) rows += TranscriptRow("time:${turn.id}", "time", text = formatTime(turn.created))
      previousEnd = (listOfNotNull(turn.user, turn.shell) + turn.entries).maxOf { it.completedAt ?: it.created }
      rows += turnRows(turn, "turn:${turn.id}", expanded, working && index == turns.lastIndex)
    }
    return rows.withUniqueKeys()
  }

  /**
   * 点击分组行时，半屏窗口要展示的明细。与主时间线共用同一套投影：先展开该分组，再把它直接包含
   * 的条目（工具调用 / 思考 / 改动文件）一并展开，取出带 [TranscriptRow.detailOf] 的行。条目正文由
   * [TranscriptRow.bodyOf] 指回条目头，半屏窗口默认折叠正文，点击条目才显示。
   */
  fun details(messages: List<Message>, key: String, working: Boolean = false, revertMessageId: String? = null): List<TranscriptRow> {
    if (key.isBlank()) return emptyList()
    val first = build(messages, setOf(key), working, revertMessageId)
    val children = first.filter { it.detailOf == key }.map { it.key }.toSet()
    val rows = if (children.isEmpty()) first else build(messages, setOf(key) + children, working, revertMessageId)
    return rows.filter { it.detailOf == key }
  }

  /**
   * A part's identity within the transcript. Part ids are not unique on their own: providers may
   * reuse a tool call id across messages of one turn. Scoping by message and falling back to the
   * position keeps keys unique and stable.
   */
  private fun partRef(message: Message, index: Int, part: MessagePart): String = "${message.id}/${part.id.ifBlank { "#$index" }}"

  /** LazyColumn rejects duplicate keys with a crash; never let server data reach it un-deduplicated. */
  private fun List<TranscriptRow>.withUniqueKeys(): List<TranscriptRow> {
    val seen = HashMap<String, Int>(size * 2)
    return map { row ->
      val count = seen.merge(row.key, 1, Int::plus)!!
      if (count == 1) row else row.copy(key = "${row.key}~$count")
    }
  }

  private fun noticeRow(scope: String, message: Message): TranscriptRow {
    val notice = message.notice ?: Notice("")
    val detail = listOf(notice.detail).plus(notice.items).filter(String::isNotBlank).joinToString("、")
    return TranscriptRow("$scope:notice:${message.id}", "note", title = notice.label, text = detail,
      status = when { notice.error -> "error"; notice.running -> "running"; else -> "" }, target = notice.target)
  }

  /** Official `isInterrupted`: decided by the structured error type. */
  private fun Message.interrupted(): Boolean = finish == "aborted" ||
    errorType?.lowercase()?.let { it.contains("abort") || it.contains("interrupt") } == true

  private fun turnRows(turn: Turn, turnKey: String, expanded: Set<String>, working: Boolean): List<TranscriptRow> {
    val rows = mutableListOf<TranscriptRow>()
    turn.user?.let { user ->
      val text = user.parts.filter { it.type == "text" }.joinToString("\n") { it.text }.trim()
      val attachments = (user.parts.filter { it.type == "file" }.map { Attachment(it.path, it.mime, it.title) } + user.parts.flatMap { it.attachments }).distinctBy { it.url }
      rows += TranscriptRow("user:${user.id}", "user", text = text, attachments = attachments,
        meta = listOfNotNull(if (user.id.startsWith(PENDING_PREFIX)) "等待连接后发送" else if (user.queued) "排队中" else null,
          user.agent, user.model?.label, formatTime(user.created).substringAfter(' '))
          .filter { it.isNotBlank() }.joinToString(" · "),
        copyText = text.takeIf { it.isNotBlank() }, status = if (user.queued) "queued" else "")
    }
    turn.shell?.let { shell ->
      shell.parts.forEachIndexed { index, part -> rows += toolRows(turnKey, partRef(shell, index, part), part, expanded) }
    }

    val assistants = turn.entries.filter { it.role == "assistant" }
    val body = mutableListOf<TranscriptRow>()
    // Qoder treats one user prompt -> one final answer as one turn. Text emitted before a
    // later reasoning/tool part is process output; only text after the last operation remains
    // in the main transcript as the final answer.
    val entries = turn.entries.filter { it.role == "assistant" }.flatMap { message ->
      message.parts.mapIndexed { index, part -> partRef(message, index, part) to part }
        .filter { (_, part) -> part.isDisplayable && !(part.type == "tool" && (part.tool in HIDDEN_TOOLS || (part.tool == "question" && part.status !in FINISHED_TOOLS))) }
    }
    // Notices remain official one-line transcript records. They are not model output and should
    // not inflate the operation counter or become a fake tool row.
    turn.entries.filter { it.role == "notice" }.forEach { body += noticeRow(turnKey, it) }
    val lastActivity = entries.indexOfLast { (_, part) -> part.type in setOf("tool", "reasoning") }
    val nonFinal = assistants.filter { it.finish in setOf("tool-calls", "tool_calls") }.map { it.id }.toSet()
    val finalParts = entries.filterIndexed { index, (ref, part) ->
      part.type == "text" && index > lastActivity && nonFinal.none { ref.startsWith("$it/") }
    }
    val finalRefs = finalParts.map { it.first }.toSet()
    val process = entries.filterNot { it.first in finalRefs }
    if (working) {
      // A running turn stays transparent: each process reply is shown inline and every consecutive
      // batch of operations becomes its own group whose calls remain visible while they execute.
      // Only finished turns collapse into the「共处理 N 个操作」summary.
      body += runningTurnRows(turnKey, process, expanded)
    } else if (process.isNotEmpty()) {
      body += toolGroupRows(turnKey, process, expanded)
    }
    if (finalParts.isNotEmpty()) {
      val text = finalParts.joinToString("\n\n") { it.second.text.trim() }
      body += TranscriptRow("$turnKey:answer", "text", text = text)
    }
    val compaction = turn.entries.any { it.type == "compaction" }
    var dividerShown = false
    turn.entries.forEach { message ->
      if (message.interrupted() && !dividerShown) {
        dividerShown = true
        if (!compaction) body += TranscriptRow("$turnKey:interrupted", "divider", text = "本轮已中断")
      }
    }
    rows += body

    val last = assistants.lastOrNull()
    if (working && last?.retry != null) rows += TranscriptRow("$turnKey:retry", "note", title = "正在重试", text = last.retry, status = "running")
    else if (last?.error != null && !last.interrupted()) rows += TranscriptRow("$turnKey:error", "error", text = unwrapError(last.error))
    if (assistants.isNotEmpty()) {
      val finished = assistants.all { it.completedAt != null }
      val first = turn.user?.created ?: turn.created
      // A running turn's「用时」would be a stale, non-ticking number that reads as “已结束” at the very
      // bottom; only surface it once the whole turn has actually completed.
      val duration = if (finished && !working) assistants.maxOf { it.completedAt ?: 0L } - first else 0L
      val meta = listOfNotNull(
        last?.agent,
        last?.model?.label,
        durationText(duration),
        last?.let { formatTime(it.completedAt ?: it.created).substringAfter(' ') }
      ).filter { it.isNotBlank() }.joinToString(" · ")
      val copy = body.filter { it.kind == "text" }.joinToString("\n\n") { it.text }
      rows += TranscriptRow("$turnKey:meta", "meta", meta = meta, copyText = copy.takeIf { it.isNotBlank() })
    }
    if (!working) {
      val edits = assistants.flatMap { message -> message.parts.mapIndexed { index, part -> partRef(message, index, part) to part } }
        .filter { (_, it) -> it.tool in EDIT_TOOLS && (it.patch.isNotBlank() || it.files.isNotEmpty() || inputString(it, "path").isNotBlank()) }
      if (edits.isNotEmpty()) {
        val key = "$turnKey:diff"
        val files = edits.flatMap { (_, edit) -> edit.files.ifEmpty { listOf(inputString(edit, "path")) } }.filter { it.isNotBlank() }.distinct()
        rows += TranscriptRow(key, "diff-summary", title = "本轮改动 ${files.size} 个文件", subtitle = files.take(3).joinToString("、") { it.substringAfterLast('/') })
        if (key in expanded) edits.take(MAX_DIFF_FILES).forEach { (ref, edit) ->
          val path = edit.files.joinToString("、").ifBlank { inputString(edit, "path") }.ifBlank { "改动" }
          val patch = edit.patch.ifBlank { edit.output }
          val fileKey = "$key:$ref"
          rows += TranscriptRow(fileKey, "diff-file", title = path, detailOf = key)
          MarkdownBlocks.chunks(patch).forEachIndexed { index, chunk ->
            rows += TranscriptRow("$fileKey:$index", "tool-body", title = if (index == 0) "改动" else "", text = chunk,
              copyText = patch.takeIf { index == 0 }, detailOf = key, bodyOf = fileKey)
          }
        }
      }
    }
    if (working && turn.shell == null && body.none { it.kind != "meta" && it.kind != "note" }) rows += TranscriptRow("$turnKey:thinking", "thinking", text = "运行中…")
    return rows
  }

  /**
   * A running turn's transparent timeline: process replies inline, and each consecutive batch of
   * operations collapsed into one small「调用了 N 次工具」group. Calls and thinking stay folded —
   * only the process report is visible, exactly like a finished turn, and a group opens on tap.
   */
  private fun runningTurnRows(turnKey: String, entries: List<Pair<String, MessagePart>>, expanded: Set<String>): List<TranscriptRow> {
    val rows = mutableListOf<TranscriptRow>()
    var index = 0
    var batch = 0
    while (index < entries.size) {
      val (ref, part) = entries[index]
      if (part.type == "text") {
        if (part.text.isNotBlank()) rows += TranscriptRow("$turnKey:process:$ref", "process-output", text = part.text, source = part)
        index++
        continue
      }
      val group = mutableListOf<Pair<String, MessagePart>>()
      while (index < entries.size && entries[index].second.type != "text") { group += entries[index]; index++ }
      if (group.isEmpty()) { index++; continue }
      batch++
      val tools = group.count { it.second.type == "tool" }
      rows += activityGroupRows("$turnKey:batch:$batch", group, expanded,
        title = if (tools > 0) "调用了 $tools 次工具" else "思考中")
    }
    return rows
  }

  /**
   * One stable activity group per user turn, including intermediate text in chronological order.
   * Process output is visible in the activity list; tool/reasoning bodies are second-level details.
   */
  private fun toolGroupRows(turnKey: String, entries: List<Pair<String, MessagePart>>, expanded: Set<String>): List<TranscriptRow> =
    activityGroupRows("$turnKey:activities", entries, expanded, "共处理 ${entries.count { it.second.type != "text" }} 个操作")

  /** A collapsed operation group: a one-line summary, and its calls/bodies only after [expanded]. */
  private fun activityGroupRows(key: String, entries: List<Pair<String, MessagePart>>, expanded: Set<String>, title: String): List<TranscriptRow> {
    val tools = entries.filter { it.second.type == "tool" }.map { it.second }
    val reasonings = entries.filter { it.second.type == "reasoning" }.map { it.second }
    // Only calls that are actually executing spin. A running turn used to force every batch to
    // "running", so finished calls showed the spinner too and read as still working.
    val status = when {
      tools.any { it.status in ACTIVE_STATUSES } || reasonings.any { it.status in ACTIVE_STATUSES } -> "running"
      tools.any { it.status == "error" } -> "error"
      else -> "completed"
    }
    val counts = LinkedHashMap<String, Int>()
    tools.forEach { tool -> counts[toolCategory(tool)] = (counts[toolCategory(tool)] ?: 0) + 1 }
    val summary = buildList {
      counts.forEach { (label, count) -> add("$count 个$label") }
      if (reasonings.isNotEmpty()) add("${reasonings.size} 个思考")
    }.joinToString("、")
    val errorText = tools.firstOrNull { it.status == "error" && it.error.isNotBlank() }?.let { unwrapError(it.error) }
    val preview = errorText?.take(140) ?: tools.singleOrNull()?.let { toolInfo(it).second }.orEmpty()
    val row = TranscriptRow(key, "tool-group", title = title, subtitle = preview, meta = summary, status = status,
      target = tools.singleOrNull()?.takeIf { it.tool in SUBAGENT_TOOLS }?.target)
    if (key !in expanded) return listOf(row)
    val children = mutableListOf<TranscriptRow>()
    entries.forEachIndexed { position, (ref, part) ->
      val occurrence = entries.take(position).count { it.first == ref }
      val itemKey = "$key:$ref" + if (occurrence == 0) "" else "~${occurrence + 1}"
      if (part.type == "reasoning") {
        val text = part.text.trim()
        children += TranscriptRow(itemKey, "reasoning", title = "深度思考", status = part.status, detailOf = key, source = part)
        reasoningBodies(key, itemKey, text).forEach { children += it }
      } else if (part.type == "text") {
        children += TranscriptRow(itemKey, "process-output", text = part.text, detailOf = key, source = part)
      } else if (part.type == "notice") {
        children += TranscriptRow(itemKey, "note", title = part.title, text = part.text, status = part.status, target = part.target, detailOf = key)
      } else if (part.type == "file") {
        children += TranscriptRow(itemKey, "attachment", attachments = listOf(Attachment(part.path, part.mime, part.title)), detailOf = key, source = part)
      } else {
        val (title, subtitle) = toolInfo(part)
        children += TranscriptRow(itemKey, "tool", title = title, subtitle = subtitle, args = toolArgs(part), status = part.status,
          attachments = part.attachments, target = part.target.takeIf { part.tool in SUBAGENT_TOOLS }, detailOf = key, source = part)
        toolSections(part).flatMap { section ->
          MarkdownBlocks.chunks(section.text).mapIndexed { index, chunk ->
            TranscriptRow("$itemKey:${section.label}:$index", "tool-body", title = if (index == 0) section.label else "", text = chunk,
              copyText = section.copyText?.takeIf { index == 0 }, detailOf = key, bodyOf = itemKey)
          }
        }.forEach { children += it }
      }
    }
    return listOf(row) + children
  }

  /** 半屏窗口里「思考」条目的正文，默认折叠，点击条目头才显示；运行中的内联思考直接展开。 */
  private fun reasoningBodies(groupKey: String?, itemKey: String, text: String): List<TranscriptRow> =
    MarkdownBlocks.chunks(text).mapIndexed { index, chunk ->
      TranscriptRow("$itemKey:body:$index", "reasoning-body", text = chunk, detailOf = groupKey, bodyOf = itemKey)
    }

  /** 工具归类文案，用于「使用了 N 个 X」的分组标题。 */
  private fun toolCategory(part: MessagePart): String = when (part.tool) {
    in SHELL_TOOLS -> "Shell"
    "read" -> "读取"
    "list" -> "列表"
    "glob", "grep" -> "搜索"
    in EDIT_TOOLS -> "编辑"
    "webfetch" -> "网页"
    "websearch" -> "网络搜索"
    in SUBAGENT_TOOLS -> "子任务"
    "question" -> "提问"
    "skill" -> "技能"
    else -> part.tool.ifBlank { "工具" }
  }

  /** A standalone tool row, used for explicit shell turns; its body is shown only after [details] expands it. */
  private fun toolRows(turnKey: String, ref: String, part: MessagePart, expanded: Set<String>): List<TranscriptRow> {
    val key = "$turnKey:tool:$ref"
    val (title, subtitle) = toolInfo(part)
    val row = TranscriptRow(key, "tool", title = title, subtitle = subtitle, args = toolArgs(part), status = part.status,
      attachments = part.attachments, target = part.target.takeIf { part.tool in SUBAGENT_TOOLS }, source = part)
    return listOf(row) + if (key !in expanded) emptyList() else toolSections(part).flatMap { section ->
      MarkdownBlocks.chunks(section.text).mapIndexed { index, chunk ->
        TranscriptRow("$key:${section.label}:$index", "tool-body", title = if (index == 0) section.label else "", text = chunk,
          copyText = section.copyText?.takeIf { index == 0 }, detailOf = key, bodyOf = key)
      }
    }
  }

  internal fun toolSections(part: MessagePart): List<ToolSection> {
    val input = inputObject(part)
    val sections = mutableListOf<ToolSection>()
    when (part.tool) {
      in SHELL_TOOLS -> {
        val command = command(part)
        if (command.isNotBlank()) sections += ToolSection("命令", command, copyText = command)
        if (part.output.isNotBlank()) sections += ToolSection("输出", part.output, copyText = part.output)
        if (part.error.isNotBlank()) sections += ToolSection("错误", part.error, copyText = part.error)
      }
      in EDIT_TOOLS -> {
        inputString(part, "path").takeIf(String::isNotBlank)?.let { sections += ToolSection("文件路径", it, code = false) }
        val patch = part.patch.ifBlank { input?.str("patchText").orEmpty() }.ifBlank { part.output }
        if (part.tool == "write") input?.str("content")?.takeIf(String::isNotBlank)?.let { sections += ToolSection("内容", it) }
        if (part.tool == "edit" && part.patch.isBlank()) {
          input?.str("oldString")?.takeIf(String::isNotBlank)?.let { sections += ToolSection("原内容", it) }
          input?.str("newString")?.takeIf(String::isNotBlank)?.let { sections += ToolSection("新内容", it) }
        }
        if (patch.isNotBlank()) sections += ToolSection("改动", patch, copyText = patch)
        if (part.error.isNotBlank()) sections += ToolSection("错误", part.error, copyText = part.error)
      }
      else -> {
        if (part.tool == "read") inputString(part, "path").takeIf(String::isNotBlank)?.let { sections += ToolSection("文件路径", it, code = false) }
        if (part.tool != "read" && part.input.isNotBlank()) sections += ToolSection("输入", part.input, copyText = part.input)
        if (part.output.isNotBlank()) sections += ToolSection(if (part.tool == "read") "内容" else "输出", part.output, copyText = part.output)
        if (part.error.isNotBlank()) sections += ToolSection("错误", part.error, copyText = part.error)
        if (sections.isEmpty() && part.input.isNotBlank() && part.tool.isNotBlank()) sections += ToolSection("输入", part.input, copyText = part.input)
      }
    }
    return sections.filter { it.text.isNotBlank() }
  }

  internal fun toolInfo(part: MessagePart): Pair<String, String> {
    val input = inputObject(part)
    fun value(key: String) = input?.str(key).orEmpty()
    fun path() = value("path").ifBlank { value("filePath") }.ifBlank { part.path }
    return when (part.tool) {
      "read" -> "读取文件" to path().substringAfterLast('/')
      "list" -> "列出目录" to path()
      "glob" -> "搜索文件" to value("pattern")
      "grep" -> "搜索内容" to value("pattern").ifBlank { part.title }
      "webfetch" -> "抓取网页" to value("url").ifBlank { part.path }
      "websearch" -> "网络搜索" to value("query").ifBlank { part.title }
      in SUBAGENT_TOOLS -> "子任务" to listOf(value("agent"), value("description").ifBlank { part.title }).filter(String::isNotBlank).joinToString(" · ")
      in SHELL_TOOLS -> "命令行" to command(part).lineSequence().firstOrNull().orEmpty()
      "edit" -> "编辑文件" to path().substringAfterLast('/')
      "write" -> "写入文件" to path().substringAfterLast('/')
      "patch", "apply_patch" -> "应用补丁" to part.files.map { it.substringAfterLast('/') }.ifEmpty { listOfNotNull(value("files").ifBlank { null }) }.joinToString("、")
      "question" -> "提问" to part.title
      "skill" -> "技能" to value("name").ifBlank { part.title }
      else -> part.tool.ifBlank { "工具" } to part.title.ifBlank { part.path }
    }
  }

  internal fun toolArgs(part: MessagePart): List<String> {
    val input = inputObject(part) ?: return emptyList()
    val args = mutableListOf<String>()
    if (part.tool == "read") {
      if (input.has("offset")) args += "offset=${input.str("offset")}"
      if (input.has("limit")) args += "limit=${input.str("limit")}"
    }
    if (part.tool == "grep" && input.str("include").isNotBlank()) args += "include=${input.str("include")}"
    return args
  }

  /** Strips a JSON error envelope down to its human-readable message (official `unwrapErrorMessage`). */
  internal fun unwrapError(message: String): String {
    val text = message.removePrefix("Error: ").trim()
    val json = runCatching { JSONObject(text) }.getOrNull() ?: runCatching {
      val start = text.indexOf('{'); val end = text.lastIndexOf('}')
      if (start in 0 until end) JSONObject(text.substring(start, end + 1)) else null
    }.getOrNull() ?: return text
    val error = json.optJSONObject("error")
    val errorText = error?.str("message")?.ifBlank { error.str("type") }.orEmpty().ifBlank { error?.str("code").orEmpty() }
    return errorText.ifBlank { json.str("message") }.ifBlank { json.str("error") }.ifBlank { text }
  }

  private fun command(part: MessagePart): String = inputObject(part)?.str("command").orEmpty()
    .ifBlank { part.input.takeIf { !it.trimStart().startsWith("{") }.orEmpty() }.ifBlank { part.text }

  private fun inputObject(part: MessagePart): JSONObject? = part.input.trim().takeIf { it.startsWith("{") }
    ?.let { runCatching { JSONObject(it) }.getOrNull() }

  private fun inputString(part: MessagePart, key: String): String = inputObject(part)?.let { it.str(key).ifBlank { if (key == "path") it.str("filePath") else "" } }.orEmpty().ifBlank {
    if (key == "path") part.path else ""
  }

  private fun formatTime(millis: Long): String = clock.format(Instant.ofEpochMilli(if (millis > 0) millis else System.currentTimeMillis()))

  private fun durationText(ms: Long): String = when {
    ms <= 0 -> ""
    ms < 60_000 -> "用时 ${ms / 1000} 秒"
    else -> "用时 ${ms / 60_000} 分 ${(ms % 60_000) / 1000} 秒"
  }
}
