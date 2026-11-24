plugins {
    id("so.alaz.taffy.library")
}

description = "Taffy config: strict YAML loading, typed readers and reload for plugin settings"

dependencies {
    api(project(":taffy-platform"))
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.paper.api)

    testImplementation(project(":taffy-test"))
    testImplementation(libs.paper.api)
    testImplementation(libs.jetbrains.annotations)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
