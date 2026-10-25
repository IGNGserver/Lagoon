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
        put("state", "uploaded")
        if (digest != null) put("digest", digest)
      })
    })
  }

  private fun releases(vararg items: JSONObject) = JSONObject().apply {
    put("schemaVersion", 1)
    put("repository", AppUpdateSource.REPOSITORY)
    put("generatedAt", "2026-10-25T00:00:00Z")
    put("releases", JSONArray().apply { items.forEach { put(it) } })
  }.toString()

  @Test fun semverOrdersPrereleaseIdentifiersAndStableVersions() {
    assertEquals(1, AppVersion.compare("0.3.0-rc.10", "0.3.0-rc.9"))
    assertEquals(1, AppVersion.compare("0.3.0", "0.3.0-rc.10"))
    assertEquals(-1, AppVersion.compare("0.3.0-rc.4", "0.3.0"))
    assertEquals(0, AppVersion.compare("v0.3.0-rc.4", "0.3.0-rc.4"))
    assertEquals(1, AppVersion.compare("0.2.10", "0.2.9"))
    assertEquals(-1, AppVersion.compare("1.0.0-alpha", "1.0.0-alpha.1"))
    assertNull(AppVersion.compare("nightly", "0.3.0"))
    assertNull(AppVersion.parse("01.0.0"))
    assertNull(AppVersion.parse("1.0.0-rc.01"))
    assertEquals(0, AppVersion.compare("1.0.0+one", "1.0.0+two"))
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

  @Test fun newerIncompleteReleaseDoesNotHideAnInstallableUpdate() {
    val check = AppReleases.parse(releases(
      release("v0.3.0-rc.4", assetName = null),
      release("v0.3.0-rc.3")
    ), currentVersion = "0.3.0-rc.2", includePrerelease = true)

    assertEquals("0.3.0-rc.3", (check as AppUpdateCheck.Available).release.version)
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

    assertEquals(AppUpdateCheck.UpToDate, check)
  }

  @Test fun missingDigestIsNotInstallable() {
    val check = AppReleases.parse(releases(
      release("v0.3.0-rc.4", digest = null)
    ), currentVersion = "0.3.0-rc.3", includePrerelease = true)

    assertEquals(AppUpdateCheck.UpToDate, check)
  }

  @Test fun wrongAssetNameIsNotAnInstallableUpdate() {
    val check = AppReleases.parse(releases(
      release("v0.3.0-rc.4", assetName = "Lagoon-universal.apk")
    ), currentVersion = "0.3.0-rc.3", includePrerelease = true)

    assertEquals(AppUpdateCheck.UpToDate, check)
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

    assertEquals(AppUpdateCheck.UpToDate, check)
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

  @Test fun stableRejectsAnRcEvenWhenGithubFlagIsWrong() {
    assertEquals(AppUpdateCheck.UpToDate, AppReleases.parse(releases(
      release("v0.3.0-rc.4", prerelease = false)), "0.2.4", includePrerelease = false))
  }

  @Test fun equalOrLowerVersionCodeCannotBeSelected() {
    val item = release("v0.3.0-rc.4")
    item.getJSONArray("assets").getJSONObject(0).put("versionCode", 42)
    assertEquals(AppUpdateCheck.UpToDate,
      AppReleases.parse(releases(item), "0.3.0-rc.3", true, currentVersionCode = 42))
  }
  @Test fun cachedFeedCanBeReselectedAfterSwitchingToStableAndRejectsStaleInstall() {
    val feed = releases(release("v0.3.0-rc.4"), release("v0.2.5"))
    val preview = (AppReleases.parse(feed, "0.2.4", true) as AppUpdateCheck.Available).release
    val stable = (AppReleases.parse(feed, "0.2.4", false) as AppUpdateCheck.Available).release
    assertEquals("0.2.5", stable.version)
    assertFalse(AppReleases.isAllowed(preview, "0.2.4", false))
    assertTrue(AppReleases.isAllowed(stable, "0.2.4", false))
  }

  @Test fun foreignOrUnsupportedFeedsAreRejected() {
    assertEquals(AppUpdateCheck.Failed(AppUpdateFailure.INVALID_RESPONSE),
      AppReleases.parse(releases().replace("IGNGserver/Lagoon", "evil/other"), "0.2.4", false))
    assertEquals(AppUpdateCheck.Failed(AppUpdateFailure.INVALID_RESPONSE),
      AppReleases.parse(releases().replace("\"schemaVersion\":1", "\"schemaVersion\":2"), "0.2.4", false))
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
