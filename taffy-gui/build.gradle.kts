plugins {
    id("so.alaz.library")
}

description = "Taffy gui: chest menus, pagination, click handling and text prompts"

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
