import so.alaz.taffy.gradle.CoverageExtension

plugins {
    id("so.alaz.java")
    jacoco
}

val coverage = extensions.create<CoverageExtension>("coverage")
coverage.minimum.convention("0.80".toBigDecimal())

tasks.withType<Test>().configureEach {
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
val gatedClasses: FileCollection = files(
    provider {
        mainClasses.asFileTree.matching {
            exclude(coverage.exclusions.get())
            exclude(coverage.conventionExclusions.get())
        }
    },
)

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("test"))
    classDirectories.setFrom(gatedClasses)
}

// Gradle skips jacocoTestCoverageVerification when no execution data file exists, which happens when no test ran.
val verifyCoverageData = tasks.register("verifyCoverageData") {
    dependsOn(tasks.named("test"))
    val classes = gatedClasses
    val executionData = layout.buildDirectory.file("jacoco/test.exec")
    val minimum = coverage.minimum
    val projectPath = project.path
    doLast {
        if (minimum.get().signum() > 0 && !classes.isEmpty && !executionData.get().asFile.exists()) {
            throw GradleException(
                "No coverage data for $projectPath: it has classes under the coverage gate but no test ran. " +
                    "Add tests, exclude the classes with coverage.exclusions, or set coverage.minimum to 0.",
            )
        }
    }
}

// JacocoLimit.minimum is a plain value, so it is read once the build script has set the extension.
afterEvaluate {
    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        violationRules {
            rule {
                element = "BUNDLE"
                limit {
                    counter = "INSTRUCTION"
                    value = "COVEREDRATIO"
                    minimum = coverage.minimum.get()
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestCoverageVerification"), verifyCoverageData)
}
