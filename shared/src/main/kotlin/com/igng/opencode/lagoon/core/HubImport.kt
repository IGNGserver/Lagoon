package com.igng.opencode.lagoon.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The hub server a local profile was imported from. */
data class HubLink(val hubId: String, val hubServerId: String)

enum class ImportAction(val label: String) {
  ADD("新增"),
  UPDATE("更新"),
  UNCHANGED("无变化"),
  /** The address is already used by a server imported from elsewhere, or appears twice in the export. */
  CONFLICT("冲突"),
  /** Imported from this hub before, but the hub no longer has it. */
  REMOVED("中枢已移除")
}

/**
 * One line of the import preview. [incoming] is null only for [ImportAction.REMOVED]; [local] is the
 * device's profile it matched (null for [ImportAction.ADD]).
 */
data class ImportItem(val action: ImportAction, val incoming: ExportedServer?, val local: ServerProfile?,
  /** What an update changes, in the words the preview shows. */
  val changes: List<String> = emptyList(), val note: String? = null) {
  /** Whether the user may tick this line at all. */
  val selectable: Boolean get() = action != ImportAction.UNCHANGED && !(action == ImportAction.CONFLICT && local == null)
  /** Ticked when the preview opens: plain additions and updates whose address the phone can use. */
  val selectedByDefault: Boolean get() = (action == ImportAction.ADD || action == ImportAction.UPDATE) && incoming?.localOnly != true
  val name: String get() = incoming?.profile?.name ?: local?.name.orEmpty()
}

data class ImportPlan(val hubId: String, val items: List<ImportItem>)

/**
 * Identity of a harness endpoint for de-duplication: the protocol plus scheme, host, port and mount
 * path, normalized (lower-case host, explicit port, no trailing slash). Null for an unusable address.
 */
fun endpointKey(kind: ServerKind, url: String): String? {
  val parsed = url.trim().toHttpUrlOrNull() ?: return null
  val path = parsed.encodedPath.trimEnd('/')
  return "${kind.wire}|${parsed.scheme}://${parsed.host.lowercase()}:${parsed.port}$path"
}

/**
 * Compares what a hub exports with the servers already on this device.
 *
 * A hub server is the same as a local one when the local one was imported from it before ([links]),
 * otherwise when both name the same endpoint ([endpointKey]) and the local one was added by hand or
 * came from a hub server that no longer exists. A local server already tied to another hub (or to
 * another server of this hub) is a conflict the user resolves. Servers the hub dropped are listed
 * but never removed unless the user ticks them. Device preferences (auto-connect, notifications) stay
 * local on update.
 */
fun planHubImport(export: HubExport, local: List<ServerProfile>, links: Map<String, HubLink>,
  credentials: (String) -> ServerCredentials): ImportPlan {
  val exportedIds = export.servers.map { it.hubServerId }.toSet()
  val linkedOf = export.servers.associate { server ->
    server.hubServerId to local.firstOrNull { links[it.id] == HubLink(export.hubId, server.hubServerId) }
  }
  val claimed = linkedOf.values.mapNotNull { it?.id }.toMutableSet()
  // Two hub servers with one endpoint import once: the one this device already follows, else the first.
  val kept = export.servers.groupBy { endpointKey(it.profile.kind, it.profile.url) ?: "id:${it.hubServerId}" }
    .mapValues { (_, group) -> group.firstOrNull { linkedOf[it.hubServerId] != null } ?: group.first() }
  val items = mutableListOf<ImportItem>()

  for (server in export.servers) {
    val key = endpointKey(server.profile.kind, server.profile.url)
    val keeper = kept.getValue(key ?: "id:${server.hubServerId}")
    if (keeper !== server) {
      items += ImportItem(ImportAction.CONFLICT, server, null, note = "中枢里「${keeper.profile.name}」用的是同一个地址，已跳过这一条")
      continue
    }
    val linked = linkedOf[server.hubServerId]
    val sameEndpoint = linked ?: local.firstOrNull { it.id !in claimed && key != null && endpointKey(it.kind, it.url) == key }
    val localOnlyNote = "这个地址只在中枢的容器里可用，请先在中枢填写「客户端访问地址」，或导入后在本机改地址".takeIf { server.localOnly }
    when {
      sameEndpoint == null -> items += ImportItem(ImportAction.ADD, server, null, note = localOnlyNote)
      linked == null && links[sameEndpoint.id].let { it != null && (it.hubId != export.hubId || it.hubServerId in exportedIds) } -> {
        claimed += sameEndpoint.id
        val owner = links.getValue(sameEndpoint.id)
        items += ImportItem(ImportAction.CONFLICT, server, sameEndpoint,
          note = if (owner.hubId == export.hubId) "本机「${sameEndpoint.name}」已对应中枢里的另一台服务器；勾选后改为对应这一台"
          else "本机「${sameEndpoint.name}」是从另一个中枢导入的；勾选后改为跟随这个中枢")
      }
      else -> {
        claimed += sameEndpoint.id
        val changes = changesOf(sameEndpoint, credentials(sameEndpoint.id), server)
        val action = if (changes.isEmpty() && linked != null) ImportAction.UNCHANGED else ImportAction.UPDATE
        val note = localOnlyNote ?: "本机「${sameEndpoint.name}」与它地址相同，将合并为同一台".takeIf { linked == null }
        items += ImportItem(action, server, sameEndpoint, changes, note)
      }
    }
  }

  local.filter { it.id !in claimed && links[it.id]?.let { link -> link.hubId == export.hubId && link.hubServerId !in exportedIds } == true }
    .forEach { items += ImportItem(ImportAction.REMOVED, null, it, note = "中枢里已没有这台服务器；勾选后从本机移除") }
  return ImportPlan(export.hubId, items)
}

