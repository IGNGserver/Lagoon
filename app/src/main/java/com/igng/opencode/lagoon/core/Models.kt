package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject

data class ServerProfile(
  val id: String,
  val name: String,
  val url: String,
  val username: String = "opencode",
  val autoConnect: Boolean = true,
  val notifications: Boolean = true,
  val allowCleartext: Boolean = false
)

data class Project(val id: String, val directory: String, val name: String, val sandboxes: List<String> = emptyList(),
  /** Source server when this project is shown in the all-servers overview. */
  val serverId: String? = null) {
  val directories: List<String> get() = (listOf(directory) + sandboxes).filter(String::isNotBlank).distinctBy(::normalizedDirectory)
  /** Project ids are only unique within one OpenCode server. */
  val key: String get() = serverId?.let { scopedKey(it, id) } ?: id
}
data class Session(val id: String, val directory: String, val title: String, val updated: Long, val parentId: String? = null,
  val projectId: String? = null, val created: Long = 0, val archived: Boolean = false,
  val agent: String? = null, val model: ModelChoice? = null,
  /** 服务端记录的最近一次“用户看过此会话”的时间点（`time.viewed`，由 `POST /api/session/{id}/view` 写入）。 */
  val viewed: Long = 0,
  /** 最近一轮执行进入空闲（即出结果）的时间点（`time.idle`）。 */
  val idle: Long = 0,
  /** 最近一轮执行的结局（`outcome`）：`succeeded` / `failed` / `interrupted`。 */
  val outcome: String? = null,
  /** 已暂存撤销的边界（`revert.messageID`）：官方时间线隐藏 id ≥ 它的消息。 */
  val revertMessageId: String? = null,
  /** Source server when this session is shown in the all-servers overview. */
  val serverId: String? = null) {
  /** 排序口径与官方一致：优先 `time.updated`，缺失时退回 `time.created`。 */
  val activityAt: Long get() = updated.takeIf { it > 0 } ?: created
  /** Session ids are only unique within one OpenCode server. */
  val key: String get() = serverId?.let { scopedKey(it, id) } ?: id
  val target: SessionTarget? get() = serverId?.let { SessionTarget(it, id) }
}

/** A session identity that remains unambiguous when several servers use the same id. */
data class SessionTarget(val serverId: String, val sessionId: String)

/** Internal map key for all-server projections; raw server session ids stay unchanged on the wire. */
internal fun scopedKey(serverId: String, id: String): String = "$serverId\u0000$id"

/**
 * One projected timeline entry (`Session.Message.Info`). [role] is the rendering family:
 * - `user` / `assistant`: a turn's prompt and replies;
 * - `shell`: a user-run shell command, which forms its own turn;
 * - `notice`: model-facing records (system, synthetic, skill, agent/model/location switches, compaction).
 *   Their `text` is written for the model and is never shown as a reply; [notice] carries the
 *   official one-line presentation;
 * - `hidden`: records the official timeline does not render (idle markers, synthetic input without
 *   a description).
 * [type] keeps the server's own message type.
 */
data class Message(val id: String, val role: String, val created: Long, val parts: List<MessagePart>, val error: String? = null,
  val agent: String? = null, val model: ModelChoice? = null, val completedAt: Long? = null, val finish: String? = null,
  val errorType: String? = null, val retry: String? = null, val notice: Notice? = null,
  /** Admitted input not yet delivered to the agent (`/inbox`); `queue` items wait for the current turn. */
  val queued: Boolean = false, val type: String = role)

/** Official `Notice` row: a label, optional detail/items and, for subagent results, the child session. */
data class Notice(val label: String, val detail: String = "", val items: List<String> = emptyList(), val target: String? = null,
  val error: Boolean = false, val running: Boolean = false)
