package so.alaz.taffy.gradle

import org.gradle.api.GradleException
import org.gradle.util.GradleVersion

/** The oldest Gradle release the Taffy Gradle plugins build a plugin on. */
internal object MinimumGradle {

    const val VERSION: String = "9.2.0"

    /**
     * Fails when [current] is older than [VERSION], with a message that names [plugin], both versions and the
     * wrapper property that sets the Gradle version. A release candidate or milestone of [VERSION] passes.
     */
    fun require(plugin: String, current: GradleVersion = GradleVersion.current()) {
        if (current.baseVersion < GradleVersion.version(VERSION)) {
            throw GradleException(
                "$plugin needs Gradle $VERSION or newer, and this build runs on Gradle ${current.version}. " +
                    "Set a newer version in distributionUrl in gradle/wrapper/gradle-wrapper.properties."
            )
        }
    }
}
