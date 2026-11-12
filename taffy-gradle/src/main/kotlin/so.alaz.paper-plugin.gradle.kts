import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import so.alaz.taffy.gradle.GenerateRuntimeLibraries
import so.alaz.taffy.gradle.PaperPluginExtension

plugins {
    `java-library`
    jacoco
    id("com.gradleup.shadow")
}

val paperPlugin = extensions.create<PaperPluginExtension>("paperPlugin")
paperPlugin.coverageMinimum.convention("0.80".toBigDecimal())

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Kotlin-compiled integration APIs are declared non-transitively, so their kotlin.* annotation types are absent.
    options.compilerArgs.add("-Xlint:all,-processing,-serial,-classfile")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

val mainClasses: FileCollection = sourceSets.named("main").get().output.classesDirs

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("test"))
}

afterEvaluate {
    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        classDirectories.setFrom(mainClasses.asFileTree.matching { exclude(paperPlugin.coverageExclusions.get()) })
        violationRules {
            rule {
                element = "BUNDLE"
                limit {
                    counter = "INSTRUCTION"
                    value = "COVEREDRATIO"
                    minimum = paperPlugin.coverageMinimum.get()
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:none", "-quiet")
        encoding = "UTF-8"
    }
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

tasks.withType<Jar>().configureEach {
    from(rootProject.layout.projectDirectory.file("LICENSE")) {
        into("META-INF")
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
