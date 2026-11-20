package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;

import java.util.Objects;
import java.util.function.Function;

/**
 * One question for a player.
 *
 * @param question the text shown
 * @param initial the text an anvil's name field starts with
 * @param origin the menu that a cancel and a timeout open again
 * @param answer judges the typed text, on the player's entity thread; it must not return {@code null}, and a
 *     {@code null} result is handled as a failure of the function. A function whose decision needs storage or an
 *     economy returns an {@link Answer.Later} instead of blocking that thread
 */
public record PromptRequest(Component question, String initial, Route origin, Function<String, Answer> answer) {

    /**
     * Checks the parts.
     *
     * @throws NullPointerException if a part is {@code null}
     */
    public PromptRequest {
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(answer, "answer");
    }
}
