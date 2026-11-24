package so.alaz.taffy.hooks;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.platform.ThrowLog;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

// The backend's listener stays registered from detection to close, and a click that arrives while no click listener
// is set is dropped. An entity scheduler runs a task on the thread that owns the entity, on Paper and on Folia.
final class PluginNpcs implements NpcProvider, NpcClicks {

    private final NpcPlugin plugin;
    private final Plugin bound;
    private final Plugin owner;
    private final NpcBackend backend;
    private final ThrowLog throwLog;
    private final ErrorReports errors;
    private final String source;
    private volatile @Nullable BiConsumer<Player, String> listener;
    private volatile boolean closed;

    PluginNpcs(NpcPlugin plugin, Plugin bound, Plugin owner, ErrorReports errors,
               Function<NpcClicks, NpcBackend> backends) {
        this.plugin = plugin;
        this.bound = bound;
        this.owner = owner;
        this.throwLog = new ThrowLog(owner.getLogger());
        this.errors = errors;
        this.source = Detection.source(plugin.plugin());
        this.backend = Objects.requireNonNull(backends.apply(this), "the backend");
    }

    static Detected<NpcProvider> detect(Plugin owner, ErrorReports errors, boolean switchedOn, NpcPlugin plugin,
                                        Presence presence, Function<NpcClicks, NpcBackend> backends) {
        Objects.requireNonNull(errors, "errors");
        return Detection.run(plugin.plugin(), plugin.probe(), switchedOn, presence, bound -> {
            PluginNpcs npcs = new PluginNpcs(plugin, bound, owner, errors, backends);
            owner.getServer().getPluginManager().registerEvents(npcs.backend.listener(), owner);
            return npcs;
        }, owner.getLogger(), errors);
    }

    @Override
    public NpcPlugin plugin() {
        return plugin;
    }

    @Override
    public boolean available() {
        return !closed && bound.isEnabled();
    }

    @Override
    public @Nullable Location location(String id) {
        Objects.requireNonNull(id, "id");
        if (!available()) {
            return null;
        }
        try {
            Location found = backend.locations().apply(id);
            return found == null || !found.isWorldLoaded() ? null : found.clone();
        } catch (Exception | LinkageError ex) {
            throwLog.warn("location", plugin.plugin() + " could not look up where an NPC stands (" + ex + ").", ex);
            errors.report(source, ex);
            return null;
        }
    }

    @Override
    public void onClick(BiConsumer<Player, String> listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        listener = null;
        HandlerList.unregisterAll(backend.listener());
    }

    @Override
    public void clicked(Player player, String id, boolean asynchronous) {
        if (closed || listener == null) {
            return;
        }
        if (!asynchronous) {
            deliver(player, id);
            return;
        }
        if (!owner.isEnabled()) {
            return;
        }
        try {
            player.getScheduler().run(owner, task -> deliver(player, id), null);
        } catch (RuntimeException | LinkageError ex) {
            throwLog.warn("hand-over", "A click on a " + plugin.plugin() + " NPC could not be handed to the player's"
                + " thread (" + ex + ").", ex);
            errors.report(source, ex);
        }
    }

    @Override
    public void failed(Throwable failure) {
        throwLog.warn("click", plugin.plugin() + " fired a click on an NPC that could not be read (" + failure + ").",
            failure);
        errors.report(source, failure);
    }

    private void deliver(Player player, String id) {
        BiConsumer<Player, String> current = listener;
        if (closed || current == null) {
            return;
        }
        try {
            current.accept(player, id);
        } catch (Exception | LinkageError ex) {
            throwLog.warn("listener", "The listener of clicks on " + plugin.plugin() + " NPCs failed (" + ex + ").",
                ex);
            errors.report(source, ex);
        }
    }
}
