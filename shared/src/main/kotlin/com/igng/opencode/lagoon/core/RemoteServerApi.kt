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
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The control-plane API, not the encrypted Host business channel. */
internal class RemoteServerApi(address: String, allowCleartext: Boolean = false) {
  private val base = requireNotNull(address.trim().toHttpUrlOrNull()) { "DSH Hub 地址无效" }
  private val http = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .connectionPool(SharedHttp.connectionPool).dispatcher(SharedHttp.dispatcher).build()

  init {
    require(base.isHttps || allowCleartext) { "DSH Hub HTTP 明文连接未获授权" }
    require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) { "DSH Hub 地址不能包含凭据或参数" }
    require(base.encodedPath == "/") { "DSH Hub 地址必须是服务根地址" }
  }

  suspend fun health() {
    if (request("GET", "health").optString("status") != "ok") throw IOException("DSH Hub 健康检查失败")
  }

  suspend fun login(account: String, password: String): RemoteAccount {
    val body = request("POST", "api/v1/auth/login", JSONObject().put("email", account.trim()).put("password", password))
    return RemoteAccount(requiredText(body, "account", 254), token(body, "token"), positiveLong(body, "expiresAt"))
  }

  suspend fun register(identity: RemoteClientDescriptor, accountToken: String): RemoteTokenPair {
    identity.validate()
    val device = JSONObject().put("deviceId", identity.deviceId).put("name", identity.name)
      .put("role", "client").put("platform", identity.platform).put("identityKey", identity.publicKey)
      .put("clientVersion", identity.clientVersion)
    return tokenPair(request("POST", "api/v1/devices/register", JSONObject().put("v", 1).put("device", device), accountToken))
  }

  suspend fun refresh(deviceId: String, refreshToken: String): RemoteTokenPair {
    require(validDeviceId(deviceId) && validToken(refreshToken)) { "DSH Hub 设备凭据无效" }
    return tokenPair(request("POST", "api/v1/auth/refresh", JSONObject().put("deviceId", deviceId).put("refreshToken", refreshToken)))
  }

  suspend fun hosts(accessToken: String): List<RemoteHost> {
    val body = request("GET", "api/v1/devices", bearer = accessToken)
    val items = body.optJSONArray("items") ?: throw IOException("DSH Hub 设备列表无效")
    if (!body.isNull("nextCursor") && body.optString("nextCursor").isNotEmpty()) throw IOException("DSH Hub 返回了暂不支持的设备分页，未使用不完整列表")
    if (items.length() > 5000) throw IOException("DSH Hub 设备列表超过上限")
    return (0 until items.length()).map { index -> host(items.optJSONObject(index) ?: throw IOException("DSH Hub 设备信息无效"), false) }
  }

  /** Fetch separately: list results intentionally do not contain the Host identity key. */
  suspend fun host(accessToken: String, deviceId: String): RemoteHost {
    require(validDeviceId(deviceId)) { "DSH Hub 设备 ID 无效" }
    val result = host(request("GET", "api/v1/devices/$deviceId", bearer = accessToken), true)
    if (result.deviceId != deviceId) throw IOException("DSH Hub 返回了错误的设备身份")
    return result
  }

  private fun host(body: JSONObject, includeKey: Boolean): RemoteHost {
    val id = requiredText(body, "deviceId", 64)
    if (!validDeviceId(id) || body.optString("role") != "host") throw IOException("DSH Hub Host 身份无效")
    val key = if (includeKey) requiredText(body, "identityKey", 43).also {
      if (!validPublicKey(it)) throw IOException("DSH Hub Host 公钥无效")
    } else null
    return RemoteHost(id, requiredText(body, "name", 128), requiredText(body, "platform", 64),
      requiredText(body, "membershipId", 256), body.optBoolean("online", false), key,
      body.optString("clientVersion").takeIf(String::isNotBlank), body.optString("harnessVersion").takeIf(String::isNotBlank),
      body.optString("fingerprint").takeIf(String::isNotBlank))
  }

  private suspend fun request(method: String, path: String, body: JSONObject? = null, bearer: String? = null): JSONObject = withContext(Dispatchers.IO) {
    if (bearer != null) require(validToken(bearer)) { "DSH Hub 授权凭据无效" }
    val request = Request.Builder().url(base.newBuilder().addEncodedPathSegments(path).build())
      .header("Accept", "application/json").apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
      .method(method, body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
    http.newCall(request).execute().use { response ->
      val source = response.body?.source() ?: throw IOException("DSH Hub 返回空响应")
      if (source.request(MAX_RESPONSE_BYTES + 1)) throw IOException("DSH Hub 响应超过上限")
      val text = source.readUtf8()
      if (!response.isSuccessful) {
        val error = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
        val code = error?.optString("code")?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) }
        throw RemoteServerException(response.code, code, error?.optBoolean("retryable") == true)
      }
      try { JSONObject(text) } catch (_: Exception) { throw IOException("DSH Hub 返回无效 JSON") }
    }
  }

  private fun tokenPair(body: JSONObject): RemoteTokenPair {
    if (body.keys().asSequence().toSet() != setOf("accessToken", "accessTokenExpiresAt", "refreshToken", "refreshTokenExpiresAt"))
      throw IOException("DSH Hub 设备凭据格式无效")
    return RemoteTokenPair(token(body, "accessToken"), positiveLong(body, "accessTokenExpiresAt"),
      token(body, "refreshToken"), positiveLong(body, "refreshTokenExpiresAt"))
  }

  private fun token(body: JSONObject, name: String): String = requiredText(body, name, 8192).also {
    if (!validToken(it)) throw IOException("DSH Hub 凭据格式无效")
  }

  private fun positiveLong(body: JSONObject, name: String): Long {
    val value = body.opt(name)
    if (value !is Number) throw IOException("DSH Hub 返回无效时间")
    val number = value.toDouble()
    if (!number.isFinite() || number <= 0 || number > 9007199254740991.0 || number % 1.0 != 0.0) throw IOException("DSH Hub 返回无效时间")
    return value.toLong()
  }

  private fun requiredText(body: JSONObject, name: String, max: Int): String {
    val value = body.opt(name) as? String ?: throw IOException("DSH Hub 返回无效字段")
    if (value.isBlank() || value.toByteArray(Charsets.UTF_8).size > max) throw IOException("DSH Hub 返回无效字段")
    return value
  }

  companion object {
    private const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024
    internal fun validToken(value: String): Boolean = value.toByteArray(Charsets.UTF_8).size in 16..8192 && value.all { it.code in 33..126 }
    internal fun validDeviceId(value: String): Boolean =
      value.matches(Regex("(?i)[0-9a-hjkmnp-tv-z]{26}")) ||
        value.matches(Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
    internal fun validPublicKey(value: String): Boolean = runCatching {
      value.length == 43 && value.matches(Regex("[A-Za-z0-9_-]+")) && Base64.getUrlDecoder().decode(value).size == 32 &&
        Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(value)) == value
    }.getOrDefault(false)
  }
}

