package so.alaz.taffy.platform;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Folia-safe scheduling over Paper's region-threaded schedulers, identical on Paper and Folia. Tasks are
 * bound to the owning plugin, and Paper cancels them when it disables. {@code Bukkit.getScheduler()} is
 * unsupported on Folia and is never called.
 *
 * <p>The scheduler never waits for work and never runs leftover work on its own. Once the plugin is
 * disabling, {@link #global}, {@link #async}, {@link #delayedAsync} and {@link #repeatingAsync} return
 * {@code false} and run nothing, {@link #entity(Entity, Runnable, Runnable)} runs {@code retired} on the
 * calling thread, {@link #dispatch} completes exceptionally, {@link #asyncExecutor} rejects, and
 * {@link #asyncOrRun} runs the task on the calling thread.
 *
 * <p>A task Paper accepted but has not run by the time the owning plugin disables may never run: an entity
 * task then runs neither its task nor its retired callback, and a delayed task is cancelled. Work that must
 * settle is tracked by its caller in {@link PendingSettlements} before it is scheduled, and settled while
 * the plugin disables. {@link PendingSettlements#settleAll} does not see an entry tracked after it began, so a
 * caller whose scheduling call is refused starts the entry itself.
 *
 * <p>Every method may be called from any thread. {@link #dispatch} with an entity tag looks the player up on
 * the server from the calling thread.
 */
public final class Scheduler implements Dispatcher {

    /** The server kind the scheduler runs on. */
    public enum Mode {
        PAPER,
        FOLIA
    }

    static final String FOLIA_MARKER = "io.papermc.paper.threadedregions.RegionizedServer";

    private static final Runnable NOTHING = () -> {
    };

    private final Plugin plugin;
    private final Server server;
    private final Mode mode;
    private final List<ScheduledTask> repeating = new CopyOnWriteArrayList<>();

    /** A scheduler whose tasks are owned by {@code plugin} and run on that plugin's server. */
    public Scheduler(Plugin plugin) {
        this(plugin, Scheduler.class.getClassLoader());
    }

    Scheduler(Plugin plugin, ClassLoader loader) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.mode = detect(plugin, loader);
    }

    private static Mode detect(Plugin plugin, ClassLoader loader) {
        return Classes.present(FOLIA_MARKER, loader, plugin.getLogger()) ? Mode.FOLIA : Mode.PAPER;
    }

    /**
     * {@link Mode#FOLIA} when Folia's server class was loadable at construction, otherwise
     * {@link Mode#PAPER}. A lookup that failed reads as Paper, with a warning in the plugin's log.
     */
    public Mode mode() {
        return mode;
    }

    /**
     * Runs {@code task} on the global region thread, as soon as possible. Returns {@code false} when the
     * plugin is disabling: the task will never run.
     */
    public boolean global(Runnable task) {
        return submit(() -> server.getGlobalRegionScheduler().run(plugin, scheduled -> task.run()));
    }

    /**
     * Runs {@code task} on an async thread, as soon as possible. Returns {@code false} when the plugin is
     * disabling: the task will never run.
     */
    public boolean async(Runnable task) {
        return submit(() -> server.getAsyncScheduler().runNow(plugin, scheduled -> task.run()));
    }

    /**
     * Runs {@code task} on an async thread, as soon as possible, or on the calling thread when the plugin is
     * disabling and cannot schedule. The task may then block the calling thread, so it must be safe to run
     * there. A task scheduled before the plugin disables keeps running once started, but one that has not
     * started when Paper cancels the plugin's tasks, after {@code onDisable} returns, never runs.
     */
    public void asyncOrRun(Runnable task) {
        if (!async(task)) {
            task.run();
        }
    }

    /**
     * An executor over {@link #async}. Its {@code execute} runs the task on an async thread, or throws
     * {@link RejectedExecutionException} when the plugin is disabling: the task will never run.
     */
    public Executor asyncExecutor() {
        return task -> {
            if (!async(task)) {
                throw disabling(ThreadTag.ASYNC);
            }
        };
    }

    /**
     * Runs {@code task} once on an async thread after {@code delay}, which is passed to Paper in whole
     * milliseconds. Returns {@code false} when the plugin is disabling: the task will never run. Paper
     * cancels the task when the plugin disables, so one not yet due by then never runs either. Paper throws
     * {@link IllegalArgumentException} for a negative delay.
     */
    public boolean delayedAsync(Runnable task, Duration delay) {
        return submit(() -> server.getAsyncScheduler().runDelayed(
            plugin, scheduled -> task.run(), delay.toMillis(), TimeUnit.MILLISECONDS));
    }

    /**
     * Runs {@code task} on an async thread after {@code initialDelay}, then every {@code period} thereafter,
     * both passed to Paper in whole milliseconds, until {@link #close}. Returns {@code false} when the
     * plugin is disabling: the task is then not scheduled, or is cancelled at once. Paper throws
     * {@link IllegalArgumentException} for a negative initial delay or a period below one millisecond.
     */
    public boolean repeatingAsync(Runnable task, Duration initialDelay, Duration period) {
        if (!plugin.isEnabled()) {
            return false;
        }
        ScheduledTask added;
        try {
            added = server.getAsyncScheduler().runAtFixedRate(
                plugin, scheduled -> task.run(), initialDelay.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
        } catch (IllegalPluginAccessException disabling) {
            return false;
        }
        repeating.add(added);
        // close() may have finished its pass before the task was tracked, so the task is cancelled directly.
        if (!plugin.isEnabled()) {
            repeating.remove(added);
            added.cancel();
            repeating.forEach(ScheduledTask::cancel);
            return false;
        }
        return true;
    }

    /**
     * Cancels every task {@link #repeatingAsync} started through this instance, whichever class started it. A
     * run already in progress finishes. One-shot tasks are left to Paper. The owning plugin calls this first
     * in {@code onDisable}. The instance stays usable: a repeating task started after the call, or while it
     * runs, is cancelled by the next call.
     */
    public void close() {
        // A task tracked during this pass is not in the snapshot being iterated and stays tracked.
        for (ScheduledTask task : repeating) {
            task.cancel();
            repeating.remove(task);
        }
    }

    /**
     * Runs {@code task} on the region thread owning the entity. The task is dropped if the entity is gone or
     * the plugin is disabling.
     */
    public void entity(Entity entity, Runnable task) {
        entity(entity, task, NOTHING);
    }

    /**
     * Runs {@code task} on the region thread owning the entity, or {@code retired} when the task cannot run.
     * Paper runs {@code retired} if the entity retires (for example the player logs out) before the task
     * runs. When the entity is already removed, Paper runs neither, and while the plugin is disabling
     * nothing can be scheduled: in both cases {@code retired} runs here, on the calling thread, so it must
     * not assume a region thread.
     *
     * <p>At most one of the two runs. A task still scheduled when the owning plugin disables runs neither,
     * so work that must settle is also tracked by its caller in {@link PendingSettlements}.
     */
    public void entity(Entity entity, Runnable task, Runnable retired) {
        if (plugin.isEnabled()) {
            try {
                // Paper returns null and runs neither callback when the entity is already removed.
                if (entity.getScheduler().run(plugin, scheduled -> task.run(), retired) != null) {
                    return;
                }
            } catch (IllegalPluginAccessException disabling) {
                // The plugin began disabling after the check, so Paper scheduled nothing.
            }
        }
        retired.run();
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@link ThreadTag#GLOBAL} and {@link ThreadTag#ASYNC} complete with
     * {@link RejectedExecutionException} when the plugin is disabling. An entity tag names an online player
     * and completes with {@link EntityUnavailableException} when the player is offline, retires before the
     * task runs, or the plugin is disabling. A task still scheduled when the plugin disables may leave the
     * future incomplete. Every tag queues the task, also when the caller is already on that thread.
     */
    @Override
    public <T> CompletableFuture<T> dispatch(ThreadTag tag, Supplier<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable work = () -> {
            try {
                result.complete(task.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        };
        switch (tag) {
            case ThreadTag.Global _ -> {
                if (!global(work)) {
                    result.completeExceptionally(disabling(tag));
                }
            }
            case ThreadTag.Async _ -> {
                if (!async(work)) {
                    result.completeExceptionally(disabling(tag));
                }
            }
            case ThreadTag.Entity(UUID id) -> {
                Runnable retired = () -> result.completeExceptionally(new EntityUnavailableException(id));
                Player player = server.getPlayer(id);
                if (player == null) {
                    retired.run();
                } else {
                    entity(player, work, retired);
                }
            }
        }
        return result;
    }

    private boolean submit(Runnable submission) {
        if (!plugin.isEnabled()) {
            return false;
        }
        try {
            submission.run();
            return true;
        } catch (IllegalPluginAccessException disabling) {
            return false;
        }
    }

    private RejectedExecutionException disabling(ThreadTag tag) {
        return new RejectedExecutionException(plugin.getName() + " is disabling, so " + tag + " accepts no work");
    }
}
