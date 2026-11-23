package so.alaz.taffy.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Fails when [coordinates] lists a runtime library and a file of [descriptors] has no top-level `loader` key with
 * a value.
 *
 * A key counts when its line starts at the first column, so a `loader` in a comment or under another key does not.
 * A key has a value when text other than a comment follows its colon, on the same line or, indented, on the next
 * line that is neither blank nor a comment. The texts `""`, `''`, `~`, `null`, `Null` and `NULL` are no value, and
 * neither is a block scalar indicator, `|` or `>`, whose next line that is not blank is not indented.
 * A top-level mapping that is indented as a whole or written in flow style, such as `{loader: a.B}`, does not count
 * either. A byte order mark before the first line is skipped.
 * A file of [descriptors] that does not exist is not checked.
 *
 * While [coordinates] is empty, a file is checked only when [generatedLoader] is set: the task then fails when the
 * key's value, on the line of the key or on the next line that is neither blank nor a comment, is that name, plain
 * or in quotes, because no such class is generated for an empty list. No other value is compared with a class.
 */
@DisableCachingByDefault(because = "Reads one small file and writes none")
abstract class VerifyPluginLoader : DefaultTask() {

    @get:Input
    abstract val coordinates: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val descriptors: ConfigurableFileCollection

    /** Name of the loader class the build generates, which the failure message then gives as the value to add. */
    @get:Input
    @get:Optional
    abstract val generatedLoader: Property<String>

    /** The directory the failure message names a descriptor relative to. */
    @get:Internal
    abstract val projectDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val libraries = coordinates.get().size
        if (libraries == 0) {
            generatedLoader.orNull?.let(::requireNoGeneratedLoader)
            return
        }
        for (descriptor in descriptors.files.filter { it.isFile }) {
            if (!namesLoader(descriptor.readText().removePrefix("\uFEFF").lines())) {
                val path = descriptor.relativeTo(projectDirectory.get().asFile).invariantSeparatorsPath
                val listed = if (libraries == 1) "1 library" else "$libraries libraries"
                val fix = generatedLoader.orNull
                    ?.let { "Add loader: $it, the PluginLoader class paperPlugin.generateLoader generates: " }
                    ?: "Add loader: with the name of the PluginLoader class that reads RuntimeLibraries.COORDINATES: "
                throw GradleException(
                    "paperPlugin.runtimeLibraries lists $listed and $path has no top-level loader key, so Paper " +
                        "downloads none and the plugin fails at its first use of one. " + fix +
                        "https://alaz.so/taffy/docs/gradle-plugins#runtime-libraries"
                )
            }
        }
    }

    private fun requireNoGeneratedLoader(generated: String) {
        for (descriptor in descriptors.files.filter { it.isFile }) {
            if (generated in loaderValues(descriptor.readText().removePrefix("﻿").lines())) {
                val path = descriptor.relativeTo(projectDirectory.get().asFile).invariantSeparatorsPath
                throw GradleException(
                    "$path names the loader $generated, which paperPlugin.generateLoader generates only while " +
                        "paperPlugin.runtimeLibraries lists a library. The list is empty, so the jar would hold no " +
                        "such class and Paper could not load the plugin. Remove the loader key or list a library: " +
                        "https://alaz.so/taffy/docs/gradle-plugins#generated-loader"
                )
            }
        }
    }

    private fun loaderValues(lines: List<String>): List<String> =
        lines.withIndex().mapNotNull { (index, line) ->
            val value = (LOADER_KEY.matchEntire(line) ?: return@mapNotNull null).groupValues[2]
            val text = if (NO_VALUE.matches(value)) {
                lines.drop(index + 1).firstOrNull { !NO_VALUE.matches(it) }?.takeIf { it.startsWith(" ") }
            } else {
                value
            }
            text?.replace(TRAILING_COMMENT, "")?.trim()?.let { scalar ->
                QUOTED.matchEntire(scalar)?.groupValues?.get(2) ?: scalar
            }
        }

    private fun namesLoader(lines: List<String>): Boolean =
        lines.withIndex().any { (index, line) ->
            val value = (LOADER_KEY.matchEntire(line) ?: return@any false).groupValues[2]
            val rest = lines.drop(index + 1)
            when {
                BLOCK_SCALAR.matches(value) -> rest.firstOrNull { it.isNotBlank() }?.startsWith(" ") == true
                !NO_VALUE.matches(value) -> !EMPTY_VALUE.matches(value)
                else -> rest.firstOrNull { !NO_VALUE.matches(it) }
                    ?.let { it.startsWith(" ") && !EMPTY_VALUE.matches(it) } == true
            }
        }

    private companion object {
        val LOADER_KEY = Regex("(loader|\"loader\"|'loader')\\s*:(\\s.*)?")
        val NO_VALUE = Regex("\\s*(#.*)?")
        val EMPTY_VALUE = Regex("\\s+(\"\"|''|~|null|Null|NULL)(\\s+#.*|\\s*)")
        val TRAILING_COMMENT = Regex("\\s+#.*")
        val QUOTED = Regex("([\"'])(.*)\\1")
        val BLOCK_SCALAR = Regex("\\s+[|>][-+1-9]{0,2}(\\s+#.*|\\s*)")
    }
}
