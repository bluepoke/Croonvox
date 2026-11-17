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
include(":separation")
include(":lyrics")

// Throwaway spikes (not shipped).
include(":spikes:audio-engine")
include(":spikes:separation-core")
include(":spikes:separation")
