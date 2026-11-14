package so.alaz.taffy.platform;

import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Warnings about a third party that keeps failing, keyed by its source: the first carries the stack trace,
 * later ones are at most one line per source per {@link #WINDOW}, naming how many were left out.
 *
 * <p>Every distinct source is kept for as long as the instance is.
 *
 * <p>Thread-safe: {@link #warn} may be called from any thread.
 */
public final class ThrowLog {

    /** The shortest time between two logged warnings for one source. */
    public static final Duration WINDOW = Duration.ofMinutes(1);

    private record Entry(long loggedAt, int skipped) {
    }

    private final Logger logger;
    private final LongSupplier nanoTime;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public ThrowLog(Logger logger) {
        this(logger, System::nanoTime);
    }

    ThrowLog(Logger logger, LongSupplier nanoTime) {
        this.logger = logger;
        this.nanoTime = nanoTime;
    }

    /**
     * Records one failure of {@code source}. The first failure of a source is logged as a warning with
     * {@code message} and {@code thrown}. A later failure is logged only when at least {@link #WINDOW} has
     * passed since the last logged warning for that source: as {@code message} without {@code thrown},
     * followed by the number of failures left out since then when there were any. Every other failure is
     * counted and not logged.
     */
    public void warn(String source, String message, @Nullable Throwable thrown) {
        long now = nanoTime.getAsLong();
        int[] report = {-1};
        boolean[] first = {false};
        entries.compute(source, (key, entry) -> {
            if (entry == null) {
                first[0] = true;
                return new Entry(now, 0);
            }
            if (now - entry.loggedAt() >= WINDOW.toNanos()) {
                report[0] = entry.skipped();
                return new Entry(now, 0);
            }
            return new Entry(entry.loggedAt(), entry.skipped() + 1);
        });
        if (first[0]) {
            logger.log(Level.WARNING, message, thrown);
        } else if (report[0] == 0) {
            logger.warning(message);
        } else if (report[0] > 0) {
            logger.warning(message + " " + report[0] + " more since the last warning were not logged.");
        }
    }
}