private fun changesOf(local: ServerProfile, current: ServerCredentials, incoming: ExportedServer): List<String> {
  val next = incoming.profile
  return buildList {
    if (local.name != next.name) add("名称")
    if (local.url != next.url) add("地址")
    if (local.kind != next.kind) add("类型")
    if (local.kind == ServerKind.DSH && next.kind == ServerKind.DSH && local.dshAuth != next.dshAuth) add("认证方式")
    if (local.username != next.username) add("用户名")
    if (local.allowCleartext != next.allowCleartext) add("明文连接")
    if (current.password != incoming.credentials.password || current.username != incoming.credentials.username) add("凭据")
  }
}

/** The profile a ticked line saves: a new id for an addition, the local id and device preferences for the rest. */
fun importedProfile(item: ImportItem, newId: () -> String): ServerProfile? {
  val incoming = item.incoming?.profile ?: return null
  val local = item.local ?: return incoming.copy(id = newId())
  return incoming.copy(id = local.id, autoConnect = local.autoConnect, notifications = local.notifications)
}

/** Fetches and decrypts one export from a hub (see [HubExportCrypto]). */
object HubExportClient {
  private val client = OkHttpClient.Builder()
    // A redirect would re-send the signed request elsewhere; the user should enter the final address instead.
    .followRedirects(false)
    .followSslRedirects(false)
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .connectionPool(SharedHttp.connectionPool)
    .dispatcher(SharedHttp.dispatcher)
    .build()

  suspend fun fetch(target: HubImportTarget, now: Long = System.currentTimeMillis(), http: OkHttpClient = client): HubExport = withContext(Dispatchers.IO) {
    val nonce = HubExportCrypto.nonce()
    val request = Request.Builder().url(target.hubUrl.trimEnd('/') + "/api/v1/client/export")
      .post(HubExportCrypto.request(target.key, now, nonce).toString().toRequestBody("application/json".toMediaType()))
      .header("Accept", "application/json")
      .build()
    http.newCall(request).execute().use { response ->
      val text = response.body?.string().orEmpty()
      if (response.code in 300..399) throw IOException("中枢地址发生了跳转，请填写跳转后的最终地址")
      if (!response.isSuccessful) {
        val message = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()?.takeIf(String::isNotBlank)
        throw IOException(message ?: when (response.code) {
          404 -> "这个地址不是 Lagoon 中枢，或中枢版本过旧不支持导入"
          else -> "中枢返回 HTTP ${response.code}"
        })
      }
      val sealed = runCatching { JSONObject(text) }.getOrNull() ?: throw IOException("这个地址不是 Lagoon 中枢")
      val plain = try { HubExportCrypto.open(target.key, nonce, sealed) } catch (error: Exception) {
        throw IOException("无法解密中枢返回的配置：密钥不匹配或内容被篡改", error)
      }
      HubExportCodec.decode(JSONObject(plain))
    }
  }
}
