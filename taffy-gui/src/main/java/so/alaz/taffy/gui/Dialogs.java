package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/** Shows the dialog a prompt asks with and takes it away. It is called on the player's entity thread. */
interface Dialogs {

    /**
     * Shows a dialog with one text field, a submit button and a cancel button, in place of a dialog the player sees.
     *
     * @param player the player to ask
     * @param form what the dialog shows and what its buttons run
     */
    void show(Player player, Form form);

    /**
     * Takes away the dialog the player sees.
     *
     * @param player the player
     */
    void close(Player player);

    /**
     * What a prompt dialog shows and what its buttons run.
     *
     * @param title the title
     * @param note the text above the field, or {@code null} for none
     * @param initial the text the field starts with, of at most {@link Prompts#MAX_LENGTH} characters
     * @param submit gets the text of the field when the player presses submit, on any thread
     * @param cancel runs when the player presses cancel or closes the dialog with the escape key, on any thread
     */
    record Form(Component title, @Nullable Component note, String initial, Consumer<String> submit,
                Runnable cancel) {
    }
}
