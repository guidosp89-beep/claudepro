// GHOSTLINK M1 Physical Link Lab — JVM build (cloud-testable).
// Isolated spike: nothing here may become a dependency of core/, android/ or firmware/.
// The Android lab app lives in ./android as a separate Gradle build that includes this one.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "physical-link-lab"

include("codecs", "decoder", "benchmark", "generators", "labtools")
