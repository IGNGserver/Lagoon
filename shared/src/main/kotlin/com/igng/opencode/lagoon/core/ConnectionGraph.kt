package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.security.MessageDigest

/** Source, carrier and native protocol are independent identities, never inferred from a label. */
enum class ConnectionSourceType(val wire: String) { HUB("hub"), SSH("ssh"), DIRECT("direct");
  companion object { fun fromWire(value: String): ConnectionSourceType = entries.firstOrNull { it.wire == value }
    ?: throw IllegalArgumentException("Unknown connection source: $value") }
}
enum class HarnessProtocolId(val wire: String) { OPENCODE_NATIVE("opencode-native"), DSH_TYPERT("dsh-typert"), CODEX_APP_SERVER("codex-app-server");
  companion object { fun fromWire(value: String): HarnessProtocolId = entries.firstOrNull { it.wire == value }
    ?: throw IllegalArgumentException("Unknown harness protocol: $value") }
}
enum class ConnectionCarrierId(val wire: String) { DIRECT_HTTP("direct-http"), SSH_FORWARD("ssh-forward"), REMOTE_NOISE("remote-noise");
  companion object { fun fromWire(value: String): ConnectionCarrierId = entries.firstOrNull { it.wire == value }
    ?: throw IllegalArgumentException("Unknown connection carrier: $value") }
}
enum class DiscoveryValue(val wire: String) { UNKNOWN("unknown"), YES("yes"), NO("no"), UNSUPPORTED("unsupported");
  companion object { fun fromWire(value: String): DiscoveryValue = entries.firstOrNull { it.wire == value }
    ?: throw IllegalArgumentException("Unknown discovery value: $value") }
}
enum class DiscoveryCompleteness(val wire: String) {
  UNKNOWN("unknown"), PARTIAL("partial"), COMPLETE("complete");

  companion object {
    fun fromWire(value: String): DiscoveryCompleteness = entries.firstOrNull { it.wire == value }
      ?: throw IllegalArgumentException("Unknown discovery completeness: $value")
  }
}
enum class DiscoveryFreshness { UNKNOWN, FRESH, STALE }

/** Read-only observations, not a user's enable preference or an instruction to install/start anything. */
data class HarnessDiscoveryStatus(
  val installed: DiscoveryValue = DiscoveryValue.UNKNOWN,
  val configured: DiscoveryValue = DiscoveryValue.UNKNOWN,
  val listening: DiscoveryValue = DiscoveryValue.UNKNOWN,
  val authenticated: DiscoveryValue = DiscoveryValue.UNKNOWN,
  val compatible: DiscoveryValue = DiscoveryValue.UNKNOWN,
  val available: DiscoveryValue = DiscoveryValue.UNKNOWN,
  val observedAtMs: Long? = null,
  val freshUntilMs: Long? = null,
  val completeness: DiscoveryCompleteness = DiscoveryCompleteness.UNKNOWN,
  val reason: String? = null
) {
  init {
    require((observedAtMs == null) == (freshUntilMs == null)) { "Observation timestamps must be paired" }
    require(observedAtMs == null || observedAtMs >= 0 && freshUntilMs!! >= observedAtMs) { "Invalid observation timestamps" }
    require(completeness == DiscoveryCompleteness.UNKNOWN || observedAtMs != null) { "A scan needs an observation time" }
    require(available != DiscoveryValue.YES || (observedAtMs != null &&
      listening == DiscoveryValue.YES && authenticated == DiscoveryValue.YES && compatible == DiscoveryValue.YES &&
      installed != DiscoveryValue.NO && configured != DiscoveryValue.NO)) { "Availability requires verified listening/authentication/compatibility" }
  }
  fun freshness(nowMs: Long): DiscoveryFreshness = when {
    observedAtMs == null || nowMs < observedAtMs -> DiscoveryFreshness.UNKNOWN
    nowMs > freshUntilMs!! -> DiscoveryFreshness.STALE
    else -> DiscoveryFreshness.FRESH
  }
  fun availableNow(nowMs: Long): Boolean = available == DiscoveryValue.YES && freshness(nowMs) == DiscoveryFreshness.FRESH
}

