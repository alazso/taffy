package so.alaz.taffy.platform;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Server;
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
}
