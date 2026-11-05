package com.igng.opencode.lagoon.core

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Opt-in end-to-end probe against a real DSH Hub + Host.
 *
 * The normal unit run skips it (no env). `scripts/remote-e2e.sh` provisions a throwaway
 * Server + `dsh web` Host and sets:
 *
 *   LAGOON_REMOTE_BASE=http://127.0.0.1:<port>
 *   LAGOON_REMOTE_ACCOUNT=e2e@example.test
 *   LAGOON_REMOTE_PASSWORD=<generated>
 */
class RemoteLiveProbeTest {
  private class Store : RemoteDeviceStore {
    private val records = ConcurrentHashMap<String, RemoteDeviceRecord>()
    override fun load(slot: String): RemoteDeviceRecord? = records[slot]
    override fun save(slot: String, record: RemoteDeviceRecord) { records[slot] = record }
    override fun delete(slot: String) { records.remove(slot) }
  }

  private class Pins : RemotePeerTrustStore {
    private val pins = ConcurrentHashMap<String, String>()
    override fun pinned(scope: RemotePeerScope): String? = pins[scope.toString()]
    override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String): String =
      pins.putIfAbsent(scope.toString(), publicKey) ?: publicKey
  }

  @Test fun loadsRealHub() = runBlocking {
    val base = System.getenv("LAGOON_REMOTE_BASE") ?: run {
      println("LAGOON_REMOTE_BASE not set; skipping live probe")
      return@runBlocking
    }
    val account = System.getenv("LAGOON_REMOTE_ACCOUNT") ?: error("missing LAGOON_REMOTE_ACCOUNT")
    val password = System.getenv("LAGOON_REMOTE_PASSWORD") ?: error("missing LAGOON_REMOTE_PASSWORD")
    val origin = canonicalRemoteOrigin(base)
    val api = RemoteServerApi(base, allowCleartext = true)
    val devices = Store()
    val pins = Pins()
    val slot = "live-probe"
    val setup = RemoteSetup(api, devices, pins, slot, origin, "Lagoon Live Probe", "android", "probe")
    val state = setup.register(account, password)
    val host = state.hosts.firstOrNull { it.online } ?: error("no online host: ${state.hosts.size}")
    setup.confirmHost(state.account, host.deviceId, RemoteSetup.fingerprint(host.publicKey!!))
    val session = RemoteSession(base, allowCleartext = true, hostId = host.deviceId,
      devices = RemoteDeviceManager(devices, slot, base, state.account),
      trust = RemotePeerTrust(pins), account = state.account, clientVersion = "probe")
    val backend = RemoteBackend(session)
    try {
      val client = backend.client
      println("LIVE status=" + session.status.value.phaseLabel())
      // The Host may still be warming its Gateway services right after the control hello.
      var sessions: List<Session> = emptyList()
      var last: Exception? = null
      val deadline = System.currentTimeMillis() + 15_000
      while (System.currentTimeMillis() < deadline) {
        try { sessions = client.rootSessionsPage(null, null, 50).items; last = null; break }
        catch (error: Exception) {
          last = error
          println("LIVE list retry: ${error.javaClass.simpleName} code=${(error as? ApiException)?.code} msg=${error.message}")
          kotlinx.coroutines.delay(500)
        }
      }
      if (last != null) throw last
      println("LIVE sessions=${sessions.size}")
      val withContent = sessions.firstOrNull { runCatching { client.messagesPage(it.id).items.isNotEmpty() }.getOrDefault(false) }
      if (withContent != null) {
        val page = client.messagesPage(withContent.id)
        println("LIVE snapshot session=${withContent.id.take(16)} messages=${page.items.size}")
        assertTrue(page.items.isNotEmpty())
      } else {
        println("LIVE 没有可读内容的会话（连接与目录已通过）")
      }
      val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
      val eventsJob = launch { client.events {}.collect { seen += it.type } }
      var eventsReady = false
      val eventsDeadline = System.currentTimeMillis() + 5_000
      while (System.currentTimeMillis() < eventsDeadline) {
        delay(250)
        if ((client as RemoteDoctorSource).remoteDoctor("probe")?.events?.ready == true) { eventsReady = true; break }
      }
      println("LIVE events=${seen.size} ready=$eventsReady")
      eventsJob.cancelAndJoin()
      assertTrue("\$events 应建立并 ready", eventsReady)
      val doctor = (client as RemoteDoctorSource).remoteDoctor("probe")
      assertTrue(doctor != null)
      println(doctor!!.asText())
    } finally {
      backend.close()
      session.close()
    }
  }
}
