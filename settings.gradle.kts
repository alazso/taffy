pluginManagement {
    includeBuild("tooling")
}

plugins {
    id("so.alaz.repositories")
}

rootProject.name = "taffy"

include(":taffy-platform")