/** Saved route configuration. [endpoint] is a locator/display value, never permanent Host identity. */
data class ConnectionSource(
  val id: String,
  val type: ConnectionSourceType,
  val name: String,
  val endpoint: String,
  val account: String? = null,
  val sshHostConfigId: String? = null,
  val enabled: Boolean = true,
  val allowCleartext: Boolean = false
) {
  init {
    graphId(id); require(endpoint.isNotBlank()) { "Source endpoint is empty" }
    require(type == ConnectionSourceType.HUB || account == null) { "Only Hub sources have an account scope" }
    require(type != ConnectionSourceType.HUB || !account.isNullOrBlank()) { "Hub source needs an account scope" }
    require((type == ConnectionSourceType.SSH) == (sshHostConfigId != null)) { "SSH source needs its saved config ID" }
    sshHostConfigId?.let(::graphId)
  }
}

/** Route-bound Host, not a global physical-machine merge. Fingerprint is retained evidence, not a trust verdict. */
data class HostBinding(
  val id: String,
  val sourceId: String,
  val name: String,
  val externalHostId: String? = null,
  val fingerprint: String? = null,
  val enabled: Boolean = true
) {
  init { graphId(id); graphId(sourceId); externalHostId?.let(::graphId) }
}

enum class ConnectionSlotStore(val wire: String) { SERVER_SECRETS("server-secrets"), SSH_SECRETS("ssh-secrets"), REMOTE_DEVICES("remote-devices");
  companion object { fun fromWire(value: String): ConnectionSlotStore = entries.firstOrNull { it.wire == value }
    ?: throw IllegalArgumentException("Unknown credential store: $value") }
}
/** Exact existing key, no credential bytes. Absence of a record is not equivalent to an empty secret. */
data class ConnectionSlotRef(val store: ConnectionSlotStore, val key: String) { init { graphId(key) } }
/** Ordered exact candidate keys only; null selected is explicitly unresolved. */
data class RemoteIdentitySlotRefs(val candidates: List<ConnectionSlotRef>, val selected: ConnectionSlotRef? = null) {
  init {
    require(candidates.isNotEmpty() && candidates.distinct().size == candidates.size) { "Invalid Remote identity candidates" }
    require(candidates.all { it.store == ConnectionSlotStore.REMOTE_DEVICES }) { "Wrong Remote identity store" }
    require(selected == null || selected in candidates) { "Selected identity must be an exact candidate" }
  }
  val resolved: Boolean get() = selected != null
}
data class ConnectionCredentialRefs(
  val profileSecret: ConnectionSlotRef? = null,
  val sshSecrets: List<ConnectionSlotRef> = emptyList(),
  val remoteIdentity: RemoteIdentitySlotRefs? = null
) {
  init {
    require(profileSecret == null || profileSecret.store == ConnectionSlotStore.SERVER_SECRETS)
    require(sshSecrets.all { it.store == ConnectionSlotStore.SSH_SECRETS } && sshSecrets.distinct().size == sshSecrets.size)
  }
}

/**
 * A local leaf. For migrated profiles [id] is EXACTLY ServerProfile.id, preserving drafts, caches,
 * session targets, notifications and existing groups. serviceId is optional authenticated durable
 * service identity; URLs, ports, names and process instance IDs must never be substituted for it.
 */
data class HarnessBinding(
  val id: String,
  val hostBindingId: String,
  val name: String,
  val protocolId: HarnessProtocolId,
  val carrierId: ConnectionCarrierId,
  val serviceId: String? = null,
  val enabled: Boolean = true,
  val autoConnect: Boolean = true,
  val notifications: Boolean = true,
  val discovery: HarnessDiscoveryStatus = HarnessDiscoveryStatus(),
  val credentials: ConnectionCredentialRefs = ConnectionCredentialRefs(),
  val legacyProfile: ServerProfile? = null
) {
  init {
    graphId(id); graphId(hostBindingId); serviceId?.let(::graphId)
    require(legacyProfile == null || legacyProfile.id == id) { "Legacy profile identity changed" }
  }
}

