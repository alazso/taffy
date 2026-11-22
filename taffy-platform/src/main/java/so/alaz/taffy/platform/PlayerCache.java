package so.alaz.taffy.platform;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * One value per online player, loaded when the player joins and forgotten when the player quits, so a read
 * never waits for the load.
 *
 * <p>The cache registers its own join and quit listener when it is built and loads every player already online
 * then, as it loads a player who joins. A join starts a load at {@link EventPriority#LOWEST}, and a quit forgets
 * the player at {@link EventPriority#MONITOR}, so the plugin's own quit handlers still read the value. Nothing is
 * loaded at {@code AsyncPlayerPreLoginEvent} and no login is denied: until a load succeeds, {@link #get} is
 * empty and {@link #ready} is {@code false}, and the caller decides what a player without a value may do. Nothing
 * is saved at a quit or at {@link #close()}.
 *
 * <p>Each load calls the loader through the {@link Dispatcher} with {@link ThreadTag#ASYNC}, so a loader that
 * blocks does not block the thread of the join, and stores the value on the thread that completes the loader's
 * future. A load fails when the loader throws, returns {@code null}, or its future completes with {@code null}
 * or with an exception. A failed load hands out no value. It is reported to the {@link ErrorReports} as
 * {@link #SOURCE}, logged through a {@link ThrowLog} of the owner's logger, and started again through the timer
 * after {@link #FIRST_RETRY_DELAY}, then after twice the delay before it each time, up to
 * {@link #LONGEST_RETRY_DELAY}, for as long as the player stays online and no newer load of that player has
 * started. A result or a retry of a player who has quit since, also one who has joined again, and of a load
 * that {@link #refresh} has replaced, is dropped. A dispatch the dispatcher refuses, as a {@link Scheduler}
 * does once its plugin is disabling, ends the load with no report and no retry.
 *
 * <p>The values are read from any thread while a load may replace them, so {@code T} is immutable or
 * thread-safe.
 *
 * <p>Thread-safe: {@link #get}, {@link #ready}, {@link #refresh} and {@link #close} may be called from any
 * thread.
 *
 * @param <T> the type of the value kept for a player
 */
public final class PlayerCache<T> implements AutoCloseable {

    /** The {@link ErrorReports} source of a failed load. */
    public static final String SOURCE = "player-cache.load";

    /** The delay before the first retry of a failed load. */
    public static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(2);

    /** The longest delay between two attempts of one load. */
    public static final Duration LONGEST_RETRY_DELAY = Duration.ofMinutes(5);

    private static final int DOUBLINGS = 16;

    /** The latest load started for a player, compared by identity, and the value of the last one that succeeded. */
    private record Entry<V>(Object load, @Nullable V value) {
    }

    private final ErrorReports errors;
    private final Dispatcher dispatcher;
    private final BiConsumer<Runnable, Duration> timer;
    private final Function<UUID, CompletableFuture<T>> loader;
    private final ThrowLog failures;
    private final Consumer<Listener> unregister;
    private final Events listener = new Events(this);
    private final Map<UUID, Entry<T>> entries = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * Creates the cache, registers its listener with the plugin manager of the owner's server as a listener of
     * {@code owner}, and starts a load for every player online now. Call while the plugin is enabled, such as
     * in {@code onEnable}, on the thread that enables it.
     *
     * @param owner the plugin the listener belongs to, whose logger takes the warning of a failed load
     * @param errors where a failed load is reported, as {@link #SOURCE}
     * @param dispatcher runs each call of {@code loader} with {@link ThreadTag#ASYNC}, such as a {@link Scheduler}
     * @param timer runs a retry after a delay, such as {@code scheduler::delayedAsync}; a retry it never runs
     *     ends the load
     * @param loader gives the value of a player, such as a read of the plugin's storage
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.bukkit.plugin.IllegalPluginAccessException if the plugin is not enabled, as the server refuses
     *     a listener of such a plugin; no cache exists then
     */
    public PlayerCache(Plugin owner, ErrorReports errors, Dispatcher dispatcher, BiConsumer<Runnable, Duration> timer,
                       Function<UUID, CompletableFuture<T>> loader) {
        this(Objects.requireNonNull(owner, "owner").getLogger(), errors, dispatcher, timer, loader,
            listener -> owner.getServer().getPluginManager().registerEvents(listener, owner),
            HandlerList::unregisterAll,
            () -> owner.getServer().getOnlinePlayers().stream().map(Player::getUniqueId).toList());
    }

    PlayerCache(Logger logger, ErrorReports errors, Dispatcher dispatcher, BiConsumer<Runnable, Duration> timer,
                Function<UUID, CompletableFuture<T>> loader, Consumer<Listener> register,
                Consumer<Listener> unregister, Supplier<Collection<UUID>> online) {
        this.errors = Objects.requireNonNull(errors, "errors");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.failures = new ThrowLog(logger);
        this.unregister = unregister;
        register.accept(listener);
        for (UUID player : online.get()) {
            Object load = new Object();
            // A join that arrived since the listener was registered has started the player's load already.
            if (entries.putIfAbsent(player, new Entry<>(load, null)) == null) {
                begin(player, load);
            }
        }
    }

    /**
     * The value of the player's last load that succeeded since they joined, or empty when none has: before the
     * first load ends, while it keeps failing, for a player who is not online and after {@link #close()}.
     *
     * @throws NullPointerException if {@code player} is {@code null}
     */
    public Optional<T> get(UUID player) {
        Entry<T> entry = entries.get(player);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.value());
    }

    /**
     * Whether {@link #get} holds a value for the player.
     *
     * @throws NullPointerException if {@code player} is {@code null}
     */
    public boolean ready(UUID player) {
        Entry<T> entry = entries.get(player);
        return entry != null && entry.value() != null;
    }

    /**
     * Loads the player's value again and replaces any load of that player still running, whose result and
     * retries are dropped. The value held now stays readable until the new load succeeds, also while it fails
     * and is retried. Does nothing for a player the cache holds no entry for: one who is not online, or any
     * player after {@link #close()}.
     *
     * @throws NullPointerException if {@code player} is {@code null}
     */
    public void refresh(UUID player) {
        Object load = new Object();
        if (entries.computeIfPresent(player, (key, current) -> new Entry<>(load, current.value())) != null) {
            begin(player, load);
        }
    }

    /**
     * Unregisters the listener and forgets every player. The result and the retries of a load still running are
     * dropped, and no load starts afterwards. A second call does nothing more.
     */
    @Override
    public void close() {
        closed = true;
        unregister.accept(listener);
        entries.clear();
    }

    void joined(UUID player) {
        Object load = new Object();
        entries.put(player, new Entry<>(load, null));
        begin(player, load);
    }

    void left(UUID player) {
        entries.remove(player);
    }

    private void begin(UUID player, Object load) {
        // close() may have cleared the entries before this one was put.
        if (closed) {
            entries.clear();
            return;
        }
        attempt(player, load, 0);
    }

    private void attempt(UUID player, Object load, int failed) {
        if (!current(player, load)) {
            return;
        }
        Supplier<@Nullable CompletableFuture<T>> task = () -> current(player, load) ? call(player) : null;
        dispatcher.dispatch(ThreadTag.ASYNC, task).whenComplete((loading, refusal) -> {
            // The task throws nothing, so the dispatch fails only when the dispatcher refused it.
            if (refusal == null && loading != null) {
                loading.whenComplete((value, failure) -> finish(player, load, failed, value, failure));
            }
        });
    }

    private CompletableFuture<T> call(UUID player) {
        try {
            CompletableFuture<T> loading = loader.apply(player);
            return loading != null ? loading
                : CompletableFuture.failedFuture(new NullPointerException("The loader returned no future"));
        } catch (Throwable thrown) {
            return CompletableFuture.failedFuture(thrown);
        }
    }

    private void finish(UUID player, Object load, int failed, @Nullable T value, @Nullable Throwable failure) {
        if (failure == null && value != null) {
            entries.computeIfPresent(player, (key, current) ->
                current.load() == load ? new Entry<>(load, value) : current);
            return;
        }
        if (!current(player, load)) {
            return;
        }
        Throwable cause = failure == null ? new NullPointerException("The loader completed with null")
            : failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        Duration delay = retryDelay(failed + 1);
        errors.report(SOURCE, cause);
        failures.warn(SOURCE, "Could not load the cached value of " + player + ", and try again in "
            + delay.toSeconds() + " s while the player is online: " + cause, cause);
        try {
            timer.accept(() -> attempt(player, load, failed + 1), delay);
        } catch (Throwable thrown) {
            errors.report(SOURCE, thrown);
        }
    }

    private boolean current(UUID player, Object load) {
        Entry<T> entry = entries.get(player);
        return entry != null && entry.load() == load;
    }

    /** The delay before the attempt that follows {@code failed} failed attempts of one load. */
    static Duration retryDelay(int failed) {
        if (failed > DOUBLINGS) {
            return LONGEST_RETRY_DELAY;
        }
        Duration delay = FIRST_RETRY_DELAY.multipliedBy(1L << (failed - 1));
        return delay.compareTo(LONGEST_RETRY_DELAY) > 0 ? LONGEST_RETRY_DELAY : delay;
    }

    Listener listener() {
        return listener;
    }

    /** The join and quit handlers, which Paper calls through an executor it builds for each public method. */
    static final class Events implements Listener {

        private final PlayerCache<?> cache;

        private Events(PlayerCache<?> cache) {
            this.cache = cache;
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onJoin(PlayerJoinEvent event) {
            cache.joined(event.getPlayer().getUniqueId());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent event) {
            cache.left(event.getPlayer().getUniqueId());
        }
    }
}