data class Attachment(val url: String, val mime: String = "", val name: String = "")
data class MessagePart(
  val id: String,
  val type: String,
  val text: String = "",
  val tool: String = "",
  val title: String = "",
  val status: String = "",
  val input: String = "",
  val output: String = "",
  val path: String = "",
  val error: String = "",
  val patch: String = "",
  val files: List<String> = emptyList(),
  val mime: String = "", val attachments: List<Attachment> = emptyList(),
  /** Child session a subagent tool runs in, when the server reports it. */
  val target: String? = null,
  /** Official background metadata: the tool returned while its child work remains running. */
  val background: Boolean = false
)
data class PermissionRequest(
  val id: String,
  val sessionId: String,
  val directory: String,
  val action: String,
  val detail: String,
  val always: List<String> = emptyList(),
  val toolMessageId: String = "",
  val toolCallId: String = ""
)
data class QuestionOption(val label: String, val description: String, val value: String = label)
data class QuestionPrompt(
  val title: String,
  val options: List<QuestionOption>,
  val multiple: Boolean,
  val custom: Boolean = false,
  val field: String = ""
)
data class QuestionRequest(val id: String, val sessionId: String, val directory: String, val questions: List<QuestionPrompt>, val form: Boolean = true)
data class FileChange(
  val path: String,
  val after: String,
  val additions: Int,
  val deletions: Int,
  val patch: String = ""
)
data class FileNode(val path: String, val type: String)
data class FileContent(val type: String, val content: String)
data class ModelChoice(val providerId: String, val modelId: String, val label: String, val variant: String? = null)
data class AgentChoice(val name: String, val description: String)
data class CommandChoice(val name: String, val description: String)

/**
 * 一条运行中的 shell 作业（`GET /api/shell` 的 `Shell.Info` 或 `shell.created` 事件）。
 * [sessionId] 来自服务端写入的 `metadata.sessionID`；后台 shell 不是会话，因此不在
 * `/api/session/active` 里，需要单独跟踪（对齐官方 `packages/app/src/session/requests/background.ts`）。
 */
data class ShellJob(
  val id: String,
  val sessionId: String,
  val command: String,
  val started: Long,
  val directory: String,
  /** 本机观测时间：目录读取失败时用于过期旧条目，避免事件丢失后“后台运行中”僵死。 */
  val observedAt: Long = System.currentTimeMillis()
)

enum class TaskPhase { IDLE, THINKING, TOOL, SUBAGENT, TESTING, WAITING_PERMISSION, WAITING_QUESTION, WAITING_SERVER, COMPLETED, FAILED, ABORTED, DISCONNECTED }
data class TaskState(
  val sessionId: String,
  val phase: TaskPhase,
  val detail: String = "",
  val since: Long = System.currentTimeMillis(),
  val finishedAt: Long? = null,
  /**
   * 服务端时间基线：本轮开始时会话的 `time.idle`（由前台读取得到）或 `session.execution.started`
   * 的 `created`。对账时若 `会话.time.idle > activeAt`，说明本轮已结束。−1 表示未知（例如旧版本持久化的
   * 任务、或刚发送还没拿到服务端信号），此时不用对账收尾，只认终止事件。
   */
  val activeAt: Long = -1,
  /**
   * 该会话所属家族仍有“根执行结束后会重新唤醒会话”的后台工作：运行中的后台 shell、或仍在执行的
   * 子代理会话。此时即使根执行已经 settle（或已由 [BackgroundWork.fold] 折算），也应继续显示
   * “后台运行中”，直到后台工作完成、服务端重新唤醒 AI（新的 `session.execution.started`）。
   * 旧的“用户显式转后台”合成消息标记也仍会置位这里（[TaskReducer.BACKGROUND_MARKER]）。
   */
  val background: Boolean = false
) {
  val active: Boolean get() = when (phase) {
    TaskPhase.THINKING, TaskPhase.TOOL, TaskPhase.SUBAGENT, TaskPhase.TESTING, TaskPhase.WAITING_PERMISSION, TaskPhase.WAITING_QUESTION, TaskPhase.WAITING_SERVER -> true
    else -> false
  }

  companion object {
    /** The single user-facing label for every running phase (thinking/tool/subagent/testing alike). */
    const val RUNNING_DETAIL = "运行中"
    /** Detail shown while a backgrounded tool/subagent keeps the session alive after its own run settled. */
    const val BACKGROUND_DETAIL = "后台任务运行中"
    /** Phases where the agent is actively working, without an outstanding user prompt. */
    val RUNNING_PHASES: Set<TaskPhase> = setOf(TaskPhase.THINKING, TaskPhase.TOOL, TaskPhase.SUBAGENT, TaskPhase.TESTING)
    /** Phases waiting on a user decision. */
    val WAITING_PHASES: Set<TaskPhase> = setOf(TaskPhase.WAITING_PERMISSION, TaskPhase.WAITING_QUESTION, TaskPhase.WAITING_SERVER)
    /** Union of [RUNNING_PHASES] and [WAITING_PHASES], matching [active]. */
    val ACTIVE_PHASES: Set<TaskPhase> = RUNNING_PHASES + WAITING_PHASES
    /** Terminal phases that a run never leaves on its own. */
    val TERMINAL_PHASES: Set<TaskPhase> = setOf(TaskPhase.COMPLETED, TaskPhase.FAILED, TaskPhase.ABORTED)
  }
}

