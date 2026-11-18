import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import so.alaz.taffy.gradle.CoverageExtension
import so.alaz.taffy.gradle.GenerateRuntimeLibraries
import so.alaz.taffy.gradle.PaperPluginExtension

plugins {
    id("so.alaz.taffy.coverage")
    id("com.gradleup.shadow")
}

val paperPlugin = extensions.create<PaperPluginExtension>("paperPlugin")

extensions.configure<CoverageExtension> {
    conventionExclusions.add("**/RuntimeLibraries.class")
}

tasks.withType<JavaCompile>().configureEach {
    // Kotlin-compiled integration APIs are declared non-transitively, so their kotlin.* annotation types are absent.
    options.compilerArgs.add("-Xlint:-classfile")
}

val generateRuntimeLibraries = tasks.register<GenerateRuntimeLibraries>("generateRuntimeLibraries") {
    packageName.set(project.group.toString())
    coordinates.set(paperPlugin.runtimeLibraries)
    outputDirectory.set(layout.buildDirectory.dir("generated/sources/runtimeLibraries/java/main"))
}

sourceSets.named("main") {
    java.srcDir(generateRuntimeLibraries)
}

tasks.named<ProcessResources>("processResources") {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    val libraries = project.group.toString() + ".libs"
    relocate("org.bstats", "$libraries.bstats")
    relocate("dev.faststats", "$libraries.faststats")
    relocate("so.alaz.taffy", "$libraries.taffy")
}

tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
}
