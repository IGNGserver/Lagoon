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
    if (request("GET", "health").optString("status") != "ok") throw IOException("DSH 远程中枢健康检查失败")
  }

  suspend fun login(account: String, password: String): RemoteAccount {
    val body = try {
      request("POST", "api/v1/auth/login", JSONObject().put("email", account.trim()).put("password", password))
    } catch (error: RemoteServerException) {
      // A login 401 is a credential mismatch, not an expired session. Do not let the raw
      // AUTH_INVALID code read like a network or token problem.
      if (error.status == 401) throw IOException("账号或密码不正确；请使用 DSH 远程中枢配置的账号与密码", error)
      throw error
    }
    return RemoteAccount(requiredText(body, "account", 254), token(body, "token"), positiveLong(body, "expiresAt"))
  }

  /** Browser/QR sign-in providers. A 404 means this Server only supports email/password. */
  suspend fun oauthProviders(): List<RemoteOAuthProvider> {
    val body = try { request("GET", "api/v1/auth/oauth/providers") }
    catch (error: RemoteServerException) { if (error.status == 404) return emptyList() else throw error }
    val items = body.optJSONArray("items") ?: return emptyList()
    if (items.length() > 64) throw IOException("DSH Remote 登录方式过多")
    val ids = HashSet<String>()
    return (0 until items.length()).mapNotNull { index ->
      val item = items.optJSONObject(index) ?: return@mapNotNull null
      val id = (item.opt("id") as? String)?.takeIf { it.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}")) } ?: return@mapNotNull null
      val label = (item.opt("displayName") as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 128 } ?: return@mapNotNull null
      val startRaw = (item.opt("startUrl") as? String) ?: return@mapNotNull null
      val start = try { requireAuthEntryPath(startRaw) } catch (_: IOException) { return@mapNotNull null }
      val qr = (item.opt("qrStartUrl") as? String)?.takeIf { it.isNotBlank() }?.let {
        try { requireAuthEntryPath(it) } catch (_: IOException) { null }
      }
      if (!ids.add(id)) return@mapNotNull null
      RemoteOAuthProvider(id, label, start, qr)
    }
  }

  suspend fun startQrLogin(provider: RemoteOAuthProvider): RemoteBrowserLogin {
    val path = requireAuthEntryPath(provider.qrStartUrl ?: throw IOException("这个登录方式不支持浏览器登录"))
    val body = requestAt("POST", entryUrl(path), JSONObject())
    val qrId = requiredText(body, "qrId", 256).also {
      if (!it.matches(Regex("[A-Za-z0-9_-]{16,256}"))) throw IOException("DSH Remote 登录会话无效")
    }
    val scan = requiredText(body, "scanUrl", 4096).let { requireQrLoginUrl(it, base) }
    val expires = body.opt("expiresIn") as? Number ?: throw IOException("DSH Remote 登录会话无效")
    val seconds = expires.toLong()
    if (expires.toDouble() != seconds.toDouble() || seconds !in 1..3600) throw IOException("DSH Remote 登录会话无效")
    return RemoteBrowserLogin(qrId, scan, System.currentTimeMillis() + seconds * 1000, provider.id)
  }

  suspend fun pollQrLogin(qrId: String): RemoteQrPoll {
    if (!qrId.matches(Regex("[A-Za-z0-9_-]{16,256}"))) throw IOException("DSH Remote 登录会话无效")
    val body = request("GET", "api/v1/auth/oauth/qr/$qrId")
    return when (body.optString("status")) {
      "pending" -> RemoteQrPoll.Pending
      "expired" -> RemoteQrPoll.Expired
      "complete" -> RemoteQrPoll.Complete(requiredText(body, "token", 8192).also {
        if (it.length < 16) throw IOException("DSH Remote 登录凭据无效")
      })
      else -> throw IOException("DSH Remote 登录状态无效")
    }
  }

  suspend fun accountMe(accountToken: String): String {
    if (!validToken(accountToken)) throw IOException("DSH Remote 账号凭据无效")
    val body = requestAt("GET", entryUrl("/api/v1/auth/me"), null, accountToken)
    return requiredText(body, "account", 254)
  }

  /** Revoke this device on the Server. A missing/expired token is not a local failure. */
  suspend fun revokeSelf(accessToken: String) = withContext(Dispatchers.IO) {
    if (!validToken(accessToken)) throw IllegalArgumentException("Remote 设备凭据无效")
    val request = Request.Builder().url(sameOriginUrl("/api/v1/devices/self")).delete()
      .header("Accept", "application/json").header("Authorization", "Bearer $accessToken").build()
    http.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw RemoteServerException(response.code, null, response.code == 429 || response.code >= 500)
    }
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
    val hosts = LinkedHashMap<String, RemoteHost>()
    val cursors = HashSet<String>()
    var cursor: String? = null
    var pages = 0
    do {
      if (++pages > 100) throw IOException("DSH Hub 设备分页超过上限，未使用不完整列表")
      val url = base.newBuilder().addEncodedPathSegments("api/v1/devices")
        .apply { cursor?.let { addQueryParameter("limit", "50"); addQueryParameter("cursor", it) } }.build()
      val body = requestAt("GET", url, bearer = accessToken)
      val items = body.optJSONArray("items") ?: throw IOException("DSH Hub 设备列表无效")
      if (items.length() > 5000 || hosts.size + items.length() > 5000) throw IOException("DSH Hub 设备列表超过上限")
      for (index in 0 until items.length()) {
        val host = host(items.optJSONObject(index) ?: throw IOException("DSH Hub 设备信息无效"), false)
        if (hosts.put(host.deviceId, host) != null) throw IOException("DSH Hub 设备分页包含重复身份，未使用不完整列表")
      }
      cursor = when (val next = body.opt("nextCursor")) {
        null, JSONObject.NULL -> null
        is String -> next.takeIf { it.isNotEmpty() }?.also {
          if (it.length > 4096 || it.any { c -> c.code < 0x21 || c.code == 0x7f } || !cursors.add(it))
            throw IOException("DSH Hub 返回无效或循环分页游标，未使用不完整列表")
        }
        else -> throw IOException("DSH Hub 返回无效分页游标")
      }
    } while (cursor != null)
    return hosts.values.toList()
  }

  /**
   * Account-scoped Host directory. `/api/v1/devices` is Client-device-token scoped and rejects the
   * 8-hour account token issued by login; `/api/v1/account/devices` is the account-token route and
   * already carries each Host's identityKey, so discovery needs no per-Host detail call.
   */
  suspend fun accountHosts(accountToken: String): List<RemoteHost> {
    if (!validToken(accountToken)) throw IOException("DSH Remote 账号凭据无效")
    val body = try {
      requestAt("GET", sameOriginUrl("/api/v1/account/devices"), null, accountToken)
    } catch (error: RemoteServerException) {
      if (error.status == 401) throw IOException("DSH 远程中枢登录已失效，请重新登录", error)
      if (error.status == 404) throw IOException("服务端不支持账号设备目录，请升级 DSH 远程中枢", error)
      throw error
    }
    val items = body.optJSONArray("items") ?: throw IOException("DSH Hub 设备列表无效")
    if (body.has("nextCursor") && !body.isNull("nextCursor") && body.opt("nextCursor") != "")
      throw IOException("DSH Hub 账号目录需要分页，未使用不完整列表")
    if (items.length() > 5000) throw IOException("DSH Hub 设备列表超过上限")
    return (0 until items.length()).mapNotNull { index ->
      val item = items.optJSONObject(index) ?: throw IOException("DSH Hub 设备信息无效")
      if (item.optString("role") != "host") null else accountHost(item)
    }
  }

  /** Fetch separately: list results intentionally do not contain the Host identity key. */
  suspend fun host(accessToken: String, deviceId: String): RemoteHost {
    require(validDeviceId(deviceId)) { "DSH Hub 设备 ID 无效" }
    val result = host(request("GET", "api/v1/devices/$deviceId", bearer = accessToken), true)
    if (result.deviceId != deviceId) throw IOException("DSH Hub 返回了错误的设备身份")
    return result
  }

  private fun host(body: JSONObject, includeKey: Boolean): RemoteHost {
    val id = hostId(body)
    val key = if (includeKey) requiredText(body, "identityKey", 43).also {
      if (!validPublicKey(it)) throw IOException("DSH Hub Host 公钥无效")
    } else null
    return remoteHost(body, id, key)
  }

  /** Account directory entries may omit identityKey on older/minimal Servers; show them as unenrollable instead of hiding them. */
  private fun accountHost(body: JSONObject): RemoteHost =
    remoteHost(body, hostId(body), (body.opt("identityKey") as? String)?.takeIf(RemoteServerApi::validPublicKey))

  private fun hostId(body: JSONObject): String {
    val id = requiredText(body, "deviceId", 64)
    if (!validDeviceId(id) || body.optString("role") != "host") throw IOException("DSH Hub Host 身份无效")
    return id
  }

  private fun remoteHost(body: JSONObject, id: String, key: String?): RemoteHost =
    RemoteHost(id, requiredText(body, "name", 128), requiredText(body, "platform", 64),
      requiredText(body, "membershipId", 256), body.optBoolean("online", false), key,
      body.optString("clientVersion").takeIf(String::isNotBlank), body.optString("harnessVersion").takeIf(String::isNotBlank),
      body.optString("fingerprint").takeIf(String::isNotBlank))

  private fun entryUrl(path: String): okhttp3.HttpUrl = sameOriginUrl(requireAuthEntryPath(path))

  private fun sameOriginUrl(path: String): okhttp3.HttpUrl {
    val url = requireNotNull(base.resolve(path)) { "DSH Hub 地址无效" }
    require(url.scheme == base.scheme && url.host == base.host && url.port == base.port) { "DSH Hub 请求目标无效" }
    return url
  }

  private suspend fun request(method: String, path: String, body: JSONObject? = null, bearer: String? = null): JSONObject =
    requestAt(method, base.newBuilder().addEncodedPathSegments(path).build(), body, bearer)

  private suspend fun requestAt(method: String, url: okhttp3.HttpUrl, body: JSONObject? = null, bearer: String? = null): JSONObject = withContext(Dispatchers.IO) {
    if (url.scheme != base.scheme || url.host != base.host || url.port != base.port) throw IOException("DSH Hub 请求目标无效")
    if (bearer != null) require(validToken(bearer)) { "DSH Hub 授权凭据无效" }
    val request = Request.Builder().url(url)
      .header("Accept", "application/json").apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
      .method(method, body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
    http.newCall(request).execute().use { response ->
      val source = response.body?.source() ?: throw IOException("DSH Hub 返回空响应")
      if (source.request(MAX_RESPONSE_BYTES + 1)) throw IOException("DSH Hub 响应超过上限")
      val text = source.readUtf8()
      if (!response.isSuccessful) {
        val error = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
        val rawCode = (error?.opt("code") as? String)?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) }
        val validError = rawCode != null && error?.opt("message") is String && error.opt("retryable") is Boolean &&
          (!error.has("details") || error.opt("details") is JSONObject)
        throw RemoteServerException(response.code, rawCode.takeIf { validError },
          if (validError) error!!.optBoolean("retryable") else response.code in setOf(401, 403, 429) || response.code >= 500)
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
internal class RemoteServerException(val status: Int, val code: String?, val retryable: Boolean) : IOException("DSH Hub 请求失败（HTTP $status${code?.let { ", $it" }.orEmpty()}）") {
  val isCredentialRejected: Boolean get() = status in setOf(401, 403) && code in setOf(
    "AUTH_REQUIRED", "AUTH_INVALID", "TOKEN_EXPIRED", "DEVICE_REVOKED", "ACCOUNT_AUTH_REQUIRED")
}
