package so.alaz.taffy.gui;

import net.kyori.adventure.text.event.ClickEvent;

/** Makes the click event of the cancel link a chat prompt shows. */
@FunctionalInterface
interface CancelLink {

    /**
     * Gives a click event that runs {@code action} when the player clicks it. The action may run on any thread.
     *
     * @param action what the click does
     * @return the click event
     */
    ClickEvent link(Runnable action);
}
