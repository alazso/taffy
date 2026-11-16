package so.alaz.taffy.gui;

import org.bukkit.entity.Player;

import java.time.Instant;

/** An ask waiting for its answer. The token tells this ask from a later one for the same player. */
record Pending(long token, PromptMode mode, PromptRequest request, Instant deadline, Player player) {
}
