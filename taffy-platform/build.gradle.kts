plugins {
    id("so.alaz.library")
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
