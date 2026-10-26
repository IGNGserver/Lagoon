package com.igng.opencode.lagoon.system

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.igng.opencode.lagoon.core.AppRelease
import com.igng.opencode.lagoon.core.AppReleases
import com.igng.opencode.lagoon.core.AppUpdateCheck
import com.igng.opencode.lagoon.core.AppUpdateFailure
import com.igng.opencode.lagoon.core.AppUpdateSource
import com.igng.opencode.lagoon.core.AppVersion
import com.igng.opencode.lagoon.core.SharedHttp
import com.igng.opencode.lagoon.core.UpdateFetchPolicy
import com.igng.opencode.lagoon.core.MAX_UPDATE_FEED_BYTES
import com.igng.opencode.lagoon.core.readUpdateFeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class AppUpdatePhase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, FAILED }

data class AppUpdateState(
  val phase: AppUpdatePhase = AppUpdatePhase.IDLE,
  val release: AppRelease? = null,
  /** 0..1 while downloading; null when the server did not give a length. */
  val progress: Float? = null,
  val downloadedBytes: Long = 0L,
  val totalBytes: Long? = null,
  val apkPath: String? = null,
  val failure: AppUpdateFailure? = null,
  val failureDetail: String? = null,
  val lastCheckedAt: Long = 0L
)

/**
 * Checks GitHub Releases, downloads the APK and verifies it before handing it to the system installer.
 *
 * The process-wide instance keeps a download alive when Settings leaves the composition. It never
 * touches server profiles or credentials: updates are independent of any connected harness.
 */
@SuppressLint("StaticFieldLeak") // the singleton only retains the application context
class AppUpdater private constructor(private val context: Context) {
  companion object {
    @Volatile private var instance: AppUpdater? = null

    fun get(context: Context): AppUpdater = instance ?: synchronized(this) {
      instance ?: AppUpdater(context.applicationContext).also { instance = it }
    }

    private const val UPDATE_DIR = "updates"
  }

  private val updateDir = File(context.cacheDir, UPDATE_DIR)
  private val feedDir = File(context.filesDir, "update-feed")
  private val releasesCache = File(feedDir, "updates.json")
  private val etagCache = File(feedDir, "updates.etag")
  private val preferences = context.getSharedPreferences("update-policy", Context.MODE_PRIVATE)
  private var fetchPolicy = UpdateFetchPolicy(
    preferences.getLong("attempt", 0), preferences.getLong("automatic", 0),
    preferences.getLong("retry", 0), preferences.getInt("failures", 0).coerceIn(0, 16))
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val apiClient = OkHttpClient.Builder()
    .connectionPool(SharedHttp.connectionPool)
    .dispatcher(SharedHttp.dispatcher)
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()
  private val downloadClient = apiClient.newBuilder().addNetworkInterceptor { chain ->
    val url = chain.request().url
    // Network interceptors run for every request, including each redirect hop.
    if (!url.isHttps || !AppUpdateSource.isTrustedAssetHost(url.host)) {
      throw DownloadFailure(AppUpdateFailure.INVALID_APK, "不受信任的更新地址：${url.host}")
    }
    chain.proceed(chain.request())
  }.build()
  @Volatile private var selectedIncludePrerelease = context.getSharedPreferences("ui", Context.MODE_PRIVATE).let {
    when (it.getString("updateChannel", null)) {
      "stable" -> false
      "preview" -> true
      else -> it.getBoolean("updatePrerelease", true)
    }
  }
  private val _state = MutableStateFlow(AppUpdateState())
  val state: StateFlow<AppUpdateState> = _state.asStateFlow()
  private var checkJob: Job? = null
  private var downloadJob: Job? = null
  @Volatile private var activeDownloadCall: Call? = null
  @Volatile private var cachedBody: String? = null
  @Volatile private var cachedEtag: String? = null

  init {
    // A process restart loses the in-memory state, so any previously downloaded APK is no longer
    // referenced. Keep the releases cache (ETag) but reclaim the APK space.
    runCatching {
      updateDir.listFiles()?.forEach { file ->
        if (file.name.endsWith(".apk") || file.name.endsWith(".apk.part")) file.delete()
      }
    }
  }

