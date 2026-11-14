pluginManagement {
    includeBuild("tooling")
}

plugins {
    id("so.alaz.repositories")
}

rootProject.name = "taffy"

// Also read by tooling/taffy-catalog, which gives every module listed a catalog entry.
providers.gradleProperty("taffy.modules").get().split(",").forEach { include(":" + it.trim()) }