/** References only stable leaf IDs. Repeated references resolve once, in first-occurrence order. */
data class BindingComposition(val id: String, val name: String, val bindingIds: List<String>) {
  init { graphId(id); bindingIds.forEach(::graphId) }
  fun deduplicated(): BindingComposition = copy(bindingIds = bindingIds.distinct())
}

data class ConnectionGraph(
  val sources: List<ConnectionSource> = emptyList(),
  val hosts: List<HostBinding> = emptyList(),
  val bindings: List<HarnessBinding> = emptyList(),
  val compositions: List<BindingComposition> = emptyList()
) {
  init { ConnectionGraphInvariants.requireValid(this) }

  /** Local selection only: observations never rewrite any of these three preferences. */
  fun effectiveEnabled(bindingId: String): Boolean {
    val binding = bindings.firstOrNull { it.id == bindingId } ?: return false
    val host = hosts.firstOrNull { it.id == binding.hostBindingId } ?: return false
    val source = sources.firstOrNull { it.id == host.sourceId } ?: return false
    // An SSH source is one saved config and one device: the device switch carries its intent, so the
    // source-level flag is never a second gate (see docs/DEVICE_MODEL.md).
    return (source.enabled || source.type == ConnectionSourceType.SSH) && host.enabled && binding.enabled
  }
  fun compositionBindings(compositionId: String, enabledOnly: Boolean = false): List<HarnessBinding> {
    val members = compositions.firstOrNull { it.id == compositionId }?.bindingIds.orEmpty().distinct()
    val byId = bindings.associateBy { it.id }
    return members.mapNotNull(byId::get).filter { !enabledOnly || effectiveEnabled(it.id) }
  }
}

/** Pure validation; no routing, dialing, secret resolution, store write or authority merge. */
object ConnectionGraphInvariants {
  fun requireValid(graph: ConnectionGraph) {
    fun unique(ids: List<String>, what: String) { require(ids.distinct().size == ids.size) { "Duplicate $what IDs" } }
    unique(graph.sources.map { it.id }, "source"); unique(graph.hosts.map { it.id }, "host")
    unique(graph.bindings.map { it.id }, "binding"); unique(graph.compositions.map { it.id }, "composition")
    val sources = graph.sources.associateBy { it.id }; val hosts = graph.hosts.associateBy { it.id }
    val bindings = graph.bindings.associateBy { it.id }
    graph.hosts.forEach { host ->
      requireNotNull(sources[host.sourceId]) { "Dangling source reference" }
    }
    graph.bindings.forEach { binding ->
      val host = requireNotNull(hosts[binding.hostBindingId]) { "Dangling Host reference" }
      val source = requireNotNull(sources[host.sourceId])
      val expected = when (source.type) {
        ConnectionSourceType.HUB -> ConnectionCarrierId.REMOTE_NOISE
        ConnectionSourceType.SSH -> ConnectionCarrierId.SSH_FORWARD
        ConnectionSourceType.DIRECT -> ConnectionCarrierId.DIRECT_HTTP
      }
      require(binding.carrierId == expected) { "Carrier/source mismatch" }
      require(binding.credentials.remoteIdentity == null || source.type == ConnectionSourceType.HUB)
      require(binding.credentials.sshSecrets.isEmpty() || source.type == ConnectionSourceType.SSH)
      binding.legacyProfile?.let { profile ->
        require(MigrationProjection.protocol(profile.kind) == binding.protocolId) { "Legacy protocol changed" }
        require(MigrationProjection.sourceType(profile) == source.type) { "Legacy source changed" }
      }
    }
    graph.compositions.forEach { require(it.bindingIds.all(bindings::containsKey)) { "Dangling composition reference" } }
  }
}