  @Synchronized
  fun check(includePrerelease: Boolean, automatic: Boolean = false) {
    if (selectedIncludePrerelease != includePrerelease) selectChannel(includePrerelease)
    if (checkJob?.isCompleted == false || downloadJob?.isCompleted == false) return
    val now = System.currentTimeMillis()
    val allowed = fetchPolicy.allowedAt(automatic)
    if (now < allowed) {
      if (_state.value.release == null && cachedBody() != null) selectChannel(includePrerelease)
      if (!automatic) _state.update { it.copy(failure = AppUpdateFailure.RATE_LIMITED,
        failureDetail = "请在 ${(allowed - now + 999) / 1000} 秒后重试") }
      return
    }
    // Even an explicit retry cannot bypass a persisted server or local cooldown.
    fetchPolicy = fetchPolicy.attempted(now)
    savePolicy()
    _state.update {
      it.copy(
        phase = AppUpdatePhase.CHECKING,
        progress = null,
        failure = null,
        failureDetail = null
      )
    }
    checkJob = scope.launch {
      val fetched = fetchReleases(includePrerelease)
      synchronized(this@AppUpdater) {
      val result = if (fetched is AppUpdateCheck.Failed) fetched else
        cachedBody()?.let { AppReleases.parse(it, currentVersionName(), selectedIncludePrerelease, currentVersionCode()) } ?: fetched
      val current = _state.value
      when (result) {
        is AppUpdateCheck.Available -> {
          val sameRelease = current.release?.version == result.release.version
          val base = if (sameRelease) current else withoutDownload(current)
          val ready = sameRelease && base.apkPath?.let { File(it).isFile } == true
          val size = base.apkPath?.let { File(it).length() }.takeIf { ready }
          _state.value = base.copy(
            phase = if (ready) AppUpdatePhase.READY else AppUpdatePhase.AVAILABLE,
            release = result.release,
            progress = if (ready) 1f else null,
            downloadedBytes = size ?: 0L,
            totalBytes = size ?: result.release.assetSize,
            failure = null,
            failureDetail = null,
            lastCheckedAt = System.currentTimeMillis()
          )
        }
        is AppUpdateCheck.UpToDate -> {
          val base = withoutDownload(current)
          _state.value = base.copy(
            phase = AppUpdatePhase.UP_TO_DATE,
            release = null,
            progress = null,
            downloadedBytes = 0L,
            totalBytes = null,
            failure = null,
            failureDetail = null,
            lastCheckedAt = System.currentTimeMillis()
          )
        }
        is AppUpdateCheck.Failed -> {
          // A previously downloaded APK stays installable; this failure only describes the check.
          val ready = current.apkPath?.let { File(it).isFile } == true
          _state.value = current.copy(
            phase = if (ready) AppUpdatePhase.READY else AppUpdatePhase.FAILED,
            progress = if (ready) 1f else null,
            failure = result.failure,
            failureDetail = result.detail,
            lastCheckedAt = System.currentTimeMillis()
          )
        }
      }
      }
    }
  }

