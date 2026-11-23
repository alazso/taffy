package so.alaz.taffy.platform;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.MalformedJsonException;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * Asks once, at enable, whether a newer release of the plugin is published, and tells the server owner in the
 * log.
 *
 * <p>{@link #start} sends one {@code GET} to the {@link UpdateSource} on the executor the plugin passes, such as
 * {@code scheduler::async}, and returns at once. The check owns no thread, downloads no file and sends no message
 * to a player: {@link #latest()} holds the newer version for a notice the plugin sends itself, to the players it
 * chooses.
 *
 * <p>The running version is {@code plugin.getPluginMeta().getVersion()}. Versions are read as Semantic
 * Versioning 2.0.0, with a leading {@code v} and a missing patch number allowed and build metadata dropped:
 * <ul>
 *   <li>a running version without a pre-release part, such as {@code 1.4.0}, is compared with the published
 *       releases only. What the source marks as a pre-release is not offered to it;</li>
 *   <li>a running version with a pre-release part, such as {@code 1.5.0-rc.1} or {@code 1.5.0-SNAPSHOT}, is
 *       compared with the published pre-releases as well. It is below {@code 1.5.0} and above {@code 1.4.9};</li>
 *   <li>a running version of another form, such as {@code build-17}, cannot be compared: the check logs one
 *       warning and sends nothing;</li>
 *   <li>a published version of another form is skipped.</li>
 * </ul>
 * The newest published version that is left is the one compared. When it is above the running version, the check
 * logs one line at {@code INFO} on the plugin's logger,
 * {@code A newer release is available: <newest> (running <running>). <releases page>}, and {@link #latest()}
 * returns it.
 *
 * <p>The request goes over HTTPS to {@code api.github.com} or {@code api.modrinth.com} and follows no redirect.
 * Its headers are {@code Host}, {@code Connection}, {@code Cache-Control: no-cache}, {@code Pragma: no-cache},
 * {@code Accept}, for GitHub {@code X-GitHub-Api-Version}, and a {@code User-Agent} of the plugin's name and
 * version, as {@code Shop/1.4.0}. It holds no player data and nothing about the server.
 * Connecting and each read wait at most 10 seconds and the whole answer at most 30. At most 262144 bytes of the
 * answer are read, and of them the first 100 entries: what is complete within that is compared, since both
 * sources answer newest first.
 *
 * <p>Nothing here throws into enable for a failed check. An answer other than status 200, a failed or timed out
 * connection, an answer that is not a list of releases, an executor that refuses the task and an executor that
 * runs it on a server thread each log one warning, and {@link #latest()} stays null. An exception of another kind
 * is logged as one warning and reported to the {@link ErrorReports} under {@code update-check}.
 *
 * <p>The request can outlast the plugin's disable by its timeouts when the executor does not wait for it. It
 * touches only the plugin's logger and this object after it was sent.
 *
 * <p>Thread-safe.
 */
public final class UpdateCheck {

    /** The limits of one request. */
    record Limits(int connectMillis, int readMillis, long totalMillis, int maxBytes, int maxEntries) {

        static final Limits DEFAULT = new Limits(10_000, 10_000, 30_000, 262_144, 100);
    }

    /** An answer the check cannot use, with the words for the log. */
    private static final class Unusable extends IOException {

        private static final long serialVersionUID = 1L;

        Unusable(String message) {
            super(message);
        }
    }

    private static final String SOURCE = "update-check";

    private volatile @Nullable String latest;

    private UpdateCheck() {
    }

    /**
     * Starts the check, or returns a check that does nothing when {@code enabled} is false. The plugin passes its
     * own config switch as {@code enabled}, so a server owner can turn the check off.
     *
     * @param plugin the plugin whose version is compared and whose logger takes the lines
     * @param errors where an unexpected exception of the check is reported
     * @param enabled false to send nothing and log nothing
     * @param source where the releases are published
     * @param executor runs the request off the server threads. It runs one task, which waits on the network.
     * @return the check, whose {@link #latest()} is null until the answer is read
     * @throws NullPointerException if an argument is null
     */
    public static UpdateCheck start(Plugin plugin, ErrorReports errors, boolean enabled, UpdateSource source,
                                    Executor executor) {
        return start(plugin, errors, enabled, source, executor, Limits.DEFAULT);
    }

    static UpdateCheck start(Plugin plugin, ErrorReports errors, boolean enabled, UpdateSource source,
                             Executor executor, Limits limits) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(errors, "errors");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(executor, "executor");
        UpdateCheck check = new UpdateCheck();
        if (!enabled) {
            return check;
        }
        Logger logger = plugin.getLogger();
        String running = plugin.getPluginMeta().getVersion();
        SemanticVersion current = SemanticVersion.parse(running);
        if (current == null) {
            logger.warning("Update check skipped: the plugin's version " + shown(running)
                + " is not a semantic version such as 1.4.0");
            return check;
        }
        String agent = token(plugin.getName()) + "/" + token(running.strip());
        Runnable task = () -> check.run(plugin, errors, source, current, agent, limits);
        try {
            executor.execute(task);
        } catch (RuntimeException ex) {
            logger.warning("Update check skipped: the executor refused it (" + ex.getClass().getName() + ")");
        }
        return check;
    }

    /**
     * The newest published version when it is above the running one, as {@code major.minor.patch} with
     * {@code -} and the pre-release identifiers when it has any, such as {@code 1.5.0} or {@code 1.5.0-rc.1}. It
     * holds only ASCII letters, digits, {@code .} and {@code -}, at most 64 characters.
     *
     * @return the newer version, or null while the check is switched off, before the answer is read, after a
     *     failed check and when no newer version is published
     */
    public @Nullable String latest() {
        return latest;
    }

    private void run(Plugin plugin, ErrorReports errors, UpdateSource source, SemanticVersion current,
                     String agent, Limits limits) {
        Logger logger = plugin.getLogger();
        String host = source.endpoint().getHost();
        try {
            if (plugin.getServer().isPrimaryThread()) {
                logger.warning("Update check skipped: the executor ran it on a server thread, and the request"
                    + " waits on the network");
                return;
            }
            SemanticVersion newest = newest(fetch(source, agent, limits), current);
            if (newest != null && newest.compareTo(current) > 0) {
                latest = newest.toString();
                logger.info("A newer release is available: " + newest + " (running " + current + "). "
                    + source.page());
            }
        } catch (Unusable ex) {
            logger.warning("Update check failed: " + ex.getMessage());
        } catch (IOException ex) {
            logger.warning("Update check failed: could not read from " + host + " (" + ex.getClass().getName()
                + ")");
        } catch (RuntimeException ex) {
            logger.warning("Update check failed: " + ex.getClass().getName());
            errors.report(SOURCE, ex);
        }
    }

    /** The highest version among the releases the running version is compared with, or null when there is none. */
    private static @Nullable SemanticVersion newest(List<UpdateSource.Release> releases, SemanticVersion current) {
        SemanticVersion newest = null;
        for (UpdateSource.Release release : releases) {
            if (release.preRelease() && !current.isPreRelease()) {
                continue;
            }
            SemanticVersion version = SemanticVersion.parse(release.version());
            if (version != null && (newest == null || version.compareTo(newest) > 0)) {
                newest = version;
            }
        }
        return newest;
    }

    private static List<UpdateSource.Release> fetch(UpdateSource source, String agent, Limits limits)
        throws IOException {
        URI endpoint = source.endpoint();
        long deadline = System.nanoTime() + limits.totalMillis() * 1_000_000L;
        HttpURLConnection connection = (HttpURLConnection) endpoint.toURL().openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setAllowUserInteraction(false);
            connection.setConnectTimeout(limits.connectMillis());
            connection.setReadTimeout(limits.readMillis());
            connection.setRequestProperty("User-Agent", agent);
            for (Map.Entry<String, String> header : source.headers().entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new Unusable(endpoint.getHost() + " answered with status " + status);
            }
            Bounded body = new Bounded(connection.getInputStream(), limits.maxBytes(), deadline);
            List<UpdateSource.Release> releases = new ArrayList<>();
            try (JsonReader reader = new JsonReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                reader.beginArray();
                for (int entries = 0; entries < limits.maxEntries() && reader.hasNext(); entries++) {
                    UpdateSource.Release release = source.release(reader);
                    if (release != null) {
                        releases.add(release);
                    }
                }
            } catch (MalformedJsonException | EOFException | IllegalStateException | NumberFormatException ex) {
                if (!body.cut || releases.isEmpty()) {
                    throw new Unusable("the answer of " + endpoint.getHost() + " is not a list of releases");
                }
            }
            return releases;
        } finally {
            connection.disconnect();
        }
    }

    /** At most 64 characters of a text for a log line, without control characters. */
    private static String shown(String text) {
        String cut = text.length() > 64 ? text.substring(0, 64) : text;
        return cut.replaceAll("\\p{Cntrl}", "?");
    }

    /** A text as one {@code User-Agent} product token of at most 64 characters. */
    private static String token(String text) {
        String cut = text.length() > 64 ? text.substring(0, 64) : text;
        String token = cut.replaceAll("[^A-Za-z0-9._+-]", "_");
        return token.isEmpty() ? "_" : token;
    }

    /** Ends the stream after a number of bytes and fails a read that starts after the deadline. */
    private static final class Bounded extends InputStream {

        private final InputStream in;
        private final long deadline;
        private int left;
        private boolean cut;

        Bounded(InputStream in, int limit, long deadline) {
            this.in = in;
            this.left = limit;
            this.deadline = deadline;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (System.nanoTime() - deadline > 0) {
                throw new SocketTimeoutException("answer not read in time");
            }
            if (length == 0) {
                return 0;
            }
            if (left == 0) {
                cut = true;
                return -1;
            }
            int count = in.read(buffer, offset, Math.min(length, left));
            if (count > 0) {
                left -= count;
            }
            return count;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
