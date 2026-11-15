package com.igng.opencode.lagoon.core

import android.app.NotificationManager
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.igng.opencode.lagoon.core.ssh.SshHostManager
import com.igng.opencode.lagoon.system.TaskNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RemoteHubLifecycleRegressionTest {
  private val hostId1 = "f2b08ff1-afd6-46a6-8dd4-4de1f026f60a"
  private val hostId2 = "b1c2d3e4-f5a6-47b8-89c0-1d2e3f4a5b6c"
  private val hostId3 = "c3d4e5f6-a7b8-49c0-9d1e-2f3a4b5c6d7e"
  private val hostId4 = "d4e5f6a7-b8c9-40d1-8e2f-3a4b5c6d7e8f"

  private val key1 = testPublicKey(1)
  private val key2 = testPublicKey(21)
  private val key3 = testPublicKey(41)
  private val key3Old = testPublicKey(61)
  private val key4 = testPublicKey(81)

  private fun testPublicKey(seed: Int): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + seed).toByte() })

  private fun putField(target: Any, name: String, value: Any?) {
    target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
  }

  private fun <T : Any> allocateUnsafe(clazz: Class<T>): T {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    return clazz.cast(unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, clazz))!!
  }

  private fun seal(text: String): String = "enc:$text"
  private fun unseal(text: String): String = text.removePrefix("enc:")

  private class InMemoryContext : ContextWrapper(null) {
    val prefs = linkedMapOf<String, FakeSharedPreferences>()
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
      prefs.getOrPut(name) { FakeSharedPreferences() }
  }

  private data class ControllerFixture(
    val context: InMemoryContext,
    val controller: LagoonController,
    val store: ServerStore,
    val remoteDevices: AndroidRemoteDeviceStore,
    val offlineCache: OfflineCache,
    val discoveryStore: DeviceDiscoveryStore,
    val groupStore: DeviceGroupStore,
    val connectionGraphStore: ConnectionGraphStore,
    val sshHostStore: SshHostStore,
    val mutable: MutableStateFlow<LagoonState>,
    val allSnapshots: MutableMap<String, ServerOverviewSnapshot>
  )

  private fun newFixture(): ControllerFixture {
    val context = InMemoryContext()
    val remoteDevices = AndroidRemoteDeviceStore(
      context.getSharedPreferences("remote-device-identities", 0),
      ::seal,
      ::unseal
    )
    val store = ServerStore(
      context.getSharedPreferences("servers", 0),
      context.getSharedPreferences("secrets", 0),
      ::seal,
      ::unseal,
      context,
      remoteDevicesOverride = remoteDevices
    )
    val groupStore = DeviceGroupStore(context.getSharedPreferences("device-groups", 0))
    val discoveryStore = DeviceDiscoveryStore(context.getSharedPreferences("device-discovery", 0))
    val sshHostStore = SshHostStore(
      context.getSharedPreferences("ssh-hosts", 0),
      context.getSharedPreferences("ssh-secrets", 0),
      ::seal,
      ::unseal
    )
    val sshHostManager = SshHostManager(sshHostStore)
    val offlineCache = OfflineCache(
      context.getSharedPreferences("offline_cache", 0),
      ::seal,
      ::unseal
    )
    val connectionGraphStore = ConnectionGraphStore(
      context.getSharedPreferences("connection-graph", 0),
      { store.profiles() },
      { groupStore.load().state.groups.map { BindingComposition(it.id, it.name, it.memberIds) } }
    )
    val graphMutable = MutableStateFlow<ConnectionGraph?>(null)
    val mutable = MutableStateFlow(LagoonState())
    val scope = CoroutineScope(Job().apply { cancel() } + Dispatchers.Unconfined)
    val allOverview = AllServersOverview(store, offlineCache, scope, {})
    val allSnapshots = mutableMapOf<String, ServerOverviewSnapshot>()
    val notifications = allocateUnsafe(TaskNotifications::class.java).apply {
      putField(this, "context", context)
      putField(this, "manager", allocateUnsafe(NotificationManager::class.java))
    }

    val controller = allocateUnsafe(LagoonController::class.java).apply {
      putField(this, "appContext", context)
      putField(this, "store", store)
      putField(this, "deviceGroupStore", groupStore)
      putField(this, "connectionGraphStore", connectionGraphStore)
      putField(this, "connectionGraphMutable", graphMutable)
      putField(this, "connectionGraph", graphMutable)
      putField(this, "connectionGraphWritable", true)
      putField(this, "mutable", mutable)
      putField(this, "state", mutable)
      putField(this, "scope", scope)
      putField(this, "allOverview", allOverview)
      putField(this, "allSnapshots", allSnapshots)
      putField(this, "queuedSends", mutableListOf<Any>())
      putField(this, "generation", 1)
      putField(this, "notifications", notifications)
      putField(this, "cache", offlineCache)
      putField(this, "deviceDiscoveryStore", discoveryStore)
      putField(this, "sshHostStore", sshHostStore)
      putField(this, "sshHostManager", sshHostManager)
      putField(this, "revocationDrainMutex", Mutex())
      putField(this, "conversationPoolStorage", ConversationStorePool())
      putField(this, "transcriptMemory", TranscriptMemory())
      putField(this, "sessionViewsStorage", SessionViewCache())
      putField(this, "choicesCacheStorage", ChoicesCache())
      putField(this, "persistedTasks", HashMap<Any, Any>())
      putField(this, "draftWrites", mutableMapOf<String, Job>())
      putField(this, "attentionJobs", mutableMapOf<String, Job>())
      putField(this, "seenEvents", linkedSetOf<String>())
      putField(this, "waitingEventSeqAt", mutableMapOf<String, Long>())
      putField(this, "resolvedPermissions", linkedSetOf<String>())
      putField(this, "connectTrace", ConnectTrace())
      putField(this, "openTrace", ConnectTrace())
      putField(this, "monitorRequested", mutableSetOf<String>())
      putField(this, "transcripts", null)
      putField(this, "transcriptSync", null)
      putField(this, "storeDispatcher", Dispatchers.Unconfined)
    }

    return ControllerFixture(
      context = context,
      controller = controller,
      store = store,
      remoteDevices = remoteDevices,
      offlineCache = offlineCache,
      discoveryStore = discoveryStore,
      groupStore = groupStore,
      connectionGraphStore = connectionGraphStore,
      sshHostStore = sshHostStore,
      mutable = mutable,
      allSnapshots = allSnapshots
    )
  }

  private fun invokeInitializeConnectionGraph(controller: LagoonController) {
    LagoonController::class.java
      .getDeclaredMethod("initializeConnectionGraph")
      .apply { isAccessible = true }
      .invoke(controller)
  }

  private fun tokenPair(refreshExpiresAt: Long, accessExpiresAt: Long = refreshExpiresAt): RemoteTokenPair =
    RemoteTokenPair(
      accessToken = "access-token-valid-123456",
      accessTokenExpiresAt = accessExpiresAt,
      refreshToken = "refresh-token-valid-123456",
      refreshTokenExpiresAt = refreshExpiresAt
    )

  private fun deviceRecord(
    origin: String,
    account: String,
    refreshExpiresAt: Long,
    invalidated: Boolean = false
  ): RemoteDeviceRecord = RemoteDeviceRecord(
    origin = canonicalRemoteOrigin(origin),
    account = account,
    identity = RemoteIdentity.generate(),
    tokens = tokenPair(refreshExpiresAt),
    transition = RemoteDeviceRecord.Transition.NONE,
    transitionAt = 0L,
    invalidated = invalidated
  )

  private class InMemoryDevices : RemoteDeviceStore {
    val values = linkedMapOf<String, RemoteDeviceRecord>()
    override fun load(slot: String): RemoteDeviceRecord? = values[slot]
    override fun save(slot: String, record: RemoteDeviceRecord) { values[slot] = record }
    override fun delete(slot: String) { values.remove(slot) }
    override fun listSlots(): Set<String> = values.keys.toSet()
  }

  private class InMemoryPins : RemotePeerTrustStore {
    val values = linkedMapOf<String, String>()
    private fun key(scope: RemotePeerScope) = "${scope.origin}|${scope.account}|${scope.hostId}"
    override fun pinned(scope: RemotePeerScope): String? = values[key(scope)]
    override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String): String = values.getOrPut(key(scope)) { publicKey }
    override fun remove(scope: RemotePeerScope) { values.remove(key(scope)) }
    override fun removeAllForAccount(origin: String, account: String) {
      val prefix = "$origin|$account|"
      values.keys.removeIf { it.startsWith(prefix) }
    }
  }

  private class InMemoryCatalogs : RemoteCatalogStore {
    val items = linkedMapOf<String, RemoteCatalog>()
    override fun load(): List<RemoteCatalog> = items.values.toList()
    override fun save(catalog: RemoteCatalog) { items[catalog.key] = catalog }
    override fun clear(origin: String, account: String) { items.remove("$origin\u0000$account") }
  }

  @Test fun renewAdoptedPinnedHostsReenrollsPinnedAdoptedOnlyAndRetainsAccountToken() = runBlocking {
    MockWebServer().use { mock ->
      mock.start()
      val origin = canonicalRemoteOrigin(mock.url("/").toString())
      val account = "owner@example.org"

      val pins = InMemoryPins().apply {
        pinIfAbsent(RemotePeerScope(origin, account, hostId1), key1)
        // hostId3 has a stale pin that does not match directory key3 -> CHANGED
        pinIfAbsent(RemotePeerScope(origin, account, hostId3), key3Old)
        // hostId4 is pinned, but excluded from adoptedHostIds
        pinIfAbsent(RemotePeerScope(origin, account, hostId4), key4)
      }
      val catalogs = InMemoryCatalogs().apply {
        save(
          RemoteCatalog(
            origin = origin,
            account = account,
            provider = "password",
            deviceLabel = "Phone",
            hosts = listOf(
              RemoteHostMeta(hostId1, "Pinned Adopted", "linux", true, 1000L, "1.0", "1.0", RemoteSetup.fingerprint(key1), RemoteTrustState.PINNED),
              RemoteHostMeta(hostId2, "Unknown Adopted", "darwin", true, 1000L, "1.0", "1.0", RemoteSetup.fingerprint(key2), RemoteTrustState.UNKNOWN),
              RemoteHostMeta(hostId3, "Changed Pin", "linux", true, 1000L, "1.0", "1.0", RemoteSetup.fingerprint(key3Old), RemoteTrustState.CHANGED),
              RemoteHostMeta(hostId4, "Pinned Unadopted", "linux", true, 1000L, "1.0", "1.0", RemoteSetup.fingerprint(key4), RemoteTrustState.PINNED)
            ),
            updatedAt = 1000L,
            allowCleartext = true,
            signedOut = true,
            adoptedHostIds = setOf(hostId1, hostId2, hostId3),
            userPaused = true
          )
        )
      }
      val devices = InMemoryDevices()

      mock.enqueue(
        MockResponse().setBody(
          JSONObject()
            .put("account", account)
            .put("token", "account-token-123456789")
            .put("expiresAt", 4_000_000_000_000L)
            .toString()
        )
      )
      mock.enqueue(
        MockResponse().setBody(
          """{"items":[
            {"deviceId":"$hostId1","name":"Pinned Adopted","platform":"linux","role":"host","membershipId":"m1","online":true,"identityKey":"$key1"},
            {"deviceId":"$hostId2","name":"Unknown Adopted","platform":"darwin","role":"host","membershipId":"m2","online":true,"identityKey":"$key2"},
            {"deviceId":"$hostId3","name":"Changed Pin","platform":"linux","role":"host","membershipId":"m3","online":true,"identityKey":"$key3"},
            {"deviceId":"$hostId4","name":"Pinned Unadopted","platform":"linux","role":"host","membershipId":"m4","online":true,"identityKey":"$key4"}
          ]}"""
        )
      )
      val registerBody = JSONObject()
        .put("accessToken", "device-access-renewed-123")
        .put("accessTokenExpiresAt", 4_000_000_000_000L)
        .put("refreshToken", "device-refresh-renewed-123")
        .put("refreshTokenExpiresAt", 4_000_000_000_000L)
        .toString()
      mock.enqueue(MockResponse().setBody(registerBody))
      mock.enqueue(MockResponse().setBody(registerBody))

      val manager = RemoteAccountManager(origin, true, "Lagoon · Test", "android", "1.0", devices, pins, catalogs)
      val candidates = manager.loginWithPassword(account, "pw")
      assertEquals(4, candidates.size)

      val renewed = manager.renewAdoptedPinnedHosts()
      assertNotNull(renewed)
      assertEquals(setOf(hostId1), renewed!!.registered.keys)
      assertTrue(renewed.failures.isEmpty())
      assertFalse(renewed.catalog.signedOut)
      assertEquals(false, renewed.catalog.userPaused)
      assertNotNull(devices.load(scopedRemoteDeviceSlot(origin, account, hostId1)))
      assertNull(devices.load(scopedRemoteDeviceSlot(origin, account, hostId2)))
      assertNull(devices.load(scopedRemoteDeviceSlot(origin, account, hostId3)))
      assertNull(devices.load(scopedRemoteDeviceSlot(origin, account, hostId4)))

      // accountToken stays alive after renewAdoptedPinnedHosts so remaining candidates can still be enrolled in the same session.
      val host2 = candidates.first { it.hostId == hostId2 }
      val manualEnroll = manager.enroll(listOf(host2), mapOf(hostId2 to host2.meta.fingerprint!!))
      assertEquals(setOf(hostId2), manualEnroll.registered.keys)
      assertEquals(4, mock.requestCount)
    }
  }

  @Test fun initializeAndSaveCatalogSyncPinnedFingerprintAndSelfHealLegacyPause() {
    val fixture = newFixture()
    val origin = canonicalRemoteOrigin("http://hub.example.invalid:8080/")
    val endpointNoSlash = origin.trimEnd('/')
    val account = "owner@example.org"
    val fp1 = RemoteSetup.fingerprint(key1)
    val fp2 = RemoteSetup.fingerprint(key2)

    val dshProfile = ServerProfile(
      id = "dsh-$hostId1",
      name = "Workstation",
      url = endpointNoSlash,
      username = account,
      allowCleartext = false,
      kind = ServerKind.DSH_REMOTE,
      remoteHostId = hostId1,
      remoteHostFingerprint = null
    )
    val nativeProfile = ServerProfile(
      id = "oc-$hostId1",
      name = "OpenCode",
      url = endpointNoSlash,
      username = account,
      allowCleartext = false,
      kind = ServerKind.OPENCODE,
      remoteHostId = hostId1,
      remoteServiceId = "svc-main",
      remoteHostFingerprint = null
    )
    fixture.store.save(dshProfile, null)
    fixture.store.save(nativeProfile, null)

    val hostMeta = RemoteHostMeta(
      hostId = hostId1,
      name = "Workstation",
      platform = "linux",
      online = true,
      lastSeenAt = 1_000L,
      harnessVersion = "1.0",
      pluginVersion = "1.0",
      fingerprint = fp1,
      trust = RemoteTrustState.PINNED
    )
    val legacyCatalog = RemoteCatalog(
      origin = origin,
      account = account,
      provider = "password",
      deviceLabel = "Phone",
      hosts = listOf(hostMeta),
      updatedAt = 1_000L,
      allowCleartext = true,
      signedOut = false,
      adoptedHostIds = setOf(hostId1),
      userPaused = null
    )
    fixture.store.saveRemoteCatalog(legacyCatalog)

    // Simulate upgraded install whose existing ConnectionGraph got stuck with HUB source enabled = false.
    val initialGraph = MigrationProjection.fromProfiles(fixture.store.profiles())
    val stuckGraph = initialGraph.copy(
      sources = initialGraph.sources.map {
        if (it.type == ConnectionSourceType.HUB) it.copy(enabled = false) else it
      }
    )
    fixture.connectionGraphStore.save(stuckGraph)

    invokeInitializeConnectionGraph(fixture.controller)

    val healedCatalog = fixture.store.remoteCatalogs().single()
    assertEquals(false, healedCatalog.userPaused)
    assertTrue(fixture.controller.connectionGraph.value!!.sources.single().enabled)
    val profilesAfterInit = fixture.store.profiles().associateBy { it.id }
    assertEquals(fp1, profilesAfterInit[dshProfile.id]?.remoteHostFingerprint)
    assertEquals(true, profilesAfterInit[dshProfile.id]?.allowCleartext)
    assertEquals(fp1, profilesAfterInit[nativeProfile.id]?.remoteHostFingerprint)
    assertEquals(true, profilesAfterInit[nativeProfile.id]?.allowCleartext)

    // Saving a new pinned fingerprint & cleartext preference immediately syncs into matching ServerProfiles.
    fixture.controller.saveRemoteCatalog(
      healedCatalog.copy(
        hosts = listOf(hostMeta.copy(fingerprint = fp2, trust = RemoteTrustState.PINNED)),
        allowCleartext = false
      )
    )
    val profilesAfterSave = fixture.store.profiles().associateBy { it.id }
    assertEquals(fp2, profilesAfterSave[dshProfile.id]?.remoteHostFingerprint)
    assertEquals(false, profilesAfterSave[dshProfile.id]?.allowCleartext)
    assertEquals(fp2, profilesAfterSave[nativeProfile.id]?.remoteHostFingerprint)
    assertEquals(false, profilesAfterSave[nativeProfile.id]?.allowCleartext)
  }

  @Test fun deleteRemoteSourceCascadeWipesAccountStoresWithoutAffectingSiblings() {
    val fixture = newFixture()
    val origin = canonicalRemoteOrigin("http://hub.example.invalid:8080/")
    val endpoint = origin.trimEnd('/')
    val acct1 = "acct1@example.org"
    val acct2 = "acct2@example.org"
    val fp1 = RemoteSetup.fingerprint(key1)
    val fp2 = RemoteSetup.fingerprint(key2)

    val hub1Dsh = ServerProfile(
      id = "hub1-dsh",
      name = "Hub1 DSH",
      url = endpoint,
      username = acct1,
      allowCleartext = true,
      kind = ServerKind.DSH_REMOTE,
      remoteHostId = hostId1,
      remoteHostFingerprint = fp1
    )
    val hub1Oc = ServerProfile(
      id = "hub1-oc",
      name = "Hub1 OpenCode",
      url = endpoint,
      username = acct1,
      allowCleartext = true,
      kind = ServerKind.OPENCODE,
      remoteHostId = hostId1,
      remoteServiceId = "svc-main",
      remoteHostFingerprint = fp1
    )
    val hub2Dsh = ServerProfile(
      id = "hub2-dsh",
      name = "Hub2 DSH",
      url = endpoint,
      username = acct2,
      allowCleartext = true,
      kind = ServerKind.DSH_REMOTE,
      remoteHostId = hostId2,
      remoteHostFingerprint = fp2
    )
    val sshConfig = SshHostConfig(
      id = "ssh-1",
      name = "Office Mac",
      host = "10.0.0.10",
      user = "dev"
    )
    fixture.sshHostStore.save(sshConfig, SshSecrets(password = "secret"))
    val sshProfile = ServerProfile(
      id = "ssh-dsh",
      name = "Office Mac DSH",
      url = sshConfig.profileUrl(),
      username = sshConfig.user,
      kind = ServerKind.DSH,
      sshHostId = sshConfig.id
    )

    listOf(hub1Dsh, hub1Oc, hub2Dsh, sshProfile).forEach { fixture.store.save(it, null) }

    fixture.store.saveRemoteCatalog(
      RemoteCatalog(
        origin = origin,
        account = acct1,
        provider = "password",
        deviceLabel = "Phone",
        hosts = listOf(RemoteHostMeta(hostId1, "Host1", "linux", true, 1000L, "1.0", "1.0", fp1, RemoteTrustState.PINNED)),
        updatedAt = 1000L,
        allowCleartext = true,
        adoptedHostIds = setOf(hostId1),
        userPaused = false
      )
    )
    fixture.store.saveRemoteCatalog(
      RemoteCatalog(
        origin = origin,
        account = acct2,
        provider = "password",
        deviceLabel = "Phone",
        hosts = listOf(RemoteHostMeta(hostId2, "Host2", "darwin", true, 1000L, "1.0", "1.0", fp2, RemoteTrustState.PINNED)),
        updatedAt = 1000L,
        allowCleartext = true,
        adoptedHostIds = setOf(hostId2),
        userPaused = false
      )
    )

    val scope1 = RemotePeerScope(origin, acct1, hostId1)
    val scope2 = RemotePeerScope(origin, acct2, hostId2)
    fixture.store.remotePins.pinIfAbsent(scope1, key1)
    fixture.store.remotePins.pinIfAbsent(scope2, key2)

    val scopedSlot1 = scopedRemoteDeviceSlot(origin, acct1, hostId1)
    val legacyHostSlot1 = RemoteIdentitySlots.legacyHostSlot(hostId1)
    val profileSlot1 = "profile-hub1-dsh"
    val scopedSlot2 = scopedRemoteDeviceSlot(origin, acct2, hostId2)

    val rec1Scoped = deviceRecord(origin, acct1, 4_000_000_000_000L)
    val rec1LegacyHost = deviceRecord(origin, acct1, 4_000_000_000_000L)
    val rec1Profile = deviceRecord(origin, acct1, 4_000_000_000_000L)
    val rec2Scoped = deviceRecord(origin, acct2, 4_000_000_000_000L)

    fixture.remoteDevices.save(scopedSlot1, rec1Scoped)
    fixture.remoteDevices.save(legacyHostSlot1, rec1LegacyHost)
    fixture.remoteDevices.save(profileSlot1, rec1Profile)
    fixture.remoteDevices.save(scopedSlot2, rec2Scoped)

    val hub1SourceId = MigrationProjection.hubSourceId(origin, acct1)
    val hub1HostBindingId = MigrationProjection.hostId(hub1SourceId, hostId1)
    val sshSourceId = MigrationProjection.sshSourceId(sshConfig.id)
    val sshHostBindingId = MigrationProjection.hostId(sshSourceId, sshConfig.id)

    val sampleProject = Project("p1", "/repo", "Repo")
    val sampleSession = Session("s1", "/repo", "Title", 1000L)
    listOf(hub1Dsh.id, hub1Oc.id, hub2Dsh.id, sshProfile.id).forEach { id ->
      fixture.offlineCache.saveCatalog(id, listOf(sampleProject), listOf(sampleSession), complete = true)
    }
    runBlocking { fixture.offlineCache.awaitWrites() }

    val hub2SourceId = MigrationProjection.hubSourceId(origin, acct2)
    val hub2HostBindingId = MigrationProjection.hostId(hub2SourceId, hostId2)

    fixture.discoveryStore.put(DeviceDiscovery(hub1HostBindingId, 1000L, emptyList(), DiscoveryCompleteness.COMPLETE))
    fixture.discoveryStore.put(DeviceDiscovery(hub2HostBindingId, 1000L, emptyList(), DiscoveryCompleteness.COMPLETE))
    fixture.discoveryStore.put(DeviceDiscovery(sshHostBindingId, 1000L, emptyList(), DiscoveryCompleteness.COMPLETE))

    fixture.groupStore.save(
      DeviceGroupState(
        groups = listOf(
          DeviceGroup(
            id = "grp-device",
            name = "Device Group",
            memberIds = listOf(hub1Dsh.id, hub1Oc.id, sshProfile.id),
            deviceIds = listOf(hub1HostBindingId, sshHostBindingId)
          ),
          DeviceGroup(
            id = "grp-legacy",
            name = "Legacy Group",
            memberIds = listOf(hub1Dsh.id, hub2Dsh.id, sshProfile.id),
            deviceIds = emptyList()
          )
        ),
        selectedGroupId = "grp-device"
      )
    )

    invokeInitializeConnectionGraph(fixture.controller)
    fixture.allSnapshots[hub1Dsh.id] = ServerOverviewSnapshot(hub1Dsh.id, hub1Dsh, connected = false, cached = true)
    fixture.mutable.value = fixture.mutable.value.copy(
      profiles = fixture.store.profiles(),
      deviceGroups = fixture.groupStore.load().state.groups,
      serverId = hub1Dsh.id,
      homeServerId = hub1Dsh.id,
      allServerSnapshots = fixture.allSnapshots.toMap()
    )

    fixture.controller.deleteRemoteSource(origin, acct1)

    // Hub 1 profiles, caches, discoveries, pins, catalog, credentials and graph nodes are completely wiped.
    val remainingProfileIds = fixture.store.profiles().map { it.id }.toSet()
    assertEquals(setOf(hub2Dsh.id, sshProfile.id), remainingProfileIds)
    assertEquals(remainingProfileIds, fixture.controller.state.value.profiles.map { it.id }.toSet())
    assertNull(fixture.offlineCache.catalog(hub1Dsh.id))
    assertNull(fixture.offlineCache.catalog(hub1Oc.id))
    assertNull(fixture.discoveryStore.get(hub1HostBindingId))
    assertNull(fixture.store.remotePins.pinned(scope1))
    assertEquals(listOf(acct2), fixture.store.remoteCatalogs().map { it.account })
    assertNull(fixture.remoteDevices.load(scopedSlot1))
    assertNull(fixture.remoteDevices.load(legacyHostSlot1))
    assertNull(fixture.remoteDevices.load(profileSlot1))

    // PendingRemoteRevocation items were queued for Hub 1's device credentials.
    val revocations = fixture.remoteDevices.pendingRevocations()
    assertTrue(revocations.isNotEmpty())
    assertTrue(revocations.all { it.origin == origin && it.account == acct1 && it.peerHostId == hostId1 })
    assertEquals(
      setOf(rec1Scoped.identity.deviceId, rec1LegacyHost.identity.deviceId, rec1Profile.identity.deviceId),
      revocations.map { it.clientDeviceId }.toSet()
    )

    // ConnectionGraph & DeviceGroup were pruned cleanly without dropping siblings.
    val graphAfter = fixture.controller.connectionGraph.value!!
    assertFalse(graphAfter.sources.any { it.id == hub1SourceId })
    assertFalse(graphAfter.hosts.any { it.id == hub1HostBindingId })
    assertEquals(setOf(hub2Dsh.id, sshProfile.id), graphAfter.bindings.map { it.id }.toSet())
    val groupsOnDisk = fixture.groupStore.load().state.groups.associateBy { it.id }
    val deviceGroup = groupsOnDisk.getValue("grp-device")
    assertEquals(listOf(sshProfile.id), deviceGroup.memberIds)
    assertEquals(listOf(sshHostBindingId), deviceGroup.deviceIds)
    val legacyGroup = groupsOnDisk.getValue("grp-legacy")
    assertEquals(listOf(hub2Dsh.id, sshProfile.id), legacyGroup.memberIds)
    assertTrue(legacyGroup.deviceIds.isEmpty())
    assertEquals(groupsOnDisk, fixture.controller.state.value.deviceGroups.associateBy { it.id })
    assertFalse(fixture.controller.state.value.serverId in setOf(hub1Dsh.id, hub1Oc.id))
    assertFalse(fixture.controller.state.value.homeServerId in setOf(hub1Dsh.id, hub1Oc.id))
    assertFalse(fixture.allSnapshots.containsKey(hub1Dsh.id))

    // Sibling Hub 2 and SSH 1 remain completely intact.
    assertNotNull(fixture.offlineCache.catalog(hub2Dsh.id))
    assertNotNull(fixture.offlineCache.catalog(sshProfile.id))
    assertNotNull(fixture.discoveryStore.get(hub2HostBindingId))
    assertNotNull(fixture.discoveryStore.get(sshHostBindingId))
    assertEquals(key2, fixture.store.remotePins.pinned(scope2))
    assertNotNull(fixture.remoteDevices.load(scopedSlot2))
    assertNotNull(fixture.sshHostStore.load().firstOrNull { it.id == sshConfig.id })
  }

  @Test fun hubAndDeviceAuthStatusDetectsActiveSignedOutAndAllExpiredConditions() {
    val fixture = newFixture()
    val origin = canonicalRemoteOrigin("https://hub.example.invalid/")
    val endpoint = origin.trimEnd('/')
    val account = "owner@example.org"
    val nowMs = 1_000_000L
    val fp1 = RemoteSetup.fingerprint(key1)

    val profile = ServerProfile(
      id = "hub-auth-dsh",
      name = "Workstation",
      url = endpoint,
      username = account,
      kind = ServerKind.DSH_REMOTE,
      remoteHostId = hostId1,
      remoteHostFingerprint = fp1
    )
    fixture.store.save(profile, null)
    val meta = RemoteHostMeta(hostId1, "Workstation", "linux", true, nowMs, "1.0", "1.0", fp1, RemoteTrustState.PINNED)
    val activeCatalog = RemoteCatalog(
      origin = origin,
      account = account,
      provider = "password",
      deviceLabel = "Phone",
      hosts = listOf(meta),
      updatedAt = nowMs,
      signedOut = false,
      adoptedHostIds = setOf(hostId1),
      userPaused = false
    )
    fixture.store.saveRemoteCatalog(activeCatalog)
    invokeInitializeConnectionGraph(fixture.controller)
    fixture.mutable.value = fixture.mutable.value.copy(profiles = fixture.store.profiles())

    val deviceView = DeviceProjection.devices(
      graph = fixture.controller.connectionGraph.value,
      profiles = fixture.controller.state.value.profiles,
      adoptedDeviceIds = setOf(MigrationProjection.hostId(MigrationProjection.hubSourceId(origin, account), hostId1))
    ).single()
    val slot = scopedRemoteDeviceSlot(origin, account, hostId1)

    // 1. Missing slots -> EXPIRED
    assertTrue(fixture.controller.hostAuthExpired(origin, account, hostId1, fixture.controller.state.value, nowMs))
    assertEquals(HubAuthStatus.EXPIRED, fixture.controller.hubAuthStatus(activeCatalog, fixture.controller.state.value, nowMs))
    assertEquals(HubAuthStatus.EXPIRED, fixture.controller.deviceAuthStatus(deviceView, fixture.controller.state.value, nowMs))

    // 2. Valid non-invalidated record -> ACTIVE
    fixture.remoteDevices.save(slot, deviceRecord(origin, account, refreshExpiresAt = nowMs + 60_000L, invalidated = false))
    assertFalse(fixture.controller.hostAuthExpired(origin, account, hostId1, fixture.controller.state.value, nowMs))
    assertEquals(HubAuthStatus.ACTIVE, fixture.controller.hubAuthStatus(activeCatalog, fixture.controller.state.value, nowMs))
    assertEquals(HubAuthStatus.ACTIVE, fixture.controller.deviceAuthStatus(deviceView, fixture.controller.state.value, nowMs))

    // 3. Invalidated record -> EXPIRED
    fixture.remoteDevices.save(slot, deviceRecord(origin, account, refreshExpiresAt = nowMs + 60_000L, invalidated = true))
    assertTrue(fixture.controller.hostAuthExpired(origin, account, hostId1, fixture.controller.state.value, nowMs))
    assertEquals(HubAuthStatus.EXPIRED, fixture.controller.hubAuthStatus(activeCatalog, fixture.controller.state.value, nowMs))
    assertEquals(HubAuthStatus.EXPIRED, fixture.controller.deviceAuthStatus(deviceView, fixture.controller.state.value, nowMs))

    // 4. Refresh token expired -> EXPIRED
    fixture.remoteDevices.save(slot, deviceRecord(origin, account, refreshExpiresAt = nowMs, invalidated = false))
    assertTrue(fixture.controller.hostAuthExpired(origin, account, hostId1, fixture.controller.state.value, nowMs))
    assertEquals(HubAuthStatus.EXPIRED, fixture.controller.hubAuthStatus(activeCatalog, fixture.controller.state.value, nowMs))
    assertEquals(HubAuthStatus.EXPIRED, fixture.controller.deviceAuthStatus(deviceView, fixture.controller.state.value, nowMs))

    // 5. Runtime snapshot signaling RemoteRecoveryKind.LOGIN overrides valid token -> EXPIRED
    fixture.remoteDevices.save(slot, deviceRecord(origin, account, refreshExpiresAt = nowMs + 60_000L, invalidated = false))
    val loginState = fixture.controller.state.value.copy(
      allServerSnapshots = mapOf(
        profile.id to ServerOverviewSnapshot(profile.id, profile, remoteRecovery = RemoteRecoveryKind.LOGIN)
      )
    )
    assertTrue(fixture.controller.hostAuthExpired(origin, account, hostId1, loginState, nowMs))
    assertEquals(HubAuthStatus.EXPIRED, fixture.controller.hubAuthStatus(activeCatalog, loginState, nowMs))
    assertEquals(HubAuthStatus.EXPIRED, fixture.controller.deviceAuthStatus(deviceView, loginState, nowMs))

    // 6. Explicitly signed out catalog -> SIGNED_OUT
    val signedOutCatalog = activeCatalog.copy(signedOut = true)
    fixture.store.saveRemoteCatalog(signedOutCatalog)
    assertEquals(HubAuthStatus.SIGNED_OUT, fixture.controller.hubAuthStatus(signedOutCatalog, fixture.controller.state.value, nowMs))
    assertEquals(HubAuthStatus.SIGNED_OUT, fixture.controller.deviceAuthStatus(deviceView, fixture.controller.state.value, nowMs))
  }
}
