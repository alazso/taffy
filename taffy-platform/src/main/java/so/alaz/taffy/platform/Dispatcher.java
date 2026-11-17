package so.alaz.taffy.platform;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Runs a task on a named thread and reports its result, or its refusal, through a future. */
@FunctionalInterface
public interface Dispatcher {

    /**
     * Runs {@code task} on the thread {@code tag} names and completes the returned future with its result,
     * or with what it threw, on that thread. The future completes with {@link EntityUnavailableException}
     * or {@link java.util.concurrent.RejectedExecutionException}, and {@code task} never runs, when that
     * thread does not take it. A refusal for an entity tag is always an {@link EntityUnavailableException},
     * also when the cause is the owning plugin disabling.
     *
     * <p>An implementation may leave the future incomplete for good when the task was accepted and the
     * owning plugin disables before it runs. Work that must settle is therefore tracked in
     * {@link PendingSettlements} before it is dispatched, or the future is bounded with
     * {@link CompletableFuture#orTimeout}. When the timeout fires, a future bounded with {@code orTimeout}
     * completes on the JDK's timeout thread, and so does every dependent stage already added to it without an
     * executor.
     *
     * <p>The task may be queued even when the caller is already on the thread {@code tag} names, so waiting
     * for the future on a server thread can block that thread for good.
     */
    <T> CompletableFuture<T> dispatch(ThreadTag tag, Supplier<T> task);
}
