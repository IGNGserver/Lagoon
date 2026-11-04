plugins {
  id("com.android.application") version "8.13.2" apply false
  id("org.jetbrains.kotlin.android") version "2.2.10" apply false
  id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
// Build output goes to BUILD_SCRATCH_ROOT (default ~/.build) on the local disk to keep the
// project volume for sources, namespaced by the checkout path so parallel worktrees cannot collide.
val checkoutKey = Integer.toHexString(rootDir.absolutePath.hashCode())
val scratchRoot = System.getenv("BUILD_SCRATCH_ROOT")?.takeIf { it.isNotBlank() }
  ?: "${System.getProperty("user.home")}/.build"
allprojects {
  layout.buildDirectory.set(file("$scratchRoot/Lagoon/$checkoutKey/${project.name}"))
}
