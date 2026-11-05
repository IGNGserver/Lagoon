package com.igng.opencode.lagoon.core.ssh

import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * A [HarnessClient] for one SSH-backed DSH host. Every call waits for the link to have an
 * authenticated DshApi; if the remote dsh process is replaced, the link hands out a new generation
 * and the next call transparently uses it. This keeps AllServersOverview's one-client-per-profile
 * contract while the transport underneath reconnects.
 */
internal class SshDshClient(
  private val link: SshHostLink,
  private val onClose: () -> Unit = {}
) : HarnessClient {
  private suspend fun api(): DshApi = link.awaitClient()

  override fun close() {
    link.close()
    onClose()
  }

  override fun capabilities(): ApiCapabilities = DshApi.TUNNEL_CAPABILITIES

  override suspend fun discoverCapabilities(): ApiCapabilities = api().discoverCapabilities()

  override suspend fun health(): String = api().health()

  override suspend fun projects(): List<Project> = api().projects()

  override suspend fun session(id: String): Session = api().session(id)

  override suspend fun projectAt(directory: String): Project? = api().projectAt(directory)

  override suspend fun rootSessionsPage(directory: String?, cursor: String?, size: Int): ApiPage<Session> =
    api().rootSessionsPage(directory, cursor, size)

  override suspend fun viewSession(session: Session, idle: Long) = api().viewSession(session, idle)

  override suspend fun messagesPage(id: String, cursor: String?, size: Int): ApiPage<Message> =
    api().messagesPage(id, cursor, size)

  override suspend fun messageRecords(id: String, cursor: String?, size: Int): ApiPage<MessageRecord> =
    api().messageRecords(id, cursor, size)

  override suspend fun inbox(id: String): List<Message> = api().inbox(id)

  override suspend fun updateQueue(sessionId: String, itemId: String, action: QueueAction): Boolean =
    api().updateQueue(sessionId, itemId, action)

  override suspend fun followJobOutput(
    sessionId: String,
    jobId: String,
    onUpdate: (text: String, running: Boolean, finished: Boolean) -> Unit
  ): Boolean = api().followJobOutput(sessionId, jobId, onUpdate)

  override suspend fun probeRemote(): RemoteProbe? = api().probeRemote()

  override suspend fun promptSubagent(parentId: String, childId: String, text: String): Boolean =
    api().promptSubagent(parentId, childId, text)

  override suspend fun pluginBundles(): List<RemotePluginBundle> = api().pluginBundles()

  override suspend fun pluginEntries(): List<RemotePluginEntry> = api().pluginEntries()

  override suspend fun setPluginBundle(name: String, enabled: Boolean): Boolean = api().setPluginBundle(name, enabled)

  override suspend fun setPluginEntry(entryId: String, enabled: Boolean): Boolean = api().setPluginEntry(entryId, enabled)

  override suspend fun credentialStatus(refs: List<String>): Map<String, RemoteCredentialStatus> = api().credentialStatus(refs)

  override suspend fun setCredential(ref: String, value: String): Boolean = api().setCredential(ref, value)

  override suspend fun clearCredential(ref: String): Boolean = api().clearCredential(ref)

  override suspend fun goal(sessionId: String): RemoteGoal? = api().goal(sessionId)

  override suspend fun createGoal(sessionId: String, objective: String, maxRounds: Int?): Boolean =
    api().createGoal(sessionId, objective, maxRounds)

  override suspend fun goalAction(sessionId: String, id: String, revision: Int, action: String): Boolean =
    api().goalAction(sessionId, id, revision, action)

  override suspend fun previewFile(directory: String, path: String, session: Session?): FilePreview? =
    api().previewFile(directory, path, session)

  override suspend fun schedules(sessionId: String): List<RemoteSchedule> = api().schedules(sessionId)

  override suspend fun createProviderRoute(
    route: String,
    displayName: String,
    api: String,
    baseUrl: String,
    apiKeyEnv: String,
    modelId: String,
    apiKey: String
  ): Boolean = this.api().createProviderRoute(route, displayName, api, baseUrl, apiKeyEnv, modelId, apiKey)

  override suspend fun hasPendingInput(id: String): Boolean = api().hasPendingInput(id)

  override suspend fun activeSessions(): Set<String> = api().activeSessions()

  override suspend fun shells(directory: String): List<ShellJob> = api().shells(directory)

  override suspend fun shellInfo(id: String, directory: String): ShellSnapshot? = api().shellInfo(id, directory)

  override suspend fun shellOutput(id: String, directory: String, cursor: Long?, limit: Int): ShellOutput? =
    api().shellOutput(id, directory, cursor, limit)

  override suspend fun removeShell(id: String, directory: String) = api().removeShell(id, directory)

  override suspend fun createSession(directory: String, title: String): Session = api().createSession(directory, title)

  override suspend fun renameSession(session: Session, title: String) = api().renameSession(session, title)

  override suspend fun setArchived(session: Session, archived: Boolean, now: Long) = api().setArchived(session, archived, now)

  override suspend fun deleteSession(session: Session) = api().deleteSession(session)

  override suspend fun forkSession(session: Session): Session = api().forkSession(session)

  override suspend fun abort(session: Session) = api().abort(session)

  override suspend fun compact(session: Session) = api().compact(session)

  override suspend fun revert(session: Session, messageId: String) = api().revert(session, messageId)

  override suspend fun unrevert(session: Session) = api().unrevert(session)

  override suspend fun send(
    session: Session,
    text: String,
    agent: String?,
    model: ModelChoice?,
    references: List<FileReference>,
    inline: List<InlineFile>,
    id: String,
    delivery: Delivery
  ) = api().send(session, text, agent, model, references, inline, id, delivery)

  override suspend fun command(
    session: Session,
    name: String,
    arguments: String,
    agent: String?,
    model: ModelChoice?,
    references: List<FileReference>,
    inline: List<InlineFile>,
    delivery: Delivery
  ) = api().command(session, name, arguments, agent, model, references, inline, delivery)

  override suspend fun agents(directory: String): List<AgentChoice> = api().agents(directory)

  override suspend fun commands(directory: String): List<CommandChoice> = api().commands(directory)

  override suspend fun modelCatalog(directory: String): List<ModelInfo> = api().modelCatalog(directory)

  override suspend fun sessionPermissions(session: Session): List<PermissionRequest> = api().sessionPermissions(session)

  override suspend fun pendingPermissions(directory: String): List<PermissionRequest> = api().pendingPermissions(directory)

  override suspend fun forms(directory: String): List<QuestionRequest> = api().forms(directory)

  override suspend fun replyPermission(request: PermissionRequest, reply: String) = api().replyPermission(request, reply)

  override suspend fun replyQuestion(request: QuestionRequest, answers: List<List<String>>) =
    api().replyQuestion(request, answers)

  override suspend fun rejectQuestion(request: QuestionRequest) = api().rejectQuestion(request)

  override suspend fun children(session: Session): List<Session> = api().children(session)

  override suspend fun diff(session: Session): List<FileChange> = api().diff(session)

  override suspend fun files(directory: String, path: String, session: Session?): List<FileNode> =
    api().files(directory, path, session)

  override suspend fun fileContent(directory: String, path: String, session: Session?): FileContent =
    api().fileContent(directory, path, session)

  override suspend fun searchFiles(directory: String, query: String, session: Session?): List<String> =
    api().searchFiles(directory, query, session)

  override fun events(onOpen: () -> Unit): Flow<ServerEvent> = flow { emitAll(api().events(onOpen)) }
}
