package so.alaz.taffy.gradle

import java.util.Locale
import java.util.zip.ZipFile
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCopyDetails
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Copies the license files of each jar of [jars] to `META-INF/licenses/<jar file name without .jar>/` under
 * [outputDirectory]. A license file is a file directly in `META-INF` whose name starts with `LICENSE` in any case.
 *
 * A jar whose file name, compared without case, was already used by an earlier jar of [jars] gets the suffix `-2`,
 * `-3` and so on, and so does a license file whose name, compared without case, repeats within one jar. A jar
 * without a license file takes no directory name.
 */
@CacheableTask
abstract class CollectDependencyLicenses : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun collect() {
        val root = outputDirectory.get().asFile
        root.deleteRecursively()
        root.mkdirs()
        val directories = mutableSetOf<String>()
        for (jar in jars.files.filter { it.isFile }) {
            ZipFile(jar).use { zip ->
                val licenses = zip.entries().asSequence()
                    .filter { !it.isDirectory && isLicense(it.name.split('/')) }
                    .toList()
                if (licenses.isNotEmpty()) {
                    val directory = root.resolve("META-INF/licenses/" + unused(directories, jar.nameWithoutExtension))
                    directory.mkdirs()
                    val files = mutableSetOf<String>()
                    for (entry in licenses) {
                        val target = directory.resolve(unused(files, entry.name.substringAfterLast('/')))
                        zip.getInputStream(entry).use { input -> target.outputStream().use(input::copyTo) }
                    }
                }
            }
        }
    }

    /**
     * Excludes a license file at its own path in `META-INF` when it lies under [expandedArchives], the directory
     * Gradle expands each shaded jar into, which drops the copies Shadow takes from the dependency jars.
     */
    internal class DropShadedCopies(private val expandedArchives: Provider<Directory>) : Action<FileCopyDetails> {

        override fun execute(details: FileCopyDetails) {
            if (isLicense(details.relativePath.segments.asList()) &&
                details.file.startsWith(expandedArchives.get().asFile)
            ) {
                details.exclude()
            }
        }
    }

    internal companion object {

        fun isLicense(segments: List<String>): Boolean = segments.size == 2 &&
            segments[0].equals("META-INF", ignoreCase = true) && segments[1].startsWith("LICENSE", ignoreCase = true)

        /**
         * [name], or [name] with the first of the suffixes `-2`, `-3` and so on that gives a name not in [taken],
         * compared without case. The result is added to [taken].
         */
        fun unused(taken: MutableSet<String>, name: String): String {
            var candidate = name
            var next = 2
            while (!taken.add(candidate.lowercase(Locale.ROOT))) {
                candidate = "$name-$next"
                next++
            }
            return candidate
        }
    }
}
