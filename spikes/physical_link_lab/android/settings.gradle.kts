// GHOSTLINK M1 Link Lab — Android app (separate Gradle build).
// Open THIS directory in Android Studio. The pure-JVM lab modules are pulled in as an included build.
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

rootProject.name = "ghostlink-link-lab-android"
includeBuild("..")
include(":app")
