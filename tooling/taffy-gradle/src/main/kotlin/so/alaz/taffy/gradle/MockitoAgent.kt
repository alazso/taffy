package so.alaz.taffy.gradle

import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.process.CommandLineArgumentProvider

/**
 * Passes each Mockito core jar of a test [classpath] to the test JVM as a Java agent. A classpath without one adds no
 * argument.
 */
internal class MockitoAgent(@get:Classpath val classpath: FileCollection) : CommandLineArgumentProvider {

    override fun asArguments(): Iterable<String> = classpath.files
        .filter { it.isFile && it.name.startsWith("mockito-core-") && it.name.endsWith(".jar") }
        .map { "-javaagent:" + it.absolutePath }
}
