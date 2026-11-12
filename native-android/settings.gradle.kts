pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // LiveKit's audio-switch helper (com.github.davidliu:audioswitch) is
        // only published to JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "JWGardenServicesCRM"
include(":app")