internal fun JSONObject.str(key: String): String = optString(key).takeUnless { it == "null" } ?: ""
internal fun JSONObject.obj(key: String): JSONObject = optJSONObject(key) ?: JSONObject()
internal fun JSONObject.arr(key: String): JSONArray = optJSONArray(key) ?: JSONArray()
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
internal fun JSONObject.longPath(parent: String, child: String): Long = obj(parent).optLong(child, 0)

/** `session.created` has appeared both as a direct Session.Info and as `{info: Session.Info}`. */
internal fun JSONObject.sessionInfo(): JSONObject = obj("info").takeIf { it.length() > 0 } ?: this

/** Extracts the session carrying an event without treating an unrelated event `id` as a session id. */
internal fun JSONObject.sessionEventId(type: String): String {
  val direct = str("sessionID").ifBlank { obj("form").str("sessionID") }
  if (direct.isNotBlank()) return direct
  if (type != "session.created") return ""
  return sessionInfo().str("id").ifBlank { str("id") }
}

internal fun JSONObject.errorMessage(key: String = "error"): String {
  val direct = str("message")
  if (direct.isNotBlank()) return direct
  val value = opt(key)
  if (value is JSONObject) {
    return value.str("message").ifBlank { value.obj("data").str("message") }
  }
  return value?.toString().orEmpty().takeUnless { it == "null" }.orEmpty()
}

internal fun JSONObject.valueText(key: String): String {
  val value = opt(key) ?: return ""
  return when (value) {
    is JSONObject -> value.toString(2)
    is JSONArray -> value.toString(2)
    JSONObject.NULL -> ""
    else -> value.toString()
  }
}

