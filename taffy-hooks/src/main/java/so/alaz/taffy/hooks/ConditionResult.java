package so.alaz.taffy.hooks;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

/**
 * The outcome of a {@link Condition}: passed, or denied with the reason to show the player.
 *
 * @param passed whether the player met the condition
 * @param reason the message for the player, rendered for that player, or {@code null} for a result that passed
 * @since 0.4.14
 */
public record ConditionResult(boolean passed, @Nullable Component reason) {

    private static final ConditionResult PASS = new ConditionResult(true, null);

    /**
     * Creates a result.
     *
     * @throws IllegalArgumentException if a result that passed carries a reason, or a denied one carries none
     * @since 0.4.14
     */
    public ConditionResult {
        if (passed && reason != null) {
            throw new IllegalArgumentException("a result that passed has no reason");
        }
        if (!passed && reason == null) {
            throw new IllegalArgumentException("a denied result names its reason");
        }
    }

    /**
     * The player met the condition.
     *
     * @return the result
     * @since 0.4.14
     */
    public static ConditionResult pass() {
        return PASS;
    }

    /**
     * The player did not meet the condition.
     *
     * @param reason the message for the player
     * @return the result
     * @throws IllegalArgumentException if {@code reason} is {@code null}
     * @since 0.4.14
     */
    public static ConditionResult denied(Component reason) {
        return new ConditionResult(false, reason);
    }
}
