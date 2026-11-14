package so.alaz.taffy.platform;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Admits async tasks until the owning plugin disables, and lets the disabling thread wait, for a bounded
 * time, for the tasks already admitted. A task enters before it does any work and exits when done.
 * Once {@link #close} begins, every later entry is refused, so a refused task leaves nothing to settle,
 * and once the admitted tasks have exited, everything they left to settle is tracked. The owning plugin
 * therefore closes the gate before it calls {@link PendingSettlements#settleAll}.
 *
 * <p>Thread-safe: tasks enter and exit on any thread.
 */
public final class TaskGate {

    /** How long {@link #close} sleeps between checks for admitted tasks still running. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(10);

    /** The bit of {@link #state} that is set once {@link #close} has begun. */
    private static final int CLOSED = Integer.MIN_VALUE;

    /** The closed bit and, in the remaining bits, the number of admitted tasks that have not exited. */
    private final AtomicInteger state = new AtomicInteger();

    /**
     * Admits a task, or returns {@code false} once {@link #close} has begun, when the task must do nothing.
     * An admitted task must call {@link #exit} when done, in a finally.
     */
    public boolean enter() {
        // One atomic step reads the closed bit and counts the task, so a refused call never shows in the count.
        int current;
        do {
            current = state.get();
            if (current < 0) {
                return false;
            }
        } while (!state.compareAndSet(current, current + 1));
        return true;
    }

    /** Marks an admitted task done. A call that matches no admitted task does nothing. */
    public void exit() {
        state.updateAndGet(current -> running(current) == 0 ? current : current - 1);
    }

    /**
     * Refuses every later entry, then waits up to {@code wait} for the admitted tasks to exit. Returns how
     * many are still running: {@code 0} once all have exited. An interrupt ends the wait early and stays set.
     *
     * <p>A result above {@code 0} means a task may still track work after this call, which a
     * {@link PendingSettlements#settleAll} that follows does not see.
     */
    public int close(Duration wait) {
        state.getAndUpdate(current -> current | CLOSED);
        long deadline = System.nanoTime() + Math.max(0, wait.toNanos());
        int remaining;
        while ((remaining = running(state.get())) > 0) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return remaining;
            }
            try {
                Thread.sleep(Duration.ofNanos(Math.min(left, POLL_INTERVAL.toNanos())));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return running(state.get());
            }
        }
        return 0;
    }

    private static int running(int state) {
        return state & ~CLOSED;
    }
}
