package com.igng.opencode.lagoon.core

import kotlinx.coroutines.flow.Flow

/** 一台已保存服务器使用的 harness 协议。 */
enum class ServerKind(val wire: String, val label: String) {
  OPENCODE("opencode", "OpenCode"),
  DSH("dsh", "DeepSeek Harness"),
  DSH_REMOTE("dsh-remote", "DSH Remote");

  companion object {
    fun fromWire(value: String?): ServerKind =
      entries.firstOrNull { it.wire == value?.trim()?.lowercase() } ?: OPENCODE
  }
}

/**
 * 一个 harness 客户端。
 *
 * OpenCode（[OpenCodeApi]）与 DeepSeek Harness（[DshApi]）各自实现同一套方法面，
 * 控制器与 UI 因此完全与协议无关：读列表、发消息、中止、审批、事件流都走这里。
 */
sealed interface QueueAction {
  data class Edit(val text: String) : QueueAction
  data object Remove : QueueAction
  data object Steer : QueueAction
}

interface HarnessClient {
  /** Releases transport-owned sockets and cryptographic state; safe to call more than once. */
  fun close() = Unit

  /** True after [close] or after the shared Remote session was released. */
  val isClosed: Boolean get() = false

  fun capabilities(): ApiCapabilities

  suspend fun discoverCapabilities(): ApiCapabilities

  /** 连通性探测；返回服务端可读的版本描述。 */
  suspend fun health(): String

  suspend fun projects(): List<Project>

  /**
   * 行内展示用的项目/工作区标题，绝不参与筛选、计数或新建会话目标。
   * 已经持续维护工作区清单的 harness 直接返回本地清单，不额外开流；其余可以按需做一次廉价读取。
   */
  suspend fun projectsForDisplay(): List<Project> = projects()

  suspend fun session(id: String): Session

  suspend fun projectAt(directory: String): Project?

  suspend fun rootSessionsPage(directory: String? = null, cursor: String? = null, size: Int = 100): ApiPage<Session>

  suspend fun viewSession(session: Session, idle: Long)

  suspend fun messagesPage(id: String, cursor: String? = null, size: Int = 20): ApiPage<Message>

  /**
   * True when [messageRecords] returns each message's wire form and the protocol pages a stable,
   * server-ordered timeline, so the device may keep a verified copy (see [TranscriptSync]).
   */
  val cacheableTranscripts: Boolean get() = false

  /** [messagesPage] with each message's wire JSON when [cacheableTranscripts]; null `raw` otherwise. */
  suspend fun messageRecords(id: String, cursor: String? = null, size: Int = 20): ApiPage<MessageRecord> =
    messagesPage(id, cursor, size).let { page -> ApiPage(page.items.map { MessageRecord(it, null) }, page.next) }

  suspend fun inbox(id: String): List<Message>

  /**
   * [inbox] plus the conversation's durable todo list and goal, when the harness projects them.
   * DSH reads all three from one `session/projections` round trip, so opening a conversation costs
   * no extra request; other harnesses fall back to [inbox] alone.
   */
  suspend fun composerContext(id: String): ComposerContext = ComposerContext(inbox = inbox(id))

  /** Edits/removes/steers a still-queued prompt. False when the harness has no queue RPC. */
  suspend fun updateQueue(sessionId: String, itemId: String, action: QueueAction): Boolean = false

  /**
   * Streams one background job's output (`job/follow`), appending text and reporting running/finished.
   * Returns false when the harness has no such stream.
   */
  suspend fun followJobOutput(
    sessionId: String,
    jobId: String,
    onUpdate: (text: String, running: Boolean, finished: Boolean) -> Unit
  ): Boolean = false

  /** One encrypted RPC round trip, when the harness has a remote diagnostic channel. */
  suspend fun probeRemote(): RemoteProbe? = null

  /** Sends one follow-up to a continuable subagent child. False when unsupported. */
  suspend fun promptSubagent(parentId: String, childId: String, text: String): Boolean = false

  /** Official plugin inventory; empty when the harness has no plugin channel. */
  suspend fun pluginBundles(): List<RemotePluginBundle> = emptyList()
  suspend fun pluginEntries(): List<RemotePluginEntry> = emptyList()
  suspend fun setPluginBundle(name: String, enabled: Boolean): Boolean = false
  suspend fun setPluginEntry(entryId: String, enabled: Boolean): Boolean = false

