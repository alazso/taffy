package so.alaz.taffy.test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/** Runs several contenders at the same moment, to test code that must hold when calls race. */
public final class RaceRunner {

    private RaceRunner() {
    }

    /**
     * Starts one virtual thread per contender, holds them all at a gate until every one has started, releases them
     * together and returns each contender's result in contender order.
     *
     * <p>The threads belong to this call. Any that still run when it returns or throws are interrupted.
     *
     * @param contenders the number of contenders
     * @param contender the work of one contender, given its index from 0 to {@code contenders - 1}
     * @return the result of contender 0, then of contender 1, and so on
     * @throws IllegalArgumentException if {@code contenders} is negative
     * @throws java.util.concurrent.ExecutionException if a contender throws, with what it threw as the cause
     * @throws IllegalStateException if the contenders have not all started after 10 seconds
     * @throws java.util.concurrent.TimeoutException if a contender has no result 30 seconds after the ones before
     *     it gave theirs
     * @throws InterruptedException if the calling thread is interrupted while it waits
     */
    public static <T> List<T> race(int contenders, IntFunction<T> contender) throws Exception {
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<T>> running = new ArrayList<>();
            for (int index = 0; index < contenders; index++) {
                int id = index;
                running.add(threads.submit(() -> {
                    ready.countDown();
                    // The caller opens the gate or, when it throws, interrupts this wait through shutdownNow.
                    gate.await();
                    return contender.apply(id);
                }));
            }
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the contenders did not all start");
            }
            gate.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> result : running) {
                results.add(result.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            threads.shutdownNow();
        }
    }
}
