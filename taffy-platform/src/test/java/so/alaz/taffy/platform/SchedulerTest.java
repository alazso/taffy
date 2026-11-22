package so.alaz.taffy.platform;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import so.alaz.taffy.test.SimulatedClasspath;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.lang.reflect.AccessFlag;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ParameterizedClass
@EnumSource(Scheduler.Mode.class)
final class SchedulerTest {

    private static final String PLUGIN_NAME = "Host";

    private record Report(String source, Throwable error) {
    }

    private final List<Runnable> queued = new ArrayList<>();
    private final Plugin plugin = mock(Plugin.class);
    private final Server server = mock(Server.class);
    private final GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
    private final AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
    private final EntityScheduler entityScheduler = mock(EntityScheduler.class);
    private final RegionScheduler regionScheduler = mock(RegionScheduler.class);
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final ScheduledTask scheduled = mock(ScheduledTask.class);
    private final UUID playerId = UUID.randomUUID();
    private final AtomicInteger taskRuns = new AtomicInteger();
    private final AtomicInteger retiredRuns = new AtomicInteger();
    private final ErrorReports errors = new ErrorReports();
    private final List<Report> reports = new ArrayList<>();
    private final List<Report> handledReports = new ArrayList<>();
    private final Scheduler scheduler;

    SchedulerTest(Scheduler.Mode mode) {
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getName()).thenReturn(PLUGIN_NAME);
        when(plugin.isEnabled()).thenReturn(true);
        when(server.getGlobalRegionScheduler()).thenReturn(globalScheduler);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        when(server.getRegionScheduler()).thenReturn(regionScheduler);
        when(server.getPlayer(playerId)).thenReturn(player);
        when(player.getScheduler()).thenReturn(entityScheduler);
        when(globalScheduler.run(eq(plugin), any())).thenAnswer(this::enqueue);
        when(asyncScheduler.runNow(eq(plugin), any())).thenAnswer(this::enqueue);
        when(entityScheduler.run(eq(plugin), any(), any())).thenAnswer(this::enqueue);

