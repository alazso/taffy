package so.alaz.taffy.gradle

import java.io.File
import java.util.zip.ZipFile
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ConventionPluginsTest {

    @TempDir
    lateinit var projectDirectory: File

    private val testRepository: String = System.getProperty("taffy.testRepository")
    private val taffyVersion: String = System.getProperty("taffy.version")

    @Test
    fun paperPluginBuildsOneRelocatedJar() {
        write("LICENSE", "MIT License\n")
        write("gradle.properties", "group=so.alaz.demo\nversion=1.2.3\n")
        write(
            "settings.gradle.kts",
            """
            rootProject.name = "demo"
            dependencyResolutionManagement {
                repositories {
                    mavenCentral()
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
                implementation("org.bstats:bstats-base:3.2.1")
            }
            paperPlugin {
                coverageExclusions.add("so/alaz/demo/**")
                runtimeLibraries.add("com.zaxxer:HikariCP:7.1.0")
            }
            """,
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

        run("build")

        val libs = projectDirectory.resolve("build/libs").listFiles()!!.map { it.name }
        assertThat(libs).containsExactly("demo-1.2.3.jar")
        ZipFile(projectDirectory.resolve("build/libs/demo-1.2.3.jar")).use { jar ->
            val names = jar.entries().asSequence().map { it.name }.toList()
            assertThat(names).contains(
                "META-INF/LICENSE",
                "so/alaz/demo/Demo.class",
                "so/alaz/demo/RuntimeLibraries.class",
                "so/alaz/demo/libs/bstats/MetricsBase.class",
            )
            assertThat(names).noneMatch { it.startsWith("org/bstats/") }
            val descriptor = jar.getInputStream(jar.getEntry("paper-plugin.yml")).reader().readText()
            assertThat(descriptor).contains("version: \"1.2.3\"")
        }
    }

    @Test
    fun paperPluginFailsBelowTheCoverageMinimum() {
        write("LICENSE", "MIT License\n")
        write("gradle.properties", "group=so.alaz.demo\nversion=1.2.3\n")
        write(
            "settings.gradle.kts",
            """
            rootProject.name = "demo"
            dependencyResolutionManagement {
                repositories {
                    mavenCentral()
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
                testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
            }
            """,
        )
        write(
            "src/main/java/so/alaz/demo/Demo.java",
            "package so.alaz.demo;\n\nfinal class Demo {\n    static int one() {\n        return 1;\n    }\n}\n",
        )
        write(
            "src/test/java/so/alaz/demo/OtherTest.java",
            """
            package so.alaz.demo;

            import org.junit.jupiter.api.Test;

            class OtherTest {
                @Test
                void runs() {
                }
            }
            """,
        )

        val result = runner("check").buildAndFail()

        assertThat(result.output).contains("instructions covered ratio is 0.00, but expected minimum is 0.80")
    }

    @Test
    fun settingsPluginProvidesTheCatalogAndPaperApiPublishesUnderTheProjectGroup() {
        write("LICENSE", "MIT License\n")
        write("gradle.properties", "group=so.alaz.demo\nversion=1.2.3\n")
        write(
            "settings.gradle.kts",
            """
            plugins {
                id("so.alaz.settings")
            }
            rootProject.name = "demo"
            dependencyResolutionManagement {
                repositories {
                    maven("$testRepository") { name = "test" }
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
            val paper = alaz.versions.paper
            tasks.register("showPaper") {
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
        assertThat(taffyVersion).isNotBlank()
    }

    private fun run(vararg arguments: String): BuildResult = runner(*arguments).build()

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDirectory)
            .withPluginClasspath()
            .withArguments(*arguments, "--stacktrace")

    private fun write(path: String, content: String) {
        val file = projectDirectory.resolve(path)
        file.parentFile.mkdirs()
        file.writeText(content.trimIndent())
    }
}