  @Synchronized
  fun download() {
    val release = _state.value.release?.takeIf { AppReleases.isAllowed(it, currentVersionName(), selectedIncludePrerelease) } ?: return
    if (downloadJob?.isCompleted == false || checkJob?.isCompleted == false) return
    updateDir.mkdirs()
    val part = File(updateDir, "Lagoon-${release.version}.apk.part")
    val target = File(updateDir, "Lagoon-${release.version}.apk")
    part.delete()
    target.delete()
    _state.update {
      it.copy(
        phase = AppUpdatePhase.DOWNLOADING,
        progress = if (release.assetSize != null) 0f else null,
        downloadedBytes = 0L,
        totalBytes = release.assetSize,
        apkPath = null,
        failure = null,
        failureDetail = null
      )
    }
    downloadJob = scope.launch {
      try {
        ensureActive()
        val request = Request.Builder()
          .url(release.assetUrl)
          .header("User-Agent", "Lagoon-Android/${currentVersionName()}")
          .build()
        val call = downloadClient.newCall(request)
        activeDownloadCall = call
        try {
          call.execute().use { response ->
            if (!response.isSuccessful) {
              throw DownloadFailure(AppUpdateFailure.HTTP, "HTTP ${response.code}")
            }
            val body = response.body ?: throw DownloadFailure(AppUpdateFailure.NETWORK)
            val total = body.contentLength().takeIf { it > 0 } ?: release.assetSize
            val digest = MessageDigest.getInstance("SHA-256")
            body.byteStream().use { input ->
              FileOutputStream(part).use { output ->
                val buffer = ByteArray(128 * 1024)
                var copied = 0L
                var lastPercent = -1
                while (true) {
                  ensureActive()
                  val count = input.read(buffer)
                  if (count < 0) break
                  output.write(buffer, 0, count)
                  digest.update(buffer, 0, count)
                  copied += count
                  if (copied > 200L * 1024 * 1024 || release.assetSize?.let { copied > it } == true) {
                    throw DownloadFailure(AppUpdateFailure.SIZE_MISMATCH)
                  }
                  val percent = total?.let { length -> ((copied * 100) / length).toInt() }
                  if (percent != lastPercent) {
                    lastPercent = percent ?: -1
                    _state.update { state ->
                      state.copy(
                        progress = total?.let { length -> (copied.toFloat() / length).coerceIn(0f, 1f) },
                        downloadedBytes = copied,
                        totalBytes = total
                      )
                    }
                  }
                }
                if (release.assetSize != null && copied != release.assetSize) {
                  throw DownloadFailure(AppUpdateFailure.SIZE_MISMATCH)
                }
                if (release.assetSize == null && total != null && copied != total) {
                  throw DownloadFailure(AppUpdateFailure.SIZE_MISMATCH)
                }
                if (copied <= 0L) throw DownloadFailure(AppUpdateFailure.SIZE_MISMATCH)
                val actualDigest = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
                if (release.sha256 == null || !actualDigest.equals(release.sha256, ignoreCase = true)) {
                  throw DownloadFailure(AppUpdateFailure.DIGEST_MISMATCH)
                }
              }
            }
          }
        } finally {
          activeDownloadCall = null
        }
        ensureActive()
        if (!part.renameTo(target)) {
          part.copyTo(target, overwrite = true)
          part.delete()
        }
        verifyApk(target, release)
        ensureActive()
        cleanupOldApks(keep = target)
        _state.update {
          it.copy(
            phase = AppUpdatePhase.READY,
            progress = 1f,
            downloadedBytes = target.length(),
            totalBytes = target.length(),
            apkPath = target.absolutePath,
            failure = null,
            failureDetail = null
          )
        }
      } catch (cancel: CancellationException) {
        part.delete()
        target.delete()
        _state.update { state ->
          state.copy(
            phase = if (state.release != null) AppUpdatePhase.AVAILABLE else AppUpdatePhase.IDLE,
            progress = null,
            downloadedBytes = 0L,
            totalBytes = state.release?.assetSize
          )
        }
        throw cancel
      } catch (failure: DownloadFailure) {
        part.delete()
        target.delete()
        _state.update { state ->
          state.copy(
            phase = AppUpdatePhase.FAILED,
            progress = null,
            downloadedBytes = 0L,
            apkPath = null,
            failure = failure.failure,
            failureDetail = failure.detail
          )
        }
      } catch (error: IOException) {
        part.delete()
        target.delete()
        _state.update { state ->
          if (!isActive) {
            state.copy(
              phase = if (state.release != null) AppUpdatePhase.AVAILABLE else AppUpdatePhase.IDLE,
              progress = null,
              downloadedBytes = 0L,
              totalBytes = state.release?.assetSize
            )
          } else {
            state.copy(
              phase = AppUpdatePhase.FAILED,
              progress = null,
              downloadedBytes = 0L,
              apkPath = null,
              failure = AppUpdateFailure.NETWORK,
              failureDetail = error.message
            )
          }
        }
      } catch (error: Exception) {
        part.delete()
        target.delete()
        _state.update { state ->
          if (!isActive) {
            state.copy(
              phase = if (state.release != null) AppUpdatePhase.AVAILABLE else AppUpdatePhase.IDLE,
              progress = null,
              downloadedBytes = 0L,
              totalBytes = state.release?.assetSize
            )
          } else {
            state.copy(
              phase = AppUpdatePhase.FAILED,
              progress = null,
              downloadedBytes = 0L,
              apkPath = null,
              failure = AppUpdateFailure.INVALID_APK,
              failureDetail = error.message
            )
          }
        }
      }
    }
  }

  fun cancelDownload() {
    activeDownloadCall?.cancel()
    downloadJob?.cancel()
  }

