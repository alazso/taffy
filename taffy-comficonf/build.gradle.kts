plugins {
    id("so.alaz.library")
}

description = "Taffy comficonf: ComfiConf registration, connect guard and YAML file provider"

dependencies {
    api(project(":taffy-platform"))
    api(project(":taffy-config"))
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.paper.api)
    // Provided by the ComfiConf bridge at runtime: never bundled, never relocated, absent from the published metadata.
    compileOnly(libs.comficonf.api)

    testImplementation(project(":taffy-test"))
    testImplementation(libs.paper.api)
    testImplementation(libs.jetbrains.annotations)
    testImplementation(libs.comficonf.api)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
