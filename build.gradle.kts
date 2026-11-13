plugins {
    base
}

// The convention plugins and the catalog are built by the included build; these tasks carry the root lifecycle to it.
// clean is not forwarded: Gradle cannot clean a plugin build in the invocation that loads plugins from it.
val tooling: IncludedBuild = gradle.includedBuild("tooling")

tasks.named("assemble") {
    dependsOn(tooling.task(":taffy-gradle:assemble"), tooling.task(":taffy-catalog:generateCatalogAsToml"))
}

tasks.named("check") {
    dependsOn(tooling.task(":taffy-gradle:check"))
}

tasks.register("publishAllPublicationsToAlazsoRepository") {
    group = "publishing"
    description = "Publishes the convention plugins and the catalog to repo.alaz.so."
    dependsOn(
        tooling.task(":taffy-gradle:publishAllPublicationsToAlazsoRepository"),
        tooling.task(":taffy-catalog:publishAllPublicationsToAlazsoRepository"),
    )
}

tasks.register("publishToMavenLocal") {
    group = "publishing"
    description = "Publishes the convention plugins and the catalog to the local Maven repository."
    dependsOn(
        tooling.task(":taffy-gradle:publishToMavenLocal"),
        tooling.task(":taffy-catalog:publishToMavenLocal"),
    )
}