/** Version-one wire document. Decode is all-or-nothing and rejects unknown values. */
object ConnectionGraphCodec {
  const val VERSION = 1
  fun encode(graph: ConnectionGraph): String {
    ConnectionGraphInvariants.requireValid(graph)
    return JSONObject().put("version", VERSION)
      .put("sources", jsonArray(graph.sources) { source -> JSONObject().put("id", source.id).put("type", source.type.wire)
        .put("name", source.name).put("endpoint", source.endpoint).putOpt("account", source.account)
        .putOpt("sshHostConfigId", source.sshHostConfigId).put("enabled", source.enabled).put("allowCleartext", source.allowCleartext) })
      .put("hosts", jsonArray(graph.hosts) { host -> JSONObject().put("id", host.id).put("sourceId", host.sourceId)
        .put("name", host.name).putOpt("externalHostId", host.externalHostId).putOpt("fingerprint", host.fingerprint).put("enabled", host.enabled) })
      .put("bindings", jsonArray(graph.bindings, ::bindingJson))
      .put("compositions", jsonArray(graph.compositions) { group -> JSONObject().put("id", group.id).put("name", group.name)
        .put("bindingIds", JSONArray(group.bindingIds)) }).toString()
  }

  fun decode(text: String): ConnectionGraph = try {
    require(text.length <= 8 * 1024 * 1024) { "Graph document too large" }
    val tokener = JSONTokener(text)
    val root = tokener.nextValue() as? JSONObject ?: error("Graph must be an object")
    require(tokener.nextClean() == '\u0000') { "Trailing graph content" }
    require(root.graphLong("version") == VERSION.toLong()) { "Unsupported graph version" }
    ConnectionGraph(
      sources = root.graphObjects("sources").map { source -> ConnectionSource(source.graphString("id"),
        ConnectionSourceType.fromWire(source.graphString("type")), source.graphString("name"), source.graphString("endpoint"),
        source.graphOptionalString("account"), source.graphOptionalString("sshHostConfigId"), source.graphBoolean("enabled"), source.graphBoolean("allowCleartext")) },
      hosts = root.graphObjects("hosts").map { host -> HostBinding(host.graphString("id"), host.graphString("sourceId"), host.graphString("name"),
        host.graphOptionalString("externalHostId"), host.graphOptionalString("fingerprint"), host.graphBoolean("enabled")) },
      bindings = root.graphObjects("bindings").map(::readBinding),
      compositions = root.graphObjects("compositions").map { group -> BindingComposition(group.graphString("id"), group.graphString("name"), group.graphStrings("bindingIds")) }
    )
  } catch (error: Exception) { throw IOException("Connection graph is unreadable; retain the original document", error) }

  private fun bindingJson(binding: HarnessBinding): JSONObject = JSONObject().put("id", binding.id).put("hostBindingId", binding.hostBindingId)
    .put("name", binding.name).put("protocolId", binding.protocolId.wire).put("carrierId", binding.carrierId.wire)
    .putOpt("serviceId", binding.serviceId).put("enabled", binding.enabled).put("autoConnect", binding.autoConnect).put("notifications", binding.notifications)
    .put("discovery", discoveryJson(binding.discovery)).put("credentials", credentialsJson(binding.credentials))
    .putOpt("legacyProfile", binding.legacyProfile?.let(::profileJson))

  private fun readBinding(value: JSONObject): HarnessBinding = HarnessBinding(value.graphString("id"), value.graphString("hostBindingId"), value.graphString("name"),
    HarnessProtocolId.fromWire(value.graphString("protocolId")), ConnectionCarrierId.fromWire(value.graphString("carrierId")), value.graphOptionalString("serviceId"),
    value.graphBoolean("enabled"), value.graphBoolean("autoConnect"), value.graphBoolean("notifications"), readDiscovery(value.getJSONObject("discovery")),
    readCredentials(value.getJSONObject("credentials")), value.graphOptionalObject("legacyProfile")?.let(::readProfile))

  private fun discoveryJson(status: HarnessDiscoveryStatus): JSONObject = JSONObject().put("installed", status.installed.wire).put("configured", status.configured.wire)
    .put("listening", status.listening.wire).put("authenticated", status.authenticated.wire).put("compatible", status.compatible.wire).put("available", status.available.wire)
    .putOpt("observedAtMs", status.observedAtMs).putOpt("freshUntilMs", status.freshUntilMs).put("completeness", status.completeness.wire).putOpt("reason", status.reason)
  private fun readDiscovery(value: JSONObject): HarnessDiscoveryStatus = HarnessDiscoveryStatus(
    DiscoveryValue.fromWire(value.graphString("installed")), DiscoveryValue.fromWire(value.graphString("configured")), DiscoveryValue.fromWire(value.graphString("listening")),
    DiscoveryValue.fromWire(value.graphString("authenticated")), DiscoveryValue.fromWire(value.graphString("compatible")), DiscoveryValue.fromWire(value.graphString("available")),
    value.graphOptionalLong("observedAtMs"), value.graphOptionalLong("freshUntilMs"), DiscoveryCompleteness.fromWire(value.graphString("completeness")), value.graphOptionalString("reason"))

