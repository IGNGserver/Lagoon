package com.igng.opencode.lagoon.core

/**
 * 单个会话（ServerId + SessionId）的独立状态槽位：
 * 隔离会话自身的消息流、改动、目标、草稿与分页游标，使父子会话切换、跨设备返回与预测式手势转场期间
 * 新旧两个会话页面可同时独立渲染，彻底消除 MainActivity 手动维护 snapshots Map 的脆弱补丁。
 */
internal data class ConversationSlot(
  val serverId: String,
  val sessionId: String,
  val session: Session,
  val projectId: String? = null,
  val messages: List<Message> = emptyList(),
  val changes: List<FileChange> = emptyList(),
  val goal: RemoteGoal? = null,
  val schedules: List<RemoteSchedule> = emptyList(),
  val agent: String? = null,
  val model: ModelChoice? = null,
  val agentChanged: Boolean = false,
  val modelChanged: Boolean = false,
  val draft: String = "",
  val draftRevision: Long = 0,
  val references: List<FileReference> = emptyList(),
  val messagesCursor: String? = null,
  val loadedOlderMessages: Boolean = false,
  val cacheComplete: Boolean = true,
  val resources: Map<String, ResourceStatus> = emptyMap(),
  val provisionalMessageIds: Set<String> = emptySet(),
  val transcriptLoadingSince: Long = 0,
  val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 多会话独立状态池（LRU 容量默认 12）：
 * 由 [LagoonController] 持有并在状态归约时自动同步；UI 路由层通过 [project] 按目标路由获取隔离后的会话状态。
 */
internal class ConversationStorePool(private val capacity: Int = 12) {
  private val slots = object : LinkedHashMap<String, ConversationSlot>(capacity, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ConversationSlot>?): Boolean =
      size > capacity
  }

  private fun key(serverId: String, sessionId: String): String = "$serverId\u0000$sessionId"

  @Synchronized
  fun record(state: LagoonState) {
    val serverId = state.serverId ?: return
    val sessionId = state.sessionId ?: return
    val session = state.session ?: return
    val k = key(serverId, sessionId)
    val existing = slots[k]
    // 当新选中会话尚处于空列表初始加载帧、而槽位中已有消息时，保留已缓存消息直至新消息到达
    val keepMessages = state.messages.isEmpty() &&
      state.resource("messages").state == ResourceState.LOADING &&
      existing != null && existing.messages.isNotEmpty()
    slots[k] = ConversationSlot(
      serverId = serverId,
      sessionId = sessionId,
      session = session,
      projectId = state.projectId ?: existing?.projectId,
      messages = if (keepMessages) existing!!.messages else state.messages,
      changes = state.changes.ifEmpty { existing?.changes.orEmpty() },
      goal = state.goal ?: existing?.goal,
      schedules = state.schedules.ifEmpty { existing?.schedules.orEmpty() },
      agent = state.agent ?: existing?.agent,
      model = state.model ?: existing?.model,
      agentChanged = state.agentChanged,
      modelChanged = state.modelChanged,
      draft = state.draft,
      draftRevision = state.draftRevision,
      references = state.references,
      messagesCursor = if (keepMessages) existing!!.messagesCursor else state.messagesCursor,
      loadedOlderMessages = if (keepMessages) existing!!.loadedOlderMessages else state.loadedOlderMessages,
      cacheComplete = if (keepMessages) existing!!.cacheComplete else state.cacheComplete,
      resources = state.resources,
      provisionalMessageIds = state.provisionalMessageIds,
      transcriptLoadingSince = state.transcriptLoadingSince,
      updatedAt = System.currentTimeMillis()
    )
  }

  @Synchronized
  fun get(serverId: String, sessionId: String): ConversationSlot? = slots[key(serverId, sessionId)]

  @Synchronized
  fun project(
    base: LagoonState,
    serverId: String?,
    sessionId: String?,
    sessionViews: SessionViewCache? = null,
    transcriptMemory: TranscriptMemory? = null
  ): LagoonState {
    if (serverId.isNullOrBlank() || sessionId.isNullOrBlank()) return base
    if (base.serverId == serverId && base.sessionId == sessionId && base.session != null) {
      record(base)
      return base
    }
    val slot = slots[key(serverId, sessionId)]
    if (slot != null) {
      val mergedSessions = if (base.sessions.any { it.id == slot.sessionId }) base.sessions
      else base.sessions + slot.session
      return base.copy(
        serverId = serverId,
        sessionId = sessionId,
        projectId = slot.projectId,
        sessions = mergedSessions,
        messages = slot.messages,
        changes = slot.changes,
        goal = slot.goal,
        schedules = slot.schedules,
        agent = slot.agent,
        model = slot.model,
        agentChanged = slot.agentChanged,
        modelChanged = slot.modelChanged,
        draft = slot.draft,
        draftRevision = slot.draftRevision,
        references = slot.references,
        messagesCursor = slot.messagesCursor,
        loadedOlderMessages = slot.loadedOlderMessages,
        cacheComplete = slot.cacheComplete,
        resources = slot.resources,
        provisionalMessageIds = slot.provisionalMessageIds,
        transcriptLoadingSince = slot.transcriptLoadingSince
      )
    }
    // 回退至 SessionViewCache / TranscriptMemory（如预取会话或父会话）
    val knownSession = base.sessions.firstOrNull { it.id == sessionId }
      ?: base.allServerSnapshots[serverId]?.sessions?.firstOrNull { it.id == sessionId }
    val cachedView = sessionViews?.get(serverId, sessionId)
    val fallbackMessages = cachedView?.messages ?: transcriptMemory?.get(serverId, sessionId).orEmpty()
    val mergedSessions = when {
      knownSession == null -> emptyList()
      base.sessions.any { it.id == sessionId } -> base.sessions
      else -> base.sessions + knownSession
    }
    return base.copy(
      serverId = serverId,
      sessionId = sessionId,
      sessions = mergedSessions,
      messages = fallbackMessages,
      messagesCursor = cachedView?.olderCursor,
      loadedOlderMessages = cachedView?.loadedOlder ?: false,
      cacheComplete = cachedView?.cacheComplete ?: true,
      draft = ""
    )
  }

  @Synchronized
  fun forget(serverId: String, sessionId: String) {
    slots.remove(key(serverId, sessionId))
  }

  @Synchronized
  fun forgetServer(serverId: String) {
    slots.keys.removeAll { it.startsWith("$serverId\u0000") }
  }

  @Synchronized
  fun clear() {
    slots.clear()
  }

  @Synchronized
  fun size(): Int = slots.size
}
