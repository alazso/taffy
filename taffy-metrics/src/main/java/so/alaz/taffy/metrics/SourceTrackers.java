package so.alaz.taffy.metrics;

import dev.faststats.ErrorTracker;
import dev.faststats.ErrorTrackerService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends handled reports to FastStats error tracking, one tracker per source, so reports of one kind from one source
 * merge into one error with a count and each error carries its source as the {@code source} attribute.
 *
 * <p>The tracker of a source is created on the first report from it, given the {@link Anonymizers} rules and the
 * source attribute, and registered with the error service. At most {@value #MAX_SOURCES} sources get a tracker; a
 * report from any other source goes to the service's global tracker, with no source attribute.
 *
 * <p>Two reports are of one kind when every throwable of their cause chains has the same class, the same message
 * once the {@link Anonymizers} rules hide it, and the same stack frames. A report of a kind already seen is tracked
 * as the first report of that kind, so messages that differ only in what the rules hide merge. Each source, and the
 * sources past {@value #MAX_SOURCES} together, keep at most {@value #MAX_KINDS} kinds for the life of this instance,
 * which is the life of the {@link Metrics} handle that holds it; a report of any further kind from that source is
 * dropped. This bounds what the trackers hold while submissions fail.
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

    private final ErrorTrackerService service;
    private final Map<String, Source> bySource = new ConcurrentHashMap<>();
    private final Source overflow;

    SourceTrackers(ErrorTrackerService service) {
        this.service = service;
        this.overflow = new Source(service.globalErrorTracker());
    }

    /**
     * Tracks {@code error} on the tracker of {@code source}, or on the global tracker once the sources are full, unless
     * that source already holds {@value #MAX_KINDS} other kinds.
     */
    void report(String source, Throwable error) {
        Objects.requireNonNull(error, "error");
        Source target = bySource.get(source);
        if (target == null) {
            target = register(source);
        }
        target.track(error);
    }

    /** The number of sources that have a tracker. */
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
            ErrorTracker tracker = Anonymizers.apply(ErrorTracker.contextUnaware());
            tracker.getAttributes().put("source", source);
            service.registerErrorTracker(tracker);
            Source created = new Source(tracker);
            bySource.put(source, created);
            return created;
        }
    }

    /**
     * The kind of {@code error}: for each throwable of its cause chain, its class name, its message with the
     * {@link Anonymizers} rules applied, and its stack frames.
     */
    static List<Object> kindOf(Throwable error) {
        List<Object> kind = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = error; current != null && seen.add(current); current = current.getCause()) {
            kind.add(current.getClass().getName());
            kind.add(Anonymizers.anonymize(current.getMessage()));
            kind.add(Arrays.asList(current.getStackTrace()));
        }
        return kind;
    }

    /** One tracker and the first report of each kind it was given. */
    private static final class Source {

        private final ErrorTracker tracker;
        private final Map<List<Object>, Throwable> kinds = new HashMap<>();

        Source(ErrorTracker tracker) {
            this.tracker = tracker;
        }

        /** Tracks the first report of the kind of {@code error}, or nothing when the kinds are full. */
        void track(Throwable error) {
            List<Object> kind = kindOf(error);
            Throwable first;
            synchronized (kinds) {
                first = kinds.get(kind);
                if (first == null) {
                    if (kinds.size() >= MAX_KINDS) {
                        return;
                    }
                    kinds.put(kind, error);
                    first = error;
                }
            }
            tracker.trackError(first);
        }
    }
}
