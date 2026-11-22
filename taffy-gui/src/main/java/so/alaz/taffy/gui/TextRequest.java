package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One question for a player that opens no menu, as a command or a plugin's own screen asks it through
 * {@link Prompts#ask(org.bukkit.entity.Player, TextRequest)}.
 *
 * <p>An ask ends in one of two ways, and the prompts open nothing after either. An answer the refusal check lets
 * through runs {@code accepted} once with the text. Any other end runs {@code ended} once:
 * {@link PromptEnd#CANCELLED} for the cancel word, the cancel link, a closed anvil, the cancel button of a dialog, a
 * refusal check that failed, a future of the check that was cancelled and {@link Prompts#shutdown()},
 * {@link PromptEnd#TIMED_OUT} for the deadline, {@link PromptEnd#LEFT} for a player who quit or is gone, and
 * {@link PromptEnd#REPLACED} for a newer ask, a menu of the menus the prompts are built over, another inventory
 * opened over the prompt's anvil and an anvil or dialog that could not be shown again after a refusal. Neither runs
 * for an ask that never started: one after the prompts were shut down, one whose anvil the server refused to open,
 * or one whose dialog the server failed to show, which {@link Prompts#ask(org.bukkit.entity.Player, TextRequest)}
 * throws on. A dialog that the client takes away without a button, as when another plugin opens an inventory or the
 * player dies, ends at the deadline.
 *
 * <p>A refused answer tells the player the refusal and keeps the ask waiting until its first deadline, as
 * {@link Answer.Retry} does. A refusal check that throws a {@link RuntimeException}, or whose future fails, is logged
 * and reported to {@link so.alaz.taffy.platform.ErrorReports} under {@code gui.prompt}, and the player is told
 * {@link Menus#ERROR}. {@code accepted} and {@code ended} run on the player's entity thread, except that
 * {@link PromptEnd#LEFT} for a player who is gone runs on the thread that found the player gone, where the player
 * must not be used, and that {@link Prompts#shutdown()} runs {@code ended} on the disabling thread, and only for a
 * player that thread owns. One of them that throws a {@link RuntimeException} is logged and reported under
 * {@code gui.prompt}.
 */
public final class TextRequest {

    private final Component question;
    private final String initial;
    private final Function<String, CompletableFuture<@Nullable Component>> check;
    private final Consumer<String> accepted;
    private final Consumer<PromptEnd> ended;

    /**
     * Creates a question whose refusal check decides on the player's entity thread.
     *
     * @param question the text shown
     * @param initial the text an anvil's name field or a dialog's text field starts with
     * @param refusal judges the typed text, on the player's entity thread: {@code null} takes the answer, and a text
     *     refuses it and is shown to the player as the reason
     * @param accepted gets the answer that was taken
     * @param ended gets how the ask ended when no answer was taken
     * @throws NullPointerException if an argument is {@code null}
     */
    public TextRequest(Component question, String initial, Function<String, @Nullable Component> refusal,
                       Consumer<String> accepted, Consumer<PromptEnd> ended) {
        this(completed(refusal), question, initial, accepted, ended);
    }

    private TextRequest(Function<String, CompletableFuture<@Nullable Component>> check, Component question,
                        String initial, Consumer<String> accepted, Consumer<PromptEnd> ended) {
        this.question = Objects.requireNonNull(question, "question");
        this.initial = Objects.requireNonNull(initial, "initial");
        this.check = Objects.requireNonNull(check, "refusal");
        this.accepted = Objects.requireNonNull(accepted, "accepted");
        this.ended = Objects.requireNonNull(ended, "ended");
    }

    /**
     * Creates a question whose refusal check decides later, by work that must not run on the player's entity thread,
     * such as a balance read from storage or an economy. The function runs on the player's entity thread and its
     * future completes on any thread, with {@code null} to take the answer or with the reason that refuses it. The
     * result is then applied on the player's entity thread. While the future runs the ask takes no chat line, no
     * anvil submit and no dialog submit, as with an {@link Answer.Later}; what ends the ask meanwhile drops the
     * result. A function that returns {@code null} is handled as one that throws.
     *
     * @param question the text shown
     * @param initial the text an anvil's name field or a dialog's text field starts with
     * @param refusal judges the typed text into a future of the refusal
     * @param accepted gets the answer that was taken
     * @param ended gets how the ask ended when no answer was taken
     * @return the question
     * @throws NullPointerException if an argument is {@code null}
     */
    public static TextRequest later(Component question, String initial,
                                    Function<String, CompletableFuture<@Nullable Component>> refusal,
                                    Consumer<String> accepted, Consumer<PromptEnd> ended) {
        return new TextRequest(refusal, question, initial, accepted, ended);
    }

    private static Function<String, CompletableFuture<@Nullable Component>> completed(
        Function<String, @Nullable Component> refusal) {
        Objects.requireNonNull(refusal, "refusal");
        return text -> CompletableFuture.completedFuture(refusal.apply(text));
    }

    /**
     * Gives the text shown.
     *
     * @return the question
     */
    public Component question() {
        return question;
    }

    /**
     * Gives the text an anvil's name field or a dialog's text field starts with.
     *
     * @return the starting text
     */
    public String initial() {
        return initial;
    }

    /**
     * Gives what gets the answer that was taken.
     *
     * @return the accepted step
     */
    public Consumer<String> accepted() {
        return accepted;
    }

    /**
     * Gives what gets how the ask ended when no answer was taken.
     *
     * @return the ended step
     */
    public Consumer<PromptEnd> ended() {
        return ended;
    }

    /** The refusal check as a future, already completed for a check that decides on the player's entity thread. */
    Function<String, CompletableFuture<@Nullable Component>> check() {
        return check;
    }
}
