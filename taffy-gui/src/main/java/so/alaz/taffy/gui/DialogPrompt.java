package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The dialog prompt: which dialog each player was shown last and for which ask. {@link Prompts} builds one for
 * {@link PromptMode#DIALOG}.
 *
 * <p>{@link #open} and {@link #close} run on the player's entity thread. {@link #showing}, {@link #forget} and
 * {@link #usable} are safe from any thread, and {@link #shutdown} runs on the thread that disables the plugin. A
 * button of a dialog that is no longer the one shown last does nothing.
 */
final class DialogPrompt {

    private record Open(Player player, long token, long shown) {
    }

    private final Dialogs dialogs;
    private final Logger logger;
    private final Map<UUID, Open> open = new ConcurrentHashMap<>();
    private final AtomicLong shows = new AtomicLong();
    private volatile boolean unusable;

    /**
     * Creates the dialog prompt with no dialog shown.
     *
     * @param dialogs shows and takes away each dialog
     * @param logger the logger a Dialog API that cannot be linked is logged through
     * @throws NullPointerException if an argument is {@code null}
     */
    DialogPrompt(Dialogs dialogs, Logger logger) {
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Gives the dialog prompt of a server that has the class {@code api}, and logs a warning on one that has not.
     *
     * @param api the name of the class whose presence tells that the server has the Dialog API
     * @param logger the logger the missing API is logged through
     * @return the dialog prompt, or {@code null} when the server has no Dialog API
     */
    static @Nullable DialogPrompt paper(String api, Logger logger) {
        try {
            Class.forName(api, false, DialogPrompt.class.getClassLoader());
            return new DialogPrompt(new PaperDialogs(), logger);
        } catch (ClassNotFoundException | LinkageError missing) {
            logger.warning("gui: this server has no Paper Dialog API, so prompts are asked in chat");
            return null;
        }
    }

    /**
     * Tells whether a dialog can still be shown.
     *
     * @return {@code false} once {@link #open} failed with a {@link LinkageError}
     */
    boolean usable() {
        return !unusable;
    }

    /**
     * Shows a dialog for {@code token} in place of a dialog the player sees.
     *
     * @param player the player to ask
     * @param token the token of the ask
     * @param title the title
     * @param note the text above the field, or {@code null} for none
     * @param initial the text the field starts with, cut to {@link Prompts#MAX_LENGTH} characters
     * @param submit gets the text of the field when the player presses submit, on any thread
     * @param cancel runs when the player presses cancel or closes the dialog with the escape key, on any thread
     * @return {@code false} when the server's Dialog API failed to link, which is logged the first time
     */
    boolean open(Player player, long token, Component title, @Nullable Component note, String initial,
                 Consumer<String> submit, Runnable cancel) {
        UUID id = player.getUniqueId();
        Open entry = new Open(player, token, shows.incrementAndGet());
        open.put(id, entry);
        try {
            dialogs.show(player, new Dialogs.Form(title, note, Prompts.cut(initial), text -> {
                if (open.remove(id, entry)) {
                    submit.accept(text);
                }
            }, () -> {
                if (open.remove(id, entry)) {
                    cancel.run();
                }
            }));
            return true;
        } catch (LinkageError changed) {
            open.remove(id, entry);
            if (!unusable) {
                unusable = true;
                logger.log(Level.WARNING, "gui: the Paper Dialog API of this server could not be linked, so prompts"
                    + " are asked in chat: " + changed, changed);
            }
            return false;
        } catch (RuntimeException failure) {
            open.remove(id, entry);
            throw failure;
        }
    }

    /**
     * Takes the dialog for {@code token} away from the player. Nothing happens when the dialog shown last is not the
     * one for {@code token}, or a button of it was pressed.
     *
     * @param player the player
     * @param token the token of the ask
     */
    void close(Player player, long token) {
        UUID id = player.getUniqueId();
        Open entry = open.get(id);
        if (entry == null || entry.token() != token || !open.remove(id, entry)) {
            return;
        }
        if (player.isOnline()) {
            dialogs.close(player);
        }
    }

    /**
     * Tells whether the dialog shown last to the player is the one for {@code token} and no button of it was pressed.
     *
     * @param player the id of the player
     * @param token the token of the ask
     * @return {@code true} while it shows
     */
    boolean showing(UUID player, long token) {
        Open entry = open.get(player);
        return entry != null && entry.token() == token;
    }

    /**
     * Drops what is kept for the player without sending the player anything.
     *
     * @param player the id of the player
     */
    void forget(UUID player) {
        open.remove(player);
    }

    /**
     * Takes the dialog away from every player it still shows whom {@code here} accepts, and forgets the others.
     *
     * @param here whether the calling thread may act on a player
     */
    void shutdown(Predicate<Player> here) {
        for (Map.Entry<UUID, Open> entry : List.copyOf(open.entrySet())) {
            Open shown = entry.getValue();
            if (open.remove(entry.getKey(), shown) && here.test(shown.player())) {
                dialogs.close(shown.player());
            }
        }
    }
}
