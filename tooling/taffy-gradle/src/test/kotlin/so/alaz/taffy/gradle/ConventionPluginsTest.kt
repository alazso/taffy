package so.alaz.taffy.gradle

import java.io.File
import java.util.zip.ZipFile
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ConventionPluginsTest {

    @TempDir
    lateinit var projectDirectory: File

    private val testRepository: String = System.getProperty("taffy.testRepository")

    private val centralOnly = """
        dependencyResolutionManagement {
            repositories {
                mavenCentral()
            }
        }
    """

    private val junit = """
        dependencies {
            testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
            testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
        }
    """

    private val uncovered = """
        package so.alaz.demo;

        final class Demo {
            static int one() {
                return 1;
            }
        }
    """

    private val covered = """
        package so.alaz.demo;

        public interface Covered {
            static int one() {
                return 1;
            }
        }
    """

    private val coveredTest = """
        package so.alaz.demo;

        import org.junit.jupiter.api.Test;

        class CoveredTest {
            @Test
            void runs() {
                if (Covered.one() != 1) {
                    throw new AssertionError();
                }
            }
        }
    """

    private val unrelatedTest = """
        package so.alaz.demo;

        import org.junit.jupiter.api.Test;

        class UnrelatedTest {
            @Test
            void runs() {
            }
        }
    """

    @Test
    fun paperPluginBuildsOneRelocatedJarAndReusesTheConfigurationCache() {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write(
            "build.gradle.kts",
            """
            plugins {
                id("so.alaz.paper-plugin")
            }
            dependencies {
                implementation("org.bstats:bstats-base:3.2.1")
            }
            coverage {
                exclusions.set(listOf("so/alaz/demo/Demo.class", "dev/faststats/**", "so/alaz/taffy/**"))
            }
            paperPlugin {
                runtimeLibraries.add("com.zaxxer:HikariCP:7.1.0")
            }
            """,
            junit,
        )
        write("src/main/resources/paper-plugin.yml", "name: Demo\nversion: \"\${version}\"\n")
        write(
            "src/main/java/so/alaz/demo/Demo.java",
            """
            package so.alaz.demo;

            final class Demo {
                static final Class<?> METRICS = org.bstats.MetricsBase.class;
                static final String FIRST = RuntimeLibraries.COORDINATES.get(0);
            }
            """,
        )
        write("src/main/java/so/alaz/demo/Covered.java", covered)
        write("src/test/java/so/alaz/demo/CoveredTest.java", coveredTest)
        write("src/main/java/dev/faststats/Fake.java", "package dev.faststats;\n\npublic interface Fake {\n}\n")
        write("src/main/java/so/alaz/taffy/Fake.java", "package so.alaz.taffy;\n\npublic interface Fake {\n}\n")

        val first = run("build", "--configuration-cache")

        assertThat(first.task(":jacocoTestCoverageVerification")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(first.task(":verifyCoverageData")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val libs = projectDirectory.resolve("build/libs").listFiles()!!.map { it.name }
        assertThat(libs).containsExactly("demo-1.2.3.jar")
        ZipFile(projectDirectory.resolve("build/libs/demo-1.2.3.jar")).use { jar ->
            val names = jar.entries().asSequence().map { it.name }.toList()
            assertThat(names).contains(
                "META-INF/LICENSE",
                "so/alaz/demo/Demo.class",
                "so/alaz/demo/RuntimeLibraries.class",
                "so/alaz/demo/libs/bstats/MetricsBase.class",
                "so/alaz/demo/libs/faststats/Fake.class",
                "so/alaz/demo/libs/taffy/Fake.class",
            )
            assertThat(names).noneMatch {
                it.startsWith("org/bstats/") || it.startsWith("dev/faststats/") || it.startsWith("so/alaz/taffy/")
            }
            val descriptor = jar.getInputStream(jar.getEntry("paper-plugin.yml")).reader().readText()
            assertThat(descriptor).contains("version: \"1.2.3\"")
        }
        val generated = projectDirectory
            .resolve("build/generated/sources/runtimeLibraries/java/main/so/alaz/demo/RuntimeLibraries.java")
        assertThat(generated.readText()).contains("\"com.zaxxer:HikariCP:7.1.0\"")

        val second = run("build", "--configuration-cache")

        assertThat(second.output).contains("Reusing configuration cache.")
    }

    @Test
    fun coverageGateFailsBelowTheMinimum() {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write("build.gradle.kts", "plugins {\n    id(\"so.alaz.paper-plugin\")\n}", junit)
        write("src/main/java/so/alaz/demo/Demo.java", uncovered)
        write("src/test/java/so/alaz/demo/UnrelatedTest.java", unrelatedTest)

        val result = runner("check").buildAndFail()

        assertThat(result.task(":jacocoTestCoverageVerification")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output).contains("instructions covered ratio is 0.00, but expected minimum is 0.80")
    }

    @Test
    fun coverageGateLeavesOutExcludedClasses() {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write(
            "build.gradle.kts",
            "plugins {\n    id(\"so.alaz.coverage\")\n}",
            junit,
            "coverage {\n    exclusions.add(\"**/Demo.class\")\n}",
        )
        write("src/main/java/so/alaz/demo/Demo.java", uncovered)
        write("src/main/java/so/alaz/demo/Covered.java", covered)
        write("src/test/java/so/alaz/demo/CoveredTest.java", coveredTest)

        val result = run("check")

        assertThat(result.task(":jacocoTestCoverageVerification")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }

    @Test
    fun coverageMinimumCanBeLowered() {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write(
            "build.gradle.kts",
            "plugins {\n    id(\"so.alaz.coverage\")\n}",
            junit,
            "coverage {\n    minimum.set(\"0.0\".toBigDecimal())\n}",
        )
        write("src/main/java/so/alaz/demo/Demo.java", uncovered)
        write("src/test/java/so/alaz/demo/UnrelatedTest.java", unrelatedTest)

        val result = run("check")

        assertThat(result.task(":jacocoTestCoverageVerification")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }

    @Test
    fun checkFailsWhenAModuleUnderTheGateHasNoTests() {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write("build.gradle.kts", "plugins {\n    id(\"so.alaz.library\")\n}")
        write("src/main/java/so/alaz/demo/Demo.java", uncovered)

        val result = runner("check").buildAndFail()

        assertThat(result.task(":verifyCoverageData")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output).contains("No coverage data for :")
    }

    @Test
    fun libraryPublishesATestedJarWithSourcesAndJavadoc() {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"\ninclude(\":demo-core\")", centralOnly)
        write(
            "demo-core/build.gradle.kts",
            """
            plugins {
                id("so.alaz.library")
            }
            description = "Demo core"
            publishing {
                repositories {
                    maven {
                        name = "local"
                        url = uri(rootProject.layout.buildDirectory.dir("repo"))
                    }
                }
            }
            """,
            junit,
        )
        write("demo-core/src/main/java/so/alaz/demo/Covered.java", covered)
        write("demo-core/src/test/java/so/alaz/demo/CoveredTest.java", coveredTest)

        val result = run("build", ":demo-core:publishLibraryPublicationToLocalRepository")

        assertThat(result.task(":demo-core:test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":demo-core:jacocoTestCoverageVerification")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val published = projectDirectory.resolve("build/repo/so/alaz/demo/demo-core/1.2.3")
        assertThat(published.listFiles()!!.map { it.name }).contains(
            "demo-core-1.2.3.jar",
            "demo-core-1.2.3-sources.jar",
            "demo-core-1.2.3-javadoc.jar",
            "demo-core-1.2.3.pom",
            "demo-core-1.2.3.module",
        )
        ZipFile(published.resolve("demo-core-1.2.3.jar")).use { jar ->
            assertThat(jar.getEntry("META-INF/LICENSE")).isNotNull()
            assertThat(jar.getEntry("so/alaz/demo/Covered.class")).isNotNull()
        }
        assertThat(published.resolve("demo-core-1.2.3.pom").readText()).contains(
            "<groupId>so.alaz.demo</groupId>",
            "<artifactId>demo-core</artifactId>",
            "<description>Demo core</description>",
            "<url>https://github.com/alazso/demo</url>",
        )
    }

    @Test
    fun paperApiPublishesSnapshotsToTheSnapshotsRepositoryAndTakesTheUrlFromTheProperty() {
        project()
        write("gradle.properties", "group=so.alaz.demo\nalaz.repositoryUrl=https://example.org/demo\n")
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write(
            "build.gradle.kts",
            """
            plugins {
                id("so.alaz.paper-api")
            }
            version = "1.2.3-SNAPSHOT"
            tasks.register("showRepository") {
                val repository = publishing.repositories.named<MavenArtifactRepository>("alazso")
                doLast {
                    println("alazso=" + repository.get().url)
                }
            }
            """,
        )

        val result = run("showRepository", "generatePomFileForLibraryPublication")

        assertThat(result.output).contains("alazso=https://repo.alaz.so/snapshots")
        val pom = projectDirectory.resolve("build/publications/library/pom-default.xml").readText()
        assertThat(pom).contains("<url>https://example.org/demo</url>")
    }

    @Test
    fun settingsPluginProvidesTheCatalogAndPaperApiPublishesUnderTheProjectGroup() {
        project()
        write(
            "settings.gradle.kts",
            """
            plugins {
                id("so.alaz.settings")
            }
            rootProject.name = "demo"
            dependencyResolutionManagement {
                repositories {
                    exclusiveContent {
                        forRepository {
                            maven("$testRepository") { name = "test" }
                        }
                        filter {
                            includeGroup("so.alaz.taffy")
                        }
                    }
                }
            }
            include(":demo-api")
            """,
        )
        write(
            "demo-api/build.gradle.kts",
            """
            plugins {
                id("so.alaz.paper-api")
            }
            description = "Demo public API"
            tasks.register("showPaper") {
                val paper = alaz.versions.paper
                doLast {
                    println("paper=" + paper.get())
                }
            }
            """,
        )

        val result = run(":demo-api:showPaper", ":demo-api:generatePomFileForLibraryPublication")

        assertThat(result.output).containsPattern("paper=\\d+\\.\\d+")
        val pom = projectDirectory.resolve("demo-api/build/publications/library/pom-default.xml").readText()
        assertThat(pom).contains(
            "<groupId>so.alaz.demo</groupId>",
            "<artifactId>demo-api</artifactId>",
            "<version>1.2.3</version>",
            "<description>Demo public API</description>",
            "<url>https://github.com/alazso/demo</url>",
        )
    }

    @Test
    fun repositoriesPluginDeclaresRepositoriesWithoutACatalog() {
        project()
        write(
            "settings.gradle.kts",
            """
            plugins {
                id("so.alaz.repositories")
            }
            rootProject.name = "demo"
            println("catalogs=" + dependencyResolutionManagement.versionCatalogs.names)
            println("repositories=" + dependencyResolutionManagement.repositories.joinToString(",") { it.name })
            """,
        )

        val result = run("help")

        assertThat(result.output).contains("catalogs=[]")
        assertThat(result.output).contains(
            "repositories=MavenRepo,papermc,alazso,alazsoSnapshots,placeholderapi,faststats,jitpack," +
                "vaultunlocked,playerpoints,oraxen,nexo,enginehub",
        )
    }

    private fun project() {
        write("LICENSE", "MIT License\n")
        write("gradle.properties", "group=so.alaz.demo\nversion=1.2.3\n")
    }

    private fun run(vararg arguments: String): BuildResult = runner(*arguments).build()

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDirectory)
            .withPluginClasspath()
            .withArguments(*arguments, "--stacktrace")

    private fun write(path: String, vararg parts: String) {
        val file = projectDirectory.resolve(path)
        file.parentFile.mkdirs()
        file.writeText(parts.joinToString("\n") { it.trimIndent().trim('\n') } + "\n")
    }
}
