package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;

import java.util.Objects;

/** What a prompt's answer function decides about the text the player typed. */
public sealed interface Answer {

    /**
     * The answer is taken and {@code next} opens, usually the menu that asked, with a new state.
     *
     * @param next the menu to open
     */
    record Accept(Route next) implements Answer {

        /**
         * Checks the route.
         *
         * @throws NullPointerException if {@code next} is {@code null}
         */
        public Accept {
            Objects.requireNonNull(next, "next");
        }
    }

    /**
     * The answer is refused: the player is told {@code reason} and the prompt keeps waiting until its deadline.
     *
     * @param reason why the answer was refused
     */
    record Retry(Component reason) implements Answer {

        /**
         * Checks the reason.
         *
         * @throws NullPointerException if {@code reason} is {@code null}
         */
        public Retry {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
