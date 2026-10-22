package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject

/** One installable APK published on the project's GitHub Releases page. */
data class AppRelease(
  val version: String,
  val tag: String,
  val prerelease: Boolean,
  val publishedAt: String?,
  val notes: String,
  val pageUrl: String,
  val assetName: String,
  val assetUrl: String,
  val assetSize: Long?,
  val sha256: String?
)

enum class AppUpdateFailure {
  NETWORK, RATE_LIMITED, NOT_FOUND, HTTP, INVALID_RESPONSE, UNRECOGNIZED_VERSION, NO_ASSET,
  DIGEST_MISMATCH, SIZE_MISMATCH, INVALID_APK, PACKAGE_MISMATCH, VERSION_REGRESSION, SIGNATURE_MISMATCH
}

sealed interface AppUpdateCheck {
  data object UpToDate : AppUpdateCheck
  data class Available(val release: AppRelease) : AppUpdateCheck
  data class Failed(val failure: AppUpdateFailure, val detail: String? = null) : AppUpdateCheck
}

/** User-facing text for every failure the updater can report. Diagnostics stay in [detail]. */
fun AppUpdateFailure.userMessage(detail: String? = null): String {
  val base = when (this) {
    AppUpdateFailure.NETWORK -> "无法连接 GitHub，请检查网络后重试"
    AppUpdateFailure.RATE_LIMITED -> "GitHub 接口暂时限流，请稍后再试"
    AppUpdateFailure.NOT_FOUND -> "找不到 Lagoon 的发布页面"
    AppUpdateFailure.HTTP -> "更新服务返回异常"
    AppUpdateFailure.INVALID_RESPONSE -> "更新信息格式无法识别"
    AppUpdateFailure.UNRECOGNIZED_VERSION -> "当前版本号无法识别，不能比较更新"
    AppUpdateFailure.NO_ASSET -> "新版本没有可下载的安装包"
    AppUpdateFailure.DIGEST_MISMATCH -> "安装包校验失败，已停止安装"
    AppUpdateFailure.SIZE_MISMATCH -> "安装包大小与发布信息不一致，已停止安装"
    AppUpdateFailure.INVALID_APK -> "下载的安装包无法解析"
    AppUpdateFailure.PACKAGE_MISMATCH -> "安装包不是 Lagoon，已停止安装"
    AppUpdateFailure.VERSION_REGRESSION -> "安装包版本不比当前版本新，已停止安装"
    AppUpdateFailure.SIGNATURE_MISMATCH -> "安装包签名与当前应用不一致；开发版或不同来源的安装包不能直接覆盖"
  }
  return when {
    detail.isNullOrBlank() -> base
    this == AppUpdateFailure.HTTP || this == AppUpdateFailure.INVALID_RESPONSE ||
      this == AppUpdateFailure.INVALID_APK || this == AppUpdateFailure.PACKAGE_MISMATCH ||
      this == AppUpdateFailure.VERSION_REGRESSION -> "$base（$detail）"
    else -> base
  }
}

/** SemVer 2.0 precedence, including the pre-release suffixes used by this project (`-rc.N`). */
object AppVersion {
  data class Parsed(val major: Int, val minor: Int, val patch: Int, val prerelease: List<String>?)

  // Tags may carry a leading v; build metadata is accepted but does not participate in ordering.
  private val pattern = Regex(
    "^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$"
  )

  fun normalize(value: String): String {
    val trimmed = value.trim()
    return if (trimmed.startsWith("v", ignoreCase = true)) trimmed.substring(1) else trimmed
  }

  fun parse(value: String): Parsed? {
    val match = pattern.matchEntire(value.trim()) ?: return null
    val major = match.groupValues[1].toIntOrNull() ?: return null
    val minor = match.groupValues[2].toIntOrNull() ?: return null
    val patch = match.groupValues[3].toIntOrNull() ?: return null
    val rawPrerelease = match.groupValues[4]
    val prerelease = if (rawPrerelease.isEmpty()) null else {
      val identifiers = rawPrerelease.split('.')
      if (identifiers.any { it.isEmpty() }) return null
      identifiers
    }
    return Parsed(major, minor, patch, prerelease)
  }

  /** Returns -1, 0 or 1; null when either side is not a SemVer this app understands. */
  fun compare(left: String, right: String): Int? {
    val a = parse(left) ?: return null
    val b = parse(right) ?: return null
    val core = a.major.compareTo(b.major).takeIf { it != 0 }
      ?: a.minor.compareTo(b.minor).takeIf { it != 0 }
      ?: a.patch.compareTo(b.patch)
    if (core != 0) return core
    if (a.prerelease == null && b.prerelease == null) return 0
    if (a.prerelease == null) return 1
    if (b.prerelease == null) return -1
    for (index in 0 until minOf(a.prerelease.size, b.prerelease.size)) {
      val leftPart = a.prerelease[index]
      val rightPart = b.prerelease[index]
      val leftNumber = leftPart.toIntOrNull()
      val rightNumber = rightPart.toIntOrNull()
      val order = when {
        leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
        // SemVer: numeric identifiers always have lower precedence than alphanumeric ones.
        leftNumber != null -> -1
        rightNumber != null -> 1
        else -> leftPart.compareTo(rightPart)
      }
      if (order != 0) return order
    }
    return a.prerelease.size.compareTo(b.prerelease.size)
  }
}

