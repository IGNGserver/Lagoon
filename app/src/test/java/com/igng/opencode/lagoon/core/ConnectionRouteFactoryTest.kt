package com.igng.opencode.lagoon.core

import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

/** Construction/admission only: no health calls, sockets, or live monitor coroutines. */
class ConnectionRouteFactoryTest {
  private val kinds = listOf(ServerKind.OPENCODE, ServerKind.DSH)
  private val hostId = "9bdcb22d-316d-4a51-8e2d-9a7bc1609350"
  private val credentials = ServerCredentials("credential-user",
    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 }), "cookie")
  private fun direct(kind: ServerKind) = ServerProfile("binding", "Saved", "https://native.example/api",
    username = "route-user", kind = kind, dshAuth = DshAuthMode.SECRET)
  private fun ssh(kind: ServerKind) = direct(kind).copy(url = "ssh://alice@ssh.example:22",
    sshHostId = "saved-ssh", sshServicePath = "/state/service.json")
  private fun remote(kind: ServerKind) = direct(kind).copy(url = "https://hub.example/",
    kind = if (kind == ServerKind.DSH) ServerKind.DSH_REMOTE else kind, username = "account@example.test",
    remoteHostId = hostId, remoteHostFingerprint = "0123456789ab",
    remoteServiceId = if (kind == ServerKind.OPENCODE) "opencode-main" else null)
  private fun route(profile: ServerProfile) = ConnectionRouteAdmission.requireCurrent(profile,
    MigrationProjection.fromProfiles(listOf(profile)))
  private fun memoryStore(graph: () -> ConnectionGraph?) = ServerStore(memoryPreferences(), memoryPreferences(),
    { it }, { it }, readConnectionGraph = graph)
  private fun marker(onClose: () -> Unit = {}): HarnessClient = Proxy.newProxyInstance(javaClass.classLoader,
    arrayOf(HarnessClient::class.java)) { _, method, _ ->
    if (method.name == "close") { onClose(); null } else error("Unexpected protocol call: ${method.name}")
  } as HarnessClient

  @Test fun executionProfileTakesEveryExecutionFieldFromSnapshotButKeepsUserPreferences() {
    val hostile = ServerProfile("different-id", "My display name", "http://evil.example/", "attacker",
      autoConnect = false, notifications = false, allowCleartext = true, kind = ServerKind.DSH,
      dshAuth = DshAuthMode.TOKEN, sshHostId = "evil-ssh", sshServicePath = "/evil/service.json")
    for (saved in kinds.flatMap { listOf(direct(it), ssh(it), remote(it)) }) {
      val snapshot = route(saved)
      val actual = snapshot.executionProfile(hostile)
      assertEquals(saved.copy(name = hostile.name, autoConnect = false, notifications = false), actual)
      assertEquals(saved.id, snapshot.bindingId)
      assertEquals(saved.remoteServiceId, actual.remoteServiceId)
      // Reverse direction also clears an injected Remote host/service when admitted route is direct/SSH.
      val foreignRemote = remote(ServerKind.OPENCODE).copy(id = "foreign", remoteHostId = "evil-host",
        remoteHostFingerprint = "aaaaaaaaaaaa", remoteServiceId = "evil-service")
      assertEquals(saved, snapshot.executionProfile(foreignRemote.copy(name = saved.name)))
    }
  }

  @Test fun directNativeProtocolsConstructTheirNativeClassesWithoutCarrierCallbacksOrIo() {
    for (kind in kinds) {
      val saved = direct(kind)
      val client = createRoutedHarnessClient(route(saved), remote(ServerKind.OPENCODE), credentials,
        remoteFactory = { _, _ -> error("Direct route called Remote") },
        sshFactory = { _, _ -> error("Direct route called SSH") })
      try {
        assertEquals(if (kind == ServerKind.OPENCODE) OpenCodeApi::class.java else DshApi::class.java, client.javaClass)
        val captured = client.javaClass.getDeclaredField("profile").apply { isAccessible = true }.get(client)
        assertEquals(saved, captured)
      } finally { client.close() }
    }
  }

  @Test fun bothNativeProtocolsUseExactlyTheAdmittedSshProfileAndCredentialObject() {
    for (kind in kinds) {
      val saved = ssh(kind)
      val preferences = remote(ServerKind.OPENCODE).copy(name = "Display only", autoConnect = false, notifications = false)
      val expected = saved.copy(name = preferences.name, autoConnect = false, notifications = false)
      val sentinel = marker()
      var calls = 0
      val actual = createRoutedHarnessClient(route(saved), preferences, credentials,
        remoteFactory = { _, _ -> error("SSH route called Remote") }, sshFactory = { profile, secret ->
          calls++
          assertEquals(expected, profile)
          assertSame(credentials, secret)
          sentinel
        })
      assertSame(sentinel, actual)
      assertEquals(1, calls)
    }
  }

  @Test fun remoteNativeAndTypertDispatchThroughActualRemoteBackendWithExactHostOriginAndService() {
    for (kind in kinds) {
      val saved = remote(kind)
      val sentinel = marker()
      val backend = backend(sentinel)
      var calls = 0
      val actual = createRoutedHarnessClient(route(saved), ssh(ServerKind.DSH), credentials,
        remoteFactory = { profile, secret ->
          calls++
          assertEquals(saved, profile)
          assertEquals("https://hub.example/", profile.url)
          assertEquals(hostId, profile.remoteHostId)
          assertEquals("0123456789ab", profile.remoteHostFingerprint)
          assertEquals(if (kind == ServerKind.OPENCODE) "opencode-main" else null, profile.remoteServiceId)
          assertSame(credentials, secret)
          backend
        }, sshFactory = { _, _ -> error("Remote route called SSH") })
      assertSame(sentinel, actual)
      assertEquals(1, calls)
    }
  }

  @Test fun absentCarrierFactoryFailsInsteadOfFallingBackToDirectOrTheOtherCarrier() {
    for (kind in kinds) {
      val remoteFailure = runCatching {
        createRoutedHarnessClient(route(remote(kind)), direct(kind), credentials,
          sshFactory = { _, _ -> error("Remote fell back to SSH") })
      }.exceptionOrNull()
      assertTrue(remoteFailure is IllegalStateException)
      assertTrue(remoteFailure!!.message.orEmpty().contains("Remote carrier"))
      val sshFailure = runCatching {
        createRoutedHarnessClient(route(ssh(kind)), direct(kind), credentials,
          remoteFactory = { _, _ -> error("SSH fell back to Remote") })
      }.exceptionOrNull()
      assertTrue(sshFailure is IllegalStateException)
      assertTrue(sshFailure!!.message.orEmpty().contains("SSH carrier"))
    }
  }

  @Test fun realStoreDeniesParentsBeforeAnyCredentialOrCarrierCallback() {
    for (saved in kinds.flatMap { listOf(ssh(it), remote(it)) }) {
      val admitted = MigrationProjection.fromProfiles(listOf(saved))
      for (denySource in listOf(true, false)) {
        var graph = admitted
        val store = memoryStore { graph }
        store.save(saved, "secret")
        graph = if (denySource) admitted.copy(sources = admitted.sources.map { it.copy(enabled = false) })
          else admitted.copy(hosts = admitted.hosts.map { it.copy(enabled = false) })
        var credentialCalls = 0
        var carrierCalls = 0
        val failure = runCatching {
          val snapshot = store.runtimeRoute(saved)
          val secret = run { credentialCalls++; store.credentials(saved.id) }
          createRoutedHarnessClient(snapshot, saved, secret,
            remoteFactory = { _, _ -> carrierCalls++; error("denied Remote") },
            sshFactory = { _, _ -> carrierCalls++; error("denied SSH") })
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(0, credentialCalls)
        assertEquals(0, carrierCalls)
        assertEquals(saved, store.readProfiles().single())
      }
    }
  }

  @Test fun overviewRevalidatesCachedClientAndDisposesItWhenSavedRouteChanges() {
    val saved = ssh(ServerKind.OPENCODE)
    var graph = MigrationProjection.fromProfiles(listOf(saved))
    val store = memoryStore { graph }
    store.save(saved, "secret")
    var credentialCalls = 0
    val guardedStore = object : OverviewStore by store, ConnectionRouteStore {
      override fun runtimeRoute(expected: ServerProfile) = store.runtimeRoute(expected)
      override fun credentials(id: String): ServerCredentials { credentialCalls++; return store.credentials(id) }
    }
    val scope = CoroutineScope(Job().apply { cancel() }) // start records config; canceled loops cannot dial.
    var carrierCalls = 0
    var closes = 0
    val removed = mutableListOf<String>()
    val overview = AllServersOverview(guardedStore, CatalogCache { null }, scope, {},
      onSnapshotRemoved = { removed += it }, sshClientFactory = { _, _ -> carrierCalls++; error("No dial") })
    try {
      overview.start(listOf(saved))
      val sentinel = marker { closes++ }
      @Suppress("UNCHECKED_CAST")
      val clients = AllServersOverview::class.java.getDeclaredField("clients").apply { isAccessible = true }
        .get(overview) as MutableMap<String, HarnessClient>
      clients[saved.id] = sentinel
      assertSame(sentinel, overview.client(saved.id))
      removed.clear()
      val changed = saved.copy(sshHostId = "replacement-ssh", url = "ssh://bob@new.example:22")
      store.save(changed, null)
      graph = MigrationProjection.fromProfiles(listOf(changed))
      assertTrue(runCatching { overview.client(saved.id) }.exceptionOrNull() is StaleConnectionRouteException)
      assertEquals(1, closes)
      assertEquals(listOf(saved.id), removed)
      assertTrue(clients.isEmpty())
      assertTrue(runCatching { overview.client(saved.id) }.isFailure)
      assertEquals(1, credentialCalls)
      assertEquals(0, carrierCalls)
    } finally { overview.stop(); scope.coroutineContext[Job]?.cancel() }
  }

  private fun backend(client: HarnessClient): RemoteBackend {
    val type = Class.forName("sun.misc.Unsafe")
    val unsafe = type.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    val backend = type.getMethod("allocateInstance", Class::class.java).invoke(unsafe, RemoteBackend::class.java) as RemoteBackend
    RemoteBackend::class.java.getDeclaredField("harness").apply { isAccessible = true }.set(backend, client)
    return backend
  }
}
