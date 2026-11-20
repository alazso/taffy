pluginManagement {
    includeBuild("tooling")
}

plugins {
    id("so.alaz.taffy.repositories")
}

rootProject.name = "taffy"

// -Ptaffy.paperApi=<version> builds the modules against that paper-api version in place of the catalog's paper
// version. The published catalog is generated from gradle/libs.versions.toml and keeps its paper version.
providers.gradleProperty("taffy.paperApi").orNull?.let { paperApi ->
    dependencyResolutionManagement {
        versionCatalogs {
            create("libs") {
                version("paper", paperApi)
            }
        }
    }
}

// Also read by tooling/taffy-catalog, which gives every module listed a catalog entry.
providers.gradleProperty("taffy.modules").get().split(",").forEach { include(":" + it.trim()) }
