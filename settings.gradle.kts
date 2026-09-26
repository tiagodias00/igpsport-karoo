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
        // karoo-ext without a GitHub token (GitHub Packages always requires auth).
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "igpsport-karoo"
include(":protocol")
include(":app")
