plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}

// Local release builds may use the debug key for verification. Every publishing build sets
// REQUIRE_RELEASE_SIGNING and must use the protected keystore and fixed certificate.
val releaseKeystorePath: String? = System.getenv("LAGOON_KEYSTORE_FILE") ?: System.getenv("OPENCODE_LAGOON_KEYSTORE_FILE")
val releaseKeystorePassword: String? = System.getenv("LAGOON_KEYSTORE_PASSWORD") ?: System.getenv("OPENCODE_LAGOON_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("LAGOON_KEY_ALIAS") ?: System.getenv("OPENCODE_LAGOON_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("LAGOON_KEY_PASSWORD") ?: System.getenv("OPENCODE_LAGOON_KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }

val requireReleaseSigning = System.getenv("LAGOON_REQUIRE_RELEASE_SIGNING") == "true" ||
  System.getenv("OPENCODE_LAGOON_REQUIRE_RELEASE_SIGNING") == "true"
if (requireReleaseSigning) {
  require(hasReleaseSigning && file(releaseKeystorePath!!).isFile) { "发布构建必须提供完整的稳定签名输入" }
}
android {
  namespace = "com.igng.opencode.lagoon"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.igng.opencode.lagoon"
    minSdk = 26
    targetSdk = 36
    versionCode = (System.getenv("LAGOON_VERSION_CODE") ?: System.getenv("OPENCODE_LAGOON_VERSION_CODE"))?.toInt() ?: 3
    versionName = System.getenv("LAGOON_VERSION_NAME") ?: System.getenv("OPENCODE_LAGOON_VERSION_NAME") ?: "0.1.0"
  }
  if (hasReleaseSigning) {
    signingConfigs {
      create("release") {
        storeFile = file(releaseKeystorePath!!)
        storePassword = releaseKeystorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      }
    }
  }
  buildTypes {
    release {
      // A distributable build must never be debuggable; keep the debug key as a non-publishable
      // fallback so local `assembleRelease` still works without the production keystore.
      isDebuggable = false
      signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
    }
  }
  buildFeatures { compose = true }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlin { jvmToolchain(17) }
  testOptions { unitTests.isReturnDefaultValues = true }
}
// Unit tests read the shared cross-language contract from the repository root.
tasks.withType<Test>().configureEach {
  workingDir = rootDir
  // PopupScopeRegressionTest 直接扫描 UI 源码，源码变化必须触发重跑而不是 UP-TO-DATE。
  inputs.dir("src/main/java")
}
dependencies {
  implementation("androidx.core:core-ktx:1.17.0")
  // Must stay >= 1.12.0: MIUIX popups register androidx.navigationevent NavigationBackHandlers,
  // and only ComponentActivity from Activity 1.12+ implements NavigationEventDispatcherOwner
  // (and sets the view-tree owner). Without it every MIUIX SuperDialog/SuperBottomSheet throws
  // "No NavigationEventDispatcher was provided via LocalNavigationEventDispatcherOwner".
  implementation("androidx.activity:activity-compose:1.12.0")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
  // 后台定时刷新（BackgroundSyncWorker）：Android 上唯一可靠的周期性后台执行方式。
  implementation("androidx.work:work-runtime-ktx:2.10.1")
  // 每页帧耗时进入"复制诊断"（FrameStats）：真机上流畅度改动的前后对照。
  implementation("androidx.metrics:metrics-performance:1.0.0")
  implementation(platform("androidx.compose:compose-bom:2024.12.01"))
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.foundation:foundation")
  implementation("org.commonmark:commonmark:0.30.0")
  implementation("org.commonmark:commonmark-ext-gfm-tables:0.30.0")
  implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.30.0")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
  implementation("io.github.kyant0:shapes-android:1.2.0")
  implementation("top.yukonga.miuix.kmp:miuix-android:0.8.8")
  implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.8.8")
  testImplementation("junit:junit:4.13.2")
  testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
  testImplementation("org.json:json:20240303")
  // TranscriptStore's SQL runs against a real SQLite in JVM tests (Android's SQLiteDatabase is device-only).
  testImplementation("org.xerial:sqlite-jdbc:3.46.1.0")
}

tasks.register("printReleaseApkPath") {
  doLast { println(layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile.absolutePath) }
}