  private fun slotJson(ref: ConnectionSlotRef): JSONObject = JSONObject().put("store", ref.store.wire).put("key", ref.key)
  private fun readSlot(value: JSONObject): ConnectionSlotRef = ConnectionSlotRef(ConnectionSlotStore.fromWire(value.graphString("store")), value.graphString("key"))
  private fun credentialsJson(refs: ConnectionCredentialRefs): JSONObject = JSONObject().putOpt("profileSecret", refs.profileSecret?.let(::slotJson))
    .put("sshSecrets", jsonArray(refs.sshSecrets, ::slotJson)).putOpt("remoteIdentity", refs.remoteIdentity?.let { remote ->
      JSONObject().put("candidates", jsonArray(remote.candidates, ::slotJson)).putOpt("selected", remote.selected?.let(::slotJson)) })
  private fun readCredentials(value: JSONObject): ConnectionCredentialRefs = ConnectionCredentialRefs(value.graphOptionalObject("profileSecret")?.let(::readSlot),
    value.graphObjects("sshSecrets").map(::readSlot), value.graphOptionalObject("remoteIdentity")?.let { remote ->
      RemoteIdentitySlotRefs(remote.graphObjects("candidates").map(::readSlot), remote.graphOptionalObject("selected")?.let(::readSlot)) })

  private fun profileJson(profile: ServerProfile): JSONObject = JSONObject().put("id", profile.id).put("name", profile.name).put("url", profile.url).put("username", profile.username)
    .put("autoConnect", profile.autoConnect).put("notifications", profile.notifications).put("allowCleartext", profile.allowCleartext).put("kind", profile.kind.wire)
    .put("dshAuth", profile.dshAuth.wire).putOpt("remoteHostId", profile.remoteHostId).putOpt("remoteHostFingerprint", profile.remoteHostFingerprint)
    .putOpt("sshHostId", profile.sshHostId).put("enabled", profile.enabled).putOpt("sshServicePath", profile.sshServicePath)
    .putOpt("remoteServiceId", profile.remoteServiceId)
  private fun readProfile(value: JSONObject): ServerProfile = ServerProfile(value.graphString("id"), value.graphString("name"), value.graphString("url"), value.graphString("username"),
    value.graphBoolean("autoConnect"), value.graphBoolean("notifications"), value.graphBoolean("allowCleartext"), MigrationProjection.legacyKind(value.graphString("kind")),
    DshAuthMode.entries.firstOrNull { it.wire == value.graphString("dshAuth") } ?: error("Unknown legacy DSH auth mode"),
    value.graphOptionalString("remoteHostId"), value.graphOptionalString("remoteHostFingerprint"), value.graphOptionalString("sshHostId"), value.graphBoolean("enabled"), value.graphOptionalString("sshServicePath"),
    parentEnabled = true, remoteServiceId = value.graphOptionalString("remoteServiceId"))
}

/** Pure/idempotent compatibility projection only. Durable migration/transactions belong to a later store integration. */
object MigrationProjection {
  /** Do not use ServerKind.fromWire: it defaults unknown strings to OpenCode. */
  fun legacyKind(wire: String): ServerKind = ServerKind.entries.firstOrNull { it.wire == wire }
    ?: throw IllegalArgumentException("Unknown legacy harness kind: $wire")
  fun protocol(kind: ServerKind): HarnessProtocolId = when (kind) {
    ServerKind.OPENCODE -> HarnessProtocolId.OPENCODE_NATIVE
    ServerKind.CODEX -> HarnessProtocolId.CODEX_APP_SERVER
    ServerKind.DSH, ServerKind.DSH_REMOTE -> HarnessProtocolId.DSH_TYPERT
  }
  fun sourceType(profile: ServerProfile): ConnectionSourceType = when {
    profile.sshHostId != null -> ConnectionSourceType.SSH
    profile.kind == ServerKind.DSH_REMOTE || profile.remoteHostId != null -> ConnectionSourceType.HUB
    else -> ConnectionSourceType.DIRECT
  }

