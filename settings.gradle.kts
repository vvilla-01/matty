pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Matty"
include(":app")

// The stand-in UnifiedPush distributor for the dev rig (tools/dev-server/README.md): part of the
// build only when asked for, with -PdevDistributor=true. Without it the build is the app alone.
if (providers.gradleProperty("devDistributor").orNull == "true") {
    include(":devdistributor")
    project(":devdistributor").projectDir = file("tools/dev-server/distributor")
}
