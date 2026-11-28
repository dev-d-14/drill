pluginManagement {
    repositories {
        google()
        // Google's copy of Maven Central: GitHub's runners get rate-limited (HTTP 429) by Maven Central itself.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's copy of Maven Central: GitHub's runners get rate-limited (HTTP 429) by Maven Central itself.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "Drill"
include(":app")
include(":sthira")
