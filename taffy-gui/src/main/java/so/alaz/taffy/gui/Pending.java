package so.alaz.taffy.gui;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * An ask waiting for its answer. The token tells this ask from a later one the same prompts made for the same player.
 * The mode is where the player answers this ask. The deadline is a reading of the prompts' nanosecond time source,
 * compared by its difference to a later reading. The owner is the prompts that asked.
 */
record Pending(long token, PromptMode mode, Ask ask, long deadline, Player player, Prompts owner) {

    /** The question that opens a menu, or {@code null} for an ask that opens none. */
    @Nullable PromptRequest request() {
        return ask.menu();
    }
}
