package com.igng.opencode.lagoon.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * 设备发现的观测缓存（version 2，按 [SshDevice.id] 直接索引）。
 *
 * 发现是带时效的观测，不是连接许可：读不出来就当作"没有观测"，绝不放行任何连接。但它也不是可以
 * 随手丢弃的缓存——设备停用时页面只靠它显示上次看到的 Harness，所以写坏时必须保留原文件、拒绝覆盖。
 */
internal class DeviceDiscoveryStore internal constructor(
  private val preferences: SharedPreferences,
  private val now: () -> Long = System::currentTimeMillis
) {
  constructor(context: Context) : this(context.getSharedPreferences("device-discovery", Context.MODE_PRIVATE))

  fun load(): Map<String, DeviceDiscovery> = try {
    readStrict()
  } catch (error: Exception) {
    Diagnostics.warn("DeviceDiscoveryStore", "设备发现缓存损坏，保留原文件并暂不显示", error)
    emptyMap()
  }

  fun get(deviceId: String): DeviceDiscovery? = load()[deviceId]

  fun put(discovery: DeviceDiscovery) {
    val next = readStrict().toMutableMap()
    next[discovery.deviceId] = discovery
    save(next)
  }

  fun clear(deviceId: String) {
    val current = readStrict()
    if (deviceId !in current) return
    save(current - deviceId)
  }

  fun clearAll() {
    if (!preferences.edit().remove(KEY).commit()) throw IOException("无法清除设备发现缓存")
  }

  /** 将 v1 的 `host-<sha256>` 键一次性重映射到 v2 的 `SshDevice.id`，丢弃已下线设备的发现缓存。 */
  fun migrateFromLegacyHostIds(oldHostIdToSshId: Map<String, String>, survivingDeviceIds: Set<String>) {
    val raw = preferences.getString(KEY, null) ?: return
    val version = runCatching { JSONObject(raw).optInt("version", -1) }.getOrDefault(-1)
    if (version == VERSION) return
    if (version != 1) return
    runCatching {
      val legacy = decode(raw, allowLegacyVersion = true)
      val remapped = LinkedHashMap<String, DeviceDiscovery>()
      legacy.forEach { (oldId, discovery) ->
        val nextId = oldHostIdToSshId[oldId] ?: oldId.takeIf { it in survivingDeviceIds } ?: return@forEach
        val existing = remapped[nextId]
        if (existing == null || discovery.observedAtMs > existing.observedAtMs) {
          remapped[nextId] = discovery.copy(deviceId = nextId)
        }
      }
      save(remapped)
    }.onFailure {
      Diagnostics.warn("DeviceDiscoveryStore", "升级设备发现缓存到 v2 失败", it)
    }
  }

  private fun readStrict(): Map<String, DeviceDiscovery> {
    val text = preferences.getString(KEY, null) ?: return emptyMap()
    return decode(text, allowLegacyVersion = false)
  }

  private fun save(devices: Map<String, DeviceDiscovery>) {
    val bounded = devices.entries
      .sortedByDescending { it.value.observedAtMs }
      .take(MAX_DEVICES)
      .associate { it.key to it.value }
    val encoded = encode(bounded)
    if (!preferences.edit().putString(KEY, encoded).commit()) throw IOException("设备发现缓存保存失败")
  }

  private fun encode(devices: Map<String, DeviceDiscovery>): String = JSONObject()
    .put("version", VERSION)
    .put("devices", JSONObject().apply {
      devices.forEach { (id, discovery) ->
        put(id, JSONObject()
          .put("observedAtMs", discovery.observedAtMs)
          .put("completeness", discovery.completeness.wire)
          .putOpt("note", discovery.note?.take(MAX_TEXT))
          .put("harnesses", JSONArray().apply {
            discovery.harnesses.take(MAX_HARNESSES).forEach { harness ->
              put(JSONObject()
                .put("protocol", harness.protocol.wire)
                .putOpt("locator", harness.locator?.take(MAX_TEXT))
                .put("name", harness.name.take(MAX_TEXT))
                .put("detail", harness.detail.take(MAX_TEXT))
                .put("status", statusJson(harness.status)))
            }
          }))
      }
    }).toString()

  private fun statusJson(status: HarnessDiscoveryStatus): JSONObject = JSONObject()
    .put("installed", status.installed.wire)
    .put("configured", status.configured.wire)
    .put("listening", status.listening.wire)
    .put("authenticated", status.authenticated.wire)
    .put("compatible", status.compatible.wire)
    .put("available", status.available.wire)
    .putOpt("observedAtMs", status.observedAtMs)
    .putOpt("freshUntilMs", status.freshUntilMs)
    .put("completeness", status.completeness.wire)
    .putOpt("reason", status.reason?.take(MAX_TEXT))

  private fun decode(text: String, allowLegacyVersion: Boolean): Map<String, DeviceDiscovery> {
    val root = try {
      JSONObject(text)
    } catch (error: Exception) {
      throw IOException("设备发现缓存不是 JSON", error)
    }
    val version = root.optInt("version", -1)
    if (version != VERSION && !(allowLegacyVersion && version == 1)) {
      throw IOException("设备发现缓存版本不受支持：$version")
    }
    val devices = root.optJSONObject("devices") ?: return emptyMap()
    val result = LinkedHashMap<String, DeviceDiscovery>()
    devices.keys().forEach { id ->
      val item = devices.optJSONObject(id) ?: return@forEach
      if (!validId(id)) return@forEach
      val observedAt = item.optLong("observedAtMs", 0)
      if (observedAt <= 0) return@forEach
      val harnesses = item.optJSONArray("harnesses")?.let { array ->
        (0 until minOf(array.length(), MAX_HARNESSES)).mapNotNull { index ->
          array.optJSONObject(index)?.let { harness ->
            val protocol = HarnessProtocol.fromWireOrNull(harness.optString("protocol"))
              ?: return@mapNotNull null
            DiscoveredHarness(
              protocol = protocol,
              locator = harness.stringOrNull("locator"),
              name = harness.optString("name").take(MAX_TEXT),
              status = readStatus(harness.optJSONObject("status")),
              detail = harness.optString("detail").take(MAX_TEXT)
            )
          }
        }
      }.orEmpty()
      result[id] = DeviceDiscovery(
        deviceId = id,
        observedAtMs = observedAt,
        harnesses = harnesses,
        completeness = DiscoveryCompleteness.entries.firstOrNull { it.wire == item.optString("completeness") }
          ?: DiscoveryCompleteness.UNKNOWN,
        note = item.stringOrNull("note")
      )
    }
    return result
  }

  private fun readStatus(json: JSONObject?): HarnessDiscoveryStatus {
    if (json == null) return HarnessDiscoveryStatus()
    fun value(key: String) = DiscoveryValue.entries.firstOrNull { it.wire == json.optString(key) } ?: DiscoveryValue.UNKNOWN
    val observed = json.optLong("observedAtMs", 0).takeIf { it > 0 }
    val fresh = json.optLong("freshUntilMs", 0).takeIf { stamp -> stamp > 0 && observed != null && stamp >= observed }
    return try {
      HarnessDiscoveryStatus(
        installed = value("installed"), configured = value("configured"), listening = value("listening"),
        authenticated = value("authenticated"), compatible = value("compatible"), available = value("available"),
        observedAtMs = observed, freshUntilMs = fresh,
        completeness = DiscoveryCompleteness.entries.firstOrNull { it.wire == json.optString("completeness") }
          ?: DiscoveryCompleteness.UNKNOWN,
        reason = json.stringOrNull("reason")
      )
    } catch (_: Exception) {
      HarnessDiscoveryStatus(reason = "发现结果无效")
    }
  }

  private fun validId(id: String): Boolean = id.isNotBlank() && id.length <= 512 && '\u0000' !in id

  private fun JSONObject.stringOrNull(key: String): String? =
    (opt(key) as? String)?.takeIf { it.isNotBlank() && it.length <= MAX_TEXT }

  private companion object {
    const val KEY = "discovery"
    const val VERSION = 2
    const val MAX_DEVICES = 64
    const val MAX_HARNESSES = 32
    const val MAX_TEXT = 4096
  }
}
