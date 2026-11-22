plugins {
    id("so.alaz.taffy.library")
}

description = "Taffy hooks: custom items, economy, permissions, regions, placeholders and holograms behind Taffy interfaces"

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
    compileOnly(libs.floodgate.api) { isTransitive = false }
    compileOnly(libs.geyser.api) { isTransitive = false }
    compileOnly(libs.geyser.base.api) { isTransitive = false }
    compileOnly(libs.placeholderapi) { isTransitive = false }
    compileOnly(libs.miniplaceholders.api) { isTransitive = false }
    compileOnly(libs.decentholograms) { isTransitive = false }
    compileOnly(libs.fancyholograms) { isTransitive = false }
    compileOnly(libs.holograms) { isTransitive = false }
    compileOnly(libs.hologramlib) { isTransitive = false }

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
    testImplementation(libs.floodgate.api) { isTransitive = false }
    testImplementation(libs.geyser.api) { isTransitive = false }
    testImplementation(libs.geyser.base.api) { isTransitive = false }
    testImplementation(libs.placeholderapi) { isTransitive = false }
    testImplementation(libs.miniplaceholders.api) { isTransitive = false }
    testImplementation(libs.decentholograms) { isTransitive = false }
    testImplementation(libs.fancyholograms) { isTransitive = false }
    testImplementation(libs.holograms) { isTransitive = false }
    testImplementation(libs.hologramlib) { isTransitive = false }
    testImplementation(libs.packetevents.api) { isTransitive = false }
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    // Nexo is Kotlin-compiled and declared non-transitively, so its kotlin.* annotation types are absent.
    options.compilerArgs.add("-Xlint:-classfile")
    // The HologramLib jar carries its own copy of Adventure 4 under net.kyori.adventure. Searched last, the
    // Adventure of paper-api is the one the sources compile against.
    classpath = classpath.filter { !it.name.startsWith("HologramLib-") } +
        classpath.filter { it.name.startsWith("HologramLib-") }
}

tasks.withType<Test>().configureEach {
    // Searched last for the same reason: the Adventure of paper-api is the one the tests load.
    classpath = classpath.filter { !it.name.startsWith("HologramLib-") } +
        classpath.filter { it.name.startsWith("HologramLib-") }
}
