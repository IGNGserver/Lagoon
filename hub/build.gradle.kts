plugins {
  id("org.jetbrains.kotlin.jvm")
  application
}

kotlin { jvmToolchain(17) }

// The hub compiles the same platform-neutral sources as the Android app (harness protocols, models,
// task reducers, transcript projection, the per-server monitor); see docs/HUB_DESIGN.md.
sourceSets { main { kotlin.srcDir("../shared/src/main/kotlin") } }

dependencies {
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
  // Android ships org.json in the platform; the JVM hub needs the artifact.
  implementation("org.json:json:20240303")
  implementation("org.commonmark:commonmark:0.30.0")
  implementation("org.commonmark:commonmark-ext-gfm-tables:0.30.0")
  implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.30.0")
  testImplementation("junit:junit:4.13.2")
  testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

application {
  mainClass.set("com.igng.lagoon.hub.MainKt")
  applicationName = "lagoon-hub"
}

tasks.withType<Test>().configureEach {
  // Contract fixtures shared with the web client live in the repository.
  workingDir = rootDir
  // Timeline times in the shared contract are written in UTC.
  systemProperty("user.timezone", "UTC")
  environment("UPDATE_CONTRACT", System.getenv("UPDATE_CONTRACT") ?: "")
}
