package so.alaz.taffy.gradle

import java.math.BigDecimal
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

/** Settings of the `so.alaz.paper-plugin` convention, exposed as `paperPlugin`. */
abstract class PaperPluginExtension {

    /** Minimum instruction coverage the `check` task requires. Defaults to 0.80. */
    abstract val coverageMinimum: Property<BigDecimal>

    /** Ant patterns of class files left out of the coverage gate, relative to the classes directory. */
    abstract val coverageExclusions: ListProperty<String>

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
