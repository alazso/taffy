import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import so.alaz.taffy.gradle.CoverageExtension
import so.alaz.taffy.gradle.CollectDependencyLicenses
import so.alaz.taffy.gradle.GenerateRuntimeLibraries
import so.alaz.taffy.gradle.PaperPluginExtension
import so.alaz.taffy.gradle.PluginGroup
import so.alaz.taffy.gradle.TaffyBuild
import so.alaz.taffy.gradle.VerifyPluginLoader

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
    packageName.set(provider { project.group.toString() })
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

val verifyPluginLoader = tasks.register<VerifyPluginLoader>("verifyPluginLoader") {
    group = "verification"
    description = "Fails when runtime libraries are listed and paper-plugin.yml has no top-level loader key."
    coordinates.set(paperPlugin.runtimeLibraries)
    descriptors.from(
        sourceSets.named("main").map { main -> main.resources.matching { include("paper-plugin.yml") } },
    )
    projectDirectory.set(layout.projectDirectory)
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(verifyPluginLoader)
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("paper-plugin.yml") {
        expand(props) {
            escapeBackslash.set(true)
        }
    }
}

val collectDependencyLicenses = tasks.register<CollectDependencyLicenses>("collectDependencyLicenses") {
    description = "Copies the license files of the shaded dependency jars to META-INF/licenses/<jar name>/."
    // A provider mapped from the shadowJar task would make this task depend on it.
    jars.from(provider { tasks.named<ShadowJar>("shadowJar").get().includedDependencies })
    outputDirectory.set(layout.buildDirectory.dir("generated/resources/dependencyLicenses"))
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    mergeServiceFiles()
    filesMatching("META-INF/services/**") {
        // ServiceFileTransformer does not take this descriptor, so a second copy of it would be a second jar entry.
        if (name != "org.codehaus.groovy.runtime.ExtensionModule") {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
    }
    eachFile(CollectDependencyLicenses.DropShadedCopies(layout.buildDirectory.dir("tmp/.cache/expanded")))
    from(collectDependencyLicenses)
    val libraries = PluginGroup.requireSegments(project.group.toString()) + ".libs"
    relocate("org.bstats", "$libraries.bstats") {
        include("org.bstats.**")
    }
    relocate("dev.faststats", "$libraries.faststats") {
        include("dev.faststats.**")
    }
    relocate("so.alaz.taffy", "$libraries.taffy") {
        include("so.alaz.taffy.**")
    }
}

tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
}
