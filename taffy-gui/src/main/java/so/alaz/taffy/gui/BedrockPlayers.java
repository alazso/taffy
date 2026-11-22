package so.alaz.taffy.gui;

import java.util.UUID;

/**
 * Tells which players joined from Minecraft Bedrock Edition. They always answer a prompt in chat, whatever the
 * {@link PromptMode}.
 */
@FunctionalInterface
public interface BedrockPlayers {

    /** No player is a Bedrock player. */
    BedrockPlayers NONE = player -> false;

    /**
     * Tells whether a player joined from Minecraft Bedrock Edition. Called on the player's entity thread.
     *
     * @param player the id of the player
     * @return {@code true} for a Bedrock player
     */
    boolean bedrock(UUID player);
}
