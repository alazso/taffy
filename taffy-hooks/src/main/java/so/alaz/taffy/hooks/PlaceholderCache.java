package so.alaz.taffy.hooks;

import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ThrowLog;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * The last known value of a placeholder whose source cannot be read on the thread that asks, such as a balance or a
 * row of a database. PlaceholderAPI asks for a value on whatever thread the requester runs on, often the server
 * thread or a Folia region thread, so a handler returns {@link #get} at once and the cache refreshes the value
 * behind it.
 *
 * <p>{@link #get} never waits. It returns the value the last completed refresh gave, and starts a refresh when the
 * key has none yet or its value is at least the time-to-live old, unless a refresh for that key is already running.
 * Until the first refresh of a key completes, {@link #get} returns {@code null}, which a handler passes on as no
 * value or replaces with a text of its own. A refresh that gives {@code null} leaves the key with no value.
 *
 * <p>The cache owns no thread. The refresh a caller supplies only starts the work and returns its future, as
 * {@link so.alaz.taffy.platform.Scheduler#dispatch} or a storage call does, and it runs on the thread that called
 * {@link #get}. The value is stored on the thread that completes the future. A future that never completes stops
 * further refreshes of its key until {@link #forget} or {@link #clear}.
 *
 * <p>A refresh fails when the supplier throws or returns {@code null}, or when its future completes with a
 * failure. The last known value is then kept, the next refresh of that key starts no sooner than one time-to-live
 * later, and the failure is logged as {@link ThrowLog} does: the first with its stack trace, later ones at most
 * once per {@link ThrowLog#WINDOW} for the whole cache.
 *
 * <p>The cache keeps at most its maximum number of keys, counting a key whose first refresh is still running. A
 * new key beyond that drops the key that was asked for longest ago. {@link #forget} drops one key, as for a
 * player who quits, and {@link #clear} drops every key, as in {@code onDisable}. A refresh that completes after its
 * key was dropped stores nothing.
 *
 * <p>Thread-safe: every method may be called from any thread.
 *
 * @param <K> the key of a value, such as a player's {@link java.util.UUID}; it is used as a map key
 */
public final class PlaceholderCache<K> {

    private static final String SOURCE = "placeholder-cache";

    private static final class Entry {
        private @Nullable String value;
        private boolean checked;
        private long checkedAt;
        private boolean refreshing;
    }

    private final long timeToLiveNanos;
    private final ThrowLog failures;
    private final LongSupplier nanoTime;
    private final Map<K, Entry> entries;

    /**
     * Starts an empty cache.
     *
     * @param timeToLive how old a value may be before the next {@link #get} refreshes it
     * @param maximumEntries the most keys kept at once
     * @param logger receives the warning about a refresh that failed
     * @throws IllegalArgumentException if {@code timeToLive} is zero or negative, or {@code maximumEntries} is below
     *     one
     */
    public PlaceholderCache(Duration timeToLive, int maximumEntries, Logger logger) {
        this(timeToLive, maximumEntries, logger, System::nanoTime);
    }

    PlaceholderCache(Duration timeToLive, int maximumEntries, Logger logger, LongSupplier nanoTime) {
        Objects.requireNonNull(timeToLive, "timeToLive");
        Objects.requireNonNull(logger, "logger");
        if (timeToLive.isZero() || timeToLive.isNegative()) {
            throw new IllegalArgumentException("invalid time-to-live " + timeToLive + ": expected more than zero");
        }
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("invalid maximum of " + maximumEntries + " entries: expected at least"
                + " one");
        }
        this.timeToLiveNanos = nanos(timeToLive);
        this.failures = new ThrowLog(logger);
        this.nanoTime = nanoTime;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Entry> eldest) {
                return size() > maximumEntries;
            }
        };
    }

    private static long nanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException tooLong) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Returns the last known value of {@code key} without waiting, and starts a refresh when the key has no value
     * yet or its value is stale, as the class description states.
     *
     * @param key the key of the value
     * @param refresh starts the read of a fresh value and returns its future; it is called on this thread, at most
     *     once per call, and must not block
     * @return the value the last completed refresh gave, or {@code null} when none completed yet or it gave
     *     {@code null}
     * @throws NullPointerException if {@code key} or {@code refresh} is {@code null}
     */
    public @Nullable String get(K key, Supplier<CompletableFuture<@Nullable String>> refresh) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(refresh, "refresh");
        Entry entry;
        synchronized (entries) {
            entry = entries.computeIfAbsent(key, absent -> new Entry());
            boolean fresh = entry.checked && nanoTime.getAsLong() - entry.checkedAt < timeToLiveNanos;
            if (entry.refreshing || fresh) {
                return entry.value;
            }
            entry.refreshing = true;
        }
        start(entry, refresh);
        synchronized (entries) {
            return entry.value;
        }
    }

    // Called without the lock, since the supplier is the caller's code and its future may complete at once.
    private void start(Entry entry, Supplier<CompletableFuture<@Nullable String>> refresh) {
        CompletableFuture<@Nullable String> future;
        try {
            future = Objects.requireNonNull(refresh.get(), "the refresh returned no future");
        } catch (RuntimeException | LinkageError failure) {
            finish(entry, null, failure);
            return;
        }
        future.whenComplete((value, failure) -> finish(entry, value, failure));
    }

    private void finish(Entry entry, @Nullable String value, @Nullable Throwable failure) {
        synchronized (entries) {
            entry.refreshing = false;
            entry.checked = true;
            entry.checkedAt = nanoTime.getAsLong();
            if (failure == null) {
                entry.value = value;
            }
        }
        if (failure != null) {
            failures.warn(SOURCE, "A placeholder value could not be refreshed, so its last known value is kept ("
                + failure + ").", failure);
        }
    }

    /**
     * Drops {@code key} and its value, so the next {@link #get} for it returns {@code null} and starts a refresh. A
     * refresh of the key that is still running stores nothing. A key that is not kept is ignored.
     *
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public void forget(K key) {
        Objects.requireNonNull(key, "key");
        synchronized (entries) {
            entries.remove(key);
        }
    }

    /**
     * Drops every key and value. A refresh that is still running stores nothing. The cache can be used again
     * afterwards.
     */
    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }

    int size() {
        synchronized (entries) {
            return entries.size();
        }
    }
}
