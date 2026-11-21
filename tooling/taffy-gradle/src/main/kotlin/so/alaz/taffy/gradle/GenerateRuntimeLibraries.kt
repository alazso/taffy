package so.alaz.taffy.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Writes the `RuntimeLibraries` class a plugin's `PluginLoader` reads its coordinates from.
 *
 * The task fails, and writes nothing, when [packageName] is blank or has an empty segment. With [coordinates]
 * listed it also fails when [packageName] is not a Java package name, or when an entry is not `group:name:version`,
 * `group:name:extension:version` or `group:name:extension:classifier:version` with segments that are not blank and
 * hold no whitespace, quote or backslash.
 */
@CacheableTask
abstract class GenerateRuntimeLibraries : DefaultTask() {

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val coordinates: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val group = PluginGroup.requireSegments(packageName.get())
        val libraries = coordinates.get()
        if (libraries.isNotEmpty()) {
            PluginGroup.requireJavaPackage(group)
            libraries.forEach(::requireCoordinate)
        }
        val root = outputDirectory.get().asFile
        root.deleteRecursively()
        root.mkdirs()
        if (libraries.isEmpty()) {
            return
        }
        val entries = libraries.joinToString(",\n") { "        \"" + it + "\"" }
        val source = listOf(
            "package $group;",
            "",
            "import java.util.List;",
            "",
            "/** Generated from the version catalog at build time. Do not edit. */",
            "final class RuntimeLibraries {",
            "",
            "    /** Maven coordinates of the libraries Paper downloads before the plugin loads. */",
            "    static final List<String> COORDINATES = List.of(",
            entries + ");",
            "",
            "    private RuntimeLibraries() {",
            "    }",
            "}",
            "",
        ).joinToString("\n")
        val file = root.resolve(group.replace('.', '/') + "/RuntimeLibraries.java")
        file.parentFile.mkdirs()
        file.writeText(source)
    }

    private fun requireCoordinate(entry: String) {
        val segments = entry.split(':')
        val wellFormed = segments.size in 3..5 && segments.all { segment ->
            segment.isNotEmpty() && segment.none { it.isWhitespace() || it == '"' || it == '\'' || it == '\\' }
        }
        if (!wellFormed) {
            throw GradleException(
                "The paperPlugin.runtimeLibraries entry \"$entry\" is not group:name:version, with an " +
                    "extension or an extension and a classifier allowed before the version, in segments that are " +
                    "not blank and hold no whitespace, quote or backslash. A version catalog entry passed to " +
                    "runtimeLibrary needs a version."
            )
        }
    }
}