  /** Returns the verified APK, or null if cache cleanup removed it (the UI can download again). */
  fun readyApk(): File? {
    val release = _state.value.release ?: return null
    if (!AppReleases.isAllowed(release, currentVersionName(), selectedIncludePrerelease)) return null
    val path = _state.value.apkPath ?: return null
    val file = File(path)
    if (file.isFile) return file
    _state.update {
      it.copy(
        phase = if (it.release != null) AppUpdatePhase.AVAILABLE else AppUpdatePhase.IDLE,
        apkPath = null,
        progress = null,
        downloadedBytes = 0L
      )
    }
    return null
  }

  @Synchronized
  fun installerUri(apk: File): Uri? {
    val ready = readyApk() ?: return null
    if (ready.absolutePath != apk.absolutePath) return null
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
  }

  @Synchronized
  fun selectChannel(includePrerelease: Boolean) {
    selectedIncludePrerelease = includePrerelease
    context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit()
      .putString("updateChannel", if (includePrerelease) "preview" else "stable").apply()
    if (downloadJob?.isCompleted == false) cancelDownload()
    val base = withoutDownload(_state.value)
    val result = cachedBody()?.let { AppReleases.parse(it, currentVersionName(), includePrerelease, currentVersionCode()) }
    _state.value = base.copy(
      phase = if (checkJob?.isCompleted == false) AppUpdatePhase.CHECKING else
        if (result is AppUpdateCheck.Available) AppUpdatePhase.AVAILABLE else AppUpdatePhase.IDLE,
      release = (result as? AppUpdateCheck.Available)?.release,
      failure = null, failureDetail = null)
  }

  private fun savePolicy() {
    preferences.edit().putLong("attempt", fetchPolicy.lastAttempt)
      .putLong("automatic", fetchPolicy.nextAutomatic).putLong("retry", fetchPolicy.retryAt)
      .putInt("failures", fetchPolicy.failures).apply()
  }

  private fun fetchReleases(includePrerelease: Boolean): AppUpdateCheck {
    val now = System.currentTimeMillis()
    val validCache = cachedBody()?.takeIf {
      val result = AppReleases.parse(it, currentVersionName(), includePrerelease, currentVersionCode())
      result !is AppUpdateCheck.Failed || result.failure != AppUpdateFailure.INVALID_RESPONSE
    }
    val request = Request.Builder().url(AppUpdateSource.API_URL)
      .header("Accept", "application/json")
      .header("User-Agent", "Lagoon-Android/${currentVersionName()}")
      .apply { if (validCache != null) cachedEtag()?.let { header("If-None-Match", it) } }
      .build()
    return try {
      apiClient.newCall(request).execute().use { response ->
        if (response.code != 304 && !response.isSuccessful) {
          fetchPolicy = fetchPolicy.failed(now, response.header("Retry-After"), response.header("X-RateLimit-Reset"))
          savePolicy()
          when {
            response.code == 429 || response.code == 403 &&
              (response.header("Retry-After") != null || response.header("X-RateLimit-Remaining") == "0") ->
              AppUpdateCheck.Failed(AppUpdateFailure.RATE_LIMITED, "请在 ${(fetchPolicy.retryAt - now + 999) / 1000} 秒后重试")
            response.code == 404 -> AppUpdateCheck.Failed(AppUpdateFailure.NOT_FOUND)
            else -> AppUpdateCheck.Failed(AppUpdateFailure.HTTP, "HTTP ${response.code}")
          }
        } else {
          val body = if (response.code == 304) validCache.orEmpty() else response.body?.let { data -> data.byteStream().use { readUpdateFeed(it, data.contentLength()) } }.orEmpty()
          val result = AppReleases.parse(body, currentVersionName(), includePrerelease, currentVersionCode())
          if (result is AppUpdateCheck.Failed) {
            fetchPolicy = fetchPolicy.failed(now)
          } else {
            saveCache(body, if (response.code == 304) cachedEtag() else response.header("ETag"))
            fetchPolicy = fetchPolicy.succeeded(now, kotlin.random.Random.nextLong(15 * 60_000L + 1))
          }
          savePolicy()
          result
        }
      }
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (_: Exception) {
      fetchPolicy = fetchPolicy.failed(now)
      savePolicy()
      AppUpdateCheck.Failed(AppUpdateFailure.NETWORK)
    }
  }
  private fun verifyApk(apk: File, release: AppRelease) {
    if (!AppReleases.isAllowed(release, currentVersionName(), selectedIncludePrerelease)) {
      throw DownloadFailure(AppUpdateFailure.INVALID_APK, "当前通道已变更，请重新检查更新")
    }
    val flags = packageFlags()
    val packageManager = context.packageManager
    val archive = packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
      ?: throw DownloadFailure(AppUpdateFailure.INVALID_APK)
    if (archive.packageName != context.packageName) {
      throw DownloadFailure(AppUpdateFailure.PACKAGE_MISMATCH)
    }
    val archiveVersion = archive.versionName?.let(AppVersion::normalize)
    if (archiveVersion != release.version) {
      throw DownloadFailure(AppUpdateFailure.INVALID_APK, "APK 版本为 ${archiveVersion ?: "未知"}")
    }
    val archiveCode = versionCode(archive)
    if (archiveCode <= currentVersionCode() || (release.versionCode != null && archiveCode != release.versionCode)) {
      throw DownloadFailure(AppUpdateFailure.VERSION_REGRESSION, "APK versionCode=$archiveCode")
    }
    val archiveSigners = signerDigests(archive)
    val currentSigners = runCatching {
      signerDigests(packageManager.getPackageInfo(context.packageName, flags))
    }.getOrDefault(emptySet())
    if (archiveSigners.isEmpty() || currentSigners.isEmpty() || archiveSigners != currentSigners) {
      throw DownloadFailure(AppUpdateFailure.SIGNATURE_MISMATCH)
    }
  }

  private fun signerDigests(info: PackageInfo): Set<String> {
    val signatures = if (Build.VERSION.SDK_INT >= 28) {
      info.signingInfo?.apkContentsSigners.orEmpty()
    } else {
      @Suppress("DEPRECATION")
      info.signatures.orEmpty()
    }
    return signatures.mapTo(linkedSetOf()) { signature ->
      MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
    }
  }

  @Suppress("DEPRECATION")
  private fun packageFlags(): Int =
    if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
    else PackageManager.GET_SIGNATURES

  private fun versionCode(info: PackageInfo): Long =
    if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()

  private fun currentVersionCode(): Long = runCatching {
    versionCode(context.packageManager.getPackageInfo(context.packageName, 0))
  }.getOrDefault(0L)

  private fun currentVersionName(): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
  }.getOrNull()?.takeIf { it.isNotBlank() } ?: "0.0.0"