/** GitHub appends `sha256:<hex>` to release assets; older uploads may not have one. */
object AppUpdateSource {
  const val REPOSITORY = "IGNGserver/Lagoon"
  const val API_URL = "https://api.github.com/repos/$REPOSITORY/releases?per_page=30"

  fun isTrustedAssetUrl(value: String): Boolean {
    val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    return isTrustedAssetHost(uri.host.orEmpty())
  }

  fun isTrustedAssetHost(host: String): Boolean {
    val normalized = host.lowercase()
    return normalized == "github.com" || normalized.endsWith(".githubusercontent.com")
  }
}

object AppReleases {
  private val digestPattern = Regex("^sha256:([0-9a-fA-F]{64})$")

  /**
   * Selects the highest newer release. Pre-releases are included only when [includePrerelease] is
   * true. A newer release without the expected APK is a hard failure instead of silently offering
   * an older version.
   */
  fun parse(json: String, currentVersion: String, includePrerelease: Boolean): AppUpdateCheck {
    if (AppVersion.parse(currentVersion) == null) {
      return AppUpdateCheck.Failed(AppUpdateFailure.UNRECOGNIZED_VERSION)
    }
    val array = runCatching { JSONArray(json) }.getOrElse {
      return AppUpdateCheck.Failed(AppUpdateFailure.INVALID_RESPONSE)
    }

    var highest: JSONObject? = null
    var highestVersion: String? = null
    var highestPrerelease = false
    for (index in 0 until array.length()) {
      val item = array.optJSONObject(index) ?: continue
      if (item.optBoolean("draft", false)) continue
      val prerelease = item.optBoolean("prerelease", false)
      if (prerelease && !includePrerelease) continue
      val tag = item.optString("tag_name").trim()
      if (tag.isEmpty()) continue
      val version = AppVersion.normalize(tag)
      if (AppVersion.parse(version) == null) continue
      val order = AppVersion.compare(version, currentVersion) ?: continue
      if (order <= 0) continue
      val previousOrder = highestVersion?.let { AppVersion.compare(version, it) }
      if (highest == null || previousOrder != null && (previousOrder > 0 ||
          previousOrder == 0 && highestPrerelease && !prerelease)) {
        highest = item
        highestVersion = version
        highestPrerelease = prerelease
      }
    }

    val candidate = highest ?: return AppUpdateCheck.UpToDate
    val version = highestVersion ?: return AppUpdateCheck.UpToDate
    val asset = findAsset(candidate.optJSONArray("assets"), version)
      ?: return AppUpdateCheck.Failed(AppUpdateFailure.NO_ASSET)
    val assetUrl = asset.optString("browser_download_url").trim()
    if (!AppUpdateSource.isTrustedAssetUrl(assetUrl)) {
      return AppUpdateCheck.Failed(AppUpdateFailure.INVALID_RESPONSE, "安装包地址不可信")
    }
    val size = asset.optLong("size", 0L).takeIf { it > 0 }
    val release = AppRelease(
      version = version,
      tag = candidate.optString("tag_name").trim(),
      prerelease = candidate.optBoolean("prerelease", false),
      publishedAt = candidate.optString("published_at").trim().takeIf { it.isNotEmpty() },
      notes = candidate.optString("body", ""),
      pageUrl = candidate.optString("html_url").trim(),
      assetName = asset.optString("name").trim(),
      assetUrl = assetUrl,
      assetSize = size,
      sha256 = parseDigest(asset)
    )
    return AppUpdateCheck.Available(release)
  }

  private fun findAsset(assets: JSONArray?, version: String): JSONObject? {
    if (assets == null) return null
    val expected = "Lagoon-$version.apk"
    val apkAssets = mutableListOf<JSONObject>()
    for (index in 0 until assets.length()) {
      val asset = assets.optJSONObject(index) ?: continue
      val name = asset.optString("name").trim()
      if (name == expected) return asset
      if (name.startsWith("Lagoon-") && name.endsWith(".apk")) apkAssets += asset
    }
    // One unambiguous APK with an unexpected name is still usable after package and signature checks.
    return apkAssets.singleOrNull()
  }

  private fun parseDigest(asset: JSONObject): String? {
    if (!asset.has("digest") || asset.isNull("digest")) return null
    val match = digestPattern.matchEntire(asset.optString("digest").trim()) ?: return null
    return match.groupValues[1].lowercase()
  }
}
