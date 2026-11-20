package so.alaz.taffy.platform;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Where a plugin reports an error that nothing else watching the plugin's errors sees.
 *
 * <p>An error handled inside the plugin never escapes to the server, so anything that watches for uncaught
 * errors does not see it, and it has to be reported here to be seen. The same holds for a task Paper's region,
 * async or entity scheduler runs that throws: Paper logs it and fires no {@code ServerExceptionEvent}, so
 * {@link Scheduler} reports it here before rethrowing it. The plugin creates one instance and passes it to the
 * classes that report such errors (hooks, storage, {@link Scheduler}, {@link Shutdown}).
 *
 * <p>A report is either handled or unhandled. {@link #report} makes a handled one, for an error the caller
 * caught and dealt with. {@link #reportUnhandled} makes an unhandled one, for an error the caller rethrows or
 * lets escape, as {@link Scheduler} does. A target installed with {@link #install(BiConsumer)} receives both and
 * cannot tell them apart; {@link #install(BiConsumer, BiConsumer)} installs one handler for each.
 *
 * <p>The sink has one target. Each {@link #install} replaces the target before it, which receives nothing
 * after that. To send each report to two places, install one target that passes it to both.
 *
 * <p>Until {@link #install} or {@link #clear} first runs, a report is held, the first one of each source and
 * at most 16 in all: a later report of a source already held, or of a new source once 16 are held, is
 * dropped. The first {@link #install} of a non-null target delivers the held reports to it, in the order they
 * were made, on the installing thread, each to the handler that takes it. When {@link #clear} or
 * {@code install(null)} runs first, the held reports are discarded. A plugin that never calls either keeps the
 * held reports for its lifetime. Once either has run, a report made while no target is installed is dropped.
 *
 * <p>Thread-safe: every method may be called from any thread.
 */
public final class ErrorReports {

    private static final int MAX_HELD = 16;

    private record Held(Throwable error, boolean handled) {
    }

    private record Target(BiConsumer<String, Throwable> handled, BiConsumer<String, Throwable> unhandled) {

        BiConsumer<String, Throwable> of(boolean handledReport) {
            return handledReport ? handled : unhandled;
        }
    }

    private final Object lock = new Object();
    private final Map<String, Held> held = new LinkedHashMap<>();
    private volatile boolean settled;
    private volatile @Nullable Target target;

    /**
     * Routes later reports, handled and unhandled alike, to {@code handle}, or nowhere when it is {@code null}.
     * {@code handle} receives the source and the error of each report, on the thread that reported it. When
     * neither an install nor {@link #clear} has run before, the held reports are delivered to a non-null
     * {@code handle} on the calling thread before this method returns, or discarded when {@code handle} is
     * {@code null}.
     */
    public void install(@Nullable BiConsumer<String, Throwable> handle) {
        settle(handle == null ? null : new Target(handle, handle));
    }

    /**
     * Routes later reports made through {@link #report} to {@code handle} and those made through
     * {@link #reportUnhandled} to {@code unhandled}, replacing the target before it. Each receives the source and
     * the error of a report, on the thread that reported it. When neither an install nor {@link #clear} has run
     * before, the held reports are delivered on the calling thread before this method returns, each to the one
     * of the two that takes it.
     *
     * @throws NullPointerException if {@code handle} or {@code unhandled} is {@code null}
     */
    public void install(BiConsumer<String, Throwable> handle, BiConsumer<String, Throwable> unhandled) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(unhandled, "unhandled");
        settle(new Target(handle, unhandled));
    }

    private void settle(@Nullable Target installed) {
        Map<String, Held> delivered;
        synchronized (lock) {
            target = installed;
            if (settled) {
                return;
            }
            settled = true;
            delivered = installed == null ? Map.of() : new LinkedHashMap<>(held);
            held.clear();
        }
        if (installed != null) {
            delivered.forEach((source, report) ->
                deliver(installed.of(report.handled()), source, report.error()));
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
     * Reports {@code error} as handled, identified by a short stable {@code source} key such as
     * {@code shutdown}. Holds it when neither {@link #install} nor {@link #clear} has run yet, as the class
     * description states, and does nothing when no target is installed after that. Never throws: anything the
     * target throws, an {@link Error} included, is discarded.
     *
     * <p>A target may send the source key off the server as it is: {@code taffy-metrics} sends it to FastStats
     * as the {@code source} attribute of the error, and no rule hides anything in it. The key is therefore a
     * constant of the plugin or a category from its configuration, and never holds a player name, a UUID, an
     * address, a world name or a server name.
     */
    public void report(String source, Throwable error) {
        report(source, error, true);
    }

    /**
     * Reports {@code error} as unhandled: an error the caller does not deal with and rethrows or lets escape
     * once this method returns. Holds, drops and delivers it as {@link #report} does, and the rule
     * {@link #report} gives for the source key applies. It reaches the {@code unhandled} handler of
     * {@link #install(BiConsumer, BiConsumer)}, or the one handler of {@link #install(BiConsumer)}. Never
     * throws.
     */
    public void reportUnhandled(String source, Throwable error) {
        report(source, error, false);
    }

    private void report(String source, Throwable error, boolean handled) {
        Target installed = target;
        if (installed == null) {
            if (!settled) {
                synchronized (lock) {
                    if (!settled) {
                        if (held.size() < MAX_HELD) {
                            held.putIfAbsent(source, new Held(error, handled));
                        }
                        return;
                    }
                }
            }
            // target is written before settled, so a read after settled is true sees the first install.
            installed = target;
            if (installed == null) {
                return;
            }
        }
        deliver(installed.of(handled), source, error);
    }

    private static void deliver(BiConsumer<String, Throwable> handle, String source, Throwable error) {
        try {
            handle.accept(source, error);
        } catch (Throwable ignored) {
            // Reporting an error must never raise one.
        }
    }
}
