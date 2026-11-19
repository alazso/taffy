package so.alaz.taffy.gradle

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Taffy's own gradle/skip-published-releases.gradle.kts, applied to a build that publishes to [repository]. */
class SkipPublishedReleasesTest {

    @TempDir
    lateinit var projectDirectory: File

    private val repository = FakeMavenRepository()

    private val script = File(System.getProperty("taffy.sourceRoot"))
        .resolve("gradle/skip-published-releases.gradle.kts")

    private val module = "so/alaz/demo/demo"

    private val metadata = "$module/maven-metadata.xml"

    private val publishToAlazso = ":publishLibraryPublicationToAlazsoRepository"

    @AfterEach
    fun stopRepository() {
        repository.close()
    }

    @Test
    fun aReleaseIsUploadedWithItsMetadataLastAndARerunSkipsIt() {
        demo("1.2.3")

        val first = run(publishToAlazso, "--configuration-cache")

        assertThat(first.task(publishToAlazso)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(repository.uploadedFiles()).containsExactly(
            "$module/1.2.3/demo-1.2.3.jar",
            "$module/1.2.3/demo-1.2.3.pom",
            "$module/1.2.3/demo-1.2.3.module",
            metadata,
        )
        assertThat(repository.uploaded().takeLast(5)).containsExactly(
            metadata,
            "$metadata.sha1",
            "$metadata.md5",
            "$metadata.sha256",
            "$metadata.sha512",
        )
        repository.clearUploads()

        val second = run(publishToAlazso, "--configuration-cache")

        assertThat(second.output).contains("Reusing configuration cache.")
        assertThat(second.task(publishToAlazso)?.outcome).isEqualTo(TaskOutcome.SKIPPED)
        assertThat(second.output).contains("$module/1.2.3/demo-1.2.3.pom is already complete in the alazso repository")
        assertThat(repository.uploaded()).isEmpty()
    }

    @Test
    fun aReleaseInterruptedBeforeItsMetadataIsUploadedAgain() {
        repository.put("$module/1.2.3/demo-1.2.3.jar")
        repository.putPom("so/alaz/demo", "demo", "1.2.3")
        repository.putMetadata("so/alaz/demo", "demo", "1.2.2")
        repository.putSha512(metadata)
        demo("1.2.3")

        val result = run(publishToAlazso)

        assertThat(result.task(publishToAlazso)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(repository.uploadedFiles()).contains("$module/1.2.3/demo-1.2.3.module", metadata)
    }

    @Test
    fun aReleaseInterruptedBeforeTheChecksumsOfItsMetadataIsUploadedAgain() {
        repository.putPom("so/alaz/demo", "demo", "1.2.3")
        repository.putMetadata("so/alaz/demo", "demo", "1.2.3")
        demo("1.2.3")

        val result = run(publishToAlazso)

        assertThat(result.task(publishToAlazso)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).doesNotContain("is already complete in the alazso repository")
        assertThat(repository.uploaded()).contains("$module/1.2.3/demo-1.2.3.jar", "$metadata.sha512")
    }

    @Test
    fun aReleaseWhoseMetadataSha512IsOfTheMetadataBeforeItIsUploadedAgain() {
        repository.putMetadata("so/alaz/demo", "demo", "1.2.2")
        repository.putSha512(metadata)
        repository.putMetadata("so/alaz/demo", "demo", "1.2.2", "1.2.3")
        repository.putPom("so/alaz/demo", "demo", "1.2.3")
        demo("1.2.3")

        val result = run(publishToAlazso)

        assertThat(result.task(publishToAlazso)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).doesNotContain("is already complete in the alazso repository")
        assertThat(repository.uploaded()).contains("$module/1.2.3/demo-1.2.3.jar", "$metadata.sha512")
    }

    @Test
    fun aReleaseListedInTheMetadataWithoutItsPomIsUploadedAgain() {
        repository.putMetadata("so/alaz/demo", "demo", "1.2.3")
        repository.putSha512(metadata)
        demo("1.2.3")

        val result = run(publishToAlazso)

        assertThat(result.task(publishToAlazso)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(repository.uploadedFiles()).contains("$module/1.2.3/demo-1.2.3.pom")
    }

    @Test
    fun aReleaseIsUploadedWhenTheRepositoryAnswersAGetWith500() {
        repository.putRelease("so/alaz/demo", "demo", "1.2.3")
        repository.failNextGets(500)
        demo("1.2.3")

        val result = run(publishToAlazso)

        assertThat(result.task(publishToAlazso)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).doesNotContain("is already complete in the alazso repository")
        assertThat(repository.uploadedFiles()).contains("$module/1.2.3/demo-1.2.3.jar", metadata)
    }

    @Test
    fun aReleaseIsPublishedWithAWarningWhenTheRepositoryCannotBeReached() {
        val closed = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { socket ->
            "http://127.0.0.1:${socket.localPort}/releases"
        }
        demo("1.2.3", url = closed)

        val result = runner(publishToAlazso).buildAndFail()

        assertThat(result.task(publishToAlazso)?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output).contains(
            "Could not check $closed/$module/1.2.3/demo-1.2.3.pom in the alazso repository, so $publishToAlazso runs",
            "Failed to publish publication 'library' to repository 'alazso'",
        )
    }

    @Test
    fun aSnapshotIsUploadedEvenWhenTheRepositoryHasIt() {
        repository.putRelease("so/alaz/demo", "demo", "1.2.3-SNAPSHOT")
        demo("1.2.3-SNAPSHOT")

        val result = run(publishToAlazso)

        assertThat(result.task(publishToAlazso)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(repository.uploadedFiles()).anyMatch { it.startsWith("$module/1.2.3-SNAPSHOT/demo-1.2.3-") }
    }

    @Test
    fun aRepositoryWithAnotherNameIsUploadedToEvenWhenItHasTheRelease() {
        repository.putRelease("so/alaz/demo", "demo", "1.2.3")
        demo("1.2.3", repositoryName = "mirror")

        val result = run(":publishLibraryPublicationToMirrorRepository")

        assertThat(result.task(":publishLibraryPublicationToMirrorRepository")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(repository.uploadedFiles()).contains("$module/1.2.3/demo-1.2.3.jar")
    }

    private fun demo(version: String, repositoryName: String = "alazso", url: String = repository.url) {
        write("settings.gradle.kts", "rootProject.name = \"demo\"\n")
        write(
            "build.gradle.kts",
            """
            plugins {
                `java-library`
                `maven-publish`
            }
            group = "so.alaz.demo"
            version = "$version"
            publishing {
                publications {
                    create<MavenPublication>("library") {
                        from(components["java"])
                    }
                }
                repositories {
                    maven("$url") {
                        name = "$repositoryName"
                        isAllowInsecureProtocol = true
                    }
                }
            }
            apply(from = "${script.invariantSeparatorsPath}")
            """.trimIndent() + "\n",
        )
        write("src/main/java/so/alaz/demo/Demo.java", "package so.alaz.demo;\n\npublic interface Demo {\n}\n")
    }

    private fun run(vararg arguments: String): BuildResult = runner(*arguments).build()

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDirectory)
            .withArguments(*arguments, "--stacktrace")

    private fun write(path: String, content: String) {
        val file = projectDirectory.resolve(path)
        file.parentFile.mkdirs()
        file.writeText(content)
    }
}
