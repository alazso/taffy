package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One question for a player.
 *
 * <p>An ask ends in one of two ways. Either the prompts open a menu, the origin after a cancel, a timeout or a failed
 * answer and the route of an {@link Answer.Accept} after an accepted one, or they open none and call
 * {@code abandoned} once with how the ask ended. A menu that hands what its session held to an ask, such as escrowed
 * items, takes it back when its route opens again and releases it in {@code abandoned}. {@code abandoned} is not
 * called for an ask that never started: one after {@link Menus#shutdown()}, or one whose anvil the server refused to
 * open, which leaves the menu closed with {@code left = true}.
 *
 * @param question the text shown
 * @param initial the text an anvil's name field or a dialog's text field starts with
 * @param origin the menu that a cancel and a timeout open again
 * @param answer judges the typed text, on the player's entity thread; it must not return {@code null}, and a
 *     {@code null} result is handled as a failure of the function. A function whose decision needs storage or an
 *     economy returns an {@link Answer.Later} instead of blocking that thread
 * @param abandoned runs once when the ask ended and the prompts opened no menu for it, with how it ended. It runs on
 *     the player's entity thread, except that {@link PromptEnd#LEFT} for a player who is gone runs on the thread
 *     that found the player gone, where the player must not be used. At {@link Menus#shutdown()} it runs with
 *     {@link PromptEnd#CANCELLED} on the disabling thread, and only for a player that thread owns. One that throws a
 *     {@link RuntimeException} is logged and reported under {@code gui.prompt}
 */
public record PromptRequest(Component question, String initial, Route origin, Function<String, Answer> answer,
                            Consumer<PromptEnd> abandoned) {

    private static final Consumer<PromptEnd> IGNORED = end -> {
    };

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
        Objects.requireNonNull(abandoned, "abandoned");
    }

    /**
     * Creates a question whose {@code abandoned} does nothing.
     *
     * @param question the text shown
     * @param initial the text an anvil's name field starts with
     * @param origin the menu that a cancel and a timeout open again
     * @param answer judges the typed text
     * @throws NullPointerException if a part is {@code null}
     */
    public PromptRequest(Component question, String initial, Route origin, Function<String, Answer> answer) {
        this(question, initial, origin, answer, IGNORED);
    }

    /**
     * Gives this question with another {@code abandoned}.
     *
     * @param step what runs once when the ask ended and the prompts opened no menu for it
     * @return a copy with {@code abandoned} replaced
     * @throws NullPointerException if {@code step} is {@code null}
     */
    public PromptRequest abandoned(Consumer<PromptEnd> step) {
        return new PromptRequest(question, initial, origin, answer, step);
    }
}
