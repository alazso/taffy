package so.alaz.taffy.platform;

import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;

/**
 * Where a plugin reports an error it caught and handled.
 *
 * <p>An error handled inside the plugin never escapes to the server, so anything that watches for uncaught
 * errors does not see it, and it has to be reported here to be seen. The plugin creates one instance and
 * passes it to the classes that handle such errors (hooks, storage, {@link Shutdown}). The instance is inert
 * until {@link #install} runs: a report made while no target is installed is dropped.
 *
 * <p>Thread-safe: every method may be called from any thread.
 */
public final class ErrorReports {

    private volatile @Nullable BiConsumer<String, Throwable> target;

    /**
     * Routes later reports to {@code handle}, or nowhere when it is {@code null}. {@code handle} receives the
     * source and the error of each report, on the thread that reported it.
     */
    public void install(@Nullable BiConsumer<String, Throwable> handle) {
        target = handle;
    }

    /** Stops reporting: later reports are dropped until {@link #install} runs again. */
    public void clear() {
        target = null;
    }

    /**
     * Reports {@code error}, identified by a short stable {@code source} key such as {@code shutdown}. Does
     * nothing when no target is installed. Never throws: anything the target throws, an {@link Error}
     * included, is discarded.
     */
    public void report(String source, Throwable error) {
        BiConsumer<String, Throwable> handle = target;
        if (handle == null) {
            return;
        }
        try {
            handle.accept(source, error);
        } catch (Throwable ignored) {
            // Reporting an error must never raise one.
        }
    }
}
