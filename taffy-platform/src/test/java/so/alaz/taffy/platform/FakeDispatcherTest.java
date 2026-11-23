package so.alaz.taffy.platform;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.ApiRules;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class FakeDispatcherTest {

    private final FakeDispatcher dispatcher = new FakeDispatcher();
    private final UUID id = UUID.randomUUID();
    private final AtomicInteger runs = new AtomicInteger();

    @Test
    void aTaskRunsOnTheCallingThreadAndItsTagIsRecorded() {
        CompletableFuture<Thread> global = dispatcher.dispatch(ThreadTag.GLOBAL, Thread::currentThread);
        CompletableFuture<String> async = dispatcher.dispatch(ThreadTag.ASYNC, () -> "read");
        CompletableFuture<String> entity = dispatcher.dispatch(ThreadTag.entity(id), () -> "given");

        assertThat(global).isCompletedWithValue(Thread.currentThread());
        assertThat(async).isCompletedWithValue("read");
        assertThat(entity).isCompletedWithValue("given");
        assertThat(dispatcher.tags()).containsExactly(ThreadTag.GLOBAL, ThreadTag.ASYNC, ThreadTag.entity(id));
    }

    @Test
    void theFutureCarriesWhatTheTaskThrewAnErrorIncluded() {
        IllegalStateException failure = new IllegalStateException("economy crashed");
        AssertionError error = new AssertionError("not reached");

        assertThatThrownBy(() -> dispatcher.dispatch(ThreadTag.GLOBAL, () -> {
            throw failure;
        }).join()).isInstanceOf(CompletionException.class).cause().isSameAs(failure);
        assertThatThrownBy(() -> dispatcher.dispatch(ThreadTag.ASYNC, () -> {
            throw error;
        }).join()).isInstanceOf(CompletionException.class).cause().isSameAs(error);
    }

    @Test
    void aNullTagOrTaskIsRefusedByNameBeforeTheTagIsRecorded() {
        assertThatThrownBy(() -> dispatcher.dispatch(null, () -> "never"))
            .isInstanceOf(NullPointerException.class).hasMessage("tag");
        assertThatThrownBy(() -> dispatcher.dispatch(ThreadTag.GLOBAL, null))
            .isInstanceOf(NullPointerException.class).hasMessage("task");
        assertThat(dispatcher.tags()).isEmpty();
    }

    @Test
    void theTagsAreACopyThatALaterDispatchDoesNotChange() {
        dispatcher.dispatch(ThreadTag.GLOBAL, runs::incrementAndGet);
        List<ThreadTag> before = dispatcher.tags();

        dispatcher.dispatch(ThreadTag.ASYNC, runs::incrementAndGet);

        assertThat(before).containsExactly(ThreadTag.GLOBAL);
        assertThatThrownBy(() -> before.add(ThreadTag.ASYNC)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void heldTasksWaitForRunHeldAndRunInDispatchOrder() {
        List<String> order = new ArrayList<>();
        dispatcher.hold();

        CompletableFuture<Boolean> first = dispatcher.dispatch(ThreadTag.ASYNC, () -> order.add("first"));
        CompletableFuture<Boolean> second = dispatcher.dispatch(ThreadTag.GLOBAL, () -> order.add("second"));

        assertThat(order).isEmpty();
        assertThat(first).isNotDone();
        assertThat(second).isNotDone();
        assertThat(dispatcher.held()).isEqualTo(2);
        assertThat(dispatcher.tags()).containsExactly(ThreadTag.ASYNC, ThreadTag.GLOBAL);

        dispatcher.runHeld();

        assertThat(order).containsExactly("first", "second");
        assertThat(first).isCompletedWithValue(true);
        assertThat(second).isCompletedWithValue(true);
        assertThat(dispatcher.held()).isZero();
    }

    @Test
    void runHeldEndsTheHoldAlsoForATaskAHeldTaskDispatches() {
        dispatcher.hold();
        CompletableFuture<CompletableFuture<Integer>> outer = dispatcher.dispatch(ThreadTag.GLOBAL,
            () -> dispatcher.dispatch(ThreadTag.ASYNC, runs::incrementAndGet));

        dispatcher.runHeld();

        assertThat(outer.join()).isCompletedWithValue(1);
        assertThat(dispatcher.dispatch(ThreadTag.GLOBAL, runs::incrementAndGet)).isCompletedWithValue(2);
        assertThat(dispatcher.held()).isZero();
    }

    @Test
    void aHeldTaskRunsOnceHoweverOftenRunHeldIsCalled() {
        dispatcher.hold();
        dispatcher.dispatch(ThreadTag.GLOBAL, runs::incrementAndGet);

        dispatcher.runHeld();
        dispatcher.runHeld();

        assertThat(runs).hasValue(1);
    }

    @Test
    void runHeldEndsAHoldThatHoldsNoTask() {
        dispatcher.hold();

        dispatcher.runHeld();

        assertThat(dispatcher.dispatch(ThreadTag.GLOBAL, runs::incrementAndGet)).isCompletedWithValue(1);
        assertThat(dispatcher.held()).isZero();
    }

    @Test
    void runHeldWithNoTaskHeldLeavesARefusingDispatcherRefusing() {
        dispatcher.refuse();

        dispatcher.runHeld();

        assertThat(dispatcher.dispatch(ThreadTag.GLOBAL, runs::incrementAndGet)).isCompletedExceptionally();
        assertThat(runs).hasValue(0);
    }

    @Test
    void aRefusedGlobalOrAsyncDispatchFailsWithRejectedExecutionAndNeverRuns() {
        dispatcher.refuse();

        CompletableFuture<Integer> global = dispatcher.dispatch(ThreadTag.GLOBAL, runs::incrementAndGet);
        CompletableFuture<Integer> async = dispatcher.dispatch(ThreadTag.ASYNC, runs::incrementAndGet);

        assertThatThrownBy(global::join).isInstanceOf(CompletionException.class)
            .cause().isInstanceOf(RejectedExecutionException.class).hasMessageContaining("GLOBAL");
        assertThatThrownBy(async::join).isInstanceOf(CompletionException.class)
            .cause().isInstanceOf(RejectedExecutionException.class).hasMessageContaining("ASYNC");
        assertThat(runs).hasValue(0);
        assertThat(dispatcher.tags()).containsExactly(ThreadTag.GLOBAL, ThreadTag.ASYNC);
    }

    @Test
    void aRefusedEntityDispatchFailsWithTheEntityUnavailable() {
        dispatcher.refuse();

        CompletableFuture<Integer> entity = dispatcher.dispatch(ThreadTag.entity(id), runs::incrementAndGet);

        assertThatThrownBy(entity::join).isInstanceOf(CompletionException.class)
            .cause().isInstanceOfSatisfying(EntityUnavailableException.class,
                unavailable -> assertThat(unavailable.id()).isEqualTo(id));
        assertThat(runs).hasValue(0);
    }

    @Test
    void refuseRefusesTheHeldTasksWhichThenNeverRun() {
        dispatcher.hold();
        CompletableFuture<Integer> global = dispatcher.dispatch(ThreadTag.GLOBAL, runs::incrementAndGet);
        CompletableFuture<Integer> entity = dispatcher.dispatch(ThreadTag.entity(id), runs::incrementAndGet);

        dispatcher.refuse();
        dispatcher.runHeld();

        assertThat(global).isCompletedExceptionally();
        assertThatThrownBy(global::join).hasCauseInstanceOf(RejectedExecutionException.class);
        assertThatThrownBy(entity::join).hasCauseInstanceOf(EntityUnavailableException.class);
        assertThat(runs).hasValue(0);
        assertThat(dispatcher.held()).isZero();
        assertThat(dispatcher.dispatch(ThreadTag.ASYNC, runs::incrementAndGet)).isCompletedExceptionally();
    }

    @Test
    void aDroppedDispatchIsAcceptedNeverRunsAndNeverCompletes() {
        dispatcher.drop();

        CompletableFuture<Integer> dropped = dispatcher.dispatch(ThreadTag.entity(id), runs::incrementAndGet);
        dispatcher.runHeld();

        assertThat(dropped).isNotDone();
        assertThat(runs).hasValue(0);
        assertThat(dispatcher.held()).isZero();
        assertThat(dispatcher.tags()).containsExactly(ThreadTag.entity(id));
        assertThat(dispatcher.dispatch(ThreadTag.GLOBAL, runs::incrementAndGet)).isNotDone();
    }

    @Test
    void dropForgetsTheHeldTasksWithoutCompletingThem() {
        dispatcher.hold();
        CompletableFuture<Integer> held = dispatcher.dispatch(ThreadTag.GLOBAL, runs::incrementAndGet);

        dispatcher.drop();
        dispatcher.runHeld();

        assertThat(held).isNotDone();
        assertThat(runs).hasValue(0);
        assertThat(dispatcher.held()).isZero();
    }

    @Test
    void theLastOfHoldRefuseAndDropDecidesWhatADispatchDoes() {
        dispatcher.refuse();
        dispatcher.drop();
        assertThat(dispatcher.dispatch(ThreadTag.GLOBAL, runs::incrementAndGet)).isNotDone();

        dispatcher.refuse();
        assertThat(dispatcher.dispatch(ThreadTag.GLOBAL, runs::incrementAndGet)).isCompletedExceptionally();

        dispatcher.hold();
        CompletableFuture<Integer> held = dispatcher.dispatch(ThreadTag.GLOBAL, runs::incrementAndGet);
        assertThat(held).isNotDone();
        dispatcher.runHeld();
        assertThat(held).isCompletedWithValue(1);
    }

    @Test
    void anEntityThreadOverTheFakeLooksThePlayerUpWhenTheHeldTaskRuns() {
        Server server = mock(Server.class);
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("Alex");
        EntityThread thread = EntityThread.on(dispatcher, server);
        dispatcher.hold();

        CompletableFuture<String> online = thread.call(id, Player::getName);
        when(server.getPlayer(id)).thenReturn(player);
        dispatcher.runHeld();
        CompletableFuture<String> offline = thread.call(UUID.randomUUID(), Player::getName);

        assertThat(online).isCompletedWithValue("Alex");
        assertThatThrownBy(offline::join).hasCauseInstanceOf(EntityUnavailableException.class);
    }

    @Test
    void theFixturesHoldOnlyTheFakeAndKeepTheDesignRules() throws Exception {
        assertThat(ApiRules.classes(FakeDispatcher.class))
            .contains(FakeDispatcher.class)
            .doesNotContain(Scheduler.class)
            .allSatisfy(type -> assertThat(type.getName()).startsWith(FakeDispatcher.class.getName()));
        assertThat(ApiRules.signatureViolations(FakeDispatcher.class)).isEmpty();
        assertThat(ApiRules.staticHolderViolations(FakeDispatcher.class)).isEmpty();
        assertThat(ApiRules.resourceFiles(FakeDispatcher.class)).isEmpty();
    }
}
