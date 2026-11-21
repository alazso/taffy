package so.alaz.taffy.gradle

import java.io.File
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opentest4j.TestAbortedException

/**
 * Taffy's own .github/scripts/resolve-release-version.sh, run by bash in a git checkout of its own, and the
 * lines of .github/workflows/release.yml that call it and create the GitHub Release. On Windows a test that runs
 * the script is skipped when no bash.exe of Git for Windows is found.
 */
class ResolveReleaseVersionTest {

    @TempDir
    lateinit var directory: File

    private val sourceRoot = File(System.getProperty("taffy.sourceRoot"))

    private val script = sourceRoot.resolve(".github/scripts/resolve-release-version.sh")

    private val workflow = sourceRoot.resolve(".github/workflows/release.yml")

    private lateinit var checkout: File

    private lateinit var output: File

    private lateinit var first: String

    private lateinit var head: String

    private class Result(val exitCode: Int, val error: String, val output: String)

    @BeforeEach
    fun createACheckoutWithTwoCommits() {
        checkout = directory.resolve("checkout").apply { mkdirs() }
        output = directory.resolve("github-output")
        directory.resolve("gitconfig").writeText("")
        git("init", "--quiet")
        val tree = git("write-tree")
        first = git("commit-tree", tree, "-m", "first")
        head = git("commit-tree", tree, "-p", first, "-m", "second")
        git("update-ref", "HEAD", head)
    }

    @Test
    fun aPushedTagThatEqualsTheVersionGivesTheVersionAndTheTag() {
        version("1.2.3")

        val result = resolve(event = "push", dryRun = "false", ref = "v1.2.3")

        assertThat(result.error).isEmpty()
        assertThat(result.exitCode).isZero()
        assertThat(result.output).isEqualTo("version=1.2.3\ntag=v1.2.3\n")
    }

    @Test
    fun aVersionLineThatEndsWithACarriageReturnIsReadWithoutIt() {
        checkout.resolve("gradle.properties").writeText("group=so.alaz.demo\r\nversion=1.2.3\r\n")

        val result = resolve(event = "push", dryRun = "false", ref = "v1.2.3")

        assertThat(result.exitCode).isZero()
        assertThat(result.output).isEqualTo("version=1.2.3\ntag=v1.2.3\n")
    }

