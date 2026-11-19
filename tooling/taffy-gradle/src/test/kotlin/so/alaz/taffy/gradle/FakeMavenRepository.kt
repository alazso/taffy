package so.alaz.taffy.gradle

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A Maven repository served over HTTP from memory at [url]. It answers GET and HEAD with 200 for a stored file and
 * 404 otherwise, stores every PUT, and records the path of each upload. A GET is answered with a status queued by
 * [failNextGets] while one is queued.
 */
class FakeMavenRepository : AutoCloseable {

    private val files = ConcurrentHashMap<String, ByteArray>()

    private val uploads = CopyOnWriteArrayList<String>()

    private val getFailures = ConcurrentLinkedQueue<Int>()

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/releases/") { exchange -> respond(exchange) }
        start()
    }

    val url: String = "http://127.0.0.1:${server.address.port}/releases"

    /** The paths, relative to [url], of the files uploaded since the last [clearUploads], in upload order. */
    fun uploaded(): List<String> = uploads.toList()

    /** The paths of [uploaded] without the checksum files. */
    fun uploadedFiles(): List<String> = uploaded().filterNot { path -> CHECKSUMS.any { path.endsWith(it) } }

    fun clearUploads() = uploads.clear()

    /** Stores [content] at [path], relative to [url], as if an earlier publish had uploaded it. */
    fun put(path: String, content: String = "") {
        files[path] = content.toByteArray()
    }

    /** Stores at [path].sha512 the SHA-512 of the file stored at [path], as Gradle uploads it after that file. */
    fun putSha512(path: String) {
        val digest = MessageDigest.getInstance("SHA-512").digest(files.getValue(path))
        files["$path.sha512"] = HexFormat.of().formatHex(digest).toByteArray()
    }

    /**
     * Stores the POM of [artifactId] [version], a maven-metadata.xml listing [version] and its .sha512, as a complete
     * publish of [version] to an empty repository leaves them.
     */
    fun putRelease(groupPath: String, artifactId: String, version: String) {
        putPom(groupPath, artifactId, version)
        putMetadata(groupPath, artifactId, version)
        putSha512("$groupPath/$artifactId/maven-metadata.xml")
    }

    /** Answers each of the next [count] GET requests with [status], whatever the path. */
    fun failNextGets(status: Int, count: Int = 1) = repeat(count) { getFailures.add(status) }

    /** Stores the POM of [artifactId] [version] in the group whose path is [groupPath]. */
    fun putPom(groupPath: String, artifactId: String, version: String) =
        put("$groupPath/$artifactId/$version/$artifactId-$version.pom", "<project/>\n")

    /** Stores the artifact-level maven-metadata.xml of [artifactId], listing [versions]. */
    fun putMetadata(groupPath: String, artifactId: String, vararg versions: String) = put(
        "$groupPath/$artifactId/maven-metadata.xml",
        listOf(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
            "<metadata>",
            "  <groupId>${groupPath.replace('/', '.')}</groupId>",
            "  <artifactId>$artifactId</artifactId>",
            "  <versioning>",
            "    <latest>${versions.last()}</latest>",
            "    <release>${versions.last()}</release>",
            "    <versions>",
            *versions.map { "      <version>$it</version>" }.toTypedArray(),
            "    </versions>",
            "    <lastUpdated>20260101000000</lastUpdated>",
            "  </versioning>",
            "</metadata>",
            "",
        ).joinToString("\n"),
    )

    private fun respond(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path.removePrefix("/releases/")
            when (exchange.requestMethod) {
                "PUT" -> {
                    files[path] = exchange.requestBody.readBytes()
                    uploads.add(path)
                    exchange.sendResponseHeaders(201, -1)
                }
                "GET", "HEAD" -> {
                    val content = files[path]
                    val failure = if (exchange.requestMethod == "GET") getFailures.poll() else null
                    when {
                        failure != null -> exchange.sendResponseHeaders(failure, -1)
                        content == null -> exchange.sendResponseHeaders(404, -1)
                        exchange.requestMethod == "HEAD" || content.isEmpty() -> exchange.sendResponseHeaders(200, -1)
                        else -> {
                            exchange.sendResponseHeaders(200, content.size.toLong())
                            exchange.responseBody.write(content)
                        }
                    }
                }
                else -> exchange.sendResponseHeaders(405, -1)
            }
        } finally {
            exchange.close()
        }
    }

    override fun close() = server.stop(0)

    private companion object {
        val CHECKSUMS = listOf(".md5", ".sha1", ".sha256", ".sha512")
    }
}
