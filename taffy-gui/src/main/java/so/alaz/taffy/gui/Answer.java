package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * What a prompt's answer function decides about the text the player typed. A later release may add a permitted
 * subtype.
 */
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

    /**
     * The answer is decided later, by work that must not run on the player's entity thread, such as a balance read
     * from storage or an economy. The future completes on any thread. Its result is then applied on the player's
     * entity thread exactly as a result the answer function returned: an {@link Accept} opens its menu, a
     * {@link Retry} tells the player and waits for the next answer, and another {@code Later} waits again.
     *
     * <p>While the future runs the ask takes no chat line and no anvil submit, an anvil stays open and no dialog
     * shows. The ask's
     * first deadline, the cancel link and a closed anvil still end it, as do another menu, a newer ask, a quit and
     * {@link Menus#shutdown()}; the result of a future that completes after the ask ended is dropped, and nothing runs
     * for a player who left the server. A future that fails or completes with {@code null} is handled as an answer
     * function that throws. A future that was cancelled opens the origin again with no message, and nothing is
     * logged or reported.
     *
     * @param answer the decision, completed on any thread
     */
    record Later(CompletableFuture<Answer> answer) implements Answer {

        /**
         * Checks the future.
         *
         * @throws NullPointerException if {@code answer} is {@code null}
         */
        public Later {
            Objects.requireNonNull(answer, "answer");
        }
    }
}
