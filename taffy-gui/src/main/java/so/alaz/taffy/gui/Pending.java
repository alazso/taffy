package so.alaz.taffy.gui;

import org.bukkit.entity.Player;

/**
 * An ask waiting for its answer. The token tells this ask from a later one for the same player. The deadline is a
 * reading of the prompts' nanosecond time source, compared by its difference to a later reading.
 */
record Pending(long token, PromptMode mode, PromptRequest request, long deadline, Player player) {
}
