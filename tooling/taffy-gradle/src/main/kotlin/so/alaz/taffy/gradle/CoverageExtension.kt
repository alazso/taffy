package so.alaz.taffy.gradle

import java.math.BigDecimal
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/** Settings of the `so.alaz.coverage` convention, exposed as `coverage`. */
abstract class CoverageExtension {

    /** Minimum instruction coverage the `check` task requires. Defaults to 0.80. */
    abstract val minimum: Property<BigDecimal>

    /** Ant patterns of class files left out of the coverage gate, relative to the classes directory. */
    abstract val exclusions: ListProperty<String>

    /** Patterns contributed by other conventions, applied in addition to [exclusions]. */
    abstract val conventionExclusions: ListProperty<String>
}
