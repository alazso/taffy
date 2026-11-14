import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
    `maven-publish`
}

description = "Gradle convention plugins shared by the so.alaz plugins"

// Gradle loads these plugins into its own JVM, which may be older than the Java 25 toolchain the plugins configure.
kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    implementation(libs.shadow.gradle.plugin)
    implementation(libs.foojay.resolver)

    testImplementation(gradleTestKit())
    testImplementation(alaz.junit.jupiter)
    testImplementation(alaz.assertj.core)
    testRuntimeOnly(alaz.junit.platform.launcher)
}

val generatedSourcesDirectory: Provider<Directory> =
    layout.buildDirectory.dir("generated/sources/taffyBuild/kotlin/main")

val generateTaffyBuild = tasks.register("generateTaffyBuild") {
    val taffyVersion = project.version.toString()
    val outputDirectory = generatedSourcesDirectory
    inputs.property("version", taffyVersion)
    outputs.dir(outputDirectory)
    doLast {
        val source = listOf(
            "package so.alaz.taffy.gradle",
            "",
            "/** Generated from gradle.properties at build time. Do not edit. */",
            "internal object TaffyBuild {",
            "    const val VERSION: String = \"$taffyVersion\"",
            "}",
            ""
        ).joinToString("\n")
        val file = outputDirectory.get().file("so/alaz/taffy/gradle/TaffyBuild.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(source)
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateTaffyBuild)
}

tasks.withType<Jar>().configureEach {
    from(rootProject.layout.projectDirectory.file("../LICENSE")) {
        into("META-INF")
    }
}

val testRepository: Provider<Directory> = rootProject.layout.buildDirectory.dir("test-repo")

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
    dependsOn(":taffy-catalog:publishCatalogPublicationToTestRepository")
    inputs.file(rootProject.layout.projectDirectory.file("../gradle/libs.versions.toml"))
        .withPropertyName("publishedCatalog").withPathSensitivity(PathSensitivity.NONE)
    inputs.file(rootProject.layout.projectDirectory.file("taffy-catalog/build.gradle.kts"))
        .withPropertyName("catalogBuildScript").withPathSensitivity(PathSensitivity.NONE)
    inputs.file(rootProject.layout.projectDirectory.file("../gradle.properties"))
        .withPropertyName("taffyProperties").withPathSensitivity(PathSensitivity.NONE)
    val repositoryUri = testRepository.map { it.asFile.toURI().toString().trimEnd('/') + "/" }
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dtaffy.testRepository=" + repositoryUri.get()) })
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Taffy Gradle")
            description.set(project.description)
        }
    }
}