  /**
   * [resolvedRemoteSlots] is keyed by legacy profile ID and supplied ONLY after scope-validated store
   * resolution. Without it every candidate list remains explicitly unresolved, including scoped keys.
   * Hub grouping is origin+account scoped; SSH grouping uses saved config IDs, never addresses.
   * Direct profiles are deliberately not merged just because URLs or names match.
   */
  fun fromProfiles(profiles: List<ServerProfile>, resolvedRemoteSlots: Map<String, String> = emptyMap(),
    compositions: List<BindingComposition> = emptyList()): ConnectionGraph {
    require(profiles.map { it.id }.distinct().size == profiles.size) { "Duplicate legacy profile IDs" }
    require(resolvedRemoteSlots.keys.all { key -> profiles.any { it.id == key && sourceType(it) == ConnectionSourceType.HUB } }) { "Unknown Remote slot profile" }
    val sources = linkedMapOf<String, ConnectionSource>(); val hosts = linkedMapOf<String, HostBinding>()
    val bindings = profiles.map { profile ->
      graphId(profile.id)
      val type = sourceType(profile)
      require(profile.sshHostId == null || profile.kind != ServerKind.DSH_REMOTE) { "SSH profile cannot use Remote protocol/carrier kind" }
      val origin = if (type == ConnectionSourceType.HUB) canonicalRemoteOrigin(profile.url) else profile.url
      val sourceId = when (type) {
        ConnectionSourceType.HUB -> stableId("source-hub", origin, profile.username.trim())
        ConnectionSourceType.SSH -> stableId("source-ssh", profile.sshHostId!!)
        ConnectionSourceType.DIRECT -> stableId("source-direct", profile.id)
      }
      val source = ConnectionSource(sourceId, type, if (type == ConnectionSourceType.HUB) origin else profile.name,
        origin, if (type == ConnectionSourceType.HUB) profile.username.trim() else null, profile.sshHostId, allowCleartext = profile.allowCleartext)
      val oldSource = sources[sourceId]
      require(oldSource == null || oldSource.type == source.type && oldSource.endpoint == source.endpoint && oldSource.allowCleartext == source.allowCleartext) { "Conflicting legacy source configuration" }
      sources.putIfAbsent(sourceId, source)
      val hostId = stableId("host", sourceId, if (type == ConnectionSourceType.HUB) requireNotNull(profile.remoteHostId) { "Remote profile has no Host" } else profile.sshHostId ?: profile.id)
      val host = HostBinding(hostId, sourceId, profile.name, profile.remoteHostId.takeIf { type == ConnectionSourceType.HUB } ?: profile.sshHostId,
        profile.remoteHostFingerprint.takeIf { type == ConnectionSourceType.HUB })
      val oldHost = hosts[hostId]
      require(oldHost == null || oldHost.fingerprint == host.fingerprint) { "Conflicting legacy Host fingerprints" }
      hosts.putIfAbsent(hostId, host)
      val carrier = when (type) {
        ConnectionSourceType.HUB -> ConnectionCarrierId.REMOTE_NOISE
        ConnectionSourceType.SSH -> ConnectionCarrierId.SSH_FORWARD
        ConnectionSourceType.DIRECT -> ConnectionCarrierId.DIRECT_HTTP
      }
      val refs = when (type) {
        ConnectionSourceType.HUB -> ConnectionCredentialRefs(
          profileSecret = ConnectionSlotRef(ConnectionSlotStore.SERVER_SECRETS, profile.id),
          remoteIdentity = remoteSlotRefs(profile, resolvedRemoteSlots[profile.id]))
        ConnectionSourceType.SSH -> ConnectionCredentialRefs(sshSecrets = listOf("password", "privateKey", "passphrase").map {
          ConnectionSlotRef(ConnectionSlotStore.SSH_SECRETS, "ssh:${profile.sshHostId}:$it") })
        ConnectionSourceType.DIRECT -> ConnectionCredentialRefs(profileSecret = ConnectionSlotRef(ConnectionSlotStore.SERVER_SECRETS, profile.id))
      }
      HarnessBinding(profile.id, hostId, profile.name, protocol(profile.kind), carrier, serviceId = profile.remoteServiceId, enabled = profile.enabled,
        autoConnect = profile.autoConnect, notifications = profile.notifications, credentials = refs, legacyProfile = profile)
    }
    return ConnectionGraph(sources.values.toList(), hosts.values.toList(), bindings, compositions)
  }

