package so.alaz.taffy.gui;

import org.bukkit.entity.Player;

/**
 * An ask waiting for its answer. The token tells this ask from a later one the same prompts made for the same player.
 * The deadline is a reading of the prompts' nanosecond time source, compared by its difference to a later reading. The
 * owner is the prompts that asked.
 */
record Pending(long token, PromptMode mode, PromptRequest request, long deadline, Player player, Prompts owner) {
}
