import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import so.alaz.taffy.gradle.CoverageExtension
import so.alaz.taffy.gradle.GenerateRuntimeLibraries
import so.alaz.taffy.gradle.PaperPluginExtension
import so.alaz.taffy.gradle.TaffyBuild

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

// Resolves what compileClasspath resolves, with every paper-api request answered by the floor build.
val paperFloorCompileClasspath = configurations.create("paperFloorCompileClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
    extendsFrom(configurations.getByName("compileOnly"), configurations.getByName("implementation"))
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(
            TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
            objects.named(TargetJvmEnvironment.STANDARD_JVM),
        )
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
    resolutionStrategy.eachDependency {
        if (requested.group == "io.papermc.paper" && requested.name == "paper-api") {
            useVersion(TaffyBuild.PAPER_FLOOR)
            because("the oldest Paper build Taffy supports")
        }
    }
}

tasks.register<JavaCompile>("compileAgainstPaperFloor") {
    group = "verification"
    description = "Compiles the main Java sources against paper-api " + TaffyBuild.PAPER_FLOOR +
        ", the oldest Paper build Taffy supports."
    source = sourceSets.named("main").get().java
    classpath = paperFloorCompileClasspath
    javaCompiler.set(javaToolchains.compilerFor(java.toolchain))
    options.annotationProcessorPath = configurations.getByName("annotationProcessor")
    destinationDirectory.set(layout.buildDirectory.dir("classes/java/paperFloor"))
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