internal fun JSONObject.toProject(): Project {
  val directory = str("canonical").ifBlank { str("worktree").ifBlank { str("directory") } }
  val sandboxes = arr("sandboxes").strings()
  return Project(str("id"), directory, str("name").ifBlank { directory.replace('\\', '/').trimEnd('/').substringAfterLast('/').ifBlank { directory } }, sandboxes)
}
internal fun JSONObject.toSession(): Session {
  val info = sessionInfo()
  val root = info.obj("location").str("directory").let { path -> info.str("subpath").takeIf { it.isNotBlank() }?.let { "${path.trimEnd('/')}/${it.trim('/')}" } ?: path }
    .ifBlank { info.str("directory") }
  return Session(
    info.str("id"), root,
    info.str("title"), info.longPath("time", "updated"), info.str("parentID").ifBlank { null },
    info.str("projectID").ifBlank { info.obj("location").obj("project").str("id") }.ifBlank { null },
    info.longPath("time", "created"), info.obj("time").opt("archived") is Number,
    info.str("agent").ifBlank { null }, info.obj("model").toModelChoice(),
    info.longPath("time", "viewed"), info.longPath("time", "idle"),
    info.str("outcome").ifBlank { null },
    info.obj("revert").str("messageID").ifBlank { null }
  )
}
/** `Model.Ref` (`{providerID, id, variant?}`); `modelID` is accepted for metadata written by older clients. */
internal fun JSONObject.toModelChoice(): ModelChoice? {
  val provider = str("providerID")
  val model = str("id").ifBlank { str("modelID") }
  return if (provider.isBlank() || model.isBlank()) null else ModelChoice(provider, model, str("name").ifBlank { model }, str("variant").ifBlank { null })
}
internal fun JSONArray.toAttachments(): List<Attachment> = objects().mapNotNull {
  val url = it.str("uri").ifBlank { it.str("url") }.ifBlank { it.str("path") }
  if (url.isBlank()) null else Attachment(url, it.str("mime").ifBlank { it.str("mediaType") }, it.str("name").ifBlank { it.str("filename") })
}
internal fun JSONArray.contentText(): String = objects().mapNotNull { item ->
  item.str("text").takeIf { item.str("type") != "file" && it.isNotBlank() }
}.joinToString("\n")

/** Parses one `Session.Message.Info`. */
internal fun JSONObject.toMessage(): Message {
  val id = str("id")
  val created = longPath("time", "created")
  return when (val type = str("type")) {
    "user" -> userMessage(id, created, this, obj("metadata"), queued = false)
    "assistant" -> {
      val ordinals = mutableMapOf("text" to 0, "reasoning" to 0)
      val parts = arr("content").objects().map { content ->
        val kind = content.str("type")
        // Official part ids: tool calls keep their call id; text/reasoning are `<message>:<type>:<ordinal>`.
        val partId = if (kind == "tool") content.str("id") else "$id:$kind:${ordinals.merge(kind, 1, Int::plus)!! - 1}"
        content.toAssistantPart(partId)
      }
      val error = optJSONObject("error")
      val retry = optJSONObject("retry")
      Message(id, "assistant", created, parts, error?.str("message")?.ifBlank { null }, str("agent").ifBlank { null }, obj("model").toModelChoice(),
        longPath("time", "completed").takeIf { it > 0 }, str("finish").ifBlank { null }, error?.str("type")?.ifBlank { null },
        retry?.let { "第 ${it.optInt("attempt")} 次重试：${it.obj("error").str("message")}" }, type = type)
    }
    "shell" -> Message(id, "shell", created, listOf(shellPart(id, str("command"), str("status"), opt("exit") as? Number, obj("output").str("output"))
      .copy(target = str("shellID").ifBlank { null })),
      completedAt = longPath("time", "completed").takeIf { it > 0 }, type = type)
    "idle" -> Message(id, "hidden", created, emptyList(), type = type)
    else -> noticeMessage(id, created, type, this)
  }
}

/** A pending `/inbox` item rendered like the official transcript does before delivery. */
internal fun JSONObject.toInboxMessage(): Message? {
  val payload = obj("payload")
  val created = longPath("time", "created")
  val queued = str("delivery") == "queue"
  return when (str("type")) {
    "user" -> userMessage(str("id"), created, payload, payload.obj("metadata"), queued)
    "synthetic" -> noticeMessage(str("id"), created, "synthetic", payload).copy(queued = queued)
    else -> null
  }
}

