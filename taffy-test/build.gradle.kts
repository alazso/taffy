plugins {
    id("so.alaz.library")
}

description = "Taffy test: helpers for testing Paper plugin code without a server"

dependencies {
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.paper.api)
    implementation(libs.mockito.core)

    testImplementation(libs.paper.api)
    testImplementation(libs.jetbrains.annotations)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
