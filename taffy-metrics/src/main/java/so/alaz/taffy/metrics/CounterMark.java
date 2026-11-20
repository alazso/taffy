package so.alaz.taffy.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * One provider's reporting position on a monotonic counter. Each provider holds its own mark, so a
 * single running total feeds several providers and each reports only the increase since its own mark:
 * nothing is split between providers. An increase read with {@link #takeDelta()} for a submission that fails
 * is lost, and one read with {@link #peekDelta()} is reported again until {@link #commit()} runs.
 */
final class CounterMark {

    private final LongSupplier total;
    private final AtomicLong reported = new AtomicLong();
    /** The total this thread last read with {@link #peekDelta()} and the mark then, awaiting {@link #commit()}. */
    private final ThreadLocal<Read> pending = new ThreadLocal<>();

    CounterMark(LongSupplier total) {
        this.total = total;
    }

    /** The increase since the previous call, advancing the mark on read, for bStats (no success callback). */
    long takeDelta() {
        long value = total.getAsLong();
        return Math.max(0L, value - reported.getAndSet(value));
    }

    /**
     * The increase since the last commit, without advancing the mark. A read that is never committed
     * (a failed submission, or an error report assembling metric data) leaves the count for the next report.
     */
    long peekDelta() {
        long value = total.getAsLong();
        long mark = reported.get();
        pending.set(new Read(value, mark));
        return Math.max(0L, value - mark);
    }

    /**
     * Moves the mark to the total this thread last read with {@link #peekDelta()}. Call it on the
     * submitting thread once a submission is accepted; FastStats runs {@code onFlush} there, and a read
     * made on another thread in the meantime cannot move this thread's mark. When the mark is still the one
     * that read was made against, it takes the total read, so after a total that went down the next report
     * counts from the lower total. When another commit moved the mark since, it only ever moves forward.
     */
    void commit() {
        Read read = pending.get();
        if (read != null) {
            pending.remove();
            reported.accumulateAndGet(read.value,
                (current, value) -> current == read.mark ? value : Math.max(current, value));
        }
    }

    /** A total read by {@link #peekDelta()} and the mark at that read. */
    private record Read(long value, long mark) {
    }
}