  /** Host credential metadata; values are never returned. */
  suspend fun credentialStatus(refs: List<String>): Map<String, RemoteCredentialStatus> = emptyMap()
  suspend fun setCredential(ref: String, value: String): Boolean = false
  suspend fun clearCredential(ref: String): Boolean = false

  /** Session goal, when the harness exposes durable goals. */
  suspend fun goal(sessionId: String): RemoteGoal? = null
  suspend fun createGoal(sessionId: String, objective: String, maxRounds: Int? = null): Boolean = false
  suspend fun goalAction(sessionId: String, id: String, revision: Int, action: String): Boolean = false

  /** PDF/image bytes, or Office converted to PDF; null when the harness cannot preview this path. */
  suspend fun previewFile(directory: String, path: String, session: Session?): FilePreview? = null

  /** Host automation reminders bound to this Session; read-only list. */
  suspend fun schedules(sessionId: String): List<RemoteSchedule> = emptyList()

  /** Creates one custom model route in the Host's pi-ai settings; false when unsupported. */
  suspend fun createProviderRoute(
    route: String, displayName: String, api: String, baseUrl: String,
    apiKeyEnv: String, modelId: String, apiKey: String
  ): Boolean = false

  /** Includes admitted internal inputs (e.g. compaction/move) which have no transcript row. */
  suspend fun hasPendingInput(id: String): Boolean = inbox(id).isNotEmpty()

  suspend fun activeSessions(): Set<String>

  suspend fun shells(directory: String): List<ShellJob>

  /**
   * One shell command including its terminal status. OpenCode exposes this as `GET /api/shell/{id}`;
   * harnesses without a shell route return null and the UI hides the output action.
   */
  suspend fun shellInfo(id: String, directory: String): ShellSnapshot? = null

  /** Captured output page at an absolute byte cursor. Null when the harness has no shell output route. */
  suspend fun shellOutput(id: String, directory: String, cursor: Long? = null, limit: Int = SHELL_OUTPUT_PAGE): ShellOutput? = null

  /** Terminate/remove a shell command and its retained output. */
  suspend fun removeShell(id: String, directory: String) = Unit

  /**
   * Creates a session. [workspaceId] is the server's workspace identity for DSH hosts: without it
   * the Host creates an ungrouped session that other clients cannot see. Harnesses with a single
   * directory namespace ignore it.
   */
  suspend fun createSession(directory: String, title: String, workspaceId: String? = null): Session

  suspend fun renameSession(session: Session, title: String)

  suspend fun setArchived(session: Session, archived: Boolean, now: Long = System.currentTimeMillis())

  suspend fun deleteSession(session: Session)

  suspend fun forkSession(session: Session): Session

  suspend fun abort(session: Session)

  suspend fun compact(session: Session)

  suspend fun revert(session: Session, messageId: String)

  suspend fun unrevert(session: Session)

  suspend fun send(
    session: Session,
    text: String,
    agent: String? = null,
    model: ModelChoice? = null,
    references: List<FileReference> = emptyList(),
    inline: List<InlineFile> = emptyList(),
    id: String = MessageIds.ascending(),
    delivery: Delivery = Delivery.STEER
  )

  suspend fun command(
    session: Session,
    name: String,
    arguments: String,
    agent: String? = null,
    model: ModelChoice? = null,
    references: List<FileReference> = emptyList(),
    inline: List<InlineFile> = emptyList(),
    delivery: Delivery = Delivery.STEER
  )

  suspend fun agents(directory: String): List<AgentChoice>

  suspend fun commands(directory: String): List<CommandChoice>

  suspend fun modelCatalog(directory: String): List<ModelInfo>

  /**
   * 可选的权限模式目录（DSH：`permissionPresets/catalog`）。空列表＝该 harness 没有这个概念，
   * 界面不显示选择器。
   */
  suspend fun permissionPresets(directory: String): List<PermissionPresetChoice> = emptyList()

  /** 切换一个会话的权限模式（DSH：执行 `/permission <value>`）；不支持的 harness 返回 false。 */
  suspend fun setPermission(session: Session, value: String): Boolean = false

  /** 服务端记录的置顶会话（DSH：`workspace/follow` 的 `pinnedSessionIds`）；null＝该 harness 没有。 */
  suspend fun serverPinnedSessions(): Set<String>? = null

