package so.alaz.taffy.hooks;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * The outcome of an {@link EconomyProvider} withdraw or deposit. A call that throws has no result: its outcome is
 * unknown, and money may or may not have moved.
 *
 * @param status what happened
 * @param message the economy's reason, for {@link Status#FAILED}
 */
public record EconomyResult(Status status, @Nullable String message) {

    /**
     * What a withdraw or deposit did.
     */
    public enum Status {
        /** The money moved. */
        OK,
        /** The balance did not cover the amount, and no money moved. */
        INSUFFICIENT,
        /** The economy does not support this call, and no money moved. */
        UNSUPPORTED,
        /** The money moved and left the balance below zero. */
        OVERDRAWN,
        /** The economy refused the call, and no money moved. */
        FAILED,
        /** The withdraw was stopped after the balance check and before any money moved. */
        NOT_STARTED
    }

    /**
     * Creates a result.
     *
     * @throws NullPointerException if {@code status} is {@code null}
     * @throws IllegalArgumentException if a {@link Status#FAILED} result has no message or a blank one
     */
    public EconomyResult {
        Objects.requireNonNull(status, "status");
        if (status == Status.FAILED && (message == null || message.isBlank())) {
            throw new IllegalArgumentException("a FAILED result names its reason");
        }
    }

    /**
     * The money moved.
     *
     * @return the result
     */
    public static EconomyResult ok() {
        return new EconomyResult(Status.OK, null);
    }

    /**
     * A result with no message.
     *
     * @param status what happened; not {@link Status#FAILED}, which needs {@link #failed(String)}
     * @return the result
     * @throws IllegalArgumentException if {@code status} is {@link Status#FAILED}
     */
    public static EconomyResult of(Status status) {
        return new EconomyResult(status, null);
    }

    /**
     * The economy refused the call, and no money moved.
     *
     * @param message the economy's reason
     * @return the result
     * @throws IllegalArgumentException if {@code message} is {@code null} or blank
     */
    public static EconomyResult failed(String message) {
        return new EconomyResult(Status.FAILED, message);
    }
}
