package so.alaz.taffy.gui;

/**
 * How an ask ended when the prompts opened no menu after it, as {@link PromptRequest#abandoned()} is told, and how
 * the ask of a {@link TextRequest} ended when no answer was taken, as {@link TextRequest#ended()} is told and as
 * {@link TextRequest} states for each end. A later release may add constants, so a {@code switch} over the ends has a
 * {@code default} branch.
 */
public enum PromptEnd {
    /**
     * The ask reached its deadline and its origin did not open, because the player is dead, offline or has another
     * inventory open, the server refused the origin's inventory or opening the origin failed.
     */
    TIMED_OUT,
    /**
     * The player quit while the ask waited, was gone when the ask was to end, or was dead or offline when an
     * accepted or failed answer was to open its menu.
     */
    LEFT,
    /**
     * Something took the place of the ask: a menu of these menus opened, a newer ask started, another inventory opened
     * over the prompt's anvil, the player had another inventory open when an accepted, refused or failed answer was to
     * show its menu or its anvil, or the server refused to show the anvil of a refused answer again. It is also how
     * an ask ends when the menu an accepted or failed answer was to open did not open: the server refused its
     * inventory, as when another plugin cancels the open, its route names no registered menu or a state of another
     * type, or the menu failed to draw.
     */
    REPLACED,
    /**
     * The player cancelled the ask, with the cancel word, the cancel link, the cancel button of a dialog or by
     * closing the anvil or the dialog, and its origin did not open, for a reason {@link #TIMED_OUT} lists; or
     * {@link Menus#shutdown()} or {@link Prompts#shutdown()} ended the ask.
     */
    CANCELLED
}