private fun userMessage(id: String, created: Long, source: JSONObject, metadata: JSONObject, queued: Boolean): Message {
  // The official composer records what the user typed in `metadata.displayText`; `text` may carry
  // expanded context (file contents, comments) written for the model.
  val text = metadata.str("displayText").ifBlank { source.str("text") }
  val parts = listOfNotNull(text.takeIf(String::isNotBlank)?.let { MessagePart("$id:text", "text", text = it) }) +
    source.arr("files").toAttachments().mapIndexed { index, attachment ->
      MessagePart("$id:file:$index", "file", path = attachment.url, title = attachment.name, mime = attachment.mime)
    }
  val agent = metadata.str("agent").ifBlank { null }
  return Message(id, "user", created, parts, agent = agent, model = metadata.obj("model").toModelChoice(), queued = queued, type = "user")
}

private fun shellPart(id: String, command: String, status: String, exit: Number?, output: String): MessagePart {
  val failed = status == "timeout" || status == "killed" || (status == "exited" && exit != null && exit.toInt() != 0)
  return MessagePart("$id:shell", "tool", tool = "shell", input = JSONObject().put("command", command).toString(),
    status = when { status == "running" -> "running"; failed -> "error"; else -> "completed" }, output = output,
    error = if (failed) "退出码 ${exit ?: status}" else "")
}

/** Official `notice()` presentation for every non-user/assistant/shell/idle message type. */
private fun noticeMessage(id: String, created: Long, type: String, source: JSONObject): Message {
  val metadata = source.obj("metadata")
  val description = source.str("description")
  val notice: Notice? = when (type) {
    "system" -> {
      val sources = metadata.arr("instructionSources").strings()
      val prefix = "Instructions updated: "
      when {
        metadata.str("notice") == "instructions" && sources.isNotEmpty() -> Notice("指令已更新", items = sources)
        description.startsWith(prefix) -> Notice("指令已更新", items = description.removePrefix(prefix).split(',').map(String::trim).filter(String::isNotBlank))
        description.isNotBlank() -> Notice(description)
        // Never surface the model-facing text itself; it can be an entire tool catalog.
        else -> Notice("系统更新")
      }
    }
    "synthetic" -> {
      val state = metadata.str("state")
      val source = metadata.str("source")
      val shellFailed = source == "shell" && state == "completed" && (metadata.optBoolean("timeout") || (metadata.opt("exit") as? Number)?.toInt()?.let { it != 0 } == true)
      val required = state == "error" || shellFailed
      when {
        metadata.str("notice") == "restart" || description == "Continuing after restart" -> Notice("重启后继续")
        source == "subagent" || source == "shell" -> {
          val actor = if (source == "shell") "Shell" else metadata.str("agent").ifBlank { "智能体" }
          val label = when (state) { "error" -> "$actor 失败"; "cancelled" -> "$actor 已取消"; else -> "$actor 已完成" }
          Notice(label, description, target = metadata.str("childID").takeIf { source == "subagent" && it.isNotBlank() }, error = required)
        }
        // Official: synthetic input without a description is not a transcript row.
        description.isBlank() && !required -> null
        else -> Notice(description.ifBlank { "执行失败" }, error = required)
      }
    }
    "skill" -> Notice("技能", source.str("name"))
    "agent-switched" -> Notice("代理变更", listOfNotNull(source.str("previous").ifBlank { null }, source.str("agent")).joinToString(" → "))
    "model-switched" -> Notice("已切换到 ${source.obj("model").str("id")}")
    "location-switched" -> Notice("已移至", source.obj("location").str("directory"))
    "compaction" -> when (source.str("status")) {
      "running" -> Notice("正在整理上下文", running = true)
      "failed" -> Notice("上下文整理失败", source.obj("error").str("message"), error = true)
      else -> Notice("上下文已整理")
    }
    else -> null
  }
  return Message(id, if (notice == null) "hidden" else "notice", created, emptyList(), notice = notice,
    agent = source.str("agent").ifBlank { null }.takeIf { type == "agent-switched" },
    model = source.obj("model").toModelChoice().takeIf { type == "model-switched" }, type = type)
}

