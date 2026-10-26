package com.igng.lagoon.hub

import com.igng.opencode.lagoon.core.Diagnostics
import com.igng.opencode.lagoon.core.DiagnosticsSink
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/** A running hub: storage, the runtime that watches every server, and the HTTP front door. */
class Hub(val config: HubConfig, version: String) : AutoCloseable {
  val store = HubStore(config.dataDir, SecretBox.load(config.dataDir))
  val auth = Authenticator(store, config.password)
  val streams = StreamRegistry()
  val push: PushService? = if (config.pushEnabled) PushService(store, Vapid.load(store, config.pushSubject)) else null
  val runtime = HubRuntime(store, streams, push)
  val tls: TlsSetup? = TlsSetup.create(config)
  private val router = Router()
  private val api = HubApi(runtime, store, auth, streams, push, tls, version).also { it.register(router) }
  private val server = HubServer(config, router, api, auth, StaticFiles(config.webRoot), tls)

  val port: Int get() = server.port

  fun start() {
    runtime.start()
    server.start()
  }

  override fun close() {
    server.close()
    runtime.close()
    store.close()
  }
}

fun main() {
  val clock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
  Diagnostics.sink = DiagnosticsSink { level, area, message, error ->
    val line = "${LocalDateTime.now().format(clock)} ${level.name.padEnd(4)} [$area] $message" +
      (error?.let { " — ${it::class.java.simpleName}: ${it.message}" } ?: "")
    System.out.println(line)
  }
  val version = Hub::class.java.`package`?.implementationVersion ?: System.getenv("LAGOON_VERSION") ?: "dev"
  val config = HubConfig.fromEnv(System.getenv())
  val hub = try {
    Hub(config, version).also { it.start() }
  } catch (error: Exception) {
    Diagnostics.warn("Hub", "启动失败：${error.message}", error)
    exitProcess(1)
  }
  Runtime.getRuntime().addShutdownHook(Thread { hub.close() })
  Diagnostics.info("Hub", "Lagoon 中枢 $version 已启动：http://${config.bind}:${config.port}" +
    (hub.tls?.let { "，https 端口 ${it.port}" + if (it.selfSigned) "（自签名）" else "" } ?: ""))
  if (!StaticFiles(config.webRoot).available) Diagnostics.warn("Hub", "未找到网页文件：${config.webRoot}")
  if (hub.push == null) Diagnostics.info("Hub", "推送已关闭（LAGOON_PUSH=false）")
}
