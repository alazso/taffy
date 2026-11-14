plugins {
    base
}

// The convention plugins and the catalog are built by the included build; these tasks carry the root lifecycle to it.
// clean is not forwarded: Gradle cannot clean a plugin build in the invocation that loads plugins from it.
val tooling: IncludedBuild = gradle.includedBuild("tooling")

tasks.named("assemble") {
    dependsOn(tooling.task(":taffy-gradle:assemble"), tooling.task(":taffy-catalog:generateCatalogAsToml"))
}

val verifyPublicApiRulesTests = tasks.register("verifyPublicApiRulesTests") {
    group = "verification"
    description = "Fails when a module listed in taffy.modules has no PublicApiRulesTest."
    val rulesTests = providers.gradleProperty("taffy.modules").get().split(",").map { it.trim() }.associateWith { module ->
        fileTree(layout.projectDirectory.dir("$module/src/test/java")) { include("**/PublicApiRulesTest.java") }
    }
    inputs.files(rulesTests.values)
    doLast {
        val missing = rulesTests.filterValues { it.isEmpty }.keys
        if (missing.isNotEmpty()) {
            throw GradleException(
                missing.joinToString("\n") { module ->
                    "$module has no src/test/java/**/PublicApiRulesTest.java. Every module listed in " +
                        "taffy.modules checks its design rules with ApiRules from taffy-test."
                },
            )
        }
    }
}

tasks.named("check") {
    dependsOn(tooling.task(":taffy-gradle:check"), verifyPublicApiRulesTests)
}

// Registered here because a task name given on the command line selects every project that has it:
// without a root task, test and publish would run in the runtime modules and skip the tooling build.
tasks.register("test") {
    group = "verification"
    description = "Runs the tests of the build tooling."
    dependsOn(tooling.task(":taffy-gradle:test"))
}

tasks.register("publish") {
    group = "publishing"
    description = "Publishes the build tooling to repo.alaz.so."
    dependsOn("publishAllPublicationsToAlazsoRepository")
}

tasks.register("publishAllPublicationsToAlazsoRepository") {
    group = "publishing"
    description = "Publishes the build tooling to repo.alaz.so."
    dependsOn(
        tooling.task(":taffy-gradle:publishAllPublicationsToAlazsoRepository"),
        tooling.task(":taffy-catalog:publishAllPublicationsToAlazsoRepository"),
    )
}

tasks.register("publishToMavenLocal") {
    group = "publishing"
    description = "Publishes the build tooling to the local Maven repository."
    dependsOn(
        tooling.task(":taffy-gradle:publishToMavenLocal"),
        tooling.task(":taffy-catalog:publishToMavenLocal"),
    )
}
