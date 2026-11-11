package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenCodeProjectManagementTest {
  private fun api(server: MockWebServer) = OpenCodeApi(
    ServerProfile("native", "Native", server.url("/native/").toString().trimEnd('/'), "opencode", allowCleartext = true),
    ServerCredentials(password = "secret"), callTimeoutSeconds = 2)
  private fun project(id: String = "prj_opaque", updated: Long = 20, active: Long = 30) = JSONObject()
    .put("id", id).put("canonical", "/repo").put("name", "").put("vcs", "git")
    .put("icon", JSONObject().put("url", "https://example.test/icon").put("override", "old").put("color", "blue"))
    .put("commands", JSONObject().put("start", "secret-start-command"))
    .put("time", JSONObject().put("created", 10).put("updated", updated).put("active", active))
    .put("sandboxes", JSONArray().put("/trees/one"))
  private fun projects(value: JSONObject = project()) = JSONArray().put(value).toString()
  private val expected get() = OpenCodeProjectInfo("prj_opaque", "/repo", "", "git", "https://example.test/icon", "old", "blue",
    "secret-start-command", 10, 20, 30, null, listOf("/trees/one"))
  private fun MockWebServer.ready(client: OpenCodeApi) = runBlocking {
    enqueue(MockResponse().setBody(document().toString()))
    assertTrue(client.discoverCapabilities().openCodeProjectManagement)
    assertTrue(client.capabilities().openCodeWorktreeManagement)
    assertEquals("/native/openapi.json", takeRequest().path)
  }

  @Test fun discoveryIsRequiredAndFreshNativeProjectsIncludeArchivedWithBlankOptionalMetadata() = runBlocking {
    MockWebServer().use { server ->
      val client = api(server)
      try {
        assertNull(client.openCodeProjects()); assertNull(client.updateOpenCodeProject(expected, OpenCodeProjectEdit(name = "x")))
        assertNull(client.openCodeWorktrees(expected)); assertNull(client.createOpenCodeWorktree(expected, OpenCodeWorktreeCreate()))
        assertNull(client.removeOpenCodeWorktree(expected, OpenCodeWorktree("/trees/one", "git")))
        assertNull(client.refreshOpenCodeWorktrees(expected)); assertEquals(0, server.requestCount)
        server.ready(client)
        repeat(2) {
          server.enqueue(MockResponse().setBody(projects()))
          assertEquals(expected, client.openCodeProjects()!!.single())
          val read = server.takeRequest()
          assertEquals("GET", read.method); assertEquals("/native/api/project?archived=true", read.path)
          assertNotNull(read.getHeader("Authorization"))
        }
        assertFalse(expected.toString().contains("secret-start-command"))
        assertFalse(OpenCodeProjectEdit(startCommand = "secret-start-command").toString().contains("secret-start-command"))
      } finally { client.close() }
    }
  }

  @Test fun malformedOrCoercedNativePayloadsNeverBecomeAnEmptyOrPartialCatalog() = runBlocking {
    MockWebServer().use { server ->
      val client = api(server)
      try {
        server.ready(client)
        val bad = listOf("", "{}", "[{}]", "[] trailing", "[{'id':'bad'}]", "[true]",
          projects(project().put("id", 17)), projects(project().put("canonical", "relative")),
          projects(project().put("name", false)), projects(project().put("sandboxes", JSONArray().put(7))),
          projects(project().put("time", JSONObject().put("created", "10").put("updated", 20).put("active", 30))),
          projects(project().put("time", JSONObject().put("created", 10).put("updated", -1).put("active", 30))),
          projects(project().put("time", JSONObject().put("created", 10).put("updated", 20).put("active", 9_007_199_254_740_992L))),
          JSONArray().put(project()).put(project()).toString())
        for (body in bad) {
          server.enqueue(MockResponse().setBody(body))
          assertEquals(502, (runCatching { client.openCodeProjects() }.exceptionOrNull() as? ApiException)?.status)
          server.takeRequest()
        }
        server.enqueue(MockResponse().setBody(JSONObject().put("data", JSONArray().put(project())).toString()))
        assertEquals(expected, client.openCodeProjects()!!.single())
      } finally { client.close() }
    }
  }

  @Test fun metadataPatchOnlyWritesDirtyNativeFieldsAndRequiresSameIdentityAck(): Unit = runBlocking {
    MockWebServer().use { server ->
      val client = api(server)
      try {
        server.ready(client)
        // Activity can advance without invalidating a metadata snapshot.
        server.enqueue(MockResponse().setBody(projects(project(active = 99))))
        server.enqueue(MockResponse().setBody(project(updated = 21).put("name", "New").put("icon", JSONObject().put("override", ""))
          .put("commands", JSONObject().put("start", "")).toString()))
        val ack = client.updateOpenCodeProject(expected, OpenCodeProjectEdit(name = "New", iconOverride = "", startCommand = ""))!!
        assertEquals("New", ack.name); assertEquals("", ack.startCommand)
        assertEquals("GET", server.takeRequest().method)
        val write = server.takeRequest()
        assertEquals("PATCH", write.method); assertEquals("/native/api/project/prj_opaque", write.path)
        val body = JSONObject(write.body.readUtf8())
        assertEquals(setOf("name", "icon", "commands"), body.keys().asSequence().toSet())
        assertEquals(setOf("override"), body.getJSONObject("icon").keys().asSequence().toSet())
        assertEquals("", body.getJSONObject("commands").getString("start"))
        server.enqueue(MockResponse().setBody(projects()))
        server.enqueue(MockResponse().setBody(project("other-id").toString()))
        assertEquals(502, (runCatching { client.updateOpenCodeProject(expected, OpenCodeProjectEdit(name = "Other")) }.exceptionOrNull() as? ApiException)?.status)
        server.takeRequest(); server.takeRequest()
      } finally { client.close() }
    }
  }

  @Test fun staleMetadataAndInvalidInputsAbortBeforeAnyWrite() = runBlocking {
    MockWebServer().use { server ->
      val client = api(server)
      try {
        server.ready(client)
        server.enqueue(MockResponse().setBody(projects(project(updated = 21))))
        assertEquals(409, (runCatching { client.updateOpenCodeProject(expected, OpenCodeProjectEdit(name = "New")) }.exceptionOrNull() as? ApiException)?.status)
        assertEquals("GET", server.takeRequest().method)
        for (input in listOf(OpenCodeWorktreeCreate(name = "../escape"), OpenCodeWorktreeCreate(name = ".."),
          OpenCodeWorktreeCreate(branch = " "), OpenCodeWorktreeCreate(parentDirectory = "relative"))) {
          assertEquals(400, (runCatching { client.createOpenCodeWorktree(expected, input) }.exceptionOrNull() as? ApiException)?.status)
        }
        assertEquals(400, (runCatching { client.updateOpenCodeProject(expected, OpenCodeProjectEdit(canonical = "relative")) }.exceptionOrNull() as? ApiException)?.status)
        assertEquals(2, server.requestCount)
      } finally { client.close() }
    }
  }

  @Test fun unaddressableProjectIdsAreRejectedBeforeThePathOrAnyWrite() = runBlocking {
    MockWebServer().use { server ->
      val client = api(server)
      try {
        server.ready(client)
        for (id in listOf("project/opaque", "back\\slash", "percent%2Fslash", ".", "..")) {
          assertEquals(id, 400, (runCatching {
            client.updateOpenCodeProject(expected.copy(id = id), OpenCodeProjectEdit(name = "x"))
          }.exceptionOrNull() as? ApiException)?.status)
        }
        // Reads stay available: the ID is only a query/body value there.
        server.enqueue(MockResponse().setBody(projects(project().put("id", "project/opaque"))))
        assertEquals("project/opaque", client.openCodeProjects()!!.single().id)
        // Only discovery plus this catalog read; none of the rejected IDs reached the wire.
        assertEquals(2, server.requestCount)
      } finally { client.close() }
    }
  }

  @Test fun nativeInventoryAndCreateKeepParentDirectoryDistinctFromCanonicalOutput() = runBlocking {
    MockWebServer().use { server ->
      val client = api(server)
      try {
        server.ready(client)
        val inventory = """[{"directory":"/repo"},{"directory":"/trees/one","strategy":"git"}]"""
        server.enqueue(MockResponse().setBody(projects())); server.enqueue(MockResponse().setBody(inventory))
        assertEquals(listOf(OpenCodeWorktree("/repo", null), OpenCodeWorktree("/trees/one", "git")), client.openCodeWorktrees(expected))
        server.takeRequest(); val listing = server.takeRequest()
        assertEquals("/native/api/worktree?projectID=prj_opaque", listing.path)
        server.enqueue(MockResponse().setBody(projects())); server.enqueue(MockResponse().setBody(inventory))
        server.enqueue(MockResponse().setBody("""{"directory":"/parent/server-selected"}"""))
        assertEquals(OpenCodeWorktree("/parent/server-selected", null), client.createOpenCodeWorktree(expected,
          OpenCodeWorktreeCreate(from = "/trees/one", branch = "topic", parentDirectory = "/parent", name = "chosen")))
        server.takeRequest(); server.takeRequest(); val write = server.takeRequest()
        assertEquals("POST", write.method)
        val body = JSONObject(write.body.readUtf8())
        assertEquals("/parent", body.getString("directory")); assertEquals("/trees/one", body.getString("from"))
        assertEquals("chosen", body.getString("name")); assertEquals(expected.id, body.getString("projectID"))
        server.enqueue(MockResponse().setBody(projects())); server.enqueue(MockResponse().setResponseCode(400)
          .setBody("""{"name":"WorktreeError","data":{"message":"setup failed"}}"""))
        assertEquals(400, (runCatching { client.createOpenCodeWorktree(expected, OpenCodeWorktreeCreate()) }.exceptionOrNull() as? ApiException)?.status)
        server.takeRequest(); assertEquals("POST", server.takeRequest().method)
        assertNull(server.takeRequest(50, TimeUnit.MILLISECONDS))
      } finally { client.close() }
    }
  }

  @Test fun deleteRechecksOwnershipSendsFalseForceAndPreservesForceRequiredWithoutEscalation() = runBlocking {
    MockWebServer().use { server ->
      val client = api(server)
      try {
        server.ready(client)
        val target = OpenCodeWorktree("/trees/one", "git")
        server.enqueue(MockResponse().setBody(projects()))
        server.enqueue(MockResponse().setBody("""[{"directory":"/trees/one","strategy":"git"}]"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"name":"WorktreeError","data":{"message":"dirty","forceRequired":true}}"""))
        val error = runCatching { client.removeOpenCodeWorktree(expected, target) }.exceptionOrNull() as? ApiException
        assertEquals("WorktreeError", error?.code); assertEquals(true, error?.details?.opt("forceRequired"))
        server.takeRequest(); server.takeRequest(); val write = server.takeRequest()
        assertEquals("DELETE", write.method)
        val body = JSONObject(write.body.readUtf8())
        assertEquals(false, body.get("force")); assertEquals(expected.id, body.getString("projectID"))
        assertEquals(target.directory, body.getString("directory"))
        assertEquals(400, (runCatching { client.removeOpenCodeWorktree(expected, OpenCodeWorktree("/repo", null)) }.exceptionOrNull() as? ApiException)?.status)
        server.enqueue(MockResponse().setBody(projects())); server.enqueue(MockResponse().setBody("""[{"directory":"/trees/one","strategy":"other"}]"""))
        assertEquals(409, (runCatching { client.removeOpenCodeWorktree(expected, target) }.exceptionOrNull() as? ApiException)?.status)
        server.takeRequest(); server.takeRequest()
        assertNull(server.takeRequest(50, TimeUnit.MILLISECONDS))
      } finally { client.close() }
    }
  }

  @Test fun refreshIsOneNativeWriteAndAcceptsEmpty204() = runBlocking {
    MockWebServer().use { server ->
      val client = api(server)
      try {
        server.ready(client)
        server.enqueue(MockResponse().setBody(projects())); server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(true, client.refreshOpenCodeWorktrees(expected))
        assertEquals("GET", server.takeRequest().method)
        val write = server.takeRequest()
        assertEquals("POST", write.method); assertEquals("/native/api/worktree/refresh", write.path)
        assertEquals(setOf("projectID"), JSONObject(write.body.readUtf8()).keys().asSequence().toSet())
        assertEquals(3, server.requestCount)
      } finally { client.close() }
    }
  }

  @Test fun unknownCreationOutcomesAndRejectedWritesNeverReplay() = runBlocking {
    for (status in listOf(0, 401, 503)) MockWebServer().use { server ->
      val client = api(server)
      try {
        server.ready(client)
        server.enqueue(MockResponse().setBody(projects()))
        server.enqueue(if (status == 0) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
          else MockResponse().setResponseCode(status).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setBody("""{"directory":"/duplicate"}"""))
        val error = runCatching { client.createOpenCodeWorktree(expected, OpenCodeWorktreeCreate()) }.exceptionOrNull()
        assertTrue(error is IOException)
        if (status != 0) assertEquals(status, (error as? ApiException)?.status)
        assertEquals("GET", server.takeRequest().method); assertEquals("POST", server.takeRequest().method)
        assertEquals(3, server.requestCount); assertNull(server.takeRequest(50, TimeUnit.MILLISECONDS))
      } finally { client.close() }
    }
  }

  companion object {
    fun document(): JSONObject {
      fun string() = JSONObject().put("anyOf", JSONArray().put(JSONObject().put("type", "null"))
        .put(JSONObject().put("${'$'}ref", "#/components/schemas/Text")))
      fun shape(fields: Map<String, JSONObject>, required: List<String> = emptyList()) = JSONObject().put("type", "object")
        .put("properties", JSONObject(fields)).put("required", JSONArray(required)).put("additionalProperties", false)
      fun operation(input: JSONObject) = JSONObject().put("requestBody", JSONObject().put("required", true)
        .put("content", JSONObject().put("application/json", JSONObject().put("schema", input))))
      val patch = shape(mapOf("canonical" to string(), "name" to string(),
        "icon" to shape(mapOf("override" to string(), "color" to string())),
        "commands" to shape(mapOf("start" to string()))))
      val create = shape(listOf("projectID", "from", "branch", "directory", "name").associateWith { string() }, listOf("projectID"))
      val remove = shape(mapOf("projectID" to string(), "directory" to string(), "force" to JSONObject().put("type", "boolean")), listOf("projectID", "directory", "force"))
      val refresh = shape(mapOf("projectID" to string()), listOf("projectID"))
      return JSONObject().put("paths", JSONObject()
        .put("/api/project", JSONObject().put("get", JSONObject()))
        .put("/api/project/{id}", JSONObject().put("patch", operation(patch)))
        .put("/api/worktree", JSONObject().put("get", JSONObject().put("parameters", JSONArray().put(JSONObject()
          .put("in", "query").put("name", "projectID").put("required", true).put("schema", string()))))
          .put("post", operation(create)).put("delete", operation(remove)))
        .put("/api/worktree/refresh", JSONObject().put("post", operation(refresh))))
        .put("components", JSONObject().put("schemas", JSONObject().put("Text", JSONObject().put("type", "string"))))
    }
  }
}
