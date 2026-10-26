pluginManagement {
  repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories { google(); mavenCentral() }
}
rootProject.name = "Lagoon"
// The hub's container build has no Android SDK: LAGOON_HUB_ONLY=1 configures only the hub.
if (System.getenv("LAGOON_HUB_ONLY") != "1") include(":app")
include(":hub")
