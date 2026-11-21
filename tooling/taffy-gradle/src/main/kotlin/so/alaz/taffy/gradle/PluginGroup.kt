package so.alaz.taffy.gradle

import javax.lang.model.SourceVersion
import org.gradle.api.GradleException

/** Checks of the project group `so.alaz.taffy.paper-plugin` relocates under and generates `RuntimeLibraries` in. */
internal object PluginGroup {

    /**
     * Returns [group] when it has at least one segment and none of its dot-separated segments is blank.
     *
     * @throws GradleException for any other group, with a message that names `group` in `gradle.properties`
     */
    fun requireSegments(group: String): String {
        if (group.split('.').any { it.isBlank() }) {
            throw GradleException(
                "so.alaz.taffy.paper-plugin relocates Taffy under <group>.libs, and the project group \"$group\" " +
                    "is blank or has an empty segment. Set group in gradle.properties, for example " +
                    "group=com.example.myplugin."
            )
        }
        return group
    }

    /**
     * Returns [group] when it is a Java package name: dot-separated identifiers, none of them a keyword.
     *
     * @throws GradleException for any other group, with a message that names `group` in `gradle.properties`
     */
    fun requireJavaPackage(group: String): String {
        if (!SourceVersion.isName(group)) {
            throw GradleException(
                "paperPlugin.runtimeLibraries generates RuntimeLibraries in the package named by the project " +
                    "group, and \"$group\" is not a Java package name. Set group in gradle.properties to one, " +
                    "for example group=com.example.myplugin."
            )
        }
        return group
    }
}
