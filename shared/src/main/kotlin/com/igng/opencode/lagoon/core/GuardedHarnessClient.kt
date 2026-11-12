package com.igng.opencode.lagoon.core

import kotlinx.coroutines.flow.Flow

/** Explicit lease admission for the complete client surface, including optional/default methods. */
internal class GuardedHarnessClient(private val access: HarnessLeaseAccess) : HarnessClient {
  override fun close() = access.close()
  override suspend fun awaitClosed() = access.awaitClosed()
  override val isClosed: Boolean get() = access.isClosed
  override val cacheableTranscripts: Boolean get() = access.use { it.cacheableTranscripts }
  override val recoverableQuestions: Boolean get() = access.use { it.recoverableQuestions }
  override val workspaceUpdates: Flow<List<Project>>?
    get() = access.use { owner -> owner.workspaceUpdates?.let { source -> access.stream { source } } }

  override fun capabilities(): ApiCapabilities = access.use { it.capabilities() }
  override suspend fun discoverCapabilities(): ApiCapabilities = access.useSuspending { it.discoverCapabilities() }
  override suspend fun health(): String = access.useSuspending { it.health() }
  override suspend fun projects(): List<Project> = access.useSuspending { it.projects() }
  override suspend fun createCodexProject(path: String): Project? = access.useSuspending { it.createCodexProject(path) }
  override suspend fun openCodeProjects(): List<OpenCodeProjectInfo>? = access.useSuspending { it.openCodeProjects() }
  override suspend fun updateOpenCodeProject(expected: OpenCodeProjectInfo, edit: OpenCodeProjectEdit): OpenCodeProjectInfo? =
    access.useSuspending { it.updateOpenCodeProject(expected, edit) }
  override suspend fun openCodeWorktrees(expected: OpenCodeProjectInfo): List<OpenCodeWorktree>? =
    access.useSuspending { it.openCodeWorktrees(expected) }
  override suspend fun createOpenCodeWorktree(expected: OpenCodeProjectInfo, input: OpenCodeWorktreeCreate): OpenCodeWorktree? =
    access.useSuspending { it.createOpenCodeWorktree(expected, input) }
  override suspend fun removeOpenCodeWorktree(expected: OpenCodeProjectInfo, worktree: OpenCodeWorktree, force: Boolean): Boolean? =
    access.useSuspending { it.removeOpenCodeWorktree(expected, worktree, force) }
  override suspend fun refreshOpenCodeWorktrees(expected: OpenCodeProjectInfo): Boolean? =
    access.useSuspending { it.refreshOpenCodeWorktrees(expected) }
  override suspend fun serverWorkspaces(): List<ServerWorkspace>? = access.useSuspending { it.serverWorkspaces() }
  override suspend fun registerWorkspace(path: String): WorkspaceRegistration? = access.useSuspending { it.registerWorkspace(path) }
  override suspend fun renameWorkspace(expected: ServerWorkspace, title: String): ServerWorkspace? =
    access.useSuspending { it.renameWorkspace(expected, title) }
  override suspend fun deleteWorkspace(expected: ServerWorkspace): Boolean? = access.useSuspending { it.deleteWorkspace(expected) }
  override suspend fun moveWorkspace(expected: ServerWorkspace, before: ServerWorkspace?): List<String>? =
    access.useSuspending { it.moveWorkspace(expected, before) }
  override suspend fun projectsForDisplay(): List<Project> = access.useSuspending { it.projectsForDisplay() }
  override suspend fun session(id: String): Session = access.useSuspending { it.session(id) }
  override suspend fun searchSessionContent(query: String): SessionContentSearchResult? =
    access.useSuspending { it.searchSessionContent(query) }
  override suspend fun searchResultSession(id: String): Session? = access.useSuspending { it.searchResultSession(id) }
  override suspend fun projectAt(directory: String): Project? = access.useSuspending { it.projectAt(directory) }
  override suspend fun rootSessionsPage(directory: String?, cursor: String?, size: Int): ApiPage<Session> =
    access.useSuspending { it.rootSessionsPage(directory, cursor, size) }
  override suspend fun selectCodexSession(session: Session?): Unit = access.useSuspending { it.selectCodexSession(session) }
  override suspend fun monitorCodexSessions(active: List<Session>, selectedId: () -> String?): Unit =
    access.useSuspending { it.monitorCodexSessions(active, selectedId) }
  override suspend fun viewSession(session: Session, idle: Long): Unit = access.useSuspending { it.viewSession(session, idle) }
  override suspend fun messagesPage(id: String, cursor: String?, size: Int): ApiPage<Message> =
    access.useSuspending { it.messagesPage(id, cursor, size) }
  override suspend fun messageRecords(id: String, cursor: String?, size: Int): ApiPage<MessageRecord> =
    access.useSuspending { it.messageRecords(id, cursor, size) }
  override suspend fun inbox(id: String): List<Message> = access.useSuspending { it.inbox(id) }
  override suspend fun composerContext(id: String): ComposerContext = access.useSuspending { it.composerContext(id) }
  override suspend fun updateQueue(sessionId: String, itemId: String, action: QueueAction): Boolean =
    access.useSuspending { it.updateQueue(sessionId, itemId, action) }
  override suspend fun followJobOutput(
    sessionId: String, jobId: String, onUpdate: (text: String, running: Boolean, finished: Boolean) -> Unit
  ): Boolean = access.useSuspending { client ->
    client.followJobOutput(sessionId, jobId) { text, running, finished ->
      access.callback { onUpdate(text, running, finished) }
    }
  }
  override suspend fun probeRemote(): RemoteProbe? = access.useSuspending { it.probeRemote() }
  override suspend fun promptSubagent(parentId: String, childId: String, text: String): Boolean =
    access.useSuspending { it.promptSubagent(parentId, childId, text) }
  override suspend fun pluginBundles(): List<RemotePluginBundle> = access.useSuspending { it.pluginBundles() }
  override suspend fun pluginEntries(): List<RemotePluginEntry> = access.useSuspending { it.pluginEntries() }
  override suspend fun setPluginBundle(name: String, enabled: Boolean): Boolean =
    access.useSuspending { it.setPluginBundle(name, enabled) }
  override suspend fun setPluginEntry(entryId: String, enabled: Boolean): Boolean =
    access.useSuspending { it.setPluginEntry(entryId, enabled) }
  override suspend fun credentialStatus(refs: List<String>): Map<String, RemoteCredentialStatus> =
    access.useSuspending { it.credentialStatus(refs) }
  override suspend fun setCredential(ref: String, value: String): Boolean = access.useSuspending { it.setCredential(ref, value) }
  override suspend fun clearCredential(ref: String): Boolean = access.useSuspending { it.clearCredential(ref) }
  override suspend fun goal(sessionId: String): RemoteGoal? = access.useSuspending { it.goal(sessionId) }
  override suspend fun createGoal(sessionId: String, objective: String, maxRounds: Int?): Boolean =
    access.useSuspending { it.createGoal(sessionId, objective, maxRounds) }
  override suspend fun createGoalWithLimit(sessionId: String, objective: String, maxRounds: Long?): Boolean =
    access.useSuspending { it.createGoalWithLimit(sessionId, objective, maxRounds) }
  override suspend fun editGoal(sessionId: String, id: String, revision: Int, objective: String?, maxRounds: Long?): RemoteGoal? =
    access.useSuspending { it.editGoal(sessionId, id, revision, objective, maxRounds) }
  override suspend fun goalAction(sessionId: String, id: String, revision: Int, action: String): Boolean =
    access.useSuspending { it.goalAction(sessionId, id, revision, action) }
  override suspend fun previewFile(directory: String, path: String, session: Session?): FilePreview? =
    access.useSuspending { it.previewFile(directory, path, session) }
  override suspend fun schedules(sessionId: String): List<RemoteSchedule> = access.useSuspending { it.schedules(sessionId) }
  override suspend fun scheduleCatalog(): List<RemoteSchedule>? = access.useSuspending { it.scheduleCatalog() }
  override suspend fun scheduleHistory(sessionId: String, id: String, before: String?, limit: Int): ScheduleHistoryPage? =
    access.useSuspending { it.scheduleHistory(sessionId, id, before, limit) }
  override suspend fun deleteSchedule(sessionId: String, id: String): ScheduleDeleteResult? =
    access.useSuspending { it.deleteSchedule(sessionId, id) }
  override suspend fun updateSchedule(sessionId: String, id: String, expected: ScheduleRecord, title: String?, prompt: String?, change: ScheduleTimingChange?): ScheduleUpdateResult? =
    access.useSuspending { it.updateSchedule(sessionId, id, expected, title, prompt, change) }
  override suspend fun createProviderRoute(route: String, displayName: String, api: String, baseUrl: String, apiKeyEnv: String, modelId: String, apiKey: String): Boolean =
    access.useSuspending { it.createProviderRoute(route, displayName, api, baseUrl, apiKeyEnv, modelId, apiKey) }
  override suspend fun hasPendingInput(id: String): Boolean = access.useSuspending { it.hasPendingInput(id) }
  override suspend fun activeSessions(): Set<String> = access.useSuspending { it.activeSessions() }
  override suspend fun shells(directory: String): List<ShellJob> = access.useSuspending { it.shells(directory) }
  override suspend fun terminals(scope: TerminalScope): List<NativeTerminal> = access.useSuspending { it.terminals(scope) }
  override suspend fun createTerminal(scope: TerminalScope): NativeTerminal = access.useSuspending { it.createTerminal(scope) }
  override suspend fun resizeTerminal(scope: TerminalScope, id: String, cols: Int, rows: Int): NativeTerminal =
    access.useSuspending { it.resizeTerminal(scope, id, cols, rows) }
  override suspend fun renameTerminal(scope: TerminalScope, id: String, title: String): NativeTerminal =
    access.useSuspending { it.renameTerminal(scope, id, title) }
  override suspend fun closeTerminal(scope: TerminalScope, id: String): Unit = access.useSuspending { it.closeTerminal(scope, id) }
  override suspend fun attachTerminal(scope: TerminalScope, id: String): TerminalAttachment =
    access.useSuspending { it.attachTerminal(scope, id) }
  override suspend fun terminals(directory: String): List<NativeTerminal> = access.useSuspending { it.terminals(directory) }
  override suspend fun createTerminal(directory: String): NativeTerminal = access.useSuspending { it.createTerminal(directory) }
  override suspend fun resizeTerminal(directory: String, id: String, cols: Int, rows: Int): NativeTerminal =
    access.useSuspending { it.resizeTerminal(directory, id, cols, rows) }
  override suspend fun renameTerminal(directory: String, id: String, title: String): NativeTerminal =
    access.useSuspending { it.renameTerminal(directory, id, title) }
  override suspend fun closeTerminal(directory: String, id: String): Unit = access.useSuspending { it.closeTerminal(directory, id) }
  override suspend fun attachTerminal(directory: String, id: String): TerminalAttachment =
    access.useSuspending { it.attachTerminal(directory, id) }
  override suspend fun shellInfo(id: String, directory: String): ShellSnapshot? = access.useSuspending { it.shellInfo(id, directory) }
  override suspend fun shellOutput(id: String, directory: String, cursor: Long?, limit: Int): ShellOutput? =
    access.useSuspending { it.shellOutput(id, directory, cursor, limit) }
  override suspend fun removeShell(id: String, directory: String): Unit = access.useSuspending { it.removeShell(id, directory) }
  override suspend fun createSession(directory: String, title: String, workspaceId: String?): Session =
    access.useSuspending { it.createSession(directory, title, workspaceId) }
  override suspend fun renameSession(session: Session, title: String): Unit = access.useSuspending { it.renameSession(session, title) }
  override suspend fun setArchived(session: Session, archived: Boolean, now: Long): Unit =
    access.useSuspending { it.setArchived(session, archived, now) }
  override suspend fun deleteSession(session: Session): Unit = access.useSuspending { it.deleteSession(session) }
  override suspend fun forkSession(session: Session): Session = access.useSuspending { it.forkSession(session) }
  override suspend fun forkSession(session: Session, boundary: ForkBoundary): Session = access.useSuspending { it.forkSession(session, boundary) }
  override suspend fun abort(session: Session): Unit = access.useSuspending { it.abort(session) }
  override suspend fun compact(session: Session): Unit = access.useSuspending { it.compact(session) }
  override suspend fun revert(session: Session, messageId: String): Unit = access.useSuspending { it.revert(session, messageId) }
  override suspend fun unrevert(session: Session): Unit = access.useSuspending { it.unrevert(session) }
  override suspend fun send(session: Session, text: String, agent: String?, model: ModelChoice?, references: List<FileReference>, inline: List<InlineFile>, id: String, delivery: Delivery): Unit =
    access.useSuspending { it.send(session, text, agent, model, references, inline, id, delivery) }
  override suspend fun command(session: Session, name: String, arguments: String, agent: String?, model: ModelChoice?, references: List<FileReference>, inline: List<InlineFile>, delivery: Delivery): Unit =
    access.useSuspending { it.command(session, name, arguments, agent, model, references, inline, delivery) }
  override suspend fun agents(directory: String): List<AgentChoice> = access.useSuspending { it.agents(directory) }
  override suspend fun commands(directory: String): List<CommandChoice> = access.useSuspending { it.commands(directory) }
  override suspend fun modelCatalog(directory: String): List<ModelInfo> = access.useSuspending { it.modelCatalog(directory) }
  override suspend fun permissionPresets(directory: String): List<PermissionPresetChoice> =
    access.useSuspending { it.permissionPresets(directory) }
  override suspend fun setPermission(session: Session, value: String): Boolean =
    access.useSuspending { it.setPermission(session, value) }
  override suspend fun serverPinnedSessions(): Set<String>? = access.useSuspending { it.serverPinnedSessions() }
  override suspend fun setSessionPinned(session: Session, pinned: Boolean): Boolean =
    access.useSuspending { it.setSessionPinned(session, pinned) }
  override suspend fun sessionPermissions(session: Session): List<PermissionRequest> =
    access.useSuspending { it.sessionPermissions(session) }
  override suspend fun pendingPermissions(directory: String): List<PermissionRequest> =
    access.useSuspending { it.pendingPermissions(directory) }
  override suspend fun forms(directory: String): List<QuestionRequest> = access.useSuspending { it.forms(directory) }
  override suspend fun replyPermission(request: PermissionRequest, reply: String): Unit =
    access.useSuspending { it.replyPermission(request, reply) }
  override suspend fun replyQuestion(request: QuestionRequest, answers: List<List<String>>): Unit =
    access.useSuspending { it.replyQuestion(request, answers) }
  override suspend fun rejectQuestion(request: QuestionRequest): Unit = access.useSuspending { it.rejectQuestion(request) }
  override fun questionPresentation(sessionId: String?, foreground: Boolean): Unit = access.questionPresentation(sessionId, foreground)
  override fun questionFocus(id: String, focused: Boolean): Unit = access.questionFocus(id, focused)
  override fun questionEngage(id: String): Unit = access.questionEngage(id)
  override fun questionTakeTime(id: String): Unit = access.questionTakeTime(id)
  override suspend fun children(session: Session): List<Session> = access.useSuspending { it.children(session) }
  override suspend fun diff(session: Session): List<FileChange> = access.useSuspending { it.diff(session) }
  override suspend fun files(directory: String, path: String, session: Session?): List<FileNode> =
    access.useSuspending { it.files(directory, path, session) }
  override suspend fun fileContent(directory: String, path: String, session: Session?): FileContent =
    access.useSuspending { it.fileContent(directory, path, session) }
  override suspend fun searchFiles(directory: String, query: String, session: Session?): List<String> =
    access.useSuspending { it.searchFiles(directory, query, session) }
  override fun events(onOpen: () -> Unit): Flow<ServerEvent> =
    access.stream { client -> client.events { access.callback(onOpen) } }
  override fun setContentWatching(enabled: Boolean): Unit = access.setContentWatching(enabled)
  override fun transportStatus(): String? = access.use { it.transportStatus() }
}
