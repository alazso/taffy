package so.alaz.taffy.comficonf;

import com.comficonf.api.Actor;
import com.comficonf.api.ApplyContext;
import com.comficonf.api.ChangeKind;
import com.comficonf.api.ConfigValues;
import com.comficonf.api.FieldChange;
import com.comficonf.api.ResourceId;
import com.comficonf.api.schema.ApplyMode;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An {@link ApplyContext} for the resource {@code taffy:test} whose {@code callSync} counts the calls and hands the
 * task to a {@link MainThread}, which runs it inline unless the test gives another.
 */
final class TestApplyContext implements ApplyContext {

    /** Stands for the server main thread that {@code callSync} hands its task to. */
    @FunctionalInterface
    interface MainThread {

        /** The task's result, or what the main thread throws instead. */
        Object run(Callable<?> task) throws Exception;
    }

    final AtomicInteger syncCalls = new AtomicInteger();
    private final String baseRevision;
    private final ConfigValues current;
    private final ConfigValues proposed;
    private final List<FieldChange> changes;
    private final MainThread mainThread;

    TestApplyContext(String baseRevision, ConfigValues current, ConfigValues proposed, List<FieldChange> changes) {
        this(baseRevision, current, proposed, changes, Callable::call);
    }

    private TestApplyContext(String baseRevision, ConfigValues current, ConfigValues proposed,
                             List<FieldChange> changes, MainThread mainThread) {
        this.baseRevision = baseRevision;
        this.current = current;
        this.proposed = proposed;
        this.changes = changes;
        this.mainThread = mainThread;
    }

    /** A context for one modified field, with the before value taken from {@code current}. */
    static TestApplyContext changing(String baseRevision, ConfigValues current, String path, Object value,
                                     ApplyMode mode) {
        Object before = current.get(path);
        FieldChange change = new FieldChange(path, ChangeKind.MODIFIED, mode, false, null, before, value);
        return new TestApplyContext(baseRevision, current, current.with(path, value), List.of(change));
    }

    /** The same context with {@code callSync} handing its task to {@code mainThread}, and no calls counted. */
    TestApplyContext onMainThread(MainThread mainThread) {
        return new TestApplyContext(baseRevision, current, proposed, changes, mainThread);
    }

    @Override
    public ResourceId resourceId() {
        return ResourceId.parse("taffy:test");
    }

    @Override
    public String operationId() {
        return UUID.randomUUID().toString();
    }

    @Override
    public String baseRevision() {
        return baseRevision;
    }

    @Override
    public ConfigValues current() {
        return current;
    }

    @Override
    public ConfigValues proposed() {
        return proposed;
    }

    @Override
    public List<FieldChange> changes() {
        return changes;
    }

    @Override
    public boolean requiresRestart() {
        return changes.stream().anyMatch(change -> change.applyMode() == ApplyMode.RESTART);
    }

    @Override
    public Actor actor() {
        return new Actor(UUID.randomUUID().toString(), "owner");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T callSync(Callable<T> task) throws Exception {
        syncCalls.incrementAndGet();
        return (T) mainThread.run(Objects.requireNonNull(task, "task"));
    }
}