    @Test
    fun gradlePropertiesWithoutAVersionIsRefused() {
        checkout.resolve("gradle.properties").writeText("group=so.alaz.demo\n")

        val result = resolve(event = "push", dryRun = "false", ref = "v")

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.error).contains("Could not read version from gradle.properties")
        assertThat(result.output).isEmpty()
    }

    @Test
    fun aPushedTagOfASnapshotVersionIsRefused() {
        version("1.2.3-SNAPSHOT")

        val result = resolve(event = "push", dryRun = "false", ref = "v1.2.3-SNAPSHOT")

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.error).contains(
            "gradle.properties has the snapshot version 1.2.3-SNAPSHOT; the CI workflow publishes snapshots.",
        )
        assertThat(result.output).isEmpty()
    }

    @Test
    fun aManualRunThatPublishesASnapshotVersionIsRefusedEvenWithItsTagOnHead() {
        version("1.2.3-SNAPSHOT")
        tag("v1.2.3-SNAPSHOT", head)

        val result = resolve(event = "workflow_dispatch", dryRun = "false")

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.error).contains("gradle.properties has the snapshot version 1.2.3-SNAPSHOT")
        assertThat(result.output).isEmpty()
    }

    @Test
    fun aPushedTagThatDoesNotEqualTheVersionIsRefused() {
        version("1.2.3")

        val other = resolve(event = "push", dryRun = "false", ref = "v9.9.9")
        val longer = resolve(event = "push", dryRun = "false", ref = "v1.2.3-rc1")

        assertThat(other.exitCode).isEqualTo(1)
        assertThat(other.error).contains("Tag v9.9.9 (9.9.9) does not match gradle.properties (1.2.3)")
        assertThat(longer.exitCode).isEqualTo(1)
        assertThat(longer.error)
            .contains("Tag v1.2.3-rc1 (1.2.3-rc1) does not match gradle.properties (1.2.3)")
        assertThat(output).doesNotExist()
    }

    @Test
    fun aManualRunThatPublishesIsRefusedWithoutTheTagOfTheVersion() {
        version("1.2.3")
        tag("v1.2.2", head)

        val result = resolve(event = "workflow_dispatch", dryRun = "false")

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.error).contains("Tag v1.2.3 does not exist; push it before publishing 1.2.3.")
        assertThat(result.output).isEmpty()
    }

    @Test
    fun aManualRunThatPublishesIsRefusedWhenTheTagIsOnAnotherCommit() {
        version("1.2.3")
        tag("v1.2.3", first)

        val result = resolve(event = "workflow_dispatch", dryRun = "false")

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.error)
            .contains("Tag v1.2.3 is at $first, not at the commit being built; run the workflow on the tag.")
        assertThat(result.output).isEmpty()
    }

    @Test
    fun aManualRunThatPublishesGivesTheVersionAndTheTagWhenTheTagIsOnHead() {
        version("1.2.3")
        tag("v1.2.3", head)

        val result = resolve(event = "workflow_dispatch", dryRun = "false")

        assertThat(result.error).isEmpty()
        assertThat(result.exitCode).isZero()
        assertThat(result.output).isEqualTo("version=1.2.3\ntag=v1.2.3\n")
    }

    @Test
    fun aDryRunGivesTheVersionAndTheTagOfASnapshotVersionWithoutATag() {
        version("1.2.3-SNAPSHOT")

        val result = resolve(event = "workflow_dispatch", dryRun = "true")

        assertThat(result.error).isEmpty()
        assertThat(result.exitCode).isZero()
        assertThat(result.output).isEqualTo("version=1.2.3-SNAPSHOT\ntag=v1.2.3-SNAPSHOT\n")
    }

    @Test
    fun theReleaseWorkflowResolvesItsVersionWithTheScriptAndCreatesNoTag() {
        val lines = workflow.readLines()
        val step = lines.dropWhile { it.trim() != "- name: Resolve version" }.drop(1)
            .takeWhile { !it.trim().startsWith("- name:") }.map { it.trim() }

        assertThat(step).contains(
            "id: version",
            "EVENT_NAME: \${{ github.event_name }}",
            "DRY_RUN: \${{ github.event_name == 'workflow_dispatch' && inputs.dry_run }}",
            "run: bash .github/scripts/resolve-release-version.sh",
        )
        assertThat(lines).contains(
            "      version: \${{ steps.version.outputs.version }}",
            "      tag: \${{ steps.version.outputs.tag }}",
            "          fetch-depth: 0",
        )
        assertThat(lines.filter { it.contains("gh release create") }).hasSize(2).allMatch {
            it.trim().startsWith("gh release create \"\$TAG\" --verify-tag ")
        }
    }

    @Test
    fun onWindowsAPathWithoutGitForWindowsSkipsTheTest() {
        assumeTrue(windows)

        assertThatThrownBy { bash(directory.path) }.isInstanceOf(TestAbortedException::class.java)
        assertThat(File(bash(System.getenv("PATH")))).isFile()
    }

    private fun version(version: String) {
        checkout.resolve("gradle.properties").writeText("group=so.alaz.demo\nversion=$version\n")
    }

    private fun tag(name: String, commit: String) {
        git("update-ref", "refs/tags/$name", commit)
    }

    private fun resolve(event: String, dryRun: String, ref: String? = null): Result {
        val variables = mutableMapOf(
            "EVENT_NAME" to event,
            "DRY_RUN" to dryRun,
            "GITHUB_OUTPUT" to output.invariantSeparatorsPath,
        )
        if (ref != null) {
            variables["GITHUB_REF_NAME"] = ref
        }
        val result = execute(listOf(bash(System.getenv("PATH")), script.invariantSeparatorsPath), variables)
        return Result(result.first, result.third, if (output.isFile) output.readText() else "")
    }

    private fun git(vararg arguments: String): String {
        val result = execute(listOf("git", *arguments), emptyMap())
        check(result.first == 0) { "git ${arguments.joinToString(" ")} exited with ${result.first}: ${result.third}" }
        return result.second.trim()
    }

    /** Runs [command] in the checkout and returns its exit code, standard output and standard error. */
    private fun execute(command: List<String>, variables: Map<String, String>): Triple<Int, String, String> {
        val standardOutput = directory.resolve("stdout")
        val standardError = directory.resolve("stderr")
        val builder = ProcessBuilder(command).directory(checkout)
            .redirectOutput(standardOutput).redirectError(standardError)
        val environment = builder.environment()
        environment.keys.removeIf { it.startsWith("GIT_") || it.startsWith("GITHUB_") }
        environment.remove("EVENT_NAME")
        environment.remove("DRY_RUN")
        // The checkout reads no git configuration of the machine, so no commit hook, signing or template applies.
        environment["GIT_CONFIG_GLOBAL"] = directory.resolve("gitconfig").path
        environment["GIT_CONFIG_NOSYSTEM"] = "1"
        environment["GIT_AUTHOR_NAME"] = "Demo"
        environment["GIT_AUTHOR_EMAIL"] = "demo@example.com"
        environment["GIT_COMMITTER_NAME"] = "Demo"
        environment["GIT_COMMITTER_EMAIL"] = "demo@example.com"
        environment.putAll(variables)
        val process = builder.start()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "${command.first()} did not exit within 60 seconds" }
        return Triple(process.exitValue(), standardOutput.readText(), standardError.readText())
    }

    private val windows = System.getProperty("os.name").startsWith("Windows")

    /** The bash to run, found through the directories of [path] on Windows, where a test without one is skipped. */
    private fun bash(path: String): String {
        if (!windows) {
            return "bash"
        }
        // A bash.exe on a Windows PATH can be the WSL launcher, which does not read Windows paths. Git for Windows
        // keeps its bash.exe in the bin directory beside the cmd and mingw64 directories that hold git.exe.
        val found = path.split(File.pathSeparator)
            .filter { File(it, "git.exe").isFile }
            .flatMap { listOf(File(it, "../bin/bash.exe"), File(it, "../../bin/bash.exe")) }
            .firstOrNull { it.isFile }
        assumeTrue(found != null, "no bash.exe of Git for Windows beside a git.exe on the PATH")
        return found!!.canonicalPath
    }
}
