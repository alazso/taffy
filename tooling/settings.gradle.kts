rootProject.name = "taffy-tooling"

plugins {
    // The version catalog is not available in settings scripts; this literal is mirrored by `foojay` in the catalog.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        exclusiveContent {
            forRepository { gradlePluginPortal() }
            filter { includeGroup("org.gradle.toolchains") }
        }
    }
    versionCatalogs {
        create("alaz") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

include(":taffy-catalog")
include(":taffy-gradle")
