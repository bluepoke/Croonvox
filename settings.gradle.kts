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

rootProject.name = "croonvox"

include(":app")
include(":core")
include(":data")
include(":engine")

// Throwaway spikes (not shipped).
include(":spikes:audio-engine")
