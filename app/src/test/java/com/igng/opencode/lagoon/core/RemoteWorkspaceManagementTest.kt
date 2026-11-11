package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real Noise and strict Remote endpoint contract; controlled authoritative workspace domain. */
class RemoteWorkspaceManagementTest {
  private class Store(private var value: RemoteDeviceRecord) : RemoteDeviceStore {
    override fun load(slot: String) = value
    override fun save(slot: String, record: RemoteDeviceRecord) { value = record }
    override fun delete(slot: String) = Unit
  }
  private class Pins(private val key: String) : RemotePeerTrustStore {
    override fun pinned(scope: RemotePeerScope) = key
    override fun pinIfAbsent(scope: RemotePeerScope, publicKey: String) = key
  }
  private fun backend(host: FakeRemoteHost): Pair<RemoteSession, RemoteBackend> {
    val account = host.accountEmail()
    val record = RemoteDeviceRecord(canonicalRemoteOrigin(host.url), account, RemoteIdentity.generate(),
      RemoteTokenPair(UUID.randomUUID().toString(), Long.MAX_VALUE / 2, UUID.randomUUID().toString(), Long.MAX_VALUE / 2))
    val session = RemoteSession(host.url, true, host.hostId, RemoteDeviceManager(Store(record), "workspaces", host.url, account),
      RemotePeerTrust(Pins(host.hostIdentity.publicKey)), account)
    return session to RemoteBackend(session)
  }
  private fun item(id: String, path: String, title: String) = JSONObject().put("workspaceId", id).put("path", path).put("title", title)
    .put("sessionIds", JSONArray().put("retained-session")).put("createdAt", "2026-11-01T00:00:00Z").put("updatedAt", "2026-11-02T00:00:00Z")

  @Test fun nativeRemoteRegistrationRenameOrderAndRemovalUseOpaqueIdentityAndKeepSessions() = runBlocking {
    FakeRemoteHost().use { host ->
      val items = linkedMapOf("first" to item("first", "/repo", "First"))
      var order = listOf("first"); var mutations = 0
      host.streamFrames = { endpoint, _ ->
        if (endpoint == "workspace/follow") listOf(JSONObject().put("type", "baseline").put("value", JSONObject()
          .put("items", JSONArray(order.map { items.getValue(it) })).put("archivedSessionIds", JSONArray()).put("pinnedSessionIds", JSONArray())))
        else emptyList()
      }
      host.call = { endpoint, payload ->
        val request = payload.getJSONObject("args").getJSONObject("request")
        when (endpoint) {
          "workspace/create" -> {
            mutations++; assertEquals(setOf("path"), request.keys().asSequence().toSet()); assertEquals("/second", request.getString("path"))
            val existing = items["second"]
            if (existing == null) { items["second"] = item("second", "/canonical-second", "Second"); order = order + "second" }
            host.ok(JSONObject().put("created", existing == null).put("workspace", items.getValue("second")))
          }
          "workspace/rename" -> {
            mutations++; assertEquals(setOf("workspaceId", "title"), request.keys().asSequence().toSet())
            val value = items.getValue(request.getString("workspaceId")); value.put("title", request.getString("title")).put("updatedAt", "2026-11-03T00:00:00Z")
            host.ok(JSONObject().put("workspace", value))
          }
          "workspace/insertBefore" -> {
            mutations++; assertEquals(setOf("workspaceId", "beforeWorkspaceId"), request.keys().asSequence().toSet())
            val id = request.getString("workspaceId"); val before = request.getString("beforeWorkspaceId")
            order = order.filterNot { it == id }.toMutableList().apply { add(indexOf(before), id) }
            host.ok(JSONObject().put("workspaceIds", JSONArray(order)))
          }
          "workspace/delete" -> {
            mutations++; assertEquals(setOf("workspaceId"), request.keys().asSequence().toSet())
            val id = request.getString("workspaceId"); items.remove(id); order = order.filterNot { it == id }
            host.ok(JSONObject().put("deleted", true))
          }
          else -> error("Unexpected workspace operation $endpoint")
        }
      }
      host.start(); val (session, backend) = backend(host); val api = backend.client
      try {
        assertTrue(api.capabilities().workspaceManagement)
        val first = api.serverWorkspaces()!!.single()
        val registered = api.registerWorkspace("/second")!!
        assertTrue(registered.created); assertEquals("/canonical-second", registered.workspace.path)
        assertEquals("second", api.projectAt("/canonical-second")!!.id)
        val renamed = api.renameWorkspace(registered.workspace, "Second renamed")!!
        assertEquals("Second renamed", renamed.title)
        assertEquals(listOf("second", "first"), api.moveWorkspace(renamed, first))
        assertEquals(listOf("second", "first"), api.serverWorkspaces()!!.map { it.id })
        assertEquals(true, api.deleteWorkspace(renamed))
        assertEquals(listOf("first"), api.serverWorkspaces()!!.map { it.id })
        assertEquals(listOf("retained-session"), first.sessionIds)
        assertEquals(4, mutations)
        val calls = synchronized(host.rpcCalls) { host.rpcCalls.toList() }
        assertTrue(calls.none { it.toString().contains("session/delete") || it.toString().contains("directoryPicker/createDirectory") })
      } finally { backend.close(); session.close() }
    }
  }

  @Test fun staleNativeWorkspaceCannotRenameOrDeleteBeforeFreshCatalogCheck() = runBlocking {
    FakeRemoteHost().use { host ->
      var value = item("first", "/repo", "First"); var mutations = 0
      host.streamFrames = { endpoint, _ -> if (endpoint == "workspace/follow") listOf(JSONObject().put("type", "baseline").put("value", JSONObject()
        .put("items", JSONArray().put(value)).put("archivedSessionIds", JSONArray()).put("pinnedSessionIds", JSONArray()))) else emptyList() }
      host.call = { _, _ -> mutations++; host.ok(JSONObject()) }
      host.start(); val (session, backend) = backend(host)
      try {
        val previous = backend.client.serverWorkspaces()!!.single()
        value = item("first", "/repo", "Changed elsewhere").put("updatedAt", "2026-11-04T00:00:00Z")
        assertTrue(runCatching { backend.client.renameWorkspace(previous, "Overwrite") }.isFailure)
        assertTrue(runCatching { backend.client.deleteWorkspace(previous) }.isFailure)
        assertEquals(0, mutations)
      } finally { backend.close(); session.close() }
    }
  }
}
