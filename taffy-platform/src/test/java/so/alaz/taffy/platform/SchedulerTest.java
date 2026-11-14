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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ParameterizedClass
@EnumSource(Scheduler.Mode.class)
final class SchedulerTest {

    private static final String PLUGIN_NAME = "Host";

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
        scheduler = new Scheduler(plugin, loader);
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
        Runnable retired = retiredRuns::incrementAndGet;
        ArgumentCaptor<Runnable> passed = ArgumentCaptor.forClass(Runnable.class);

        scheduler.entity(player, taskRuns::incrementAndGet, retired);

        verify(entityScheduler).run(eq(plugin), any(), passed.capture());
        assertThat(passed.getValue()).isSameAs(retired);
        assertThat(retiredRuns).hasValue(0);
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
}
