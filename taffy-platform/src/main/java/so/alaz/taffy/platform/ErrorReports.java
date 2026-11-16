package so.alaz.taffy.platform;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Where a plugin reports an error it caught and handled.
 *
 * <p>An error handled inside the plugin never escapes to the server, so anything that watches for uncaught
 * errors does not see it, and it has to be reported here to be seen. The plugin creates one instance and
 * passes it to the classes that handle such errors (hooks, storage, {@link Shutdown}).
 *
 * <p>The sink has one target. Each {@link #install} replaces the target before it, which receives nothing
 * after that. To send each report to two places, install one target that passes it to both.
 *
 * <p>Until {@link #install} or {@link #clear} first runs, a report is held, the first one of each source and
 * at most 16 in all: a later report of a source already held, or of a new source once 16 are held, is
 * dropped. The first {@link #install} of a non-null target delivers the held reports to it, in the order they
 * were made, on the installing thread. When {@link #clear} or {@code install(null)} runs first, the held
 * reports are discarded. A plugin that never calls either keeps the held reports for its lifetime. Once
 * either has run, a report made while no target is installed is dropped.
 *
 * <p>Thread-safe: every method may be called from any thread.
 */
public final class ErrorReports {

    private static final int MAX_HELD = 16;

    private final Object lock = new Object();
    private final Map<String, Throwable> held = new LinkedHashMap<>();
    private volatile boolean settled;
    private volatile @Nullable BiConsumer<String, Throwable> target;

    /**
     * Routes later reports to {@code handle}, or nowhere when it is {@code null}. {@code handle} receives the
     * source and the error of each report, on the thread that reported it. When neither this method nor
     * {@link #clear} has run before, the held reports are delivered to a non-null {@code handle} on the
     * calling thread before this method returns, or discarded when {@code handle} is {@code null}.
     */
    public void install(@Nullable BiConsumer<String, Throwable> handle) {
        Map<String, Throwable> delivered;
        synchronized (lock) {
            target = handle;
            if (settled) {
                return;
            }
            settled = true;
            delivered = handle == null ? Map.of() : new LinkedHashMap<>(held);
            held.clear();
        }
        if (handle != null) {
            delivered.forEach((source, error) -> deliver(handle, source, error));
        }
    }

    /**
     * Stops reporting: later reports are dropped until {@link #install} runs again. Reports held because
     * neither method has run yet are discarded.
     */
    public void clear() {
        synchronized (lock) {
            target = null;
            settled = true;
            held.clear();
        }
    }

    /**
     * Reports {@code error}, identified by a short stable {@code source} key such as {@code shutdown}. Holds
     * it when neither {@link #install} nor {@link #clear} has run yet, as the class description states, and
     * does nothing when no target is installed after that. Never throws: anything the target throws, an
     * {@link Error} included, is discarded.
     */
    public void report(String source, Throwable error) {
        BiConsumer<String, Throwable> handle = target;
        if (handle == null) {
            if (!settled) {
                synchronized (lock) {
                    if (!settled) {
                        if (held.size() < MAX_HELD) {
                            held.putIfAbsent(source, error);
                        }
                        return;
                    }
                }
            }
            // target is written before settled, so a read after settled is true sees the first install.
            handle = target;
            if (handle == null) {
                return;
            }
        }
        deliver(handle, source, error);
    }

    private static void deliver(BiConsumer<String, Throwable> handle, String source, Throwable error) {
        try {
            handle.accept(source, error);
        } catch (Throwable ignored) {
            // Reporting an error must never raise one.
        }
    }
}
