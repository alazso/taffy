plugins {
    id("so.alaz.taffy.library")
    `java-test-fixtures`
}

description = "Taffy platform: the classes every other Taffy module builds on"

dependencies {
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.paper.api)
    compileOnly(libs.placeholderapi) { isTransitive = false }
    compileOnly(libs.miniplaceholders.api) { isTransitive = false }

    testImplementation(project(":taffy-test"))
    testImplementation(libs.paper.api)
    testImplementation(libs.jetbrains.annotations)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The test fixtures variants carry a capability, which a POM cannot state: Gradle Module Metadata does.
publishing {
    publications {
        named<MavenPublication>("library") {
            suppressPomMetadataWarningsFor("testFixturesApiElements")
            suppressPomMetadataWarningsFor("testFixturesRuntimeElements")
        }
    }
}