/** Projects one `Session.Message.Assistant` content item. */
internal fun JSONObject.toAssistantPart(id: String): MessagePart {
  val type = str("type")
  if (type != "tool") return MessagePart(id, type, text = str("text"), status = if (type == "reasoning" && longPath("time", "completed") == 0L && obj("time").length() > 0) "running" else "")
  val state = obj("state")
  val metadata = state.obj("metadata")
  val name = str("name")
  val input = state.opt("input")
  val inputObject = input as? JSONObject
  val diffs = metadata.arr("files").objects()
  var status = state.str("status")
  val background = status == "completed" && metadata.str("status") == "running"
  // Shell completion reports the process outcome in metadata, not the tool status (official `shellResultFailed`).
  val shellFailed = name in SHELL_TOOLS && status == "completed" && (metadata.optBoolean("timeout") || (metadata.opt("exit") as? Number)?.toInt()?.let { it != 0 } == true)
  if (shellFailed) status = "error"
  val content = state.arr("content")
  return MessagePart(
    id = id, type = "tool", tool = name,
    title = inputObject?.str("description").orEmpty(),
    status = if (status == "streaming") "pending" else status,
    input = when (input) { is JSONObject -> input.toString(2); null, JSONObject.NULL -> ""; else -> input.toString() },
    output = content.contentText(),
    path = inputObject?.str("path").orEmpty(),
    error = state.optJSONObject("error")?.str("message").orEmpty().ifBlank { if (shellFailed) "退出码 ${metadata.opt("exit") ?: "超时"}" else "" },
    patch = diffs.joinToString("\n") { it.str("patch") }.trim(),
    files = diffs.map { it.str("file") }.filter(String::isNotBlank),
    attachments = content.objects().filter { it.str("type") == "file" }.let { JSONArray(it) }.toAttachments(),
    target = (metadata.str("sessionID").ifBlank { inputObject?.str("sessionID").orEmpty() }).takeIf { name in SUBAGENT_TOOLS && it.isNotBlank() },
    background = background
  )
}

internal val SHELL_TOOLS = setOf("shell", "bash", "execute")
internal val SUBAGENT_TOOLS = setOf("subagent", "task")

internal fun JSONObject.toPermission(directory: String): PermissionRequest {
  val resources = arr("resources")
  val detail = when {
    resources.length() > 0 -> resources.strings().joinToString("\n")
    str("message").isNotBlank() -> str("message")
    else -> obj("metadata").toString()
  }
  val source = obj("source")
  return PermissionRequest(
    str("id"), str("sessionID"), directory, str("action"), detail,
    arr("save").strings(), source.str("messageID"), source.str("id")
  )
}
/** `FileDiff.Info` from `GET /api/session/{id}/diff`. */
internal fun JSONObject.toChange(): FileChange = FileChange(
  path = str("file").ifBlank { str("path") }, after = str("after"), additions = optInt("additions"), deletions = optInt("deletions"),
  patch = str("patch")
)

/**
 * One running shell (`Shell.Info`). The server's `shell.list` already returns running commands only;
 * the guard keeps `shell.created` and defensive parsing on the same contract.
 */
internal fun JSONObject.toShellJob(directory: String): ShellJob? {
  val id = str("id")
  val sessionId = obj("metadata").str("sessionID")
  if (id.isBlank() || sessionId.isBlank() || str("status") != "running") return null
  return ShellJob(id, sessionId, str("command"), obj("time").optLong("started"), directory)
}

object TaskReducer {
  private val TEST_COMMAND = Regex("(?i)(test|gradle|pytest|vitest|jest)")

  /** 服务端把阻塞工具/子代理转入后台时写入会话的合成消息标记（`session.synthetic`）。 */
  private const val BACKGROUND_MARKER = "backgrounded work is still unfinished"

