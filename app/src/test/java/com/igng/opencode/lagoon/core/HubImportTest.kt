package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Importing servers from a Lagoon hub: de-duplication against this device, the client, and what is stored. */
class HubImportTest {
  private val hub = "hub-1"
  private fun exported(id: String, url: String, name: String = id, password: String = "pw", kind: ServerKind = ServerKind.OPENCODE, localOnly: Boolean = false) =
    ExportedServer(ServerProfile(id, name, url, if (kind == ServerKind.DSH) "dsh" else "opencode", allowCleartext = url.startsWith("http://"), kind = kind),
      ServerCredentials(if (kind == ServerKind.DSH) "dsh" else "opencode", password), localOnly)
  private fun local(id: String, url: String, name: String = id, kind: ServerKind = ServerKind.OPENCODE) =
    ServerProfile(id, name, url, if (kind == ServerKind.DSH) "dsh" else "opencode", autoConnect = false, notifications = false,
      allowCleartext = url.startsWith("http://"), kind = kind)
  private fun plan(servers: List<ExportedServer>, locals: List<ServerProfile>, links: Map<String, HubLink> = emptyMap(), password: String = "pw") =
    planHubImport(HubExport(hub, 0, servers), locals, links) { ServerCredentials("opencode", password) }.items

  @Test fun endpointsNormalizeCaseDefaultPortsAndTrailingSlashes() {
    assertEquals(endpointKey(ServerKind.OPENCODE, "https://Dev.Example.com/"), endpointKey(ServerKind.OPENCODE, "https://dev.example.com:443"))
    assertEquals(endpointKey(ServerKind.DSH, "http://10.0.0.2:3080/dsh/"), endpointKey(ServerKind.DSH, "http://10.0.0.2:3080/dsh"))
    assertFalse(endpointKey(ServerKind.OPENCODE, "http://10.0.0.2:4096") == endpointKey(ServerKind.DSH, "http://10.0.0.2:4096"))
    assertFalse(endpointKey(ServerKind.OPENCODE, "http://10.0.0.2:4096") == endpointKey(ServerKind.OPENCODE, "https://10.0.0.2:4096"))
    assertNull(endpointKey(ServerKind.OPENCODE, "not a url"))
  }

  @Test fun newServersAreAddedAndContainerOnlyOnesAreNotTickedByDefault() {
    val items = plan(listOf(exported("a", "http://10.0.0.2:4096"), exported("b", "http://host.docker.internal:4096", localOnly = true)), emptyList())
    assertEquals(listOf(ImportAction.ADD, ImportAction.ADD), items.map { it.action })
    assertTrue(items[0].selectedByDefault)
    assertFalse(items[1].selectedByDefault)
    assertTrue(items[1].selectable)
    assertTrue(items[1].note!!.contains("客户端访问地址"))
  }

  @Test fun aServerImportedBeforeIsUpdatedInPlaceEvenAfterTheHubChangedItsAddress() {
    val items = plan(listOf(exported("a", "http://10.0.0.3:4096", name = "Dev")), listOf(local("L", "http://10.0.0.2:4096", name = "Dev")),
      mapOf("L" to HubLink(hub, "a")))
    val item = items.single()
    assertEquals(ImportAction.UPDATE, item.action)
    assertEquals("L", item.local!!.id)
    assertEquals(listOf("地址"), item.changes)
    val saved = importedProfile(item) { error("不应生成新 id") }!!
    assertEquals("L", saved.id)
    assertEquals("http://10.0.0.3:4096", saved.url)
    // Device preferences stay as they were on this phone.
    assertFalse(saved.autoConnect)
    assertFalse(saved.notifications)
  }

  @Test fun nothingChangedMeansNothingToTick() {
    val items = plan(listOf(exported("a", "http://10.0.0.2:4096", name = "Dev")), listOf(local("L", "http://10.0.0.2:4096", name = "Dev")),
      mapOf("L" to HubLink(hub, "a")))
    assertEquals(ImportAction.UNCHANGED, items.single().action)
    assertFalse(items.single().selectable)
    assertEquals(listOf("凭据"), plan(listOf(exported("a", "http://10.0.0.2:4096", name = "Dev", password = "new")),
      listOf(local("L", "http://10.0.0.2:4096", name = "Dev")), mapOf("L" to HubLink(hub, "a"))).single().changes)
  }

  @Test fun aServerAddedByHandWithTheSameEndpointIsMergedNotDuplicated() {
    val item = plan(listOf(exported("a", "http://10.0.0.2:4096/", name = "Dev")), listOf(local("L", "http://10.0.0.2:4096", name = "Dev"))).single()
    assertEquals(ImportAction.UPDATE, item.action)
    assertEquals("L", item.local!!.id)
    assertTrue(item.selectedByDefault)
    assertTrue(item.note!!.contains("合并"))
  }

  @Test fun theSameAddressWithAnotherHarnessIsADifferentServer() {
    val items = plan(listOf(exported("a", "http://10.0.0.2:4096", kind = ServerKind.DSH)), listOf(local("L", "http://10.0.0.2:4096")))
    assertEquals(ImportAction.ADD, items.single().action)
  }

  @Test fun aServerFollowingAnotherHubIsAConflictTheUserResolves() {
    val item = plan(listOf(exported("a", "http://10.0.0.2:4096")), listOf(local("L", "http://10.0.0.2:4096")), mapOf("L" to HubLink("hub-2", "x"))).single()
    assertEquals(ImportAction.CONFLICT, item.action)
    assertTrue(item.selectable)
    assertFalse(item.selectedByDefault)
    assertTrue(item.note!!.contains("另一个中枢"))
  }