internal data class RemoteClientDescriptor(val deviceId: String, val name: String, val platform: String, val publicKey: String, val clientVersion: String) {
  fun validate() {
    require(RemoteServerApi.validDeviceId(deviceId)) { "DSH Hub 设备 ID 无效" }
    require(name.isNotBlank() && name.toByteArray(Charsets.UTF_8).size <= 128) { "DSH Hub 设备名称无效" }
    require(platform.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "DSH Hub 设备平台无效" }
    require(RemoteServerApi.validPublicKey(publicKey)) { "DSH Hub 设备公钥无效" }
    require(clientVersion.isNotEmpty() && clientVersion.toByteArray(Charsets.UTF_8).size <= 64) { "DSH Hub 客户端版本无效" }
  }

  companion object { fun newId(): String = UUID.randomUUID().toString() }
}

internal data class RemoteAccount(val account: String, val token: String, val expiresAt: Long) {
  override fun toString(): String = "RemoteAccount(account=$account, token=<redacted>, expiresAt=$expiresAt)"
}
internal data class RemoteTokenPair(val accessToken: String, val accessTokenExpiresAt: Long, val refreshToken: String, val refreshTokenExpiresAt: Long) {
  override fun toString(): String = "RemoteTokenPair(accessToken=<redacted>, accessTokenExpiresAt=$accessTokenExpiresAt, refreshToken=<redacted>, refreshTokenExpiresAt=$refreshTokenExpiresAt)"
}
internal data class RemoteHost(val deviceId: String, val name: String, val platform: String, val membershipId: String,
  val online: Boolean, val publicKey: String?, val clientVersion: String?, val harnessVersion: String?, val fingerprint: String? = null)
internal class RemoteServerException(val status: Int, val code: String?, val retryable: Boolean) : IOException("DSH Hub 请求失败（HTTP $status${code?.let { ", $it" }.orEmpty()}）")
