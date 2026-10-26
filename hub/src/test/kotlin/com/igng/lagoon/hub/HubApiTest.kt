package com.igng.lagoon.hub

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The hub end to end: login, adding an OpenCode server, live state, an open conversation and sending. */
class HubApiTest {
  private val harness = MockWebServer()
  private val prompts = CopyOnWriteArrayList<JSONObject>()
  private lateinit var dataDir: Path
  private lateinit var hub: Hub
  private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
  private var cookie = ""

  @Before fun start() {
    harness.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath
        if (path == "/api/event") {
          return MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"type\":\"server.connected\",\"data\":{}}\n\n")
        }
        if (path == "/api/session/s/prompt") {
          prompts += JSONObject(request.body.readUtf8())
          return MockResponse().setResponseCode(204)
        }
        val body = when (path) {
          "/api/info" -> """{"version":"2.0.22"}"""
          "/openapi.json" -> return MockResponse().setResponseCode(404).setBody("{}")
          "/api/session" -> """{"data":[$SESSION],"cursor":{}}"""
          "/api/session/s" -> """{"data":$SESSION}"""
          "/api/session/active" -> """{"data":{}}"""
          "/api/session/s/message" -> """{"data":[
            {"id":"msg_2","type":"assistant","content":[{"type":"text","text":"你好"}],"time":{"created":2,"completed":3}},
            {"id":"msg_1","type":"user","text":"hi","time":{"created":1}}],"cursor":{}}"""
          else -> """{"data":[]}"""
        }
        return MockResponse().setBody(body)
      }
    }
    harness.start()
    dataDir = Files.createTempDirectory("hub-api")
    val web = Files.createDirectories(dataDir.resolve("web"))
    Files.writeString(web.resolve("index.html"), "<!doctype html><title>Lagoon</title>")
    hub = Hub(HubConfig.fromEnv(mapOf("LAGOON_DATA_DIR" to dataDir.resolve("data").toString(), "LAGOON_WEB_ROOT" to web.toString(),
      "LAGOON_BIND" to "127.0.0.1", "LAGOON_PORT" to "0", "LAGOON_PASSWORD" to "1")).copy(port = 0), "test")
    hub.start()
  }

  @After fun stop() {
    hub.close()
    harness.shutdown()
  }

  private fun url(path: String) = "http://127.0.0.1:${hub.port}$path"

  private fun call(method: String, path: String, body: JSONObject? = null, origin: String? = null): Response {
    val builder = Request.Builder().url(url(path)).header("Cookie", cookie)
    origin?.let { builder.header("Origin", it) }
    val requestBody = body?.toString()?.toRequestBody("application/json".toMediaType())
      ?: if (method == "GET") null else "".toRequestBody("application/json".toMediaType())
    return http.newCall(builder.method(method, requestBody).build()).execute()
  }

  private fun json(method: String, path: String, body: JSONObject? = null): JSONObject = call(method, path, body).use { response ->
    val text = response.body!!.string()
    assertTrue("$method $path → ${response.code} $text", response.isSuccessful)
    JSONObject(text)
  }

  private fun login() {
    call("POST", "/api/v1/auth/login", JSONObject().put("password", "1")).use { response ->
      assertEquals(200, response.code)
      cookie = response.header("Set-Cookie")!!.substringBefore(';')
    }
  }

  private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
      check(System.currentTimeMillis() < deadline) { "条件未在时限内满足" }
      Thread.sleep(50)
    }
  }

  private fun addServer(): String {
    val saved = json("POST", "/api/v1/servers", JSONObject()
      .put("profile", JSONObject().put("name", "Dev").put("url", harness.url("/").toString().trimEnd('/')).put("kind", "opencode").put("allowCleartext", true))
      .put("secret", "pw"))
    assertEquals("2.0.22", saved.getString("version"))
    return saved.getJSONObject("profile").getString("id")
  }

  @Test fun signedOutBrowsersOnlySeeTheLoginEndpoints() {
    assertEquals(401, call("GET", "/api/v1/state").use { it.code })
    val status = json("GET", "/api/v1/auth/status")
    assertFalse(status.getBoolean("authenticated"))
    assertTrue(status.getBoolean("passwordFromEnv"))
    assertEquals(401, call("POST", "/api/v1/auth/login", JSONObject().put("password", "2")).use { it.code })
    login()
    assertTrue(json("GET", "/api/v1/auth/status").getBoolean("authenticated"))
    call("POST", "/api/v1/auth/logout").close()
    assertEquals(401, call("GET", "/api/v1/state").use { it.code })
  }

  @Test fun repeatedWrongPasswordsAreThrottled() {
    repeat(LoginThrottle.FREE_ATTEMPTS) { call("POST", "/api/v1/auth/login", JSONObject().put("password", "x")).close() }
    call("POST", "/api/v1/auth/login", JSONObject().put("password", "1")).use { response ->
      assertEquals(429, response.code)
      assertNotNull(response.header("Retry-After"))
    }
  }

  @Test fun crossSiteWritesAreRefused() {
    login()
    assertEquals(403, call("POST", "/api/v1/servers/parse", JSONObject().put("text", "x"), origin = "https://evil.example").use { it.code })
    assertEquals(200, call("POST", "/api/v1/servers/parse", JSONObject().put("text", "x"), origin = url("")).use { it.code })
  }

  @Test fun pastedLinksAreRecognized() {
    login()
    val dsh = json("POST", "/api/v1/servers/parse", JSONObject().put("text", "http://192.168.1.5:3080/?token=abc123"))
    assertEquals("dsh", dsh.getString("kind"))
    assertEquals("abc123", dsh.getString("secret"))
    assertTrue(dsh.getBoolean("allowCleartext"))
  }

  @Test fun theWebClientIsServedWithStrictHeaders() {
    http.newCall(Request.Builder().url(url("/s/a/b")).build()).execute().use { response ->
      assertEquals(200, response.code)
      assertTrue(response.body!!.string().contains("<title>Lagoon"))
      assertTrue(response.header("Content-Security-Policy")!!.contains("default-src 'self'"))
    }
    assertEquals(404, http.newCall(Request.Builder().url(url("/assets/missing.js")).build()).execute().use { it.code })
    assertEquals(200, http.newCall(Request.Builder().url(url("/healthz")).build()).execute().use { it.code })
  }

  @Test fun aServerIsMonitoredAndAConversationStreams() {
    login()
    val id = addServer()
    // The secret is stored but never returned.
    assertFalse(json("GET", "/api/v1/servers").toString().contains("pw"))
    waitFor {
      val server = json("GET", "/api/v1/state").getJSONArray("servers").optJSONObject(0)
      server != null && server.getBoolean("connected") && server.getJSONArray("sessions").length() == 1
    }

    val events = LinkedBlockingQueue<Pair<String, JSONObject>>()
    val stream = http.newCall(Request.Builder().url(url("/api/v1/events")).header("Cookie", cookie).build()).execute()
    val reader = Thread {
      runCatching {
        val source = stream.body!!.source()
        var event = ""
        while (true) {
          val line = source.readUtf8Line() ?: break
          when {
            line.startsWith("event: ") -> event = line.removePrefix("event: ")
            line.startsWith("data: ") -> events.put(event to JSONObject(line.removePrefix("data: ")))
          }
        }
      }
    }.apply { isDaemon = true; start() }
    try {
      fun next(name: String): JSONObject {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
          val (event, data) = events.poll(200, TimeUnit.MILLISECONDS) ?: continue
          if (event == name) return data
        }
        error("没有收到 $name")
      }
      val streamId = next("hello").getString("streamId")
      val state = next("state")
      assertEquals(id, state.getJSONArray("servers").getJSONObject(0).getString("serverId"))

      json("PUT", "/api/v1/streams/$streamId/watch", JSONObject().put("visible", true)
        .put("conversations", JSONArray().put(JSONObject().put("serverId", id).put("sessionId", "s"))))
      var reset = next("conv.reset")
      while (reset.getBoolean("loading")) reset = next("conv.reset")
      val ids = reset.getJSONArray("messages").let { list -> (0 until list.length()).map { list.getJSONObject(it).getString("id") } }
      assertEquals(listOf("msg_1", "msg_2"), ids)

      val sent = json("POST", "/api/v1/servers/$id/sessions/s/send", JSONObject().put("text", "继续"))
      waitFor { prompts.isNotEmpty() }
      assertTrue(prompts.single().toString().contains("继续"))
      // The prompt appears in the open conversation at once, before the server echoes it.
      val ops = next("conv.ops").getJSONArray("ops")
      val put = (0 until ops.length()).map(ops::getJSONObject).first { it.getString("op") == "put" }
      assertEquals(sent.getString("id"), put.getJSONObject("message").getString("id"))
    } finally {
      // Cancel rather than close: closing would drain the endless body on this thread.
      http.dispatcher.cancelAll()
      reader.join(2_000)
      runCatching { stream.close() }
    }
  }

  @Test fun removingAServerForgetsIt() {
    login()
    val id = addServer()
    json("DELETE", "/api/v1/servers/$id")
    assertEquals(0, json("GET", "/api/v1/servers").getJSONArray("servers").length())
    assertEquals(404, call("GET", "/api/v1/servers/$id/sessions").use { it.code })
  }

  companion object {
    private const val SESSION = """{"id":"s","title":"会话","location":{"directory":"/repo"},"time":{"created":1,"updated":2,"idle":0}}"""
  }
}
