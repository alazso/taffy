package so.alaz.taffy.hooks;

import org.bukkit.entity.Player;

/**
 * One check on a player that passes or is denied with a reason. A {@link ConditionType} builds one from an entry of
 * a config list, and {@link ConditionRegistry#build} gives one that runs every entry of a list in order.
 *
 * <p>{@link #test(Player)} is called on the thread that owns the player: the main thread on Paper, the player's
 * region thread on Folia.
 *
 * @since 0.4.14
 */
@FunctionalInterface
public interface Condition {

    /**
     * Checks the player.
     *
     * @param player the player who is checked and who the reason of a denial is rendered for
     * @return {@link ConditionResult#pass()}, or a denied result with its reason
     * @since 0.4.14
     */
    ConditionResult test(Player player);
}
