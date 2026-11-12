rootProject.name = "taffy"

plugins {
    // The version catalog is not available in settings scripts; this literal is mirrored by `foojay` in the catalog.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

include(":taffy-catalog")
include(":taffy-gradle")
