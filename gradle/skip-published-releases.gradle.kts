import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.HexFormat

// Skips a release publish to the repository named alazso when that repository already has the module complete:
// the artifact-level maven-metadata.xml lists the version, maven-metadata.xml.sha512 is the SHA-512 of that
// maven-metadata.xml, and the version's POM answers 200. Gradle uploads the main artifact, the POM and the other
// artifacts, each followed by its checksums, then maven-metadata.xml followed by its .sha1, .md5, .sha256 and
// .sha512, so maven-metadata.xml.sha512 is the last file of a publish and a module interrupted part way is not
// skipped. Any other answer, or a failure to reach the repository, runs the task. A -SNAPSHOT version is never
// skipped. Taffy's root and tooling builds apply this script; no convention plugin applies it.
tasks.withType<PublishToMavenRepository>().configureEach {
    // The task does not keep its publication and repository in the configuration cache; the cache stores the
    // value of this provider instead, so the spec below reads only that value.
    val release: Provider<List<String>> = providers.provider {
        val coordinates = publication
        if (repository.name != "alazso" || coordinates.version.endsWith("-SNAPSHOT")) {
            emptyList()
        } else {
            val version = coordinates.version
            val module = repository.url.toString().trimEnd('/') + "/" +
                coordinates.groupId.replace('.', '/') + "/" + coordinates.artifactId
            listOf(version, "$module/maven-metadata.xml", "$module/$version/${coordinates.artifactId}-$version.pom")
        }
    }
    onlyIf("the alazso repository does not already have this release complete") { task ->
        val (version, metadata, pom) = release.get().takeIf { it.isNotEmpty() } ?: return@onlyIf true

        // The body for GET, or an empty array for HEAD, when the answer is 200; null for any other answer.
        fun fetch(url: String, method: String): ByteArray? {
            val connection = URI(url).toURL().openConnection() as? HttpURLConnection ?: return null
            try {
                connection.requestMethod = method
                connection.useCaches = false
                connection.setRequestProperty("Cache-Control", "no-cache")
                connection.connectTimeout = 10_000
                connection.readTimeout = 30_000
                if (connection.responseCode != 200) return null
                return if (method == "HEAD") ByteArray(0) else connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }

        val listed = Regex("<version>\\s*" + Regex.escape(version) + "\\s*</version>")
        val complete = try {
            val body = fetch(metadata, "GET")
            body != null &&
                listed.containsMatchIn(body.toString(Charsets.UTF_8)) &&
                fetch("$metadata.sha512", "GET")?.toString(Charsets.UTF_8)?.trim()?.lowercase() ==
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(body)) &&
                fetch(pom, "HEAD") != null
        } catch (failure: IOException) {
            task.logger.warn(
                "Could not check {} in the alazso repository, so {} runs: {}", pom, task.path, failure.toString(),
            )
            false
        }
        if (complete) {
            task.logger.lifecycle("{} is already complete in the alazso repository; {} is skipped.", pom, task.path)
        }
        !complete
    }
}
