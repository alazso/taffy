package so.alaz.taffy.platform;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TaskGateTest {

    @Test
    void admitsTasksUntilClosed() {
        TaskGate gate = new TaskGate();
        assertThat(gate.enter()).isTrue();
        gate.exit();

        assertThat(gate.close(Duration.ZERO)).isZero();
        assertThat(gate.enter()).isFalse();
        assertThat(gate.enter()).isFalse();
        assertThat(gate.close(Duration.ZERO)).isZero();
    }

    @Test
    void closeWaitsForTheAdmittedTasks() throws Exception {
        TaskGate gate = new TaskGate();
        CountDownLatch finish = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            assertThat(gate.enter()).isTrue();
            Future<?> task = pool.submit(() -> {
                finish.await(30, TimeUnit.SECONDS);
                gate.exit();
                return null;
            });
            Future<Integer> closing = pool.submit(() -> gate.close(Duration.ofSeconds(30)));

            Thread.sleep(50);
            assertThat(closing.isDone()).isFalse();
            finish.countDown();
            task.get(30, TimeUnit.SECONDS);
            assertThat(closing.get(30, TimeUnit.SECONDS)).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void closeGivesUpAfterTheWait() {
        TaskGate gate = new TaskGate();
        assertThat(gate.enter()).isTrue();
        assertThat(gate.enter()).isTrue();

        long started = System.nanoTime();
        assertThat(gate.close(Duration.ofMillis(100))).isEqualTo(2);
        long waited = System.nanoTime() - started;
        assertThat(waited).isGreaterThanOrEqualTo(Duration.ofMillis(100).toNanos());
        assertThat(waited).isLessThan(Duration.ofSeconds(5).toNanos());
    }

    @Test
    void anInterruptEndsTheWaitAndStaysSet() {
        TaskGate gate = new TaskGate();
        assertThat(gate.enter()).isTrue();
        Thread.currentThread().interrupt();
        try {
            long started = System.nanoTime();
            assertThat(gate.close(Duration.ofSeconds(30))).isEqualTo(1);
            assertThat(System.nanoTime() - started).isLessThan(Duration.ofSeconds(5).toNanos());
        } finally {
            assertThat(Thread.interrupted()).isTrue();                  // also clears it for the next test
        }
    }

    @Test
    void anExitThatMatchesNoAdmittedTaskDoesNothing() {
        TaskGate gate = new TaskGate();
        gate.exit();

        assertThat(gate.enter()).isTrue();
        assertThat(gate.close(Duration.ZERO)).isEqualTo(1);
        gate.exit();
        gate.exit();

        assertThat(gate.close(Duration.ZERO)).isZero();
        assertThat(gate.enter()).isFalse();
    }

    @Test
    void aRefusedEntryIsNeverCountedAsRunning() throws Exception {
        TaskGate gate = new TaskGate();
        assertThat(gate.close(Duration.ZERO)).isZero();
        int callers = 4;
        AtomicBoolean stop = new AtomicBoolean();
        AtomicBoolean admitted = new AtomicBoolean();
        CyclicBarrier start = new CyclicBarrier(callers + 1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                runs.add(pool.submit(() -> {
                    start.await(30, TimeUnit.SECONDS);
                    while (!stop.get() && !Thread.currentThread().isInterrupted()) {
                        if (gate.enter()) {
                            admitted.set(true);
                        }
                    }
                    return null;
                }));
            }
            start.await(30, TimeUnit.SECONDS);
            int reportedRunning = 0;
            for (int i = 0; i < 20_000; i++) {
                if (gate.close(Duration.ZERO) != 0) {
                    reportedRunning++;
                }
            }
            stop.set(true);
            for (Future<?> run : runs) {
                run.get(30, TimeUnit.SECONDS);
            }
            assertThat(reportedRunning).isZero();
            assertThat(admitted).isFalse();
        } finally {
            stop.set(true);
            pool.shutdownNow();
        }
    }

    @Test
    void noAdmittedTaskIsRunningOnceCloseReportsNone() throws Exception {
        TaskGate gate = new TaskGate();
        int workers = 8;
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean admittedAfterClose = new AtomicBoolean();
        AtomicInteger admitted = new AtomicInteger();
        CyclicBarrier start = new CyclicBarrier(workers + 1);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                runs.add(pool.submit(() -> {
                    start.await(30, TimeUnit.SECONDS);
                    while (!Thread.currentThread().isInterrupted() && gate.enter()) {
                        try {
                            admitted.incrementAndGet();
                            if (closed.get()) {
                                admittedAfterClose.set(true);
                            }
                            Thread.onSpinWait();
                        } finally {
                            gate.exit();
                        }
                    }
                    return null;
                }));
            }
            start.await(30, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (admitted.get() < 1_000 && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertThat(gate.close(Duration.ofSeconds(30))).isZero();
            closed.set(true);
            pool.shutdown();
            boolean everyWorkerStopped = pool.awaitTermination(30, TimeUnit.SECONDS);
            assertThat(admittedAfterClose).isFalse();
            assertThat(everyWorkerStopped).isTrue();
            for (Future<?> run : runs) {
                run.get(30, TimeUnit.SECONDS);
            }
            assertThat(admitted.get()).isGreaterThanOrEqualTo(1_000);
        } finally {
            pool.shutdownNow();
        }
    }
}
