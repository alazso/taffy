package so.alaz.taffy.platform;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.CheckReturnValue;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Folia-safe scheduling over Paper's region-threaded schedulers, identical on Paper and Folia. Tasks are
 * bound to the owning plugin, and Paper cancels them when it disables. {@code Bukkit.getScheduler()} is
 * unsupported on Folia and is never called.
 *
 * <p>The scheduler never waits for work and never runs leftover work on its own. When the plugin is not
 * enabled (before {@code onEnable} or once it is disabling), {@link #global}, {@link #async},
 * {@link #delayedAsync} and {@link #repeatingAsync} return {@code false} and run nothing,
 * {@link #entity(Entity, Runnable, Runnable)} runs {@code retired} on the calling thread, {@link #dispatch}
 * completes exceptionally, {@link #asyncExecutor} rejects, and {@link #asyncOrRun} runs the task on the calling
 * thread. The methods that return a {@link ScheduledTask} ({@link #repeatingAsyncTask}, {@link #delayedGlobal},
 * {@link #repeatingGlobal}, {@link #delayedEntity}, {@link #repeatingEntity}, {@link #region},
 * {@link #delayedRegion} and {@link #repeatingRegion}) return {@code null} and run nothing, and the two entity
 * ones first run {@code retired} on the calling thread. A {@code null} from them always means the task was
 * refused: besides a plugin that is not enabled, only an entity that is already removed gives one.
 *
 * <p>A returned {@link ScheduledTask} is Paper's own handle, and its {@code cancel()} stops that one task. The
 * global, entity and region schedulers count in server ticks of 50 ms, so a {@link Duration} given to
 * {@link #delayedGlobal}, {@link #repeatingGlobal}, {@link #delayedEntity}, {@link #repeatingEntity},
 * {@link #delayedRegion} or {@link #repeatingRegion} is rounded up to whole ticks, and anything shorter than
 * one tick, zero and negative values included, is one tick: Paper accepts no shorter delay or period. The async
 * methods pass milliseconds and count no ticks.
 *
 * <p>A task is not passed its own handle. A task that stops itself cancels the returned handle, which its caller
 * keeps where the task's thread reads it, in a {@code volatile} field or an
 * {@link java.util.concurrent.atomic.AtomicReference}. When the scheduling call is made on another thread than the
 * one that runs the task, a first run can start before the caller has stored the handle, as it can at once with a
 * zero initial delay of {@link #repeatingAsyncTask}. Such a run finds no handle to cancel, and a later run does.
 *
 * <p>A task Paper accepted but has not run by the time the owning plugin disables may never run: an entity
 * task then runs neither its task nor its retired callback, and a delayed task is cancelled. {@link #close}
 * completes the future of a {@link #dispatch} whose task has not started, as a refused call does. Work that must
 * settle is tracked by its caller in {@link PendingSettlements} before it is scheduled, and settled while
 * the plugin disables. {@link PendingSettlements#settleAll} does not see an entry tracked after it began, so a
 * caller whose scheduling call is refused starts the entry itself.
 *
 * <p>Paper's region, async and entity schedulers fire no {@code ServerExceptionEvent} for a task that throws: they
 * log it, and nothing that watches the plugin's errors sees it. So when a task Paper runs for {@link #global},
 * {@link #async} (also through {@link #asyncExecutor} and {@link #asyncOrRun}), {@link #delayedAsync},
 * {@link #repeatingAsync} or {@link #entity}, or a {@code retired} callback Paper runs, throws anything (a
 * checked exception thrown without being declared included), the scheduler reports it to the {@link ErrorReports}
 * it was built with, under the source {@code scheduler.global}, {@code scheduler.async} or
 * {@code scheduler.entity}, and then rethrows it unchanged, so Paper logs and handles it as it would without the
 * scheduler. The same holds for the methods that return a {@link ScheduledTask}: the global ones report under
 * {@code scheduler.global}, {@link #repeatingAsyncTask} under {@code scheduler.async}, the entity ones under
 * {@code scheduler.entity} and the region ones under {@code scheduler.region}. The report is made through
 * {@link ErrorReports#reportUnhandled}, since nothing handles the error.
 * Each throw is reported once: a repeating task that throws on several runs is reported for each of them. A task
 * or {@code retired} callback run on the calling thread is not reported, and what it throws reaches the caller.
 * {@link #dispatch} reports nothing: its future carries what the task threw.
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

    static final String GLOBAL_SOURCE = "scheduler.global";
    static final String ASYNC_SOURCE = "scheduler.async";
    static final String ENTITY_SOURCE = "scheduler.entity";
    static final String REGION_SOURCE = "scheduler.region";

    private static final long TICKS_PER_SECOND = 20;
    private static final long NANOS_PER_TICK = 50_000_000;

    private static final Runnable NOTHING = () -> {
    };

    private final Plugin plugin;
    private final ErrorReports errors;
    private final Server server;
    private final Mode mode;
    private final List<ScheduledTask> repeating = new CopyOnWriteArrayList<>();
    private final Set<Dispatch<?>> dispatches = ConcurrentHashMap.newKeySet();

    /**
     * A scheduler whose tasks are owned by {@code plugin} and run on that plugin's server. A task Paper runs that
     * throws is reported to {@code errors}, as the class description states.
     *
     * @throws NullPointerException when {@code plugin} or {@code errors} is {@code null}
     */
    public Scheduler(Plugin plugin, ErrorReports errors) {
        this(plugin, errors, Scheduler.class.getClassLoader());
    }

    Scheduler(Plugin plugin, ErrorReports errors, ClassLoader loader) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.errors = Objects.requireNonNull(errors, "errors");
        this.server = plugin.getServer();
        this.mode = detect(plugin, loader);
    }

    // The class loader of the server's own class holds Folia's server class on Folia, and a lookup through it reaches
    // no plugin's class loader, so a broken plugin cannot make it throw there.
    private static Mode detect(Plugin plugin, ClassLoader loader) {
        Server running = plugin.getServer();
        if (running != null && Classes.present(FOLIA_MARKER, running.getClass().getClassLoader(), plugin.getLogger())) {
            return Mode.FOLIA;
        }
        return Classes.present(FOLIA_MARKER, loader, plugin.getLogger()) ? Mode.FOLIA : Mode.PAPER;
    }

    /**
     * {@link Mode#FOLIA} when Folia's server class was loadable at construction, from the class loader of the
     * server's own class or from this class's loader, otherwise {@link Mode#PAPER}. A lookup that failed counts as
     * not loadable, with a warning in the plugin's log.
     */
    public Mode mode() {
        return mode;
    }

    /**
     * Runs {@code task} on the global region thread, as soon as possible. Returns {@code false} when the
     * plugin is not enabled (before {@code onEnable} or once it is disabling): the task will never run.
     *
     * <p>On Paper the global region thread is the main thread, which owns every chunk and entity. On Folia it
     * owns no chunk and no entity, so world and entity work in {@code task} must hop to the thread that owns
     * its target: {@link #region} for a location, {@link #entity} for an entity.
     */
    @CheckReturnValue
    public boolean global(Runnable task) {
        return submit(() -> server.getGlobalRegionScheduler().run(plugin, reported(GLOBAL_SOURCE, task)));
    }

    /**
     * Runs {@code task} on an async thread, as soon as possible. Returns {@code false} when the plugin is
     * not enabled (before {@code onEnable} or once it is disabling): the task will never run.
     */
    @CheckReturnValue
    public boolean async(Runnable task) {
        return submit(() -> server.getAsyncScheduler().runNow(plugin, reported(ASYNC_SOURCE, task)));
    }

    /**
     * Runs {@code task} on an async thread, as soon as possible, or on the calling thread when the plugin is
     * not enabled (before {@code onEnable} or once it is disabling) and cannot schedule. A call before enable,
     * from the plugin's constructor or {@code onLoad}, therefore runs the task on the calling thread. The task
     * may then block the calling thread, so it must be safe to run there. A task scheduled before the plugin
     * disables keeps running once started, but one that has not started when Paper cancels the plugin's tasks,
     * after {@code onDisable} returns, never runs.
     */
    public void asyncOrRun(Runnable task) {
        if (!async(task)) {
            task.run();
        }
    }

    /**
     * An executor over {@link #async}. Its {@code execute} runs the task on an async thread, or throws
     * {@link RejectedExecutionException} when the plugin is not enabled (before {@code onEnable} or once it is
     * disabling): the task will never run.
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
     * milliseconds. Returns {@code false} when the plugin is not enabled (before {@code onEnable} or once it is
     * disabling): the task will never run. Paper cancels the task when the plugin disables, so one not yet due
     * by then never runs either. Paper throws {@link IllegalArgumentException} for a negative delay.
     */
    @CheckReturnValue
    public boolean delayedAsync(Runnable task, Duration delay) {
        return submit(() -> server.getAsyncScheduler().runDelayed(
            plugin, reported(ASYNC_SOURCE, task), delay.toMillis(), TimeUnit.MILLISECONDS));
    }

    /**
     * Runs {@code task} on an async thread after {@code initialDelay}, then every {@code period} thereafter,
     * both passed to Paper in whole milliseconds, until {@link #close}. Returns {@code false} when the
     * plugin is not enabled (before {@code onEnable} or once it is disabling): the task is then not scheduled,
     * or is cancelled at once. Paper throws {@link IllegalArgumentException} for a negative initial delay or a
     * period below one millisecond. The task cannot be cancelled on its own: {@link #repeatingAsyncTask}
     * returns a handle that can.
     */
    @CheckReturnValue
    public boolean repeatingAsync(Runnable task, Duration initialDelay, Duration period) {
        return repeatingAsyncTask(task, initialDelay, period) != null;
    }

    /**
     * {@link #repeatingAsync} with a handle: runs {@code task} on an async thread after {@code initialDelay},
     * then every {@code period} thereafter, both passed to Paper in whole milliseconds, until the returned task
     * is cancelled or {@link #close} runs. Returns {@code null} when the plugin is not enabled (before
     * {@code onEnable} or once it is disabling): the task is then not scheduled, or is cancelled at once. Paper
     * throws {@link IllegalArgumentException} for a negative initial delay or a period below one millisecond.
     */
    public @Nullable ScheduledTask repeatingAsyncTask(Runnable task, Duration initialDelay, Duration period) {
        return repeating(() -> server.getAsyncScheduler().runAtFixedRate(
            plugin, reported(ASYNC_SOURCE, task), initialDelay.toMillis(), period.toMillis(),
            TimeUnit.MILLISECONDS));
    }

    /**
     * Runs {@code task} once on the global region thread after {@code delay}, in ticks as the class description
     * states. Returns {@code null} when the plugin is not enabled (before {@code onEnable} or once it is
     * disabling): the task will never run. Paper cancels the task when the plugin disables, so one not yet due
     * by then never runs either. What {@link #global} says about the thread on Folia holds here.
     */
    public @Nullable ScheduledTask delayedGlobal(Runnable task, Duration delay) {
        return scheduled(() -> server.getGlobalRegionScheduler().runDelayed(
            plugin, reported(GLOBAL_SOURCE, task), ticks(delay)));
    }

    /**
     * Runs {@code task} on the global region thread after {@code initialDelay}, then every {@code period}
     * thereafter, both in ticks as the class description states, until the returned task is cancelled or
     * {@link #close} runs. Returns {@code null} when the plugin is not enabled (before {@code onEnable} or once
     * it is disabling): the task is then not scheduled, or is cancelled at once. What {@link #global} says about
     * the thread on Folia holds here.
     */
    public @Nullable ScheduledTask repeatingGlobal(Runnable task, Duration initialDelay, Duration period) {
        return repeating(() -> server.getGlobalRegionScheduler().runAtFixedRate(
            plugin, reported(GLOBAL_SOURCE, task), ticks(initialDelay), ticks(period)));
    }

    /**
     * Runs {@code task} on the region thread owning the block of {@code location}, as soon as possible. On
     * Paper that is the main thread. Returns {@code null} when the plugin is not enabled (before
     * {@code onEnable} or once it is disabling): the task will never run.
     *
     * <p>The region is taken from the world and the block coordinates {@code location} has when the call is
     * made, so a later change to the {@link Location} does not move the task.
     *
     * @throws NullPointerException when {@code task} is {@code null} or {@code location} has no world, before
     *     anything is scheduled
     * @throws IllegalArgumentException when the world of {@code location} is unloaded, as
     *     {@link Location#getWorld()} throws it, before anything is scheduled
     */
    public @Nullable ScheduledTask region(Location location, Runnable task) {
        return scheduled(inRegion(location, task, (world, chunkX, chunkZ, run) ->
            server.getRegionScheduler().run(plugin, world, chunkX, chunkZ, run)));
    }

    /**
     * Runs {@code task} once on the region thread owning the block of {@code location} after {@code delay}, in
     * ticks as the class description states. Returns {@code null} when the plugin is not enabled (before
     * {@code onEnable} or once it is disabling): the task will never run. Paper cancels the task when the plugin
     * disables, so one not yet due by then never runs either. The region is taken as {@link #region} takes it.
     *
     * @throws NullPointerException when {@code task} is {@code null} or {@code location} has no world, before
     *     anything is scheduled
     * @throws IllegalArgumentException when the world of {@code location} is unloaded, as
     *     {@link Location#getWorld()} throws it, before anything is scheduled
     */
    public @Nullable ScheduledTask delayedRegion(Location location, Runnable task, Duration delay) {
        return scheduled(inRegion(location, task, (world, chunkX, chunkZ, run) ->
            server.getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ, run, ticks(delay))));
    }

    /**
     * Runs {@code task} on the region thread owning the block of {@code location} after {@code initialDelay},
     * then every {@code period} thereafter, both in ticks as the class description states, until the returned
     * task is cancelled or {@link #close} runs. Returns {@code null} when the plugin is not enabled (before
     * {@code onEnable} or once it is disabling): the task is then not scheduled, or is cancelled at once. The
     * region is taken as {@link #region} takes it.
     *
     * @throws NullPointerException when {@code task} is {@code null} or {@code location} has no world, before
     *     anything is scheduled
     * @throws IllegalArgumentException when the world of {@code location} is unloaded, as
     *     {@link Location#getWorld()} throws it, before anything is scheduled
     */
    public @Nullable ScheduledTask repeatingRegion(
        Location location, Runnable task, Duration initialDelay, Duration period) {
        return repeating(inRegion(location, task, (world, chunkX, chunkZ, run) ->
            server.getRegionScheduler().runAtFixedRate(
                plugin, world, chunkX, chunkZ, run, ticks(initialDelay), ticks(period))));
    }

    /**
     * Cancels every task {@link #repeatingAsync}, {@link #repeatingAsyncTask}, {@link #repeatingGlobal},
     * {@link #repeatingEntity} and {@link #repeatingRegion} started through this instance, whichever class
     * started it, and completes every future {@link #dispatch} returned whose task has not started, as a refused
     * call does: with {@link RejectedExecutionException} for {@link ThreadTag#GLOBAL} and {@link ThreadTag#ASYNC},
     * and with {@link EntityUnavailableException} for an entity tag. A task whose future is completed here never runs,
     * also when Paper still runs what it accepted. A run already in progress finishes, and the future of a
     * dispatched task that has started completes with what the task returns or throws. Other one-shot tasks are
     * left to Paper. The owning plugin calls this while it disables, at the place the order in the package
     * description gives it. The instance stays usable: a repeating task started, or a dispatch made, after the
     * call or while it runs is handled by the next call.
     */
    public void close() {
        // A task tracked during this pass is not in the snapshot being iterated and stays tracked.
        for (ScheduledTask task : repeating) {
            task.cancel();
            repeating.remove(task);
        }
        dispatches.forEach(Dispatch::refuse);
    }

    /**
     * Runs {@code task} on the region thread owning the entity. The task is dropped if the entity is gone or
     * the plugin is not enabled (before {@code onEnable} or once it is disabling).
     */
    public void entity(Entity entity, Runnable task) {
        entity(entity, task, NOTHING);
    }

    /**
     * Runs {@code task} on the region thread owning the entity, or {@code retired} when the task cannot run.
     * Paper runs {@code retired} if the entity retires (for example the player logs out) before the task
     * runs. When the entity is already removed, Paper runs neither, and when the plugin is not enabled (before
     * {@code onEnable} or once it is disabling) nothing can be scheduled: in both cases {@code retired} runs
     * here, on the calling thread, so it must not assume a region thread.
     *
     * <p>At most one of the two runs. A task still scheduled when the owning plugin disables runs neither,
     * so work that must settle is also tracked by its caller in {@link PendingSettlements}.
     *
     * @throws NullPointerException when {@code task} or {@code retired} is {@code null}, before anything is
     *     scheduled
     */
    public void entity(Entity entity, Runnable task, Runnable retired) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(retired, "retired");
        if (plugin.isEnabled()) {
            try {
                Runnable retiredByPaper = reporting(ENTITY_SOURCE, retired);
                // Paper returns null and runs neither callback when the entity is already removed.
                if (entity.getScheduler().run(plugin, reported(ENTITY_SOURCE, task), retiredByPaper) != null) {
                    return;
                }
            } catch (IllegalPluginAccessException disabling) {
                // The plugin began disabling after the check, so Paper scheduled nothing.
            }
        }
        retired.run();
    }

    /**
     * Runs {@code task} once on the region thread owning the entity after {@code delay}, in ticks as the class
     * description states, or {@code retired} when the task cannot run. Paper runs {@code retired} if the entity
     * retires (for example the player logs out) before the task runs. Returns {@code null} when the entity is
     * already removed, or when the plugin is not enabled (before {@code onEnable} or once it is disabling): in
     * both cases {@code retired} has run here, on the calling thread, so it must not assume a region thread.
     *
     * <p>At most one of the two runs. Paper cancels the task when the plugin disables, so one not yet due by
     * then runs neither, and nor does a task whose returned handle is cancelled.
     *
     * @throws NullPointerException when {@code task} or {@code retired} is {@code null}, before anything is
     *     scheduled
     */
    public @Nullable ScheduledTask delayedEntity(Entity entity, Runnable task, Runnable retired, Duration delay) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(retired, "retired");
        ScheduledTask added = scheduled(() -> entity.getScheduler().runDelayed(
            plugin, reported(ENTITY_SOURCE, task), reporting(ENTITY_SOURCE, retired), ticks(delay)));
        if (added == null) {
            retired.run();
        }
        return added;
    }

    /**
     * Runs {@code task} on the region thread owning the entity after {@code initialDelay}, then every
     * {@code period} thereafter, both in ticks as the class description states, until the returned task is
     * cancelled, the entity retires or {@link #close} runs. Paper runs {@code retired} once if the entity
     * retires (for example the player logs out) while the task repeats, and the task does not run again. Returns
     * {@code null} when the entity is already removed, or when the plugin is not enabled (before
     * {@code onEnable} or once it is disabling): the task is then not scheduled, or is cancelled at once, and
     * {@code retired} has run here, on the calling thread, so it must not assume a region thread.
     *
     * <p>{@code retired} runs at most once. A task that is cancelled, by its handle, by {@link #close} or by
     * Paper when the plugin disables, stops without it. So does a task whose own run removes the entity, for
     * example by {@link Entity#remove()} or by kicking the player: Paper stops the task after that run and runs
     * no callback, so such a task does itself what {@code retired} would do.
     *
     * @throws NullPointerException when {@code task} or {@code retired} is {@code null}, before anything is
     *     scheduled
     */
    public @Nullable ScheduledTask repeatingEntity(
        Entity entity, Runnable task, Runnable retired, Duration initialDelay, Duration period) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(retired, "retired");
        AtomicBoolean retiredRan = new AtomicBoolean();
        Runnable retiredByPaper = reporting(ENTITY_SOURCE, retired);
        ScheduledTask added = repeating(() -> entity.getScheduler().runAtFixedRate(
            plugin,
            reported(ENTITY_SOURCE, task),
            () -> {
                if (retiredRan.compareAndSet(false, true)) {
                    retiredByPaper.run();
                }
            },
            ticks(initialDelay),
            ticks(period)));
        if (added == null && retiredRan.compareAndSet(false, true)) {
            retired.run();
        }
        return added;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@link ThreadTag#GLOBAL} and {@link ThreadTag#ASYNC} complete with
     * {@link RejectedExecutionException} when the plugin is not enabled (before {@code onEnable} or once it is
     * disabling). An entity tag names an online player and completes with {@link EntityUnavailableException}
     * when the player is offline, retires before the task runs, or the plugin is not enabled. A task that has
     * not started when {@link #close} runs, or that Paper accepted as the plugin stopped being enabled, never
     * runs, and its future completes the same way. Every tag queues the task, also when the caller is already on
     * that thread.
     */
    @Override
    public <T> CompletableFuture<T> dispatch(ThreadTag tag, Supplier<T> task) {
        Dispatch<T> dispatch = new Dispatch<>(tag, task);
        CompletableFuture<T> result = dispatch.result;
        dispatches.add(dispatch);
        result.whenComplete((value, failure) -> dispatches.remove(dispatch));
        switch (tag) {
            case ThreadTag.Global _ -> {
                if (!global(dispatch)) {
                    dispatch.refuse();
                }
            }
            case ThreadTag.Async _ -> {
                if (!async(dispatch)) {
                    dispatch.refuse();
                }
            }
            case ThreadTag.Entity(UUID id) -> {
                Player player = server.getPlayer(id);
                if (player == null) {
                    dispatch.refuse();
                } else {
                    entity(player, dispatch, dispatch::refuse);
                }
            }
        }
        // close() may have finished its pass before the dispatch was tracked, so the dispatch is refused directly.
        if (!plugin.isEnabled()) {
            dispatch.refuse();
        }
        return result;
    }

    /** A dispatched task and its future. The task runs or the future is refused, never both. */
    private final class Dispatch<T> implements Runnable {

        private final ThreadTag tag;
        private final Supplier<T> task;
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private final AtomicBoolean claimed = new AtomicBoolean();

        Dispatch(ThreadTag tag, Supplier<T> task) {
            this.tag = tag;
            this.task = task;
        }

        @Override
        public void run() {
            if (!claimed.compareAndSet(false, true)) {
                return;
            }
            try {
                result.complete(task.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }

        void refuse() {
            if (claimed.compareAndSet(false, true)) {
                result.completeExceptionally(tag instanceof ThreadTag.Entity(UUID id)
                    ? new EntityUnavailableException(id)
                    : disabling(tag));
            }
        }
    }

    private Consumer<ScheduledTask> reported(String source, Runnable task) {
        Runnable run = reporting(source, task);
        return scheduled -> run.run();
    }

    /** {@code task} wrapped so that anything it throws is reported as unhandled, then rethrown unchanged. */
    private Runnable reporting(String source, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable failure) {
                errors.reportUnhandled(source, failure);
                throw failure;
            }
        };
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

    /** The task {@code submission} returns, or {@code null} when the plugin is not enabled. */
    private @Nullable ScheduledTask scheduled(Supplier<@Nullable ScheduledTask> submission) {
        if (!plugin.isEnabled()) {
            return null;
        }
        try {
            return submission.get();
        } catch (IllegalPluginAccessException disabling) {
            return null;
        }
    }

    /**
     * The repeating task {@code submission} returns, tracked for {@link #close}, or {@code null} when it returns
     * none or the plugin is not enabled. Each call drops the cancelled tasks from the tracked ones.
     */
    private @Nullable ScheduledTask repeating(Supplier<@Nullable ScheduledTask> submission) {
        ScheduledTask added = scheduled(submission);
        if (added == null) {
            return null;
        }
        repeating.removeIf(ScheduledTask::isCancelled);
        repeating.add(added);
        // close() may have finished its pass before the task was tracked, so the task is cancelled directly.
        if (!plugin.isEnabled()) {
            repeating.remove(added);
            added.cancel();
            repeating.forEach(ScheduledTask::cancel);
            return null;
        }
        return added;
    }

    private interface RegionSubmission {
        ScheduledTask submit(World world, int chunkX, int chunkZ, Consumer<ScheduledTask> run);
    }

    /** A submission of {@code task} to the region owning the block {@code location} names at this call. */
    private Supplier<@Nullable ScheduledTask> inRegion(Location location, Runnable task, RegionSubmission submission) {
        Objects.requireNonNull(task, "task");
        World world = Objects.requireNonNull(location.getWorld(), "location world");
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        return () -> submission.submit(world, chunkX, chunkZ, reported(REGION_SOURCE, task));
    }

    /** {@code duration} in server ticks of 50 ms, rounded up, and at least one. */
    static long ticks(Duration duration) {
        long seconds = duration.getSeconds();
        if (seconds < 0) {
            return 1;
        }
        if (seconds >= Long.MAX_VALUE / TICKS_PER_SECOND) {
            return Long.MAX_VALUE;
        }
        return Math.max(1, seconds * TICKS_PER_SECOND + Math.ceilDiv(duration.getNano(), NANOS_PER_TICK));
    }

    private RejectedExecutionException disabling(ThreadTag tag) {
        return new RejectedExecutionException(plugin.getName() + " is disabling, so " + tag + " accepts no work");
    }
}
