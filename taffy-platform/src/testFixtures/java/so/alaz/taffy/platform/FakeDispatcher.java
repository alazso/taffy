package so.alaz.taffy.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * A {@link Dispatcher} for tests that needs no server. It records the tag of every dispatch and, until told
 * otherwise, runs each task on the calling thread before {@link #dispatch} returns, so the returned future is
 * already complete. {@link Scheduler} queues every task instead, so code that only works when the task has run
 * by the time {@code dispatch} returns passes here and fails on a server: {@link #hold()} gives the queued order.
 *
 * <p>The dispatcher is in one of four states, and the last of {@link #hold()}, {@link #refuse()} and
 * {@link #drop()} called decides which:
 * <ul>
 *   <li>running, the state of a new instance: a task runs on the calling thread;
 *   <li>holding, after {@link #hold()}: a task is queued until {@link #runHeld()};
 *   <li>refusing, after {@link #refuse()}: a dispatch fails and its task never runs, as a {@link Scheduler} whose
 *       plugin is disabling refuses it;
 *   <li>dropping, after {@link #drop()}: a dispatch is accepted, its task never runs and its future never
 *       completes, which the {@link Dispatcher} contract allows for a task accepted before the owning plugin
 *       disables.
 * </ul>
 *
 * <p>A task runs or its future is refused, never both, and a task runs at most once. A future completes with
 * what its task returned or with whatever it threw, an {@link Error} included, on the thread that ran the task.
 * Nothing is caught and logged, and no thread is started.
 *
 * <p>An entity tag is recorded and treated like any other: no player is looked up, so
 * {@link EntityThread#on(Dispatcher, org.bukkit.Server)} over this dispatcher finds the player on the server it
 * was given once the task runs. Every method may be called from any thread.
 */
public final class FakeDispatcher implements Dispatcher {

    private enum State {
        RUNNING,
        HOLDING,
        REFUSING,
        DROPPING
    }

    private final Object lock = new Object();
    private final List<ThreadTag> tags = new ArrayList<>();
    private final List<Held<?>> held = new ArrayList<>();
    private State state = State.RUNNING;

    /** A dispatcher that runs each task on the calling thread. */
    public FakeDispatcher() {
    }

    /**
     * Records {@code tag}, then runs, holds, refuses or drops {@code task} as the class description states. A
     * refused dispatch returns a future completed with {@link RejectedExecutionException} for
     * {@link ThreadTag#GLOBAL} and {@link ThreadTag#ASYNC}, and with an {@link EntityUnavailableException}
     * carrying the tag's id for an entity tag.
     *
     * @throws NullPointerException when {@code tag} or {@code task} is {@code null}, before the tag is recorded
     */
    @Override
    public <T> CompletableFuture<T> dispatch(ThreadTag tag, Supplier<T> task) {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(task, "task");
        Held<T> dispatched = new Held<>(tag, task);
        State now;
        synchronized (lock) {
            tags.add(tag);
            now = state;
            if (now == State.HOLDING) {
                held.add(dispatched);
            }
        }
        switch (now) {
            case RUNNING -> dispatched.run();
            case REFUSING -> dispatched.refuse();
            case HOLDING, DROPPING -> {
            }
        }
        return dispatched.result;
    }

    /**
     * The tag of every dispatch made so far, in the order the calls were made, those refused, dropped or still
     * held included. The list is a copy that does not change.
     */
    public List<ThreadTag> tags() {
        synchronized (lock) {
            return List.copyOf(tags);
        }
    }

    /**
     * Queues every task dispatched from now until {@link #runHeld()}. A held task's future stays incomplete
     * while it is held.
     */
    public void hold() {
        synchronized (lock) {
            state = State.HOLDING;
        }
    }

    /** The number of tasks held now. */
    public int held() {
        synchronized (lock) {
            return held.size();
        }
    }

    /**
     * Runs the held tasks on the calling thread, in the order they were dispatched, and ends the hold: a task
     * dispatched from now runs on its caller's thread again, one dispatched by a held task included. With
     * no task held it runs nothing: it still ends a hold, and leaves a dispatcher that refuses or drops as it is.
     */
    public void runHeld() {
        List<Held<?>> runs;
        synchronized (lock) {
            if (state == State.HOLDING) {
                state = State.RUNNING;
            }
            runs = List.copyOf(held);
            held.clear();
        }
        runs.forEach(Held::run);
    }

    /**
     * Refuses every dispatch from now, and the tasks held now: none of them runs, and each future completes
     * with the exception {@link #dispatch} names, as {@link Scheduler#close()} completes the future of a task
     * that has not started.
     */
    public void refuse() {
        List<Held<?>> refused;
        synchronized (lock) {
            state = State.REFUSING;
            refused = List.copyOf(held);
            held.clear();
        }
        refused.forEach(Held::refuse);
    }

    /**
     * Accepts every dispatch from now without running its task or completing its future, and forgets the tasks
     * held now the same way: they never run and their futures never complete.
     */
    public void drop() {
        synchronized (lock) {
            state = State.DROPPING;
            held.clear();
        }
    }

    private static final class Held<T> {

        private final ThreadTag tag;
        private final Supplier<T> task;
        private final CompletableFuture<T> result = new CompletableFuture<>();

        Held(ThreadTag tag, Supplier<T> task) {
            this.tag = tag;
            this.task = task;
        }

        void run() {
            try {
                result.complete(task.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }

        void refuse() {
            result.completeExceptionally(tag instanceof ThreadTag.Entity(UUID id)
                ? new EntityUnavailableException(id)
                : new RejectedExecutionException("The fake dispatcher refuses, so " + tag + " accepts no work"));
        }
    }
}
