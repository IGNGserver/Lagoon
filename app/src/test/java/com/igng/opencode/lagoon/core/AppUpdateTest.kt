package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
  private fun release(
    tag: String,
    prerelease: Boolean = tag.contains('-'),
    draft: Boolean = false,
    assetName: String? = "Lagoon-${tag.removePrefix("v")}.apk",
    assetUrl: String? = null,
    digest: String? = "sha256:" + "a".repeat(64)
  ) = JSONObject().apply {
    put("tag_name", tag)
    put("prerelease", prerelease)
    put("draft", draft)
    put("published_at", "2026-10-20T00:02:48Z")
    put("html_url", "https://github.com/IGNGserver/Lagoon/releases/tag/$tag")
    put("body", "本版本说明 $tag")
    put("assets", JSONArray().apply {
      if (assetName != null) put(JSONObject().apply {
        put("name", assetName)
        put("browser_download_url", assetUrl
          ?: "https://github.com/IGNGserver/Lagoon/releases/download/$tag/$assetName")
        put("size", 10_229_763L)
        if (digest != null) put("digest", digest)
      })
    })
  }

  private fun releases(vararg items: JSONObject) = JSONArray().apply {
    items.forEach { put(it) }
  }.toString()

  @Test fun semverOrdersPrereleaseIdentifiersAndStableVersions() {
    assertEquals(1, AppVersion.compare("0.3.0-rc.10", "0.3.0-rc.9"))
    assertEquals(1, AppVersion.compare("0.3.0", "0.3.0-rc.10"))
    assertEquals(-1, AppVersion.compare("0.3.0-rc.4", "0.3.0"))
    assertEquals(0, AppVersion.compare("v0.3.0-rc.4", "0.3.0-rc.4"))
    assertEquals(1, AppVersion.compare("0.2.10", "0.2.9"))
    assertEquals(-1, AppVersion.compare("1.0.0-alpha", "1.0.0-alpha.1"))
    assertNull(AppVersion.compare("nightly", "0.3.0"))
  }

  @Test fun picksHighestNewerReleaseIncludingPrereleases() {
    val check = AppReleases.parse(releases(
      release("v0.3.0-rc.3"),
      release("v0.3.0-rc.4"),
      release("v0.2.4"),
      release("v0.4.0", draft = true)
    ), currentVersion = "0.3.0-rc.3", includePrerelease = true)

    val release = (check as AppUpdateCheck.Available).release
    assertEquals("0.3.0-rc.4", release.version)
    assertEquals("Lagoon-0.3.0-rc.4.apk", release.assetName)
    assertEquals("a".repeat(64), release.sha256)
    assertTrue(release.prerelease)
    assertEquals(10_229_763L, release.assetSize)
  }

  @Test fun stableChannelIgnoresNewerPrereleases() {
    val check = AppReleases.parse(releases(
      release("v0.3.0-rc.4"),
      release("v0.2.4")
    ), currentVersion = "0.2.4", includePrerelease = false)

    assertEquals(AppUpdateCheck.UpToDate, check)
  }

  @Test fun stableChannelStillTakesNewerStableRelease() {
    val check = AppReleases.parse(releases(
      release("v0.3.0-rc.4"),
      release("v0.2.5")
    ), currentVersion = "0.2.4", includePrerelease = false)

    assertEquals("0.2.5", (check as AppUpdateCheck.Available).release.version)
  }

  @Test fun aStableReleaseWinsWhenTheSameVersionIsAlsoAPrerelease() {
    val check = AppReleases.parse(releases(
      release("v0.3.0-rc.4"),
      release("v0.3.0", prerelease = false)
    ), currentVersion = "0.2.4", includePrerelease = true)

    val release = (check as AppUpdateCheck.Available).release
    assertEquals("0.3.0", release.version)
    assertFalse(release.prerelease)
  }

  @Test fun newerReleaseWithoutAnApkFailsInsteadOfOfferingAnOlderVersion() {
    val check = AppReleases.parse(releases(
      release("v0.3.0-rc.4", assetName = null),
      release("v0.3.0-rc.3")
    ), currentVersion = "0.3.0-rc.2", includePrerelease = true)

    assertEquals(AppUpdateCheck.Failed(AppUpdateFailure.NO_ASSET), check)
  }

  @Test fun skipsDrafts() {
    val check = AppReleases.parse(releases(
      release("v0.3.0-rc.4", draft = true),
      release("v0.3.0-rc.3")
    ), currentVersion = "0.3.0-rc.2", includePrerelease = true)

    assertEquals("0.3.0-rc.3", (check as AppUpdateCheck.Available).release.version)
  }

  @Test fun aNewestReleaseWithOnlyAnUntrustedUrlIsAFailure() {
    val check = AppReleases.parse(releases(
      release("v0.3.0-rc.5", assetUrl = "http://github.com/IGNGserver/Lagoon/releases/download/x.apk")
    ), currentVersion = "0.3.0-rc.4", includePrerelease = true)

    assertEquals(AppUpdateCheck.Failed(AppUpdateFailure.INVALID_RESPONSE, "安装包地址不可信"), check)
  }

  @Test fun missingDigestIsAllowedBecauseApkSignatureIsStillCheckedBeforeInstall() {
    val check = AppReleases.parse(releases(
      release("v0.3.0-rc.4", digest = null)
    ), currentVersion = "0.3.0-rc.3", includePrerelease = true)

    assertNull((check as AppUpdateCheck.Available).release.sha256)
  }

  @Test fun oneUnusuallyNamedApkIsUsedAsALastResort() {
    val check = AppReleases.parse(releases(
      release("v0.3.0-rc.4", assetName = "Lagoon-universal.apk")
    ), currentVersion = "0.3.0-rc.3", includePrerelease = true)

    assertEquals("Lagoon-universal.apk", (check as AppUpdateCheck.Available).release.assetName)
  }

  @Test fun multipleUnexpectedApksAreAmbiguousAndRejected() {
    val item = release("v0.3.0-rc.4", assetName = null)
    item.put("assets", JSONArray().apply {
      put(JSONObject().put("name", "Lagoon-a.apk")
        .put("browser_download_url", "https://github.com/IGNGserver/Lagoon/releases/download/v0.3.0-rc.4/Lagoon-a.apk"))
      put(JSONObject().put("name", "Lagoon-b.apk")
        .put("browser_download_url", "https://github.com/IGNGserver/Lagoon/releases/download/v0.3.0-rc.4/Lagoon-b.apk"))
    })
    val check = AppReleases.parse(releases(item), "0.3.0-rc.3", includePrerelease = true)

    assertEquals(AppUpdateCheck.Failed(AppUpdateFailure.NO_ASSET), check)
  }

  @Test fun upToDateWhenCurrentVersionIsNewest() {
    val check = AppReleases.parse(releases(
      release("v0.3.0-rc.4"),
      release("v0.2.4")
    ), currentVersion = "0.3.0-rc.4", includePrerelease = true)

    assertEquals(AppUpdateCheck.UpToDate, check)
  }

  @Test fun malformedJsonAndUnknownCurrentVersionAreReported() {
    assertEquals(
      AppUpdateCheck.Failed(AppUpdateFailure.INVALID_RESPONSE),
      AppReleases.parse("not-json", "0.3.0-rc.4", includePrerelease = true)
    )
    assertEquals(
      AppUpdateCheck.Failed(AppUpdateFailure.UNRECOGNIZED_VERSION),
      AppReleases.parse("[]", "dev-build", includePrerelease = true)
    )
  }

  @Test fun onlyHttpsGithubHostsAreTrustedForDownloads() {
    assertTrue(AppUpdateSource.isTrustedAssetUrl("https://github.com/IGNGserver/Lagoon/releases/download/v1/Lagoon-1.apk"))
    assertTrue(AppUpdateSource.isTrustedAssetUrl("https://release-assets.githubusercontent.com/x/Lagoon-1.apk"))
    assertTrue(AppUpdateSource.isTrustedAssetUrl("https://objects.githubusercontent.com/x/Lagoon-1.apk"))
    assertFalse(AppUpdateSource.isTrustedAssetUrl("http://github.com/IGNGserver/Lagoon/releases/download/v1/Lagoon-1.apk"))
    assertFalse(AppUpdateSource.isTrustedAssetUrl("https://github.com.evil.example/Lagoon-1.apk"))
    assertFalse(AppUpdateSource.isTrustedAssetUrl("https://evil.example/Lagoon-1.apk"))
  }
}
