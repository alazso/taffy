package so.alaz.taffy.hooks;

import org.bukkit.Server;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.Scheduler;

import java.util.Objects;

/**
 * The Vault economy, looked up again when an economy registers or leaves. {@link EconomyProvider#watchVault} gives
 * one: it looks the economy up at once and, while the options are switched on, registers an {@link EconomyWatcher}
 * for the owner and looks the economy up once more on the first tick. Each lookup after the first is given the
 * result before it, as {@link EconomyProvider#vault(VaultOptions, Detected)} takes it, so a status is warned about
 * and a failed lookup reported once, and again only after the status changed.
 *
 * <p>{@link #current()} is safe to read from any thread. A lookup runs on the thread that fires the service event,
 * and one lookup runs at a time.
 */
public final class VaultWatch implements AutoCloseable {

    private final VaultOptions options;
    private @Nullable EconomyWatcher watcher;
    private volatile Detected<EconomyProvider> current;
    private boolean closed;

    private VaultWatch(VaultOptions options) {
        this.options = options;
        this.current = EconomyLookup.vault(options, null);
    }

    static VaultWatch start(VaultOptions options, Scheduler scheduler) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(scheduler, "scheduler");
        if (options.switchedOn() && !options.owner().isEnabled()) {
            throw new IllegalStateException("the plugin " + options.owner().getName() + " is not enabled: call"
                + " watchVault in onEnable or later, and not in or after onDisable");
        }
        VaultWatch watch = new VaultWatch(options);
        if (options.switchedOn()) {
            watch.listen(scheduler);
        }
        return watch;
    }

    private synchronized void listen(Scheduler scheduler) {
        Plugin owner = options.owner();
        Server server = owner.getServer();
        // Bukkit refuses a listener of a plugin that is not enabled with an IllegalPluginAccessException.
        watcher = new EconomyWatcher(this::lookUp, server::isStopping);
        server.getPluginManager().registerEvents(watcher, owner);
        if (!scheduler.global(this::lookUp)) {
            owner.getLogger().warning("The economy is not looked up again on the first tick, because this plugin is"
                + " not enabled.");
        }
    }

    private synchronized void lookUp() {
        if (!closed) {
            current = EconomyLookup.vault(options, current);
        }
    }

    /**
     * Gives the result of the latest lookup.
     *
     * @return the latest result, which stays the last one found once this watch is closed
     */
    public Detected<EconomyProvider> current() {
        return current;
    }

    /**
     * Unregisters the listener and stops every later lookup, the one on the first tick included. Closing a watch
     * that is closed, or one whose options are switched off, does nothing.
     */
    @Override
    public synchronized void close() {
        closed = true;
        if (watcher != null) {
            HandlerList.unregisterAll(watcher);
            watcher = null;
        }
    }
}
