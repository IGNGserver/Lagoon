package com.igng.opencode.lagoon.core

import kotlinx.coroutines.flow.Flow

/** 一台已保存服务器使用的 harness 协议。 */
enum class ServerKind(val wire: String, val label: String) {
  OPENCODE("opencode", "OpenCode"),
  DSH("dsh", "DeepSeek Harness");

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
interface HarnessClient {
  fun capabilities(): ApiCapabilities

  suspend fun discoverCapabilities(): ApiCapabilities

  /** 连通性探测；返回服务端可读的版本描述。 */
  suspend fun health(): String

  suspend fun projects(): List<Project>

  suspend fun session(id: String): Session

  suspend fun projectAt(directory: String): Project?

  suspend fun rootSessionsPage(directory: String? = null, cursor: String? = null, size: Int = 100): ApiPage<Session>

  suspend fun viewSession(session: Session, idle: Long)

  suspend fun messagesPage(id: String, cursor: String? = null, size: Int = 20): ApiPage<Message>

  suspend fun inbox(id: String): List<Message>

  suspend fun activeSessions(): Set<String>

  suspend fun shells(directory: String): List<ShellJob>

  suspend fun createSession(directory: String, title: String): Session

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

  /** 服务端事件流；[onOpen] 在流真正建立（握手成功）时回调一次。 */
  fun events(onOpen: () -> Unit = {}): Flow<ServerEvent>

  companion object {
    /** 一次消息翻页的默认条数。 */
    const val MESSAGE_PAGE = 20

    /** 一次历史回补（向更早方向）的默认条数。 */
    const val HISTORY_PAGE = 200
  }
}

/** 按服务器类型创建对应的客户端。 */
fun createHarnessClient(
  profile: ServerProfile,
  credentials: ServerCredentials,
  callTimeoutSeconds: Long = 60
): HarnessClient = when (profile.kind) {
  ServerKind.OPENCODE -> OpenCodeApi(profile, credentials, callTimeoutSeconds)
  ServerKind.DSH -> DshApi(profile, credentials, callTimeoutSeconds)
}