  /** 把置顶同步到服务端（DSH：`workspace/pinSession` / `unpinSession`）；不支持返回 false。 */
  suspend fun setSessionPinned(session: Session, pinned: Boolean): Boolean = false

  suspend fun sessionPermissions(session: Session): List<PermissionRequest>

  suspend fun pendingPermissions(directory: String): List<PermissionRequest>

  suspend fun forms(directory: String): List<QuestionRequest>

  suspend fun replyPermission(request: PermissionRequest, reply: String)

  suspend fun replyQuestion(request: QuestionRequest, answers: List<List<String>>)

  suspend fun rejectQuestion(request: QuestionRequest)

  suspend fun children(session: Session): List<Session>

  suspend fun diff(session: Session): List<FileChange>

  /**
   * 工作区文件浏览。[session] 是可选的文件访问身份：DSH 的 `workspaceFiles` 需要会话
   * （`workspaceFileScopeId`），OpenCode 按目录读取、忽略该参数。
   */
  suspend fun files(directory: String, path: String, session: Session? = null): List<FileNode>

  suspend fun fileContent(directory: String, path: String, session: Session? = null): FileContent

  suspend fun searchFiles(directory: String, query: String, session: Session? = null): List<String>

  /**
   * Full content subscription. Only the selected connection may watch content; background monitors
   * call [setContentWatching] with `false` so they keep the control stream but do not open a
   * `session/follow` for every running session.
   */
  fun events(onOpen: () -> Unit = {}): Flow<ServerEvent>

  /**
   * Enables or disables the expensive half of [events] (workspace stream and per-session follows).
   * The selected harness is the only one that needs it; background monitoring only needs control
   * events (running state, permissions, results). Default: no-op for harnesses whose event stream is
   * already cheap.
   */
  fun setContentWatching(enabled: Boolean) {}

  /**
   * Live server workspace titles, when the harness maintains them. The controller uses this to make
   * a newly created/renamed workspace selectable without waiting for the next catalog reconciliation.
   */
  val workspaceUpdates: Flow<List<Project>>? get() = null

  /** Low-cardinality transport step for the connection strip (SSH bootstrap, relay handshake). */
  fun transportStatus(): String? = null

  companion object {
    /** 一次消息翻页的默认条数。 */
    const val MESSAGE_PAGE = 20

    /** 一次历史回补（向更早方向）的默认条数。 */
    const val HISTORY_PAGE = 200

    /** 后台 shell 输出一次读取的字节数；界面只保留最近一页，避免长任务占满内存。 */
    const val SHELL_OUTPUT_PAGE = 64 * 1024
  }
}

/** One timeline message and, when the protocol has one, its wire JSON exactly as the server sent it. */
data class MessageRecord(val message: Message, val raw: org.json.JSONObject?)

/**
 * Durable values the open conversation shows beside its composer.
 *
 * [todos] is null when the harness exposes no `todos` projection (an older Host, or another
 * harness); an empty list means the Host cleared the list. [goal] has no process-local activation:
 * that only comes from a live `goals/get` read.
 */
data class ComposerContext(
  val inbox: List<Message> = emptyList(),
  val todos: List<RemoteTodo>? = null,
  val goal: RemoteGoal? = null
)

/** 按服务器类型创建对应的客户端。 */
fun createHarnessClient(
  profile: ServerProfile,
  credentials: ServerCredentials,
  callTimeoutSeconds: Long = 60,
  /** 客户端换到新会话 Cookie（或确认旧 Cookie 失效，传空串）时回调，由调用方落盘。 */
  onCookie: (String) -> Unit = {},
  remoteBackend: RemoteBackend? = null,
  /** App-provided factory for `sshHostId != null`; shared/ has no Android SSH implementation. */
  sshClientFactory: ((ServerProfile, ServerCredentials) -> HarnessClient)? = null
): HarnessClient = when (profile.kind) {
  ServerKind.OPENCODE -> OpenCodeApi(profile, credentials, callTimeoutSeconds)
  ServerKind.DSH -> if (profile.sshHostId != null) {
    sshClientFactory?.invoke(profile, credentials) ?: throw IllegalStateException("SSH backend 尚未配置")
  } else DshApi(profile, credentials, callTimeoutSeconds, onCookie)
  ServerKind.DSH_REMOTE -> (remoteBackend ?: throw IllegalStateException("DSH Remote backend 尚未配置")).client
}
