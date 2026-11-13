plugins {
    id("so.alaz.library")
}

description = "Taffy platform: the classes every other Taffy module builds on"

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
