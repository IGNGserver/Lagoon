package com.igng.opencode.lagoon.core

import android.content.Context
import com.igng.opencode.lagoon.core.ssh.SshHostManager
import com.igng.opencode.lagoon.system.BackgroundSyncWorker
import com.igng.opencode.lagoon.system.TaskNotifications
import com.igng.opencode.lagoon.system.TaskMonitorService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class LagoonController private constructor(private val appContext: Context) {
  companion object {
    private val TERMINAL_PHASES = setOf(TaskPhase.COMPLETED, TaskPhase.FAILED)
    /** Backoff attempt that maps to the slowest reconnect delay; used once credentials are rejected. */
    private const val AUTH_RETRY_ATTEMPT = 4
    /** Keep the live stream briefly after leaving the app so quick app switches do not reconnect. */
    private const val BACKGROUND_GRACE_MILLIS = 30_000L
    /** 总览读完为非活跃后，先等这么久复核再拆岛，避免多步任务步骤间隙导致息屏超级岛反复重弹。 */
    private const val SUMMARY_SETTLE_MILLIS = 4_000L
    /** 进入“待处理”后先等这么久再发通知：权限/表单瞬时出现又消失时不打扰用户。 */
    private const val ATTENTION_DEBOUNCE_MILLIS = 1_500L
    @Volatile private var instance: LagoonController? = null
    fun get(context: Context): LagoonController = instance ?: synchronized(this) {
      instance ?: LagoonController(context.applicationContext).also { instance = it }
    }
    /** Upper bound of running sessions whose pending permissions are read in one reconciliation. */
    private const val MAX_REQUEST_SESSIONS = 24
    /** Extra pages the initial load may pull so the first screen starts on a whole turn (official behaviour). */
    private const val MAX_INITIAL_PAGES = 5
    /** Recent conversations whose first page is fetched into memory after a full read. */
    private const val PREFETCH_SESSIONS = 3
    /** Away from the foreground longer than this, the live streams are rebuilt on return. */
    private const val STREAM_RESUME_RESTART_MILLIS = 60_000L
    /** A resume inside this window keeps the last catalog; event streams catch up on their own. */
    private const val FOREGROUND_STALE_MILLIS = 5 * 60_000L
    /** Safety reconcile when the event stream is nominally connected. */
    private const val SAFETY_RECONCILE_MILLIS = 5 * 60_000L
    /** Network callbacks can fire repeatedly during a handover. */
    private const val NETWORK_REFRESH_DEBOUNCE_MILLIS = 10_000L
    /** The first screen must not wait for a slow host indefinitely; the remaining reads continue later. */
    private const val FIRST_ROUND_TIMEOUT_MILLIS = 20_000L
    /** 一次对账最多读取多少个目录的 shell 列表（首页项目优先，其次是最近活跃会话的目录）。 */
    private const val MAX_SHELL_DIRECTORIES = 2
    /** shell 归属会话不在目录页时，最多补读多少个会话身份来解析父链。 */
    private const val MAX_SHELL_SESSIONS = 12
    /** 一轮里最多补读多少层父链、每层多少个；避免异常会话链把对账拖成几百次请求。 */
    private const val MAX_PARENT_ROUNDS = 8
    private const val MAX_PARENT_FETCH = 32
    /** 自动审批只扫最近这些目录；完整目录扫描交给手动刷新。 */
    private const val MAX_SWEEP_DIRECTORIES = 12
    /** 一次控制读最多检查多少个待处理目录的表单。 */
    private const val MAX_FORM_DIRECTORIES = 8
  }
  private fun scoped(serverId: String, session: Session): Session = session.copy(serverId = serverId)

  /**
   * Recomputes the server-wide island summary and persists live task state. Persisted running states are
   * what lets a later refresh — even after the process was frozen or killed — notice that a run this device
   * saw has ended and record it as an unread result.
   *
    * 后台 shell / 子会话是独立的“后台活动”，只生成 [LagoonState.backgroundRunning] 供列表标记和
   * 会话内面板使用；灵动岛、通知与根会话运行状态只读根执行自己的 [TaskState]。
   */
  private fun withSummary(state: LagoonState): LagoonState {
    // A tool can settle in the parent session while its metadata still says that a `task`/`subagent`
    // child is running. Reconstruct those child identities from the loaded transcript as the official
    // client does; this only discovers identities, never promotes them to the root's execution state.
    val transcriptParent = state.sessionId
    // Most updates change tasks, not the transcript: reuse the last scan while the message list is the same instance.
    val transcriptChildren = subagentScan?.takeIf { it.first == transcriptParent && it.second === state.messages }?.third
      ?: BackgroundWork.messageSubagents(transcriptParent, state.messages).also { subagentScan = Triple(transcriptParent, state.messages, it) }
    val observed = if (transcriptChildren.isEmpty() || transcriptParent == null) state else
      state.copy(knownParents = state.knownParents + transcriptChildren.keys.associateWith { transcriptParent })
    val next = observed
    next.serverId?.let { server -> next.tasks.values.forEach { persistTask(server, it, next.parents[it.sessionId]) } }
    val titles = sessionTitles?.takeIf { it.first === next.sessions }?.second
      ?: next.sessions.associate { it.id to next.title(it) }.also { sessionTitles = next.sessions to it }
    val active = next.activeRootTasks
    // Background activity is a display marker only: running shells or descendants never make the
    // root's own execution count as running, and never keep the island alive.
    val background = SessionActivityRules.backgroundRoots(next.tasks, next.shells, next.parents)
    val summary = TaskSummary.of(next.livePresentedTasks, next.notices, next.parents, titles,
      serverId = next.serverId, serverName = next.server?.name)
    val targetId = active.values.firstOrNull { it.phase in TaskState.WAITING_PHASES }?.sessionId
      ?: next.notices.filter { !it.viewed && it.sessionId !in active }.maxByOrNull { it.time }?.sessionId
      ?: summary.items.firstOrNull()?.sessionId
    return next.copy(
      summary = summary,
      backgroundRunning = background,
      // Prefer the session that needs a reply, then the newest unread result; otherwise the summary's
      // headline task, so tapping the island always lands somewhere, including when tasks are only running.
      summaryTargetId = targetId,
      summaryTarget = targetId?.let { next.serverId?.let { server -> SessionTarget(server, it) } }
    ).also { conversationPool.record(it) }
  }

  private var conversationPoolStorage: ConversationStorePool? = null
  internal val conversationPool: ConversationStorePool
    get() = conversationPoolStorage ?: ConversationStorePool().also { conversationPoolStorage = it }

  /**
   * 统一的状态提交入口：所有状态变更（包括流式增量、乐观发送、翻页、草稿、目标、定时任务与改动列表）
   * 在 CAS 提交后立即同步到 [conversationPool]，避免绕过 [withSummary] 的更新在会话切换时丢失。
   */
  private fun updateState(transform: (LagoonState) -> LagoonState) {
    var committed: LagoonState? = null
    mutable.update { current -> transform(current).also { committed = it } }
    committed?.let(conversationPool::record)
  }

  /** 按目标 (serverId, sessionId) 投影独立会话状态，供路由转场与父子会话栈直接读取。 */
  fun conversationState(base: LagoonState, serverId: String?, sessionId: String?): LagoonState =
    conversationPool.project(base, serverId, sessionId, sessionViews, transcriptMemory)

  private val store = ServerStore(appContext)
  /** Device groups remain a legacy presentation store; the graph only references their stable IDs. */
  private val deviceGroupStore = DeviceGroupStore(appContext)
  /** Graph persistence is a companion projection and never the source of legacy profiles. */
  private val connectionGraphStore = ConnectionGraphStore(
    appContext,
    readProfiles = { store.readProfiles() },
    readCompositions = { connectionGraphCompositions() }
  )
  private val connectionGraphMutable = MutableStateFlow<ConnectionGraph?>(null)
  /** Null means the graph was unavailable (corrupt/future schema), not that legacy profiles are empty. */
  val connectionGraph: StateFlow<ConnectionGraph?> = connectionGraphMutable
  private var connectionGraphWritable = false

  /** Effective enabled state for one graph leaf, including source and host parents. */
  data class GraphEnabledProjection(
    val sourceId: String,
    val hostId: String,
    val bindingId: String,
    val sourceEnabled: Boolean,
    val hostEnabled: Boolean,
    val bindingEnabled: Boolean,
    val effectiveEnabled: Boolean
  )

  fun graphBinding(bindingId: String): HarnessBinding? = connectionGraphMutable.value?.bindings?.firstOrNull { it.id == bindingId }
  fun graphEffectiveEnabled(bindingId: String): Boolean =
    store.profiles().firstOrNull { it.id == bindingId }?.effectiveEnabled ?: false
  fun graphEnabledProjection(bindingId: String): GraphEnabledProjection? {
    val graph = connectionGraphMutable.value ?: return null
    val binding = graph.bindings.firstOrNull { it.id == bindingId } ?: return null
    val host = graph.hosts.firstOrNull { it.id == binding.hostBindingId } ?: return null
    val source = graph.sources.firstOrNull { it.id == host.sourceId } ?: return null
    val leafEnabled = store.readProfiles().firstOrNull { it.id == bindingId }?.enabled ?: binding.enabled
    return GraphEnabledProjection(source.id, host.id, binding.id, source.enabled, host.enabled, leafEnabled,
      source.enabled && host.enabled && leafEnabled)
  }

  fun setConnectionSourceEnabled(sourceId: String, enabled: Boolean) = setConnectionParentEnabled {
    connectionGraphStore.setSourceEnabled(sourceId, enabled)
  }
  fun setConnectionHostEnabled(hostId: String, enabled: Boolean) = setConnectionParentEnabled {
    connectionGraphStore.setHostEnabled(hostId, enabled)
  }

  private fun setConnectionParentEnabled(change: () -> ConnectionGraph) {
    if (!connectionGraphWritable) {
      showNotice("连接图不可写，请先修复或升级；原资料已保留", FeedbackTone.WARNING)
      return
    }
    val next = try { change() }
    catch (cancel: CancellationException) { throw cancel }
    catch (error: Exception) {
      // SharedPreferences may report failure after the new primary was committed but before journal
      // cleanup. Reread the committed primary and reconcile runtime gates before exposing the error.
      when (val recovered = runCatching { connectionGraphStore.loadOrMigrate() }.getOrNull()) {
        is ConnectionGraphStore.LoadResult.Loaded -> { connectionGraphMutable.value = recovered.graph; connectionGraphWritable = true }
        is ConnectionGraphStore.LoadResult.Unsupported, null -> {
          connectionGraphMutable.value = null; connectionGraphWritable = false
        }
      }
      applyCurrentGraphRuntimeGates()
      showNotice(userFacingError(error, "父级状态写入结果不确定，已按磁盘中的连接许可同步运行连接"), FeedbackTone.WARNING)
      return
    }
    connectionGraphMutable.value = next
    connectionGraphWritable = true
    applyCurrentGraphRuntimeGates()
  }

  private fun applyCurrentGraphRuntimeGates() {
    val before = state.value.profiles
    val profiles = store.profiles()
    val denied = before.filter { it.effectiveEnabled }.map { it.id }.toSet() -
      profiles.filter { it.effectiveEnabled }.map { it.id }.toSet()
    if (denied.isNotEmpty()) {
      returnQueuedToDrafts { it.serverId !in denied }
      for (id in denied) {
        cancelProfileNotifications(id)
        allOverview.invalidate(id)
        if (profiles.any { it.id == id && it.sshHostId != null }) sshHostManager.closeBinding(id)
        allSnapshots.remove(id)
      }
    }
    val selectedGroup = state.value.homeGroupId
    updateState { withSummary(it.copy(profiles = profiles, allServerSnapshots = allSnapshots.toMap(),
      message = "父级连接状态已保存，Harness 自身开关未改动", messageTone = FeedbackTone.INFO)) }
    allOverview.start(profiles, include = setOfNotNull(state.value.serverId?.takeIf { id -> profiles.any { it.id == id && it.effectiveEnabled } }))
    if (state.value.serverId in denied) {
      val fallback = if (selectedGroup != null) state.value.homeGroup?.memberIds?.mapNotNull { id ->
        profiles.firstOrNull { it.id == id && it.effectiveEnabled }
      }?.firstOrNull() else profiles.firstOrNull { it.effectiveEnabled }
      if (fallback != null) connect(fallback.id, preserveHome = true, groupOverride = selectedGroup)
      else {
        showAggregateAfterDisable(profiles, "所属接入源或主机已停用")
        if (selectedGroup != null) updateState { it.copy(homeGroupId = selectedGroup, allServers = false) }
      }
    }
    scheduleBackgroundSync()
  }

  private fun connectionGraphCompositions(): List<BindingComposition> {
    val profiles = store.profiles()
    val valid = profiles.map { it.id }.toSet()
    return deviceGroupStore.load().state.sanitized(valid).groups.map { group ->
      BindingComposition(group.id, group.name, group.memberIds)
    }
  }

  /**
   * Reprojects legacy profiles into the graph after a profile/group mutation. Legacy profile fields
   * remain authoritative for binding enabled/autoConnect/notifications; graph-only source/host
   * switches and discovery evidence survive the projection.
   */
  private fun projectedConnectionGraph(profiles: List<ServerProfile>, groups: List<DeviceGroup>): ConnectionGraph {
    val existing = connectionGraphMutable.value
    val selectedSlots = existing?.bindings.orEmpty().mapNotNull { binding ->
      binding.credentials.remoteIdentity?.selected?.key?.let { binding.id to it }
    }.toMap()
    val compositions = groups.map { group -> BindingComposition(group.id, group.name, group.memberIds) }
    val projected = MigrationProjection.fromProfiles(profiles,
      resolvedRemoteSlots = profiles.mapNotNull { profile ->
        if (MigrationProjection.sourceType(profile) != ConnectionSourceType.HUB) return@mapNotNull null
        val selected = selectedSlots[profile.id] ?: return@mapNotNull null
        selected.takeIf { slot -> MigrationProjection.remoteSlotRefs(profile).candidates.any { it.key == slot } }
          ?.let { profile.id to it }
      }.toMap(),
      compositions = compositions)
    if (existing == null) return projected
    val oldSources = existing.sources.associateBy { it.id }
    val oldHosts = existing.hosts.associateBy { it.id }
    val oldBindings = existing.bindings.associateBy { it.id }
    return projected.copy(
      sources = projected.sources.map { source -> oldSources[source.id]?.let { source.copy(enabled = it.enabled) } ?: source },
      hosts = projected.hosts.map { host -> oldHosts[host.id]?.let { host.copy(enabled = it.enabled) } ?: host },
      bindings = projected.bindings.map { binding ->
        val old = oldBindings[binding.id]
        binding.copy(
          enabled = binding.legacyProfile?.enabled ?: binding.enabled,
          discovery = old?.discovery ?: binding.discovery,
          serviceId = old?.serviceId ?: binding.serviceId
        )
      }
    )
  }

  private fun persistConnectionGraph(profiles: List<ServerProfile> = store.readProfiles(), groups: List<DeviceGroup> = state.value.deviceGroups) {
    if (!connectionGraphWritable) return
    try {
      // Runtime parentEnabled is an overlay, never part of migration/legacy profile authority.
      val graph = projectedConnectionGraph(store.readProfiles(), groups)
      connectionGraphStore.save(graph)
      connectionGraphMutable.value = graph
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      // The graph is a companion projection. Never make a legacy profile/group edit fail because
      // graph persistence is unavailable; keep the last published graph and record diagnostics.
      Diagnostics.warn("ConnectionGraph", "连接图保存失败，保留旧图并继续使用旧连接资料", error)
    }
  }

  private val remoteBackendFactory = object : RemoteBackendFactory {
    override fun create(profile: ServerProfile, credentials: ServerCredentials): RemoteBackend {
      val hostId = profile.remoteHostId ?: throw IllegalStateException("请先选择并验证 DSH Host")
      val origin = canonicalRemoteOrigin(profile.url)
      val account = credentials.username.trim()
      if (account.isBlank()) throw IllegalStateException("请先登录 DSH Hub 并注册 Remote 设备")
      val manager = RemoteDeviceManager(store.remoteDevices, remoteSlot(profile), origin, account,
        registerer = { descriptor, token -> RemoteServerApi(profile.url, profile.allowCleartext).register(descriptor, token) },
        refresher = { id, token -> RemoteServerApi(profile.url, profile.allowCleartext).refresh(id, token) })
      // 同一 (Hub, 账号, Host) 全进程只保留一条加密连接，避免 monitor/控制器/测试互相顶掉。
      val key = "remote|$origin|$account|$hostId"
      val session = RemoteConnectionRegistry.acquire(key) {
        RemoteSession(profile.url, profile.allowCleartext, hostId, manager,
          RemotePeerTrust(store.remotePins), account, appVersion())
      }
      return RemoteBackend(session) { RemoteConnectionRegistry.release(key) }
    }
  }

  private fun remoteSlot(profileId: String) = "profile-${profileId.replace(Regex("[^A-Za-z0-9_-]"), "_")}"

  /** Reuse only an identity bound to this Hub/account; never alias another source's Host ID. */
  private fun remoteSlot(profile: ServerProfile): String {
    val host = profile.remoteHostId?.takeIf(String::isNotBlank) ?: return remoteSlot(profile.id)
    return resolveRemoteDeviceSlot(store.remoteDevices, profile.url, profile.username, host,
      legacyProfileSlots = listOf(remoteSlot(profile.id)))
  }

  internal suspend fun registerRemote(profile: ServerProfile, account: String, password: String): List<RemoteHost> {
    require(profile.kind == ServerKind.DSH_REMOTE)
    val origin = canonicalRemoteOrigin(profile.url)
    val api = RemoteServerApi(profile.url, profile.allowCleartext)
    val setup = RemoteSetup(api, store.remoteDevices, store.remotePins, remoteSlot(profile.id), origin,
      profile.name.ifBlank { "Lagoon Android" }, "android", appVersion())
    val result = setup.register(account, password)
    return result.hosts
  }

  internal suspend fun confirmRemoteHost(profile: ServerProfile, account: String, hostId: String, fingerprint: String): RemoteHost {
    require(profile.kind == ServerKind.DSH_REMOTE)
    val origin = canonicalRemoteOrigin(profile.url)
    val setup = RemoteSetup(RemoteServerApi(profile.url, profile.allowCleartext), store.remoteDevices, store.remotePins,
      remoteSlot(profile.id), origin, profile.name.ifBlank { "Lagoon Android" }, "android", appVersion())
    val host = setup.confirmHost(account, hostId, fingerprint)
    return host
  }

  /** One manager per login flow; the in-memory account token lives only as long as the UI keeps it. */
  internal fun remoteAccountManager(address: String, allowCleartext: Boolean): RemoteAccountManager =
    RemoteAccountManager(address, allowCleartext, "Lagoon · ${android.os.Build.MODEL.ifBlank { "Android" }}",
      "android", appVersion(), store.remoteDevices, store.remotePins, store.remoteCatalogStore)

  internal fun remoteCatalogs(): List<RemoteCatalog> = store.remoteCatalogs()

  /** One active Hub account: the most recently saved catalog. Older catalogs stay for cleanup. */
  internal fun activeRemoteCatalog(): RemoteCatalog? = store.remoteCatalogs().maxByOrNull { it.updatedAt }

  internal fun remoteCatalog(origin: String, account: String): RemoteCatalog? =
    store.remoteCatalogs().firstOrNull { it.origin == canonicalRemoteOrigin(origin) && it.account == account }

  /**
   * Creates one ServerProfile per selected Host. Existing profiles for the same origin/host are
   * reused, so adding a Host twice does not duplicate it in the home chips.
   */
  internal fun addRemoteProfiles(catalog: RemoteCatalog, selected: List<RemoteHostMeta>): List<ServerProfile> {
    require(!catalog.requiresCleartextConsent) {
      "HTTP 明文连接未获授权，请重新登录并明确允许"
    }
    if (catalog.hosts.isEmpty()) return emptyList()
    val origin = catalog.origin.trimEnd('/')
    val existing = store.profiles()
    val created = mutableListOf<ServerProfile>()
    for (host in selected) {
      val current = existing.firstOrNull { it.matchesRemoteHost(catalog.origin, catalog.account, host.hostId) }
      if (current != null) {
        // Reuse the saved profile; only an auto-generated " (N)" suffix may be dropped.
        val repaired = ProfileNames.adoptHostName(current, host.name, store.profiles())
        if (repaired != null) {
          runCatching { store.rename(repaired.id, repaired.name) }
            .onFailure { Diagnostics.warn("ServerStore", "无法修复设备名称 ${repaired.id}", it) }
        }
        continue
      }
      val name = ProfileNames.uniqueDeviceName(host.name, store.profiles())
      val profile = ServerProfile(
        id = java.util.UUID.randomUUID().toString(),
        name = name,
        url = origin,
        username = catalog.account,
        autoConnect = true,
        notifications = true,
        allowCleartext = catalog.allowCleartext,
        kind = ServerKind.DSH_REMOTE,
        remoteHostId = host.hostId,
        remoteHostFingerprint = host.fingerprint
      )
      saveServer(profile, password = null, cookie = null, credentialUsername = catalog.account, connect = false)
      created += profile
    }
    store.saveRemoteCatalog(catalog)
    val firstOnline = created.firstOrNull { host -> catalog.hosts.firstOrNull { it.hostId == host.remoteHostId }?.online == true }
      ?: created.firstOrNull()
    if (firstOnline != null) connect(firstOnline.id, preserveHome = state.value.allServers)
    return created
  }

  internal fun saveRemoteCatalog(catalog: RemoteCatalog) = store.saveRemoteCatalog(catalog)

  /** Stop background retries after a sign-out; profiles and cached conversations stay local. */
  internal fun markRemoteSignedOut(origin: String, account: String) {
    store.profiles().filter {
      it.kind == ServerKind.DSH_REMOTE && canonicalRemoteOrigin(it.url) == canonicalRemoteOrigin(origin) && it.username == account && it.effectiveEnabled
    }.forEach { profile ->
      setProfileEnabled(profile.id, false)
    }
  }
  private fun appVersion(): String = runCatching {
    appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
  }.getOrNull()?.takeIf(String::isNotBlank) ?: "0.1.0"
  private val cache = OfflineCache(appContext)
  /** Transcript cache access runs one at a time in submission order: binding a server precedes reading it. */
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  private val storeDispatcher = Dispatchers.IO.limitedParallelism(1)
  /** Verified on-device copy of OpenCode transcripts; its database opens on first use, off the main thread. */
  private val transcripts: TranscriptStore? = TranscriptStore.get(appContext)
  private val transcriptSync = transcripts?.let { TranscriptSync(it, storeDispatcher) }
  private val notifications = TaskNotifications(appContext)
  private val attachmentImporter = AttachmentImporter(appContext)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  // Writing operations that must survive the UI leaving the foreground (permission replies, aborts)
  // run here rather than in a composer-scoped coroutine.
  private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val sshHostStore = SshHostStore(appContext)
  private val sshHostManager = SshHostManager(sshHostStore)
  private val mutable = MutableStateFlow(
    run {
      val storedProfiles = store.profiles()
      // Old builds deduped device names against every profile, including OpenCode Harnesses. Strip
      // only suffixes proven to be generated: the base must still be the Hub's name for this Host.
      val profiles = ProfileNames.repairGeneratedSuffixes(storedProfiles, store.remoteCatalogs()).also { repaired ->
        repaired.filterIndexed { index, profile -> profile.name != storedProfiles[index].name }.forEach { profile ->
          runCatching { store.rename(profile.id, profile.name) }
            .onFailure { Diagnostics.warn("ServerStore", "无法修复设备名称 ${profile.id}", it) }
        }
      }
      val groupLoad = deviceGroupStore.load()
      val groups = groupLoad.state.sanitized(profiles.map { it.id }.toSet())
      val sshHosts = sshHostStore.load()
      val enabledIds = profiles.filter { it.effectiveEnabled }.mapTo(HashSet()) { it.id }
      val storedHome = store.homeServerId()?.takeIf { it in enabledIds }
      // Parent gates hide runtime members, never the user's saved composition selection. When
      // its members are all denied, do not silently dial an unrelated outside profile at startup.
      val selectedGroup = groups.selectedGroupId?.takeIf { id -> groups.groups.any { it.id == id } }
      val allowedSelection = profiles.filter { profile ->
        profile.effectiveEnabled && (selectedGroup == null || groups.groups.firstOrNull { it.id == selectedGroup }?.memberIds?.contains(profile.id) == true)
      }
      val selectedProfile = store.selectedId()?.takeIf { id -> allowedSelection.any { it.id == id } }
        ?: storedHome?.takeIf { id -> allowedSelection.any { it.id == id } }
        ?: allowedSelection.firstOrNull()?.id
      LagoonState(
        profiles = profiles,
        sshHosts = sshHosts,
        deviceGroups = groups.groups,
        homeGroupId = selectedGroup,
        deviceGroupReset = groupLoad.resetLegacy,
        // 全部 is the default home; a selected group is the only other explicit home mode.
        allServers = selectedGroup == null,
        serverId = selectedProfile,
        homeServerId = storedHome ?: selectedProfile,
        message = if (groupLoad.resetLegacy) "设备分组已按新版规则重置，请重新创建" else null,
        messageTone = FeedbackTone.INFO,
        permissionPolicy = store.permissionPolicy()
      )
    }
  )
  val state: StateFlow<LagoonState> = mutable
  private val allSnapshots = mutableMapOf<String, ServerOverviewSnapshot>()
  /** Shared per-directory read health; a dead directory must not degrade every server's state. */
  private val directoryHealth = DirectoryHealth()
  private val allOverview = AllServersOverview(store, cache, scope, ::acceptAllSnapshot, { state.value.serverId },
    { id, event -> if (id == state.value.serverId) handleEvent(event) },
    { id ->
      if (id == state.value.serverId) trace(generation, ConnectMilestone.STREAM)
      if (id == state.value.serverId && api != null && connecting?.isActive != true) reconcileAfterStream()
    },
    directoryHealth, ::acceptSnapshotRemoved, remoteBackendFactory,
    sshClientFactory = { profile, _ -> sshHostManager.client(profile) })
  @Volatile private var api: HarnessClient? = null
  private var stream: Job? = null
  private var generation = 0
  private var refresh: Job? = null
  private var eventRefresh: Job? = null
  private var messageRefresh: Job? = null
  private val sendMutex = Mutex()
  private val referenceMutex = Mutex()
  private var searchSequence = 0L
  private var fileSequence = 0L
  private var selectionRevision = 0L
  private var catalogSequence = 0L
  private var messageSequence = 0L
  private var reconcile: Job? = null
  /** 上一次真正下发到系统的通知指纹（服务器 + 落点 + 渲染后的文案 + 后台活动标记）。 */
  private var lastSummary: Pair<Triple<String?, SessionTarget?, LiveUpdateContent>, List<SessionTarget>>? = null
  /** 拆岛的延迟复核任务：瞬时非活跃不立即取消，避免息屏岛 / 胶囊反复消失与重弹。 */
  private var summarySettle: Job? = null
  private var visibleConversation: Pair<String, String>? = null
  private val seenEvents = linkedSetOf<String>()
  private val draftWrites = mutableMapOf<String, Job>()
  /** Server, session and text of the latest composer keystroke; see [typeDraft]. */
  private data class TypedDraft(val server: String, val session: String?, val text: String)
  private var typedDraft: TypedDraft? = null
  /**
   * The server the blank "新会话" page is open on. While it matches [LagoonState.serverId], a
   * connect/loadAll must not restore that server's last session: the reader asked for a draft, not
   * for the conversation they had before.
   */
  private var draftServerId: String? = null
  /** Explicit workspace of the open draft; only meaningful while [draftServerId] is set. */
  private var draftProjectId: String? = null
  /** 从空白页切换服务器时保留已输入的正文；从首页重新开草稿时从空白开始。 */
  private var draftKeepsComposer = false
  private var preservedDraftText = ""
  /** An activity is started (ON_START…ON_STOP). The live stream runs only while foreground or monitoring. */
  @Volatile private var foreground = false
  /** [TaskMonitorService] keeps the process alive for running tasks; the stream may then run in background. */
  @Volatile private var monitoring = false
  private var backgroundStop: Job? = null
  private var connecting: Job? = null
  /** The connection the user was actively viewing when it was disabled; restored on re-enable. */
  private var lastDisabledActiveId: String? = null
  @Volatile private var lastFullRefreshAt = 0L
  /** 自动刷新频率；界面切换后立即生效（对账循环每次迭代都会读取）。 */
  @Volatile private var refreshMode: RefreshMode = RefreshMode.STANDARD
  /** Root sessions already handed to [TaskMonitorService] during this run. */
  private val monitorRequested = mutableSetOf<String>()
  /** 待处理事项通知的去抖任务：进入等待态后短暂延迟再发，期间被处理就不打扰。 */
  private val attentionJobs = mutableMapOf<String, Job>()
  /** The long-running independent “服务端已读” sweep; prevents a second tap from racing it. */
  private var serverReadJob: Job? = null
  /** 等待态事件的单调序号：对账只在读取期间没有新的等待事件时才清除等待态，避免事件/对读竞态造成抖动。 */
  private var waitingEventSeq = 0L
  /** Execution/shell lifecycle events fence older HTTP snapshots (not token/delta events). */
  private var workRevision = 0L
  private var childDiscovery: ChildDiscovery? = null
  /** Cached transcript-derived child identities, keyed by parent + message-list instance. */
  private var subagentScan: Triple<String?, List<Message>, Map<String, Long>>? = null
  /** Cached session titles, keyed by session-list instance. */
  private var sessionTitles: Pair<List<Session>, Map<String, String>>? = null
  /** Last task state this controller wrote per server/session, see [persistTask]. */
  private val persistedTasks = HashMap<String, Pair<TaskState, String?>>()
  /**
   * Writes a task to the store only when it changed. Every streamed event moves [TaskState.activeAt]
   * (the idle baseline); that alone is written at most every [TaskPersistence.ACTIVE_AT_INTERVAL_MS],
   * instead of a JSON read and write of every task on every event on the main thread.
   */
  private fun persistTask(server: String, task: TaskState, parent: String?) {
    val key = "$server\u0000${task.sessionId}"
    val previous = persistedTasks[key]
    if (!TaskPersistence.shouldWrite(previous?.first, previous?.second, task, parent)) return
    store.rememberTask(server, task, parent)
    persistedTasks[key] = task to parent
  }
  /** Descendant sweep for the open conversation's family (kept across polls). */
  private var familyDiscovery: ChildDiscovery? = null
  /** One shell output viewer is polled at a time. */
  private var shellOutputJob: Job? = null
  private val waitingEventSeqAt = mutableMapOf<String, Long>()
  private val resolvedPermissions = linkedSetOf<String>()
  /** Field timing of connect / reconnect / refresh attempts, keyed by [generation]. */
  private val connectTrace = ConnectTrace()
  /** Field timing of opening a conversation, keyed by [selectionRevision]. */
  private val openTrace = ConnectTrace()
  /** Recently viewed transcripts kept in memory, so switching back paints without decrypting the cache. */
  private val transcriptMemory = TranscriptMemory()
  /** Per-session window cache: navigating back must not clear the page or fetch it again. */
  private var sessionViewsStorage: SessionViewCache? = null
  private val sessionViews: SessionViewCache
    get() = sessionViewsStorage ?: SessionViewCache().also { sessionViewsStorage = it }
  /** Agent/model/command catalogs are directory-scoped and stable for minutes. */
  private var choicesCacheStorage: ChoicesCache? = null
  private val choicesCache: ChoicesCache
    get() = choicesCacheStorage ?: ChoicesCache().also { choicesCacheStorage = it }
  /** One cancellable job per session/choice load; a switch aborts the previous network read. */
  private var sessionLoadJobsStorage: KeyedJobs? = null
  private val sessionLoadJobs: KeyedJobs
    get() = sessionLoadJobsStorage ?: KeyedJobs().also { sessionLoadJobsStorage = it }
  /** The blank page's first prompt, held until the selected server becomes interactive. */
  private var pendingDraft: PendingDraft? = null
  /** Workspace events of the selected DSH connection; replaced on every connect. */
  private var workspaceEvents: Job? = null
  /** Transport phase polling (no network): SSH bootstrap / relay handshake copy. */
  private var transportJob: Job? = null
  /** Background refresh after a transient transcript failure; cancelled on selection change. */
  private var transcriptRetry: Job? = null
  private var transcriptRetryCountsStorage: MutableMap<String, Int>? = null
  private val transcriptRetryCounts: MutableMap<String, Int>
    get() = transcriptRetryCountsStorage ?: mutableMapOf<String, Int>().also { transcriptRetryCountsStorage = it }
  /** Network callbacks fire in bursts; refresh at most once per few seconds. */
  @Volatile private var lastNetworkRefreshAt = 0L
  /** Prompts submitted while the server was unreachable; delivered once it is interactive again. */
  private val queuedSends = mutableListOf<QueuedPrompt>()

  private fun initializeConnectionGraph() {
    try {
      when (val result = connectionGraphStore.loadOrMigrate()) {
        is ConnectionGraphStore.LoadResult.Loaded -> {
          connectionGraphMutable.value = result.graph
          connectionGraphWritable = true
          persistConnectionGraph()
        }
        is ConnectionGraphStore.LoadResult.Unsupported -> {
          connectionGraphWritable = false
          Diagnostics.warn("ConnectionGraph", "连接图版本 ${result.version} 高于当前版本，保留原图并继续使用旧连接资料")
        }
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      connectionGraphWritable = false
      Diagnostics.warn("ConnectionGraph", "连接图读取/迁移失败，继续使用旧连接资料", error)
    }
  }

  init {
    initializeConnectionGraph()
    // Recovery can promote a prepared parent denial after the initial state was constructed.
    // Refresh runtime gates BEFORE any monitor or selected client is allowed to dial.
    val startupProfiles = store.profiles()
    updateState { current -> current.copy(profiles = startupProfiles,
      serverId = current.serverId?.takeIf { id -> startupProfiles.any { it.id == id && it.effectiveEnabled } },
      homeServerId = current.homeServerId?.takeIf { id -> startupProfiles.any { it.id == id && it.effectiveEnabled } }) }
    store.migrateLocalProjects()
    // OpenCode transcripts moved to the verified store; drop their truncated SharedPreferences copies.
    if (transcriptSync != null) scope.launch(storeDispatcher) {
      attempt { store.profiles().filter { it.kind == ServerKind.OPENCODE }.forEach { cache.deleteTranscripts(it.id) } }
    }
    refreshMode = store.refreshMode()
    // Single publisher for the server-wide island summary, so the system notification never drifts
    // from the in-app island: both read the same derived `summary` on every state emission.
    scope.launch { state.collect { state ->
      // Dedupe on the content the system actually renders, not on the transient task detail: a running
      // task changes detail on every tool step, and re-posting the same island each time makes some
      // systems replay their expand animation on the AOD island.
      val signature = Triple(TaskNotifications.ALL_SERVERS_ID, state.globalSummaryTarget, LiveUpdateContent.of(state.globalSummary)) to
        state.globalBackgroundTargets
      if (signature != lastSummary) { lastSummary = signature; publishSummary(state); ensureMonitoring(state) }
    } }
    allOverview.start(mutable.value.profiles, include = setOfNotNull(mutable.value.serverId))
    mutable.value.serverId?.takeIf { id -> mutable.value.profiles.any { it.id == id && it.effectiveEnabled } }?.let { connect(it, preserveHome = true) }
    observeNetwork()
    scheduleBackgroundSync()
  }
  private fun scheduleBackgroundSync() {
    attempt { BackgroundSyncWorker.schedule(appContext, store.profiles().any { it.effectiveEnabled }) }
      .onFailure { Diagnostics.warn("BackgroundSync", "无法安排后台定时刷新", it) }
  }

  /** A monitor was deliberately stopped: drop its stale tasks/notices from the global island. */
  private fun acceptSnapshotRemoved(id: String) {
    if (allSnapshots.remove(id) == null) return
    updateState { it.copy(allServerSnapshots = allSnapshots.toMap()) }
  }

  /** Rebuilds the home/notification projection after one overview connection changes. */
  private fun acceptAllSnapshot(snapshot: ServerOverviewSnapshot) {
    val profiles = store.profiles()
    val currentProfile = profiles.firstOrNull { it.id == snapshot.serverId }
    if (currentProfile == null || !currentProfile.effectiveEnabled) {
      // A monitor can publish once more while it is being removed. A disabled profile must never
      // reappear in the home/notification projection, even for that frame.
      if (allSnapshots.remove(snapshot.serverId) != null) updateState { it.copy(allServerSnapshots = allSnapshots.toMap()) }
      return
    }
    val normalized = snapshot.copy(profile = currentProfile)
    val previous = allSnapshots.put(normalized.serverId, normalized)
    updateState { current ->
      val streamDropAt = when {
        current.serverId != snapshot.serverId -> current.streamDownSince
        snapshot.streamConnected -> 0L
        current.streamConnected || previous?.streamConnected == true ->
          current.streamDownSince.takeIf { it > 0 } ?: System.currentTimeMillis()
        else -> current.streamDownSince
      }
      withSummary(current.copy(profiles = profiles, allServerSnapshots = allSnapshots.toMap(),
        streamConnected = if (current.serverId == snapshot.serverId) snapshot.streamConnected else current.streamConnected,
        streamDownSince = streamDropAt,
        autoApprovalFailures = if (current.serverId == snapshot.serverId) snapshot.autoApprovalFailures else current.autoApprovalFailures))
    }
    // The conversation reducer publishes its own attention/results. Never have two owners.
    if (state.value.serverId == snapshot.serverId) {
      val failures = normalized.autoApprovalFailures - previous?.autoApprovalFailures.orEmpty()
      if (failures.isNotEmpty() && !state.value.permissionPolicy.hide) {
        updateState { it.copy(error = "自动处理失败，正在重试；可在对话中手动处理") }
        state.value.permissions.filter { it.id in failures }.forEach { notifyAttention(it.sessionId) }
      }
      return
    }

    // Clear obsolete attention before publishing a result which uses the same notification id.
    (previous?.permissions?.map { it.sessionId }.orEmpty() + previous?.questions?.map { it.sessionId }.orEmpty()).distinct().forEach { session ->
      if (normalized.questions.none { it.sessionId == session } && normalized.permissions.none { it.sessionId == session })
        notifications.cancel(normalized.serverId, session)
    }
    // Sessions the overview dropped (deleted on the server, pruned as ghosts): their notifications
    // must not stay tappable forever.
    (previous?.sessions?.map { it.id }?.toSet().orEmpty() - normalized.sessions.map { it.id }.toSet()).forEach { session ->
      notifications.cancel(normalized.serverId, session)
    }

    // The overview reader does not infer notifications from historical rows. Only rows that appeared
    // after this server was already being watched are forwarded to the ordinary per-session channel.
    // The very first snapshot is a baseline: old unread results (including ones retained across a
    // disable/enable cycle) must not replay as a notification burst.
    val oldNoticeIds = previous?.notices?.map { it.id }?.toSet().orEmpty()
    if (previous != null) normalized.notices.filter { it.id !in oldNoticeIds && !it.viewed }.forEach { notice ->
      val session = normalized.sessions.firstOrNull { it.id == notice.sessionId } ?: return@forEach
      val task = TaskState(session.id, if (notice.error) TaskPhase.FAILED else TaskPhase.COMPLETED,
        if (notice.error) "执行失败" else "任务已完成", notice.time, finishedAt = notice.time)
      notifications.show(normalized.profile, session, task)
    }
    val oldPermissionIds = previous?.permissions?.map { it.id }?.toSet().orEmpty()
    val oldQuestionIds = previous?.questions?.map { it.id }?.toSet().orEmpty()
    normalized.permissions.filter { it.id !in oldPermissionIds || it.id in normalized.autoApprovalFailures - previous?.autoApprovalFailures.orEmpty() }.forEach { request ->
      notifyOverviewAttention(normalized.serverId, request.sessionId)
    }
    normalized.questions.filter { it.id !in oldQuestionIds }.forEach { request ->
      notifyOverviewAttention(normalized.serverId, request.sessionId)
    }
  }
  /**
   * 下发服务器总览（实时更新）。真正开始时立即发布；一旦读到的不是 ACTIVE，先等一个短暂窗口复核，
   * 只有持续非活跃才拆岛。多步任务在步骤之间会瞬时变为 idle，立即拆岛会让息屏岛 / 胶囊反复消失与重弹。
   */
  private fun publishSummary(state: LagoonState) {
    val profile = TaskNotifications.ALL_PROFILE
    if (LiveUpdateContent.stageOf(state.globalSummary) == LiveUpdateStage.ACTIVE) {
      summarySettle?.cancel(); summarySettle = null
      notifications.showSummary(profile, state.globalSummary, state.globalSummaryTarget, state.globalBackgroundTargets)
      return
    }
    summarySettle?.cancel()
    // The foreground service owns the active → background-only transition (it switches channels on
    // the same state emission). Letting the controller cancel its foreground notification would
    // briefly remove the only notification Android requires for the service.
    if (monitoring) return
    summarySettle = scope.launch {
      delay(SUMMARY_SETTLE_MILLIS)
      val current = this@LagoonController.state.value
      notifications.showSummary(profile, current.globalSummary, current.globalSummaryTarget, current.globalBackgroundTargets)
    }
  }
  fun credentials(serverId: String): ServerCredentials = store.credentials(serverId)
  fun deviceId(): String = store.deviceId()
  fun currentRefreshMode(): RefreshMode = refreshMode
  fun setRefreshMode(mode: RefreshMode) {
    if (refreshMode == mode) return
    refreshMode = mode
    store.rememberRefreshMode(mode)
  }
  /** [onCookie] 收到测试连接换到的会话 Cookie，保存资料时一并落盘，避免保存后再用令牌换一次。 */
  suspend fun testServer(profile: ServerProfile, credentials: ServerCredentials, onCookie: (String) -> Unit = {}): String {
    val saved = store.profiles().firstOrNull { it.id == profile.id }
    check(saved == null || saved.parentEnabled) { "所属接入源或主机已停用，请先启用父级" }
    // A second Remote connection with the same client identity would replace the live one. Reuse the
    // persistent monitor's client for any already-saved Remote profile.
    if (profile.kind == ServerKind.DSH_REMOTE && store.profiles().any { it.id == profile.id }) {
      val existing = runCatching { allOverview.client(profile.id) }.getOrNull()
      if (existing != null) return existing.health()
    }
    if (profile.sshHostId != null) {
      // Saved SSH profiles are tested through testSshHost, which dials the form draft on its own link.
      // Reaching here means a caller asked to probe the already-saved host; reuse its live client so a
      // second SSH session does not fight the monitor for the same remote dsh web.
      if (store.profiles().any { it.id == profile.id }) {
        val existing = runCatching { allOverview.client(profile.id) }.getOrNull()
        if (existing != null) return existing.health()
      }
      val client = sshHostManager.client(profile)
      return try {
        val version = client.health()
        client.projects()
        version
      } finally { client.close() }
    }
    val client = createHarnessClient(profile, credentials, onCookie = onCookie,
      remoteBackend = if (profile.kind == ServerKind.DSH_REMOTE) remoteBackendFactory.create(profile, credentials) else null,
      sshClientFactory = { p, _ -> sshHostManager.client(p) })
    return try {
      val version = client.health()
      client.projects()
      version
    } finally { client.close() }
  }
  fun notifyOverviewAction(serverId: String) = allOverview.refresh(serverId)

  private fun clientFor(serverId: String): HarnessClient? =
    if (store.profiles().none { it.id == serverId && it.effectiveEnabled }) null
    else if (serverId == state.value.serverId) {
      // A monitor rebuild can close the cached client while this screen still holds it. Resolve the
      // current lease instead of calling into a closed transport.
      api?.takeUnless { it.isClosed } ?: runCatching { allOverview.client(serverId) }.getOrNull()?.also { api = it }
    } else runCatching { allOverview.client(serverId) }.getOrNull()

  internal suspend fun pluginBundles(serverId: String): List<RemotePluginBundle> =
    clientFor(serverId)?.pluginBundles().orEmpty()

  internal suspend fun pluginEntries(serverId: String): List<RemotePluginEntry> =
    clientFor(serverId)?.pluginEntries().orEmpty()

  internal suspend fun setPluginBundle(serverId: String, name: String, enabled: Boolean): Boolean =
    clientFor(serverId)?.setPluginBundle(name, enabled) == true

  internal suspend fun setPluginEntry(serverId: String, entryId: String, enabled: Boolean): Boolean =
    clientFor(serverId)?.setPluginEntry(entryId, enabled) == true

  internal suspend fun credentialStatus(serverId: String, ref: String): RemoteCredentialStatus? =
    clientFor(serverId)?.credentialStatus(listOf(ref))?.get(ref)

  internal suspend fun setCredential(serverId: String, ref: String, value: String): Boolean =
    clientFor(serverId)?.setCredential(ref, value) == true

  internal suspend fun createProviderRoute(serverId: String, route: String, displayName: String, api: String,
    baseUrl: String, apiKeyEnv: String, modelId: String, apiKey: String): Boolean =
    clientFor(serverId)?.createProviderRoute(route, displayName, api, baseUrl, apiKeyEnv, modelId, apiKey) == true

  internal suspend fun clearCredential(serverId: String, ref: String): Boolean =
    clientFor(serverId)?.clearCredential(ref) == true

  /**
   * Reads the goal shown above the composer. `goals/get` is the only source of the process-local
   * activation ("暂停续跑"), but it fails while the Agent is not live; a failed read keeps the last
   * durable projection instead of making the goal vanish, and the next successful read replaces it.
   */
  fun loadGoal() = withSession("goal-refresh") { op, client, session ->
    val fetched = try { Result.success(client.goal(session.id)) } catch (error: Exception) {
      if (error is CancellationException) throw error
      Result.failure(error)
    }
    if (op.isCurrent(this)) op.commit { current ->
      if (fetched.isFailure) current else current.copy(goal = fetched.getOrNull())
    }
  }

  fun loadSchedules() = withSession("schedules") { op, client, session ->
    if (op.isCurrent(this)) op.commit { it.copy(schedulesLoading = true) }
    try {
      val schedules = client.schedules(session.id)
      if (op.isCurrent(this)) op.commit { it.copy(schedules = schedules, schedulesLoading = false) }
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      if (op.isCurrent(this)) op.commit { it.copy(schedulesLoading = false) }
      throw error
    }
  }

  fun createGoal(objective: String, maxRounds: Int? = null, onCreated: (() -> Unit)? = null) = withSession("goal") { op, client, session ->
    check(objective.isNotBlank()) { "目标不能为空" }
    if (!client.createGoal(session.id, objective.trim(), maxRounds)) throw ApiException(501, "此服务器不支持长期目标")
    if (op.isCurrent(this)) {
      loadGoal()
      onCreated?.invoke()
    }
  }

  fun goalAction(action: String) = withSession("goal") { op, client, session ->
    val current = state.value.goal ?: throw ApiException(409, "当前会话没有目标")
    if (!client.goalAction(session.id, current.id, current.revision, action)) throw ApiException(501, "此服务器不支持目标操作")
    if (op.isCurrent(this)) {
      if (action == "clear") op.commit { it.copy(goal = null) }
      loadGoal()
    }
  }

  /** Set by the composition root; the connection strip asks it to open the Remote recovery sheet. */
  @Volatile var remoteRecoveryHandler: (() -> Unit)? = null

  fun openRemoteRecovery() { remoteRecoveryHandler?.invoke() }

  /** Full connection report for the diagnostic dialog; only DSH Remote connections implement it. */
  internal suspend fun remoteDoctor(serverId: String? = state.value.serverId): RemoteDoctor? {
    val id = serverId ?: return null
    val client = clientFor(id) ?: return null
    return (client as? RemoteDoctorSource)?.remoteDoctor(appVersion())
  }

  /** Measures one encrypted RPC round trip on the requested Remote connection, not only the open chat. */
  internal suspend fun probeRemote(serverId: String? = state.value.serverId): RemoteProbe? {
    val id = serverId ?: return null
    val profile = store.profiles().firstOrNull { it.id == id } ?: return null
    if (profile.kind != ServerKind.DSH_REMOTE || !profile.effectiveEnabled) return null
    return clientFor(id)?.probeRemote()
  }
  fun clearError() = updateState { it.copy(error = null) }
  /**
   * The live stream (re)connected. Events between the last read and the handshake may be lost, so the
   * control plane and the open transcript's tail are re-read — but only those: the catalog paging,
   * model/agent/command lists, children and diff did not go stale because a socket reconnected.
   */
  private fun reconcileAfterStream() {
    if (!state.value.connected) { reloadConversation(deep = false); return }
    if (refresh?.isActive == true) return
    // The connect read and the event-stream handshake often finish within the same second. Do not
    // repeat the control read for a stream that only just opened; a real reconnect (seconds later)
    // still gets its gap repair.
    if (System.currentTimeMillis() - state.value.lastContactAt < 2_000L) return
    val token = generation
    refresh = scope.launch {
      try { loadAll(token, controlOnly = true) } catch (error: Exception) {
        if (error is CancellationException) throw error
        Diagnostics.warn("LagoonController", "实时连接恢复后的对账失败", error)
        if (token != generation) return@launch
        if (error.isRetryableTransportFailure()) {
          val serverId = state.value.serverId ?: return@launch
          updateState { it.copy(connected = false, error = null) }
          scheduleReconnect(serverId, token, attempt = 1, reason = "实时同步恢复失败，正在重连")
        } else updateState { it.copy(degraded = true) }
      }
    }
  }
  /** The default network last reported by Android; a different one means sockets may be stale. */
  private var defaultNetwork: Any? = null
  private var networkSeen = false
  /** Wall clock when the app last left the foreground; 0 while visible. */
  @Volatile private var backgroundedAt = 0L
  /**
   * Holds a prompt typed while the server is unreachable. It is shown at once as a “等待连接后发送” row
   * and the composer is cleared; one pending prompt per conversation, so a second one is appended to
   * it. Nothing typed is ever dropped: if it is not delivered, it goes back into the draft.
   */
  private fun queuePrompt(text: String, accepted: (() -> Unit)?) {
    val current = state.value
    val serverId = current.serverId ?: return
    val sessionId = current.sessionId ?: return
    if (text.isBlank()) { updateState { it.copy(error = "附件需要在连接恢复后发送") }; return }
    val existing = queuedSends.firstOrNull { it.serverId == serverId && it.sessionId == sessionId }
    val prompt = existing?.copy(text = existing.text + "\n\n" + text)
      ?: QueuedPrompt(serverId, sessionId, text, PENDING_PREFIX + MessageIds.ascending(), System.currentTimeMillis())
    queuedSends.remove(existing); queuedSends += prompt
    updateState { it.copy(messages = it.messages.filterNot { message -> message.id == prompt.messageId } + prompt.row()) }
    accepted?.invoke()
  }
  /** [messages] plus the conversation's pending prompt row, which no server read or cache paint knows about. */
  private fun withPending(serverId: String?, sessionId: String?, messages: List<Message>): List<Message> {
    val prompt = queuedSends.firstOrNull { it.serverId == serverId && it.sessionId == sessionId } ?: return messages
    return if (messages.any { it.id == prompt.messageId }) messages else messages + prompt.row()
  }
  /** Delivers the open conversation's pending prompt once the server is interactive. */
  private fun flushQueuedSends() {
    val current = state.value
    if (queuedSends.isEmpty() || !current.connected) return
    val prompt = queuedSends.firstOrNull { it.serverId == current.serverId && it.sessionId == current.sessionId } ?: return
    queuedSends.remove(prompt)
    updateState { it.copy(messages = it.messages.filterNot { message -> message.id == prompt.messageId }) }
    deliver(prompt.text, prompt.serverId, prompt.sessionId, accepted = null, onFailed = { restoreDraft(prompt) })
  }
  /** Pending prompts of conversations the user is leaving go back into their drafts, undelivered. */
  private fun returnQueuedToDrafts(keep: (QueuedPrompt) -> Boolean = { false }) {
    val leaving = queuedSends.filterNot(keep)
    if (leaving.isEmpty()) return
    queuedSends.removeAll(leaving)
    val ids = leaving.map { it.messageId }.toSet()
    updateState { it.copy(messages = it.messages.filterNot { message -> message.id in ids }) }
    leaving.forEach(::restoreDraft)
  }
  private fun restoreDraft(prompt: QueuedPrompt) {
    val current = state.value
    if (current.serverId == prompt.serverId && current.sessionId == prompt.sessionId) {
      updateDraft(listOf(prompt.text, liveDraft(current)).filter(String::isNotBlank).joinToString("\n\n"))
    } else {
      draftWrites.remove("${prompt.serverId}:${prompt.sessionId}")?.cancel()
      val stored = attempt { store.draft(prompt.serverId, prompt.sessionId) }.getOrDefault("")
      attempt { store.rememberDraft(prompt.serverId, prompt.sessionId, listOf(prompt.text, stored).filter(String::isNotBlank).joinToString("\n\n")) }
    }
  }
  /**
   * After a full read, the few most recent other conversations are brought up to date in the
   * transcript store (or, without one, their first page is kept in memory), so opening one paints at
   * once and verifies with a single small read. OpenCode only (a DSH page read changes its live
   * subscriptions) and never on a metered network.
   */
  private fun prefetchTranscripts(token: Int, client: HarnessClient) {
    val current = state.value
    val serverId = current.serverId ?: return
    if (current.server?.kind != ServerKind.OPENCODE) return
    val sync = transcriptSync?.takeIf { client.cacheableTranscripts }
    if (sync != null) {
      val candidates = current.sessions.filter { it.parentId == null && !it.archived && it.id != current.sessionId }.take(PREFETCH_SESSIONS)
      if (candidates.isEmpty()) return
      scope.launch {
        if (meteredNetwork()) return@launch
        for (session in candidates) {
          if (token != generation) return@launch
          // Already current in the store: nothing to read.
          val info = kotlinx.coroutines.withContext(storeDispatcher) { attempt { transcripts?.info(serverId, session.id) }.getOrNull() }
          if (info != null && session.updated > 0 && info.sessionUpdated == session.updated) continue
          attempt { sync.open(client, serverId, session) }.onFailure { Diagnostics.warn("TranscriptSync", "预取会话失败", it) }
        }
      }
      return
    }
    val targets = current.sessions.asSequence()
      .filter { it.parentId == null && !it.archived && it.id != current.sessionId && transcriptMemory.get(serverId, it.id) == null }
      .take(PREFETCH_SESSIONS).toList()
    if (targets.isEmpty()) return
    scope.launch {
      if (meteredNetwork()) return@launch
      for (session in targets) {
        if (token != generation) return@launch
        val page = kotlinx.coroutines.withContext(Dispatchers.IO) { attempt { client.messagesPage(session.id) }.getOrNull() } ?: continue
        if (token == generation && transcriptMemory.get(serverId, session.id) == null) transcriptMemory.put(serverId, session.id, page.items)
      }
    }
  }
  private fun meteredNetwork(): Boolean =
    attempt { (appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered }.getOrDefault(true)
  /**
   * Opening only checks the newest page against the stored run. About once a week per conversation,
   * on an unmetered network, the whole run is compared too; a difference drops it and the open
   * conversation is read again from the server.
   */
  private fun scheduleFullCheck(sync: TranscriptSync, client: HarnessClient, serverId: String, session: Session, token: Int) {
    scope.launch {
      if (!sync.dueForFullCheck(serverId, session.id) || meteredNetwork()) return@launch
      val matched = attempt { sync.verifyAll(client, serverId, session) }.getOrNull()
      if (matched == false && token == generation && state.value.serverId == serverId && state.value.sessionId == session.id) {
        transcriptMemory.forget(serverId, session.id)
        selectSession(session.id)
      }
    }
  }
  /** Size of the on-device transcript copy, for settings; null when there is no store. */
  internal suspend fun transcriptCacheUsage(): TranscriptStore.Usage? = transcripts?.let { store ->
    kotlinx.coroutines.withContext(storeDispatcher) { attempt { store.usage() }.getOrNull() }
  }
  /** Drops every stored transcript (the server keeps them); conversations are read again on open. */
  fun clearTranscriptCache(onCleared: () -> Unit = {}) {
    transcriptMemory.clear()
    sessionViews.clear()
    conversationPool.clear()
    scope.launch {
      kotlinx.coroutines.withContext(storeDispatcher) {
        attempt { transcripts?.clear() }
        attempt { store.profiles().forEach { cache.deleteTranscripts(it.id) } }
      }
      onCleared()
    }
  }
  /** “立即重试” from the status strip: skip the backoff wait and read the server now. */
  fun retryNow() {
    val id = state.value.serverId ?: return
    if (api == null) connect(id, preserveHome = true) else reload()
  }
  private fun retryReason(error: String?): String = error?.takeIf { it.isNotBlank() } ?: "连接失败"
  private fun setStage(token: Int, stage: ConnectStage) {
    if (token != generation) return
    updateState { if (it.connection.stage == stage) it else it.copy(connection = ConnectionStatus(stage, System.currentTimeMillis())) }
  }
  /** Moves the connecting stages forward; a background refresh of an interactive screen stays READY. */
  private fun advanceStage(token: Int, stage: ConnectStage) {
    if (state.value.connection.stage != ConnectStage.READY) setStage(token, stage)
  }
  /** Cold start: show the last catalog this device saw while the server is read, never an empty list. */
  private fun paintCachedCatalog(id: String, token: Int) {
    scope.launch {
      val cached = kotlinx.coroutines.withContext(Dispatchers.IO) { attempt { cache.catalog(id) }.getOrNull() } ?: return@launch
      if (token != generation || state.value.serverId != id || state.value.connected || state.value.sessions.isNotEmpty()) return@launch
      val projects = (cached.first + store.localProjects(id)).distinctBy { normalizedDirectory(it.directory) }.map { it.copy(serverId = id) }
      if (cached.second.isEmpty()) return@launch
      updateState { current -> withSummary(current.copy(sessions = cached.second.map { scoped(id, it) }.sortedWith(sessionActivityOrder),
        projects = current.projects.takeIf { it.isNotEmpty() } ?: projects)) }
      trace(token, ConnectMilestone.CACHE_PAINT)
    }
  }
  /** Transcripts of this server live in [transcripts]: an OpenCode server with an open database. */
  private fun storesTranscripts(serverId: String?): Boolean =
    transcriptSync != null && serverId != null && state.value.profiles.firstOrNull { it.id == serverId }?.kind == ServerKind.OPENCODE
  /** The device's last copy of a conversation: the verified store for OpenCode, the snapshot cache otherwise. */
  private suspend fun cachedTranscript(serverId: String, sessionId: String): List<Message> = kotlinx.coroutines.withContext(storeDispatcher) {
    attempt { if (storesTranscripts(serverId)) transcriptSync!!.paint(serverId, sessionId) else cache.messages(serverId, sessionId) }.getOrDefault(emptyList())
  }
  /** [cachedTranscript] plus where its older history continues (stored rows are readable offline). */
  private suspend fun cachedTranscriptWindow(serverId: String, sessionId: String): Pair<List<Message>, String?> =
    if (!storesTranscripts(serverId)) cachedTranscript(serverId, sessionId) to null
    else kotlinx.coroutines.withContext(storeDispatcher) { attempt { transcriptSync!!.paintWindow(serverId, sessionId) }.getOrDefault(emptyList<Message>() to null) }
  /** False when the copy is known to be cut short; the store never truncates what it keeps. */
  private suspend fun cachedTranscriptComplete(serverId: String, sessionId: String): Boolean =
    if (storesTranscripts(serverId)) true else kotlinx.coroutines.withContext(storeDispatcher) { cache.messagesComplete(serverId, sessionId) }
  private fun forgetStoredTranscript(serverId: String, sessionId: String) {
    if (transcripts != null) scope.launch(storeDispatcher) { attempt { transcripts.delete(serverId, sessionId) } }
  }
  /**
   * Shows the conversation's last known transcript (memory first, then the encrypted cache) while the
   * server copy is read. Only fills an empty, still-loading transcript of the same selection.
   */
  private fun showCachedTranscript(serverId: String, sessionId: String, revision: Long) {
    fun stillWaiting() = state.value.serverId == serverId && state.value.sessionId == sessionId && selectionRevision == revision &&
      state.value.messages.persistable().isEmpty() && state.value.resource("messages").state in setOf(ResourceState.LOADING, ResourceState.NOT_LOADED)
    fun show(messages: List<Message>) {
      if (messages.isEmpty() || !stillWaiting()) return
      updateState { withSummary(it.copy(messages = withPending(serverId, sessionId, messages), provisionalMessageIds = messages.mapTo(mutableSetOf()) { message -> message.id })) }
      openTrace.mark(revision, ConnectMilestone.CACHE_PAINT)
    }
    transcriptMemory.get(serverId, sessionId)?.let { show(it); return }
    scope.launch { show(cachedTranscript(serverId, sessionId)) }
  }
  /** Recent connection and conversation-open timings, one line each, for the copied diagnostics. */
  fun connectionDiagnostics(): String = ((connectTrace.reports() + openTrace.reports()).map { it.line() } +
    listOfNotNull(transcriptSync?.statsLine(), LoadMetrics.line().takeIf(String::isNotBlank))).joinToString("\n")
  private fun trace(token: Int, milestone: ConnectMilestone) {
    connectTrace.mark(token.toLong(), milestone)?.let { Diagnostics.info("ConnectTrace", "${milestone.label} ${it}ms") }
  }
  fun clearMessage() = updateState { it.copy(message = null) }
  fun showNotice(text: String, tone: FeedbackTone = FeedbackTone.INFO) =
    updateState { it.copy(message = text, messageTone = tone) }

  fun setPermissionPolicy(policy: PermissionPolicy) {
    store.rememberPermissionPolicy(policy)
    updateState { withSummary(it.copy(permissionPolicy = policy)) }
    state.value.serverId?.let { allOverview.consider(it, state.value.permissions) }
    allOverview.policyChanged()
    state.value.serverSnapshots().values.forEach { snapshot ->
      snapshot.permissions.forEach { request ->
        if (!policy.visible(request.id, snapshot.autoApprovalFailures)) notifications.cancel(snapshot.serverId, request.sessionId)
      }
    }
  }

  /** Android connectivity is a recovery hint, not permission to rebuild every connection. */
  private fun observeNetwork() {
    attempt {
      val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
      manager.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
          scope.launch {
            // The default network changed (Wi-Fi ↔ cellular, or back after a loss): sockets opened on the
            // previous network may be half-open, so every stream reconnects now instead of hanging.
            val switched = networkSeen && network != defaultNetwork
            networkSeen = true; defaultNetwork = network
            if (switched) {
              allOverview.restartStreams()
              sshHostManager.kickAll()
            }
            val now = System.currentTimeMillis()
            if (now - lastNetworkRefreshAt > NETWORK_REFRESH_DEBOUNCE_MILLIS) {
              lastNetworkRefreshAt = now
              allOverview.refreshAll()
              if (!state.value.connected) reloadConversation(deep = false)
            }
          }
        }
        override fun onLost(network: android.net.Network) {
          scope.launch { if (network == defaultNetwork) defaultNetwork = null }
        }
      })
    }.onFailure { Diagnostics.warn("Network", "无法监听网络恢复", it) }
  }

  /**
   * Changes only the home projection. 全部 is the implicit aggregate; the only other home mode is
   * an explicit group. Connections and the open conversation remain alive.
   */
  fun setAllServers(enabled: Boolean) {
    if (!enabled) {
      val group = state.value.homeGroup
      if (group != null) selectGroup(group.id)
      else if (!state.value.allServers) updateState { it.copy(allServers = true, homeGroupId = null) }
      return
    }
    if (state.value.allServers && state.value.homeGroupId == null) return
    store.rememberAllServers(true)
    saveDeviceGroups(state.value.deviceGroups, null)
    updateState { it.copy(allServers = true, homeGroupId = null, scopeWorkspaceKey = null, scopeProjectId = null) }
  }

  /** Persists a device-group edit and publishes it without touching any server connection. */
  private fun saveDeviceGroups(groups: List<DeviceGroup>, selected: String?) {
    val clean = DeviceGroupState(groups, selected).sanitized(store.profiles().map { it.id }.toSet())
    try {
      deviceGroupStore.save(clean)
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (error: Exception) {
      Diagnostics.warn("LagoonController", "设备分组保存失败", error)
      updateState { it.copy(error = "设备分组保存失败") }
      return
    }
    updateState {
      it.copy(
        deviceGroups = clean.groups,
        homeGroupId = clean.selectedGroupId,
        // 全部 is the fallback home whenever no explicit group is selected.
        allServers = if (clean.selectedGroupId == null) true else it.allServers
      )
    }
    persistConnectionGraph(store.profiles(), clean.groups)
    allOverview.start(store.profiles(), include = setOfNotNull(state.value.serverId))
  }

  /** A group edit can move the writable profile out of the selected group. Pick a surviving member. */
  private fun ensureSelectedGroupActive() {
    val group = state.value.homeGroup ?: return
    val active = state.value.server?.takeIf { it.effectiveEnabled }
    if (active == null || active.id !in group.memberIds) selectGroup(group.id)
  }

  fun createDevice(name: String, memberIds: List<String>): String {
    val valid = memberIds.filter { id -> state.value.profiles.any { it.id == id } }.distinct()
    val label = name.trim().take(128)
    if (label.isBlank() || valid.isEmpty()) return ""
    val id = java.util.UUID.randomUUID().toString()
    val groups = state.value.deviceGroups.map { group ->
      group.copy(memberIds = group.memberIds.filterNot { it in valid })
    }.filter { it.memberIds.isNotEmpty() } + DeviceGroup(
      id = id, name = label, memberIds = valid, updatedAt = System.currentTimeMillis()
    )
    saveDeviceGroups(groups, state.value.homeGroupId)
    ensureSelectedGroupActive()
    return id
  }

  fun updateDevice(id: String, name: String, memberIds: List<String>) {
    val label = name.trim().take(128)
    val valid = memberIds.filter { profileId -> state.value.profiles.any { it.id == profileId } }.distinct()
    if (label.isBlank() || valid.isEmpty() || state.value.deviceGroups.none { it.id == id }) return
    val groups = state.value.deviceGroups.map { group ->
      when (group.id) {
        id -> group.copy(name = label, memberIds = valid, updatedAt = System.currentTimeMillis())
        else -> group.copy(memberIds = group.memberIds.filterNot { it in valid })
      }
    }.filter { it.memberIds.isNotEmpty() }
    saveDeviceGroups(groups, state.value.homeGroupId)
    if (state.value.homeGroupId == id) {
      val active = state.value.serverId?.takeIf { it in valid }
      if (active == null) selectGroup(id)
    }
    ensureSelectedGroupActive()
  }

  fun deleteDevice(id: String) {
    if (state.value.deviceGroups.none { it.id == id }) return
    val groups = state.value.deviceGroups.filterNot { it.id == id }
    saveDeviceGroups(groups, state.value.homeGroupId?.takeIf { it != id })
  }

  fun moveProfile(profileId: String, groupId: String?) {
    if (state.value.profiles.none { it.id == profileId }) return
    if (groupId != null && state.value.deviceGroups.none { it.id == groupId }) return
    val groups = state.value.deviceGroups.map { group ->
      val without = group.copy(memberIds = group.memberIds.filterNot { it == profileId })
      if (group.id == groupId) without.copy(memberIds = without.memberIds + profileId, updatedAt = System.currentTimeMillis()) else without
    }.filter { it.memberIds.isNotEmpty() }
    saveDeviceGroups(groups, state.value.homeGroupId)
    ensureSelectedGroupActive()
  }

  /** Selects a device tab: a group keeps its last active profile, a singleton connects directly. */
  fun selectGroup(id: String, profileId: String? = null) {
    val group = state.value.deviceGroups.firstOrNull { it.id == id } ?: return
    val members = group.memberIds.mapNotNull { member -> state.value.profiles.firstOrNull { it.id == member && it.effectiveEnabled } }
    if (members.isEmpty()) {
      updateState { it.copy(message = "这个设备组没有启用的连接", messageTone = FeedbackTone.WARNING) }
      return
    }
    val current = state.value.serverId
    val active: ServerProfile = profileId?.let { requested -> members.firstOrNull { it.id == requested } }
      ?: current?.let { selected -> members.firstOrNull { it.id == selected } }
      ?: members.firstOrNull { profile -> state.value.allServerSnapshots[profile.id]?.connected == true }
      ?: members.first()
    store.rememberAllServers(false)
    saveDeviceGroups(state.value.deviceGroups, id)
    updateState { it.copy(allServers = false, homeGroupId = id, scopeWorkspaceKey = null, scopeProjectId = null) }
    if (active.id != state.value.serverId || !state.value.connected) {
      connect(active.id, preserveHome = true, groupOverride = id)
    }
  }

  fun selectProfile(profileId: String) {
    val group = state.value.deviceGroups.firstOrNull { profileId in it.memberIds }
    if (group != null) selectGroup(group.id, profileId)
    else connect(profileId, preserveHome = true)
  }

  fun saveServer(profile: ServerProfile, password: String?, cookie: String? = null, credentialUsername: String? = null, connect: Boolean = true) {
    val hadNotifications = store.profiles().firstOrNull { it.id == profile.id }?.notifications == true
    store.save(profile, password, cookie, credentialUsername)
    if (hadNotifications && !profile.notifications) {
      (store.taskStates(profile.id).keys + store.sessionNotices(profile.id).map { it.sessionId }).distinct().forEach { session ->
        notifications.cancel(profile.id, session)
      }
    }
    if (!profile.effectiveEnabled) cancelProfileNotifications(profile.id)
    val profiles = store.profiles()
    val groups = DeviceGroupState(state.value.deviceGroups, state.value.homeGroupId).sanitized(profiles.map { it.id }.toSet())
    persistConnectionGraph(profiles, groups.groups)
    updateState { it.copy(profiles = profiles, deviceGroups = groups.groups, homeGroupId = groups.selectedGroupId) }
    scheduleBackgroundSync()
    val connectNow = connect && profiles.any { it.id == profile.id && it.effectiveEnabled }
    allOverview.start(profiles, include = setOfNotNull(if (connectNow) profile.id else state.value.serverId))
    if (connectNow) connect(profile.id, preserveHome = true)
  }

  /**
   * Enables or disables one saved connection. Disabling stops its monitor/client immediately (an SSH
   * link is closed too), removes it from every home/notification projection, and switches away when
   * it was the writable server. Re-enabling starts monitoring at once so cached conversations
   * return and a live read is scheduled. Credentials, drafts, caches and the unread ledger are
   * never touched.
   */
  fun setProfileEnabled(profileId: String, enabled: Boolean) {
    val profile = store.profiles().firstOrNull { it.id == profileId } ?: return
    if (profile.enabled == enabled) return
    val saved = try { store.setProfileEnabled(profileId, enabled) } catch (cancel: CancellationException) {
      throw cancel
    } catch (error: Exception) {
      updateState { it.copy(error = userFacingError(error, "无法保存连接状态，请重试")) }
      return
    }
    if (!saved) {
      updateState { it.copy(error = "无法保存连接状态，请重试") }
      return
    }
    val profiles = store.profiles()
    persistConnectionGraph(profiles, state.value.deviceGroups)
    val wasActive = state.value.serverId == profileId
    val currentGroupId = state.value.homeGroupId
    if (!enabled) {
      if (draftServerId == profileId) clearDraftIntent()
      cancelProfileNotifications(profileId)
      if (profile.sshHostId != null) sshHostManager.closeBinding(profileId)
      allSnapshots.remove(profileId)
      val enabledIds = profiles.filter { it.effectiveEnabled }.mapTo(HashSet()) { it.id }
      val groups = DeviceGroupState(state.value.deviceGroups, currentGroupId).sanitized(profiles.map { it.id }.toSet())
      updateState {
        it.copy(
          profiles = profiles,
          deviceGroups = groups.groups,
          homeGroupId = groups.selectedGroupId,
          homeServerId = it.homeServerId?.takeIf { id -> id != profileId },
          allServerSnapshots = allSnapshots.toMap(),
          message = "已禁用 ${profile.name}",
          messageTone = FeedbackTone.INFO
        )
      }
      allOverview.start(profiles, include = setOfNotNull(state.value.serverId?.takeIf { it in enabledIds }))
      if (wasActive) {
        val fallback = if (currentGroupId != null) {
          // Another enabled member keeps the group and its writable connection alive.
          state.value.homeGroup?.memberIds
            ?.mapNotNull { member -> profiles.firstOrNull { it.id == member && it.effectiveEnabled } }
            ?.firstOrNull()
        } else if (!state.value.allServers) {
          profiles.firstOrNull { it.effectiveEnabled }
        } else null
        if (fallback != null) {
          val group = currentGroupId?.takeIf { groupId ->
            state.value.deviceGroups.firstOrNull { it.id == groupId }?.memberIds?.contains(fallback.id) == true
          }
          connect(fallback.id, preserveHome = true, groupOverride = group)
        } else {
          showAggregateAfterDisable(profiles, "已禁用 ${profile.name}")
        }
        // Remember this only after the fallback connect: connect() clears it when a different
        // profile becomes active. Re-enabling the disabled connection can then restore the view.
        lastDisabledActiveId = profileId
      }
      scheduleBackgroundSync()
      return
    }

    if (profiles.none { it.id == profileId && it.effectiveEnabled }) {
      updateState { it.copy(profiles = profiles, message = "已保留 Harness 启用选择；所属接入源或主机仍停用", messageTone = FeedbackTone.INFO) }
      allOverview.start(profiles)
      scheduleBackgroundSync()
      return
    }
    val restore = lastDisabledActiveId == profileId
    lastDisabledActiveId = null
    val groups = DeviceGroupState(state.value.deviceGroups, state.value.homeGroupId)
      .sanitized(profiles.map { it.id }.toSet())
    updateState {
      it.copy(
        profiles = profiles,
        deviceGroups = groups.groups,
        homeGroupId = groups.selectedGroupId,
        homeServerId = it.homeServerId ?: profileId,
        message = "已启用 ${profile.name}，正在连接",
        messageTone = FeedbackTone.INFO
      )
    }
    allOverview.start(profiles, include = setOfNotNull(state.value.serverId, profileId))
    allOverview.refresh(profileId)
    val current = state.value
    if (restore || current.serverId == null ||
      (!current.allServers && current.homeGroupId == null && current.homeServerId == profileId)) {
      connect(profileId, preserveHome = true)
    }
    scheduleBackgroundSync()
  }

  private fun cancelProfileNotifications(id: String) {
    notifications.cancelSummary(id)
    (store.taskStates(id).keys + store.sessionNotices(id).map { it.sessionId }).distinct().forEach { session ->
      notifications.cancel(id, session)
    }
  }

  /**
   * Leaves single-server mode after its last enabled connection was disabled. Other enabled
   * profiles keep their overview monitors; the home becomes the aggregate projection with no
   * writable server until the user picks one.
   */
  private fun showAggregateAfterDisable(profiles: List<ServerProfile>, message: String) {
    clearDraftIntent()
    stopSingleConnection()
    store.rememberAllServers(true)
    val snapshots = allSnapshots.filterKeys { id -> profiles.any { it.id == id && it.effectiveEnabled } }
    updateState { latest ->
      LagoonState(
        profiles = profiles,
        sshHosts = latest.sshHosts,
        deviceGroups = latest.deviceGroups,
        deviceGroupReset = latest.deviceGroupReset,
        homeGroupId = null,
        allServers = true,
        permissionPolicy = latest.permissionPolicy,
        pendingAttachments = latest.pendingAttachments,
        allServerSnapshots = snapshots,
        message = message,
        messageTone = FeedbackTone.INFO
      ).homeProjection()
    }
  }


  // ------------------------------------------------------------------ SSH hosts

  /** Saved SSH metadata only; passwords and private keys stay encrypted in SshHostStore. */
  fun sshHosts(): List<SshHostConfig> = state.value.sshHosts

  fun sshHost(id: String): SshHostConfig? = state.value.sshHosts.firstOrNull { it.id == id }

  fun sshHostSecrets(hostId: String): SshSecrets = sshHostStore.secrets(hostId)

  internal class NotificationClientLease(val client: HarnessClient, private val owned: Boolean) : AutoCloseable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    override fun close() {
      if (closed.compareAndSet(false, true) && owned) client.close()
    }
  }

  /**
   * 通知栏操作专用客户端租约：
   * - DSH / DSH_REMOTE / SSH 优先复用 [allOverview] 持有的在线客户端（DSH 审批瀑布绑定在活跃 `$events` 流上，
   *   且 DSH_REMOTE / SSH 共享单条加密连接）；进程冷启动尚未建立在线客户端时再按完整工厂构造并在用后关闭。
   * - OpenCode 使用 8 秒超时的独立 REST 客户端并在完成后关闭。
   */
  internal fun notificationClient(profile: ServerProfile, credentials: ServerCredentials): NotificationClientLease {
    check(store.profiles().any { it.id == profile.id && it.effectiveEnabled }) { "连接或其所属接入源/主机已停用" }
    if (profile.kind == ServerKind.DSH || profile.kind == ServerKind.DSH_REMOTE || profile.sshHostId != null) {
      val live = clientFor(profile.id)?.takeUnless { it.isClosed }
      if (live != null) return NotificationClientLease(live, owned = false)
      if (profile.sshHostId != null) return NotificationClientLease(sshHostManager.client(profile), owned = true)
      val remoteBackend = if (profile.kind == ServerKind.DSH_REMOTE) remoteBackendFactory.create(profile, credentials) else null
      return NotificationClientLease(
        createHarnessClient(profile, credentials, callTimeoutSeconds = 8, remoteBackend = remoteBackend),
        owned = true
      )
    }
    return NotificationClientLease(createHarnessClient(profile, credentials, callTimeoutSeconds = 8), owned = true)
  }

  /** A bounded client for notification actions; SSH may need to reconnect first. */
  internal fun sshClientForNotification(profile: ServerProfile): HarnessClient = sshHostManager.client(profile)

  fun sshLinkStatus(hostId: String) = sshHostManager.status(hostId)

  fun dismissDeviceGroupReset() {
    if (state.value.deviceGroupReset) updateState { it.copy(deviceGroupReset = false) }
  }

  suspend fun probeSshHostKey(config: SshHostConfig): PresentedSshHostKey =
    sshHostManager.probeHostKey(cleanSshConfig(config))

  fun pinSshHostKey(hostId: String, key: PresentedSshHostKey) {
    sshHostManager.pinHostKey(hostId, key)
    updateState { it.copy(sshHosts = sshHostStore.load()) }
  }

  /** Read-only discovery after host pin confirmation. Passwords stay in the returned runtime-only descriptor. */
  suspend fun discoverSshHarnesses(config: SshHostConfig, secrets: SshSecrets, path: String? = null): SshHarnessDiscoveryResult {
    val clean = cleanSshConfig(config)
    validateSshSecrets(clean, secrets)
    return sshHostManager.discover(clean, secrets, path)
  }

  /** Runs the real connect → host key → bootstrap → tunnel → login flow without saving. */
  suspend fun testSshHost(
    config: SshHostConfig,
    secrets: SshSecrets,
    onStatus: (SshLinkStatus) -> Unit,
    harnessKind: ServerKind = ServerKind.DSH,
    registrationPath: String? = null
  ): String {
    val clean = cleanSshConfig(config)
    validateSshSecrets(clean, secrets)
    val parent = store.profiles().firstOrNull { it.sshHostId == clean.id }
    check(parent?.parentEnabled != false) { "所属接入源或主机已停用，不能测试 Harness" }
    val link = sshHostManager.testLink(clean, secrets, onStatus, harnessKind, registrationPath)
    return try {
      val client = link.awaitHarness()
      client.projects()
      "已连接 · ${clean.address}"
    } finally {
      link.close()
    }
  }

  /** Save a host whose Harness leaves are all disabled, without starting a runtime. */
  fun saveSshSourceOnly(config: SshHostConfig, secrets: SshSecrets) {
    val clean = cleanSshConfig(config)
    validateSshSecrets(clean, secrets)
    val savedProfiles = store.readProfiles()
    val old = sshHostStore.load().firstOrNull { it.id == clean.id }
    val changed = old == null || old.connectionKey != clean.connectionKey || old.hostKeyType != clean.hostKeyType ||
      old.hostKeyFingerprint != clean.hostKeyFingerprint || sshHostStore.secrets(clean.id) != secrets
    // Disable leaves before replacing source credentials; no new runtime can dial mid-update.
    savedProfiles.filter { it.sshHostId == clean.id && it.enabled }.forEach { setProfileEnabled(it.id, false) }
    sshHostStore.save(clean, secrets)
    store.updateSshSourceRoute(clean.id, clean.profileUrl(), clean.user)
    if (changed) sshHostManager.replace(clean.id)
    persistConnectionGraph()
    updateState { it.copy(profiles = store.profiles(), sshHosts = sshHostStore.load()) }
  }

  /**
   * Saves the SSH config + secrets and one native Harness binding. The profile id is stable across edits;
   * an existing link and overview monitor are dropped so the next connection uses the new config.
   */
  fun saveSshHost(
    config: SshHostConfig,
    secrets: SshSecrets,
    autoConnect: Boolean = true,
    notifications: Boolean = true,
    groupId: String? = null,
    connect: Boolean = true,
    harnessKind: ServerKind = ServerKind.DSH,
    registrationPath: String? = null,
    bindingEnabled: Boolean? = null
  ): ServerProfile {
    require(harnessKind != ServerKind.DSH_REMOTE) { "SSH source 不使用 Remote carrier" }
    val clean = cleanSshConfig(config)
    validateSshSecrets(clean, secrets)
    require(registrationPath == null || registrationPath.startsWith('/') && registrationPath.length <= 4096 && registrationPath.none(Char::isISOControl)) {
      "OpenCode 注册路径必须是远端绝对路径"
    }
    val savedProfiles = store.readProfiles()
    val existing = savedProfiles.firstOrNull {
      it.sshHostId == clean.id && it.kind == harnessKind && it.sshServicePath == registrationPath
    }
    val fallbackId = clean.id.takeUnless { candidate -> savedProfiles.any { it.id == candidate } }
      ?: java.util.UUID.randomUUID().toString()
    val profileId = existing?.id ?: fallbackId
    val previousHost = sshHostStore.load().firstOrNull { it.id == clean.id }
    val hadHostConfig = previousHost != null
    val hostChanged = previousHost == null || previousHost.connectionKey != clean.connectionKey ||
      previousHost.hostKeyType != clean.hostKeyType || previousHost.hostKeyFingerprint != clean.hostKeyFingerprint ||
      sshHostStore.secrets(clean.id) != secrets
    sshHostStore.save(clean, secrets)
    val profile = (existing ?: ServerProfile(profileId, clean.name, clean.profileUrl(), clean.user)).copy(
      name = clean.name,
      url = clean.profileUrl(),
      username = clean.user,
      autoConnect = autoConnect,
      notifications = notifications,
      allowCleartext = true,
      kind = harnessKind,
      dshAuth = DshAuthMode.TOKEN,
      remoteHostId = null,
      remoteHostFingerprint = null,
      sshHostId = clean.id,
      sshServicePath = registrationPath,
      enabled = bindingEnabled ?: existing?.enabled ?: true
    )
    try {
      store.save(profile, password = null, cookie = null, credentialUsername = clean.user)
      store.updateSshSourceRoute(clean.id, clean.profileUrl(), clean.user)
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (error: Exception) {
      if (!hadHostConfig) runCatching { sshHostStore.delete(clean.id) }
      throw error
    }
    val profiles = store.profiles()
    val groups = DeviceGroupState(state.value.deviceGroups, state.value.homeGroupId).sanitized(profiles.map { it.id }.toSet())
    persistConnectionGraph(profiles, groups.groups)
    if (!profile.effectiveEnabled) cancelProfileNotifications(profile.id)
    updateState {
      it.copy(
        profiles = profiles,
        sshHosts = sshHostStore.load(),
        deviceGroups = groups.groups,
        homeGroupId = groups.selectedGroupId
      )
    }
    // Drop the monitor's client first. Its close() only releases a refcount, so closing the link
    // afterwards is what actually tears the old forwarder down; start() below then dials the new config.
    if (hostChanged) {
      profiles.filter { it.sshHostId == clean.id }.forEach { allOverview.invalidate(it.id) }
      sshHostManager.replace(clean.id)
    } else {
      allOverview.invalidate(profile.id)
      sshHostManager.closeBinding(profile.id)
    }
    scheduleBackgroundSync()
    allOverview.start(profiles, include = setOfNotNull(if (profiles.any { it.id == profile.id && it.effectiveEnabled }) profile.id else state.value.serverId))
    if (groupId != null) moveProfile(profile.id, groupId)
    if (connect && profiles.any { it.id == profile.id && it.effectiveEnabled }) connect(profile.id, preserveHome = true)
    return profile
  }

  fun deleteSshHost(hostId: String) {
    val profiles = store.profiles().filter { it.sshHostId == hostId }
    if (profiles.isNotEmpty()) {
      profiles.forEach { deleteServer(it.id) }
      return
    }
    sshHostManager.close(hostId)
    runCatching { sshHostStore.delete(hostId) }
    updateState { it.copy(sshHosts = sshHostStore.load()) }
  }

  fun retrySshHost(hostId: String) = sshHostManager.retry(hostId)

  private fun cleanSshConfig(config: SshHostConfig): SshHostConfig {
    val host = config.host.trim()
    val user = config.user.trim()
    require(host.isNotBlank()) { "填写主机地址（IP 或域名）" }
    require(host.length <= 512) { "主机地址过长" }
    require(user.isNotBlank()) { "填写登录用户名" }
    require(user.length <= 128) { "登录用户名过长" }
    require(config.port in 1..65535) { "端口需在 1–65535" }
    val name = config.name.trim().ifBlank { host }.take(128)
    val command = config.launchCommand.trim().ifBlank { DEFAULT_DSH_LAUNCH_COMMAND }
    require(command.length <= 512 && '\n' !in command && '\r' !in command) { "启动命令不能包含换行" }
    return config.copy(
      name = name,
      host = host,
      user = user,
      launchCommand = command,
      workingDirectory = config.workingDirectory?.trim()?.takeIf { it.isNotEmpty() }?.take(1024)
    )
  }

  private fun validateSshSecrets(config: SshHostConfig, secrets: SshSecrets) {
    when (config.auth) {
      SshAuthMethod.PASSWORD -> require(!secrets.password.isNullOrEmpty()) { "填写 SSH 密码，或改用私钥" }
      SshAuthMethod.PRIVATE_KEY -> require(!secrets.privateKey.isNullOrBlank()) { "粘贴 SSH 私钥" }
    }
  }

  fun deleteServer(id: String) {
    if (draftServerId == id) clearDraftIntent()
    val removed = store.profiles().firstOrNull { it.id == id }
    cancelProfileNotifications(id)
    if (mutable.value.serverId == id) {
      stopSingleConnection()
    }
    allSnapshots.remove(id)
    transcriptMemory.forgetServer(id)
    sessionViews.forgetServer(id)
    conversationPool.forgetServer(id)
    choicesCache.invalidateServer(id)
    store.delete(id)
    removed?.sshHostId?.let { hostId ->
      sshHostManager.closeBinding(id)
      // A Harness leaf is not the SSH source. Its siblings retain configuration and transport.
      if (store.profiles().none { it.sshHostId == hostId }) {
        sshHostManager.close(hostId)
        runCatching { sshHostStore.delete(hostId) }
          .onFailure { Diagnostics.warn("LagoonController", "SSH 主机删除失败", it) }
      }
    }
    cache.delete(id)
    if (transcripts != null) scope.launch(storeDispatcher) { attempt { transcripts.deleteServer(id) } }
    scheduleBackgroundSync()

    // A deleted profile cannot stay in a device tab. Empty groups disappear; the selected group
    // falls back to all when it no longer has members.
    val nextGroups = DeviceGroupState(state.value.deviceGroups, state.value.homeGroupId).withoutProfile(id)
    val nextSelected = nextGroups.selectedGroupId
    saveDeviceGroups(nextGroups.groups, nextSelected)
    allOverview.start(store.profiles(), include = setOfNotNull(state.value.serverId))
    updateState { it.copy(
      profiles = store.profiles(),
      sshHosts = sshHostStore.load(),
      allServerSnapshots = allSnapshots.toMap(),
      homeServerId = it.homeServerId.takeUnless { selected -> selected == id },
      allServers = nextSelected == null,
      homeGroupId = nextSelected
    ) }
    if (mutable.value.serverId == id) {
      val group = state.value.homeGroup
      if (group != null) selectGroup(group.id)
      if (mutable.value.serverId == id) {
        val next = store.profiles().firstOrNull { it.effectiveEnabled }?.id
        if (next != null) connect(next, preserveHome = true)
        else showAggregateAfterDisable(store.profiles(), "已删除连接")
      }
    }
  }
  private fun stopSingleConnection() {
    api?.questionPresentation(null, false)
    stream?.cancel()
    refresh?.cancel()
    eventRefresh?.cancel()
    messageRefresh?.cancel()
    reconcile?.cancel()
    connecting?.cancel()
    workspaceEvents?.cancel()
    workspaceEvents = null
    transportJob?.cancel()
    transportJob = null
    transcriptRetry?.cancel()
    transcriptRetry = null
    sessionLoadJobs.clear()
    generation += 1
    api = null
  }

  /**
   * Mirrors DSH's live workspace list into the draft picker. Previously the list only refreshed on
   * the next 30–60s catalog read, so a fresh connection showed "还没有工作区" and the new-session page
   * appeared stuck.
   */
  private fun observeWorkspaceEvents(serverId: String) {
    workspaceEvents?.cancel()
    val flow = api?.workspaceUpdates ?: return
    workspaceEvents = scope.launch {
      try {
        flow.collect { projects ->
          if (state.value.serverId != serverId) return@collect
          val mapped = projects.map { it.copy(serverId = serverId) }
          if (mapped == state.value.workspaceCatalog) return@collect
          updateState { it.copy(workspaceCatalog = mapped) }
          adoptDefaultDraftWorkspace(serverId, mapped)
        }
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (error: Exception) {
        Diagnostics.warn("LagoonController", "工作区事件流失败", error)
      }
    }
  }
  private fun adoptDefaultDraftWorkspace(serverId: String, projects: List<Project>) {
    if (draftServerId != serverId) return
    val current = state.value
    if (current.projectId != null) return
    val first = projects.firstOrNull()?.id ?: return
    draftProjectId = first
    updateState { if (it.serverId == serverId && it.projectId == null) it.copy(projectId = first) else it }
  }
  private fun observeTransport(serverId: String) {
    transportJob?.cancel()
    transportJob = scope.launch {
      while (isActive) {
        val detail = api?.transportStatus()
        if (state.value.serverId == serverId && state.value.transportDetail != detail) {
          updateState { it.copy(transportDetail = detail) }
        }
        delay(1_000)
      }
    }
  }
  fun connect(id: String, projectOverride: String? = null, sessionOverride: String? = null, preserveHome: Boolean = false,
    groupOverride: String? = null) {
    val profiles = store.profiles()
    val profile = profiles.firstOrNull { it.id == id && it.effectiveEnabled }
    if (profile == null) {
      updateState {
        it.copy(
          message = if (profiles.any { p -> p.id == id }) "这个连接已禁用，请先在设备管理中启用" else "这个连接已移除",
          messageTone = FeedbackTone.WARNING
        )
      }
      return
    }
    if (id != lastDisabledActiveId) lastDisabledActiveId = null
    rememberCurrentSessionView()
    val previous = state.value
    val previousServer = previous.serverId
    // Hand the old server's control state back to its persistent observer, not to a disconnected cache.
    allSnapshots.putAll(previous.serverSnapshots())
    previousServer?.let { previous.serverSnapshots()[it]?.let(allOverview::handoff) }
    if (!preserveHome) { store.rememberAllServers(false); store.rememberHomeServer(id) }
    // Only keep a device group when the newly active profile is still one of its enabled members.
    val group = (groupOverride ?: if (preserveHome) previous.homeGroupId else null)?.let { groupId ->
      previous.deviceGroups.firstOrNull { it.id == groupId && id in it.memberIds }
    }
    // A disabled previous home must not survive as the single-server projection.
    val homeServerId = previous.homeServerId?.takeIf { homeId ->
      preserveHome && (homeId == id || profiles.any { it.id == homeId && it.effectiveEnabled })
    } ?: id
    // A reconnect to the same server keeps the open conversation's pending prompt; anything else goes back to its draft.
    returnQueuedToDrafts { it.serverId == id && it.sessionId == (sessionOverride ?: previous.sessionId) && id == previousServer }
    stopSingleConnection()
    val token = generation
    connectTrace.begin(token.toLong(), "连接 ${profile.kind.label}")
    allOverview.start(profiles, include = setOfNotNull(id))
    if (transcripts != null && profile.kind == ServerKind.OPENCODE) scope.launch(storeDispatcher) {
      attempt { transcripts.bindServer(id, transcriptIdentity(profile)) }.onFailure { Diagnostics.warn("TranscriptStore", "绑定服务器失败", it) }
    }
    val snapshot = allSnapshots[id]
    // A blank draft owns the page while it is open: never restore the server's last conversation,
    // even when loadAll reads the catalog again after this connect.
    val draftIntent = draftServerId == id
    val rememberedProject = projectOverride ?: draftProjectId.takeIf { draftIntent } ?: store.selectedProject(id)
    // 恢复上次打开的会话时，不把服务端空白草稿恢复成聊天页；回到本地「新会话」草稿。
    val rememberedSession = if (draftIntent) null else (sessionOverride ?: store.selectedSession(id))?.takeIf { selected ->
      snapshot?.sessions?.firstOrNull { it.id == selected }?.blank != true
    }
    seenEvents.clear(); visibleConversation = null
    closeShellOutput(); familyDiscovery = null
    resolvedPermissions.clear()
    val configuration = rememberedSession?.let { store.configuration(id, it) } ?: SessionConfiguration()
    val defaults = store.newSessionDefaults(id)
    // A reconnect to the same blank page keeps the reader's manual preset/model choice; switching
    // to another server starts from that server's defaults because catalogs differ.
    val keepDraftSelection = draftIntent && previous.serverId == id && previous.sessionId == null
    val draftAgent = if (keepDraftSelection) previous.agent else defaults.agent
    val draftModel = if (keepDraftSelection) previous.model else defaults.model?.let { model ->
      model.copy(variant = store.modelVariants(id)[modelKey(model)])
    }
    val liveBlankDraft = when {
      !draftIntent -> ""
      keepDraftSelection -> liveDraft(previous)
      draftKeepsComposer -> preservedDraftText.ifEmpty { liveDraft(previous) }
      else -> ""
    }
    store.select(id, rememberedProject, rememberedSession)
    // Drop the pre-ledger “永久已完成” states and read markers; keep runs this device saw as still running.
    store.migrateTaskLedger(id)
    updateState { LagoonState(profiles = profiles, sshHosts = previous.sshHosts,
      deviceGroups = previous.deviceGroups, deviceGroupReset = previous.deviceGroupReset,
      homeGroupId = group?.id,
      serverId = id, loading = true, projectId = rememberedProject, sessionId = rememberedSession,
      allServers = if (group != null) false else preserveHome && previous.allServers,
      homeServerId = homeServerId,
      allServerSnapshots = allSnapshots.toMap(), permissionPolicy = store.permissionPolicy(),
      workspaceCatalog = allSnapshots[id]?.workspaceCatalog.orEmpty(),
      sessions = snapshot?.sessions.orEmpty(), projects = snapshot?.projects ?: store.localProjects(id).map { it.copy(serverId = id) },
      permissions = snapshot?.permissions.orEmpty(), questions = snapshot?.questions.orEmpty(),
      autoApprovalFailures = snapshot?.autoApprovalFailures.orEmpty(), streamConnected = snapshot?.streamConnected == true,
      streamDownSince = 0L,
      connection = ConnectionStatus(ConnectStage.CONNECTING, System.currentTimeMillis()), remoteRecovery = null,
      agent = if (draftIntent) draftAgent else configuration.agent,
      model = if (draftIntent) draftModel else configuration.model,
      agentChanged = if (draftIntent) {
        if (keepDraftSelection) previous.agentChanged else defaults.agent != null
      } else configuration.agentChanged,
      modelChanged = if (draftIntent) {
        if (keepDraftSelection) previous.modelChanged else defaults.model != null
      } else configuration.modelChanged,
      newSessionAgent = defaults.agent, newSessionModel = defaults.model,
      draft = when {
        draftIntent -> liveBlankDraft
        else -> ""
      },
      draftRevision = if (draftIntent) previous.draftRevision + (if (liveBlankDraft != previous.draft) 1 else 0) else 0,
      references = emptyList(),
      tasks = snapshot?.tasks ?: TaskReconciliation.restored(store.taskStates(id)), knownParents = store.taskParents(id), notices = store.sessionNotices(id), collapsedSections = store.collapsedSections(id), pinned = store.pinnedSessions(id),
      pendingAttachments = previous.pendingAttachments,
      modelOverrides = store.modelOverrides(id), modelVisibilityConfigured = store.modelVisibilityConfigured(id),
      modelVariants = store.modelVariants(id), recentModels = store.recentModels(id), scopeProjectId = store.scope(id)) }
    if (state.value.sessions.isNotEmpty()) trace(token, ConnectMilestone.CACHE_PAINT)
    else paintCachedCatalog(id, token)
    rememberedSession?.let {
      openTrace.begin(selectionRevision, "恢复会话")
      showCachedTranscript(id, it, selectionRevision)
      restoreComposerInput(id, it, selectionRevision)
    }
    allOverview.selectionChanged(previousServer)
    // A broken DSH Remote / SSH profile can fail while the client is being constructed (missing
    // host, blank account). That used to escape connect() on the main thread and close the app;
    // keep the page on its offline state and let the normal retry loop pick up a fixed profile.
    api = try {
      allOverview.client(id)
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      val reason = userFacingError(error, "连接失败")
      connectTrace.fail(token.toLong(), reason)
      updateState { it.copy(loading = false, connected = false, error = reason) }
      scheduleReconnect(id, token, if (error.isAuthFailure) AUTH_RETRY_ATTEMPT else 0, reason)
      return
    }
    api?.setContentWatching(true)
    synchronizeQuestionPresentation()
    observeWorkspaceEvents(id)
    observeTransport(id)
    connecting = scope.launch {
      try {
        loadAll(token)
        if (token == generation && streamAllowed()) {
          startStream(token)
        }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        if (token != generation) return@launch
        val reason = userFacingError(error, "连接失败")
        connectTrace.fail(token.toLong(), reason)
        showOffline(id, token, reason)
        if (token == generation) updateState { it.copy(remoteRecovery = remoteFailure(error)?.kind?.recovery()) }
        // A refresh while offline must be able to recover: retry the connection with backoff instead
        // of leaving the user on a stale snapshot with no path back to a live stream (A10).
        // Expired credentials do not heal by retrying quickly; keep probing at the slowest pace.
        scheduleReconnect(id, token, if (error.isAuthFailure) AUTH_RETRY_ATTEMPT else 0, reason)
      }
    }
  }
  /** Opens immediately; loading the transcript does not change the originating home filter. */
  fun openConversation(target: SessionTarget) {
    clearDraftIntent()
    if (store.profiles().none { it.id == target.serverId && it.effectiveEnabled }) {
      updateState {
        it.copy(
          message = if (store.profiles().any { profile -> profile.id == target.serverId }) "这个连接已禁用，请先在设备管理中启用" else "这个连接已移除",
          messageTone = FeedbackTone.WARNING
        )
      }
      return
    }
    if (target.serverId != state.value.serverId) {
      connect(target.serverId, sessionOverride = target.sessionId, preserveHome = true)
    } else if (state.value.sessions.any { it.id == target.sessionId }) selectSession(target.sessionId)
    else {
      // 先清掉上一个会话的输入：随后的 selectSession 会把它当作"重新选中"而保留，并异步填回本会话的草稿。
      updateState { it.copy(sessionId = target.sessionId, messages = emptyList(), draft = "", references = emptyList(), resources = mapOf("messages" to ResourceStatus(ResourceState.LOADING))) }
      resolveSession(target.sessionId) { if (state.value.serverId == target.serverId && state.value.sessionId == target.sessionId) selectSession(target.sessionId) }
    }
  }
  private fun scheduleReconnect(id: String, token: Int, attempt: Int = 0, reason: String? = null) {
    if (token != generation || state.value.serverId != id) return
    if (store.profiles().none { it.id == id && it.effectiveEnabled }) return
    val delayMillis = (2_000L shl attempt.coerceAtMost(4)).coerceAtMost(60_000L)
    val now = System.currentTimeMillis()
    updateState { it.copy(connection = ConnectionStatus(ConnectStage.RETRYING, now, now + delayMillis, reason ?: retryReason(it.error))) }
    scope.launch {
      delay(delayMillis)
      if (token == generation && state.value.serverId == id && !state.value.connected) reconnect(id, token, attempt)
    }
  }
  private suspend fun reconnect(id: String, token: Int, attempt: Int) {
    val profile = store.profiles().firstOrNull { it.id == id && it.effectiveEnabled } ?: return
    connectTrace.begin(token.toLong(), "重连 ${profile.kind.label} 第 ${attempt + 1} 次")
    setStage(token, ConnectStage.CONNECTING)
    // Rebuilds the client from the current profile; a still-broken DSH Remote / SSH setup must
    // surface as an offline banner + retry, not as an uncaught coroutine exception.
    api = try {
      allOverview.client(id)
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      val reason = userFacingError(error, "连接失败")
      if (token == generation) {
        connectTrace.fail(token.toLong(), reason)
        showOffline(id, token, reason)
        scheduleReconnect(id, token, if (error.isAuthFailure) AUTH_RETRY_ATTEMPT else attempt + 1, reason)
      }
      return
    }
    api?.setContentWatching(true)
    synchronizeQuestionPresentation()
    observeWorkspaceEvents(id)
    observeTransport(id)
    try {
      loadAll(token)
      if (token != generation) return
      if (streamAllowed()) startStream(token)
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      val reason = userFacingError(error, "连接失败")
      if (token == generation) connectTrace.fail(token.toLong(), reason)
      if (token == generation) showOffline(id, token, reason)
      scheduleReconnect(id, token, if (error.isAuthFailure) AUTH_RETRY_ATTEMPT else attempt + 1, reason)
    }
  }
  private suspend fun showOffline(id: String, token: Int, reason: String) {
    // 完整性标志需要解密并解析缓存：与读取一起放到 IO，主线程只接收结果。
    val loaded = kotlinx.coroutines.withContext(Dispatchers.IO) {
      val catalog = cache.catalog(id) ?: return@withContext null
      Triple(catalog.first, catalog.second, cache.catalogComplete(id))
    }
    if (token != generation || state.value.serverId != id) return
    // Expected offline/retry states belong in the banner and the connection strip, not in a toast.
    // The raw reason stays in diagnostics; repeating it on every backoff attempt used to raise a
    // new toast each time the app tried to reconnect.
    Diagnostics.warn("LagoonController", "显示离线缓存：$reason")
    if (loaded == null) updateState { it.copy(loading = false, connected = false, error = null) }
    else {
      val (cachedProjects, cachedSessions, catalogComplete) = loaded
      val projects = (cachedProjects + store.localProjects(id))
        .distinctBy { normalizedDirectory(it.directory) }
        .map { it.copy(serverId = id) }
      val sessions = cachedSessions.map { scoped(id, it) }
      val sessionId = if (draftServerId == id) null
        else store.selectedSession(id)?.takeIf { selected -> sessions.any { it.id == selected && !it.blank } }
      trace(token, ConnectMilestone.CACHE_PAINT)
      updateState { it.copy(loading = false, connected = false, cached = true, error = null,
        projects = projects, sessions = sessions, projectId = store.selectedProject(id) ?: projects.firstOrNull()?.id,
        sessionId = sessionId, messages = withPending(id, sessionId, emptyList()), catalogComplete = catalogComplete) }
      if (sessionId != null) {
        val (messages, olderCursor) = cachedTranscriptWindow(id, sessionId)
        val messagesComplete = cachedTranscriptComplete(id, sessionId)
        if (token == generation && state.value.serverId == id && state.value.sessionId == sessionId) updateState { withSummary(it.copy(messages = withPending(id, sessionId, messages), cacheComplete = messagesComplete,
          messagesCursor = olderCursor, resources = mapOf("messages" to ResourceStatus(ResourceState.STALE, "离线缓存")))) }
      }
    }
  }
  private suspend fun loadAll(token: Int, controlOnly: Boolean = false, followUp: Boolean = true): Unit =
    loadAllInternal(token, controlOnly, followUp, deep = !controlOnly)

  /**
   * [deep] reads the expensive control plane (descendant sweep, shell jobs, auto-approval directory
   * sweep). The periodic safety reconcile and background sync use `deep = false`; only connect /
   * explicit refresh need the full sweep.
   */
  private suspend fun loadAllInternal(token: Int, controlOnly: Boolean, followUp: Boolean, deep: Boolean) {
    val client = api ?: return
    LoadMetrics.record("catalog")
    val requestSequence = ++catalogSequence
    // 读取开始时的等待态事件序号：读取期间到达的 permission/form 事件不能被这次（更早的）读取结果清除。
    val readWaitingSeq = waitingEventSeq
    val readWorkRevision = workRevision
    // 本设备认为仍在运行的会话；读取后用于判断哪些需要重新拉取会话信息（取得权威 outcome/time.idle）。
    val previousActiveIds = state.value.tasks.filterValues { it.active }.keys.toSet()
    val serverId = state.value.serverId ?: return
    // Probe, contract, catalog, activity and display titles are independent reads: one round trip
    // instead of five. The probe is awaited first so a wrong address or old server reports its own error.
    val firstRound = withTimeoutOrNull(FIRST_ROUND_TIMEOUT_MILLIS) {
      coroutineScope {
      val versionTask = async { client.health() }
      val capabilitiesTask = async { client.discoverCapabilities() }
      val pageTask = async { attempt { client.rootSessionsPage() } }
      val activeTask = async { attempt { client.activeSessions() } }
      // Display-only titles; a failure or timeout keeps the last known catalog, it never fails the load.
      val workspaceTask = async {
        attempt { withTimeoutOrNull(WORKSPACE_CATALOG_TIMEOUT_MILLIS) { client.projectsForDisplay() } }
      }
      val version = versionTask.await()
      if (token == generation) advanceStage(token, ConnectStage.READING)
      FirstRound(version, capabilitiesTask.await(), pageTask.await().getOrThrow(), activeTask.await(), workspaceTask.await())
      }
    } ?: throw ApiException(0, "连接超时，正在后台重试", "RPC_TIMEOUT")
    val version = firstRound.version
    val capabilities = firstRound.capabilities
    if (token != generation || state.value.serverId != serverId) return
    // The project list is device-local: the user registers a server directory, and the server's own
    // list is never auto-imported. A control-only refresh keeps the current list to avoid flicker.
    val projects = if (controlOnly && state.value.projects.isNotEmpty()) state.value.projects else store.localProjects(serverId).map { it.copy(serverId = serverId) }
    // Row label titles stay separate from [projects]: display only, never a filter or create target.
    // A failed or timed-out read (null) keeps the previous titles; a successful empty list clears them.
    val workspaceCatalog = firstRound.workspaces.getOrNull()
      ?.map { it.copy(serverId = serverId) }
      ?: state.value.workspaceCatalog
    // Official home index: one global, newest-first listing of root sessions (directory filters are
    // exact, so per-project queries would miss worktrees). Reconciliation re-reads its first page too,
    // so a session created elsewhere while an event was missed still appears.
    val page = firstRound.page
    trace(token, ConnectMilestone.CATALOG)
    advanceStage(token, ConnectStage.SYNCING)
    val sessions = page.items.map { scoped(serverId, it) }
    // Activity is authoritative from `/api/session/active`; a failed read keeps last-known tasks.
    val activeResult = firstRound.active
    val active = activeResult.getOrNull()
    if (token != generation || requestSequence != catalogSequence) return
    // First tier done: the catalog is authoritative and the conversation can be read and written now,
    // while the slower control-plane reads below (ancestry, children, shells, permissions, forms) run.
    var earlyFollowUp: Job? = null
    var earlySession: Session? = null
    if (!controlOnly) {
      updateState { current -> withSummary(current.copy(version = version, capabilities = capabilities, connected = true, loading = false,
        error = null, lastContactAt = System.currentTimeMillis(), workspaceCatalog = workspaceCatalog,
        sessions = (sessions + current.sessions).distinctBy { it.id }.sortedWith(sessionActivityOrder))) }
      if (workspaceCatalog.isNotEmpty()) adoptDefaultDraftWorkspace(serverId, workspaceCatalog)
      trace(token, ConnectMilestone.INTERACTIVE)
      flushQueuedSends()
      flushPendingDraft()
      if (followUp) {
        val current = state.value
        earlySession = current.session
        earlyFollowUp = CoroutineScope(currentCoroutineContext()).launch {
          launch { loadChoices(current.homeDirectory.orEmpty(), token) }
          current.session?.let { loadSession(it, token = token, ancillary = false) }
        }
      }
    }
    // Active sessions (often subagent children) may be older than the first page. Keep their identity and parent chain.
    val transcriptChildren = BackgroundWork.messageSubagents(state.value.sessionId, state.value.messages).keys
    val missingIds = (active.orEmpty() + transcriptChildren + state.value.permissions.map { it.sessionId } +
      state.value.questions.map { it.sessionId } + listOfNotNull(state.value.sessionId, store.selectedSession(serverId)))
      .filter { id -> sessions.none { it.id == id } && state.value.sessions.none { it.id == id } }.distinct()
    // 本设备认为仍在运行、但既不在前台活跃集合、也不在本页目录里的会话：重新读取其会话信息，取得
    // 服务端持久化的 outcome/time.idle，用来给漏掉终止事件的一轮做权威收尾（含转入后台的子代理）。
    // 在 controlOnly 或目录未携带 outcome 时，正在收尾的根会话也重新拉取最新 outcome。
    val staleActiveIds = previousActiveIds.filter { id ->
      active.orEmpty().none { it == id } && (sessions.none { it.id == id } || sessions.firstOrNull { it.id == id }?.outcome == null)
    }.distinct()
    val extraSessions = mutableListOf<Session>()
    val deletedSessions = mutableSetOf<String>()
    (missingIds + staleActiveIds).distinct().take(100).chunked(4).forEach { batch ->
      val reads = coroutineScope { batch.map { id -> async { id to attempt { client.session(id).takeIf { it.id == id } } } }.awaitAll() }
      reads.forEach { (id, result) ->
        result.getOrNull()?.let { extraSessions += scoped(serverId, it) }
        if (active != null && (result.exceptionOrNull() as? ApiException)?.status == 404 && id !in active) deletedSessions += id
      }
      if (token != generation || requestSequence != catalogSequence) return
    }
    // An active child may refer to a root outside the catalog page. Fetch the ancestry so both
    // pending requests and running indicators roll up to that root instead of leaking child rows.
    val identities = (state.value.sessions + sessions + extraSessions).associateBy { it.id }.toMutableMap()
    val attempted = mutableSetOf<String>()
    suspend fun fetchMissingParents(): Boolean {
      var rounds = 0
      while (rounds++ < MAX_PARENT_ROUNDS) {
        val parents = identities.values.mapNotNull { it.parentId }.filter { it !in identities && attempted.add(it) }.distinct().take(MAX_PARENT_FETCH)
        if (parents.isEmpty()) return true
        for (batch in parents.chunked(4)) {
          val fetched = coroutineScope { batch.map { id -> async { attempt { findSession(client, id) }.getOrNull() } }.awaitAll().filterNotNull().map { scoped(serverId, it) } }
          fetched.forEach { identities[it.id] = it }; extraSessions += fetched
          if (token != generation || requestSequence != catalogSequence) return false
        }
      }
      return true
    }
    if (!fetchMissingParents()) return

    // Pending requests only need the running and selected sessions' identities, which are all known
    // now, so they are read while the child and shell discovery below runs instead of after it.
    // Pending requests, read like the official client: permissions per session, forms per location.
    // A directory whose recent read failed (typically a deleted worktree answering 500) is skipped
    // until its retry window opens, so one dead directory cannot degrade the whole list forever.
    val waitingCandidates = (active.orEmpty() + state.value.permissions.map { it.sessionId } + state.value.questions.map { it.sessionId } + listOfNotNull(state.value.sessionId))
      .distinct().mapNotNull { identities[it] }.take(MAX_REQUEST_SESSIONS)
    val skippedPermissionSessions = waitingCandidates.filter { directoryHealth.isDown(it.directory) }.map { it.id }.toSet()
    val readableCandidates = waitingCandidates.filterNot { it.id in skippedPermissionSessions }
    val recoveryDirectories = if (client.recoverableQuestions) identities.values.map { it.directory } else emptyList()
    val formDirectories = (readableCandidates.map { it.directory } + recoveryDirectories).filter(String::isNotBlank).distinctBy(::normalizedDirectory)
      .filterNot { directoryHealth.isDown(it) }.take(MAX_FORM_DIRECTORIES)
    val pendingReads = CoroutineScope(currentCoroutineContext()).async {
      coroutineScope {
        val permissionTasks = readableCandidates.map { session -> async { session.id to attempt { client.sessionPermissions(session) } } }
        val formTasks = formDirectories.map { directory -> async { directory to attempt { client.forms(directory) } } }
        permissionTasks.awaitAll() to formTasks.awaitAll()
      }
    }

    // `/api/session/active` and the event stream can both omit a child identity after a reconnect.
    // A persisted live root is enough to discover its descendants through the authoritative child
    // listing; those identities then supply the parent map used by the task reducer below.
    val knownParents = state.value.parents + store.taskParents(serverId) + identities.values.mapNotNull { session ->
      session.parentId?.let { session.id to it }
    }
    val candidateIds = previousActiveIds + active.orEmpty() + state.value.backgroundRunning + transcriptChildren
    val candidateRoots = candidateIds.map { BackgroundWork.rootOf(it, knownParents) }.toSet()
    // A root present in the authoritative foreground set is already running itself; querying its
    // children would only add catalog traffic and cannot change the background classification.
    if (deep) {
      val childRead = TaskReconciliation.children(client,
        identities.values.filter { it.id in candidateRoots && it.parentId == null && active?.contains(it.id) != true }, knownParents,
        childDiscovery ?: ChildDiscovery().also { childDiscovery = it })
      if (token != generation || requestSequence != catalogSequence) return
      childRead.sessions.forEach { identities[it.id] = it }
      extraSessions += childRead.sessions
      if (!fetchMissingParents()) return
    }
    // 运行中的 shell 作业：后台 shell 不是会话，不在 /api/session/active 里，冷启动/断线后只能从
    // `GET /api/shell` 读到。只读有活动或有已知 shell 的目录，不再把所有项目目录都扫一遍。
    val refreshedShellDirectories = mutableSetOf<String>()
    val failedShellDirectories = mutableSetOf<String>()
    val freshShells = linkedMapOf<String, ShellJob>()
    if (deep) {
      val shellSessions = identities.values.filter { session ->
        session.id in active.orEmpty() || state.value.tasks[session.id]?.active == true ||
          state.value.shells.values.any { it.sessionId == session.id }
      }
      val shellDirectories = TaskReconciliation.shellDirectories(state.value.tasks, state.value.shells,
        shellSessions, emptyList(), active.orEmpty() + transcriptChildren, MAX_SHELL_DIRECTORIES)
        .filterNot { directoryHealth.isDown(it) }
      val shellResults = coroutineScope { shellDirectories.map { directory -> async { directory to attempt { client.shells(directory) } } }.awaitAll() }
      if (token != generation || requestSequence != catalogSequence) return
      if (readWorkRevision != workRevision) return
      shellResults.forEach { (directory, result) ->
        val key = normalizedDirectory(directory)
        if (result.isSuccess) {
          directoryHealth.succeeded(directory)
          refreshedShellDirectories += key
          result.getOrDefault(emptyList()).forEach { freshShells[it.id] = it }
        } else {
          directoryHealth.failed(directory)
          failedShellDirectories += key
          Diagnostics.warn("LagoonController", "shell 列表读取失败：$directory", result.exceptionOrNull())
        }
      }
      // shell 归属的会话不在目录页时补读身份与父链，保证后台状态能汇总到正确的根会话。
      val unknownShellSessions = freshShells.values.map { it.sessionId }.filter { it !in identities }.distinct().take(MAX_SHELL_SESSIONS)
      if (unknownShellSessions.isNotEmpty()) {
        unknownShellSessions.chunked(4).forEach { batch ->
          val fetched = coroutineScope { batch.map { id -> async { attempt { findSession(client, id) }.getOrNull() } }.awaitAll().filterNotNull() }
          fetched.forEach { identities[it.id] = it }; extraSessions += fetched
          if (token != generation || requestSequence != catalogSequence) return
        }
      }
      // A shell owner may itself be a grandchild outside the catalog. Resolve its complete ancestry
      // after the shell-specific identity fetch as well as before it.
      if (!fetchMissingParents()) return
    }
    // Pending requests, read like the official client: permissions per session, forms per location.
    val inboxRead = PendingInputs.read(client, identities.values.toList(), state.value.tasks, active)
    // Only a running session can be waiting on the user.
    // Pending requests, read like the official client: permissions per session, forms per location.
    // A directory whose recent read failed (typically a deleted worktree answering 500) is skipped
    // until its retry window opens, so one dead directory cannot degrade the whole list forever.
    val (permissionResults, formResults) = pendingReads.await()
    permissionResults.forEach { (sessionId, result) ->
      val directory = identities[sessionId]?.directory ?: return@forEach
      if (result.isSuccess) directoryHealth.succeeded(directory) else directoryHealth.failed(directory)
    }
    // Like the official desktop auto-approver, sweep known locations too: external clients can
    // park requests on idle sessions that are absent from session.active and not currently open.
    val sweepDirectories = if (store.permissionPolicy().autoApprove) (identities.values.map { it.directory } + projects.map { it.directory })
      .filter(String::isNotBlank).distinctBy(::normalizedDirectory).filterNot { directoryHealth.isDown(it) }.take(MAX_SWEEP_DIRECTORIES) else emptyList()
    val sweptPermissions = sweepDirectories.chunked(4).flatMap { batch -> coroutineScope {
      batch.map { directory -> async { directory to attempt { client.pendingPermissions(directory) } } }.awaitAll()
    } }
    sweptPermissions.forEach { (directory, result) -> if (result.isSuccess) directoryHealth.succeeded(directory) else directoryHealth.failed(directory) }
    formResults.forEach { (directory, result) -> if (result.isSuccess) directoryHealth.succeeded(directory) else directoryHealth.failed(directory) }
    if (token != generation || requestSequence != catalogSequence) return
    if (readWorkRevision != workRevision) return
    val failedPermissionSessions = permissionResults.filter { it.second.isFailure }.map { it.first }.toSet() + skippedPermissionSessions
    val failedFormDirs = formResults.filter { it.second.isFailure }.map { normalizedDirectory(it.first) }.toSet()
    val verifiedFormDirs = formResults.filter { it.second.isSuccess }.map { normalizedDirectory(it.first) }.toSet()
    val permissions = (permissionResults.mapNotNull { it.second.getOrNull() }.flatten() +
      sweptPermissions.mapNotNull { it.second.getOrNull() }.flatten()).distinctBy { it.id }
    val questions = formResults.mapNotNull { it.second.getOrNull() }.flatten().distinctBy { it.id }
    store.rememberParents(serverId, sessions + extraSessions)
    // Only the read that decides whether a row is running can say the page is out of sync. Shell,
    // permission and form failures keep their own last-known entries; they must not pin
    // "部分数据待同步" on every server with more directories than one sweep can cover.
    val degraded = activeResult.isFailure
    if (degraded) Diagnostics.warn("LagoonController", "部分状态读取失败，保留上次可信状态", activeResult.exceptionOrNull())

    deletedSessions.forEach { store.forgetTask(serverId, it) }
    val storedTasks = store.taskStates(serverId)
    // Pre-decrypt missing session previews off the main thread so a cold start with 100+ sessions
    // never blocks the UI thread on AndroidKeyStore Binder IPC inside updateState.
    val candidateSessionIds = (extraSessions.map { it.id } + sessions.map { it.id } + state.value.sessions.map { it.id })
      .filterNot { it in deletedSessions }
      .distinct()
    val knownPreviews = state.value.previews
    val missingPreviewIds = candidateSessionIds.filterNot(knownPreviews::containsKey)
    val loadedPreviews = if (missingPreviewIds.isEmpty()) emptyMap() else kotlinx.coroutines.withContext(Dispatchers.IO) {
      missingPreviewIds.associateWith { id -> store.sessionPreview(serverId, id) }
    }
    val draftSessionIds = kotlinx.coroutines.withContext(Dispatchers.IO) { store.draftSessionIds(serverId) }
    if (token != generation || requestSequence != catalogSequence) return
    var finishedRuns = emptyList<Pair<Session, Long>>()
    // 本次读取前已知的待处理项，用于识别事件流可能漏掉的新权限/表单。
    val priorRequestIds = (state.value.permissions.map { it.id } + state.value.questions.map { it.id }).toSet()
    // 对账期间被权威读取证明已处理的等待项，读取结束后撤掉已发通知（见下方 cancelAttention）。
    var resolvedWaitingIds = emptyList<String>()
    updateState { previous ->
      val now = System.currentTimeMillis()
      val nextShells = reconcileShellJobs(previous.shells, freshShells, refreshedShellDirectories, deletedSessions, now, failedShellDirectories)
      val previousTasks = (previous.tasks + storedTasks.filter { (id, task) -> previous.tasks[id]?.let { old -> task.since > old.since || (task.finishedAt ?: 0) > (old.finishedAt ?: 0) } ?: true }) - deletedSessions
      val nextSessions = (extraSessions + sessions + previous.sessions.filter {
        // A session this device saw running, or one with an unread result, never silently drops out;
        // neither do rows loaded through “加载更多” while the catalog has more pages.
        it.id in active.orEmpty() || it.id == previous.sessionId || previousTasks[it.id]?.active == true ||
          previous.notices.unseenFor(it.id).isNotEmpty() || page.next != null
        }).filterNot { it.id in deletedSessions }.map { scoped(serverId, it) }.distinctBy { it.id }.sortedWith(sessionActivityOrder)
      val parentMap = store.taskParents(serverId) + nextSessions.mapNotNull { s -> s.parentId?.let { s.id to it } }
      val states = TaskReconciliation.reconcile(previousTasks, nextSessions, active, parentMap,
        deletedSessions, inboxRead).toMutableMap()
      // A successful read is authoritative; a failed one keeps the previous requests so a pending
      // approval is never silently dropped.
      val readIds = waitingCandidates.map { it.id }.toSet()
      val changedWhileReading = waitingEventSeqAt.filterValues { it > readWaitingSeq }.keys
      val keptPermissions = previous.permissions.filter { it.sessionId !in deletedSessions && (it.sessionId !in readIds || it.sessionId in failedPermissionSessions || it.sessionId in changedWhileReading) }
      val fetchedPermissions = permissions.filterNot { it.sessionId in deletedSessions || it.sessionId in changedWhileReading || it.id in resolvedPermissions }
      val nextPermissions = allOverview?.pending(serverId, (fetchedPermissions + keptPermissions).distinctBy { it.id })
        ?: (fetchedPermissions + keptPermissions).distinctBy { it.id }
      val keptQuestions = previous.questions.filter { it.sessionId !in deletedSessions && (normalizedDirectory(it.directory) !in verifiedFormDirs || it.sessionId in changedWhileReading) }
      val fetchedQuestions = questions.filterNot { it.sessionId in deletedSessions || it.sessionId in changedWhileReading || it.id in resolvedPermissions }
      val nextQuestions = (fetchedQuestions + keptQuestions).distinctBy { it.id }
      fun waitingState(sessionId: String, phase: TaskPhase, detail: String): TaskState {
        val previousTask = previousTasks[sessionId]
        return TaskState(sessionId, phase, detail, previousTask?.since ?: System.currentTimeMillis(),
          activeAt = previousTask?.activeAt ?: -1L, pendingInput = states[sessionId]?.pendingInput == true)
      }
      nextPermissions.forEach { states[it.sessionId] = waitingState(it.sessionId, TaskPhase.WAITING_PERMISSION, "等待权限确认") }
      nextQuestions.forEach { states[it.sessionId] = waitingState(it.sessionId, TaskPhase.WAITING_QUESTION, "等待你的回答") }
      // 权威清除等待态：只有本次成功读取过该会话对应的权限/表单、且确认没有待处理项时才结束等待。
      // 读取失败、该会话没被读取、或读取期间又来了新的等待事件时一律保留，避免“等待输入 ↔ 运行中”的假切换（A04）。
      val readPermissionIds = waitingCandidates.map { it.id }.filterNot { it in failedPermissionSessions }.toSet()
      val readFormDirs = formDirectories.map(::normalizedDirectory).filterNot { it in failedFormDirs }.toSet()
      val resolvedWaiting = states.values.filter { it.phase in TaskState.WAITING_PHASES }.mapNotNull { task ->
        val session = nextSessions.firstOrNull { it.id == task.sessionId } ?: return@mapNotNull null
        if ((waitingEventSeqAt[task.sessionId] ?: 0L) > readWaitingSeq) return@mapNotNull null
        val resolved = when (task.phase) {
          TaskPhase.WAITING_PERMISSION -> task.sessionId in readPermissionIds && nextPermissions.none { it.sessionId == task.sessionId }
          TaskPhase.WAITING_QUESTION -> normalizedDirectory(session.directory) in readFormDirs && nextQuestions.none { it.sessionId == task.sessionId }
          else -> false
        }
        task.sessionId.takeIf { resolved }
      }
      resolvedWaitingIds = resolvedWaiting
      resolvedWaiting.forEach { id ->
        val waiting = states[id]!!
        if (active != null && id !in active && !waiting.pendingInput && !waiting.uncertain) {
          states.remove(id)
          store.rememberTask(serverId, waiting.copy(phase = TaskPhase.IDLE, detail = ""), parentMap[id])
          persistedTasks.remove("$serverId\u0000$id")
        }
        else states[id] = waiting.copy(phase = if (waiting.pendingInput) TaskPhase.WAITING_SERVER else TaskPhase.THINKING,
          detail = if (waiting.pendingInput) "等待服务器处理" else TaskState.RUNNING_DETAIL)
      }
      // Roots whose family this device saw running and that an authoritative read now shows idle.
      val wasActive = SessionActivityRules.rootStates(previousTasks.filterValues { it.active }, parentMap)
      val isActive = SessionActivityRules.rootStates(states.filterValues { it.active }, parentMap)
      finishedRuns = if (active == null) emptyList() else nextSessions.filter { it.parentId == null && it.id in wasActive && it.id !in isActive }
        .map { (identities[it.id] ?: it) to wasActive.getValue(it.id).since }
      // Reconciliation keeps “加载更多” progress; a full refresh restarts paging from the first page.
      val cursors = if (controlOnly && previous.sessionCursors.isNotEmpty()) previous.sessionCursors.takeIf { page.next != null }.orEmpty()
        else listOfNotNull(page.next).associateBy { "" }
      withSummary(previous.copy(version = version, capabilities = capabilities,
        draftSessions = draftSessionIds,
        previews = nextSessions.associate { session -> session.id to (previous.previews[session.id] ?: loadedPreviews[session.id] ?: SessionPreview()) },
        connected = true, cached = previous.cached && previous.sessionId != null, loading = false,
        error = null, degraded = degraded, lastContactAt = System.currentTimeMillis(),
        connection = if (previous.connection.stage == ConnectStage.READY) previous.connection else ConnectionStatus(ConnectStage.READY, System.currentTimeMillis()),
        remoteRecovery = null,
        projects = projects, workspaceCatalog = workspaceCatalog, sessions = nextSessions, knownParents = store.taskParents(serverId), tasks = states, shells = nextShells, notices = store.sessionNotices(serverId), collapsedSections = store.collapsedSections(serverId), sessionCursors = cursors, catalogComplete = cursors.isEmpty(),
        permissions = nextPermissions, questions = nextQuestions,
        projectId = (previous.projectId ?: store.selectedProject(serverId))?.takeIf { id -> projects.any { it.id == id } } ?: projects.firstOrNull()?.id,
        scopeProjectId = previous.scopeProjectId?.takeIf { id -> projects.any { it.id == id } },
        sessionId = if (draftServerId == serverId) null else (previous.sessionId ?: store.selectedSession(serverId))?.takeIf { id -> nextSessions.any { it.id == id } }))
    }
    trace(token, ConnectMilestone.INTERACTIVE)
    trace(token, ConnectMilestone.CONTROL)
    if (workspaceCatalog.isNotEmpty()) adoptDefaultDraftWorkspace(serverId, workspaceCatalog)
    flushQueuedSends()
    flushPendingDraft()
    // 权威读取证明已处理的等待项：撤掉可能已发出的“需要授权 / 等待回答”通知。
    resolvedWaitingIds.forEach(::cancelAttention)
    finishedRuns.forEach { (session, runSince) ->
      if (!state.value.notices.coversRun(session.id, runSince)) session.transitionNotice(runSince)?.let { recordNotice(serverId, session, it, runSince) }
    }
    // 权威读取发现的、事件流没送达的待处理事项也要通知（例如断线期间产生的权限/表单）。
    val newWaitingSessions = state.value.permissions.filter { it.id !in priorRequestIds }.map { it.sessionId } +
      state.value.questions.filter { it.id !in priorRequestIds }.map { it.sessionId }
    newWaitingSessions.distinct().forEach(::notifyAttention)
    allOverview?.consider(serverId, state.value.permissions)
    if (!controlOnly) lastFullRefreshAt = System.currentTimeMillis()
    val current = mutable.value
    current.serverId?.let { cache.saveCatalog(it, current.projects, current.sessions, complete = current.catalogComplete) }
    if (!followUp) return
    if (controlOnly) {
      current.session?.let { loadSession(it, token = token, ancillary = false) }
      return
    }
    // 未打开会话时也按首页选中的服务器加载模型目录（项目 → 最近会话目录），管理模型因此不再依赖会话。
    // 没有项目/会话时传空目录，让服务器按自己的默认工作区返回模型目录。它们与会话内容已在第一层读完后
    // 并行开始；这里只补上当时还不认识的会话（例如只能从祖先链补读到的会话）。
    earlyFollowUp?.join()
    if (earlySession == null) current.session?.let { loadSession(it, token = token, ancillary = false) }
    prefetchTranscripts(token, client)
  }
  fun loadMoreSessions() = act("sessions-more") { op ->
    val cursor = op.snapshot.sessionCursors[""] ?: return@act
    val page = op.client.rootSessionsPage(cursor = cursor)
    check(page.next != cursor) { "服务器返回了重复分页游标" }
    store.rememberParents(op.serverId, page.items)
    op.commitConnection { it.copy(knownParents = store.taskParents(op.serverId), sessions = (page.items.map { session -> scoped(op.serverId, session) } + it.sessions).distinctBy { s -> s.id }.sortedWith(sessionActivityOrder),
      sessionCursors = listOfNotNull(page.next).associateBy { "" }, catalogComplete = page.next == null) }
    if (op.connectionCurrent(this)) cache.saveCatalog(op.serverId, state.value.projects, state.value.sessions, complete = state.value.catalogComplete)
  }
  fun loadOlderMessages(): Job {
    val cursor = state.value.messagesCursor
    if (transcriptSync != null && TranscriptSync.localId(cursor) != null) return loadStoredOlderMessages(cursor!!)
    return loadServerOlderMessages()
  }
  /** Older history the device already holds: no connection needed, so it also works offline. */
  private fun loadStoredOlderMessages(cursor: String): Job {
    val current = state.value
    val serverId = current.serverId ?: return scope.launch { }
    val session = current.session ?: return scope.launch { }
    val revision = selectionRevision
    val key = operationKey(serverId, session.id, current.projectId, "messages-more")
    if (key in current.pendingOperations) return scope.launch { }
    updateState { it.copy(pendingOperations = it.pendingOperations + key) }
    return scope.launch {
      try {
        val older = transcriptSync!!.loadOlder(api, serverId, session, cursor)
        if (state.value.serverId == serverId && selectionRevision == revision && state.value.messagesCursor == cursor) updateState {
          it.copy(messages = (older.messages + it.messages).distinctBy { m -> m.id }, messagesCursor = older.next,
            loadedOlderMessages = true, historyRevision = it.historyRevision + 1)
        }
      } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) {
        Diagnostics.warn("TranscriptSync", "读取本机历史失败", error)
      } finally {
        updateState { it.copy(pendingOperations = it.pendingOperations - key) }
      }
    }
  }
  private fun loadServerOlderMessages() = withSession("messages-more") { op, client, session ->
    val cursor = op.snapshot.messagesCursor ?: return@withSession
    val sync = transcriptSync?.takeIf { client.cacheableTranscripts }
    // Stored history first (no network), then the server; server pages extend the stored run.
    val page = if (sync != null) sync.loadOlder(client, op.serverId, session, cursor).let { ApiPage(it.messages, it.next) }
      else client.messagesPage(session.id, cursor, HarnessClient.HISTORY_PAGE).let { first ->
        check(first.next != cursor) { "服务器返回了重复分页游标" }
        // Same whole-turn rule as the initial window and the stored path (TranscriptSync.loadOlder).
        widenOlderToTurn(first.items, first.next, TranscriptSync.MAX_TURN_WIDEN) { from ->
          client.messagesPage(session.id, from, HarnessClient.HISTORY_PAGE).also { check(it.next != from) { "服务器返回了重复分页游标" } }.let { it.items to it.next }
        }.let { (items, next) -> ApiPage(items, next) }
      }
    check(page.next != cursor) { "服务器返回了重复分页游标" }
    op.commit { it.copy(messages = (page.items + it.messages).distinctBy { m -> m.id }, messagesCursor = page.next,
      loadedOlderMessages = true, historyRevision = it.historyRevision + 1) }
  }
  /** Home/pull-to-refresh: a cheap catalog + pending read, not the deep shell/descendant sweep. */
  fun reload(): Job {
    allOverview.refreshAll()
    return reloadConversation(deep = false)
  }
  private fun reloadConversation(deep: Boolean = true): Job {
    if (api == null || state.value.serverId == null) return scope.launch { }
    val token = generation
    refresh?.cancel()
    connectTrace.begin(token.toLong(), "刷新")
    advanceStage(token, ConnectStage.CONNECTING)
    return scope.launch {
      try {
        loadAllInternal(token, controlOnly = false, followUp = true, deep = deep)
        // A refresh after an offline start must also (re)establish the live event stream, otherwise
        // the UI shows connected=true with no realtime updates (A10).
        if (token == generation && stream?.isActive != true && streamAllowed()) startStream(token)
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        val reason = userFacingError(error, "刷新失败")
        if (token == generation) connectTrace.fail(token.toLong(), reason)
        if (token != generation) return@launch
        val serverId = state.value.serverId ?: return@launch
        if (error.isRetryableTransportFailure()) {
          // A relay/RPC drop during a read is transient. Keep the last known list visible, mark the
          // page off-line and let the normal backoff reconnect; do not toast the library text or
          // leave the user with no path back to a live stream.
          Diagnostics.warn("LagoonController", "刷新时连接中断，保留现有数据并重连", error)
          updateState { state -> state.copy(connected = false, loading = false, error = null) }
          scheduleReconnect(serverId, token, reason = "连接中断，正在重连")
        } else {
          updateState { state -> state.copy(error = reason,
            remoteRecovery = remoteFailure(error)?.kind?.recovery()
              ?: if (error.isAuthFailure) RemoteRecoveryKind.LOGIN else state.remoteRecovery,
            connection = if (state.connection.stage == ConnectStage.READY) state.connection
              else ConnectionStatus(ConnectStage.RETRYING, System.currentTimeMillis(), state.connection.retryAt, retryReason(reason))) }
        }
      }
    }.also { refresh = it }
  }

  /**
   * The controller's own fallback reconcile only runs while the app is visible or the monitoring
   * service is up. The process-level SSE subscriptions in AllServersOverview stay connected, so
   * background notifications still work; this only stops the extra polling pass.
   */
  private fun streamAllowed() = foreground || monitoring

  /** The app came to the foreground: re-read the server unless that just happened, and resume live events. */
  fun onForeground() {
    foreground = true
    synchronizeQuestionPresentation()
    backgroundStop?.cancel()
    // After a long pause the OS may have frozen or silently dropped the sockets; reconnect rather than
    // trust a stream that only looks open.
    val away = backgroundedAt.takeIf { it > 0 }?.let { System.currentTimeMillis() - it } ?: 0
    backgroundedAt = 0
    if (away > STREAM_RESUME_RESTART_MILLIS) allOverview.restartStreams()
    sshHostManager.kickAll()
    allOverview.start(store.profiles(), include = setOfNotNull(state.value.serverId))
    ensureMonitoring(state.value)
    val server = state.value.serverId ?: return
    if (connecting?.isActive == true || refresh?.isActive == true) return
    if (api == null) { connect(server, preserveHome = true); return }
    val stale = System.currentTimeMillis() - state.value.lastContactAt > FOREGROUND_STALE_MILLIS
    when {
      !state.value.connected || stale -> reload()
      stream?.isActive != true && state.value.serverId == server -> startStream(generation)
    }
  }

  /** The app left the foreground: process-level connections keep running, the fallback reconcile pauses. */
  fun onBackground() {
    foreground = false
    synchronizeQuestionPresentation()
    backgroundedAt = System.currentTimeMillis()
    // Control events still drive notifications; transcript/workspace follows are only useful while
    // the reader is on screen. onForeground restores the selected server's content subscription.
    allOverview.setContentWatching(false)
  }

  /** Called by [TaskMonitorService]: while it runs, the process (and the live stream) may stay up in background. */
  fun setMonitoring(active: Boolean) {
    monitoring = active
    if (active && stream?.isActive != true && state.value.connected) startStream(generation)
    if (!active) monitorRequested.remove(TaskNotifications.ALL_SERVERS_ID)
  }

  /**
   * Periodic background check (WorkManager): one authoritative read without opening the live stream.
   * A run this device saw that has since ended becomes an unread result and a completion notification.
   */
  suspend fun backgroundSync() {
    if (foreground || monitoring && stream?.isActive == true) {
      allOverview.refreshAll()
      return
    }
    coroutineScope {
      val overviewSync = async { allOverview.refreshAllAndAwait() }
      connecting?.takeIf { it.isActive }?.join()
      if (state.value.serverId != null && api != null &&
        System.currentTimeMillis() - lastFullRefreshAt >= refreshMode.foregroundRefreshMs) {
        val token = generation
        attempt { loadAllInternal(token, controlOnly = false, followUp = false, deep = false) }
          .onFailure { Diagnostics.warn("BackgroundSync", "后台同步失败", it) }
      }
      overviewSync.await()
    }
  }

  /**
   * Running tasks — from this phone or any other client — are monitored while they run, so their
   * results arrive in real time even after the app is backgrounded. A foreground service can only be
   * started from the foreground, so this runs on state changes while the app is visible.
   */
  private fun ensureMonitoring(state: LagoonState) {
      val active = LiveUpdateContent.stageOf(state.globalSummary) == LiveUpdateStage.ACTIVE || state.globalBackgroundCount > 0
      if (foreground && active && state.enabledProfiles.any { it.notifications } && TaskNotifications.ALL_SERVERS_ID !in monitorRequested) {
        monitorRequested += TaskNotifications.ALL_SERVERS_ID
        attempt { TaskMonitorService.startAll(appContext) }
          .onFailure {
            // 一次启动失败（例如前台服务启动窗口受限）不能把本轮监控永久锁死，下一轮状态变化再试。
            monitorRequested.remove(TaskNotifications.ALL_SERVERS_ID)
            Diagnostics.warn("Monitor", "无法启动全部服务器任务监控", it)
          }
      }
      if (!active) monitorRequested.remove(TaskNotifications.ALL_SERVERS_ID)
  }
  private fun startStream(token: Int) {
    stream?.cancel()
    // AllServersOverview is the sole SSE owner. UI switches only replace this read loop.
    // Standing reconciliation: while the stream is nominally up, periodically re-read the control
    // plane (catalog/activity/permissions/forms). This converges state even if the server drops an
    // event without the client seeing a stream failure, so a missed permission or terminal state is
    // repaired rather than waiting for the user to notice (A04/A10).
    reconcile?.cancel()
    reconcile = scope.launch {
      while (isActive && token == generation) {
        // SSE/`$events` is authoritative here. This loop is a 5-minute safety net for a dropped frame,
        // not a periodic full refresh: the old 10–30s cadence re-read catalogs, children and shells
        // while the user was idle.
        delay(SAFETY_RECONCILE_MILLIS)
        // Skip while a manual refresh is running or while the stream is already down: the stream
        // loop re-reads on every reconnect, so reconciling there would only fight the disconnected state.
        if (token != generation || refresh?.isActive == true || !streamAllowed()) continue
        attempt { loadAll(token, controlOnly = true, followUp = false) }.onFailure { error ->
          Diagnostics.warn("LagoonController", "状态同步失败", error)
          if (token == generation) updateState { it.copy(degraded = true) }
        }
      }
    }
    stream = reconcile
  }

  /**
   * Applies one `/api/event` frame the way the official client does: targeted state updates and a
   * re-read of only the affected session, never a full catalog reload per event.
   */
  private fun handleEvent(event: ServerEvent) {
    val props = event.properties
    if (event.type.startsWith("session.execution.") || event.type.startsWith("shell.") ||
      event.type in setOf("session.deleted", "session.synthetic", "session.error", "session.idle", "session.status") ||
      BackgroundWork.backgroundSubagentId(event.type, props) != null) {
      workRevision++
      childDiscovery = null
    }
    if (event.type == "permission.asked" && props.str("id") in resolvedPermissions) return
    val sessionId = props.sessionEventId(event.type)
    val known = mutable.value.sessions.firstOrNull { it.id == sessionId }
    val directory = known?.directory ?: event.directory.ifBlank { props.sessionInfo().toSession().directory }
    if (sessionId.isNotBlank()) {
      val before = mutable.value.tasks[sessionId]
      // 事件会话自身子树里是否还有会唤醒它的后台工作（后台 shell / 仍在执行的子代理）。
      // 用 sessionId 而不是根：子会话自己的 run settle 时，兄弟子任务不算它的后台工作。
      var after = TaskReducer.event(sessionId, event.type, props, before, event.created)
      if (after != null) {
        val resolved = props.str("requestID").ifBlank { props.str("id") }
        val pendingPermission = mutable.value.permissions.any { it.sessionId == sessionId && !(event.type == "permission.replied" && it.id == resolved) } || event.type == "permission.asked"
        val pendingQuestion = mutable.value.questions.any { it.sessionId == sessionId && !(event.type in setOf("form.replied", "form.cancelled") && it.id == resolved) } || event.type == "form.created"
        val pending = pendingPermission || pendingQuestion
        if (pending && after.phase !in TaskState.TERMINAL_PHASES) after = TaskState(sessionId,
          if (pendingPermission) TaskPhase.WAITING_PERMISSION else TaskPhase.WAITING_QUESTION,
          if (pendingPermission) "等待权限确认" else "等待你的回答", before?.since ?: System.currentTimeMillis(),
          activeAt = before?.activeAt ?: -1L, pendingInput = before?.pendingInput == true)
        val next = after
        val enteringTerminal = next.phase in TERMINAL_PHASES && before?.phase !in TERMINAL_PHASES
        updateState { current -> withSummary(current.copy(tasks = current.tasks + (sessionId to next))) }
        if (enteringTerminal && sessionId == state.value.sessionId && messageRefresh?.isActive != true) {
          state.value.session?.let { selected -> val token = generation; messageRefresh = scope.launch { delay(refreshMode.messageDebounceMs); loadSession(selected, ancillary = false, token = token) } }
        }
        // 运行中的任务不再单独发通知：通知中心只保留实时更新总览，结果由未读账本（recordNotice）通知，
        // 等待用户处理由 notifyAttention 通知；这样运行中的会话不会在通知中心堆积。
      }
    }
    when (event.type) {
      "permission.asked" -> {
        val request = props.toPermission(directory)
        if (request.id in resolvedPermissions) return
        waitingEventSeqAt[request.sessionId] = ++waitingEventSeq
        updateState { withSummary(it.copy(permissions = (it.permissions.filterNot { old -> old.id == request.id } + request))) }
        allOverview?.consider(state.value.serverId ?: return, listOf(request))
        notifyAttention(request.sessionId)
      }
      "form.created", "form.updated" -> {
        val request = props.obj("form").toForm(directory)
        if (event.type == "form.created") waitingEventSeqAt[request.sessionId] = ++waitingEventSeq
        updateState { withSummary(it.copy(questions = (it.questions.filterNot { old -> old.id == request.id } + request))) }
        if (event.type == "form.created") notifyAttention(request.sessionId)
      }
      "permission.replied" -> {
        val requestId = props.str("requestID").ifBlank { props.str("id") }
        permissionResolved(sessionId, requestId)
        cancelAttention(sessionId)
      }
      "form.replied", "form.cancelled" -> {
        val requestId = props.str("id").ifBlank { props.str("requestID") }
        questionResolved(sessionId, requestId)
        cancelAttention(sessionId)
      }
      "session.created" -> {
        val info = JSONObject(props.sessionInfo().toString()).put("id", sessionId)
        if (event.created > 0) {
          val time = info.obj("time")
          if (time.optLong("created", 0) <= 0) time.put("created", event.created)
          if (time.optLong("updated", 0) <= 0) time.put("updated", event.created)
          info.put("time", time)
        }
        val created = state.value.serverId?.let { server -> scoped(server, info.toSession()) } ?: info.toSession()
        if (created.id.isNotBlank()) {
          state.value.serverId?.let { store.rememberParents(it, listOf(created)) }
          updateState { current ->
            val prior = current.sessions.firstOrNull { it.id == created.id }
            // blank 是单向的：已经见过它开始轮次，就不能被更旧的 created 事件重新标记为草稿。
            val merged = if (prior != null && !prior.blank) created.copy(blank = false) else created
            val parent = merged.parentId
            val parents = if (parent == null) current.knownParents - merged.id
            else current.knownParents + (merged.id to parent)
            current.copy(knownParents = parents,
              sessions = (listOf(merged) + current.sessions.filterNot { it.id == merged.id }).sortedWith(sessionActivityOrder))
          }
        }
      }
      "session.renamed" -> updateSession(sessionId) { it.copy(title = props.str("title")) }
      "session.moved" -> updateSession(sessionId) { session ->
        // `location.directory` is the new working directory; `subpath` is that location relative to
        // the project root, so it must not be appended (see `toSession`).
        val location = props.obj("location").str("directory")
        session.copy(directory = location.ifBlank { session.directory }, projectId = props.str("projectID").ifBlank { session.projectId })
      }
      "session.viewed" -> updateSession(sessionId) { it.copy(viewed = maxOf(it.viewed, props.optLong("idle"))) }
      /*
       * Archive state can change from another client (the desktop, a TUI). Without these two cases the
       * phone kept showing a freshly archived session until the next full reconcile, and — since
       * `/api/session` returns both states — it stayed in the home list as if it were live work.
       */
      "session.archived" -> updateSession(sessionId) { it.copy(archived = true) }
      "session.unarchived" -> updateSession(sessionId) { it.copy(archived = false) }
      // A fork creates a session this client has never read; re-read the id the event names.
      "session.forked" -> refreshSession(sessionId)
      "session.revert.staged" -> updateSession(sessionId) { it.copy(revertMessageId = props.obj("revert").str("messageID").ifBlank { null }) }
      "session.revert.cleared", "session.revert.committed" -> updateSession(sessionId) { it.copy(revertMessageId = null) }
      "session.agent.selected", "session.model.selected" -> {
        updateSession(sessionId) { session ->
          if (event.type == "session.agent.selected") session.copy(agent = props.str("agent").ifBlank { session.agent })
          else session.copy(model = props.obj("model").toModelChoice() ?: session.model)
        }
        updateState { current ->
          if (sessionId != current.sessionId) current else current.copy(
            agent = if (event.type == "session.agent.selected" && !current.agentChanged) props.str("agent").ifBlank { current.agent } else current.agent,
            model = if (event.type == "session.model.selected" && !current.modelChanged) props.obj("model").toModelChoice() ?: current.model else current.model)
        }
      }
      "session.deleted" -> {
        attentionJobs.remove(sessionId)?.cancel()
        waitingEventSeqAt.remove(sessionId)
        state.value.serverId?.let { server ->
          transcriptMemory.forget(server, sessionId)
          sessionViews.forget(server, sessionId)
          conversationPool.forget(server, sessionId)
          forgetStoredTranscript(server, sessionId)
          store.forgetSession(server, sessionId)
          attempt { notifications.cancel(server, sessionId) }
        }
        persistedTasks.remove("${state.value.serverId}\u0000$sessionId")
        updateState { withSummary(it.copy(
          sessions = it.sessions.filterNot { session -> session.id == sessionId },
          tasks = it.tasks - sessionId,
          knownParents = it.knownParents - sessionId,
          shells = it.shells.filterValues { shell -> shell.sessionId != sessionId },
          permissions = it.permissions.filterNot { request -> request.sessionId == sessionId },
          questions = it.questions.filterNot { request -> request.sessionId == sessionId },
          notices = it.notices.filterNot { notice -> notice.sessionId == sessionId })) }
      }
      "project.updated" -> {
        val project = props.toProject()
        val server = state.value.serverId
        if (project.id.isNotBlank()) updateState { current ->
          val updated = current.projects.map {
            if (it.id == project.id) project.copy(sandboxes = project.sandboxes.ifEmpty { it.sandboxes }, serverId = server) else it
          }
          if (server != null) store.rememberLocalProjects(server, updated)
          current.copy(projects = updated)
        }
      }
      // 后台 shell 的实时增删：created 带完整 Shell.Info（`metadata.sessionID` 归属会话），
      // exited/deleted 只带 id。后台活动由 withSummary 重新计算，不改变根会话相位。
      "shell.created" -> props.obj("info").toShellJob(event.directory)?.let { job ->
        updateState { withSummary(it.copy(shells = it.shells + (job.id to job))) }
      }
      "shell.exited", "shell.deleted" -> {
        val shellId = props.str("id")
        if (shellId.isNotBlank() && mutable.value.shells.containsKey(shellId)) updateState { withSummary(it.copy(shells = it.shells - shellId)) }
      }
      // 任务清单是整值投影：每次 `todo_write` 都带来完整列表，事件本身就是最新真值。
      "session.todos.changed" -> if (sessionId.isNotBlank() && sessionId == mutable.value.sessionId) {
        val todos = props.arr("todos").toRemoteTodos()
        updateState { it.copy(todos = todos) }
      }
      // 目标阶段/阻塞/完成由 Host 的 goal 快照变化驱动；activation 只能通过 `goals/get` 重读。
      "session.goal.changed" -> if (sessionId.isNotBlank() && sessionId == mutable.value.sessionId) loadGoal()
      // A run started or ended: official `session.sync` of that one session (outcome, idle, updated).
      "session.execution.started", "session.execution.succeeded", "session.execution.failed", "session.execution.interrupted" -> {
        // `turn/start` 是清单的边界：Host 在同一时刻清空投影，界面跟随，避免上一轮清单冒充本轮。
        if (event.type == "session.execution.started" && sessionId.isNotBlank() && sessionId == mutable.value.sessionId &&
          mutable.value.todos.isNotEmpty()) updateState { it.copy(todos = emptyList()) }
        refreshSession(sessionId)
      }
    }
    // The parent tool may settle before the child session emits a useful execution event. Official
    // metadata still identifies that child as running; keep every such child separately so sibling
    // background tasks are not collapsed into one last-seen boolean.
    val backgroundChild = BackgroundWork.backgroundSubagentId(event.type, props)
    if (sessionId.isNotBlank() && backgroundChild != null) {
      updateState { current ->
        val previous = current.tasks[backgroundChild]
        val child = if (previous?.active == true || previous?.phase in TaskState.TERMINAL_PHASES) previous!! else TaskState(backgroundChild, TaskPhase.SUBAGENT,
          TaskState.RUNNING_DETAIL, since = event.created.takeIf { it > 0 } ?: System.currentTimeMillis(), activeAt = -1L)
        withSummary(current.copy(tasks = current.tasks + (backgroundChild to child),
          knownParents = current.knownParents + (backgroundChild to sessionId)))
      }
    }
    if (event.type in setOf("session.execution.succeeded", "session.execution.failed")) recordResultNotice(event, sessionId)
    if (sessionId.isNotBlank() && sessionId == mutable.value.sessionId) {
      val projected = TranscriptProjection.apply(mutable.value.messages, event)
      if (projected != null && projected.messages !== mutable.value.messages) {
        val before = mutable.value.messages.associateBy { it.id }
        val liveIds = projected.messages.filter { before[it.id] != it }.map { it.id }.toSet()
        updateState { it.copy(messages = projected.messages, provisionalMessageIds = it.provisionalMessageIds - liveIds,
          resources = it.resources + ("messages" to ResourceStatus(ResourceState.READY))) }
      }
       if (projected?.reconcile == true && messageRefresh?.isActive != true) {
         mutable.value.session?.let { session ->
           val token = generation
           messageRefresh = scope.launch { delay(refreshMode.messageDebounceMs); loadSession(session, ancillary = false, token = token) }
         }
      }
    }
    // Apply this after transcript projection so the completion notice prevents the history-derived
    // fallback above from immediately recreating the child task.
    BackgroundWork.completedSubagentId(event.type, props)?.let { child ->
      updateState { current -> withSummary(current.copy(tasks = current.tasks + (child to
        TaskReducer.terminal(child, when (props.obj("metadata").str("state")) {
          "error" -> "failed"; "cancelled" -> "interrupted"; else -> "succeeded"
        }, current.tasks[child])))) }
    }
  }

  private fun updateSession(id: String, change: (Session) -> Session) {
    if (id.isBlank()) return
    updateState { current -> if (current.sessions.none { it.id == id }) current else current.copy(sessions = current.sessions.map { if (it.id == id) change(it) else it }.sortedWith(sessionActivityOrder)) }
  }

  /** Re-reads one session's info; an unknown session becomes known (e.g. a run started from another client). */
  private fun refreshSession(id: String) {
    if (id.isBlank()) return
    val client = api ?: return
    val token = generation
    scope.launch {
      val freshRaw = attempt { client.session(id) }.getOrNull() ?: return@launch
      val server = state.value.serverId ?: return@launch
      val fresh = scoped(server, freshRaw)
      if (token != generation) return@launch
      state.value.serverId?.let { store.rememberParents(it, listOf(fresh)) }
      updateState { current -> withSummary(current.copy(sessions = (listOf(fresh) + current.sessions).distinctBy { it.id }.sortedWith(sessionActivityOrder),
        knownParents = current.knownParents + listOfNotNull(fresh.parentId?.let { fresh.id to it }))) }
      // A child whose root is outside the catalog page: resolve the ancestry so its state rolls up.
      fresh.parentId?.takeIf { parent -> state.value.sessions.none { it.id == parent } }?.let(::refreshSession)
    }
  }

  private fun recordResultNotice(event: ServerEvent, sessionId: String) {
    if (sessionId.isBlank()) return
    val current = state.value
    val server = current.serverId ?: return
    val token = generation
    val known = current.sessions.firstOrNull { it.id == sessionId }
    val runSince = current.tasks[sessionId]?.since ?: System.currentTimeMillis()
    fun record(session: Session) {
      if (generation != token || state.value.serverId != server) return
      event.resultNotice(session)?.let { recordNotice(server, session, it, runSince) }
    }
    if (known != null) record(known)
    else scope.launch {
      val client = api ?: return@launch
      attempt { findSession(client, sessionId) }.getOrNull()?.let(::record)
    }
  }

  /**
   * Adds one unread result to the ledger. As in the official client it is born viewed when the user is
   * looking at that conversation; otherwise it raises the completion/failure notification. A second
   * report of the same run (`session.idle` plus a V2 execution event, or event plus reconciliation)
   * only upgrades a success to a failure.
   */
  private fun recordNotice(server: String, session: Session, notice: SessionNotice, runSince: Long) {
    val existing = state.value.notices.filter { it.sessionId == session.id && it.time >= runSince }
    if (existing.any { it.error || !notice.error }) return
    val viewing = foreground && visibleConversation == (server to session.id)
    val notices = store.rememberNotice(server, notice.copy(viewed = viewing))
    updateState { if (it.serverId == server) withSummary(it.copy(notices = notices)) else it }
    if (viewing) return
    val profile = state.value.server?.takeIf { it.id == server && it.notifications } ?: return
    val result = TaskState(session.id, if (notice.error) TaskPhase.FAILED else TaskPhase.COMPLETED,
      if (notice.error) "执行失败" else "任务已完成", runSince, notice.time)
    attempt { notifications.show(profile, session, result) }
  }

  fun toggleSection(key: String) {
    val current = state.value; val server = current.serverId ?: return
    val collapsed = if (key in current.collapsedSections) current.collapsedSections - key else current.collapsedSections + key
    store.rememberCollapsedSections(server, collapsed)
    updateState { it.copy(collapsedSections = collapsed) }
  }
  fun togglePin(sessionId: String) {
    val current = state.value; val server = current.serverId ?: return
    val pinning = sessionId !in current.pinned
    val pinned = if (pinning) current.pinned + sessionId else current.pinned - sessionId
    store.rememberPinnedSessions(server, pinned)
    updateState { it.copy(pinned = pinned) }
    // DSH 的置顶是服务端事实（`workspace/pinSession`）：本地立即生效，再同步给电脑端，
    // 否则手机上的置顶在 DSH Web 里看不到、桌面上的置顶手机也读不到。
    val session = current.sessions.firstOrNull { it.id == sessionId } ?: return
    scope.launch {
      val client = api ?: return@launch
      runCatching { client.setSessionPinned(session, pinning) }
        .onFailure { Diagnostics.warn("Pin", "同步置顶到服务器失败", it) }
    }
  }

  /**
   * 进入“待处理”后去抖再通知：权限/表单瞬时出现又消失（事件与对账竞态、另一客户端已处理）时不打扰。
   * 去抖窗口内请求被清除则什么都不发。
   */
  private fun notifyAttention(sessionId: String) {
    if (sessionId.isBlank()) return
    attentionJobs.remove(sessionId)?.cancel()
    val serverId = state.value.serverId
    val token = generation
    val client = api
    attentionJobs[sessionId] = scope.launch {
      delay(ATTENTION_DEBOUNCE_MILLIS)
      val current = mutable.value
      if (current.serverId != serverId) return@launch
      val profile = current.server ?: return@launch
      val session = current.sessions.firstOrNull { it.id == sessionId } ?: return@launch
      if (!profile.notifications) return@launch
      val task = current.presentedTasks[sessionId] ?: return@launch
      // 期间已被处理（事件或对账清除）则不再打扰。
      val stillWaiting = task.phase in TaskState.WAITING_PHASES &&
        (current.visiblePermissions.any { it.sessionId == sessionId } || current.questions.any { it.sessionId == sessionId })
      if (!stillWaiting) return@launch
      // Recheck the server, not just the debounce timer: another client may already have approved.
      val permission = current.visiblePermissions.firstOrNull { p -> p.sessionId == sessionId }
      if (permission != null) {
        val pending = attempt { client?.sessionPermissions(session) }.getOrNull()
        if (generation != token || state.value.serverId != serverId) return@launch
        if (pending != null && pending.none { it.id == permission.id }) { permissionResolved(sessionId, permission.id); return@launch }
      }
      if (permission != null && state.value.visiblePermissions.none { it.id == permission.id }) return@launch
      val question = current.questions.firstOrNull { it.sessionId == sessionId }
      notifications.show(profile, session, task, permission, dedupeId = permission?.id ?: question?.id)
    }
  }

  private fun notifyOverviewAttention(server: String, sessionId: String) {
    val key = scopedKey(server, sessionId)
    attentionJobs.remove(key)?.cancel()
    attentionJobs[key] = scope.launch {
      delay(ATTENTION_DEBOUNCE_MILLIS)
      val snapshot = allSnapshots[server] ?: return@launch
      if (state.value.serverId == server) return@launch
      val session = snapshot.sessions.firstOrNull { it.id == sessionId } ?: return@launch
      val permission = snapshot.permissions.firstOrNull { it.sessionId == sessionId && state.value.permissionPolicy.visible(it.id, snapshot.autoApprovalFailures) }
      val question = snapshot.questions.firstOrNull { it.sessionId == sessionId }
      if (permission == null && question == null) return@launch
      if (permission != null) {
        // A profile can be edited/disabled between the snapshot and this debounce; client setup
        // must not throw out of the attention coroutine.
        val client = runCatching { allOverview.client(server) }.getOrNull() ?: return@launch
        val pending = attempt { client.sessionPermissions(session) }.getOrNull()
        val sameClient = runCatching { allOverview.client(server) === client }.getOrDefault(false)
        if (store.profiles().none { it.id == server } || state.value.serverId == server || !sameClient) return@launch
        if (pending != null && pending.none { it.id == permission.id }) { allOverview.settled(server, permission.id); allOverview.refresh(server); return@launch }
        if (!state.value.permissionPolicy.visible(permission.id, allSnapshots[server]?.autoApprovalFailures.orEmpty())) return@launch
      }
      notifications.show(snapshot.profile, session, TaskState(sessionId,
        if (permission != null) TaskPhase.WAITING_PERMISSION else TaskPhase.WAITING_QUESTION), permission,
        dedupeId = permission?.id ?: question?.id)
    }
  }

  private fun permissionResolved(sessionId: String, requestId: String) {
    resolvedPermissions += requestId
    while (resolvedPermissions.size > 1000) resolvedPermissions.remove(resolvedPermissions.first())
    waitingEventSeqAt[sessionId] = ++waitingEventSeq
    state.value.serverId?.let { allOverview?.settled(it, requestId) }
    updateState { current ->
      val pending = current.permissions.filterNot { it.id == requestId }
      val task = current.tasks[sessionId]
      val restored = if (task?.phase == TaskPhase.WAITING_PERMISSION && pending.none { it.sessionId == sessionId })
        task.copy(phase = if (current.questions.any { it.sessionId == sessionId }) TaskPhase.WAITING_QUESTION else TaskPhase.THINKING,
          detail = if (current.questions.any { it.sessionId == sessionId }) "等待你的回答" else TaskState.RUNNING_DETAIL)
      else task
      withSummary(current.copy(permissions = pending, tasks = if (restored == null) current.tasks else current.tasks + (sessionId to restored)))
    }
  }

  private fun questionResolved(sessionId: String, requestId: String) {
    resolvedPermissions += requestId
    while (resolvedPermissions.size > 1000) resolvedPermissions.remove(resolvedPermissions.first())
    waitingEventSeqAt[sessionId] = ++waitingEventSeq
    updateState { current ->
      val pending = current.questions.filterNot { it.id == requestId }
      val task = current.tasks[sessionId]
      val restored = if (task?.phase == TaskPhase.WAITING_QUESTION && pending.none { it.sessionId == sessionId })
        task.copy(phase = if (current.permissions.any { it.sessionId == sessionId }) TaskPhase.WAITING_PERMISSION else TaskPhase.THINKING,
          detail = if (current.permissions.any { it.sessionId == sessionId }) "等待权限确认" else TaskState.RUNNING_DETAIL)
      else task
      withSummary(current.copy(questions = pending, tasks = if (restored == null) current.tasks else current.tasks + (sessionId to restored)))
    }
  }

  /** 请求被处理/清除后取消待处理通知：会话已无任何待处理项时才撤下。 */
  private fun cancelAttention(sessionId: String) {
    if (sessionId.isBlank()) return
    attentionJobs.remove(sessionId)?.cancel()
    val current = mutable.value
    val server = current.serverId ?: return
    if (current.permissions.any { it.sessionId == sessionId } || current.questions.any { it.sessionId == sessionId }) return
    attempt { notifications.cancel(server, sessionId) }
  }
  fun selectProject(id: String) {
    val project = mutable.value.projects.firstOrNull { it.id == id } ?: return
    selectionRevision += 1
    visibleConversation = null
    updateState { it.copy(projectId = id, sessionId = null, messages = emptyList(), agent = null, model = null,
      agentChanged = false, modelChanged = false, draft = "", references = emptyList(), resources = emptyMap(),
      changes = emptyList(), goal = null, todos = emptyList(), goalLoading = false, schedules = emptyList(), schedulesLoading = false,
      files = emptyList(), searchResults = emptyList(), fileText = null, fileBinary = false, filePreview = null, filePreviewMime = null) }
    store.rememberLocation(id, null)
    val token = generation
    scope.launch { loadChoices(project.directory, token) }
  }
  /**
   * Applies the phone's per-model variant selection without ever inventing a variant.  A stale
   * persisted id is removed when the server no longer advertises it, which makes the request fall
   * back to the server default on the next send.
   */
  private fun normalizeModelVariant(serverId: String, model: ModelChoice, catalog: List<ModelInfo>): ModelChoice {
    val info = catalog.firstOrNull { it.key == modelKey(model) } ?: return model
    val ids = info.variants.map { it.id }.toSet()
    val stored = store.modelVariants(serverId)[info.key]
    val selected = when {
      stored != null && stored in ids -> stored
      stored != null -> {
        store.rememberModelVariant(serverId, info.key, null)
        null
      }
      model.variant != null && model.variant in ids -> {
        // A model read from an existing server session is the same source of truth the official
        // client uses to initialize its local variant map.
        store.rememberModelVariant(serverId, info.key, model.variant)
        model.variant
      }
      else -> null
    }
    return info.choice.copy(variant = selected)
  }
  private suspend fun loadChoices(directory: String, token: Int = generation, force: Boolean = false) {
    val serverId = state.value.serverId ?: return
    val revision = selectionRevision
    val cached = if (force) null else choicesCache.get(serverId, directory)
    if (cached != null) {
      applyChoiceResults(token, revision, directory,
        Result.success(cached.agents), Result.success(cached.models), Result.success(cached.commands))
      return
    }
    val client = api ?: return
    LoadMetrics.record("choices")
    // Agents, models and commands are independent; one parallel round instead of three.
    val (agents, models, commands) = coroutineScope {
      val agentsTask = async { attempt { client.agents(directory) } }
      val modelsTask = async { attempt { client.modelCatalog(directory) } }
      val commandsTask = async { attempt { client.commands(directory) } }
      Triple(agentsTask.await(), modelsTask.await(), commandsTask.await())
    }
    applyChoiceResults(token, revision, directory, agents, models, commands)
    if (agents.isSuccess && models.isSuccess && commands.isSuccess) {
      choicesCache.put(serverId, CachedChoices(directory,
        agents.getOrDefault(emptyList()), models.getOrDefault(emptyList()), commands.getOrDefault(emptyList()),
        System.currentTimeMillis(), complete = true))
    }
  }

  private fun applyChoiceResults(
    token: Int,
    revision: Long,
    directory: String,
    agents: Result<List<AgentChoice>>,
    models: Result<List<ModelInfo>>,
    commands: Result<List<CommandChoice>>
  ) {
    val expectedDirectory = mutable.value.executionDirectory ?: mutable.value.homeDirectory
    if (token != generation || revision != selectionRevision || (expectedDirectory != null && expectedDirectory != directory)) return
    updateState { current ->
      val agentList = agents.getOrDefault(current.agents)
      val catalog = models.getOrDefault(current.modelCatalog)
      val serverId = current.serverId
      val variants = serverId?.let(store::modelVariants) ?: current.modelVariants
      // A blank page carries the phone's defaults (or the reader's override). Validate that
      // selection against the catalog before the first send: a removed preset/model must fall
      // back to the server default rather than fail the prompt with an unknown id.
      val resolved = if (current.sessionId == null) {
        resolveDraftDefaults(current.agent, current.model, agentList, catalog, variants)
      } else null
      val model = if (resolved != null) resolved.model
        else if (serverId == null) current.model
        else current.model?.let { normalizeModelVariant(serverId, it, catalog) }
      val agent = if (resolved != null) resolved.agent else current.agent
      current.copy(agents = agentList, models = catalog.map { it.choice }, modelCatalog = catalog,
        agent = agent, agentChanged = if (resolved != null) agent != null else current.agentChanged,
        model = model, modelChanged = if (resolved != null) model != null
          else current.modelChanged || model?.variant != current.model?.variant,
        modelVariants = variants, commands = commands.getOrDefault(current.commands),
        resources = current.resources + mapOf("agents" to agents.resourceStatus(), "models" to models.resourceStatus(), "commands" to commands.resourceStatus()))
    }
  }

  /** Resolve a notification/child target independently of the current catalog page. */
  fun resolveSession(id: String, onResolved: (List<String>) -> Unit): Job = act("open:$id") { op ->
    val known = op.snapshot.sessions.associateBy { it.id }.toMutableMap()
    val seen = linkedSetOf<String>(); val lineage = mutableListOf<String>()
    var next: String? = id
    while (next != null && seen.add(next) && lineage.size < 32) {
      val target = next
      val session = known[target] ?: findSession(op.client, target)?.let { scoped(op.serverId, it) }
      if (session == null) {
        check(lineage.isNotEmpty()) { "未找到这个会话，请刷新后重试" }
        break
      }
      known[target] = session; lineage += target
      next = session.parentId
    }
    if (!op.isCurrent(this)) return@act
    store.rememberParents(op.serverId, known.values.toList())
    op.commit { withSummary(it.copy(sessions = known.values.sortedWith(sessionActivityOrder), knownParents = store.taskParents(op.serverId))) }
    onResolved(lineage.asReversed())
  }
  /**
   * Opens the live output viewer for one background shell. The server's output route pages by absolute
   * byte cursor; start at the tail so a long-running command does not download its whole history.
   */
  fun openShellOutput(job: ShellJob) {
    closeShellOutput()
    val server = state.value.serverId ?: return
    val client = api ?: return
    val token = generation
    val pageSize = HarnessClient.SHELL_OUTPUT_PAGE
    val outputRevision = ++shellOutputRevision
    updateState { current ->
      if (current.serverId != server) current
      else current.copy(shellOutput = ShellOutputView(job.id, job.command, job.directory, loading = true))
    }
    if (client.capabilities().jobOutputStream) {
      shellOutputJob = scope.launch {
        val text = StringBuilder()
        try {
          val ok = client.followJobOutput(job.sessionId, job.id) { chunk, running, finished ->
            if (chunk.isNotEmpty()) {
              text.append(chunk)
              if (text.length > 64 * 1024) text.delete(0, text.length - 64 * 1024)
            }
            updateState { current ->
              if (token != generation || outputRevision != shellOutputRevision || current.serverId != server || current.shellOutput?.shellId != job.id) current
              else current.copy(shellOutput = current.shellOutput?.copy(
                text = text.toString(), loading = false,
                omitted = current.shellOutput?.omitted == true || text.length >= pageSize,
                snapshot = ShellSnapshot(job.id, job.command, job.directory,
                  status = if (running) "running" else "exited", started = job.started)))
            }
          }
          if (!ok) updateState { current ->
            if (token != generation || outputRevision != shellOutputRevision || current.serverId != server || current.shellOutput?.shellId != job.id) current
            else current.copy(shellOutput = current.shellOutput?.copy(loading = false, error = "此主机没有后台任务输出"))
          }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
          updateState { current ->
            if (token != generation || outputRevision != shellOutputRevision || current.serverId != server || current.shellOutput?.shellId != job.id) current
            else current.copy(shellOutput = current.shellOutput?.copy(loading = false, error = userFacingError(error, "读取后台输出失败")))
          }
        } finally {
          updateState { current ->
            if (token != generation || outputRevision != shellOutputRevision || current.serverId != server || current.shellOutput?.shellId != job.id) current
            else current.copy(shellOutput = current.shellOutput?.copy(loading = false))
          }
        }
      }
      return
    }
    shellOutputJob = scope.launch {
      var cursor: Long? = null
      var initialized = false
      var truncatedAtStart = false
      var missing = false
      var finalReads = 0
      while (isActive && token == generation && state.value.serverId == server &&
        state.value.shellOutput?.shellId == job.id) {
        val snapshot = try { client.shellInfo(job.id, job.directory) } catch (cancel: CancellationException) { throw cancel }
          catch (error: Exception) {
            Diagnostics.warn("ShellOutput", "读取后台命令状态失败", error); null
          }
        if (!initialized) {
          val head = try { client.shellOutput(job.id, job.directory, Long.MAX_VALUE, 1) } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
              Diagnostics.warn("ShellOutput", "读取后台输出失败", error); null
            }
          if (head == null) {
            updateState { current ->
              if (token != generation || outputRevision != shellOutputRevision || current.serverId != server || current.shellOutput?.shellId != job.id) current
              else current.copy(shellOutput = current.shellOutput?.copy(loading = false,
                error = "该服务器不支持查看后台命令输出", snapshot = snapshot))
            }
            return@launch
          }
          truncatedAtStart = head.size > pageSize
          cursor = maxOf(0L, head.size - pageSize)
          initialized = true
        }
        val page = try { client.shellOutput(job.id, job.directory, cursor, pageSize) } catch (cancel: CancellationException) { throw cancel }
          catch (error: Exception) {
            Diagnostics.warn("ShellOutput", "读取后台输出失败", error); null
          }
        if (page == null) {
          missing = true
          updateState { current ->
            if (token != generation || outputRevision != shellOutputRevision || current.serverId != server || current.shellOutput?.shellId != job.id) current
            else current.copy(shellOutput = current.shellOutput?.copy(loading = false,
              error = "后台命令输出已不可用", snapshot = snapshot))
          }
          break
        }
        val previous = mutable.value.shellOutput?.takeIf { it.shellId == job.id } ?: break
        val appended = (previous.text + page.output).takeLast(pageSize)
        cursor = page.cursor
        val caughtUp = page.cursor >= page.size
        updateState { current ->
          if (token != generation || outputRevision != shellOutputRevision || current.serverId != server || current.shellOutput?.shellId != job.id) current
          else current.copy(shellOutput = ShellOutputView(
            shellId = job.id,
            command = previous.command,
            directory = job.directory,
            snapshot = snapshot ?: previous.snapshot,
            text = appended,
            cursor = page.cursor,
            size = page.size,
            omitted = previous.omitted || truncatedAtStart || page.truncated,
            loading = false,
            error = null
          ))
        }
        val running = snapshot?.running ?: previous.snapshot?.running ?: state.value.shells.containsKey(job.id)
        if (!running && caughtUp) {
          // Poll once more after exit so the final file flush is not missed, then stop.
          if (++finalReads >= 2) break
        }
        delay(if (caughtUp && running) 1_000L else if (!caughtUp) 0L else 250L)
      }
      if (missing) return@launch
    }
  }

  private var shellOutputRevision = 0L

  fun closeShellOutput() {
    shellOutputRevision++
    shellOutputJob?.cancel()
    shellOutputJob = null
    if (state.value.shellOutput != null) updateState { it.copy(shellOutput = null) }
  }

  /** Terminate a running background command, like the official activity row's stop action. */
  fun stopShell(job: ShellJob) = scope.launch {
    val client = api ?: return@launch
    val server = state.value.serverId ?: return@launch
    val token = generation
    if (!client.capabilities().stopShell) {
      updateState { it.copy(error = "此主机不支持停止后台命令") }
      return@launch
    }
    val key = operationKey(server, state.value.sessionId, state.value.projectId, "shell-stop:${job.id}")
    fun stillCurrent() = token == generation && state.value.serverId == server && api === client
    updateState { it.copy(pendingOperations = it.pendingOperations + key) }
    try {
      attempt { client.removeShell(job.id, job.directory) }.onSuccess {
        if (stillCurrent()) updateState { withSummary(it.copy(shells = it.shells - job.id)) }
      }.onFailure { error ->
        Diagnostics.warn("ShellOutput", "停止后台命令失败", error)
        if (!stillCurrent()) return@onFailure
        val reason = userFacingError(error, "停止后台命令失败")
        updateState { current ->
          if (current.shellOutput?.shellId == job.id) current.copy(shellOutput = current.shellOutput.copy(error = reason))
          else current.copy(error = reason)
        }
      }
    } finally {
      updateState { it.copy(pendingOperations = it.pendingOperations - key) }
    }
  }

  /** Null when the server no longer has the session. */
  private suspend fun findSession(client: HarnessClient, id: String): Session? = try { client.session(id).takeIf { it.id == id } }
    catch (error: ApiException) { if (error.status == 404) null else throw error }

  /** Captures the current transcript before a navigation replaces it; never authoritative. */
  private fun rememberCurrentSessionView() {
    val current = state.value
    conversationPool.record(current)
    val server = current.serverId ?: return
    val session = current.session ?: return
    if (current.messages.isEmpty() && current.resource("messages").state == ResourceState.LOADING) return
    sessionViews.put(server, session.id, CachedSessionView(
      messages = current.messages,
      olderCursor = current.messagesCursor,
      loadedOlder = current.loadedOlderMessages,
      sessionUpdated = session.updated,
      cacheComplete = current.cacheComplete,
      at = System.currentTimeMillis()
    ))
  }

  fun selectSession(id: String) {
    val session = mutable.value.sessions.firstOrNull { it.id == id } ?: return
    // A real conversation ends the blank-draft intent: subsequent loads restore sessions normally.
    clearDraftIntent()
    selectionRevision += 1
    openTrace.begin(selectionRevision, "打开会话")
    val project = resolveSessionProject(session, mutable.value.projects)
    val serverId = state.value.serverId ?: return
    returnQueuedToDrafts { it.serverId == serverId && it.sessionId == id }
    rememberCurrentSessionView()
    val outgoing = mutable.value
    outgoing.sessionId?.takeIf { it != id && outgoing.provisionalMessageIds.isEmpty() }?.let { transcriptMemory.put(serverId, it, outgoing.messages.persistable()) }
    val configuration = store.configuration(serverId, id)
    // 同一会话被重新选中（通知/深链）时保留输入框里的最新文字，避免回退尚未落盘（约 350ms 防抖）的输入。
    // 草稿与文件引用要经 Keystore 解密，放到 [restoreComposerInput] 异步填回：点开会话的第一帧不等它。
    val reselected = mutable.value.sessionId == id
    val slot = if (reselected) null else conversationPool.get(serverId, id)
    val draft = if (reselected) liveDraft(mutable.value) else ""
    val references = if (reselected) mutable.value.references else emptyList()
    visibleConversation = null
    val offline = mutable.value.cached && !mutable.value.connected
    val cached = sessionViews.get(serverId, id)
    // A running session may have accumulated events while it was not on screen; only a non-running
    // cache without a newer `time.updated` is safe to show without verification.
    val fresh = sessionViews.fresh(cached, session) && state.value.tasks[id]?.active != true
    val messages = cached?.messages ?: withPending(serverId, id, emptyList())
    closeShellOutput()
    familyDiscovery = null
    updateState { withSummary(it.copy(projectId = project?.id, sessionId = id, messages = messages,
      agent = configuration.agent ?: session.agent, model = configuration.model ?: session.model,
      agentChanged = configuration.agentChanged, modelChanged = configuration.modelChanged,
      draft = draft, references = references, loadedOlderMessages = cached?.loadedOlder ?: false,
      messagesCursor = cached?.olderCursor, cacheComplete = cached?.cacheComplete ?: true,
      resources = mapOf("messages" to ResourceStatus(if (cached != null) ResourceState.READY else ResourceState.LOADING)),
      provisionalMessageIds = emptySet(), transcriptLoadingSince = if (offline || cached != null) 0 else System.currentTimeMillis(),
      changes = if (reselected) it.changes else slot?.changes.orEmpty(),
      goal = if (reselected) it.goal else slot?.goal,
      todos = if (reselected) it.todos else slot?.todos.orEmpty(), goalLoading = false,
      schedules = if (reselected) it.schedules else slot?.schedules.orEmpty(), schedulesLoading = false,
      files = emptyList(),
      searchResults = emptyList(), fileText = null, fileBinary = false, filePreview = null, filePreviewMime = null)) }
    store.rememberLocation(project?.id, id)
    transcripts?.protectedSession = serverId to id
    restoreComposerInput(serverId, id, selectionRevision)
    if (offline) {
      if (cached == null) {
        val revision = selectionRevision
        scope.launch {
          val (cachedMessages, olderCursor) = cachedTranscriptWindow(serverId, id)
          val complete = storesTranscripts(serverId)
          if (state.value.serverId == serverId && selectionRevision == revision && cachedMessages.isNotEmpty()) openTrace.mark(revision, ConnectMilestone.CACHE_PAINT)
          if (state.value.serverId == serverId && selectionRevision == revision) updateState { it.copy(messages = cachedMessages, cacheComplete = complete, messagesCursor = olderCursor,
            resources = it.resources + ("messages" to ResourceStatus(if (cachedMessages.isEmpty()) ResourceState.ERROR else ResourceState.STALE, "离线缓存"))) }
        }
      }
    } else {
      val token = generation
      if (cached == null) showCachedTranscript(serverId, id, selectionRevision)
      // Choices and the session transcript are independent. A fresh reused window skips the
      // transcript round trip entirely; a stale one paints immediately and verifies in the
      // background without a loading state.
      val key = "session:$serverId:$id"
      sessionLoadJobs.cancelWhere { it != key && it.startsWith("session:") }
      val job = scope.launch {
        coroutineScope {
          launch { loadChoices(session.directory, token) }
          if (!fresh) loadSession(session, token = token)
        }
      }
      sessionLoadJobs.replace(key, job)
      job.invokeOnCompletion { sessionLoadJobs.remove(key, job) }
    }
  }

  /**
   * 解密并填回 [selectSession] 留空的草稿与文件引用。只填仍为空的字段：解密完成前用户已经输入或添加的
   * 内容以用户为准；期间又切走了会话则丢弃结果。
   */
  private fun restoreComposerInput(serverId: String, sessionId: String, revision: Long) {
    scope.launch {
      val (draft, references) = kotlinx.coroutines.withContext(Dispatchers.IO) { store.draft(serverId, sessionId) to store.references(serverId, sessionId) }
      if (selectionRevision != revision || state.value.serverId != serverId || state.value.sessionId != sessionId) return@launch
      updateState { it.copy(draft = liveDraft(it).ifEmpty { draft }, references = it.references.ifEmpty { references }) }
    }
  }
  private fun scheduleTranscriptRetry(session: Session, token: Int) {
    if (transcriptRetry?.isActive == true) return
    val key = "${state.value.serverId}\u0000${session.id}"
    val attempt = (transcriptRetryCounts[key] ?: 0) + 1
    if (attempt > 2) return
    transcriptRetryCounts[key] = attempt
    transcriptRetry = scope.launch {
      delay(2_000L * attempt)
      if (token == generation && state.value.sessionId == session.id && state.value.connected) {
        loadSession(session, ancillary = false, token = token)
      }
    }
  }

  private suspend fun loadSession(session: Session, ancillary: Boolean = false, token: Int = generation, client: HarnessClient? = api) {
    LoadMetrics.record("transcript")
    val client = client ?: run {
      // 连接还没建立时也必须说明原因：静默返回会让聊天页永远空白，看起来像“会话没有内容”。
      val reason = state.value.error ?: state.value.connection.reason
        ?: "尚未连接到这台设备，无法读取会话内容"
      updateState { it.copy(resources = it.resources + ("messages" to ResourceStatus(ResourceState.ERROR, reason)),
        transcriptLoadingSince = 0) }
      return
    }
    val serverId = state.value.serverId ?: return
    val revision = selectionRevision
    val requestSequence = ++messageSequence
    fun current() = token == generation && revision == selectionRevision && state.value.serverId == serverId && state.value.sessionId == session.id && requestSequence == messageSequence
    // Capture before any HTTP/cache suspension, not after the page returns. Live events replace
    // immutable Message values, letting us protect only the IDs that changed during this read.
    val readStart = state.value.messages.associateBy { it.id }
    fun liveMessages(snapshot: LagoonState) = snapshot.messages.filter { message ->
      message.id !in snapshot.provisionalMessageIds || readStart[message.id]?.let { message != it } == true
    }
    val showingCache = state.value.provisionalMessageIds.isNotEmpty()
    if (current() && (state.value.messages.isEmpty() || showingCache)) updateState { it.copy(resources = it.resources + ("messages" to ResourceStatus(ResourceState.LOADING)),
      transcriptLoadingSince = it.transcriptLoadingSince.takeIf { since -> since > 0 } ?: System.currentTimeMillis()) }
    // Rows painted from the cache are a placeholder: the read starts from a fresh initial window.
    val initialWindow = (state.value.messages.isEmpty() || showingCache) && !state.value.loadedOlderMessages
    var nextCursor: String? = null
    // Wire form of the fetched window (oldest first), kept for the transcript store.
    var records: List<MessageRecord> = emptyList()
    // OpenCode returns `cursor.next` for every non-empty page; a short page is what marks the session start.
    var reachedStart = false
    var opened: TranscriptSync.Window? = null
    val sync = transcriptSync?.takeIf { client.cacheableTranscripts }
    // Taken before the read: a deletion while it is in flight makes its write a no-op.
    val epoch = sync?.epoch(serverId, session.id) ?: 0L
    // The newest page of the projected timeline plus admitted-but-undelivered input, the Host's
    // todo list and the durable goal projection, like the official client's syncs.
    val (result, composer) = coroutineScope {
      val page = async { attempt {
        // A cacheable conversation opens from its verified stored run plus only what is new on the server.
        if (sync != null && initialWindow) sync.open(client, serverId, session).also { opened = it; nextCursor = it.olderCursor }.messages
        else client.messageRecords(session.id).let { nextCursor = it.next; records = it.items; reachedStart = it.next == null; it.items.map(MessageRecord::message) }
      } }
      val context = async { attempt { client.composerContext(session.id) } }
      page.await() to context.await().getOrDefault(ComposerContext())
    }
    val pending = composer.inbox
    if (token != generation) return
    val messages = result.map { first ->
      // Official initial window: keep pulling older pages until the oldest boundary is a whole turn,
      // so the first screen never starts mid-reply. Bounded so a refresh cannot burst unbounded.
      val fetched = if (!initialWindow || opened != null) first else {
        val merged = first.toMutableList()
        var cursor = nextCursor
        var extra = 0
        while (cursor != null && extra < MAX_INITIAL_PAGES && needsOlderTurnRoot(merged) && current()) {
          val older = attempt { client.messageRecords(session.id, cursor, HarnessClient.MESSAGE_PAGE) }.getOrElse { break }
          if (older.items.isEmpty()) { nextCursor = older.next; reachedStart = older.next == null; break }
          merged.addAll(0, older.items.map(MessageRecord::message))
          records = older.items + records
          reachedStart = older.next == null
          nextCursor = older.next
          cursor = older.next
          extra++
        }
        merged
      }
      // Merge by id so a tail refresh keeps older pages the reader already loaded and any
      // streaming/optimistic entry the server page has not caught up with yet.
      // Sample after widening as well: same-ID events may arrive during any older-page request.
      withPending(serverId, session.id, mergeTranscript(liveMessages(state.value).takeIf { current() }.orEmpty(), fetched, pending, readStart))
    }.getOrElse { error ->
      // A transient transport timeout is not a permanent "load failed": keep the last good page and
      // retry quietly. Only a contract/auth failure (or a retry budget exhausted) becomes an error.
      Diagnostics.warn("LagoonController", "会话消息读取失败", error)
      val retryable = error.isRetryableTransportFailure()
      val fallback = cachedTranscript(serverId, session.id)
      if (current()) updateState {
        val status = if (retryable) ResourceStatus(ResourceState.STALE, "连接中断，正在后台重试")
          else ResourceStatus(if (fallback.isEmpty()) ResourceState.ERROR else ResourceState.STALE, userFacingError(error))
        it.copy(cached = fallback.isNotEmpty(), cacheComplete = false, provisionalMessageIds = emptySet(),
          transcriptLoadingSince = if (retryable) System.currentTimeMillis() else 0,
          resources = it.resources + ("messages" to status))
      }
      if (retryable) scheduleTranscriptRetry(session, token)
      if (fallback.isEmpty()) return else fallback
    }
    if (result.isSuccess) {
      if (sync == null) cache.saveMessages(serverId, session.id, messages.persistable(), complete = nextCursor == null)
      else if (opened == null) {
        // Store the actual wire page, not the live-protected UI projection (never across a gap).
        // It may predate an SSE completion; a later authoritative refresh corrects that stored copy.
        val window = records
        val older = nextCursor.takeUnless { reachedStart }
        val start = reachedStart
        scope.launch(storeDispatcher) { attempt { sync.absorb(serverId, session, epoch, window, older, start) } }
      }
      opened?.let {
        Diagnostics.info("TranscriptSync", "打开会话 · ${it.outcome} · 请求 ${it.requests} · 新增 ${it.added}")
        if (it.outcome == TranscriptSync.Outcome.VERIFIED || it.outcome == TranscriptSync.Outcome.CORRECTED) sync?.let { s -> scheduleFullCheck(s, client, serverId, session, token) }
      }
    }
    if (!current()) return
    if (result.isSuccess) {
      trace(token, ConnectMilestone.TRANSCRIPT)
      openTrace.mark(revision, ConnectMilestone.TRANSCRIPT)?.let { Diagnostics.info("ConnectTrace", "打开会话 · 会话内容 ${it}ms") }
    }
    // 缓存完整性标志解密+解析缓存体，不能放在主线程的状态提交里。
    val cachedComplete = if (result.isSuccess) true else cachedTranscriptComplete(serverId, session.id)
    if (!current()) return
    updateState {
      if (!current()) return@updateState it
      // Reconcile inside the atomic update too: cache reads and a retried state CAS must not
      // commit the earlier snapshot over a later delta, tool completion, or newly loaded page.
      val committed = withPending(serverId, session.id, mergeTranscript(liveMessages(it), messages, pending, readStart))
      val preview = committed.sessionPreview()
      val latestAgent = committed.asReversed().firstNotNullOfOrNull { message -> message.agent } ?: session.agent
      val latestModel = committed.asReversed().firstNotNullOfOrNull { message -> message.model } ?: session.model
      withSummary(it.copy(messages = committed, cached = result.isFailure, messagesCursor = if (it.loadedOlderMessages) it.messagesCursor else nextCursor, cacheComplete = cachedComplete,
      provisionalMessageIds = emptySet(), transcriptLoadingSince = 0,
      // 投影里没有清单键 = 这台 Host 不提供该能力，保留上次读到的值；null = Host 已清空。
      todos = composer.todos ?: it.todos,
      // 目标只被投影“补充”，从不被它清空：清除/完成由 goals/get 与 goalAction 的权威结果决定。
      goal = composer.goal?.let { durable -> durable.copy(activation = it.goal?.takeIf { known -> known.id == durable.id }?.activation) } ?: it.goal,
      previews = it.previews + (session.id to preview),
      agent = if (it.agentChanged) it.agent else latestAgent,
      model = if (it.modelChanged) it.model else latestModel?.let { candidate ->
        if (it.modelCatalog.isEmpty()) candidate else normalizeModelVariant(serverId, candidate, it.modelCatalog)
      },
      modelChanged = it.modelChanged || (!it.modelChanged && latestModel != null && it.modelCatalog.isNotEmpty() &&
        normalizeModelVariant(serverId, latestModel, it.modelCatalog).variant != latestModel.variant),
       resources = it.resources + ("messages" to ResourceStatus(if (result.isSuccess) { if (committed.none { message -> message.isDisplayable }) ResourceState.EMPTY else ResourceState.READY } else ResourceState.STALE))) ) }
    if (!current()) return
    if (result.isSuccess) {
      val committed = state.value.messages
      val committedPreview = committed.sessionPreview()
      kotlinx.coroutines.withContext(Dispatchers.IO) { store.rememberPreview(serverId, session.id, committedPreview) }
      transcriptMemory.put(serverId, session.id, committed.persistable())
      transcriptRetry?.cancel()
      transcriptRetry = null
      transcriptRetryCounts.remove("${state.value.serverId}\u0000${session.id}")
      val snapshot = state.value
      sessionViews.put(serverId, session.id, CachedSessionView(
        messages = snapshot.messages,
        olderCursor = snapshot.messagesCursor,
        loadedOlder = snapshot.loadedOlderMessages,
        sessionUpdated = session.updated,
        cacheComplete = snapshot.cacheComplete,
        at = System.currentTimeMillis()
      ))
    }
    // Opening a session never infers a result from its history: “已完成” exists only in the ledger.
    if (ancillary) loadAncillary(session, client, serverId, token)
  }

  /** Reads descendants and the diff only when the reader opens that panel. */
  fun loadAncillary() {
    val current = state.value
    val session = current.session ?: return
    val client = api ?: return
    val serverId = current.serverId ?: return
    val token = generation
    scope.launch { loadAncillary(session, client, serverId, token) }
  }

  private suspend fun loadAncillary(session: Session, client: HarnessClient, serverId: String, token: Int) {
    if (token != generation || state.value.serverId != serverId || state.value.sessionId != session.id) return
    LoadMetrics.record("ancillary")
    // The descendant tree and diff are independent. The family read is bounded and keeps its
    // frontier across calls, so a wide/deep family is completed over a few opens instead of one
    // unbounded request storm.
    updateState { it.copy(resources = it.resources + listOf("children", "changes").associateWith { ResourceStatus(ResourceState.LOADING) }) }
    val (family, changes) = coroutineScope {
      val changesTask = async { attempt { client.diff(session) } }
      loadFamily(session, client, serverId) to changesTask.await()
    }
    if (token != generation || state.value.serverId != serverId || state.value.sessionId != session.id) return
    updateState { current ->
      val found = family.sessions.map { scoped(serverId, it) }
      val familyStatus = when {
        family.incompleteRoots.isNotEmpty() -> ResourceStatus(ResourceState.LOADING)
        found.isEmpty() -> ResourceStatus(ResourceState.EMPTY)
        else -> ResourceStatus(ResourceState.READY)
      }
      current.copy(
        changes = changes.getOrDefault(current.changes),
        resources = current.resources + mapOf("children" to familyStatus, "changes" to changes.resourceStatus()),
        sessions = (current.sessions + found).distinctBy { item -> item.id }.sortedWith(sessionActivityOrder)
      )
    }
  }

  /** Bounded descendant discovery for the open conversation, including historical child sessions. */
  private suspend fun loadFamily(session: Session, client: HarnessClient, serverId: String): ChildDiscovery.Result {
    val parents = state.value.parents
    val rootId = BackgroundWork.rootOf(session.id, parents)
    val cachedRoot = state.value.sessions.firstOrNull { it.id == rootId }
    val fetchedRoot = if (cachedRoot == null && rootId != session.id)
      try { findSession(client, rootId)?.let { scoped(serverId, it) } } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
    else null
    val root = cachedRoot ?: fetchedRoot ?: session
    val result = TaskReconciliation.children(client, listOf(root), parents,
      familyDiscovery ?: ChildDiscovery().also { familyDiscovery = it })
    if (result.sessions.isNotEmpty()) {
      store.rememberParents(serverId, result.sessions)
      if (state.value.serverId != serverId) return result
      updateState { current ->
        current.copy(
          knownParents = store.taskParents(serverId),
          sessions = (current.sessions + result.sessions.map { scoped(serverId, it) }).distinctBy { it.id }.sortedWith(sessionActivityOrder)
        )
      }
    }
    return result
  }
  fun chooseAgent(name: String?) {
    updateState { it.copy(agent = name ?: it.messages.asReversed().firstNotNullOfOrNull { message -> message.agent } ?: it.session?.agent, agentChanged = name != null) }; rememberConfiguration()
  }
  private fun selectedModel(model: ModelChoice): ModelChoice {
    val server = state.value.serverId ?: return model.copy(variant = null)
    val info = state.value.modelCatalog.firstOrNull { it.key == modelKey(model) } ?: return model.copy(variant = null)
    val stored = store.modelVariants(server)[info.key]
    val variant = stored?.takeIf { candidate -> info.variants.any { it.id == candidate } }
    if (stored != null && variant == null) store.rememberModelVariant(server, info.key, null)
    return info.choice.copy(variant = variant)
  }
  fun chooseModel(model: ModelChoice?) {
    val next = model?.let(::selectedModel) ?: state.value.messages.asReversed().firstNotNullOfOrNull { it.model } ?: state.value.session?.model
    updateState { it.copy(model = next, modelChanged = model != null) }; rememberConfiguration()
    val server = state.value.serverId ?: return
    if (model != null) {
      store.rememberRecentModel(server, modelKey(model))
      updateState { it.copy(recentModels = store.recentModels(server)) }
    }
  }
  /** Selects a server-advertised variant for the current model; null means the server default. */
  fun chooseVariant(variant: String?) {
    val current = state.value.model ?: return
    val server = state.value.serverId ?: return
    val info = state.value.modelCatalog.firstOrNull { it.key == modelKey(current) } ?: return
    val selected = variant?.takeIf { id -> info.variants.any { it.id == id } }
    store.rememberModelVariant(server, info.key, selected)
    updateState { it.copy(model = info.choice.copy(variant = selected), modelChanged = true, modelVariants = store.modelVariants(server)) }
    rememberConfiguration()
  }
  /** Phone-local default preset for the blank page; null means "follow the server default". */
  fun setNewSessionAgent(name: String?, serverId: String? = state.value.serverId) {
    val server = serverId ?: return
    val clean = name?.takeIf(String::isNotBlank)
    store.rememberNewSessionDefaults(server, store.newSessionDefaults(server).copy(agent = clean))
    if (server == state.value.serverId) updateState { it.copy(newSessionAgent = clean) }
  }
  /** Phone-local default model; the strength stays in [modelVariants], keyed by the model. */
  fun setNewSessionModel(model: ModelChoice?, serverId: String? = state.value.serverId) {
    val server = serverId ?: return
    val clean = model?.let { candidate ->
      state.value.modelCatalog.firstOrNull { it.key == modelKey(candidate) }?.choice?.copy(variant = null)
        ?: candidate.copy(variant = null)
    }
    store.rememberNewSessionDefaults(server, store.newSessionDefaults(server).copy(model = clean))
    if (server == state.value.serverId) updateState { it.copy(newSessionModel = clean) }
  }
  /** The default strength for one model; null means "follow the model". */
  fun setNewSessionVariant(modelKey: String, variant: String?, serverId: String? = state.value.serverId) {
    val server = serverId ?: return
    val info = state.value.modelCatalog.firstOrNull { it.key == modelKey } ?: return
    val clean = variant?.takeIf { id -> info.variants.any { it.id == id } }
    store.rememberModelVariant(server, modelKey, clean)
    if (server == state.value.serverId) updateState { it.copy(modelVariants = store.modelVariants(server)) }
  }
  /**
   * Loads the agent/model catalog for the Settings page even when no conversation is open.  The
   * blank page normally learns it from loadChoices when a workspace is chosen; Settings can be
   * opened straight from the home list, so it needs its own trigger.
   */
  fun loadDefaultCatalog(force: Boolean = false, serverId: String? = state.value.serverId) {
    val current = state.value
    if (current.server == null) return
    if (!force && current.agents.isNotEmpty() && current.modelCatalog.isNotEmpty()) return
    val directory = current.executionDirectory ?: current.homeDirectory
    val token = generation
    scope.launch { loadChoices(directory.orEmpty(), token, force) }
  }
  /** A switch from the phone's "管理模型" list; it overrides the official default rule for this server. */
  fun setModelVisible(model: ModelInfo, visible: Boolean) {
    val server = state.value.serverId ?: return
    val current = state.value
    // The first edit materializes the current all-visible catalog as an allow-list, so changing one
    // switch does not accidentally hide every other model.
    val baseline = if (current.modelVisibilityConfigured) current.modelOverrides
    else current.modelCatalog.associate { it.key to true }
    val next = baseline + (model.key to visible)
    store.rememberModelOverrides(server, next)
    updateState { it.copy(modelOverrides = next, modelVisibilityConfigured = true) }
  }
  /** Returns to the unconfigured state: every model currently offered by the server is visible. */
  fun resetModelVisibility() {
    val server = state.value.serverId ?: return
    store.clearModelVisibility(server)
    updateState { it.copy(modelOverrides = emptyMap(), modelVisibilityConfigured = false) }
  }
  /** Copies picked phone files into the cache off the main thread; each rejection is reported once. */
  fun addLocalAttachments(uris: List<android.net.Uri>) {
    if (uris.isEmpty() || state.value.pending("send")) return
    val server = state.value.serverId ?: return
    val key = attachmentKey(state.value.sessionId)
    operationScope.launch {
      val errors = mutableListOf<String>()
      for (uri in uris) {
        val pending = state.value.pendingAttachments[key].orEmpty().sumOf { it.size }
        val result = kotlinx.coroutines.withContext(Dispatchers.IO) { attempt { attachmentImporter.import(uri, pending) } }
        result.onSuccess { item ->
          if (state.value.serverId == server) updateState { it.copy(pendingAttachments = it.pendingAttachments + (key to (it.pendingAttachments[key].orEmpty() + item))) }
          else attachmentImporter.discard(listOf(item))
        }.onFailure { errors += it.message ?: "无法添加附件" }
      }
      if (errors.isNotEmpty() && state.value.serverId == server) updateState { it.copy(error = errors.distinct().joinToString("\n")) }
    }
  }
  fun removeLocalAttachment(id: String) {
    if (state.value.pending("send")) return
    val key = attachmentKey(state.value.sessionId)
    val removed = state.value.pendingAttachments[key].orEmpty().filter { it.id == id }
    updateState { it.copy(pendingAttachments = it.pendingAttachments + (key to it.pendingAttachments[key].orEmpty().filterNot { item -> item.id == id })) }
    operationScope.launch(Dispatchers.IO) { attachmentImporter.discard(removed) }
  }
  private fun rememberConfiguration() {
    val current = state.value
    val server = current.serverId ?: return
    val session = current.sessionId ?: return
    store.rememberConfiguration(server, session, SessionConfiguration(current.agent, current.model, current.agentChanged, current.modelChanged))
  }
  /**
   * Sets the composer text from the app side (queued prompt returned, cleared after a send): the
   * composer adopts it through [LagoonState.draft] / [LagoonState.draftRevision].
   */
  fun updateDraft(text: String, expectedServer: String? = state.value.serverId, expectedSession: String? = state.value.sessionId) {
    val current = state.value
    if (current.serverId != expectedServer || current.sessionId != expectedSession) return
    val server = current.serverId ?: return
    // The blank page has no session yet: keep its text in state only. The composer owns the live
    // text, and its stable saveable key carries it across a server switch and rotation; there is no
    // session id to persist it under (a synthetic id would leak into draftSessionIds()).
    val session = current.sessionId
    typedDraft = null
    updateState {
      val draftSessions = if (session == null) it.draftSessions
        else if (text.isBlank()) it.draftSessions - session else it.draftSessions + session
      it.copy(draft = text, draftRevision = it.draftRevision + 1, draftSessions = draftSessions)
    }
    if (session != null) persistDraft(server, session, text)
  }
  /**
   * One keystroke in the composer. The text is kept here and saved after a short pause; the state
   * flow is only touched when the session gains or loses a draft (the home list marks those).
   */
  fun typeDraft(text: String, expectedServer: String?, expectedSession: String?) {
    val current = state.value
    if (current.serverId != expectedServer || current.sessionId != expectedSession) return
    val server = current.serverId ?: return
    val session = current.sessionId
    typedDraft = TypedDraft(server, session, text)
    if (session != null && (session in current.draftSessions) == text.isBlank())
      updateState { it.copy(draftSessions = if (text.isBlank()) it.draftSessions - session else it.draftSessions + session) }
    if (session != null) persistDraft(server, session, text)
  }
  /** The composer text of [server]/[session] as typed, falling back to the last app-side [LagoonState.draft]. */
  fun draftFor(server: String?, session: String?, fallback: String): String =
    typedDraft?.takeIf { it.server == server && it.session == session }?.text ?: fallback
  private fun liveDraft(current: LagoonState = state.value) = draftFor(current.serverId, current.sessionId, current.draft)
  private fun persistDraft(server: String, session: String, text: String) {
    val key = "$server:$session"
    draftWrites.remove(key)?.cancel()
    draftWrites[key] = operationScope.launch(Dispatchers.IO) {
      delay(if (text.isEmpty()) 0 else 350)
      attempt { store.rememberDraft(server, session, text) }.onFailure { Diagnostics.warn("Draft", "草稿暂未保存") }
    }
  }
  fun addReference(path: String, mime: String = referenceMime(path)) {
    // 发送中的请求已捕获引用快照；此处不再静默忽略，否则文件面板会“点了没反应”。
    updateState { it.copy(references = (it.references + FileReference(path, mime)).distinctBy { ref -> ref.path }) }; rememberReferences()
  }
  fun removeReference(path: String) { updateState { it.copy(references = it.references.filterNot { ref -> ref.path == path }) }; rememberReferences() }
  private fun rememberReferences() {
    val current = state.value; val server = current.serverId ?: return; val session = current.sessionId ?: return
    operationScope.launch { referenceMutex.withLock { kotlinx.coroutines.withContext(Dispatchers.IO) { attempt { store.rememberReferences(server, session, current.references) } } } }
  }
  /** Home list filter only; choosing a project also makes it the target for new sessions. */
  fun setScope(projectId: String?) {
    val server = state.value.serverId ?: return
    val scope = projectId?.takeIf { id -> state.value.projects.any { it.id == id } }
    store.rememberScope(server, scope)
    updateState { it.copy(scopeProjectId = scope, scopeWorkspaceKey = null) }
    if (scope != null && scope != state.value.projectId) selectProject(scope)
  }

  /** Group home filter by merged workspace name; selecting a harness stays a separate action. */
  fun setWorkspaceScope(key: String?) {
    updateState { it.copy(scopeWorkspaceKey = key, scopeProjectId = null) }
  }

  // ---- Device-local projects ----
  /** Resolve [directory] on the server and register the canonical project on this device. */
  fun addProject(directory: String): Job = act("project-add", directory) { op ->
    val project = op.client.projectAt(directory) ?: error("服务器没有解析出这个目录对应的项目")
    val existing = state.value.projects.firstOrNull { normalizedDirectory(it.directory) == normalizedDirectory(project.directory) }
    val resolved = (existing ?: project).copy(sandboxes = project.sandboxes.ifEmpty { existing?.sandboxes.orEmpty() }, serverId = op.serverId)
    val projects = store.addLocalProject(op.serverId, resolved)
    op.commit { withSummary(it.copy(projects = projects, projectId = resolved.id,
      browsePath = null, browseEntries = emptyList(), browseSearch = emptyList(),
      resources = it.resources + ("browse" to ResourceStatus(ResourceState.READY)))) }
    store.rememberLocation(resolved.id, null)
    loadChoices(resolved.directory, generation)
  }
  fun removeProject(projectId: String) {
    val server = state.value.serverId ?: return
    val projects = store.removeLocalProject(server, projectId)
    updateState { withSummary(it.copy(projects = projects,
      projectId = it.projectId?.takeIf { id -> projects.any { p -> p.id == id } } ?: projects.firstOrNull()?.id,
      scopeProjectId = it.scopeProjectId?.takeIf { id -> projects.any { p -> p.id == id } })) }
  }

  // ---- Remote directory browser (used to add a project) ----
  fun openDirectoryBrowser(): Job = operationScope.launch {
    val op = attempt { beginOperation(state.value.homeDirectory) }.getOrElse { error ->
      updateState { it.copy(error = userFacingError(error, "无法打开目录"), resources = it.resources + ("browse" to ResourceStatus(ResourceState.ERROR, userFacingError(error)))) }
      return@launch
    }
    updateState { it.copy(browsePath = "", browseEntries = emptyList(), browseSearch = emptyList(), browseShowHidden = false, resources = it.resources + ("browse" to ResourceStatus(ResourceState.LOADING))) }
    val root = attempt { op.client.projectAt("") }.getOrNull()?.directory?.takeIf(String::isNotBlank)
      ?: state.value.homeDirectory ?: "/"
    loadBrowse(op, root)
  }
  fun browseDirectory(path: String): Job = operationScope.launch {
    val op = attempt { beginOperation(path) }.getOrElse { error ->
      updateState { it.copy(resources = it.resources + ("browse" to ResourceStatus(ResourceState.ERROR, userFacingError(error)))) }
      return@launch
    }
    updateState { it.copy(resources = it.resources + ("browse" to ResourceStatus(ResourceState.LOADING)), browseSearch = emptyList()) }
    loadBrowse(op, path)
  }
  private suspend fun loadBrowse(op: OperationContext, path: String) {
    val entries = attempt { op.client.files(path, ".", op.snapshot.session) }.getOrElse { error ->
      op.commit { it.copy(resources = it.resources + ("browse" to ResourceStatus(ResourceState.ERROR, userFacingError(error)))) }
      return
    }
    op.commit { it.copy(browsePath = path,
      browseEntries = entries.distinctBy { it.path }.sortedWith(compareBy({ it.type != "directory" }, { it.path.lowercase() })), browseSearch = emptyList(),
      resources = it.resources + ("browse" to ResourceStatus(if (entries.isEmpty()) ResourceState.EMPTY else ResourceState.READY))) }
  }
  fun searchDirectories(query: String): Job {
    val request = ++searchSequence
    val text = query.trim()
    if (text.length < 2) {
      updateState { it.copy(browseSearch = emptyList(), resources = it.resources + ("browse-search" to ResourceStatus(ResourceState.EMPTY))) }
      return operationScope.launch { }
    }
    val path = state.value.browsePath ?: return operationScope.launch { }
    updateState { it.copy(browseSearch = emptyList(), resources = it.resources + ("browse-search" to ResourceStatus(ResourceState.LOADING))) }
    return operationScope.launch {
      val op = attempt { beginOperation(path) }.getOrElse { error ->
        if (request == searchSequence) {
          updateState { it.copy(resources = it.resources + ("browse-search" to ResourceStatus(ResourceState.ERROR, userFacingError(error)))) }
        }
        return@launch
      }
      delay(250)
      if (request != searchSequence) return@launch
      val results = attempt { op.client.searchFiles(path, text, op.snapshot.session) }.getOrElse { error ->
        if (request == searchSequence) op.commit { it.copy(resources = it.resources + ("browse-search" to ResourceStatus(ResourceState.ERROR, userFacingError(error)))) }
        return@launch
      }
      if (request == searchSequence) op.commit { it.copy(browseSearch = results.distinct(), resources = it.resources + ("browse-search" to ResourceStatus(if (results.isEmpty()) ResourceState.EMPTY else ResourceState.READY))) }
    }
  }
  fun closeDirectoryBrowser() {
    updateState { it.copy(browsePath = null, browseEntries = emptyList(), browseSearch = emptyList()) }
  }
  /** 切换「显示隐藏目录」；仅影响本机列表投影，不重新请求服务器。 */
  fun setBrowseShowHidden(show: Boolean) {
    updateState { it.copy(browseShowHidden = show) }
  }
  /**
   * Opens the blank "新会话" page on [serverId], optionally on [projectId].
   *
   * [keepComposer] is true when the reader switches server from the blank page itself: the text on
   * screen belongs to the draft, not to one server, so it must survive the switch. Opening a new
   * draft from the home page starts empty, like the previous scope-driven draft.
   *
   * While the draft page is open the controller remembers this intent: [connect] and [loadAll] then
   * skip restoring the server's last session, which would otherwise navigate straight away from the
   * draft into that conversation.
   */
  fun enterDraft(serverId: String, projectId: String? = null, keepComposer: Boolean = false) {
    if (store.profiles().none { it.id == serverId && it.effectiveEnabled }) return
    val current = state.value
    val keptText = if (keepComposer) liveDraft(current) else ""
    draftServerId = serverId
    draftProjectId = projectId
    draftKeepsComposer = keepComposer
    preservedDraftText = keptText
    typedDraft = null
    if (current.serverId != serverId) {
      // The overview snapshot carries the device-local project list; fall back to the store for an
      // enabled profile whose monitor has not published a read yet.
      val target = current.preferredDraftProject(serverId, projectId)
        ?: current.serverWorkspaceCatalogOn(serverId).firstOrNull()?.copy(serverId = serverId)
        ?: store.localProjects(serverId).firstOrNull()?.copy(serverId = serverId)
      draftProjectId = target?.id
      connect(serverId, target?.id, preserveHome = true)
      return
    }
    // Re-tapping the current server (or switching workspace) keeps what is selected; a new draft
    // opened from the home page uses the scope's target.
    val target = if (keepComposer) current.project
      else current.preferredDraftProject(serverId, projectId)
        ?: current.serverWorkspaceCatalogOn(serverId).firstOrNull()?.copy(serverId = serverId)
    draftProjectId = target?.id
    applyDraftProject(current.copy(draft = keptText), target, fresh = !keepComposer || current.sessionId != null)
  }

  /** The reader left the blank page without sending: later loads may restore sessions again. */
  fun endDraft() = clearDraftIntent()

  private fun clearDraftIntent() {
    draftServerId = null
    draftProjectId = null
    draftKeepsComposer = false
    preservedDraftText = ""
    pendingDraft = null
  }

  /**
   * Applies a workspace to the open draft without creating a session. The prompt typed on the blank
   * page survives a server switch; leaving a conversation for the draft starts it empty.
   */
  private fun applyDraftProject(current: LagoonState, project: Project?, fresh: Boolean) {
    draftProjectId = project?.id
    selectionRevision += 1
    visibleConversation = null
    val text = if (fresh) "" else current.draft
    val references = if (fresh) emptyList() else current.references
    val defaults = if (fresh) resolveDraftDefaults(current.newSessionAgent, current.newSessionModel,
      current.agents, current.modelCatalog, current.modelVariants) else null
    val agent = if (fresh) defaults?.agent else current.agent
    val model = if (fresh) defaults?.model else current.model
    updateState {
      withSummary(it.copy(projectId = project?.id, sessionId = null, messages = emptyList(),
        agent = agent, model = model,
        agentChanged = if (fresh) agent != null else it.agentChanged,
        modelChanged = if (fresh) model != null else it.modelChanged,
        draft = text, draftRevision = it.draftRevision + (if (text != it.draft) 1 else 0),
        references = references, resources = it.resources - "messages",
        files = emptyList(), searchResults = emptyList(), fileText = null, fileBinary = false,
        filePreview = null, filePreviewMime = null, loadedOlderMessages = false, messagesCursor = null,
        provisionalMessageIds = emptySet(), transcriptLoadingSince = 0, changes = emptyList(),
        goal = null, todos = emptyList(), goalLoading = false, schedules = emptyList(), schedulesLoading = false))
    }
    store.rememberLocation(project?.id, null)
    if (project != null) {
      val token = generation
      scope.launch { loadChoices(project.directory, token) }
    }
  }

  /**
   * Selects a workspace from the draft picker. A workspace known only to the server is registered
   * through the existing server-resolved [addProject], so server data stays authoritative and no
   * catalog is imported behind the reader's back.
   */
  fun chooseWorkspace(project: Project) {
    val unique = normalizedDirectory(project.directory)
    val current = state.value.let { it.copy(draft = liveDraft(it)) }
    val existing = current.projects.firstOrNull { normalizedDirectory(it.directory) == unique }
    // Keep the prompt typed on the blank page: changing workspace is not sending or leaving it.
    if (existing != null) {
      applyDraftProject(current, existing, fresh = false)
      return
    }
    // A DSH workspace in `workspace/follow` is already the canonical project. Register it locally
    // without a `projectAt` round trip (which used to open a second workspace/follow and wait up to
    // 20 seconds before the new-session page could be used).
    val kind = current.server?.kind
    val knownWorkspace = if (kind == ServerKind.DSH || kind == ServerKind.DSH_REMOTE) {
      current.workspaceCatalog.firstOrNull { normalizedDirectory(it.directory) == unique }
    } else null
    if (knownWorkspace != null) {
      val projects = store.addLocalProject(current.serverId ?: return, knownWorkspace.copy(serverId = current.serverId))
      val next = current.copy(projects = projects)
      updateState { it.copy(projects = projects) }
      applyDraftProject(next, knownWorkspace, fresh = false)
      return
    }
    addProject(project.directory)
  }
  fun conversationVisible(server: String, session: String, visible: Boolean) {
    if (visible) visibleConversation = server to session
    else if (visibleConversation == (server to session)) visibleConversation = null
    synchronizeQuestionPresentation()
    acknowledgeVisible()
  }

  private fun synchronizeQuestionPresentation() {
    val selected = state.value.serverId
    val visible = visibleConversation?.takeIf { it.first == selected }
    api?.questionPresentation(visible?.second, foreground && visible != null)
  }
  fun questionFocus(request: QuestionRequest, focused: Boolean) { api?.questionFocus(request.id, focused) }
  fun questionEngage(request: QuestionRequest) { api?.questionEngage(request.id) }
  fun questionTakeTime(request: QuestionRequest) { api?.questionTakeTime(request.id) }

  /**
   * 首页「全部已读」：把当前首页范围内的本机结果账本全部标为已读，并撤下对应的结果通知。
   *
   * 单服务器模式只作用于首页显示的那台服务器；「全部」模式作用于所有已监视服务器。它不写服务端，
   * 也不改变运行中/待处理状态；同一会话正在等待授权或回答时保留其通知。
   */
  fun markAllRead() {
    val current = state.value
    val snapshots = current.homeReadSnapshots()
    val unseen = snapshots.mapValues { (_, snapshot) -> snapshot.notices.filterNot { it.viewed } }
      .filterValues { it.isNotEmpty() }
    if (unseen.isEmpty()) return
    var cleared = 0
    val noticesByServer = mutableMapOf<String, List<SessionNotice>>()
    unseen.forEach { (server, notices) ->
      val sessions = notices.map { it.sessionId }.distinct()
      cleared += sessions.size
      noticesByServer[server] = store.viewAllNotices(server)
      snapshots.getValue(server).resultNotificationSessions(sessions.toSet()).forEach { session ->
        attempt { notifications.cancel(server, session) }
      }
    }
    if (noticesByServer.isEmpty()) return
    noticesByServer.forEach { (server, notices) ->
      val snapshot = snapshots[server] ?: return@forEach
      allSnapshots[server] = snapshot.copy(notices = notices,
        summary = snapshot.copy(notices = notices).displaySummary(current.permissionPolicy))
    }
    updateState { latest ->
      val next = noticesByServer[latest.serverId]?.let { withSummary(latest.copy(notices = it)) } ?: latest
      next.copy(allServerSnapshots = allSnapshots.toMap(),
        message = "已将 $cleared 个会话标记为已读",
        messageTone = FeedbackTone.SUCCESS)
    }
  }
  /**
   * 独立的「服务端已读」：对当前首页范围内、支持 `POST /api/session/{id}/view` 且已连接的服务器，
   * 把本机结果账本里已结束但 `time.viewed < time.idle` 的主会话按精确 idle 写回服务端。它不清本机
   * 未读，也不发通知；因此可以先清本机、之后再同步服务端，或只同步服务端给其他客户端看。
   */
  fun markServerRead() {
    if (serverReadJob?.isActive == true) return
    val current = state.value
    val targets = current.serverReadTargets()
    if (targets.isEmpty()) {
      updateState { it.copy(message = "没有需要同步的服务端已读", messageTone = FeedbackTone.INFO) }
      return
    }
    val clients = targets.keys.associateWith { server ->
      if (server == current.serverId) api else attempt { allOverview.client(server) }.getOrNull()
    }
    updateState { it.copy(serverReadPending = true) }
    serverReadJob = operationScope.launch {
      var succeeded = 0
      var failed = 0
      val acknowledged = mutableMapOf<String, MutableMap<String, Long>>()
      try {
        targets.forEach { (server, sessions) ->
          val client = clients[server]
          if (client == null) {
            failed += sessions.size
            return@forEach
          }
          sessions.chunked(4).forEach { batch ->
            val results = coroutineScope {
              batch.map { session -> async { session to attempt { client.viewSession(session, session.idle) } } }.awaitAll()
            }
            results.forEach { (session, result) ->
              result.fold(
                onSuccess = {
                  succeeded += 1
                  acknowledged.getOrPut(server) { mutableMapOf() }[session.id] = session.idle
                },
                onFailure = { Diagnostics.warn("SessionView", "服务端已读同步失败：$server/${session.id}", it); failed += 1 }
              )
            }
          }
        }
        applyServerRead(acknowledged)
        updateState {
          it.copy(
            message = when {
              failed == 0 -> "服务端已读已同步 $succeeded 个会话"
              succeeded == 0 -> null
              else -> "服务端已读：$succeeded 个成功，$failed 个失败"
            },
            messageTone = if (failed == 0) FeedbackTone.SUCCESS else FeedbackTone.WARNING,
            error = if (succeeded == 0 && failed > 0) "服务端已读同步失败" else it.error
          )
        }
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (error: Exception) {
        Diagnostics.warn("SessionView", "服务端已读同步失败", error)
        updateState { it.copy(error = "服务端已读同步失败") }
      } finally {
        updateState { it.copy(serverReadPending = false) }
      }
    }
  }
  /** Locally reflect acknowledged idle timestamps; a newer idle is never acknowledged by an older request. */
  private fun applyServerRead(acknowledged: Map<String, Map<String, Long>>) {
    if (acknowledged.isEmpty()) return
    val current = state.value
    val sources = current.serverSnapshots()
    val updated = mutableMapOf<String, List<Session>>()
    acknowledged.forEach { (server, idles) ->
      val snapshot = sources[server] ?: return@forEach
      val sessions = snapshot.sessions.map { session ->
        val idle = idles[session.id]
        if (idle != null && session.idle == idle && session.viewed < idle) session.copy(viewed = idle) else session
      }
      updated[server] = sessions
      allSnapshots[server] = snapshot.copy(sessions = sessions)
    }
    if (updated.isEmpty()) return
    updateState { latest ->
      latest.copy(sessions = updated[latest.serverId] ?: latest.sessions,
        allServerSnapshots = allSnapshots.toMap())
    }
  }
  /**
   * Official `MarkSessionNotificationsViewed`: once the conversation is on screen, every unread result of
   * that session is viewed — immediately, not after its transcript loads — and its result notification is
   * withdrawn. On V2 the server's own read marker for that idle transition is synced best-effort.
   */
  private fun acknowledgeVisible() {
    val current = state.value
    val server = current.serverId ?: return
    val session = current.sessionId ?: return
    if (!foreground || visibleConversation != (server to session)) return
    val unseen = current.notices.unseenFor(session)
    if (unseen.isNotEmpty()) {
      val notices = store.viewNotices(server, session, unseen.map { it.id }.toSet())
      updateState { if (it.serverId == server) withSummary(it.copy(notices = notices)) else it }
      attempt { notifications.cancel(server, session) }
    }
    val target = current.session ?: return
    val idle = target.idle
    if (idle > 0 && target.viewed < idle && current.capabilities.sessionView != null) {
      val client = api
      updateState { it.copy(sessions = it.sessions.map { item -> if (item.id == session && item.idle == idle) item.copy(viewed = idle) else item }) }
      operationScope.launch {
        attempt { client?.viewSession(target, idle) }.onFailure { Diagnostics.warn("SessionView", "服务端已读同步失败，保留本地已读记录") }
      }
    }
  }
  private fun <T> Result<List<T>>.resourceStatus(): ResourceStatus = fold(
    { ResourceStatus(if (it.isEmpty()) ResourceState.EMPTY else ResourceState.READY) },
    { ResourceStatus(if (it is ApiException && it.status == 501) ResourceState.UNSUPPORTED else ResourceState.ERROR, it.message) })

  /**
   * Immutable identity of the connection an asynchronous operation started on. A response may only
   * be committed while [isCurrent] still holds, so a request that was in flight when the user
   * switched server/project cannot write its result into the new context (A02).
   */
  private data class OperationContext(val token: Int, val serverId: String, val client: HarnessClient,
    val revision: Long, val snapshot: LagoonState) {
    fun connectionCurrent(c: LagoonController) = token == c.generation && c.state.value.serverId == serverId && c.api === client
    fun isCurrent(c: LagoonController) = connectionCurrent(c) && revision == c.selectionRevision &&
      c.state.value.projectId == snapshot.projectId && c.state.value.sessionId == snapshot.sessionId && c.state.value.executionDirectory == snapshot.executionDirectory
  }
  private fun beginOperation(targetDirectory: String? = null): OperationContext {
    val current = state.value
    check(current.connected && current.server?.effectiveEnabled != false) { "数据尚未同步或连接已停用，请重新连接后操作" }
    check(store.profiles().any { it.id == current.serverId && it.effectiveEnabled }) { "连接或其所属接入源/主机已停用" }
    return OperationContext(generation, current.serverId ?: error("请先连接服务器"),
      requireNotNull(api) { "请先连接服务器" }, selectionRevision, current)
  }
  private fun OperationContext.commit(update: (LagoonState) -> LagoonState) {
    if (isCurrent(this@LagoonController)) updateState(update)
  }
  private fun OperationContext.commitConnection(update: (LagoonState) -> LagoonState) {
    if (connectionCurrent(this@LagoonController)) updateState(update)
  }
  /** DSH workspaces are identified by an opaque id; a session created without it is invisible to
   *  clients that enumerate by workspace. Prefer the catalog entry for the exact directory. */
  private fun workspaceIdFor(snapshot: LagoonState, directory: String): String? {
    val kind = snapshot.server?.kind ?: return null
    if (kind != ServerKind.DSH && kind != ServerKind.DSH_REMOTE) return null
    return snapshot.workspaceCatalog.firstOrNull {
      normalizedDirectory(it.directory) == normalizedDirectory(directory)
    }?.id?.takeIf(String::isNotBlank)
  }

  fun createSession(title: String, onCreated: ((Session) -> Unit)? = null) = act("create", state.value.project?.directory) { op ->
    val project = op.snapshot.project ?: error("先选择项目")
    val session = scoped(op.serverId, op.client.createSession(project.directory, title,
      workspaceIdFor(op.snapshot, project.directory)))
    op.commitConnection { it.copy(sessions = (listOf(session) + it.sessions).distinctBy { s -> s.id }) }
    if (op.isCurrent(this)) { selectSession(session.id); onCreated?.invoke(session) }
  }
  /**
   * 新建会话一步流：建会话后直接发送第一条 Prompt，免去"空会话 → 再发一条消息"的往返。
   * 首条消息复用 [send]，命令解析、任务计数与前台服务逻辑不重复实现。
   */
  fun startSession(title: String, prompt: String, agent: String? = null, model: ModelChoice? = null, onStarted: ((Session) -> Unit)? = null): Job {
    val current = state.value
    val directory = current.project?.directory
    if (!current.connected) {
      if (directory.isNullOrBlank()) {
        updateState { it.copy(error = "连上服务器并选择工作区后即可发送") }
        return operationScope.launch { }
      }
      pendingDraft = PendingDraft(current.serverId.orEmpty(), current.projectId, directory, title, prompt, agent, model, onStarted)
      updateState { it.copy(message = "已排队：连接后会自动创建会话并发送", messageTone = FeedbackTone.INFO) }
      return operationScope.launch { }
    }
    if (directory == null) {
      updateState { it.copy(error = "先选择工作区") }
      return operationScope.launch { }
    }
    return startSessionIn(directory, title, prompt, agent, model, onStarted)
  }

  private fun startSessionIn(directory: String, title: String, prompt: String, agent: String?, model: ModelChoice?,
    onStarted: ((Session) -> Unit)?): Job = act("create", directory) { op ->
    // Files picked on the blank draft page move to the new session so a failed first send keeps them.
    val draftFiles = op.snapshot.pendingAttachments[attachmentKey(null)].orEmpty()
    check(prompt.isNotBlank() || draftFiles.isNotEmpty()) { "请输入任务" }
    val session = scoped(op.serverId, op.client.createSession(directory, title, workspaceIdFor(op.snapshot, directory)))
    op.commitConnection { it.copy(sessions = (listOf(session) + it.sessions).distinctBy { s -> s.id }) }
    if (op.isCurrent(this)) {
      store.rememberConfiguration(op.serverId, session.id, SessionConfiguration(agent, model, agent != null, model != null))
      if (draftFiles.isNotEmpty()) updateState { it.copy(pendingAttachments = it.pendingAttachments - attachmentKey(null) + (session.id to draftFiles)) }
      selectSession(session.id)
      updateState { it.copy(agentChanged = agent != null, modelChanged = model != null, draft = prompt, draftRevision = it.draftRevision + 1, previews = it.previews + (session.id to SessionPreview(SessionContent.EMPTY))) }
      onStarted?.invoke(session)
      deliver(prompt, op.serverId, session.id, accepted = null, onFailed = {
        if (prompt.isNotBlank()) {
          draftWrites.remove("${op.serverId}:${session.id}")?.cancel()
          kotlinx.coroutines.withContext(Dispatchers.IO) { attempt { store.rememberDraft(op.serverId, session.id, prompt) } }
        }
      })
    }
  }

  private fun flushPendingDraft() {
    val pending = pendingDraft ?: return
    val current = state.value
    if (!current.connected || current.serverId != pending.serverId) return
    pendingDraft = null
    startSessionIn(pending.directory, pending.title, pending.prompt, pending.agent, pending.model, pending.onStarted)
  }
  /**
   * Official composer submission: the prompt gets a client-minted id and is shown at once; it steers a
   * running turn instead of being refused, and a failed admission removes the optimistic row again.
   * A slash command known to the server runs through `/command`.
   */
  fun send(text: String, expectedServer: String? = state.value.serverId, expectedSession: String? = state.value.sessionId, accepted: (() -> Unit)? = null): Job =
    deliver(text, expectedServer, expectedSession, accepted, onFailed = null)
  /** [send] with [onFailed]: `true` means delivery is unknown and must not be replayed automatically. */
  private fun deliver(text: String, expectedServer: String?, expectedSession: String?, accepted: (() -> Unit)?,
    onFailed: (suspend (deliveryUnknown: Boolean) -> Unit)?): Job {
    if (state.value.serverId != expectedServer || state.value.sessionId != expectedSession) {
      // 静默丢弃会让用户以为已经发出；明确提示并恢复尚未进入请求的内容。
      updateState { it.copy(error = "当前会话已切换，消息未发送，请重试") }
      return operationScope.launch { onFailed?.invoke(false) }
    }
    if (state.value.canQueueSend) {
      queuePrompt(text, accepted)
      return operationScope.launch { }
    }
    var admissionStarted = false
    var admitted = false
    return act("send", onError = { error ->
      // Content must never disappear before admission. Report whether a transport/parse/cancellation
      // failure leaves the outcome unknown; callbacks restore a manual draft, never an automatic replay.
      if (!admitted) onFailed?.invoke(admissionStarted && error !is ApiException)
    }) { op ->
    val attachments = op.snapshot.attachments
    check(text.isNotBlank() || op.snapshot.references.isNotEmpty() || attachments.isNotEmpty()) { "请输入任务" }
    val session = op.snapshot.session ?: error("先打开会话")
    sendMutex.withLock {
      if (!op.isCurrent(this)) {
        onFailed?.invoke(false)
        return@act
      }
      val command = if (text.startsWith('/')) op.snapshot.commands.firstOrNull { text.substringAfter('/').substringBefore(' ') == it.name } else null
      val agent = op.snapshot.agent.takeIf { op.snapshot.agentChanged }
      val model = op.snapshot.model.takeIf { op.snapshot.modelChanged }
      val inline = if (attachments.isEmpty()) emptyList() else kotlinx.coroutines.withContext(Dispatchers.IO) { attachments.map(attachmentImporter::inline) }
      val id = MessageIds.ascending()
      if (command == null && text.isNotBlank()) op.commit { it.copy(messages = it.messages + Message(id, "user", System.currentTimeMillis(),
        listOf(MessagePart("$id:text", "text", text = text)), agent = agent, model = model, type = "user", sourceRequestId = id, sourceKind = "user")) }

      try {
        admissionStarted = true
        if (command != null) op.client.command(session, command.name, text.substringAfter(' ', ""), agent, model, op.snapshot.references, inline)
        else op.client.send(session, text, agent, model, op.snapshot.references, inline, id)
        admitted = true
      } catch (cancel: CancellationException) {
        op.commit { it.copy(messages = it.messages.filterNot { message -> message.id == id }) }
        throw cancel
      } catch (error: Exception) {
        op.commit { it.copy(messages = it.messages.filterNot { message -> message.id == id }) }
        throw error
      }
      // 首条 prompt 已被接受：这个会话不再是服务端草稿。即使随后到达的列表/事件还带旧的
      // blank=true，首页也不应把它继续藏起来（DshApi 的 known 同样会保持非空白）。
      op.commitConnection { current ->
        current.copy(sessions = current.sessions.map { item -> if (item.id == session.id) item.copy(blank = false) else item })
      }
      // Admission starts a separate pending-input claim, not a continuation of an obsolete run.
      workRevision++
      childDiscovery = null
      op.commitConnection { current -> withSummary(current.copy(tasks = current.tasks +
        (session.id to PendingInputs.accepted(session.id, current.tasks[session.id], session.idle)))) }
      if (attachments.isNotEmpty()) {
        updateState { it.copy(pendingAttachments = it.pendingAttachments + (attachmentKey(session.id) to it.pendingAttachments[attachmentKey(session.id)].orEmpty().filterNot { item -> item in attachments })) }
        kotlinx.coroutines.withContext(Dispatchers.IO) { attachmentImporter.discard(attachments) }
      }
      store.rememberConfiguration(op.serverId, session.id, SessionConfiguration(op.snapshot.agent, op.snapshot.model))
      referenceMutex.withLock { kotlinx.coroutines.withContext(Dispatchers.IO) { store.rememberReferences(op.serverId, session.id, emptyList()) } }
      val sentPreview = SessionPreview(SessionContent.CONTENT, text.take(300))
      kotlinx.coroutines.withContext(Dispatchers.IO) { store.rememberPreview(op.serverId, session.id, sentPreview) }
      if (op.isCurrent(this)) {
        if (liveDraft() == text) updateDraft("")
        accepted?.invoke()
      }
      op.commitConnection { current -> withSummary(current.copy(
        previews = current.previews + (session.id to SessionPreview(SessionContent.CONTENT, text.take(300))))) }
      op.commit { it.copy(agentChanged = false, modelChanged = false, references = emptyList()) }
    }
    if (op.isCurrent(this)) rememberCurrentSessionView()
    if (foreground && op.connectionCurrent(this) && op.snapshot.server?.notifications == true) {
      attempt { TaskMonitorService.start(appContext, op.serverId, session.id) }
        .onFailure { Diagnostics.warn("Monitor", "无法启动任务监控服务", it) }
    }
    // The live stream normally projects the turn. If it is down, reconcile in the background instead of
    // holding the send operation (and the composer) on a 20-second follow snapshot timeout.
    if (op.isCurrent(this) && !state.value.streamConnected) {
      scope.launch {
        runCatching { loadSession(session, token = op.token, client = op.client, ancillary = false) }
          .onFailure { error -> Diagnostics.warn("LagoonController", "发送后的会话后台同步失败", error) }
      }
    }
  }
  }
  fun promptChild(text: String, onSent: (() -> Unit)? = null) = withSession("child-prompt") { op, client, session ->
    val parent = session.parentId?.takeIf(String::isNotBlank) ?: throw ApiException(409, "这不是子会话")
    if (!client.promptSubagent(parent, session.id, text.trim())) throw ApiException(501, "此服务器不支持继续子代理")
    if (op.isCurrent(this)) onSent?.invoke()
  }

  fun updateQueued(itemId: String, action: QueueAction, onUpdated: (() -> Unit)? = null) = withSession("queue") { op, client, session ->
    if (!client.updateQueue(session.id, itemId, action)) throw ApiException(501, "此服务器不支持修改排队消息")
    if (op.isCurrent(this)) onUpdated?.invoke()
  }

  fun abort() = withSession("abort") { op, client, session ->
    client.abort(session)
    workRevision++
    op.commitConnection { withSummary(it.copy(tasks = it.tasks + (session.id to TaskState(session.id, TaskPhase.ABORTED, "任务已停止", it.tasks[session.id]?.since ?: System.currentTimeMillis(), System.currentTimeMillis())))) }
  }
  /** Renames [target] (a session id from the home list) or, by default, the open session. */
  fun rename(title: String, target: String? = null, onRenamed: (() -> Unit)? = null) = withSession("rename", target) { op, client, session ->
    check(title.isNotBlank()) { "标题不能为空" }
    client.renameSession(session, title.trim())
    op.commitConnection { it.copy(sessions = it.sessions.map { item -> if (item.id == session.id) item.copy(title = title.trim()) else item }) }
    if (op.isCurrent(this)) onRenamed?.invoke()
  }
  /** 切换会话权限模式；DSH 用 `/permission` 命令实现，其余 harness 不支持时直接报错。 */
  fun setPermission(value: String, target: String? = null, onDone: (() -> Unit)? = null) =
    withSession("permission", target) { op, client, session ->
      check(value.isNotBlank()) { "权限模式不能为空" }
      if (!client.setPermission(session, value)) throw ApiException(501, "该连接不支持切换权限模式")
      op.commitConnection { it.copy(sessions = it.sessions.map { item -> if (item.id == session.id) item.copy(permission = value) else item }) }
      if (op.isCurrent(this)) onDone?.invoke()
    }

  /** 读取该实例可选的权限目录；失败按空列表处理（只影响这一个选择器，不影响会话）。 */
  fun loadPermissionPresets(directory: String, onReady: (List<PermissionPresetChoice>) -> Unit) = scope.launch {
    val client = api
    if (client == null) {
      onReady(emptyList())
      return@launch
    }
    val options = try {
      client.permissionPresets(directory)
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (error: Exception) {
      Diagnostics.warn("Permission", "读取权限目录失败", error)
      emptyList()
    }
    onReady(options)
  }

  fun deleteSession(target: String? = null, onDeleted: (() -> Unit)? = null) = withSession("delete", target) { op, client, session ->
    client.deleteSession(session)
    cache.deleteMessages(op.serverId, session.id)
    transcriptMemory.forget(op.serverId, session.id)
    sessionViews.forget(op.serverId, session.id)
    conversationPool.forget(op.serverId, session.id)
    forgetStoredTranscript(op.serverId, session.id)
    store.forgetSession(op.serverId, session.id)
    if (op.isCurrent(this)) onDeleted?.invoke()
    if (session.id == op.snapshot.sessionId) op.commit { it.copy(sessionId = null, messages = emptyList()) }
    if (session.id in op.snapshot.pinned) store.rememberPinnedSessions(op.serverId, op.snapshot.pinned - session.id)
    op.commitConnection { withSummary(it.copy(sessions = it.sessions.filterNot { s -> s.id == session.id }, tasks = it.tasks - session.id, pinned = it.pinned - session.id)) }
    if (op.connectionCurrent(this)) reload()
  }
  /** Archives (or restores) [target]; archived sessions are hidden from the home list. */
  fun setArchived(target: String, archived: Boolean, onDone: (() -> Unit)? = null) = withSession("archive", target) { op, client, session ->
    client.setArchived(session, archived)
    op.commitConnection { it.copy(sessions = it.sessions.map { item -> if (item.id == session.id) item.copy(archived = archived) else item }) }
    if (op.connectionCurrent(this)) onDone?.invoke()
  }
  fun fork(onForked: ((Session) -> Unit)? = null) = withSession("fork") { op, client, session ->
    val fork = scoped(op.serverId, client.forkSession(session))
    op.commitConnection { it.copy(sessions = (listOf(fork) + it.sessions).distinctBy { s -> s.id }) }
    if (op.isCurrent(this)) { selectSession(fork.id); onForked?.invoke(fork) }
  }
  fun revert(messageId: String, onReverted: (() -> Unit)? = null) = withSession("revert") { op, client, session ->
    client.revert(session, messageId)
    op.commitConnection { current -> current.copy(sessions = current.sessions.map { if (it.id == session.id) it.copy(revertMessageId = messageId) else it }) }
    if (op.isCurrent(this)) { op.commit { it.copy(message = "已撤销，可通过恢复撤销还原") }; onReverted?.invoke() }
  }
  fun unrevert() = withSession("unrevert") { op, client, session ->
    client.unrevert(session)
    op.commitConnection { current -> current.copy(sessions = current.sessions.map { if (it.id == session.id) it.copy(revertMessageId = null) else it }) }
    if (op.isCurrent(this)) op.commit { it.copy(message = "已恢复撤销") }
  }
  fun replyPermission(request: PermissionRequest, reply: String) = act("permission:${request.id}", request.directory) { op ->
    check(request in op.snapshot.permissions) { "权限请求已变化，请刷新" }
    try {
      op.client.replyPermission(request, reply)
    } catch (e: ApiException) {
      if (e.status != 404) throw e
      val session = op.snapshot.sessions.firstOrNull { it.id == request.sessionId }
        ?: Session(request.sessionId, request.directory, "", 0)
      if (op.client.sessionPermissions(session).any { it.id == request.id }) throw e
    }
    if (op.connectionCurrent(this)) permissionResolved(request.sessionId, request.id)
    cancelAttention(request.sessionId)
  }
  /** Notification execution uses its own immutable client. Reconcile both the matching UI state and the overview connection. */
  fun notificationCompleted(serverId: String, permissionId: String? = null) {
    scope.launch {
      if (permissionId != null) allOverview.settled(serverId, permissionId)
      else allOverview.refresh(serverId)
      if (state.value.serverId == serverId) reload()
    }
  }
  fun replyQuestion(request: QuestionRequest, answers: List<List<String>>) = act("question:${request.id}", request.directory) { op ->
    check(op.snapshot.questions.any { it.id == request.id && it.sessionId == request.sessionId && it.questions == request.questions }) { "问题已变化，请刷新" }
    op.client.replyQuestion(request, answers)
    if (op.connectionCurrent(this)) questionResolved(request.sessionId, request.id)
    cancelAttention(request.sessionId)
  }
  fun rejectQuestion(request: QuestionRequest) = act("question:${request.id}", request.directory) { op ->
    check(op.snapshot.questions.any { it.id == request.id && it.sessionId == request.sessionId && it.questions == request.questions }) { "问题已变化，请刷新" }
    op.client.rejectQuestion(request)
    if (op.connectionCurrent(this)) questionResolved(request.sessionId, request.id)
    cancelAttention(request.sessionId)
  }
  fun listFiles(path: String = "."): Job {
    val request = ++fileSequence
    updateState { it.copy(resources = it.resources + ("files" to ResourceStatus(ResourceState.LOADING)), searchResults = emptyList()) }
    ++searchSequence
    return act(surfaceError = false) { op ->
      val directory = op.snapshot.executionDirectory ?: error("先选择项目")
      val files = try { op.client.files(directory, path, op.snapshot.session) } catch (error: Exception) {
        if (request == fileSequence) op.commit { it.copy(resources = it.resources + ("files" to ResourceStatus(ResourceState.ERROR, userFacingError(error)))) }
        throw error
      }
      if (request == fileSequence) op.commit { it.copy(files = files, filePath = path, fileText = null, fileBinary = false,
        filePreview = null, filePreviewMime = null,
        resources = it.resources + ("files" to ResourceStatus(if (files.isEmpty()) ResourceState.EMPTY else ResourceState.READY))) }
    }
  }
  fun readFile(path: String): Job {
    val request = ++fileSequence
    updateState { it.copy(resources = it.resources + ("files" to ResourceStatus(ResourceState.LOADING))) }
    return act(surfaceError = false) { op ->
      val directory = op.snapshot.executionDirectory ?: error("先选择项目")
      val preview = try { op.client.previewFile(directory, path, op.snapshot.session) } catch (error: Exception) {
        if (request == fileSequence) op.commit { it.copy(resources = it.resources + ("files" to ResourceStatus(ResourceState.ERROR, userFacingError(error)))) }
        throw error
      }
      if (preview != null) {
        if (request == fileSequence) op.commit { it.copy(filePath = path, fileText = null, fileBinary = false,
          filePreview = preview.bytes, filePreviewMime = preview.mime,
          resources = it.resources + ("files" to ResourceStatus(ResourceState.READY))) }
        return@act
      }
      val content = try { op.client.fileContent(directory, path, op.snapshot.session) } catch (error: Exception) {
        if (request == fileSequence) op.commit { it.copy(resources = it.resources + ("files" to ResourceStatus(ResourceState.ERROR, userFacingError(error)))) }
        throw error
      }
      if (request == fileSequence) op.commit { it.copy(fileText = content.content, fileBinary = content.type == "binary", filePath = path,
        filePreview = null, filePreviewMime = null,
        resources = it.resources + ("files" to ResourceStatus(ResourceState.READY))) }
    }
  }
  fun searchFiles(query: String): Job {
    val request = ++searchSequence
    if (query.trim().length < 2) {
      updateState { it.copy(searchResults = emptyList(), resources = it.resources + ("search" to ResourceStatus(ResourceState.EMPTY))) }
      return operationScope.launch { }
    }
    updateState { it.copy(searchResults = emptyList(), resources = it.resources + ("search" to ResourceStatus(ResourceState.LOADING))) }
    return act(surfaceError = false) { op ->
      delay(250)
      if (request != searchSequence) return@act
      val directory = op.snapshot.executionDirectory ?: error("先选择项目")
      val results = try { op.client.searchFiles(directory, query.trim(), op.snapshot.session) } catch (error: Exception) {
        if (request == searchSequence) op.commit { it.copy(resources = it.resources + ("search" to ResourceStatus(ResourceState.ERROR, userFacingError(error)))) }
        throw error
      }
      if (request == searchSequence) op.commit { it.copy(searchResults = results,
        resources = it.resources + ("search" to ResourceStatus(if (results.isEmpty()) ResourceState.EMPTY else ResourceState.READY))) }
    }
  }
  private fun withSession(action: String, target: String? = null, block: suspend (OperationContext, HarnessClient, Session) -> Unit) = act(action) { op ->
    val session = (if (target == null) op.snapshot.session else op.snapshot.sessions.firstOrNull { it.id == target }) ?: error("先打开会话")
    block(op, op.client, session)
  }
  /** Actions that render their own inline `HyperInlineNotice` / `ResourceHint` inside their container, so they must not also fire a duplicate global error toast. */
  private fun isInlineScopedAction(action: String): Boolean =
    action in setOf("send", "create", "delete", "rename", "schedules", "goal", "child-prompt", "queue")

  private fun act(action: String = "", directory: String? = null, surfaceError: Boolean = !isInlineScopedAction(action),
    onError: (suspend (Throwable) -> Unit)? = null, block: suspend (OperationContext) -> Unit): Job {
    val captured = attempt { beginOperation(directory) }
    val key = action.takeIf { it.isNotBlank() }?.let { operationKey(state.value.serverId, state.value.sessionId, state.value.projectId, it) }
    if (key != null && key in state.value.pendingOperations) return operationScope.launch {
      onError?.invoke(IllegalStateException("相同操作仍在进行，本次提交未发送到服务器"))
    }
    if (key != null) updateState { it.copy(pendingOperations = it.pendingOperations + key,
      resources = it.resources + ("action:$action" to ResourceStatus(ResourceState.LOADING))) }
    return operationScope.launch {
      val op = captured.getOrElse { error ->
        onError?.invoke(error)
        val text = userFacingError(error)
        updateState {
          it.copy(
            error = if (surfaceError) text else it.error,
            resources = if (action.isNotBlank()) it.resources + ("action:$action" to ResourceStatus(ResourceState.ERROR, text)) else it.resources,
            pendingOperations = it.pendingOperations - setOfNotNull(key)
          )
        }
        return@launch
      }
      try {
        block(op)
        if (key != null && op.isCurrent(this@LagoonController)) updateState {
          it.copy(resources = it.resources + ("action:$action" to ResourceStatus(ResourceState.READY)))
        }
      } catch (error: Exception) {
        if (error is CancellationException) {
          kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { onError?.invoke(error) }
          throw error
        }
        onError?.invoke(error)
        if (op.connectionCurrent(this@LagoonController)) {
          // Read-like actions keep their last data and show a quiet retry, like the transcript. A
          // transient relay drop must not become a global "加载失败" toast.
          val readLike = action.startsWith("open:") || action in setOf("sessions-more", "messages-more", "goal-refresh", "schedules")
          val transient = readLike && error.isRetryableTransportFailure()
          val text = userFacingError(error)
          updateState { current ->
            current.copy(
              error = if (transient || !surfaceError) current.error else text,
              resources = if (key == null || !op.isCurrent(this@LagoonController)) current.resources
                else current.resources + ("action:$action" to ResourceStatus(
                  if (transient) ResourceState.STALE else ResourceState.ERROR, text))
            )
          }
        }
      } finally {
        if (key != null) updateState { it.copy(pendingOperations = it.pendingOperations - key) }
      }
    }
  }
  private inline fun <T> attempt(block: () -> T): Result<T> = try { Result.success(block()) }
    catch (cancel: CancellationException) { throw cancel }
    catch (error: Exception) { Result.failure(error) }
}

/** The independent reads of a control-plane load's first round trip. */
private data class FirstRound(val version: String, val capabilities: ApiCapabilities, val page: ApiPage<Session>,
  val active: Result<Set<String>>, val workspaces: Result<List<Project>?>)

/** A first prompt submitted from the blank page before its target connection is interactive. */
private data class PendingDraft(
  val serverId: String,
  val projectId: String?,
  val directory: String,
  val title: String,
  val prompt: String,
  val agent: String?,
  val model: ModelChoice?,
  val onStarted: ((Session) -> Unit)?
)

/** A prompt held while its server is unreachable (see `LagoonController.queuePrompt`). */
private data class QueuedPrompt(val serverId: String, val sessionId: String, val text: String, val messageId: String, val created: Long) {
  fun row() = Message(messageId, "user", created, listOf(MessagePart("$messageId:text", "text", text = text)), queued = true, type = "user", sourceKind = "user")
}

/** Rows that may be written to the transcript cache: everything except local pending prompts. */
internal fun List<Message>.persistable(): List<Message> = filterNot { it.id.startsWith(PENDING_PREFIX) }

/** Who a saved server is for the transcript store: a profile edited to another address or account is another server. */
internal fun transcriptIdentity(profile: ServerProfile): String = "${profile.kind.wire}|${profile.url.trim().trimEnd('/').lowercase()}|${profile.username}"
