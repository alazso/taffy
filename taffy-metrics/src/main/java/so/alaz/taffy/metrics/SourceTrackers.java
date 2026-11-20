package so.alaz.taffy.metrics;

import dev.faststats.ErrorTracker;
import dev.faststats.ErrorTrackerService;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Sends reports to FastStats error tracking, one tracker per source for its handled reports and a second for its
 * unhandled ones, so handled reports of one kind from one source merge into one error with a count and each error
 * carries its source as the {@code source} attribute.
 *
 * <p>The tracker of a source is created on the first report from it, given the {@link Anonymizers} rules and the
 * source attribute, and registered with the error service. Its tracker for unhandled reports is created the same
 * way on its first unhandled report. At most {@value #MAX_SOURCES} sources get trackers; a handled report from any
 * other source goes to the service's global tracker, with no source attribute, and an unhandled one to one tracker
 * those sources share, with the rules and no source attribute.
 *
 * <p>Two reports are of one kind when every throwable of their cause chains has the same class, the same
 * {@link #messageKey} and the same stack frames. A report of a kind already seen is tracked as the first report of
 * that kind, so messages that differ only in what the {@link Anonymizers} rules hide, in an address or in a number
 * merge, and the error carries the message of the first. Each source, and the sources past {@value #MAX_SOURCES}
 * together, keep at most {@value #MAX_KINDS} kinds for the life of this instance, which is the life of the
 * {@link Metrics} handle that holds it; a report of any further kind from that source is dropped. This bounds what
 * the trackers hold while submissions fail. When a source holds its {@value #MAX_KINDS}th kind, one warning that names
 * the source is logged on the logger this instance was given.
 *
 * <p>An unhandled report is tracked with {@code handled} set to {@code false}, once for its kind: a later unhandled
 * report of that kind from the source is dropped, and a handled report of that kind, made before or after it, is
 * tracked as a handled error beside it. The SDK keeps each error it is told is unhandled as an entry of its own,
 * which no later report merges into, so tracking every unhandled report would hold one entry for each while
 * submissions fail. A tracker counts a report that equals a handled entry it holds into that entry before the
 * report can be marked unhandled, so unhandled reports go to a tracker that holds no handled entry.
 *
 * <p>Once a tracker is registered, every scheduled error submission and the send inside the context's shutdown
 * post an {@code errors} array, even when it is empty.
 *
 * <p>Thread-safe: {@link #report} may be called from any thread.
 */
final class SourceTrackers {

    /** The most sources that get a tracker of their own. */
    static final int MAX_SOURCES = 64;

    /** The most kinds of report one source, or every source past {@link #MAX_SOURCES} together, sends. */
    static final int MAX_KINDS = 32;

    static final String ADDRESS = "[address]";
    static final String NUMBER = "[number]";

    private static final Pattern IPV4 = Pattern.compile("(?<![\\w.])\\d{1,3}(?:\\.\\d{1,3}){3}(?![\\w.])");
    private static final Pattern IPV6 =
        Pattern.compile("(?i)(?<![\\w:.])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![\\w:])");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private final ErrorTrackerService service;
    private final Logger logger;
    private final Map<String, Source> bySource = new ConcurrentHashMap<>();
    private final Source overflow;

    SourceTrackers(ErrorTrackerService service, Logger logger) {
        this.service = service;
        this.logger = logger;
        this.overflow = new Source(service.globalErrorTracker(), null);
    }

    /**
     * Tracks {@code error} on the tracker of {@code source} for handled or for unhandled reports, or on those of the
     * sources past the bound once the sources are full, unless that source already holds {@value #MAX_KINDS} other
     * kinds, or {@code handled} is {@code false} and the source already tracked an unhandled report of the kind. Logs
     * one warning when {@code error} is the {@value #MAX_KINDS}th kind of its source.
     */
    void report(String source, Throwable error, boolean handled) {
        Objects.requireNonNull(error, "error");
        Source target = bySource.get(source);
        if (target == null) {
            target = register(source);
        }
        if (target.track(error, handled)) {
            String from = target == overflow
                ? "the sources past the first " + MAX_SOURCES : "the source '" + source + "'";
            logger.warning("Error reports from " + from + " reached " + MAX_KINDS + " kinds. A report of any"
                + " further kind from there is not sent to FastStats until metrics start again.");
        }
    }

    /** The number of sources that have a tracker of their own. */
    int sources() {
        return bySource.size();
    }

    /**
     * Returns the source of {@code source}, creating and registering its tracker when there is room, or the shared
     * source of the global tracker when there is none.
     */
    private Source register(String source) {
        synchronized (bySource) {
            Source known = bySource.get(source);
            if (known != null) {
                return known;
            }
            if (bySource.size() >= MAX_SOURCES) {
                return overflow;
            }
            Source created = new Source(tracker(source), source);
            bySource.put(source, created);
            return created;
        }
    }

    /**
     * A new tracker with the {@link Anonymizers} rules and, unless {@code source} is {@code null}, the source
     * attribute, registered with the error service.
     */
    private ErrorTracker tracker(@Nullable String source) {
        ErrorTracker tracker = Anonymizers.apply(ErrorTracker.contextUnaware());
        if (source != null) {
            tracker.getAttributes().put("source", source);
        }
        service.registerErrorTracker(tracker);
        return tracker;
    }

    /**
     * The kind of {@code error}: for each throwable of its cause chain, its class name, the {@link #messageKey} of
     * its message, and its stack frames.
     */
    static List<Object> kindOf(Throwable error) {
        List<Object> kind = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = error; current != null && seen.add(current); current = current.getCause()) {
            kind.add(current.getClass().getName());
            kind.add(messageKey(current.getMessage()));
            kind.add(Arrays.asList(current.getStackTrace()));
        }
        return kind;
    }

    /**
     * {@code message} with the {@link Anonymizers} rules applied, then each IPv4 and IPv6 address replaced by
     * {@value #ADDRESS} and each remaining run of digits by {@value #NUMBER}.
     *
     * @return the key, or {@code null} when {@code message} is {@code null}
     */
    static @Nullable String messageKey(@Nullable String message) {
        String hidden = Anonymizers.anonymize(message);
        if (hidden == null) {
            return null;
        }
        String key = IPV4.matcher(hidden).replaceAll(ADDRESS);
        key = IPV6.matcher(key).replaceAll(ADDRESS);
        return DIGITS.matcher(key).replaceAll(NUMBER);
    }

    /**
     * The tracker for handled reports, the first report of each kind it was given, the kinds it tracked as unhandled
     * and the tracker for those.
     */
    private final class Source {

        private final ErrorTracker tracker;
        private final @Nullable String name;
        private final Map<List<Object>, Throwable> kinds = new HashMap<>();
        private final Set<List<Object>> unhandled = new HashSet<>();

        /** Guarded by {@link #kinds}. */
        private @Nullable ErrorTracker unhandledTracker;

        /**
         * @param name the source attribute of the tracker for unhandled reports, or {@code null} for none
         */
        Source(ErrorTracker tracker, @Nullable String name) {
            this.tracker = tracker;
            this.name = name;
        }

        /**
         * Tracks the first report of the kind of {@code error}, or nothing when the kinds are full or the kind was
         * already tracked as unhandled and {@code handled} is {@code false}.
         *
         * @return {@code true} when {@code error} is the {@value #MAX_KINDS}th kind
         */
        boolean track(Throwable error, boolean handled) {
            List<Object> kind = kindOf(error);
            Throwable first;
            boolean filled = false;
            synchronized (kinds) {
                first = kinds.get(kind);
                if (first == null) {
                    if (kinds.size() >= MAX_KINDS) {
                        return false;
                    }
                    kinds.put(kind, error);
                    first = error;
                    filled = kinds.size() == MAX_KINDS;
                }
                if (!handled) {
                    if (unhandled.add(kind)) {
                        if (unhandledTracker == null) {
                            unhandledTracker = SourceTrackers.this.tracker(name);
                        }
                        unhandledTracker.trackError(first).handled(false);
                    }
                    return filled;
                }
            }
            tracker.trackError(first);
            return filled;
        }
    }
}
