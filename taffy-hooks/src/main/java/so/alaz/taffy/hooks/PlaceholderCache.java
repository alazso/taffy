package so.alaz.taffy.hooks;

import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ThrowLog;

import java.time.Duration;
import java.util.Iterator;
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
 * once per {@link ThrowLog#WINDOW} for the whole cache. An {@link Error} other than a {@link LinkageError} that
 * the supplier throws leaves {@link #get} and is not logged; the key is otherwise treated as after a failed
 * refresh.
 *
 * <p>The cache keeps at most its maximum number of keys, counting a key whose first refresh is still running. A
 * new key beyond that drops the key that was asked for longest ago among the keys with no refresh running, so a
 * refresh that runs is never started a second time for its key. While every kept key has a refresh running, a new
 * key is not kept: {@link #get} returns {@code null} for it and starts no refresh. {@link #forget} drops one key,
 * as for a player who quits, and {@link #clear} drops every key, as in {@code onDisable}. A refresh that completes
 * after {@link #forget} or {@link #clear} dropped its key stores nothing.
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
    private final int maximumEntries;
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
        this.maximumEntries = maximumEntries;
        this.failures = new ThrowLog(logger);
        this.nanoTime = nanoTime;
        this.entries = new LinkedHashMap<>(16, 0.75f, true);
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
     * @return the value the last completed refresh gave, or {@code null} when none completed yet, it gave
     *     {@code null}, or the key is not kept because every kept key has a refresh running
     * @throws NullPointerException if {@code key} or {@code refresh} is {@code null}
     */
    public @Nullable String get(K key, Supplier<CompletableFuture<@Nullable String>> refresh) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(refresh, "refresh");
        Entry entry;
        synchronized (entries) {
            entry = entries.get(key);
            if (entry == null) {
                if (entries.size() >= maximumEntries && !dropEldestAtRest()) {
                    return null;
                }
                entry = new Entry();
                entries.put(key, entry);
            }
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

    // The map iterates from the key asked for longest ago. Called with the lock held.
    private boolean dropEldestAtRest() {
        Iterator<Entry> eldestFirst = entries.values().iterator();
        while (eldestFirst.hasNext()) {
            if (!eldestFirst.next().refreshing) {
                eldestFirst.remove();
                return true;
            }
        }
        return false;
    }

    // Called without the lock, since the supplier is the caller's code and its future may complete at once.
    private void start(Entry entry, Supplier<CompletableFuture<@Nullable String>> refresh) {
        CompletableFuture<@Nullable String> future = null;
        Throwable thrown = null;
        try {
            future = Objects.requireNonNull(refresh.get(), "the refresh returned no future");
        } catch (RuntimeException | LinkageError failure) {
            thrown = failure;
        } finally {
            if (future == null && thrown == null) {
                settle(entry);
            }
        }
        if (future == null) {
            finish(entry, null, thrown);
            return;
        }
        future.whenComplete((value, failure) -> finish(entry, value, failure));
    }

    private void settle(Entry entry) {
        synchronized (entries) {
            entry.refreshing = false;
            entry.checked = true;
            entry.checkedAt = nanoTime.getAsLong();
        }
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