  private fun withoutDownload(state: AppUpdateState): AppUpdateState {
    state.apkPath?.let { path -> runCatching { File(path).delete() } }
    return state.copy(apkPath = null, progress = null, downloadedBytes = 0L)
  }

  private fun cleanupOldApks(keep: File) {
    runCatching {
      updateDir.listFiles()?.forEach { file ->
        if (file != keep && (file.name.endsWith(".apk") || file.name.endsWith(".apk.part"))) file.delete()
      }
    }
  }

  private fun cachedBody(): String? {
    cachedBody?.let { return it }
    val text = runCatching { releasesCache.takeIf { it.isFile && it.length() <= MAX_UPDATE_FEED_BYTES }?.inputStream()?.use { readUpdateFeed(it) } }.getOrNull()
    cachedBody = text
    return text
  }

  private fun cachedEtag(): String? {
    cachedEtag?.let { return it }
    val text = runCatching { etagCache.takeIf { it.isFile && it.length() <= 4096 }?.readText()?.trim() }.getOrNull()
      ?.takeIf { it.isNotEmpty() }
    cachedEtag = text
    return text
  }

  private fun saveCache(body: String, etag: String?) {
    cachedBody = body
    cachedEtag = etag
    runCatching {
      feedDir.mkdirs()
      val bodyTemp = File(feedDir, "updates.json.tmp")
      bodyTemp.writeText(body)
      if (!bodyTemp.renameTo(releasesCache)) {
        bodyTemp.copyTo(releasesCache, overwrite = true)
        bodyTemp.delete()
      }
      if (etag == null) {
        etagCache.delete()
      } else {
        val etagTemp = File(feedDir, "updates.etag.tmp")
        etagTemp.writeText(etag)
        if (!etagTemp.renameTo(etagCache)) {
          etagTemp.copyTo(etagCache, overwrite = true)
          etagTemp.delete()
        }
      }
    }
  }

  private class DownloadFailure(
    val failure: AppUpdateFailure,
    val detail: String? = null
  ) : IOException(detail)
}
