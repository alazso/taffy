package so.alaz.taffy.hooks;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * The outcome of an {@link EconomyProvider} withdraw or deposit. A call that throws has no result: its outcome is
 * unknown, and money may or may not have moved. {@link EconomyProvider#setBalance} gives one too: {@link Status#OK}
 * when the economy set the balance, for which {@link #moved()} is {@code true} also when the balance already was
 * the amount, {@link Status#UNSUPPORTED} when the economy cannot set a balance, and {@link Status#FAILED} when it
 * refused.
 *
 * @param status what happened
 * @param message the reason, for {@link Status#FAILED}
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
        /** The economy refused the call, or the plugin that registered it is disabled, and no money moved. */
        FAILED,
        /** {@code start} returned {@code false}, so the withdraw was stopped before any money moved. */
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
     * Whether the money moved: {@code true} for {@link Status#OK} and {@link Status#OVERDRAWN}, {@code false} for
     * every other status. An {@link Status#OVERDRAWN} withdraw is a completed payment, so the caller delivers what
     * was paid for; a caller that accepts no overdraft gives the amount back with
     * {@link EconomyProvider#deposit(java.util.UUID, java.math.BigDecimal)}.
     *
     * @return {@code true} if the amount left or reached the account
     */
    public boolean moved() {
        return switch (status) {
            case OK, OVERDRAWN -> true;
            case INSUFFICIENT, UNSUPPORTED, FAILED, NOT_STARTED -> false;
        };
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
     * The call was refused, and no money moved.
     *
     * @param message the reason
     * @return the result
     * @throws IllegalArgumentException if {@code message} is {@code null} or blank
     */
    public static EconomyResult failed(String message) {
        return new EconomyResult(Status.FAILED, message);
    }
}
