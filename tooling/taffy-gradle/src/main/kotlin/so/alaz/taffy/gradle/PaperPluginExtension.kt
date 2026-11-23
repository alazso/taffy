package so.alaz.taffy.gradle

import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

/** Settings of the `so.alaz.taffy.paper-plugin` convention, exposed as `paperPlugin`. */
abstract class PaperPluginExtension {

    /**
     * Maven coordinates Paper downloads before the plugin loads, in resolution order, each as `group:name:version`.
     * A non-empty list generates `RuntimeLibraries.COORDINATES` in the package named by the project group, which
     * must then be a Java package name, and makes the build fail when `paper-plugin.yml` has no top-level `loader`
     * key. `generateRuntimeLibraries` fails on an entry of any other form.
     */
    abstract val runtimeLibraries: ListProperty<String>

    /**
     * Whether a non-empty [runtimeLibraries] also generates `RuntimeLibrariesLoader` in the package named by the
     * project group: a package-private Paper `PluginLoader` that has Paper download `RuntimeLibraries.COORDINATES`
     * from `MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR`. False unless set. The plugin names the class in the
     * `loader` key of its `paper-plugin.yml` and has paper-api on its compile classpath. While true, a class file
     * named `RuntimeLibrariesLoader.class` is outside the coverage gate, and with an empty [runtimeLibraries] the
     * build fails when the `loader` key names the class, which is then not generated.
     */
    abstract val generateLoader: Property<Boolean>

    init {
        generateLoader.convention(false)
    }

    /**
     * Adds a version catalog entry to [runtimeLibraries] as `group:name:version`, with the version the catalog
     * requires. An entry the catalog gives no version fails `generateRuntimeLibraries`.
     */
    fun runtimeLibrary(library: Provider<MinimalExternalModuleDependency>) {
        runtimeLibraries.add(
            library.map { dependency ->
                dependency.module.group + ":" + dependency.module.name + ":" +
                    dependency.versionConstraint.requiredVersion
            },
        )
    }
}
