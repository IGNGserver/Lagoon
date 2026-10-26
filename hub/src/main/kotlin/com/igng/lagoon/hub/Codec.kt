package com.igng.lagoon.hub

import com.igng.opencode.lagoon.core.AgentChoice
import com.igng.opencode.lagoon.core.ApiCapabilities
import com.igng.opencode.lagoon.core.Attachment
import com.igng.opencode.lagoon.core.CommandChoice
import com.igng.opencode.lagoon.core.DshAuthMode
import com.igng.opencode.lagoon.core.FileChange
import com.igng.opencode.lagoon.core.FileContent
import com.igng.opencode.lagoon.core.FileNode
import com.igng.opencode.lagoon.core.Message
import com.igng.opencode.lagoon.core.MessagePart
import com.igng.opencode.lagoon.core.ModelChoice
import com.igng.opencode.lagoon.core.ModelInfo
import com.igng.opencode.lagoon.core.Notice
import com.igng.opencode.lagoon.core.PermissionPolicy
import com.igng.opencode.lagoon.core.PermissionRequest
import com.igng.opencode.lagoon.core.Project
import com.igng.opencode.lagoon.core.QuestionRequest
import com.igng.opencode.lagoon.core.RefreshMode
import com.igng.opencode.lagoon.core.ServerKind
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.Session
import com.igng.opencode.lagoon.core.SessionAction
import com.igng.opencode.lagoon.core.SessionNotice
import com.igng.opencode.lagoon.core.ShellJob
import com.igng.opencode.lagoon.core.TaskPhase
import com.igng.opencode.lagoon.core.TaskState
import com.igng.opencode.lagoon.core.TaskSummary
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire form of the shared domain types for the web client. Field names follow the Kotlin models so the
 * TypeScript types in `web/src/api/types.ts` read the same as the Android code they mirror.
 */
object Codec {
  fun array(items: Iterable<Any?>): JSONArray = JSONArray().apply { items.forEach { put(it ?: JSONObject.NULL) } }

  fun profile(profile: ServerProfile): JSONObject = JSONObject()
    .put("id", profile.id).put("name", profile.name).put("url", profile.url).put("username", profile.username)
    .put("autoConnect", profile.autoConnect).put("notifications", profile.notifications)
    .put("allowCleartext", profile.allowCleartext).put("kind", profile.kind.wire).put("dshAuth", profile.dshAuth.wire)

  fun profileFrom(json: JSONObject, id: String): ServerProfile = ServerProfile(
    id = id,
    name = json.optString("name").trim(),
    url = json.optString("url").trim().trimEnd('/'),
    username = json.optString("username").trim(),
    autoConnect = json.optBoolean("autoConnect", true),
    notifications = json.optBoolean("notifications", true),
    allowCleartext = json.optBoolean("allowCleartext", false),
    kind = ServerKind.fromWire(json.optString("kind")),
    dshAuth = DshAuthMode.fromWire(json.optString("dshAuth"))
  )

  fun model(model: ModelChoice?): Any = model?.let {
    JSONObject().put("providerId", it.providerId).put("modelId", it.modelId).put("label", it.label).putOpt("variant", it.variant)
  } ?: JSONObject.NULL

  fun modelFrom(json: JSONObject?): ModelChoice? {
    if (json == null) return null
    val provider = json.optString("providerId")
    val model = json.optString("modelId")
    if (provider.isBlank() || model.isBlank()) return null
    return ModelChoice(provider, model, json.optString("label").ifBlank { model }, json.optString("variant").ifBlank { null })
  }

  fun project(project: Project): JSONObject = JSONObject()
    .put("id", project.id).put("directory", project.directory).put("name", project.name).put("sandboxes", array(project.sandboxes))

  fun session(session: Session): JSONObject = JSONObject()
    .put("id", session.id).put("directory", session.directory).put("title", session.title).put("updated", session.updated)
    .putOpt("parentId", session.parentId).putOpt("projectId", session.projectId).put("created", session.created)
    .put("archived", session.archived).putOpt("agent", session.agent).put("model", model(session.model))
    .put("viewed", session.viewed).put("idle", session.idle).putOpt("outcome", session.outcome)
    .putOpt("revertMessageId", session.revertMessageId)

  /** [TaskState.activeAt] is a reconciliation baseline that moves on every streamed event; the UI never reads it. */
  fun task(task: TaskState): JSONObject = JSONObject()
    .put("sessionId", task.sessionId).put("phase", task.phase.name).put("detail", task.detail).put("since", task.since)
    .putOpt("finishedAt", task.finishedAt).put("background", task.background).put("active", task.active)
    .put("uncertain", task.uncertain).put("pendingInput", task.pendingInput)

  fun notice(notice: SessionNotice): JSONObject = JSONObject()
    .put("id", notice.id).put("sessionId", notice.sessionId).put("time", notice.time).put("error", notice.error).put("viewed", notice.viewed)

