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
 * Writes the `RuntimeLibraries` class a plugin's `PluginLoader` reads its coordinates from and, while
 * [generateLoader] is true, a package-private `RuntimeLibrariesLoader` beside it: a Paper `PluginLoader` that
 * hands each coordinate to a `MavenLibraryResolver` over `MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR`. With
 * no [coordinates] listed neither class is written.
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

    /** False unless set. */
    @get:Input
    abstract val generateLoader: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    init {
        generateLoader.convention(false)
    }

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
        val directory = root.resolve(group.replace('.', '/'))
        directory.mkdirs()
        directory.resolve("RuntimeLibraries.java").writeText(source)
        if (generateLoader.get()) {
            directory.resolve("$LOADER_NAME.java").writeText(loaderSource(group))
        }
    }

    private fun loaderSource(group: String): String = listOf(
        "package $group;",
        "",
        "import io.papermc.paper.plugin.loader.PluginClasspathBuilder;",
        "import io.papermc.paper.plugin.loader.PluginLoader;",
        "import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;",
        "import org.eclipse.aether.artifact.DefaultArtifact;",
        "import org.eclipse.aether.graph.Dependency;",
        "import org.eclipse.aether.repository.RemoteRepository;",
        "",
        "/** Generated at build time. Do not edit. */",
        "final class $LOADER_NAME implements PluginLoader {",
        "",
        "    /** Has Paper download {@link RuntimeLibraries#COORDINATES} from its Maven Central mirror. */",
        "    @Override",
        "    public void classloader(PluginClasspathBuilder classpathBuilder) {",
        "        MavenLibraryResolver resolver = new MavenLibraryResolver();",
        "        resolver.addRepository(new RemoteRepository.Builder(",
        "            \"central\", \"default\", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());",
        "        for (String coordinates : RuntimeLibraries.COORDINATES) {",
        "            resolver.addDependency(new Dependency(new DefaultArtifact(coordinates), null));",
        "        }",
        "        classpathBuilder.addLibrary(resolver);",
        "    }",
        "}",
        "",
    ).joinToString("\n")

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

    internal companion object {
        /** Simple name of the generated loader class. */
        const val LOADER_NAME = "RuntimeLibrariesLoader"
    }
}