        ClassLoader loader = mode == Scheduler.Mode.FOLIA
            ? SimulatedClasspath.with(Scheduler.FOLIA_MARKER)
            : SchedulerTest.class.getClassLoader();
        errors.install((source, error) -> handledReports.add(new Report(source, error)),
            (source, error) -> reports.add(new Report(source, error)));
        scheduler = new Scheduler(plugin, errors, loader);
        assertThat(scheduler.mode()).isEqualTo(mode);
    }

    private ScheduledTask enqueue(InvocationOnMock invocation) {
        Consumer<ScheduledTask> task = invocation.getArgument(1);
        queued.add(() -> task.accept(scheduled));
        return scheduled;
    }

    private ScheduledTask enqueueRegion(InvocationOnMock invocation) {
        Consumer<ScheduledTask> task = invocation.getArgument(4);
        queued.add(() -> task.accept(scheduled));
        return scheduled;
    }

    private void runQueued() {
        List<Runnable> tasks = List.copyOf(queued);
        queued.clear();
        tasks.forEach(Runnable::run);
    }

    /** The one task Paper received, as Paper runs it. */
    private Runnable onlyQueued() {
        assertThat(queued).hasSize(1);
        Runnable task = queued.getFirst();
        queued.clear();
        return task;
    }

    private static Runnable throwing(Throwable failure) {
        return () -> SchedulerTest.<RuntimeException>sneakyThrow(failure);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }

    /** Asserts that {@code failure} is the one report made, and that it was made as unhandled. */
    private void assertReportedOnce(String source, Throwable failure) {
        assertThat(reports).singleElement().satisfies(report -> {
            assertThat(report.source()).isEqualTo(source);
            assertThat(report.error()).isSameAs(failure);
        });
        assertThat(handledReports).isEmpty();
    }

    @Test
    void globalRunsOnlyWhenTheGlobalRegionSchedulerRunsIt() {
        assertThat(scheduler.global(taskRuns::incrementAndGet)).isTrue();

        assertThat(taskRuns).hasValue(0);
        verify(globalScheduler).run(eq(plugin), any());
        runQueued();
        assertThat(taskRuns).hasValue(1);
    }

    @Test
    void asyncRunsOnlyWhenTheAsyncSchedulerRunsIt() {
        assertThat(scheduler.async(taskRuns::incrementAndGet)).isTrue();

        assertThat(taskRuns).hasValue(0);
        verify(asyncScheduler).runNow(eq(plugin), any());
        runQueued();
        assertThat(taskRuns).hasValue(1);
    }

    @Test
    void entityRunsOnlyWhenTheEntitySchedulerRunsIt() {
        scheduler.entity(player, taskRuns::incrementAndGet);

        assertThat(taskRuns).hasValue(0);
        verify(entityScheduler).run(eq(plugin), any(), any());
        runQueued();
        assertThat(taskRuns).hasValue(1);
    }

    @Test
    void entityHandsRetiredToPaperForAnEntityThatRetiresLater() {
        ArgumentCaptor<Runnable> passed = ArgumentCaptor.forClass(Runnable.class);

        scheduler.entity(player, taskRuns::incrementAndGet, retiredRuns::incrementAndGet);

        verify(entityScheduler).run(eq(plugin), any(), passed.capture());
        assertThat(retiredRuns).hasValue(0);
        passed.getValue().run();
        assertThat(retiredRuns).hasValue(1);
        assertThat(taskRuns).hasValue(0);
    }

    @Test
    void aRemovedEntityRunsRetiredOnceAndNeverTheTask() {
        when(entityScheduler.run(eq(plugin), any(), any())).thenReturn(null);

        scheduler.entity(player, taskRuns::incrementAndGet, retiredRuns::incrementAndGet);

        assertThat(taskRuns).hasValue(0);
        assertThat(retiredRuns).hasValue(1);
    }

    @Test
    void aRemovedEntityIsSafeWithoutARetiredCallback() {
        when(entityScheduler.run(eq(plugin), any(), any())).thenReturn(null);

        scheduler.entity(player, taskRuns::incrementAndGet);

        assertThat(taskRuns).hasValue(0);
    }

    @Test
    void aDisabledPluginRunsRetiredWithoutTouchingTheEntity() {
        when(plugin.isEnabled()).thenReturn(false);

        scheduler.entity(player, taskRuns::incrementAndGet, retiredRuns::incrementAndGet);

        verify(player, never()).getScheduler();
        assertThat(retiredRuns).hasValue(1);
    }

    @Test
    void disablingDuringEntitySchedulingRunsRetired() {
        when(entityScheduler.run(eq(plugin), any(), any())).thenThrow(new IllegalPluginAccessException("disabled"));

        scheduler.entity(player, taskRuns::incrementAndGet, retiredRuns::incrementAndGet);

        assertThat(taskRuns).hasValue(0);
        assertThat(retiredRuns).hasValue(1);
    }

    @Test
    void aDisabledPluginRefusesGlobalAndAsyncWork() {
        when(plugin.isEnabled()).thenReturn(false);

        assertThat(scheduler.global(taskRuns::incrementAndGet)).isFalse();
        assertThat(scheduler.async(taskRuns::incrementAndGet)).isFalse();

        verify(globalScheduler, never()).run(any(), any());
        verify(asyncScheduler, never()).runNow(any(), any());
    }

    @Test
    void disablingDuringSchedulingRefusesGlobalAndAsyncWork() {
        when(globalScheduler.run(eq(plugin), any())).thenThrow(new IllegalPluginAccessException("disabled"));
        when(asyncScheduler.runNow(eq(plugin), any())).thenThrow(new IllegalPluginAccessException("disabled"));

        assertThat(scheduler.global(taskRuns::incrementAndGet)).isFalse();
        assertThat(scheduler.async(taskRuns::incrementAndGet)).isFalse();
    }

    @Test
    void asyncOrRunSchedulesOnTheAsyncSchedulerWhileEnabled() {
        scheduler.asyncOrRun(taskRuns::incrementAndGet);

        assertThat(taskRuns).hasValue(0);
        verify(asyncScheduler).runNow(eq(plugin), any());
        runQueued();
        assertThat(taskRuns).hasValue(1);
    }

    @Test
    void asyncOrRunRunsOnTheCallingThreadForADisabledPlugin() {
        when(plugin.isEnabled()).thenReturn(false);
        AtomicReference<Thread> taskThread = new AtomicReference<>();

        scheduler.asyncOrRun(() -> taskThread.set(Thread.currentThread()));

        assertThat(taskThread).hasValue(Thread.currentThread());
        verify(asyncScheduler, never()).runNow(any(), any());
    }

    @Test
    void asyncOrRunRunsOnTheCallingThreadWhenDisablingDuringScheduling() {
        when(asyncScheduler.runNow(eq(plugin), any())).thenThrow(new IllegalPluginAccessException("disabled"));

        scheduler.asyncOrRun(taskRuns::incrementAndGet);

        assertThat(taskRuns).hasValue(1);
        assertThat(queued).isEmpty();
    }

    @Test
    void asyncExecutorRunsOnlyWhenTheAsyncSchedulerRunsIt() {
        Executor executor = scheduler.asyncExecutor();

        executor.execute(taskRuns::incrementAndGet);

        assertThat(taskRuns).hasValue(0);
        verify(asyncScheduler).runNow(eq(plugin), any());
        runQueued();
        assertThat(taskRuns).hasValue(1);
    }

    @Test
    void asyncExecutorRejectsForADisabledPlugin() {
        when(plugin.isEnabled()).thenReturn(false);
        Executor executor = scheduler.asyncExecutor();

        assertThatThrownBy(() -> executor.execute(taskRuns::incrementAndGet))
            .isInstanceOf(RejectedExecutionException.class)
            .hasMessage(PLUGIN_NAME + " is disabling, so ASYNC accepts no work");
        assertThat(taskRuns).hasValue(0);
        verify(asyncScheduler, never()).runNow(any(), any());
    }

    @Test
    void asyncExecutorRejectsWhenDisablingDuringScheduling() {
        when(asyncScheduler.runNow(eq(plugin), any())).thenThrow(new IllegalPluginAccessException("disabled"));
        Executor executor = scheduler.asyncExecutor();

        assertThatThrownBy(() -> executor.execute(taskRuns::incrementAndGet))
            .isInstanceOf(RejectedExecutionException.class)
            .hasMessage(PLUGIN_NAME + " is disabling, so ASYNC accepts no work");
        assertThat(taskRuns).hasValue(0);
    }

    @Test
    void delayedAsyncPassesTheDelayInMilliseconds() {
        when(asyncScheduler.runDelayed(eq(plugin), any(), eq(1500L), eq(TimeUnit.MILLISECONDS)))
            .thenAnswer(this::enqueue);

        assertThat(scheduler.delayedAsync(taskRuns::incrementAndGet, Duration.ofMillis(1500))).isTrue();

        assertThat(taskRuns).hasValue(0);
        verify(asyncScheduler).runDelayed(eq(plugin), any(), eq(1500L), eq(TimeUnit.MILLISECONDS));
        runQueued();
        assertThat(taskRuns).hasValue(1);
    }

    @Test
    void aDisabledPluginRefusesDelayedWork() {
        when(plugin.isEnabled()).thenReturn(false);

        assertThat(scheduler.delayedAsync(taskRuns::incrementAndGet, Duration.ofSeconds(1))).isFalse();

        verify(asyncScheduler, never()).runDelayed(any(), any(), anyLong(), any());
    }

    @Test
    void disablingDuringSchedulingRefusesDelayedWork() {
        when(asyncScheduler.runDelayed(eq(plugin), any(), anyLong(), any()))
            .thenThrow(new IllegalPluginAccessException("disabled"));

        assertThat(scheduler.delayedAsync(taskRuns::incrementAndGet, Duration.ofSeconds(1))).isFalse();
        assertThat(taskRuns).hasValue(0);
    }

    @Test
    void repeatingAsyncUsesTheAsyncFixedRateScheduler() {
        when(asyncScheduler.runAtFixedRate(eq(plugin), any(), eq(500L), eq(1000L), eq(TimeUnit.MILLISECONDS)))
            .thenAnswer(this::enqueue);

        boolean accepted = scheduler.repeatingAsync(
            taskRuns::incrementAndGet, Duration.ofMillis(500), Duration.ofSeconds(1));

        assertThat(accepted).isTrue();
        verify(asyncScheduler).runAtFixedRate(eq(plugin), any(), eq(500L), eq(1000L), eq(TimeUnit.MILLISECONDS));
        verify(scheduled, never()).cancel();
        runQueued();
        assertThat(taskRuns).hasValue(1);
    }

    @Test
    void closeCancelsEveryRepeatingTaskOnce() {
        ScheduledTask first = mock(ScheduledTask.class);
        ScheduledTask second = mock(ScheduledTask.class);
        when(asyncScheduler.runAtFixedRate(eq(plugin), any(), anyLong(), anyLong(), any())).thenReturn(first, second);
        scheduler.repeatingAsync(taskRuns::incrementAndGet, Duration.ZERO, Duration.ofSeconds(1));
        scheduler.repeatingAsync(taskRuns::incrementAndGet, Duration.ZERO, Duration.ofSeconds(2));

        scheduler.close();
        scheduler.close();

        verify(first).cancel();
        verify(second).cancel();
    }

    @Test
    void aRepeatingTaskStartedWhileCloseRunsIsCancelledByTheNextClose() {
        ScheduledTask earlier = mock(ScheduledTask.class);
        ScheduledTask late = mock(ScheduledTask.class);
        when(asyncScheduler.runAtFixedRate(eq(plugin), any(), anyLong(), anyLong(), any())).thenReturn(earlier, late);
        scheduler.repeatingAsync(taskRuns::incrementAndGet, Duration.ZERO, Duration.ofSeconds(1));
        AtomicReference<Boolean> lateAccepted = new AtomicReference<>();
        // Stands in for another thread that starts a repeating task while close() is cancelling.
        doAnswer(call -> {
            lateAccepted.set(scheduler.repeatingAsync(taskRuns::incrementAndGet, Duration.ZERO, Duration.ofSeconds(1)));
            return null;
        }).when(earlier).cancel();

        scheduler.close();

        assertThat(lateAccepted).hasValue(true);
        verify(late, never()).cancel();

        scheduler.close();

        verify(late).cancel();
        verify(earlier).cancel();
    }

    @Test
    void closeLeavesOneShotTasksToPaper() {
        when(asyncScheduler.runDelayed(eq(plugin), any(), anyLong(), any())).thenAnswer(this::enqueue);
        scheduler.async(taskRuns::incrementAndGet);
        scheduler.delayedAsync(taskRuns::incrementAndGet, Duration.ofSeconds(1));

        scheduler.close();

        verify(scheduled, never()).cancel();
    }

    @Test
    void closeRefusesAnAcceptedButUnrunGlobalOrAsyncDispatch() {
        CompletableFuture<Integer> global = scheduler.dispatch(ThreadTag.GLOBAL, taskRuns::incrementAndGet);
        CompletableFuture<Integer> async = scheduler.dispatch(ThreadTag.ASYNC, taskRuns::incrementAndGet);
        when(plugin.isEnabled()).thenReturn(false);
        assertThat(global).isNotDone();
        assertThat(async).isNotDone();

        scheduler.close();

        assertThat(queued).hasSize(2);
        assertThat(global.exceptionNow()).isInstanceOf(RejectedExecutionException.class)
            .hasMessage(PLUGIN_NAME + " is disabling, so GLOBAL accepts no work");
        assertThat(async.exceptionNow()).isInstanceOf(RejectedExecutionException.class)
            .hasMessage(PLUGIN_NAME + " is disabling, so ASYNC accepts no work");
    }

    @Test
    void closeRefusesAnAcceptedButUnrunEntityDispatchAsUnavailable() {
        CompletableFuture<Integer> result = scheduler.dispatch(ThreadTag.entity(playerId), taskRuns::incrementAndGet);
        assertThat(result).isNotDone();

        scheduler.close();

        assertThat(result.exceptionNow()).isInstanceOf(EntityUnavailableException.class);
        assertThat(((EntityUnavailableException) result.exceptionNow()).id()).isEqualTo(playerId);
    }

    @Test
    void closeCompletesAnEntityThreadCallWhoseTaskNeverRan() {
        CompletableFuture<String> result = EntityThread.on(scheduler, server).call(playerId, Player::getName);
        assertThat(result).isNotDone();

        scheduler.close();

        assertThat(result.exceptionNow()).isInstanceOf(EntityUnavailableException.class);
        verify(player, never()).getName();
    }

    @Test
    void aDispatchRefusedByCloseNeverRunsWhenPaperRunsItLater() {
        CompletableFuture<Integer> result = scheduler.dispatch(ThreadTag.GLOBAL, taskRuns::incrementAndGet);

        scheduler.close();
        runQueued();

        assertThat(taskRuns).hasValue(0);
        assertThat(result.exceptionNow()).isInstanceOf(RejectedExecutionException.class);
        assertThat(reports).isEmpty();
    }

    @Test
    void closeLeavesADispatchThatHasStartedToCompleteWithItsResult() {
        CompletableFuture<String> result = scheduler.dispatch(ThreadTag.ASYNC, () -> {
            scheduler.close();
            return "written";
        });

        runQueued();

        assertThat(result).isCompletedWithValue("written");
    }

    @Test
    void closeLeavesACompletedDispatchAsItIs() {
        CompletableFuture<Integer> result = scheduler.dispatch(ThreadTag.GLOBAL, taskRuns::incrementAndGet);
        runQueued();

        scheduler.close();

        assertThat(result).isCompletedWithValue(1);
    }

    @Test
    void aDispatchPaperAcceptsAsThePluginStopsBeingEnabledIsRefused() {
        when(plugin.isEnabled()).thenReturn(true, false);

        CompletableFuture<Integer> result = scheduler.dispatch(ThreadTag.GLOBAL, taskRuns::incrementAndGet);
        runQueued();

        verify(globalScheduler).run(eq(plugin), any());
        assertThat(result.exceptionNow()).isInstanceOf(RejectedExecutionException.class);
        assertThat(taskRuns).hasValue(0);
    }

    @Test
    void aDisabledPluginRefusesRepeatingWork() {
        when(plugin.isEnabled()).thenReturn(false);

        assertThat(scheduler.repeatingAsync(taskRuns::incrementAndGet, Duration.ZERO, Duration.ofSeconds(1))).isFalse();

        verify(asyncScheduler, never()).runAtFixedRate(any(), any(), anyLong(), anyLong(), any());
    }

    @Test
    void disablingDuringSchedulingRefusesRepeatingWork() {
        when(asyncScheduler.runAtFixedRate(eq(plugin), any(), anyLong(), anyLong(), any()))
            .thenThrow(new IllegalPluginAccessException("disabled"));

        assertThat(scheduler.repeatingAsync(taskRuns::incrementAndGet, Duration.ZERO, Duration.ofSeconds(1))).isFalse();
        assertThat(taskRuns).hasValue(0);
    }

    @Test
    void aRepeatingTaskAddedAsThePluginStopsBeingEnabledIsCancelled() {
        ScheduledTask earlier = mock(ScheduledTask.class);
        ScheduledTask late = mock(ScheduledTask.class);
        when(asyncScheduler.runAtFixedRate(eq(plugin), any(), anyLong(), anyLong(), any())).thenReturn(earlier, late);
        scheduler.repeatingAsync(taskRuns::incrementAndGet, Duration.ZERO, Duration.ofSeconds(1));
        verify(earlier, never()).cancel();
        when(plugin.isEnabled()).thenReturn(true, false);

        boolean accepted = scheduler.repeatingAsync(taskRuns::incrementAndGet, Duration.ZERO, Duration.ofSeconds(1));

        assertThat(accepted).isFalse();
        verify(late).cancel();
        verify(earlier).cancel();
    }

    @Test
    void aRepeatingTaskTrackedBetweenTheCancelPassAndTheClearOfCloseIsCancelled() throws Exception {
        ScheduledTask earlier = mock(ScheduledTask.class);
        ScheduledTask late = mock(ScheduledTask.class);
        when(asyncScheduler.runAtFixedRate(eq(plugin), any(), anyLong(), anyLong(), any())).thenReturn(earlier, late);
        scheduler.repeatingAsync(taskRuns::incrementAndGet, Duration.ZERO, Duration.ofSeconds(1));
        CountDownLatch cancelPassStarted = new CountDownLatch(1);
        CountDownLatch lateTracked = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        // close() stays inside its cancel pass until the late task is tracked.
        doAnswer(call -> {
            cancelPassStarted.countDown();
            lateTracked.await(10, TimeUnit.SECONDS);
            return null;
        }).when(earlier).cancel();
        // The late call starts once the cancel pass runs, and checks the plugin again only after close() returned.
        when(plugin.isEnabled())
            .thenAnswer(call -> {
                cancelPassStarted.await(10, TimeUnit.SECONDS);
                return true;
            })
            .thenAnswer(call -> {
                lateTracked.countDown();
                closed.await(10, TimeUnit.SECONDS);
                return false;
            });

        CompletableFuture<Boolean> accepted = CompletableFuture.supplyAsync(
            () -> scheduler.repeatingAsync(taskRuns::incrementAndGet, Duration.ZERO, Duration.ofSeconds(1)));
        scheduler.close();
        closed.countDown();

        assertThat(accepted.get(10, TimeUnit.SECONDS)).isFalse();
        verify(late).cancel();
        verify(earlier).cancel();
    }

    @Test
    void dispatchToGlobalUsesTheGlobalRegionScheduler() {
        CompletableFuture<String> result = scheduler.dispatch(ThreadTag.GLOBAL, () -> "global");

        assertThat(result).isNotDone();
        verify(globalScheduler).run(eq(plugin), any());
        runQueued();
        assertThat(result).isCompletedWithValue("global");
    }

    @Test
    void dispatchToAsyncUsesTheAsyncScheduler() {
        CompletableFuture<String> result = scheduler.dispatch(ThreadTag.ASYNC, () -> "async");

        assertThat(result).isNotDone();
        verify(asyncScheduler).runNow(eq(plugin), any());
        runQueued();
        assertThat(result).isCompletedWithValue("async");
    }

    @Test
    void dispatchToEntityUsesThatPlayersScheduler() {
        CompletableFuture<String> result = scheduler.dispatch(ThreadTag.entity(playerId), () -> "entity");

        assertThat(result).isNotDone();
        verify(entityScheduler).run(eq(plugin), any(), any());
        runQueued();
        assertThat(result).isCompletedWithValue("entity");
    }

    @Test
    void dispatchRunsAndCompletesOnTheThreadThatRunsTheTask() throws Exception {
        AtomicReference<String> taskThread = new AtomicReference<>();
        AtomicReference<String> completionThread = new AtomicReference<>();
        CompletableFuture<Void> result = scheduler.dispatch(ThreadTag.entity(playerId), () -> {
            taskThread.set(Thread.currentThread().getName());
            return null;
        });
        CompletableFuture<Void> observed = result.thenRun(() -> completionThread.set(Thread.currentThread().getName()));

        assertThat(taskThread.get()).isNull();
        Thread region = new Thread(this::runQueued, "simulated-region");
        region.start();
        region.join();

        assertThat(observed).isCompleted();
        assertThat(taskThread.get()).isEqualTo("simulated-region");
        assertThat(completionThread.get()).isEqualTo("simulated-region");
    }

    @Test
    void dispatchCarriesATaskFailureInTheFuture() {
        IllegalStateException failure = new IllegalStateException("step failed");

        CompletableFuture<String> result = scheduler.dispatch(ThreadTag.GLOBAL, () -> {
            throw failure;
        });
        runQueued();

        assertThat(result).isCompletedExceptionally();
        assertThat(result.exceptionNow()).isSameAs(failure);
    }

    @Test
    void dispatchFailsWhileDisablingForGlobalAndAsync() {
        when(plugin.isEnabled()).thenReturn(false);

        CompletableFuture<String> global = scheduler.dispatch(ThreadTag.GLOBAL, () -> "global");
        CompletableFuture<String> async = scheduler.dispatch(ThreadTag.ASYNC, () -> "async");

        assertThat(global.exceptionNow()).isInstanceOf(RejectedExecutionException.class)
            .hasMessage(PLUGIN_NAME + " is disabling, so GLOBAL accepts no work");
        assertThat(async.exceptionNow()).isInstanceOf(RejectedExecutionException.class)
            .hasMessage(PLUGIN_NAME + " is disabling, so ASYNC accepts no work");
    }

    @Test
    void dispatchFailsWhileDisablingForAnOnlinePlayer() {
        when(plugin.isEnabled()).thenReturn(false);

        CompletableFuture<String> result = scheduler.dispatch(ThreadTag.entity(playerId), () -> "entity");

        assertThat(result.exceptionNow()).isInstanceOf(EntityUnavailableException.class);
        verify(entityScheduler, never()).run(any(), any(), any());
    }

    @Test
    void dispatchFailsForAnOfflinePlayer() {
        UUID offline = UUID.randomUUID();

        CompletableFuture<String> result = scheduler.dispatch(ThreadTag.entity(offline), () -> "entity");

        assertThat(result.exceptionNow()).isInstanceOf(EntityUnavailableException.class);
        assertThat(((EntityUnavailableException) result.exceptionNow()).id()).isEqualTo(offline);
    }

    @Test
    void dispatchFailsWhenThePlayerRetiresBeforeTheTaskRuns() {
        ArgumentCaptor<Runnable> retired = ArgumentCaptor.forClass(Runnable.class);

        CompletableFuture<String> result = scheduler.dispatch(ThreadTag.entity(playerId), () -> "entity");
        verify(entityScheduler).run(eq(plugin), any(), retired.capture());
        assertThat(result).isNotDone();
        retired.getValue().run();

        assertThat(result.exceptionNow()).isInstanceOf(EntityUnavailableException.class);
    }

    @Test
    void constructorRefusesANullPluginOrErrorReports() {
        assertThatNullPointerException().isThrownBy(() -> new Scheduler(null, errors)).withMessage("plugin");
        assertThatNullPointerException().isThrownBy(() -> new Scheduler(plugin, null)).withMessage("errors");
    }

    @Test
    void globalReportsAThrowingTaskOnceAndRethrowsIt() {
        IllegalStateException failure = new IllegalStateException("global failed");

        assertThat(scheduler.global(throwing(failure))).isTrue();
        assertThat(reports).isEmpty();

        assertThatThrownBy(onlyQueued()::run).isSameAs(failure);
        assertReportedOnce(Scheduler.GLOBAL_SOURCE, failure);
    }

    @Test
    void asyncReportsAThrowingTaskOnceAndRethrowsIt() {
        IllegalStateException failure = new IllegalStateException("async failed");

        assertThat(scheduler.async(throwing(failure))).isTrue();

        assertThatThrownBy(onlyQueued()::run).isSameAs(failure);
        assertReportedOnce(Scheduler.ASYNC_SOURCE, failure);
    }

    @Test
    void asyncReportsAThrownErrorOnceAndRethrowsIt() {
        StackOverflowError failure = new StackOverflowError("deep");

        scheduler.async(throwing(failure));

        assertThatThrownBy(onlyQueued()::run).isSameAs(failure);
        assertReportedOnce(Scheduler.ASYNC_SOURCE, failure);
    }

    @Test
    void asyncExecutorReportsAThrowingTaskOnceAndRethrowsIt() {
        IllegalStateException failure = new IllegalStateException("executor task failed");

        scheduler.asyncExecutor().execute(throwing(failure));

        assertThatThrownBy(onlyQueued()::run).isSameAs(failure);
        assertReportedOnce(Scheduler.ASYNC_SOURCE, failure);
    }

    @Test
    void asyncOrRunReportsAThrowingTaskPaperRunsOnceAndRethrowsIt() {
        IllegalStateException failure = new IllegalStateException("scheduled failed");

        scheduler.asyncOrRun(throwing(failure));

        assertThatThrownBy(onlyQueued()::run).isSameAs(failure);
        assertReportedOnce(Scheduler.ASYNC_SOURCE, failure);
    }

    @Test
    void delayedAsyncReportsAThrowingTaskOnceAndRethrowsIt() {
        when(asyncScheduler.runDelayed(eq(plugin), any(), anyLong(), any())).thenAnswer(this::enqueue);
        IllegalStateException failure = new IllegalStateException("delayed failed");

        assertThat(scheduler.delayedAsync(throwing(failure), Duration.ofSeconds(1))).isTrue();

        assertThatThrownBy(onlyQueued()::run).isSameAs(failure);
        assertReportedOnce(Scheduler.ASYNC_SOURCE, failure);
    }

    @Test
    void repeatingAsyncReportsEachThrowingRunOnceAndRethrowsIt() {
        AtomicReference<Consumer<ScheduledTask>> received = new AtomicReference<>();
        when(asyncScheduler.runAtFixedRate(eq(plugin), any(), anyLong(), anyLong(), any())).thenAnswer(call -> {
            received.set(call.getArgument(1));
            return scheduled;
        });
        IllegalStateException failure = new IllegalStateException("repeating failed");

        assertThat(scheduler.repeatingAsync(throwing(failure), Duration.ZERO, Duration.ofSeconds(1))).isTrue();

        assertThatThrownBy(() -> received.get().accept(scheduled)).isSameAs(failure);
        assertReportedOnce(Scheduler.ASYNC_SOURCE, failure);
        assertThatThrownBy(() -> received.get().accept(scheduled)).isSameAs(failure);
        assertThat(reports).hasSize(2).allSatisfy(report -> {
            assertThat(report.source()).isEqualTo(Scheduler.ASYNC_SOURCE);
            assertThat(report.error()).isSameAs(failure);
        });
    }

    @Test
    void entityReportsAThrowingTaskOnceAndRethrowsIt() {
        IllegalStateException failure = new IllegalStateException("entity failed");

        scheduler.entity(player, throwing(failure), retiredRuns::incrementAndGet);

        assertThatThrownBy(onlyQueued()::run).isSameAs(failure);
        assertReportedOnce(Scheduler.ENTITY_SOURCE, failure);
        assertThat(retiredRuns).hasValue(0);
    }

    @Test
    void entityReportsAThrowingRetiredCallbackPaperRunsOnceAndRethrowsIt() {
        IllegalStateException failure = new IllegalStateException("retired failed");
        ArgumentCaptor<Runnable> passed = ArgumentCaptor.forClass(Runnable.class);

        scheduler.entity(player, taskRuns::incrementAndGet, throwing(failure));
        verify(entityScheduler).run(eq(plugin), any(), passed.capture());

        assertThatThrownBy(passed.getValue()::run).isSameAs(failure);
        assertReportedOnce(Scheduler.ENTITY_SOURCE, failure);
        assertThat(taskRuns).hasValue(0);
    }

    @Test
    void aTaskThrowingAnUndeclaredCheckedExceptionIsReportedOnceAndRethrown() {
        Exception failure = new IOException("checked");

        scheduler.global(throwing(failure));

        assertThatThrownBy(onlyQueued()::run).isSameAs(failure);
        assertReportedOnce(Scheduler.GLOBAL_SOURCE, failure);
    }

    @Test
    void entityRefusesANullTaskOrRetiredBeforeSchedulingAnything() {
        assertThatNullPointerException()
            .isThrownBy(() -> scheduler.entity(player, null, retiredRuns::incrementAndGet))
            .withMessage("task");
        assertThatNullPointerException()
            .isThrownBy(() -> scheduler.entity(player, taskRuns::incrementAndGet, null))
            .withMessage("retired");
        assertThatNullPointerException()
            .isThrownBy(() -> scheduler.entity(player, null))
            .withMessage("task");

        verifyNoInteractions(entityScheduler);
        assertThat(reports).isEmpty();
    }

    @Test
    void tasksThatDoNotThrowReportNothing() {
        when(asyncScheduler.runDelayed(eq(plugin), any(), anyLong(), any())).thenAnswer(this::enqueue);
        when(asyncScheduler.runAtFixedRate(eq(plugin), any(), anyLong(), anyLong(), any())).thenAnswer(this::enqueue);
        ArgumentCaptor<Runnable> retired = ArgumentCaptor.forClass(Runnable.class);

        scheduler.global(taskRuns::incrementAndGet);
        scheduler.async(taskRuns::incrementAndGet);
        scheduler.asyncExecutor().execute(taskRuns::incrementAndGet);
        scheduler.asyncOrRun(taskRuns::incrementAndGet);
        scheduler.delayedAsync(taskRuns::incrementAndGet, Duration.ofSeconds(1));
        scheduler.repeatingAsync(taskRuns::incrementAndGet, Duration.ZERO, Duration.ofSeconds(1));
        scheduler.entity(player, taskRuns::incrementAndGet, retiredRuns::incrementAndGet);
        runQueued();
        verify(entityScheduler).run(eq(plugin), any(), retired.capture());
        retired.getValue().run();

        assertThat(taskRuns).hasValue(7);
        assertThat(retiredRuns).hasValue(1);
        assertThat(reports).isEmpty();
    }

    @Test
    void asyncOrRunOnTheCallingThreadPassesAThrowToTheCallerWithoutAReport() {
        when(plugin.isEnabled()).thenReturn(false);
        IllegalStateException failure = new IllegalStateException("inline failed");

        assertThatThrownBy(() -> scheduler.asyncOrRun(throwing(failure))).isSameAs(failure);
        assertThat(reports).isEmpty();
    }

    @Test
    void retiredOnTheCallingThreadPassesAThrowToTheCallerWithoutAReport() {
        IllegalStateException failure = new IllegalStateException("inline retired failed");
        when(entityScheduler.run(eq(plugin), any(), any())).thenReturn(null);

        assertThatThrownBy(() -> scheduler.entity(player, taskRuns::incrementAndGet, throwing(failure)))
            .isSameAs(failure);
        when(plugin.isEnabled()).thenReturn(false);
        assertThatThrownBy(() -> scheduler.entity(player, taskRuns::incrementAndGet, throwing(failure)))
            .isSameAs(failure);

        assertThat(reports).isEmpty();
        assertThat(taskRuns).hasValue(0);
    }

    @Test
    void dispatchCarriesAThrowInTheFutureWithoutAReport() {
        IllegalStateException failure = new IllegalStateException("dispatched failed");
        StackOverflowError error = new StackOverflowError("dispatched error");

        CompletableFuture<String> global = scheduler.dispatch(ThreadTag.GLOBAL, () -> {
            throw failure;
        });
        CompletableFuture<String> async = scheduler.dispatch(ThreadTag.ASYNC, () -> {
            throw error;
        });
        CompletableFuture<String> entity = scheduler.dispatch(ThreadTag.entity(playerId), () -> {
            throw failure;
        });
        runQueued();

        assertThat(global.exceptionNow()).isSameAs(failure);
        assertThat(async.exceptionNow()).isSameAs(error);
        assertThat(entity.exceptionNow()).isSameAs(failure);
        assertThat(reports).isEmpty();
    }

    @Test
    void dispatchToARetiringPlayerReportsNothing() {
        ArgumentCaptor<Runnable> retired = ArgumentCaptor.forClass(Runnable.class);

        CompletableFuture<String> result = scheduler.dispatch(ThreadTag.entity(playerId), () -> "entity");
        verify(entityScheduler).run(eq(plugin), any(), retired.capture());
        retired.getValue().run();

        assertThat(result.exceptionNow()).isInstanceOf(EntityUnavailableException.class);
        assertThat(reports).isEmpty();
    }

    @Test
    void repeatingAsyncTaskReturnsThePaperTaskOfTheAsyncFixedRateScheduler() {
        when(asyncScheduler.runAtFixedRate(eq(plugin), any(), eq(500L), eq(1000L), eq(TimeUnit.MILLISECONDS)))
            .thenAnswer(this::enqueue);

        ScheduledTask task = scheduler.repeatingAsyncTask(
            taskRuns::incrementAndGet, Duration.ofMillis(500), Duration.ofSeconds(1));

        assertThat(task).isSameAs(scheduled);
        verify(scheduled, never()).cancel();
        runQueued();
        assertThat(taskRuns).hasValue(1);
    }

    @Test
    void closeCancelsATaskStartedByEveryRepeatingMethod() {
        ScheduledTask async = mock(ScheduledTask.class);
        ScheduledTask global = mock(ScheduledTask.class);
        ScheduledTask entity = mock(ScheduledTask.class);
        ScheduledTask region = mock(ScheduledTask.class);
        when(asyncScheduler.runAtFixedRate(eq(plugin), any(), anyLong(), anyLong(), any())).thenReturn(async);
        when(globalScheduler.runAtFixedRate(eq(plugin), any(), anyLong(), anyLong())).thenReturn(global);
        when(entityScheduler.runAtFixedRate(eq(plugin), any(), any(), anyLong(), anyLong())).thenReturn(entity);
        when(regionScheduler.runAtFixedRate(eq(plugin), eq(world), anyInt(), anyInt(), any(), anyLong(), anyLong()))
            .thenReturn(region);
        Duration second = Duration.ofSeconds(1);
        assertThat(scheduler.repeatingAsyncTask(taskRuns::incrementAndGet, second, second)).isSameAs(async);
        assertThat(scheduler.repeatingGlobal(taskRuns::incrementAndGet, second, second)).isSameAs(global);
        assertThat(scheduler.repeatingEntity(player, taskRuns::incrementAndGet, retiredRuns::incrementAndGet,
            second, second)).isSameAs(entity);
        assertThat(scheduler.repeatingRegion(new Location(world, 0, 64, 0), taskRuns::incrementAndGet,
            second, second)).isSameAs(region);

        scheduler.close();
        scheduler.close();

        verify(async).cancel();
        verify(global).cancel();
        verify(entity).cancel();
        verify(region).cancel();
        assertThat(retiredRuns).hasValue(0);
    }

    @Test
    void aCancelledRepeatingTaskIsDroppedFromTheTrackedTasksOnTheNextAdd() {
        ScheduledTask cancelled = mock(ScheduledTask.class);
        ScheduledTask running = mock(ScheduledTask.class);
        when(asyncScheduler.runAtFixedRate(eq(plugin), any(), anyLong(), anyLong(), any())).thenReturn(cancelled);
        when(globalScheduler.runAtFixedRate(eq(plugin), any(), anyLong(), anyLong())).thenReturn(running);
        scheduler.repeatingAsyncTask(taskRuns::incrementAndGet, Duration.ZERO, Duration.ofSeconds(1));
        when(cancelled.isCancelled()).thenReturn(true);

        scheduler.repeatingGlobal(taskRuns::incrementAndGet, Duration.ofSeconds(1), Duration.ofSeconds(1));
        scheduler.close();

        verify(cancelled, never()).cancel();
        verify(running).cancel();
    }

    @Test
    void aDisabledPluginRefusesEveryTaskThatReturnsAHandle() {
        when(plugin.isEnabled()).thenReturn(false);
        Duration second = Duration.ofSeconds(1);
        Location location = new Location(world, 0, 64, 0);

        assertThat(scheduler.repeatingAsyncTask(taskRuns::incrementAndGet, second, second)).isNull();
        assertThat(scheduler.delayedGlobal(taskRuns::incrementAndGet, second)).isNull();
        assertThat(scheduler.repeatingGlobal(taskRuns::incrementAndGet, second, second)).isNull();
        assertThat(scheduler.region(location, taskRuns::incrementAndGet)).isNull();
        assertThat(scheduler.delayedRegion(location, taskRuns::incrementAndGet, second)).isNull();
        assertThat(scheduler.repeatingRegion(location, taskRuns::incrementAndGet, second, second)).isNull();

        verifyNoInteractions(asyncScheduler, globalScheduler, regionScheduler);
        assertThat(taskRuns).hasValue(0);
    }

    @Test
    void disablingDuringSchedulingRefusesEveryTaskThatReturnsAHandle() {
        IllegalPluginAccessException disabled = new IllegalPluginAccessException("disabled");
        when(asyncScheduler.runAtFixedRate(eq(plugin), any(), anyLong(), anyLong(), any())).thenThrow(disabled);
        when(globalScheduler.runDelayed(eq(plugin), any(), anyLong())).thenThrow(disabled);
        when(globalScheduler.runAtFixedRate(eq(plugin), any(), anyLong(), anyLong())).thenThrow(disabled);
        when(regionScheduler.run(eq(plugin), eq(world), anyInt(), anyInt(), any())).thenThrow(disabled);
        when(regionScheduler.runDelayed(eq(plugin), eq(world), anyInt(), anyInt(), any(), anyLong()))
            .thenThrow(disabled);
        when(regionScheduler.runAtFixedRate(eq(plugin), eq(world), anyInt(), anyInt(), any(), anyLong(), anyLong()))
            .thenThrow(disabled);
        Duration second = Duration.ofSeconds(1);
        Location location = new Location(world, 0, 64, 0);

        assertThat(scheduler.repeatingAsyncTask(taskRuns::incrementAndGet, second, second)).isNull();
        assertThat(scheduler.delayedGlobal(taskRuns::incrementAndGet, second)).isNull();
        assertThat(scheduler.repeatingGlobal(taskRuns::incrementAndGet, second, second)).isNull();
        assertThat(scheduler.region(location, taskRuns::incrementAndGet)).isNull();
        assertThat(scheduler.delayedRegion(location, taskRuns::incrementAndGet, second)).isNull();
        assertThat(scheduler.repeatingRegion(location, taskRuns::incrementAndGet, second, second)).isNull();

        assertThat(taskRuns).hasValue(0);
    }

    @Test
    void aRepeatingGlobalTaskAddedAsThePluginStopsBeingEnabledIsCancelledAndRefused() {
        ScheduledTask earlier = mock(ScheduledTask.class);
        ScheduledTask late = mock(ScheduledTask.class);
        when(globalScheduler.runAtFixedRate(eq(plugin), any(), anyLong(), anyLong())).thenReturn(earlier, late);
        scheduler.repeatingGlobal(taskRuns::incrementAndGet, Duration.ZERO, Duration.ofSeconds(1));
        when(plugin.isEnabled()).thenReturn(true, false);

        ScheduledTask task = scheduler.repeatingGlobal(taskRuns::incrementAndGet, Duration.ZERO, Duration.ofSeconds(1));

        assertThat(task).isNull();
        verify(late).cancel();
        verify(earlier).cancel();
    }

    @Test
    void delayedGlobalPassesTheDelayInTicksAndReturnsThePaperTask() {
        when(globalScheduler.runDelayed(eq(plugin), any(), eq(30L))).thenAnswer(this::enqueue);

        ScheduledTask task = scheduler.delayedGlobal(taskRuns::incrementAndGet, Duration.ofMillis(1500));

        assertThat(task).isSameAs(scheduled);
        assertThat(taskRuns).hasValue(0);
        runQueued();
        assertThat(taskRuns).hasValue(1);
        scheduler.close();
        verify(scheduled, never()).cancel();
    }

    @Test
    void repeatingGlobalPassesTheDelayAndThePeriodInTicks() {
        when(globalScheduler.runAtFixedRate(eq(plugin), any(), eq(1L), eq(3L))).thenAnswer(this::enqueue);

        ScheduledTask task = scheduler.repeatingGlobal(
            taskRuns::incrementAndGet, Duration.ZERO, Duration.ofMillis(101));

        assertThat(task).isSameAs(scheduled);
        runQueued();
        assertThat(taskRuns).hasValue(1);
    }

    @Test
    void aDurationIsRoundedUpToWholeTicksAndIsAtLeastOne() {
        assertThat(Scheduler.ticks(Duration.ZERO)).isEqualTo(1);
        assertThat(Scheduler.ticks(Duration.ofSeconds(-3))).isEqualTo(1);
        assertThat(Scheduler.ticks(Duration.ofMillis(-1))).isEqualTo(1);
        assertThat(Scheduler.ticks(Duration.ofNanos(1))).isEqualTo(1);
        assertThat(Scheduler.ticks(Duration.ofMillis(50))).isEqualTo(1);
        assertThat(Scheduler.ticks(Duration.ofMillis(50).plusNanos(1))).isEqualTo(2);
        assertThat(Scheduler.ticks(Duration.ofMillis(99))).isEqualTo(2);
        assertThat(Scheduler.ticks(Duration.ofSeconds(1))).isEqualTo(20);
        assertThat(Scheduler.ticks(Duration.ofSeconds(3).plusMillis(999))).isEqualTo(80);
        assertThat(Scheduler.ticks(Duration.ofSeconds(Long.MAX_VALUE))).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void delayedEntityPassesTheDelayInTicksAndHandsRetiredToPaper() {
        ArgumentCaptor<Runnable> passed = ArgumentCaptor.forClass(Runnable.class);
        when(entityScheduler.runDelayed(eq(plugin), any(), any(), eq(20L))).thenAnswer(this::enqueue);

        ScheduledTask task = scheduler.delayedEntity(
            player, taskRuns::incrementAndGet, retiredRuns::incrementAndGet, Duration.ofSeconds(1));

        assertThat(task).isSameAs(scheduled);
        verify(entityScheduler).runDelayed(eq(plugin), any(), passed.capture(), eq(20L));
        assertThat(retiredRuns).hasValue(0);
        runQueued();
        assertThat(taskRuns).hasValue(1);
        passed.getValue().run();
        assertThat(retiredRuns).hasValue(1);
    }

    @Test
    void aRemovedEntityRefusesADelayedOrRepeatingTaskAndRunsRetiredOnce() {
        Duration second = Duration.ofSeconds(1);

        assertThat(scheduler.delayedEntity(
            player, taskRuns::incrementAndGet, retiredRuns::incrementAndGet, second)).isNull();
        assertThat(retiredRuns).hasValue(1);
        assertThat(scheduler.repeatingEntity(
            player, taskRuns::incrementAndGet, retiredRuns::incrementAndGet, second, second)).isNull();

        assertThat(retiredRuns).hasValue(2);
        assertThat(taskRuns).hasValue(0);
        verify(entityScheduler).runDelayed(eq(plugin), any(), any(), eq(20L));
        verify(entityScheduler).runAtFixedRate(eq(plugin), any(), any(), eq(20L), eq(20L));
    }

    @Test
    void aDisabledPluginRefusesADelayedOrRepeatingEntityTaskAndRunsRetiredWithoutTouchingTheEntity() {
        when(plugin.isEnabled()).thenReturn(false);
        AtomicReference<Thread> retiredThread = new AtomicReference<>();
        Duration second = Duration.ofSeconds(1);

        assertThat(scheduler.delayedEntity(player, taskRuns::incrementAndGet,
            () -> retiredThread.set(Thread.currentThread()), second)).isNull();
        assertThat(scheduler.repeatingEntity(
            player, taskRuns::incrementAndGet, retiredRuns::incrementAndGet, second, second)).isNull();

        assertThat(retiredThread).hasValue(Thread.currentThread());
        assertThat(retiredRuns).hasValue(1);
        verify(player, never()).getScheduler();
    }

    @Test
    void disablingDuringSchedulingRefusesADelayedOrRepeatingEntityTaskAndRunsRetired() {
        IllegalPluginAccessException disabled = new IllegalPluginAccessException("disabled");
        when(entityScheduler.runDelayed(eq(plugin), any(), any(), anyLong())).thenThrow(disabled);
        when(entityScheduler.runAtFixedRate(eq(plugin), any(), any(), anyLong(), anyLong())).thenThrow(disabled);
        Duration second = Duration.ofSeconds(1);

        assertThat(scheduler.delayedEntity(
            player, taskRuns::incrementAndGet, retiredRuns::incrementAndGet, second)).isNull();
        assertThat(scheduler.repeatingEntity(
            player, taskRuns::incrementAndGet, retiredRuns::incrementAndGet, second, second)).isNull();

        assertThat(retiredRuns).hasValue(2);
        assertThat(taskRuns).hasValue(0);
    }

    @Test
    void repeatingEntityPassesTicksAndPaperRunsRetiredOnce() {
        ArgumentCaptor<Runnable> passed = ArgumentCaptor.forClass(Runnable.class);
        when(entityScheduler.runAtFixedRate(eq(plugin), any(), any(), eq(1L), eq(5L))).thenAnswer(this::enqueue);

        ScheduledTask task = scheduler.repeatingEntity(player, taskRuns::incrementAndGet,
            retiredRuns::incrementAndGet, Duration.ofMillis(10), Duration.ofMillis(250));

        assertThat(task).isSameAs(scheduled);
        verify(entityScheduler).runAtFixedRate(eq(plugin), any(), passed.capture(), eq(1L), eq(5L));
        runQueued();
        assertThat(taskRuns).hasValue(1);
        assertThat(retiredRuns).hasValue(0);
        passed.getValue().run();
        passed.getValue().run();
        assertThat(retiredRuns).hasValue(1);
    }

    @Test
    void aRepeatingEntityTaskAddedAsThePluginStopsBeingEnabledIsCancelledAndRunsRetiredOnce() {
        ArgumentCaptor<Runnable> passed = ArgumentCaptor.forClass(Runnable.class);
        when(entityScheduler.runAtFixedRate(eq(plugin), any(), any(), anyLong(), anyLong())).thenReturn(scheduled);
        when(plugin.isEnabled()).thenReturn(true, false);

        ScheduledTask task = scheduler.repeatingEntity(player, taskRuns::incrementAndGet,
            retiredRuns::incrementAndGet, Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertThat(task).isNull();
        verify(scheduled).cancel();
        assertThat(retiredRuns).hasValue(1);
        verify(entityScheduler).runAtFixedRate(eq(plugin), any(), passed.capture(), anyLong(), anyLong());
        passed.getValue().run();
        assertThat(retiredRuns).hasValue(1);
    }

    @Test
    void delayedAndRepeatingEntityRefuseANullTaskOrRetiredBeforeSchedulingAnything() {
        Duration second = Duration.ofSeconds(1);

        assertThatNullPointerException()
            .isThrownBy(() -> scheduler.delayedEntity(player, null, retiredRuns::incrementAndGet, second))
            .withMessage("task");
        assertThatNullPointerException()
            .isThrownBy(() -> scheduler.delayedEntity(player, taskRuns::incrementAndGet, null, second))
            .withMessage("retired");
        assertThatNullPointerException()
            .isThrownBy(() -> scheduler.repeatingEntity(player, null, retiredRuns::incrementAndGet, second, second))
            .withMessage("task");
        assertThatNullPointerException()
            .isThrownBy(() -> scheduler.repeatingEntity(player, taskRuns::incrementAndGet, null, second, second))
            .withMessage("retired");

        verifyNoInteractions(entityScheduler);
        assertThat(retiredRuns).hasValue(0);
    }

    @Test
    void regionRunsOnTheRegionOfTheChunkHoldingTheLocation() {
        when(regionScheduler.run(eq(plugin), eq(world), eq(2), eq(-1), any())).thenAnswer(this::enqueueRegion);

        ScheduledTask task = scheduler.region(new Location(world, 47.9, 64, -0.5), taskRuns::incrementAndGet);

        assertThat(task).isSameAs(scheduled);
        assertThat(taskRuns).hasValue(0);
        runQueued();
        assertThat(taskRuns).hasValue(1);
    }

    @Test
    void regionKeepsTheBlockTheLocationNamedAtTheCall() {
        Location location = new Location(world, 16, 64, 32);
        when(regionScheduler.run(eq(plugin), eq(world), eq(1), eq(2), any())).thenAnswer(call -> {
            location.setX(1600);
            return scheduled;
        });

        assertThat(scheduler.region(location, taskRuns::incrementAndGet)).isSameAs(scheduled);

        verify(regionScheduler).run(eq(plugin), eq(world), eq(1), eq(2), any());
    }

    @Test
    void delayedRegionPassesTheDelayInTicks() {
        when(regionScheduler.runDelayed(eq(plugin), eq(world), eq(-1), eq(0), any(), eq(40L)))
            .thenAnswer(this::enqueueRegion);

        ScheduledTask task = scheduler.delayedRegion(
            new Location(world, -1, 64, 15), taskRuns::incrementAndGet, Duration.ofSeconds(2));

        assertThat(task).isSameAs(scheduled);
        runQueued();
        assertThat(taskRuns).hasValue(1);
        scheduler.close();
        verify(scheduled, never()).cancel();
    }

    @Test
    void repeatingRegionPassesTheDelayAndThePeriodInTicks() {
        when(regionScheduler.runAtFixedRate(eq(plugin), eq(world), eq(0), eq(0), any(), eq(1L), eq(20L)))
            .thenAnswer(this::enqueueRegion);

        ScheduledTask task = scheduler.repeatingRegion(
            new Location(world, 0, 64, 0), taskRuns::incrementAndGet, Duration.ZERO, Duration.ofSeconds(1));

        assertThat(task).isSameAs(scheduled);
        runQueued();
        assertThat(taskRuns).hasValue(1);
    }

    @Test
    void theRegionTasksRefuseANullTaskOrALocationWithNoWorldBeforeSchedulingAnything() {
        Location nowhere = new Location(null, 0, 64, 0);
        Location somewhere = new Location(world, 0, 64, 0);
        Duration second = Duration.ofSeconds(1);

        assertThatNullPointerException()
            .isThrownBy(() -> scheduler.region(nowhere, taskRuns::incrementAndGet))
            .withMessage("location world");
        assertThatNullPointerException()
            .isThrownBy(() -> scheduler.delayedRegion(nowhere, taskRuns::incrementAndGet, second))
            .withMessage("location world");
        assertThatNullPointerException()
            .isThrownBy(() -> scheduler.repeatingRegion(nowhere, taskRuns::incrementAndGet, second, second))
            .withMessage("location world");
        assertThatNullPointerException().isThrownBy(() -> scheduler.region(somewhere, null)).withMessage("task");
        assertThatNullPointerException()
            .isThrownBy(() -> scheduler.delayedRegion(somewhere, null, second))
            .withMessage("task");
        assertThatNullPointerException()
            .isThrownBy(() -> scheduler.repeatingRegion(somewhere, null, second, second))
            .withMessage("task");

        verifyNoInteractions(regionScheduler);
    }

    @Test
    void theRegionTasksPassOnWhatALocationThrowsForAnUnloadedWorldBeforeSchedulingAnything() {
        IllegalArgumentException unloaded = new IllegalArgumentException("World unloaded");
        Location stale = mock(Location.class);
        when(stale.getWorld()).thenThrow(unloaded);
        Duration second = Duration.ofSeconds(1);

        assertThatThrownBy(() -> scheduler.region(stale, taskRuns::incrementAndGet)).isSameAs(unloaded);
        assertThatThrownBy(() -> scheduler.delayedRegion(stale, taskRuns::incrementAndGet, second))
            .isSameAs(unloaded);
        assertThatThrownBy(() -> scheduler.repeatingRegion(stale, taskRuns::incrementAndGet, second, second))
            .isSameAs(unloaded);

        verifyNoInteractions(regionScheduler);
        assertThat(reports).isEmpty();
    }

    @Test
    void aRegionTaskReportsAThrowOnceUnderTheRegionSourceAndRethrowsIt() {
        when(regionScheduler.run(eq(plugin), eq(world), anyInt(), anyInt(), any())).thenAnswer(this::enqueueRegion);
        IllegalStateException failure = new IllegalStateException("region failed");

        scheduler.region(new Location(world, 0, 64, 0), throwing(failure));

        assertThatThrownBy(onlyQueued()::run).isSameAs(failure);
        assertReportedOnce(Scheduler.REGION_SOURCE, failure);
    }

    @Test
    void aDelayedGlobalTaskReportsAThrowOnceUnderTheGlobalSourceAndRethrowsIt() {
        when(globalScheduler.runDelayed(eq(plugin), any(), anyLong())).thenAnswer(this::enqueue);
        IllegalStateException failure = new IllegalStateException("delayed global failed");

        scheduler.delayedGlobal(throwing(failure), Duration.ofSeconds(1));

        assertThatThrownBy(onlyQueued()::run).isSameAs(failure);
        assertReportedOnce(Scheduler.GLOBAL_SOURCE, failure);
    }

    @Test
    void aDelayedEntityTaskAndARetiredCallbackPaperRunsReportUnderTheEntitySource() {
        when(entityScheduler.runDelayed(eq(plugin), any(), any(), anyLong())).thenAnswer(this::enqueue);
        ArgumentCaptor<Runnable> passed = ArgumentCaptor.forClass(Runnable.class);
        IllegalStateException failure = new IllegalStateException("delayed entity failed");
        IllegalStateException retiredFailure = new IllegalStateException("retired failed");

        scheduler.delayedEntity(player, throwing(failure), throwing(retiredFailure), Duration.ofSeconds(1));
        verify(entityScheduler).runDelayed(eq(plugin), any(), passed.capture(), anyLong());

        assertThatThrownBy(onlyQueued()::run).isSameAs(failure);
        assertReportedOnce(Scheduler.ENTITY_SOURCE, failure);
        assertThatThrownBy(passed.getValue()::run).isSameAs(retiredFailure);
        assertThat(reports).hasSize(2).last().satisfies(report -> {
            assertThat(report.source()).isEqualTo(Scheduler.ENTITY_SOURCE);
            assertThat(report.error()).isSameAs(retiredFailure);
        });
    }

    @Test
    void retiredOfARefusedDelayedOrRepeatingEntityTaskPassesAThrowToTheCallerWithoutAReport() {
        IllegalStateException failure = new IllegalStateException("inline retired failed");
        Duration second = Duration.ofSeconds(1);

        assertThatThrownBy(() -> scheduler.delayedEntity(player, taskRuns::incrementAndGet, throwing(failure), second))
            .isSameAs(failure);
        assertThatThrownBy(() -> scheduler.repeatingEntity(
            player, taskRuns::incrementAndGet, throwing(failure), second, second)).isSameAs(failure);

        assertThat(reports).isEmpty();
    }

    @Test
    void theMethodsThatReportARefusalAsABooleanAreMarkedCheckReturnValue() throws IOException {
        List<String> marked = new ArrayList<>();
        List<String> unmarked = new ArrayList<>();
        try (InputStream file = Scheduler.class.getResourceAsStream("Scheduler.class")) {
            for (MethodModel method : ClassFile.of().parse(file.readAllBytes()).methods()) {
                boolean checked = method.findAttribute(Attributes.runtimeInvisibleAnnotations()).stream()
                    .flatMap(attribute -> attribute.annotations().stream())
                    .anyMatch(annotation -> annotation.classSymbol().descriptorString()
                        .equals("Lorg/jetbrains/annotations/CheckReturnValue;"));
                if (method.flags().has(AccessFlag.PUBLIC)) {
                    (checked ? marked : unmarked).add(method.methodName().stringValue());
                }
            }
        }

        assertThat(marked).containsExactlyInAnyOrder("global", "async", "delayedAsync", "repeatingAsync");
        assertThat(unmarked).contains("repeatingAsyncTask", "delayedGlobal", "repeatingGlobal", "delayedEntity",
            "repeatingEntity", "region", "delayedRegion", "repeatingRegion");
    }
}