  fun permission(request: PermissionRequest): JSONObject = JSONObject()
    .put("id", request.id).put("sessionId", request.sessionId).put("directory", request.directory).put("action", request.action)
    .put("detail", request.detail).put("always", array(request.always)).put("toolMessageId", request.toolMessageId).put("toolCallId", request.toolCallId)

  fun question(request: QuestionRequest): JSONObject = JSONObject()
    .put("id", request.id).put("sessionId", request.sessionId).put("directory", request.directory).put("form", request.form)
    .put("questions", array(request.questions.map { prompt ->
      JSONObject().put("title", prompt.title).put("multiple", prompt.multiple).put("custom", prompt.custom).put("field", prompt.field)
        .put("options", array(prompt.options.map { JSONObject().put("label", it.label).put("description", it.description).put("value", it.value) }))
    }))

  fun summary(summary: TaskSummary): JSONObject = JSONObject()
    .put("running", summary.running).put("completed", summary.completed).put("waiting", summary.waiting).put("failed", summary.failed)
    .put("background", summary.background).put("processing", summary.processing).putOpt("text", summary.text).put("countLine", summary.countLine)
    .put("items", array(summary.items.map {
      JSONObject().put("sessionId", it.sessionId).put("title", it.title).put("phase", it.phase.name).put("detail", it.detail)
        .putOpt("serverId", it.serverId).putOpt("serverName", it.serverName).put("since", it.since)
    }))

  fun shell(job: ShellJob): JSONObject = JSONObject()
    .put("id", job.id).put("sessionId", job.sessionId).put("command", job.command).put("started", job.started).put("directory", job.directory)

  fun capabilities(capabilities: ApiCapabilities): JSONObject = JSONObject()
    .put("rename", capabilities.supports(SessionAction.RENAME)).put("unrevert", capabilities.supports(SessionAction.UNREVERT))
    .put("diff", capabilities.diff).put("view", capabilities.sessionView != null).put("archive", capabilities.archive != null)
    .put("restore", capabilities.archive?.restorable == true).put("delete", capabilities.deleteSession).put("fork", capabilities.fork)

  fun attachment(attachment: Attachment): JSONObject = JSONObject().put("url", attachment.url).put("mime", attachment.mime).put("name", attachment.name)

  fun notice(notice: Notice?): Any = notice?.let {
    JSONObject().put("label", it.label).put("detail", it.detail).put("items", array(it.items)).putOpt("target", it.target)
      .put("error", it.error).put("running", it.running)
  } ?: JSONObject.NULL

  fun part(part: MessagePart): JSONObject = JSONObject()
    .put("id", part.id).put("type", part.type).put("text", part.text).put("tool", part.tool).put("title", part.title)
    .put("status", part.status).put("input", part.input).put("output", part.output).put("path", part.path).put("error", part.error)
    .put("patch", part.patch).put("files", array(part.files)).put("mime", part.mime)
    .put("attachments", array(part.attachments.map(::attachment))).putOpt("target", part.target).put("background", part.background)

  fun message(message: Message): JSONObject = header(message).put("parts", array(message.parts.map(::part)))

  /** Every message field except its parts; two messages with equal headers differ only in content. */
  fun header(message: Message): JSONObject = JSONObject()
    .put("id", message.id).put("role", message.role).put("created", message.created).putOpt("error", message.error)
    .putOpt("agent", message.agent).put("model", model(message.model)).putOpt("completedAt", message.completedAt)
    .putOpt("finish", message.finish).putOpt("errorType", message.errorType).putOpt("retry", message.retry)
    .put("notice", notice(message.notice)).put("queued", message.queued).put("type", message.type)

  fun modelInfo(info: ModelInfo): JSONObject = JSONObject()
    .put("choice", model(info.choice)).put("key", info.key).put("providerName", info.providerName).putOpt("family", info.family)
    .put("released", info.released).putOpt("imageInput", info.imageInput).put("deprecated", info.deprecated)
    .put("variants", array(info.variants.map { JSONObject().put("id", it.id).put("label", it.label) }))

  fun agent(agent: AgentChoice): JSONObject = JSONObject().put("name", agent.name).put("description", agent.description)
  fun command(command: CommandChoice): JSONObject = JSONObject().put("name", command.name).put("description", command.description)
  fun file(node: FileNode): JSONObject = JSONObject().put("path", node.path).put("type", node.type)
  fun content(content: FileContent): JSONObject = JSONObject().put("type", content.type).put("content", content.content)
  fun change(change: FileChange): JSONObject = JSONObject().put("path", change.path).put("after", change.after)
    .put("additions", change.additions).put("deletions", change.deletions).put("patch", change.patch)

  fun policy(policy: PermissionPolicy): JSONObject = JSONObject().put("hide", policy.hide).put("autoApprove", policy.autoApprove)

  fun refreshMode(mode: RefreshMode): String = mode.name

  fun phase(value: String?): TaskPhase? = TaskPhase.entries.firstOrNull { it.name == value }
}
