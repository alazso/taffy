package so.alaz.taffy.gradle

import java.io.File
import java.net.URI
import java.net.URLClassLoader
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ConventionPluginsTest {

    @TempDir
    lateinit var projectDirectory: File

    private val testRepository: String = System.getProperty("taffy.testRepository")

    private val repositoryNames = listOf(
        "MavenRepo", "papermc", "alazso", "alazsoSnapshots", "placeholderapi", "faststats", "vaultunlocked",
        "playerpoints", "oraxen", "nexo", "enginehub", "opencollab", "fancyinnovations", "thenextlvl", "packetevents",
        "jitpack",
    )

    /**
     * Every group a filter of so.alaz.taffy.repositories names, and one that none names. A filter is probed with
     * these groups only, so a group a filter names and this list lacks is not seen by a probe.
     */
    private val filterGroups = listOf(
        "io.papermc", "com.mojang", "net.md-5", "so.alaz", "me.clip", "dev.faststats.metrics", "net.milkbowl.vault",
        "org.black_ixx", "io.th0rgal", "md.thomas.hopper", "com.nexomc", "com.tcoded", "me.gabytm.util",
        "team.unnamed", "com.sk89q", "org.enginehub", "org.geysermc.floodgate", "org.geysermc.geyser",
        "org.geysermc.api", "de.oliver", "net.thenextlvl", "com.github.retrooper",
        "com.github.MilkBowl", "com.github.Arcaniax-Development", "com.github.decentsoftware-eu",
        "com.github.HologramLib", "com.github.maximjsx.EntityLib", "org.example",
    )

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
                id("so.alaz.taffy.paper-plugin")
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
        write(
            "src/main/resources/paper-plugin.yml",
            "name: Demo\nversion: \"\${version}\"\nloader: so.alaz.demo.DemoLoader\n",
        )
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
        write("src/main/java/com/comficonf/api/Probe.java", "package com.comficonf.api;\n\npublic interface Probe {\n}\n")
        for (neighbour in listOf("so.alaz.taffyshop", "org.bstatsx", "dev.faststatsx")) {
            val constant = "    String NAME = \"$neighbour.Named\";"
            val source = "package $neighbour;\n\npublic interface Neighbour {\n$constant\n}\n"
            write("src/main/java/${neighbour.replace('.', '/')}/Neighbour.java", source)
        }

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
            assertThat(names).`as`("no relocation pattern covers com.comficonf")
                .contains("com/comficonf/api/Probe.class")
                .noneMatch { it.startsWith("so/alaz/demo/libs/") && it.contains("comficonf") }
                .noneMatch { it.startsWith("so/alaz/demo/libs/com/") }
            assertThat(names).`as`("a package that only starts with a relocated name stays where it is")
                .contains(
                    "so/alaz/taffyshop/Neighbour.class",
                    "org/bstatsx/Neighbour.class",
                    "dev/faststatsx/Neighbour.class",
                )
                .noneMatch { it.endsWith("/Neighbour.class") && it.startsWith("so/alaz/demo/libs/") }
            for (neighbour in listOf("so.alaz.taffyshop", "org.bstatsx", "dev.faststatsx")) {
                val bytes = jar.getInputStream(jar.getEntry(neighbour.replace('.', '/') + "/Neighbour.class"))
                    .readBytes()
                assertThat(String(bytes, Charsets.ISO_8859_1)).`as`(neighbour)
                    .contains("$neighbour.Named", neighbour.replace('.', '/') + "/Neighbour")
                    .doesNotContain("so/alaz/demo/libs", "so.alaz.demo.libs")
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
    fun filterGroupsHoldsEveryGroupTheRepositoriesScriptNames() {
        val script = File(System.getProperty("taffy.sourceRoot"))
            .resolve("tooling/taffy-gradle/src/main/kotlin/so.alaz.taffy.repositories.settings.gradle.kts")
            .readText()
        val named = Regex("include[A-Za-z]*\\(\\s*\"([^\":]+)[\":]").findAll(script).map { it.groupValues[1] }.toSet()

        assertThat(named).containsExactlyInAnyOrderElementsOf(filterGroups - "org.example")
    }

    @Test
    fun theToolingBuildTakesOnlyTheToolchainsGroupFromThePluginPortal() {
        val script = File(System.getProperty("taffy.sourceRoot")).resolve("tooling/settings.gradle.kts").readText()
        val filtered = Regex(
            "exclusiveContent\\s*\\{\\s*forRepository\\s*\\{\\s*gradlePluginPortal\\(\\)\\s*}\\s*filter\\s*\\{([^}]*)}\\s*}",
        ).findAll(script).map { it.groupValues[1].trim() }.toList()

        assertThat(Regex("gradlePluginPortal\\(\\)").findAll(script).count()).isEqualTo(1)
        assertThat(filtered).containsExactly("includeGroup(\"org.gradle.toolchains\")")
    }

    @Test
    fun aPluginShadesTaffyMetricsWithTheSdksRelocatedAndOffItsCompileClasspath() {
        val sourceRoot = File(System.getProperty("taffy.sourceRoot"))
        val library = projectDirectory.resolve("taffy")
        val modules = projectDirectory.resolve("modules")
        val modulesUri = modules.toURI().toString()
        File(URI(testRepository)).copyRecursively(modules)
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
                id("so.alaz.taffy.repositories")
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
                id("so.alaz.taffy.settings")
            }
            rootProject.name = "demo"
            dependencyResolutionManagement {
                repositories.withType<MavenArtifactRepository>().matching { it.name.startsWith("alazso") }
                    .configureEach { url = uri("$modulesUri") }
            }
            """,
        )
        write(
            "build.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.paper-plugin")
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
    fun aPluginShadesTaffyComfiConfWithTheApiLeftToTheBridge() {
        val sourceRoot = File(System.getProperty("taffy.sourceRoot"))
        val library = projectDirectory.resolve("taffy")
        val modules = projectDirectory.resolve("modules")
        val modulesUri = modules.toURI().toString()
        File(URI(testRepository)).copyRecursively(modules)
        for (module in listOf("taffy-platform", "taffy-config", "taffy-comficonf")) {
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
                id("so.alaz.taffy.repositories")
            }
            rootProject.name = "taffy"
            include(":taffy-platform", ":taffy-test", ":taffy-config", ":taffy-comficonf")
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
            ":taffy-config:publishLibraryPublicationToModulesRepository",
            ":taffy-comficonf:publishLibraryPublicationToModulesRepository",
        ).build()

        assertThat(published.task(":taffy-comficonf:publishLibraryPublicationToModulesRepository")?.outcome)
            .isEqualTo(TaskOutcome.SUCCESS)
        val pom = modules.walk().single { it.name.startsWith("taffy-comficonf-") && it.name.endsWith(".pom") }
            .readText()
        val scope = "</artifactId>\\s*<version>[^<]+</version>\\s*<scope>"
        assertThat(pom)
            .containsPattern("<groupId>so\\.alaz\\.taffy</groupId>\\s*<artifactId>taffy-platform${scope}compile<")
            .containsPattern("<groupId>so\\.alaz\\.taffy</groupId>\\s*<artifactId>taffy-config${scope}compile<")
            .doesNotContain("comficonf-api", "com.comficonf")
        val metadata = modules.walk()
            .single { it.name.startsWith("taffy-comficonf-") && it.name.endsWith(".module") }
            .readText()
        assertThat(metadata).contains("\"module\": \"taffy-config\"")
            .doesNotContain("comficonf-api", "com.comficonf")

        project()
        write(
            "settings.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.settings")
            }
            rootProject.name = "demo"
            dependencyResolutionManagement {
                repositories.withType<MavenArtifactRepository>().matching { it.name.startsWith("alazso") }
                    .configureEach { url = uri("$modulesUri") }
            }
            """,
        )
        write(
            "build.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.paper-plugin")
            }
            dependencies {
                compileOnly(alaz.paper.api)
                compileOnly(alaz.comficonf.api)
                implementation(alaz.taffy.comficonf)
            }
            """,
        )
        write(
            "src/main/java/so/alaz/demo/Settings.java",
            """
            package so.alaz.demo;

            import com.comficonf.api.ConfigProvider;
            import org.bukkit.plugin.Plugin;
            import so.alaz.taffy.comficonf.ComfiConfLink;
            import so.alaz.taffy.comficonf.ComfiConfRegistration;

            final class Settings {
                static ComfiConfLink register(Plugin owner, ConfigProvider provider) {
                    return ComfiConfRegistration.register(owner, "demo:settings", "Demo", "Demo settings.", provider);
                }
            }
            """,
        )

        run("shadowJar")

        ZipFile(projectDirectory.resolve("build/libs/demo-1.2.3.jar")).use { jar ->
            val names = jar.entries().asSequence().map { it.name }.toList()
            assertThat(names).contains(
                "so/alaz/demo/Settings.class",
                "so/alaz/demo/libs/taffy/comficonf/ComfiConfConnection.class",
                "so/alaz/demo/libs/taffy/comficonf/ComfiConfRegistration.class",
                "so/alaz/demo/libs/taffy/comficonf/YamlFileProvider.class",
                "so/alaz/demo/libs/taffy/config/StrictYaml.class",
                "so/alaz/demo/libs/taffy/platform/ErrorReports.class",
            )
            assertThat(names).noneMatch { it.startsWith("com/comficonf/") || it.startsWith("so/alaz/taffy/") }
            assertThat(names).noneMatch { it.startsWith("so/alaz/demo/libs/") && it.contains("comficonf/api") }

            fun bytesOf(name: String): String =
                String(jar.getInputStream(jar.getEntry(name)).readBytes(), Charsets.ISO_8859_1)

            for (name in listOf("ComfiConfRegistration.class", "YamlFileProvider.class")) {
                assertThat(bytesOf("so/alaz/demo/libs/taffy/comficonf/$name")).`as`(name)
                    .contains("com/comficonf/api/")
                    .doesNotContain("so/alaz/taffy/", "libs/com/comficonf")
            }
            assertThat(bytesOf("so/alaz/demo/libs/taffy/comficonf/ComfiConfRegistration.class"))
                .contains("com/comficonf/api/ComfiConfApi", "so/alaz/demo/libs/taffy/comficonf/ComfiConfLink")
            assertThat(bytesOf("so/alaz/demo/libs/taffy/comficonf/ComfiConfConnection.class"))
                .contains("com.comficonf.api.ComfiConfApi")
                .doesNotContain("com/comficonf/api")
            assertThat(bytesOf("so/alaz/demo/Settings.class")).contains("com/comficonf/api/ConfigProvider")
        }
    }

    @Test
    fun theRelocationCheckOfAShadedTaffyPassesAndStillKnowsTheOriginalPackage() {
        val sourceRoot = File(System.getProperty("taffy.sourceRoot"))
        val library = projectDirectory.resolve("taffy")
        val modules = projectDirectory.resolve("modules")
        val modulesUri = modules.toURI().toString()
        File(URI(testRepository)).copyRecursively(modules)
        val module = "taffy-platform"
        sourceRoot.resolve("$module/build.gradle.kts").copyTo(library.resolve("$module/build.gradle.kts"))
        sourceRoot.resolve("$module/src/main").copyRecursively(library.resolve("$module/src/main"))
        sourceRoot.resolve("gradle/libs.versions.toml").copyTo(library.resolve("gradle/libs.versions.toml"))
        write("taffy/LICENSE", "MIT License\n")
        write("taffy/gradle.properties", "group=so.alaz.taffy\nversion=${TaffyBuild.VERSION}\n")
        write(
            "taffy/settings.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.repositories")
            }
            rootProject.name = "taffy"
            include(":taffy-platform", ":taffy-test")
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
        runner(library, ":taffy-platform:publishLibraryPublicationToModulesRepository").build()
        project()
        write(
            "settings.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.settings")
            }
            rootProject.name = "demo"
            dependencyResolutionManagement {
                repositories.withType<MavenArtifactRepository>().matching { it.name.startsWith("alazso") }
                    .configureEach { url = uri("$modulesUri") }
            }
            """,
        )
        write(
            "build.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.paper-plugin")
            }
            dependencies {
                implementation(alaz.taffy.platform)
            }
            """,
        )
        write(
            "src/main/java/so/alaz/demo/Reports.java",
            """
            package so.alaz.demo;

            import so.alaz.taffy.platform.ErrorReports;

            final class Reports {
                static ErrorReports create() {
                    return new ErrorReports();
                }
            }
            """,
        )

        run("shadowJar")

        val shaded = projectDirectory.resolve("build/libs/demo-1.2.3.jar")
        ZipFile(shaded).use { jar ->
            val names = jar.entries().asSequence().map { it.name }.toList()
            assertThat(names).contains(
                "so/alaz/demo/Reports.class",
                "so/alaz/demo/libs/taffy/platform/ErrorReports.class",
                "so/alaz/demo/libs/taffy/platform/Relocation.class",
            )
            assertThat(names).noneMatch { it.startsWith("so/alaz/taffy/") }
        }
        URLClassLoader(arrayOf(shaded.toURI().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val relocation = loader.loadClass("so.alaz.demo.libs.taffy.platform.Relocation")
            val original = relocation.getDeclaredMethod("original", String::class.java).apply { isAccessible = true }
            val message = relocation.getDeclaredMethod("message", String::class.java).apply { isAccessible = true }

            assertThat(relocation.packageName).isEqualTo("so.alaz.demo.libs.taffy.platform")
            assertThat(original.invoke(null, relocation.packageName)).isEqualTo(false)
            assertThat(original.invoke(null, "so.alaz.taffy")).isEqualTo(true)
            assertThat(original.invoke(null, "so.alaz.taffy.platform")).isEqualTo(true)
            assertThat(message.invoke(null, "Demo")).isEqualTo(
                "Demo ships Taffy in its original package so.alaz.taffy, so its Taffy classes mix with those of " +
                    "every other plugin that does the same. Its build needs relocate(\"so.alaz.taffy\", " +
                    "\"<group>.libs.taffy\") in the shadowJar task, or the so.alaz.taffy.paper-plugin Gradle " +
                    "plugin, which relocates Taffy.",
            )
            relocation.getMethod("check").invoke(null)
        }
    }

    @Test
    fun compileAgainstPaperFloorFailsOnACallTheFloorBuildLacksAndRunsOnlyWhenAsked() {
        project()
        write(
            "settings.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.settings")
            }
            rootProject.name = "demo"
            dependencyResolutionManagement {
                repositories.withType<MavenArtifactRepository>().matching { it.name.startsWith("alazso") }
                    .configureEach { url = uri("$testRepository") }
            }
            """,
        )
        write(
            "build.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.paper-plugin")
            }
            dependencies {
                compileOnly(alaz.paper.api)
            }
            tasks.register("showPaperFloorClasspath") {
                val jars = configurations.named("paperFloorCompileClasspath").map { it.files.map(File::getName) }
                doLast {
                    jars.get().filter { it.startsWith("paper-api-") }.forEach { println("floor=" + it) }
                }
            }
            """,
        )
        // PersistentDataContainerView.getPrimitiveStorageType is in paper-api 26.3 and not in 26.2.
        write(
            "src/main/java/so/alaz/demo/Stored.java",
            """
            package so.alaz.demo;

            import io.papermc.paper.persistence.PersistentDataContainerView;
            import net.kyori.adventure.key.Key;

            final class Stored {
                static boolean has(PersistentDataContainerView data, Key key) {
                    return data.getPrimitiveStorageType(key) != null;
                }
            }
            """,
        )

        val assembled = run("assemble", "showPaperFloorClasspath")

        assertThat(assembled.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(assembled.task(":compileAgainstPaperFloor")).isNull()
        assertThat(assembled.output).contains("floor=paper-api-" + paperFloor() + ".jar")
        assertThat(assembled.output.lines().filter { it.startsWith("floor=") }).hasSize(1)

        val missing = runner("compileAgainstPaperFloor").buildAndFail()

        assertThat(missing.task(":compileAgainstPaperFloor")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(missing.output).contains("cannot find symbol", "getPrimitiveStorageType")

        write(
            "src/main/java/so/alaz/demo/Stored.java",
            """
            package so.alaz.demo;

            import io.papermc.paper.persistence.PersistentDataContainerView;
            import org.bukkit.NamespacedKey;

            final class Stored {
                static boolean has(PersistentDataContainerView data, NamespacedKey key) {
                    return data.has(key);
                }
            }
            """,
        )

        val present = run("compileAgainstPaperFloor", "--configuration-cache")

        assertThat(present.task(":compileAgainstPaperFloor")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(projectDirectory.resolve("build/classes/java/paperFloor/so/alaz/demo/Stored.class")).exists()
    }

    @Test
    fun coverageGateFailsBelowTheMinimum() {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write("build.gradle.kts", "plugins {\n    id(\"so.alaz.taffy.paper-plugin\")\n}", junit)
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
            "plugins {\n    id(\"so.alaz.taffy.coverage\")\n}",
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
            "plugins {\n    id(\"so.alaz.taffy.coverage\")\n}",
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
        write("build.gradle.kts", "plugins {\n    id(\"so.alaz.taffy.library\")\n}")
        write("src/main/java/so/alaz/demo/Demo.java", uncovered)

        val result = runner("check").buildAndFail()

        assertThat(result.task(":verifyCoverageData")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output).contains("No coverage data for :")
    }

    @Test
    fun aModuleWithoutTestsPassesCheckOnlyWithBothCoverageMinimumsAtZero() {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        val libraryPlugin = "plugins {\n    id(\"so.alaz.taffy.library\")\n}"
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
                id("so.alaz.taffy.library")
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
            "plugins {\n    id(\"so.alaz.taffy.library\")\n}",
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
        val coveragePlugin = "plugins {\n    id(\"so.alaz.taffy.coverage\")\n}"
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
        write("build.gradle.kts", "plugins {\n    id(\"so.alaz.taffy.java\")\n}")
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
    fun aJarBuildsWithoutALicenseWhenTheRootHasNone() {
        write("gradle.properties", "group=so.alaz.demo\nversion=1.2.3\n")
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write("build.gradle.kts", "plugins {\n    id(\"so.alaz.taffy.java\")\n}")
        write("src/main/java/so/alaz/demo/Covered.java", covered)

        val result = run("jar")

        assertThat(projectDirectory.resolve("LICENSE")).doesNotExist()
        assertThat(result.task(":jar")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).doesNotContain("LICENSE")
        ZipFile(projectDirectory.resolve("build/libs/demo-1.2.3.jar")).use { jar ->
            assertThat(jar.getEntry("so/alaz/demo/Covered.class")).isNotNull()
            assertThat(jar.getEntry("META-INF/LICENSE")).isNull()
        }
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
                id("so.alaz.taffy.paper-api")
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

    @ParameterizedTest
    @ValueSource(strings = ["so.alaz.taffy.paper-api", "so.alaz.taffy.library"])
    fun aPublishingPluginPublishesAReleaseTheAlazsoRepositoryAlreadyHas(plugin: String) {
        FakeMavenRepository().use { repository ->
            repository.putRelease("so/alaz/demo", "demo", "1.2.3")
            project()
            write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
            write(
                "build.gradle.kts",
                """
                plugins {
                    id("$plugin")
                }
                publishing.repositories.named<MavenArtifactRepository>("alazso") {
                    url = uri("${repository.url}")
                    isAllowInsecureProtocol = true
                }
                """,
            )
            write("src/main/java/so/alaz/demo/Covered.java", covered)

            val credentials = mapOf("ALAZSO_REPO_USER" to "demo", "ALAZSO_REPO_TOKEN" to "demo")
            val result = runner("publishLibraryPublicationToAlazsoRepository")
                .withEnvironment(System.getenv() + credentials)
                .build()

            assertThat(result.task(":publishLibraryPublicationToAlazsoRepository")?.outcome)
                .isEqualTo(TaskOutcome.SUCCESS)
            assertThat(result.output).doesNotContain("is already complete in the alazso repository")
            val version = "so/alaz/demo/demo/1.2.3"
            assertThat(repository.uploadedFiles()).containsExactlyInAnyOrder(
                "$version/demo-1.2.3.jar",
                "$version/demo-1.2.3.pom",
                "$version/demo-1.2.3-sources.jar",
                "$version/demo-1.2.3-javadoc.jar",
                "$version/demo-1.2.3.module",
                "so/alaz/demo/demo/maven-metadata.xml",
            )
        }
    }

    @Test
    fun settingsPluginProvidesTheCatalogAndPaperApiPublishesUnderTheProjectGroup() {
        project()
        write(
            "settings.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.settings")
            }
            rootProject.name = "demo"
            dependencyResolutionManagement {
                repositories.withType<MavenArtifactRepository>().matching { it.name.startsWith("alazso") }
                    .configureEach { url = uri("$testRepository") }
            }
            include(":demo-api")
            """,
        )
        write(
            "demo-api/build.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.paper-api")
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
                val comficonf = alaz.taffy.comficonf
                doLast {
                    println("paper=" + paper.get())
                    println("platform=" + platform.get())
                    println("test=" + test.get())
                    println("config=" + config.get())
                    println("storage=" + storage.get())
                    println("hooks=" + hooks.get())
                    println("gui=" + gui.get())
                    println("metrics=" + metrics.get())
                    println("comficonf=" + comficonf.get())
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
        assertThat(result.output).containsPattern("comficonf=so\\.alaz\\.taffy:taffy-comficonf:\\d+\\.\\d+")
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
    fun settingsPluginFailsOnAGradleOlderThanTheMinimumWithAMessageNamingIt() {
        project()
        write("settings.gradle.kts", "plugins {\n    id(\"so.alaz.taffy.settings\")\n}\nrootProject.name = \"demo\"")

        val result = runner("help").withGradleVersion("9.1.0").buildAndFail()

        assertThat(result.output).contains(
            "so.alaz.taffy.settings needs Gradle 9.2.0 or newer, and this build runs on Gradle 9.1.0. " +
                "Set a newer version in distributionUrl in gradle/wrapper/gradle-wrapper.properties.",
        )
    }

    @Test
    fun aPaperPluginBuildsOnTheMinimumGradle() {
        project()
        write(
            "settings.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.settings")
            }
            rootProject.name = "demo"
            dependencyResolutionManagement {
                repositories.withType<MavenArtifactRepository>().matching { it.name.startsWith("alazso") }
                    .configureEach { url = uri("$testRepository") }
            }
            """,
        )
        write(
            "build.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.paper-plugin")
            }
            dependencies {
                implementation("org.bstats:bstats-base:3.2.1")
                testImplementation(alaz.junit.jupiter)
                testRuntimeOnly(alaz.junit.platform.launcher)
            }
            paperPlugin {
                runtimeLibrary(alaz.hikari)
            }
            """,
        )
        write(
            "src/main/resources/paper-plugin.yml",
            "name: Demo\nversion: \"\${version}\"\nloader: so.alaz.demo.DemoLoader\n",
        )
        write("src/main/java/so/alaz/demo/Covered.java", covered)
        write("src/test/java/so/alaz/demo/CoveredTest.java", coveredTest)

        val result = runner("build").withGradleVersion(MinimumGradle.VERSION).build()

        assertThat(result.task(":jacocoTestCoverageVerification")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        ZipFile(projectDirectory.resolve("build/libs/demo-1.2.3.jar")).use { jar ->
            val names = jar.entries().asSequence().map { it.name }.toList()
            assertThat(names).contains(
                "so/alaz/demo/Covered.class",
                "so/alaz/demo/RuntimeLibraries.class",
                "so/alaz/demo/libs/bstats/MetricsBase.class",
            )
            val descriptor = jar.getInputStream(jar.getEntry("paper-plugin.yml")).reader().readText()
            assertThat(descriptor).contains("version: \"1.2.3\"")
        }
    }

    @Test
    fun runtimeLibraryTakesItsCoordinatesFromTheCatalog() {
        project()
        write(
            "settings.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.settings")
            }
            rootProject.name = "demo"
            dependencyResolutionManagement {
                repositories.withType<MavenArtifactRepository>().matching { it.name.startsWith("alazso") }
                    .configureEach { url = uri("$testRepository") }
            }
            """,
        )
        write(
            "build.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.paper-plugin")
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
    fun shadowJarMergesTheServiceFilesOfTwoShadedProvidersAndKeepsTheFirstCopyOfAnyOtherPath() {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write(
            "build.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.paper-plugin")
            }
            dependencies {
                implementation(files("libs/first.jar", "libs/second.jar"))
            }
            """,
        )
        val service = "META-INF/services/org.example.spi.Greeter"
        jar("libs/first.jar", mapOf(service to "org.example.first.FirstGreeter\n", "shared.txt" to "first\n"))
        jar(
            "libs/second.jar",
            mapOf(
                service to "org.example.second.SecondGreeter\norg.bstats.BstatsGreeter\n",
                "shared.txt" to "second\n",
            ),
        )
        write("src/main/resources/$service", "so.alaz.demo.OwnGreeter")

        run("shadowJar")

        ZipFile(projectDirectory.resolve("build/libs/demo-1.2.3.jar")).use { jar ->
            val names = jar.entries().asSequence().map { it.name }.toList()
            assertThat(names.filter { it == service }).hasSize(1)
            assertThat(jar.getInputStream(jar.getEntry(service)).reader().readLines()).containsExactlyInAnyOrder(
                "so.alaz.demo.OwnGreeter",
                "org.example.first.FirstGreeter",
                "org.example.second.SecondGreeter",
                "so.alaz.demo.libs.bstats.BstatsGreeter",
            )
            assertThat(names.filter { it == "shared.txt" }).hasSize(1)
            assertThat(textOf(jar, "shared.txt")).isEqualTo("first\n")
        }
    }

    @Test
    fun shadowJarHasNoLicenseOfItsOwnWhenTheRootHasNoneAndKeepsADependencyLicenseUnderItsArtifact() {
        write("gradle.properties", "group=so.alaz.demo\nversion=1.2.3\n")
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write(
            "build.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.paper-plugin")
            }
            dependencies {
                implementation(files("libs/licensed-1.0.jar", "libs/other-2.0.jar"))
            }
            """,
        )
        jar(
            "libs/licensed-1.0.jar",
            mapOf(
                "META-INF/LICENSE" to "Licensed dependency\n",
                "META-INF/LICENSE.txt" to "Licensed dependency, as text\n",
                "org/example/licensed/notes.txt" to "notes\n",
            ),
        )
        jar("libs/other-2.0.jar", mapOf("META-INF/LICENSE" to "Other dependency\n"))
        write("src/main/java/so/alaz/demo/Covered.java", covered)

        val result = run("shadowJar")

        assertThat(projectDirectory.resolve("LICENSE")).doesNotExist()
        assertThat(result.task(":shadowJar")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        ZipFile(projectDirectory.resolve("build/libs/demo-1.2.3.jar")).use { jar ->
            val names = jar.entries().asSequence().map { it.name }.toList()
            assertThat(names).contains("so/alaz/demo/Covered.class", "org/example/licensed/notes.txt")
            assertThat(names.filter { !it.endsWith("/") && it.contains("LICENSE") }).containsExactlyInAnyOrder(
                "META-INF/licenses/licensed-1.0/LICENSE",
                "META-INF/licenses/licensed-1.0/LICENSE.txt",
                "META-INF/licenses/other-2.0/LICENSE",
            )
            assertThat(textOf(jar, "META-INF/licenses/licensed-1.0/LICENSE")).isEqualTo("Licensed dependency\n")
            assertThat(textOf(jar, "META-INF/licenses/licensed-1.0/LICENSE.txt"))
                .isEqualTo("Licensed dependency, as text\n")
            assertThat(textOf(jar, "META-INF/licenses/other-2.0/LICENSE")).isEqualTo("Other dependency\n")
        }
    }

    @Test
    fun shadowJarShipsTheRootLicenseAndKeepsADependencyLicenseUnderItsArtifact() {
        write("LICENSE", "Plugin license")
        write("gradle.properties", "group=so.alaz.demo\nversion=1.2.3\n")
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write(
            "build.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.paper-plugin")
            }
            dependencies {
                implementation(files("libs/licensed-1.0.jar"))
            }
            """,
        )
        jar("libs/licensed-1.0.jar", mapOf("META-INF/LICENSE" to "Licensed dependency\n"))
        write("src/main/java/so/alaz/demo/Covered.java", covered)

        run("shadowJar", "--configuration-cache")
        projectDirectory.resolve("build").deleteRecursively()
        val second = run("shadowJar", "--configuration-cache")

        assertThat(second.output).contains("Reusing configuration cache.")
        ZipFile(projectDirectory.resolve("build/libs/demo-1.2.3.jar")).use { jar ->
            val names = jar.entries().asSequence().map { it.name }.toList()
            assertThat(names.filter { !it.endsWith("/") && it.contains("LICENSE") }).containsExactlyInAnyOrder(
                "META-INF/LICENSE",
                "META-INF/licenses/licensed-1.0/LICENSE",
            )
            assertThat(textOf(jar, "META-INF/LICENSE")).isEqualTo("Plugin license\n")
            assertThat(textOf(jar, "META-INF/licenses/licensed-1.0/LICENSE")).isEqualTo("Licensed dependency\n")
        }
    }

    @Test
    fun shadowJarKeepsLicenseFilesTheBuildAddsByHand() {
        write("LICENSE.md", "Plugin license md")
        write("legal/LICENSE-THIRD-PARTY.txt", "Third party")
        write("legal/NOTICE", "Notice")
        write("gradle.properties", "group=so.alaz.demo\nversion=1.2.3\n")
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write(
            "build.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.paper-plugin")
            }
            dependencies {
                implementation(files("libs/licensed-1.0.jar"))
            }
            tasks.shadowJar {
                from("LICENSE.md") {
                    into("META-INF")
                }
                from("legal") {
                    into("META-INF")
                }
            }
            """,
        )
        jar(
            "libs/licensed-1.0.jar",
            mapOf("META-INF/LICENSE" to "Licensed dependency\n", "META-INF/LICENSE.md" to "Dependency md\n"),
        )
        write("src/main/java/so/alaz/demo/Covered.java", covered)

        run("shadowJar")

        ZipFile(projectDirectory.resolve("build/libs/demo-1.2.3.jar")).use { jar ->
            val names = jar.entries().asSequence().map { it.name }.toList()
            assertThat(names.filter { !it.endsWith("/") && it.contains("LICENSE") }).containsExactlyInAnyOrder(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-THIRD-PARTY.txt",
                "META-INF/licenses/licensed-1.0/LICENSE",
                "META-INF/licenses/licensed-1.0/LICENSE.md",
            )
            assertThat(names).contains("META-INF/NOTICE")
            assertThat(textOf(jar, "META-INF/LICENSE.md")).isEqualTo("Plugin license md\n")
            assertThat(textOf(jar, "META-INF/LICENSE-THIRD-PARTY.txt")).isEqualTo("Third party\n")
            assertThat(textOf(jar, "META-INF/licenses/licensed-1.0/LICENSE.md")).isEqualTo("Dependency md\n")
        }
    }

    @Test
    fun shadowJarKeepsEveryLicenseOfJarsWithOneFileNameAndOfFilesThatDifferOnlyInCase() {
        write("gradle.properties", "group=so.alaz.demo\nversion=1.2.3\n")
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write(
            "build.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.paper-plugin")
            }
            dependencies {
                implementation(files("libs/first.jar", "libs/plain.jar", "other/First.jar", "libs/cased.jar"))
            }
            """,
        )
        jar("libs/first.jar", mapOf("META-INF/LICENSE" to "First\n"))
        jar("libs/plain.jar", mapOf("org/example/plain/notes.txt" to "notes\n"))
        jar("other/First.jar", mapOf("META-INF/LICENSE" to "Second\n"))
        jar("libs/cased.jar", mapOf("META-INF/LICENSE" to "Upper\n", "META-INF/license" to "Lower\n"))
        write("src/main/java/so/alaz/demo/Covered.java", covered)

        run("shadowJar")

        ZipFile(projectDirectory.resolve("build/libs/demo-1.2.3.jar")).use { jar ->
            val names = jar.entries().asSequence().map { it.name }.toList()
            assertThat(names.filter { !it.endsWith("/") && it.contains("license", ignoreCase = true) })
                .containsExactlyInAnyOrder(
                    "META-INF/licenses/first/LICENSE",
                    "META-INF/licenses/First-2/LICENSE",
                    "META-INF/licenses/cased/LICENSE",
                    "META-INF/licenses/cased/license-2",
                )
            assertThat(textOf(jar, "META-INF/licenses/first/LICENSE")).isEqualTo("First\n")
            assertThat(textOf(jar, "META-INF/licenses/First-2/LICENSE")).isEqualTo("Second\n")
            assertThat(textOf(jar, "META-INF/licenses/cased/LICENSE")).isEqualTo("Upper\n")
            assertThat(textOf(jar, "META-INF/licenses/cased/license-2")).isEqualTo("Lower\n")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "so.alaz..demo", "demo."])
    fun shadowJarFailsOnAGroupThatIsBlankOrHasAnEmptySegment(group: String) {
        write("gradle.properties", "group=$group\nversion=1.2.3\n")
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write("build.gradle.kts", "plugins {\n    id(\"so.alaz.taffy.paper-plugin\")\n}")
        write("src/main/java/so/alaz/demo/Covered.java", covered)

        val help = run("help")

        assertThat(help.task(":help")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val result = runner("shadowJar").buildAndFail()

        assertThat(result.output).contains(
            "so.alaz.taffy.paper-plugin relocates Taffy under <group>.libs, and the project group \"$group\" is " +
                "blank or has an empty segment. Set group in gradle.properties, for example " +
                "group=com.example.myplugin.",
        )
        assertThat(projectDirectory.resolve("build/libs")).doesNotExist()
    }

    @ParameterizedTest
    @ValueSource(strings = ["so.alaz.taffy", "so.alaz.taffy.shop"])
    fun shadowJarFailsOnAGroupThatIsTaffysPackageOrInsideIt(group: String) {
        write("gradle.properties", "group=$group\nversion=1.2.3\n")
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write("build.gradle.kts", "plugins {\n    id(\"so.alaz.taffy.paper-plugin\")\n}")
        write("src/main/java/so/alaz/demo/Covered.java", covered)

        val help = run("help")

        assertThat(help.task(":help")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val result = runner("shadowJar").buildAndFail()

        assertThat(result.output).contains(
            "so.alaz.taffy.paper-plugin relocates Taffy under <group>.libs, and the project group \"$group\" is " +
                "so.alaz.taffy or inside it, so the relocated copy would still be inside so.alaz.taffy and Taffy " +
                "refuses it when the plugin enables. Set group in gradle.properties to a package outside " +
                "so.alaz.taffy, for example group=com.example.myplugin.",
        )
        assertThat(projectDirectory.resolve("build/libs")).doesNotExist()
    }

    @Test
    fun shadowJarBuildsOnAGroupThatOnlyStartsWithTaffysPackageName() {
        write("gradle.properties", "group=so.alaz.taffyshop\nversion=1.2.3\n")
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write("build.gradle.kts", "plugins {\n    id(\"so.alaz.taffy.paper-plugin\")\n}")
        val shop = "package so.alaz.taffyshop;\n\npublic interface Shop {\n}\n"
        write("src/main/java/so/alaz/taffyshop/Shop.java", shop)
        write("src/main/java/so/alaz/taffy/Fake.java", "package so.alaz.taffy;\n\npublic interface Fake {\n}\n")

        val result = run("shadowJar")

        assertThat(result.task(":shadowJar")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        ZipFile(projectDirectory.resolve("build/libs/demo-1.2.3.jar")).use { jar ->
            assertThat(jar.entries().asSequence().map { it.name }.toList())
                .contains("so/alaz/taffyshop/Shop.class", "so/alaz/taffyshop/libs/taffy/Fake.class")
        }
    }

    @Test
    fun generateRuntimeLibrariesFailsOnABlankGroup() {
        write("gradle.properties", "version=1.2.3\n")
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write("build.gradle.kts", "plugins {\n    id(\"so.alaz.taffy.paper-plugin\")\n}")

        val result = runner("generateRuntimeLibraries").buildAndFail()

        assertThat(result.task(":generateRuntimeLibraries")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output).contains(
            "the project group \"\" is blank or has an empty segment. Set group in gradle.properties",
        )
    }

    @Test
    fun aGroupTheBuildScriptSetsAfterThePluginIsTheOneRelocatedUnder() {
        write("gradle.properties", "version=1.2.3\n")
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write(
            "build.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.paper-plugin")
            }
            group = "so.alaz.late"
            paperPlugin {
                runtimeLibraries.add("com.zaxxer:HikariCP:7.1.0")
            }
            """,
        )
        write("src/main/resources/paper-plugin.yml", "name: Demo\nloader: so.alaz.late.DemoLoader\n")
        write("src/main/java/so/alaz/taffy/Fake.java", "package so.alaz.taffy;\n\npublic interface Fake {\n}\n")

        run("shadowJar")

        ZipFile(projectDirectory.resolve("build/libs/demo-1.2.3.jar")).use { jar ->
            val names = jar.entries().asSequence().map { it.name }.toList()
            assertThat(names).contains("so/alaz/late/libs/taffy/Fake.class", "so/alaz/late/RuntimeLibraries.class")
        }
    }

    @Test
    fun aHyphenatedGroupBuildsWithoutRuntimeLibrariesAndFailsWithThem() {
        write("gradle.properties", "group=so.alaz.my-demo\nversion=1.2.3\n")
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        val paperPlugin = "plugins {\n    id(\"so.alaz.taffy.paper-plugin\")\n}"
        write("build.gradle.kts", paperPlugin)
        write("src/main/java/so/alaz/taffy/Fake.java", "package so.alaz.taffy;\n\npublic interface Fake {\n}\n")

        val built = run("shadowJar")

        assertThat(built.task(":generateRuntimeLibraries")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        ZipFile(projectDirectory.resolve("build/libs/demo-1.2.3.jar")).use { jar ->
            assertThat(jar.getEntry("so/alaz/my-demo/libs/taffy/Fake.class")).isNotNull()
        }

        val runtimeLibrary = "paperPlugin {\n    runtimeLibraries.add(\"com.zaxxer:HikariCP:7.1.0\")\n}"
        write("build.gradle.kts", paperPlugin, runtimeLibrary)

        val result = runner("generateRuntimeLibraries").buildAndFail()

        assertThat(result.task(":generateRuntimeLibraries")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output).contains(
            "paperPlugin.runtimeLibraries generates RuntimeLibraries in the package named by the project group, and " +
                "\"so.alaz.my-demo\" is not a Java package name. Set group in gradle.properties to one, for example " +
                "group=com.example.myplugin.",
        )
        assertThat(projectDirectory.resolve("build/generated/sources/runtimeLibraries/java/main").walk().toList())
            .noneMatch { it.isFile }
    }

    @Test
    fun runtimeLibrariesFailTheBuildUntilPaperPluginYmlHasATopLevelLoaderKey() {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write(
            "build.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.paper-plugin")
            }
            paperPlugin {
                runtimeLibraries.add("com.zaxxer:HikariCP:7.1.0")
            }
            """,
        )
        write(
            "src/main/resources/paper-plugin.yml",
            "name: Demo\n# loader: so.alaz.demo.DemoLoader\ndependencies:\n  loader: {}\nloaders: none\n",
        )

        val missing = runner("processResources").buildAndFail()

        assertThat(missing.task(":verifyPluginLoader")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(missing.output).contains(
            "paperPlugin.runtimeLibraries lists 1 library and src/main/resources/paper-plugin.yml has no top-level " +
                "loader key, so Paper downloads none and the plugin fails at its first use of one. Add loader: " +
                "with the name of the PluginLoader class that reads RuntimeLibraries.COORDINATES: " +
                "https://alaz.so/taffy/docs/gradle-plugins#runtime-libraries",
        )

        write("src/main/resources/paper-plugin.yml", "name: Demo\nloader: so.alaz.demo.DemoLoader\n")

        val present = run("processResources", "--configuration-cache")

        assertThat(present.task(":verifyPluginLoader")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(present.task(":processResources")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }

    @Test
    fun aBuildWithoutRuntimeLibrariesNeedsNoLoaderKey() {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write("build.gradle.kts", "plugins {\n    id(\"so.alaz.taffy.paper-plugin\")\n}")
        write("src/main/resources/paper-plugin.yml", "name: Demo\n")

        val result = run("processResources")

        assertThat(result.task(":verifyPluginLoader")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }

    @Test
    fun aResourcesDirectoryATaskGeneratesBuildsWithAndWithoutARuntimeLibrary() {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        val build = """
            plugins {
                id("so.alaz.taffy.paper-plugin")
            }
            val generateInfo = tasks.register("generateInfo") {
                val directory = layout.buildDirectory.dir("generated/info")
                outputs.dir(directory)
                doLast {
                    val target = directory.get().asFile
                    target.mkdirs()
                    target.resolve("build-info.properties").writeText("built=now")
                }
            }
            sourceSets.main {
                resources.srcDir(generateInfo)
            }
        """
        write("build.gradle.kts", build)
        write("src/main/resources/paper-plugin.yml", "name: Demo\nloader: so.alaz.demo.DemoLoader\n")
        write("src/main/java/so/alaz/demo/Covered.java", covered)

        val plain = run("shadowJar", "--configuration-cache")

        assertThat(plain.task(":generateInfo")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(plain.task(":verifyPluginLoader")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        ZipFile(projectDirectory.resolve("build/libs/demo-1.2.3.jar")).use { jar ->
            assertThat(jar.entries().asSequence().map { it.name }.toList())
                .contains("build-info.properties", "paper-plugin.yml")
        }

        write("build.gradle.kts", build, "paperPlugin {\n    runtimeLibraries.add(\"com.zaxxer:HikariCP:7.1.0\")\n}")
        projectDirectory.resolve("build").deleteRecursively()

        val withLibrary = run("shadowJar", "--configuration-cache")

        assertThat(withLibrary.task(":generateInfo")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(withLibrary.task(":verifyPluginLoader")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        ZipFile(projectDirectory.resolve("build/libs/demo-1.2.3.jar")).use { jar ->
            assertThat(jar.entries().asSequence().map { it.name }.toList())
                .contains("build-info.properties", "paper-plugin.yml", "so/alaz/demo/RuntimeLibraries.class")
        }
    }

    @Test
    fun aPaperPluginYmlATaskGeneratesIsCheckedForItsLoaderKey() {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write(
            "build.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.paper-plugin")
            }
            val generateDescriptor = tasks.register("generateDescriptor") {
                val source = layout.projectDirectory.file("descriptor.txt")
                val directory = layout.buildDirectory.dir("generated/descriptor")
                inputs.file(source)
                outputs.dir(directory)
                doLast {
                    val target = directory.get().asFile
                    target.mkdirs()
                    target.resolve("paper-plugin.yml").writeText(source.asFile.readText())
                }
            }
            sourceSets.main {
                resources.srcDir(generateDescriptor)
            }
            paperPlugin {
                runtimeLibraries.add("com.zaxxer:HikariCP:7.1.0")
            }
            """,
        )
        write("descriptor.txt", "name: Demo")

        val missing = runner("processResources").buildAndFail()

        assertThat(missing.task(":generateDescriptor")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(missing.task(":verifyPluginLoader")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(missing.output).contains(
            "paperPlugin.runtimeLibraries lists 1 library and build/generated/descriptor/paper-plugin.yml has no " +
                "top-level loader key",
        )

        write("descriptor.txt", "\uFEFFloader: so.alaz.demo.DemoLoader\nname: Demo")

        val present = run("processResources")

        assertThat(present.task(":verifyPluginLoader")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(present.task(":processResources")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }

    @Test
    fun generateRuntimeLibrariesFailsOnACatalogEntryWithoutAVersion() {
        project()
        write(
            "settings.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.settings")
            }
            rootProject.name = "demo"
            dependencyResolutionManagement {
                repositories.withType<MavenArtifactRepository>().matching { it.name.startsWith("alazso") }
                    .configureEach { url = uri("$testRepository") }
            }
            """,
        )
        write(
            "build.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.paper-plugin")
            }
            paperPlugin {
                runtimeLibrary(alaz.hikari)
                runtimeLibrary(alaz.junit.platform.launcher)
            }
            """,
        )

        val result = runner("generateRuntimeLibraries").buildAndFail()

        assertThat(result.task(":generateRuntimeLibraries")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output).contains(
            "The paperPlugin.runtimeLibraries entry \"org.junit.platform:junit-platform-launcher:\" is not " +
                "group:name:version, with an extension or an extension and a classifier allowed before the version, " +
                "in segments that are not blank and hold no whitespace, quote or backslash. A version catalog " +
                "entry passed to runtimeLibrary needs a version.",
        )
        assertThat(projectDirectory.resolve("build/generated/sources/runtimeLibraries/java/main").walk().toList())
            .noneMatch { it.isFile }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "com.zaxxer:HikariCP", "com.zaxxer:HikariCP:jar::7.1.0", "org.lwjgl:lwjgl:jar:natives-windows:3.3.3:x",
            ":HikariCP:7.1.0", "com.zaxxer: :7.1.0",
            "com.zaxxer:Hikari CP:7.1.0", "com.zaxxer:HikariCP:7.1.0\\\"", "com.zaxxer:HikariCP:7.1.0\\\\",
            "com.zaxxer:HikariCP:'7.1.0'",
        ],
    )
    fun generateRuntimeLibrariesFailsOnACoordinateThatIsNotGroupNameVersion(coordinate: String) {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write(
            "build.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.paper-plugin")
            }
            paperPlugin {
                runtimeLibraries.add("com.zaxxer:HikariCP:7.1.0")
                runtimeLibraries.add("$coordinate")
            }
            """,
        )

        val result = runner("generateRuntimeLibraries").buildAndFail()

        assertThat(result.task(":generateRuntimeLibraries")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output).contains("The paperPlugin.runtimeLibraries entry \"", "is not group:name:version")
    }

    @Test
    fun generateRuntimeLibrariesWritesACoordinateWithAnExtensionOrAClassifierAsItIs() {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write(
            "build.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.paper-plugin")
            }
            paperPlugin {
                runtimeLibraries.add("com.zaxxer:HikariCP:jar:7.1.0")
                runtimeLibraries.add("org.lwjgl:lwjgl:jar:natives-windows:3.3.3")
            }
            """,
        )

        val result = run("generateRuntimeLibraries")

        assertThat(result.task(":generateRuntimeLibraries")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val generated = projectDirectory
            .resolve("build/generated/sources/runtimeLibraries/java/main/so/alaz/demo/RuntimeLibraries.java")
        assertThat(generated.readText())
            .contains("\"com.zaxxer:HikariCP:jar:7.1.0\"", "\"org.lwjgl:lwjgl:jar:natives-windows:3.3.3\"")
    }

    @Test
    fun paperPluginYmlKeepsItsBackslashesAndTheDollarEscapeAndFailsOnAnotherExpression() {
        project()
        write("settings.gradle.kts", "rootProject.name = \"demo\"", centralOnly)
        write("build.gradle.kts", "plugins {\n    id(\"so.alaz.taffy.paper-plugin\")\n}")
        val description = "description: \"Costs \\$5, reads C:\\data\\shop and prints First\\nSecond\\\\\""
        write("src/main/resources/paper-plugin.yml", "name: Demo\nversion: \"\${version}\"\n$description\n")
        write("src/main/resources/other.yml", "path: 'C:\\data\\\${version}'\n")

        run("processResources")

        val resources = projectDirectory.resolve("build/resources/main")
        assertThat(resources.resolve("paper-plugin.yml").readText()).isEqualTo(
            "name: Demo\nversion: \"1.2.3\"\n" +
                "description: \"Costs $5, reads C:\\data\\shop and prints First\\nSecond\\\\\"\n",
        )
        assertThat(resources.resolve("other.yml").readText()).isEqualTo("path: 'C:\\data\\\${version}'\n")

        write("src/main/resources/paper-plugin.yml", "name: \"\${name}\"\nversion: \"\${version}\"\n")

        val result = runner("processResources").buildAndFail()

        assertThat(result.task(":processResources")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output).contains("paper-plugin.yml", "name")
    }

    @Test
    fun repositoriesPluginDeclaresTheOrderedRepositoriesWithTheirContentFiltersAndNoCatalog() {
        project()
        // Gradle exposes a repository's content filter only through its internal API.
        write(
            "settings.gradle.kts",
            """
            import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
            import org.gradle.api.internal.artifacts.repositories.ArtifactResolutionDetails
            import org.gradle.api.internal.artifacts.repositories.ContentFilteringRepository
            import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier

            plugins {
                id("so.alaz.taffy.repositories")
            }
            rootProject.name = "demo"
            println("catalogs=" + dependencyResolutionManagement.versionCatalogs.names)
            println("mode=" + dependencyResolutionManagement.repositoriesMode.get())
            val groups = listOf(${filterGroups.joinToString { "\"$it\"" }}).flatMap { listOf(it, it + ".sub") }
            fun accepted(repository: ArtifactRepository, version: String): List<String> {
                val filter = (repository as ContentFilteringRepository).contentFilter
                return groups.filter { group ->
                    val module = DefaultModuleIdentifier.newId(group, "probe")
                    var found = true
                    filter.execute(object : ArtifactResolutionDetails {
                        override fun getModuleId() = module
                        override fun getComponentId() = DefaultModuleComponentIdentifier.newId(module, version)
                        override fun isVersionListing() = false
                        override fun notFound() {
                            found = false
                        }
                    })
                    found
                }
            }
            for (repository in dependencyResolutionManagement.repositories) {
                val releases = accepted(repository, "1.0")
                val snapshots = accepted(repository, "1.0-SNAPSHOT")
                val versions = when {
                    releases == snapshots -> "any"
                    snapshots.isEmpty() -> "releases"
                    releases.isEmpty() -> "snapshots"
                    else -> "mixed"
                }
                val served = releases.ifEmpty { snapshots }
                val content = if ("org.example" in served) "all but " + (groups - served) else served.toString()
                val url = (repository as MavenArtifactRepository).url
                println("repository " + repository.name + " " + url + " versions=" + versions + " groups=" + content)
            }
            """,
        )

        val result = run("help")

        assertThat(result.output.lines()).contains("catalogs=[]", "mode=PREFER_SETTINGS")
        assertThat(result.output.lines().filter { it.startsWith("repository ") }).containsExactly(
            "repository MavenRepo https://repo.maven.apache.org/maven2/ versions=releases " +
                "groups=all but [so.alaz, so.alaz.sub]",
            "repository papermc https://repo.papermc.io/repository/maven-public/ versions=any " +
                "groups=[io.papermc, io.papermc.sub, com.mojang, net.md-5]",
            "repository alazso https://repo.alaz.so/releases versions=releases groups=[so.alaz, so.alaz.sub]",
            "repository alazsoSnapshots https://repo.alaz.so/snapshots versions=snapshots " +
                "groups=[so.alaz, so.alaz.sub]",
            "repository placeholderapi https://repo.extendedclip.com/releases/ versions=any groups=[me.clip]",
            "repository faststats https://repo.faststats.dev/releases versions=any groups=[dev.faststats.metrics]",
            "repository vaultunlocked https://repo.codemc.io/repository/creatorfromhell/ versions=any " +
                "groups=[net.milkbowl.vault]",
            "repository playerpoints https://repo.rosewooddev.io/repository/public/ versions=any " +
                "groups=[org.black_ixx]",
            "repository oraxen https://repo.oraxen.com/releases versions=any groups=[io.th0rgal, md.thomas.hopper]",
            "repository nexo https://repo.nexomc.com/releases versions=any " +
                "groups=[com.nexomc, com.tcoded, me.gabytm.util, team.unnamed]",
            "repository enginehub https://maven.enginehub.org/repo/ versions=any " +
                "groups=[com.sk89q, com.sk89q.sub, org.enginehub, org.enginehub.sub]",
            "repository opencollab https://repo.opencollab.dev/main/ versions=any " +
                "groups=[org.geysermc.floodgate, org.geysermc.geyser, org.geysermc.api]",
            "repository fancyinnovations https://maven.fancyspaces.net/fancyinnovations/releases versions=any " +
                "groups=[de.oliver]",
            "repository thenextlvl https://repo.thenextlvl.net/releases versions=any groups=[net.thenextlvl]",
            "repository packetevents https://repo.codemc.io/repository/maven-releases/ versions=any " +
                "groups=[com.github.retrooper]",
            "repository jitpack https://jitpack.io versions=any " +
                "groups=[com.github.MilkBowl, com.github.Arcaniax-Development, com.github.decentsoftware-eu, " +
                "com.github.HologramLib, com.github.maximjsx.EntityLib]",
        )
    }

    @Test
    fun aRepositoryIsAskedOnlyForTheGroupsOfItsFilter() {
        project()
        write(
            "settings.gradle.kts",
            """
            plugins {
                id("so.alaz.taffy.repositories")
            }
            rootProject.name = "demo"
            val directory = rootDir.resolve("repositories")
            dependencyResolutionManagement {
                repositories.withType<MavenArtifactRepository>().configureEach {
                    url = uri(directory.resolve(name))
                }
            }
            """,
        )
        for (repository in repositoryNames) {
            if (repository != "MavenRepo") {
                module(repository, "org.example", "outside", "1.0")
            }
            module(repository, "so.alaz.demo", "inside", "1.0")
            module(repository, "so.alaz.demo", "inside", "1.0-SNAPSHOT")
        }
        write(
            "build.gradle.kts",
            """
            import org.gradle.api.artifacts.result.UnresolvedDependencyResult

            val missing = listOf(
                "org.example:outside:1.0",
                "com.github.example:missing:1.0",
                "so.alaz.demo:missing:1.0",
                "so.alaz.demo:missing:1.0-SNAPSHOT",
                "io.papermc.paper:missing:1.0",
                "com.mojang:missing:1.0",
                "net.md-5:missing:1.0",
                "me.clip:missing:1.0",
                "dev.faststats.metrics:missing:1.0",
                "net.milkbowl.vault:missing:1.0",
                "org.black_ixx:missing:1.0",
                "io.th0rgal:missing:1.0",
                "md.thomas.hopper:missing:1.0",
                "com.nexomc:missing:1.0",
                "com.tcoded:missing:1.0",
                "me.gabytm.util:missing:1.0",
                "team.unnamed:missing:1.0",
                "com.sk89q.worldguard:missing:1.0",
                "org.enginehub.lin-bus:missing:1.0",
                "org.geysermc.floodgate:missing:1.0",
                "org.geysermc.geyser:missing:1.0",
                "org.geysermc.api:missing:1.0",
                "org.geysermc.cumulus:missing:1.0",
                "de.oliver:missing:1.0",
                "net.thenextlvl:missing:1.0",
                "com.github.retrooper:missing:1.0",
                "com.github.MilkBowl:missing:1.0",
                "com.github.Arcaniax-Development:missing:1.0",
                "com.github.decentsoftware-eu:missing:1.0",
                "com.github.HologramLib:missing:1.0",
                "com.github.maximjsx.EntityLib:missing:1.0",
            )
            for (notation in missing) {
                val configuration = configurations.detachedConfiguration(dependencies.create(notation))
                val failure = configuration.incoming.resolutionResult.allDependencies
                    .filterIsInstance<UnresolvedDependencyResult>().single().failure
                val asked = generateSequence<Throwable>(failure) { it.cause }.mapNotNull { it.message }
                    .flatMap { it.lines() }.filter { it.trim().startsWith("- file:") }
                    .map { it.substringAfter("/repositories/").substringBefore('/') }.distinct().toList()
                println("asked " + notation + "=" + asked)
            }
            for (version in listOf("1.0", "1.0-SNAPSHOT")) {
                val inside = dependencies.create("so.alaz.demo:inside:" + version)
                val jar = configurations.detachedConfiguration(inside).singleFile
                println("resolved inside " + version + "=" + jar.relativeTo(rootDir).invariantSeparatorsPath)
            }
            """,
        )

        val result = run("help")

        assertThat(result.output.lines().filter { it.startsWith("asked ") }).containsExactly(
            "asked org.example:outside:1.0=[MavenRepo]",
            "asked com.github.example:missing:1.0=[MavenRepo]",
            "asked so.alaz.demo:missing:1.0=[alazso]",
            "asked so.alaz.demo:missing:1.0-SNAPSHOT=[alazsoSnapshots]",
            "asked io.papermc.paper:missing:1.0=[MavenRepo, papermc]",
            "asked com.mojang:missing:1.0=[MavenRepo, papermc]",
            "asked net.md-5:missing:1.0=[MavenRepo, papermc]",
            "asked me.clip:missing:1.0=[MavenRepo, placeholderapi]",
            "asked dev.faststats.metrics:missing:1.0=[MavenRepo, faststats]",
            "asked net.milkbowl.vault:missing:1.0=[MavenRepo, vaultunlocked]",
            "asked org.black_ixx:missing:1.0=[MavenRepo, playerpoints]",
            "asked io.th0rgal:missing:1.0=[MavenRepo, oraxen]",
            "asked md.thomas.hopper:missing:1.0=[MavenRepo, oraxen]",
            "asked com.nexomc:missing:1.0=[MavenRepo, nexo]",
            "asked com.tcoded:missing:1.0=[MavenRepo, nexo]",
            "asked me.gabytm.util:missing:1.0=[MavenRepo, nexo]",
            "asked team.unnamed:missing:1.0=[MavenRepo, nexo]",
            "asked com.sk89q.worldguard:missing:1.0=[MavenRepo, enginehub]",
            "asked org.enginehub.lin-bus:missing:1.0=[MavenRepo, enginehub]",
            "asked org.geysermc.floodgate:missing:1.0=[MavenRepo, opencollab]",
            "asked org.geysermc.geyser:missing:1.0=[MavenRepo, opencollab]",
            "asked org.geysermc.api:missing:1.0=[MavenRepo, opencollab]",
            "asked org.geysermc.cumulus:missing:1.0=[MavenRepo]",
            "asked de.oliver:missing:1.0=[MavenRepo, fancyinnovations]",
            "asked net.thenextlvl:missing:1.0=[MavenRepo, thenextlvl]",
            "asked com.github.retrooper:missing:1.0=[MavenRepo, packetevents]",
            "asked com.github.MilkBowl:missing:1.0=[MavenRepo, jitpack]",
            "asked com.github.Arcaniax-Development:missing:1.0=[MavenRepo, jitpack]",
            "asked com.github.decentsoftware-eu:missing:1.0=[MavenRepo, jitpack]",
            "asked com.github.HologramLib:missing:1.0=[MavenRepo, jitpack]",
            "asked com.github.maximjsx.EntityLib:missing:1.0=[MavenRepo, jitpack]",
        )
        assertThat(result.output.lines()).contains(
            "resolved inside 1.0=repositories/alazso/so/alaz/demo/inside/1.0/inside-1.0.jar",
            "resolved inside 1.0-SNAPSHOT=" +
                "repositories/alazsoSnapshots/so/alaz/demo/inside/1.0-SNAPSHOT/inside-1.0-SNAPSHOT.jar",
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
                id("so.alaz.taffy.java")
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
                id("so.alaz.taffy.java")
            }
            dependencies {
                implementation("org.mockito:mockito-core:5.24.0")
            }
            """,
        )
        write(
            "app/build.gradle.kts",
            "plugins {\n    id(\"so.alaz.taffy.coverage\")\n}",
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
        write("build.gradle.kts", "plugins {\n    id(\"so.alaz.taffy.java\")\n}", junit)
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
                id("so.alaz.taffy.java")
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
        write("build.gradle.kts", "plugins {\n    id(\"so.alaz.taffy.java\")\n}", junit)
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
            "plugins {\n    id(\"so.alaz.taffy.java\")\n}",
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

    /** Stores the POM and an empty jar of a module in the local directory that stands in for [repository]. */
    private fun module(repository: String, group: String, name: String, version: String) {
        val directory = "repositories/$repository/${group.replace('.', '/')}/$name/$version"
        write(
            "$directory/$name-$version.pom",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>$group</groupId>
              <artifactId>$name</artifactId>
              <version>$version</version>
            </project>
            """,
        )
        projectDirectory.resolve("$directory/$name-$version.jar").writeBytes(ByteArray(0))
    }

    private fun paperFloor(): String =
        File(System.getProperty("taffy.sourceRoot"), "gradle.properties").readLines()
            .first { it.startsWith("taffy.paperFloor=") }.substringAfter('=').trim()

    /** Writes a jar at [path] that holds [entries], each a path and its text. */
    private fun jar(path: String, entries: Map<String, String>) {
        val file = projectDirectory.resolve(path)
        file.parentFile.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun textOf(jar: ZipFile, entry: String): String =
        jar.getInputStream(jar.getEntry(entry)).reader().readText()

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
