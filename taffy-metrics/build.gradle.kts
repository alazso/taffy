plugins {
    id("so.alaz.library")
}

description = "Taffy metrics: bStats and FastStats charts from one definition, with handled errors from ErrorReports"

dependencies {
    api(project(":taffy-platform"))
    implementation(libs.bstats.bukkit)
    implementation(libs.faststats.bukkit)
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

tasks.withType<Test>().configureEach {
    // bStats refuses unrelocated classes without the first; FastStats reads its server addresses from the other two.
    systemProperty("bstats.relocatecheck", "false")
    systemProperty("faststats.metrics-server", "http://127.0.0.1:9")
    systemProperty("faststats.error-tracker-server", "http://127.0.0.1:9")
}

val generateTaffyVersion = tasks.register("generateTaffyVersion") {
    val taffyVersion = project.version.toString()
    val outputDirectory = layout.buildDirectory.dir("generated/sources/taffyVersion/java/main")
    inputs.property("version", taffyVersion)
    outputs.dir(outputDirectory)
    doLast {
        val source = listOf(
            "package so.alaz.taffy.metrics;",
            "",
            "/** The Taffy version this copy was built as. Generated at build time. */",
            "interface TaffyVersion {",
            "    String VERSION = \"$taffyVersion\";",
            "}",
            "",
        ).joinToString("\n")
        val file = outputDirectory.get().file("so/alaz/taffy/metrics/TaffyVersion.java").asFile
        file.parentFile.mkdirs()
        file.writeText(source)
    }
}

sourceSets.named("main") {
    java.srcDir(generateTaffyVersion)
}
