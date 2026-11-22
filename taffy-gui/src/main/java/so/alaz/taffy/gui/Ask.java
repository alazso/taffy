package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * The question of an ask: a {@link PromptRequest}, which opens a menu when the ask ends, or a {@link TextRequest},
 * which opens none. Exactly one of the two is given.
 *
 * @param menu the question that opens a menu, or {@code null}
 * @param text the question that opens no menu, or {@code null}
 */
record Ask(@Nullable PromptRequest menu, @Nullable TextRequest text) {

    /**
     * Checks that exactly one question is given.
     *
     * @throws IllegalArgumentException if both or neither are given
     */
    Ask {
        if ((menu == null) == (text == null)) {
            throw new IllegalArgumentException("an ask has exactly one question");
        }
    }

    /** The text shown. */
    Component question() {
        return menu != null ? menu.question() : Objects.requireNonNull(text).question();
    }

    /** The text an anvil's name field or a dialog's text field starts with. */
    String initial() {
        return menu != null ? menu.initial() : Objects.requireNonNull(text).initial();
    }

    /** The menu a cancel, a timeout and a failed answer open again, or {@code null} for a question that opens none. */
    @Nullable Route origin() {
        return menu != null ? menu.origin() : null;
    }

    /** What runs once when the ask ended with no menu opened and no answer taken. */
    Consumer<PromptEnd> ended() {
        return menu != null ? menu.abandoned() : Objects.requireNonNull(text).ended();
    }
}
