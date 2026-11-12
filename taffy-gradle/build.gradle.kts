plugins {
    `kotlin-dsl`
    `maven-publish`
}

description = "Gradle convention plugins shared by the so.alaz plugins"

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(libs.shadow.gradle.plugin)
    implementation(libs.foojay.resolver)

    testImplementation(gradleTestKit())
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
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
    from(rootProject.layout.projectDirectory.file("LICENSE")) {
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
    systemProperty("taffy.testRepository", testRepository.get().asFile.toURI().toString())
    systemProperty("taffy.version", project.version.toString())
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Taffy Gradle")
            description.set(project.description)
        }
    }
}
