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
    fun aPluginShadesTaffyMetricsWithTheSdksRelocatedAndOffItsCompileClasspath() {
        val sourceRoot = File(System.getProperty("taffy.sourceRoot"))
        val library = projectDirectory.resolve("taffy")
        val modules = projectDirectory.resolve("modules")
        val modulesUri = modules.toURI().toString()
        for (module in listOf("taffy-platform", "taffy-metrics")) {
            sourceRoot.resolve("$module/build.gradle.kts").copyTo(library.resolve("$module/build.gradle.kts"))
            sourceRoot.resolve("$module/src/main").copyRecursively(library.resolve("$module/src/main"))
        }
        sourceRoot.resolve("gradle/libs.versions.toml").copyTo(library.resolve("gradle/libs.versions.toml"))
        write("taffy/LICENSE", "MIT License\n")
        write("taffy/gradle.properties", "group=so.alaz.taffy\nversion=${TaffyBuild.VERSION}\n")
        write(
            "taffy/settings.gradle.kts",
            """
            plugins {
                id("so.alaz.repositories")
            }
            rootProject.name = "taffy"
            include(":taffy-platform", ":taffy-test", ":taffy-metrics")
            """,
        )
        write("taffy/taffy-test/build.gradle.kts", "plugins {\n    id(\"java-library\")\n}")
        write(
            "taffy/build.gradle.kts",
            """
            subprojects {
                plugins.withId("maven-publish") {
                    configure<PublishingExtension> {
                        repositories {
                            maven("$modulesUri") { name = "modules" }
                        }
                    }
                }
            }
            """,
        )

        val published = runner(
            library,
            ":taffy-platform:publishLibraryPublicationToModulesRepository",
            ":taffy-metrics:publishLibraryPublicationToModulesRepository",
        ).build()

        assertThat(published.task(":taffy-metrics:publishLibraryPublicationToModulesRepository")?.outcome)
            .isEqualTo(TaskOutcome.SUCCESS)
        val pom = modules.walk().single { it.name.startsWith("taffy-metrics-") && it.name.endsWith(".pom") }.readText()
        val scope = "</artifactId>\\s*<version>[^<]+</version>\\s*<scope>"
        assertThat(pom)
            .containsPattern("<groupId>so\\.alaz\\.taffy</groupId>\\s*<artifactId>taffy-platform${scope}compile<")
            .containsPattern("<groupId>org\\.bstats</groupId>\\s*<artifactId>bstats-bukkit${scope}runtime<")
            .containsPattern("<groupId>dev\\.faststats\\.metrics</groupId>\\s*<artifactId>bukkit${scope}runtime<")

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
                        forRepositories(
                            maven("$testRepository") { name = "test" },
                            maven("$modulesUri") { name = "modules" },
                        )
                        filter {
                            includeGroup("so.alaz.taffy")
                        }
                    }
                }
            }
            """,
        )
        write(
            "build.gradle.kts",
            """
            plugins {
                id("so.alaz.paper-plugin")
            }
            dependencies {
                implementation(alaz.taffy.metrics)
            }
            tasks.register("showCompileClasspath") {
                val classpath: FileCollection = configurations.compileClasspath.get()
                doLast {
                    classpath.files.forEach { println("compile=" + it.absolutePath) }
                }
            }
            """,
        )
        write(
            "src/main/java/so/alaz/demo/Charts.java",
            """
            package so.alaz.demo;

            import so.alaz.taffy.metrics.MetricChart;

            final class Charts {
                static final MetricChart ONLINE = MetricChart.number("online", () -> 1);
            }
            """,
        )

        val result = run("shadowJar", "showCompileClasspath")

        val compileClasspath = result.output.lines()
            .filter { it.startsWith("compile=") }
            .map { File(it.substringAfter("=")) }
        assertThat(compileClasspath.map { it.name }).anyMatch { it.startsWith("taffy-metrics-") }
        assertThat(compileClasspath.map { it.name }).anyMatch { it.startsWith("taffy-platform-") }
        for (file in compileClasspath.filter { it.isFile }) {
            ZipFile(file).use { jar ->
                assertThat(jar.entries().asSequence().map { it.name }.toList()).`as`(file.name)
                    .noneMatch { it.startsWith("org/bstats/") || it.startsWith("dev/faststats/") }
            }
        }
        ZipFile(projectDirectory.resolve("build/libs/demo-1.2.3.jar")).use { jar ->
            val names = jar.entries().asSequence().map { it.name }.toList()
            assertThat(names).contains(
                "so/alaz/demo/Charts.class",
                "so/alaz/demo/libs/taffy/metrics/MetricsBuilder.class",
                "so/alaz/demo/libs/taffy/platform/ErrorReports.class",
                "so/alaz/demo/libs/bstats/MetricsBase.class",
                "so/alaz/demo/libs/bstats/bukkit/Metrics.class",
                "so/alaz/demo/libs/faststats/ErrorTracker.class",
                "so/alaz/demo/libs/faststats/bukkit/BukkitContext.class",
            )
            assertThat(names).noneMatch {
                it.startsWith("org/bstats/") || it.startsWith("dev/faststats/") || it.startsWith("so/alaz/taffy/")
            }
            assertThat(names.filter { it == "META-INF/faststats.properties" }).hasSize(1)
            assertThat(names).noneMatch { it.contains("gson", ignoreCase = true) }
        }
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
        assertThat(result.output).doesNotContain("Rule violated for class")
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
    fun aModuleWithoutTestsPassesCheckOnlyWithBothCoverageMinimumsAtZero() {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        val libraryPlugin = "plugins {\n    id(\"so.alaz.library\")\n}"
        write("build.gradle.kts", libraryPlugin, "coverage {\n    minimum.set(\"0.0\".toBigDecimal())\n}")
        write("src/main/java/so/alaz/demo/Demo.java", uncovered)

        val underTheClassFloor = runner("check").buildAndFail()

        assertThat(underTheClassFloor.task(":verifyCoverageData")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(underTheClassFloor.output).contains("No coverage data for :")

        write(
            "build.gradle.kts",
            libraryPlugin,
            """
            coverage {
                minimum.set("0.0".toBigDecimal())
                classMinimum.set("0.0".toBigDecimal())
            }
            """,
        )

        val ungated = run("check")

        assertThat(ungated.task(":verifyCoverageData")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
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
        ZipFile(published.resolve("demo-core-1.2.3-javadoc.jar")).use { jar ->
            val names = jar.entries().asSequence().map { it.name }.toList()
            assertThat(names).contains("so/alaz/demo/Covered.html")
            assertThat(names).noneMatch { it.startsWith("resource-files/fonts/") }
        }
        assertThat(published.resolve("demo-core-1.2.3.pom").readText()).contains(
            "<groupId>so.alaz.demo</groupId>",
            "<artifactId>demo-core</artifactId>",
            "<description>Demo core</description>",
            "<url>https://github.com/alazso/demo</url>",
        )
    }

    @Test
    fun libraryFailsWhenOneClassIsBelowTheClassFloor() {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write(
            "build.gradle.kts",
            "plugins {\n    id(\"so.alaz.library\")\n}",
            junit,
            "coverage {\n    minimum.set(\"0.0\".toBigDecimal())\n}",
        )
        write("src/main/java/so/alaz/demo/Demo.java", uncovered)
        write("src/main/java/so/alaz/demo/Covered.java", covered)
        write("src/test/java/so/alaz/demo/CoveredTest.java", coveredTest)

        val result = runner("check").buildAndFail()

        assertThat(result.task(":jacocoTestCoverageVerification")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output).contains(
            "Rule violated for class so.alaz.demo.Demo: " +
                "instructions covered ratio is 0.00, but expected minimum is 0.50",
        )
        assertThat(result.output).doesNotContain("Rule violated for class so.alaz.demo.Covered")
    }

    @Test
    fun aBuildCanSetTheClassFloorAndExcludedClassesAreOutsideIt() {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        val coveragePlugin = "plugins {\n    id(\"so.alaz.coverage\")\n}"
        val classFloor = "coverage {\n    classMinimum.set(\"0.50\".toBigDecimal())\n}"
        val exclusion = "coverage {\n    exclusions.add(\"**/Demo.class\")\n}"
        write("build.gradle.kts", coveragePlugin, junit, classFloor, exclusion)
        write("src/main/java/so/alaz/demo/Demo.java", uncovered)
        write("src/main/java/so/alaz/demo/Covered.java", covered)
        write("src/test/java/so/alaz/demo/CoveredTest.java", coveredTest)

        val excluded = run("check")

        assertThat(excluded.task(":jacocoTestCoverageVerification")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

        write("build.gradle.kts", coveragePlugin, junit, classFloor)

        val gated = runner("check").buildAndFail()

        assertThat(gated.task(":jacocoTestCoverageVerification")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(gated.output).contains(
            "Rule violated for class so.alaz.demo.Demo: " +
                "instructions covered ratio is 0.00, but expected minimum is 0.50",
        )
        assertThat(gated.output).contains("Rule violated for bundle demo: ")
    }

    @Test
    fun javadocFailsOnATagThatNamesNoParameter() {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write("build.gradle.kts", "plugins {\n    id(\"so.alaz.java\")\n}")
        write(
            "src/main/java/so/alaz/demo/Documented.java",
            """
            package so.alaz.demo;

            public final class Documented {

                private Documented() {
                }

                /**
                 * Returns one.
                 *
                 * @param absent a parameter the method does not have
                 * @return one
                 */
                public static int one() {
                    return 1;
                }
            }
            """,
        )

        val result = runner("javadoc").buildAndFail()

        assertThat(result.task(":javadoc")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output).contains("Documented.java:11:")
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
                val platform = alaz.taffy.platform
                val test = alaz.taffy.test
                val config = alaz.taffy.config
                val storage = alaz.taffy.storage
                val hooks = alaz.taffy.hooks
                val gui = alaz.taffy.gui
                val metrics = alaz.taffy.metrics
                doLast {
                    println("paper=" + paper.get())
                    println("platform=" + platform.get())
                    println("test=" + test.get())
                    println("config=" + config.get())
                    println("storage=" + storage.get())
                    println("hooks=" + hooks.get())
                    println("gui=" + gui.get())
                    println("metrics=" + metrics.get())
                }
            }
            """,
        )

        val result = run(":demo-api:showPaper", ":demo-api:generatePomFileForLibraryPublication")

        assertThat(result.output).containsPattern("paper=\\d+\\.\\d+")
        assertThat(result.output).containsPattern("platform=so\\.alaz\\.taffy:taffy-platform:\\d+\\.\\d+")
        assertThat(result.output).containsPattern("test=so\\.alaz\\.taffy:taffy-test:\\d+\\.\\d+")
        assertThat(result.output).containsPattern("config=so\\.alaz\\.taffy:taffy-config:\\d+\\.\\d+")
        assertThat(result.output).containsPattern("storage=so\\.alaz\\.taffy:taffy-storage:\\d+\\.\\d+")
        assertThat(result.output).containsPattern("hooks=so\\.alaz\\.taffy:taffy-hooks:\\d+\\.\\d+")
        assertThat(result.output).containsPattern("gui=so\\.alaz\\.taffy:taffy-gui:\\d+\\.\\d+")
        assertThat(result.output).containsPattern("metrics=so\\.alaz\\.taffy:taffy-metrics:\\d+\\.\\d+")
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
    fun runtimeLibraryTakesItsCoordinatesFromTheCatalog() {
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
            """,
        )
        write(
            "build.gradle.kts",
            """
            plugins {
                id("so.alaz.paper-plugin")
            }
            paperPlugin {
                runtimeLibrary(alaz.hikari)
                runtimeLibrary(alaz.sqlite.jdbc)
                runtimeLibrary(alaz.mysql.connector)
                runtimeLibrary(alaz.mariadb.jdbc)
                runtimeLibrary(alaz.postgresql.jdbc)
            }
            """,
        )

        val result = run("generateRuntimeLibraries")

        assertThat(result.task(":generateRuntimeLibraries")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val generated = projectDirectory
            .resolve("build/generated/sources/runtimeLibraries/java/main/so/alaz/demo/RuntimeLibraries.java")
        assertThat(generated.readText())
            .containsPattern("\"com\\.zaxxer:HikariCP:\\d+(\\.\\d+)+\"")
            .containsPattern("\"org\\.xerial:sqlite-jdbc:\\d+(\\.\\d+)+\"")
            .containsPattern("\"com\\.mysql:mysql-connector-j:\\d+(\\.\\d+)+\"")
            .containsPattern("\"org\\.mariadb\\.jdbc:mariadb-java-client:\\d+(\\.\\d+)+\"")
            .containsPattern("\"org\\.postgresql:postgresql:\\d+(\\.\\d+)+\"")
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

    @Test
    fun testsRunWithTheMockitoJarAsAJavaAgent() {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        // With the attach mechanism disabled, Mockito can mock a final class only through an agent given at JVM start.
        write(
            "build.gradle.kts",
            """
            plugins {
                id("so.alaz.java")
            }
            dependencies {
                testImplementation("org.mockito:mockito-core:5.24.0")
            }
            tasks.withType<Test>().configureEach {
                jvmArgs("-XX:+DisableAttachMechanism")
            }
            """,
            junit,
        )
        write(
            "src/test/java/so/alaz/demo/AgentTest.java",
            """
            package so.alaz.demo;

            import java.lang.management.ManagementFactory;
            import java.util.List;
            import org.junit.jupiter.api.Test;
            import org.mockito.Mockito;

            class AgentTest {

                static final class Sealed {
                    String name() {
                        return "real";
                    }
                }

                @Test
                void theJvmWasStartedWithTheMockitoJarAsAnAgent() {
                    List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
                    List<String> agents = arguments.stream().filter(a -> a.startsWith("-javaagent:")).toList();
                    if (agents.size() != 1 || !agents.get(0).contains("mockito-core-5.24.0.jar")) {
                        throw new AssertionError("expected the Mockito jar as the one agent, got " + arguments);
                    }
                }

                @Test
                void aFinalClassIsMockedWithoutAttaching() {
                    Sealed mock = Mockito.mock(Sealed.class);
                    Mockito.when(mock.name()).thenReturn("mocked");
                    if (!"mocked".equals(mock.name())) {
                        throw new AssertionError(mock.name());
                    }
                }
            }
            """,
        )

        val first = run("test", "--configuration-cache")

        assertThat(first.task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(first.output).contains(
            "theJvmWasStartedWithTheMockitoJarAsAnAgent() PASSED",
            "aFinalClassIsMockedWithoutAttaching() PASSED",
        )

        projectDirectory.resolve("build").deleteRecursively()
        val second = run("test", "--configuration-cache")

        assertThat(second.output).contains("Reusing configuration cache.")
        assertThat(second.task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(second.output).contains(
            "theJvmWasStartedWithTheMockitoJarAsAnAgent() PASSED",
            "aFinalClassIsMockedWithoutAttaching() PASSED",
        )
    }

    @Test
    fun mockitoReachedThroughAProjectDependencyIsAnAgentBesideTheCoverageAgent() {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"\ninclude(\":helper\", \":app\")", centralOnly)
        write(
            "helper/build.gradle.kts",
            """
            plugins {
                id("so.alaz.java")
            }
            dependencies {
                implementation("org.mockito:mockito-core:5.24.0")
            }
            """,
        )
        write(
            "app/build.gradle.kts",
            "plugins {\n    id(\"so.alaz.coverage\")\n}",
            junit,
            "dependencies {\n    testImplementation(project(\":helper\"))\n}",
        )
        write(
            "app/src/test/java/so/alaz/demo/TransitiveAgentTest.java",
            """
            package so.alaz.demo;

            import java.lang.management.ManagementFactory;
            import java.util.List;
            import org.junit.jupiter.api.Test;

            class TransitiveAgentTest {

                @Test
                void theJvmWasStartedWithTheMockitoJarAndTheCoverageAgent() {
                    List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
                    List<String> agents = arguments.stream().filter(a -> a.startsWith("-javaagent:")).toList();
                    long mockito = agents.stream().filter(a -> a.contains("mockito-core-5.24.0.jar")).count();
                    long jacoco = agents.stream().filter(a -> a.contains("jacocoagent.jar")).count();
                    if (agents.size() != 2 || mockito != 1 || jacoco != 1) {
                        throw new AssertionError("expected the Mockito jar and the JaCoCo agent, got " + arguments);
                    }
                }
            }
            """,
        )

        val result = run(":app:test")

        assertThat(result.task(":app:test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).contains("theJvmWasStartedWithTheMockitoJarAndTheCoverageAgent() PASSED")
    }

    @Test
    fun aModuleWithoutMockitoGetsNoAgent() {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write("build.gradle.kts", "plugins {\n    id(\"so.alaz.java\")\n}", junit)
        write(
            "src/test/java/so/alaz/demo/NoAgentTest.java",
            """
            package so.alaz.demo;

            import java.lang.management.ManagementFactory;
            import java.util.List;
            import org.junit.jupiter.api.Test;

            class NoAgentTest {

                @Test
                void theJvmWasStartedWithoutAnAgent() {
                    List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
                    if (arguments.stream().anyMatch(a -> a.startsWith("-javaagent:"))) {
                        throw new AssertionError("expected no agent, got " + arguments);
                    }
                }
            }
            """,
        )

        val result = run("test")

        assertThat(result.task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).contains("theJvmWasStartedWithoutAnAgent() PASSED")
    }

    @Test
    fun eachTestTaskTakesTheMockitoAgentFromItsOwnClasspath() {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write(
            "build.gradle.kts",
            """
            plugins {
                id("so.alaz.java")
            }
            val mocked = sourceSets.create("mocked")
            dependencies {
                "mockedImplementation"("org.mockito:mockito-core:5.24.0")
                "mockedImplementation"("org.junit.jupiter:junit-jupiter:6.1.3")
                "mockedRuntimeOnly"("org.junit.platform:junit-platform-launcher:6.1.3")
            }
            tasks.register<Test>("mockedTest") {
                testClassesDirs = mocked.output.classesDirs
                classpath = mocked.runtimeClasspath
            }
            """,
            junit,
        )
        write("src/test/java/so/alaz/demo/PlainTest.java", agentCheck("PlainTest", 0))
        write("src/mocked/java/so/alaz/demo/MockedTest.java", agentCheck("MockedTest", 1))

        val first = run("test", "mockedTest", "--configuration-cache")

        assertThat(first.task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(first.task(":mockedTest")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(first.output).contains("PlainTest > theAgentsMatchTheClasspath() PASSED")
        assertThat(first.output).contains("MockedTest > theAgentsMatchTheClasspath() PASSED")

        projectDirectory.resolve("build").deleteRecursively()
        val second = run("test", "mockedTest", "--configuration-cache")

        assertThat(second.output).contains("Reusing configuration cache.")
        assertThat(second.output).contains("PlainTest > theAgentsMatchTheClasspath() PASSED")
        assertThat(second.output).contains("MockedTest > theAgentsMatchTheClasspath() PASSED")
    }

    @Test
    fun testsRunUnderATurkishLocaleAndANonUtcZone() {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write("build.gradle.kts", "plugins {\n    id(\"so.alaz.java\")\n}", junit)
        write("src/test/java/so/alaz/demo/JvmDefaultsTest.java", jvmDefaultsTest("tr-TR"))

        val result = run("test")

        assertThat(result.task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).contains("theJvmDefaultsAreTheExpectedOnes() PASSED")
    }

    @Test
    fun aBuildCanReplaceATestDefault() {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write(
            "build.gradle.kts",
            "plugins {\n    id(\"so.alaz.java\")\n}",
            junit,
            "tasks.test {\n    systemProperty(\"user.language\", \"en\")\n}",
        )
        write("src/test/java/so/alaz/demo/JvmDefaultsTest.java", jvmDefaultsTest("en-TR"))

        val result = run("test")

        assertThat(result.task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).contains("theJvmDefaultsAreTheExpectedOnes() PASSED")
    }

    private fun jvmDefaultsTest(languageTag: String): String = """
        package so.alaz.demo;

        import java.util.Locale;
        import java.util.TimeZone;
        import org.junit.jupiter.api.Test;

        class JvmDefaultsTest {

            @Test
            void theJvmDefaultsAreTheExpectedOnes() {
                String locale = Locale.getDefault().toLanguageTag();
                String zone = TimeZone.getDefault().getID();
                if (!"$languageTag".equals(locale) || !"Pacific/Auckland".equals(zone)) {
                    throw new AssertionError("expected $languageTag and Pacific/Auckland, got " + locale + " and " + zone);
                }
            }
        }
    """

    /** A test that passes when the JVM has [agents] Mockito agents and sees Mockito exactly when it has one. */
    private fun agentCheck(className: String, agents: Int): String = """
        package so.alaz.demo;

        import java.lang.management.ManagementFactory;
        import java.util.List;
        import org.junit.jupiter.api.Test;

        class $className {

            @Test
            void theAgentsMatchTheClasspath() {
                List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
                long mockito = arguments.stream()
                    .filter(a -> a.startsWith("-javaagent:") && a.contains("mockito-core-5.24.0.jar"))
                    .count();
                boolean visible;
                try {
                    Class.forName("org.mockito.Mockito", false, getClass().getClassLoader());
                    visible = true;
                } catch (ClassNotFoundException absent) {
                    visible = false;
                }
                if (mockito != $agents || visible != (mockito == 1)) {
                    throw new AssertionError("expected $agents Mockito agents, got " + arguments);
                }
            }
        }
    """

    private fun project() {
        write("LICENSE", "MIT License\n")
        write("gradle.properties", "group=so.alaz.demo\nversion=1.2.3\n")
    }

    private fun run(vararg arguments: String): BuildResult = runner(*arguments).build()

    private fun runner(vararg arguments: String): GradleRunner = runner(projectDirectory, *arguments)

    private fun runner(directory: File, vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(directory)
            .withPluginClasspath()
            .withArguments(*arguments, "--stacktrace")

    private fun write(path: String, vararg parts: String) {
        val file = projectDirectory.resolve(path)
        file.parentFile.mkdirs()
        file.writeText(parts.joinToString("\n") { it.trimIndent().trim('\n') } + "\n")
    }
}
