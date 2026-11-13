package so.alaz.taffy.gradle

import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Provider

/** Settings of the `so.alaz.paper-plugin` convention, exposed as `paperPlugin`. */
abstract class PaperPluginExtension {

    /**
     * Maven coordinates Paper downloads before the plugin loads, in resolution order.
     * A non-empty list generates `RuntimeLibraries.COORDINATES` in the package named by the project group.
     */
    abstract val runtimeLibraries: ListProperty<String>

    /** Adds a version catalog entry to [runtimeLibraries]. */
    fun runtimeLibrary(library: Provider<MinimalExternalModuleDependency>) {
        runtimeLibraries.add(
            library.map { dependency ->
                dependency.module.group + ":" + dependency.module.name + ":" +
                    dependency.versionConstraint.requiredVersion
            },
        )
    }
}
