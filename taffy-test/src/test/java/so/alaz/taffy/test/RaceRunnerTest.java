package so.alaz.taffy.test;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RaceRunnerTest {

    @Test
    void resultsComeBackInContenderOrderWhateverOrderTheContendersFinishIn() throws Exception {
        int contenders = 4;
        List<CountDownLatch> finished = new ArrayList<>();
        for (int index = 0; index < contenders; index++) {
            finished.add(new CountDownLatch(1));
        }
        List<Integer> finishOrder = Collections.synchronizedList(new ArrayList<>());

        // Each contender waits for the one after it, so they finish last to first.
        List<String> results = RaceRunner.race(contenders, id -> {
            if (id + 1 < contenders) {
                await(finished.get(id + 1));
            }
            finishOrder.add(id);
            finished.get(id).countDown();
            return "contender " + id;
        });

        assertThat(results).containsExactly("contender 0", "contender 1", "contender 2", "contender 3");
        assertThat(finishOrder).containsExactly(3, 2, 1, 0);
    }

    @Test
    void contendersRunAtTheSameTimeEachOnItsOwnVirtualThread() throws Exception {
        int contenders = 8;
        // Trips only when every contender is inside its work at once.
        CyclicBarrier together = new CyclicBarrier(contenders);

        List<Thread> threads = RaceRunner.race(contenders, id -> {
            try {
                together.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("the contenders did not run together", e);
            }
            return Thread.currentThread();
        });

        assertThat(threads).hasSize(contenders).doesNotHaveDuplicates().doesNotContain(Thread.currentThread());
        assertThat(threads).allSatisfy(thread -> assertThat(thread.isVirtual()).isTrue());
    }

    @Test
    void aContenderThatThrowsFailsTheRaceAndAContenderStillRunningIsInterrupted() throws Exception {
        IllegalArgumentException failure = new IllegalArgumentException("refused");
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        assertThatThrownBy(() -> RaceRunner.race(2, id -> {
            if (id == 0) {
                await(blocked);
                throw failure;
            }
            blocked.countDown();
            try {
                never.await();
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return id;
        })).isExactlyInstanceOf(ExecutionException.class).cause().isSameAs(failure);

        assertThat(interrupted.await(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void zeroContendersGiveAnEmptyListAndTheWorkIsNeverCalled() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        List<Integer> results = RaceRunner.race(0, id -> calls.incrementAndGet());

        assertThat(results).isEmpty();
        assertThat(calls).hasValue(0);
    }

    @Test
    void aNegativeNumberOfContendersIsRefusedAndTheWorkIsNeverCalled() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> RaceRunner.race(-1, id -> calls.incrementAndGet()))
            .isExactlyInstanceOf(IllegalArgumentException.class);

        assertThat(calls).hasValue(0);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the latch never opened");
            }
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
