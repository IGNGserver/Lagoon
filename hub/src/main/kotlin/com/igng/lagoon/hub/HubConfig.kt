package com.igng.lagoon.hub

import java.nio.file.Path

/** Runtime settings, all from environment variables (see deploy/README.md). */
data class HubConfig(
  val dataDir: Path,
  val webRoot: Path,
  val bind: String,
  val port: Int,
  val password: String?,
  val trustProxy: Boolean,
  val tlsSelfSigned: Boolean,
  val tlsCert: Path?,
  val tlsKey: Path?,
  val tlsPort: Int,
  val tlsHosts: List<String>,
  val pushEnabled: Boolean,
  val pushSubject: String
) {
  companion object {
    fun fromEnv(env: Map<String, String>): HubConfig {
      fun value(name: String) = env[name]?.trim()?.takeIf(String::isNotEmpty)
      fun flag(name: String, default: Boolean) = value(name)?.lowercase()?.let { it in setOf("1", "true", "yes", "on") } ?: default
      fun port(name: String, default: Int) = value(name)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: default
      return HubConfig(
        dataDir = Path.of(value("LAGOON_DATA_DIR") ?: "/data"),
        webRoot = Path.of(value("LAGOON_WEB_ROOT") ?: "/app/web"),
        bind = value("LAGOON_BIND") ?: "0.0.0.0",
        port = port("LAGOON_PORT", 25246),
        // Weak passwords are allowed on purpose; only emptiness is rejected (empty means “not configured”).
        password = env["LAGOON_PASSWORD"]?.takeIf(String::isNotEmpty),
        trustProxy = flag("LAGOON_TRUST_PROXY", false),
        tlsSelfSigned = value("LAGOON_TLS")?.lowercase() == "self-signed",
        tlsCert = value("LAGOON_TLS_CERT")?.let(Path::of),
        tlsKey = value("LAGOON_TLS_KEY")?.let(Path::of),
        tlsPort = port("LAGOON_TLS_PORT", 8443),
        tlsHosts = value("LAGOON_TLS_HOSTS")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty(),
        pushEnabled = flag("LAGOON_PUSH", true),
        pushSubject = value("LAGOON_PUSH_SUBJECT") ?: "https://github.com/IGNGserver/Lagoon"
      )
    }
  }
}
