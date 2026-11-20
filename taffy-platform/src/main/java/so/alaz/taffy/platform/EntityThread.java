package so.alaz.taffy.platform;

import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Runs work on the region thread owning an online player. */
public interface EntityThread {

    /**
     * Runs {@code task} on the online player's entity thread. Completes with
     * {@link EntityUnavailableException}, and {@code task} never runs, when the player is offline, the
     * server retired the task, or the task could not be scheduled because the owning plugin is disabling.
     *
     * <p>When the task was scheduled and the owning plugin disables before it runs, an entity thread over a
     * {@link Scheduler} completes the future with {@link EntityUnavailableException} at
     * {@link Scheduler#close()}, and {@code task} never runs. Over another {@link Dispatcher} the future may
     * stay incomplete for good, so it is bounded with {@link CompletableFuture#orTimeout}. Work that must settle
     * is tracked in {@link PendingSettlements} before the call. The task is queued even when the caller is
     * already on the player's thread, so waiting for the future on a server thread can block that thread for
     * good.
     */
    <T> CompletableFuture<T> call(UUID player, Function<Player, T> task);

    /**
     * An entity thread that dispatches each call through {@code dispatcher} with {@link ThreadTag#entity}
     * and looks the player up on {@code server} once the task runs.
     */
    static EntityThread on(Dispatcher dispatcher, Server server) {
        return new EntityThread() {
            @Override
            public <T> CompletableFuture<T> call(UUID id, Function<Player, T> task) {
                return dispatcher.dispatch(ThreadTag.entity(id), () -> {
                    Player player = server.getPlayer(id);
                    if (player == null) {
                        throw new EntityUnavailableException(id);
                    }
                    return task.apply(player);
                });
            }
        };
    }
}