  /**
   * 前台活跃读取（`/api/session/active`）只覆盖前台运行，**不在其中不等于本轮结束**：转入后台的
   * 阻塞工具/子代理会让会话继续存活，但其 execution 已经 settle。因此这里只在 [foreground]=true 时
   * 推进运行态；[foreground]=false 时保留既有活跃态，收尾交给终止事件或 [terminal]。
   * [idleBaseline] 是本次读取到会话的 `time.idle`，作为新一轮的收尾基线（见 [TaskState.activeAt]）。
   */
  fun status(sessionId: String, foreground: Boolean, previous: TaskState? = null, idleBaseline: Long = -1): TaskState = when {
    // WAITING 具有粘性：服务器仍在执行时不因一次通用“活跃”读取降级，避免权限/表单抖动。
    foreground && previous?.phase in TaskState.WAITING_PHASES -> previous!!
    foreground -> {
      val continuing = previous?.active == true
      TaskState(sessionId, if (continuing) previous!!.phase else TaskPhase.THINKING, TaskState.RUNNING_DETAIL,
        if (continuing) previous!!.since else System.currentTimeMillis(),
        activeAt = if (continuing && previous!!.activeAt >= 0) previous!!.activeAt else idleBaseline,
        // 会话自身在执行中一律是“运行中”；后台工作只在其 settle 后决定是否折算为“后台运行中”。
        background = false)
    }
    previous?.active == true -> previous!!
    previous?.phase in TaskState.TERMINAL_PHASES -> previous!!
    else -> TaskState(sessionId, TaskPhase.IDLE)
  }

  /**
   * 用服务端持久化的 `outcome` 收尾一轮：对账兜底，覆盖断线期间漏掉的终止事件。
   * [TaskState.activeAt] 是服务端时间，与会话自身的 `time.idle` 同源，因此不受手机与服务器时钟差影响。
   */
  fun terminal(sessionId: String, outcome: String?, previous: TaskState?): TaskState {
    val since = previous?.since ?: System.currentTimeMillis()
    val finishedAt = previous?.activeAt?.takeIf { it > 0 } ?: System.currentTimeMillis()
    return when (outcome) {
      "failed" -> TaskState(sessionId, TaskPhase.FAILED, "执行失败", since, finishedAt)
      "interrupted" -> TaskState(sessionId, TaskPhase.ABORTED, "任务已停止", since, finishedAt)
      else -> TaskState(sessionId, TaskPhase.COMPLETED, "任务已完成", since, finishedAt)
    }
  }

  private fun running(sessionId: String, phase: TaskPhase, previous: TaskState?, since: Long, at: Long): TaskState? =
    if (previous?.phase in TaskState.TERMINAL_PHASES || previous?.phase in TaskState.WAITING_PHASES) previous
    else TaskState(sessionId, phase, TaskState.RUNNING_DETAIL, since, activeAt = at, background = previous?.background == true)

  private fun waiting(sessionId: String, phase: TaskPhase, detail: String, previous: TaskState?, since: Long): TaskState =
    TaskState(sessionId, phase, detail, since, activeAt = previous?.activeAt ?: -1L, background = previous?.background == true)

  /** 显式 idle 信号（`session.idle` / `session.status` idle）：权威收尾一轮；后台工作未完成时保持运行。 */
  private fun idle(sessionId: String, previous: TaskState?, backgroundWork: Boolean): TaskState? = when {
    previous == null -> null
    previous.background -> previous
    // 后台工作派生（官方 background 算法）显示还有 shell/子会话在跑：折算为“后台运行中”而不是收尾。
    backgroundWork -> TaskState(sessionId, TaskPhase.THINKING, TaskState.BACKGROUND_DETAIL, previous.since,
      activeAt = previous.activeAt.takeIf { it > 0 } ?: System.currentTimeMillis(), background = true)
    previous.active -> TaskState(sessionId, TaskPhase.COMPLETED, "任务已完成", previous.since,
      previous.activeAt.takeIf { it > 0 } ?: System.currentTimeMillis())
    else -> null
  }

