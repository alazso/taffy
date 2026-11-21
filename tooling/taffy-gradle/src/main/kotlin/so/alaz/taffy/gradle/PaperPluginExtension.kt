package so.alaz.taffy.gradle

import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.provider.ListProperty
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