  fun remoteSlotRefs(profile: ServerProfile, selectedSlot: String? = null): RemoteIdentitySlotRefs {
    require(sourceType(profile) == ConnectionSourceType.HUB)
    val host = requireNotNull(profile.remoteHostId) { "Remote profile has no Host" }
    val slots = listOf(RemoteIdentitySlots.scoped(profile.url, profile.username, host), RemoteIdentitySlots.legacyHostSlot(host),
      "profile-${profile.id.replace(Regex("[^A-Za-z0-9_-]"), "_")}").distinct()
    return RemoteIdentitySlotRefs(slots.map { ConnectionSlotRef(ConnectionSlotStore.REMOTE_DEVICES, it) },
      selectedSlot?.let { ConnectionSlotRef(ConnectionSlotStore.REMOTE_DEVICES, it) })
  }

  /** Existing consumers can keep leaf IDs; no fake native adapter is generated. */
  fun compatibilityLeaves(graph: ConnectionGraph): List<ServerProfile> = graph.bindings.mapNotNull { binding ->
    binding.legacyProfile?.copy(name = binding.name, enabled = graph.effectiveEnabled(binding.id),
      autoConnect = binding.autoConnect, notifications = binding.notifications)
  }

  internal fun sshSourceId(configId: String): String = stableId("source-ssh", configId)
  internal fun hubSourceId(origin: String, account: String): String = stableId("source-hub", canonicalRemoteOrigin(origin), account.trim())
  internal fun hostId(sourceId: String, externalId: String): String = stableId("host", sourceId, externalId)

  private fun stableId(namespace: String, vararg parts: String): String {
    val input = parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
    return "$namespace-${MessageDigest.getInstance("SHA-256").digest(input).joinToString("") { "%02x".format(it) }}"
  }
}

private fun graphId(value: String) { require(value.isNotBlank() && value.length <= 512 && '\u0000' !in value) { "Invalid graph ID/ref" } }
private fun <T> jsonArray(values: List<T>, encode: (T) -> JSONObject): JSONArray = JSONArray().apply { values.forEach { put(encode(it)) } }
private fun JSONObject.graphString(key: String): String = (get(key) as? String)?.also { require(it.length <= 32768) } ?: error("$key must be a string")
private fun JSONObject.graphOptionalString(key: String): String? = if (!has(key)) null else graphString(key)
private fun JSONObject.graphBoolean(key: String): Boolean = get(key) as? Boolean ?: error("$key must be boolean")
private fun JSONObject.graphLong(key: String): Long = when (val value = get(key)) { is Int -> value.toLong(); is Long -> value; else -> error("$key must be an integer") }
private fun JSONObject.graphOptionalLong(key: String): Long? = if (!has(key)) null else graphLong(key)
private fun JSONObject.graphOptionalObject(key: String): JSONObject? = if (!has(key)) null else getJSONObject(key)
private fun JSONObject.graphObjects(key: String): List<JSONObject> = getJSONArray(key).let { array ->
  require(array.length() <= 10000) { "Too many $key records" }; (0 until array.length()).map { array.getJSONObject(it) }
}
private fun JSONObject.graphStrings(key: String): List<String> = getJSONArray(key).let { array ->
  require(array.length() <= 10000); (0 until array.length()).map { array.get(it) as? String ?: error("$key must contain string IDs") }
}
