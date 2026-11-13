package so.alaz.taffy.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/** Writes the `RuntimeLibraries` class a plugin's `PluginLoader` reads its coordinates from. */
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
        val root = outputDirectory.get().asFile
        root.deleteRecursively()
        root.mkdirs()
        if (coordinates.get().isEmpty()) {
            return
        }
        val entries = coordinates.get().joinToString(",\n") { "        \"" + it + "\"" }
        val source = listOf(
            "package " + packageName.get() + ";",
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
        val file = root.resolve(packageName.get().replace('.', '/') + "/RuntimeLibraries.java")
        file.parentFile.mkdirs()
        file.writeText(source)
    }
}