  /** Reduces one `/api/event` frame; null means the event does not change the task phase. */
  fun event(sessionId: String, type: String, properties: JSONObject, previous: TaskState?, timestamp: Long = 0,
    backgroundWork: Boolean = false): TaskState? {
    val since = previous?.since ?: System.currentTimeMillis()
    val at = timestamp.takeIf { it > 0 } ?: System.currentTimeMillis()
    return when (type) {
      "session.execution.started" -> status(sessionId, true, previous?.takeIf { it.active }, at).copy(background = false)
      // 后台工作未完成时，根执行 settle 不代表会话结束：继续显示“后台运行中”，等后台完成唤醒 AI。
      // [backgroundWork] 是控制器按官方算法派生的实时结果（后台 shell / 子代理），[previous.background]
      // 保留“用户显式转后台”合成消息的标记路径。
      "session.execution.succeeded" -> if (backgroundWork || previous?.background == true)
        TaskState(sessionId, TaskPhase.THINKING, TaskState.BACKGROUND_DETAIL, since, activeAt = at, background = true)
        else TaskState(sessionId, TaskPhase.COMPLETED, "任务已完成", since, at)
      "session.execution.failed" -> TaskState(sessionId, TaskPhase.FAILED, properties.obj("error").str("message").ifBlank { "执行失败" }, since, at)
      // A shutdown keeps the execution claim; the restarted server resumes the same turn.
      "session.execution.interrupted" -> if (properties.str("reason") == "shutdown") previous
        else TaskState(sessionId, TaskPhase.ABORTED, "任务已停止", since, at)
      "session.status" -> when (properties.obj("status").str("type")) {
        "busy", "retry" -> running(sessionId, TaskPhase.THINKING, previous, since, at)
        "idle" -> idle(sessionId, previous, backgroundWork)
        else -> previous
      }
      "session.idle" -> idle(sessionId, previous, backgroundWork)
      "session.error" -> TaskState(sessionId, TaskPhase.FAILED,
        properties.errorMessage().ifBlank { properties.obj("error").str("message").ifBlank { "执行失败" } }, since, at)
      // 服务端把阻塞工具/子代理转入后台时写入的合成消息：标记后台工作，直到后台完成重新唤醒 AI。
      "session.synthetic" -> {
        val marker = properties.str("text").contains(BACKGROUND_MARKER, ignoreCase = true)
        val running = previous?.active == true
        if (!marker) null
        else TaskState(sessionId, if (running) previous!!.phase else TaskPhase.THINKING,
          if (running) previous!!.detail else TaskState.BACKGROUND_DETAIL, since, activeAt = at, background = true)
      }
      "permission.asked" -> waiting(sessionId, TaskPhase.WAITING_PERMISSION, "等待权限确认", previous, since)
      "form.created" -> waiting(sessionId, TaskPhase.WAITING_QUESTION, "等待你的回答", previous, since)
      "permission.replied", "form.replied", "form.cancelled" ->
        if (previous?.phase in TaskState.WAITING_PHASES)
          TaskState(sessionId, TaskPhase.THINKING, TaskState.RUNNING_DETAIL, since, activeAt = at, background = previous!!.background)
        else previous
      "session.step.started", "session.reasoning.started", "session.text.started", "session.retry.scheduled" ->
        running(sessionId, TaskPhase.THINKING, previous, since, at)
      "session.tool.called" -> {
        val tool = properties.str("name")
        val phase = when {
          tool in SUBAGENT_TOOLS -> TaskPhase.SUBAGENT
          tool in SHELL_TOOLS && TEST_COMMAND.containsMatchIn(properties.obj("input").str("command")) -> TaskPhase.TESTING
          else -> TaskPhase.TOOL
        }
        running(sessionId, phase, previous, since, at)
      }
      "session.tool.input.started" -> {
        val tool = properties.str("name")
        running(sessionId, if (tool in SUBAGENT_TOOLS) TaskPhase.SUBAGENT else TaskPhase.TOOL, previous, since, at)
      }
      else -> null
    }
  }
}
