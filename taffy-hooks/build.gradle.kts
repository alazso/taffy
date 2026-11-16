plugins {
    id("so.alaz.library")
}

description = "Taffy hooks: custom items, economy, permissions, regions and placeholders behind Taffy interfaces"

dependencies {
    api(project(":taffy-platform"))
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.paper.api)
    compileOnly(libs.itemsadder.api) { isTransitive = false }
    compileOnly(libs.nexo) { isTransitive = false }
    compileOnly(libs.oraxen) { isTransitive = false }
    compileOnly(libs.headdatabase.api) { isTransitive = false }
    compileOnly(libs.vault.api) { isTransitive = false }
    compileOnly(libs.luckperms.api) { isTransitive = false }
    compileOnly(libs.worldguard.core) { isTransitive = false }
    compileOnly(libs.worldedit.core) { isTransitive = false }
    compileOnly(libs.worldedit.bukkit) { isTransitive = false }
    compileOnly(libs.placeholderapi) { isTransitive = false }
    compileOnly(libs.miniplaceholders.api) { isTransitive = false }

    testImplementation(project(":taffy-test"))
    testImplementation(libs.paper.api)
    testImplementation(libs.jetbrains.annotations)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.itemsadder.api) { isTransitive = false }
    testImplementation(libs.nexo) { isTransitive = false }
    testImplementation(libs.oraxen) { isTransitive = false }
    testImplementation(libs.headdatabase.api) { isTransitive = false }
    testImplementation(libs.vault.api) { isTransitive = false }
    testImplementation(libs.luckperms.api) { isTransitive = false }
    testImplementation(libs.worldguard.core) { isTransitive = false }
    testImplementation(libs.worldedit.core) { isTransitive = false }
    testImplementation(libs.worldedit.bukkit) { isTransitive = false }
    testImplementation(libs.placeholderapi) { isTransitive = false }
    testImplementation(libs.miniplaceholders.api) { isTransitive = false }
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    // Nexo is Kotlin-compiled and declared non-transitively, so its kotlin.* annotation types are absent.
    options.compilerArgs.add("-Xlint:-classfile")
}