  @Test fun duplicateEndpointsInTheExportImportOnceAndTheFollowedOneWins() {
    val items = plan(listOf(exported("a", "http://10.0.0.2:4096", name = "Copy"), exported("b", "http://10.0.0.2:4096", name = "Dev")),
      listOf(local("L", "http://10.0.0.2:4096", name = "Dev")), mapOf("L" to HubLink(hub, "b")))
    assertEquals(ImportAction.CONFLICT, items[0].action)
    assertFalse(items[0].selectable)
    assertTrue(items[0].note!!.contains("Dev"))
    assertEquals(ImportAction.UNCHANGED, items[1].action)
    // Without a local server, the first of the duplicates is added and the second skipped.
    assertEquals(listOf(ImportAction.ADD, ImportAction.CONFLICT),
      plan(listOf(exported("a", "http://10.0.0.2:4096"), exported("b", "http://10.0.0.2:4096")), emptyList()).map { it.action })
  }

  @Test fun serversTheHubDroppedAreListedButNeverRemovedByDefault() {
    val items = plan(listOf(exported("a", "http://10.0.0.2:4096")),
      listOf(local("L", "http://10.0.0.2:4096"), local("G", "http://10.0.0.9:4096"), local("M", "http://10.0.0.8:4096")),
      mapOf("L" to HubLink(hub, "a"), "G" to HubLink(hub, "gone"), "M" to HubLink("hub-2", "gone")))
    val removed = items.single { it.action == ImportAction.REMOVED }
    assertEquals("G", removed.local!!.id)
    assertTrue(removed.selectable)
    assertFalse(removed.selectedByDefault)
    assertNull(importedProfile(removed) { "x" })
  }

  @Test fun aStaleLinkToAServerTheHubReplacedIsReusedForTheSameEndpoint() {
    val item = plan(listOf(exported("new", "http://10.0.0.2:4096")), listOf(local("L", "http://10.0.0.2:4096")), mapOf("L" to HubLink(hub, "old"))).single()
    assertEquals(ImportAction.UPDATE, item.action)
    assertEquals("L", item.local!!.id)
  }

  @Test fun theStoreRemembersLinksAndTheHubAndForgetsLinksOfDeletedServers() {
    val store = ServerStore(memoryPreferences(), memoryPreferences(), { "encrypted:$it" }, { require(it.startsWith("encrypted:")); it.removePrefix("encrypted:") })
    store.save(local("L", "http://10.0.0.2:4096"), "pw")
    store.rememberHubLink("L", HubLink(hub, "a"))
    assertEquals(mapOf("L" to HubLink(hub, "a")), store.hubLinks())
    val target = HubImportTarget("https://hub.lan", HubAccessKey.generate())
    store.rememberHubTarget(target)
    assertEquals(target, store.hubTarget())
    store.delete("L")
    assertTrue(store.hubLinks().isEmpty())
    store.rememberHubTarget(null)
    assertNull(store.hubTarget())
  }

  @Test fun signaturesMatchTheHub() {
    // Same vector as HubExportTest.GOLDEN_MAC in the hub's tests.
    assertEquals("pCKvzHb0bYwxglMls4r0yogURejjtPLHNr_gQsnqHqs",
      HubExportCrypto.mac(HubAccessKey("abcd2345", ByteArray(32) { it.toByte() }), 1_000, "nonce-0123456789ab"))
  }

  @Test fun theClientSignsFetchesAndDecryptsAnExport() = runBlocking {
    val key = HubAccessKey.generate()
    val export = HubExport(hub, 7, listOf(exported("a", "http://10.0.0.2:4096")))
    val server = MockWebServer()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val body = JSONObject(request.body.readUtf8())
        if (request.path != "/api/v1/client/export" || !HubExportCrypto.verify(key, body.getLong("ts"), body.getString("nonce"), body.getString("mac"))) {
          return MockResponse().setResponseCode(401).setBody("""{"error":{"code":"bad-key","message":"接入密钥无效或已被吊销"}}""")
        }
        return MockResponse().setBody(HubExportCrypto.seal(key, body.getString("nonce"), HubExportCodec.encode(export).toString()).toString())
      }
    }
    server.start()
    try {
      val target = HubImportTarget(server.url("/").toString().trimEnd('/'), key)
      assertEquals(export, HubExportClient.fetch(target))
      try {
        HubExportClient.fetch(target.copy(key = HubAccessKey(key.id, HubAccessKey.generate().secret)))
        fail("错误的密钥不应导入成功")
      } catch (error: IOException) {
        assertEquals("接入密钥无效或已被吊销", error.message)
      }
    } finally {
      server.shutdown()
    }
  }

  @Test fun aTamperedOrForeignAnswerIsRejected() = runBlocking {
    val key = HubAccessKey.generate()
    val server = MockWebServer()
    server.enqueue(MockResponse().setBody(HubExportCrypto.seal(key, "another-nonce-123456", "{}").toString()))
    server.enqueue(MockResponse().setResponseCode(404).setBody("<html>"))
    server.start()
    try {
      val target = HubImportTarget(server.url("/").toString().trimEnd('/'), key)
      val replayed = runCatching { HubExportClient.fetch(target) }.exceptionOrNull()
      assertTrue(replayed?.message, replayed?.message?.contains("无法解密") == true)
      val missing = runCatching { HubExportClient.fetch(target) }.exceptionOrNull()
      assertTrue(missing?.message, missing?.message?.contains("不是 Lagoon 中枢") == true)
    } finally {
      server.shutdown()
    }
  }
}
