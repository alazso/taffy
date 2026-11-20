package so.alaz.taffy.gui;

/**
 * How an ask ended when the prompts opened no menu after it, as {@link PromptRequest#abandoned()} is told. A later
 * release may add constants, so a {@code switch} over the ends has a {@code default} branch.
 */
public enum PromptEnd {
    /** The ask reached its deadline and its origin did not open. */
    TIMED_OUT,
    /**
     * The player quit while the ask waited, was gone when the ask was to end, or was dead or offline when an
     * accepted or failed answer was to open its menu.
     */
    LEFT,
    /**
     * Something took the place of the ask: a menu of these menus opened, a newer ask started, another inventory opened
     * over the prompt's anvil, the player had another inventory open when an accepted, refused or failed answer was to
     * show its menu or its anvil, or the server refused to show the anvil of a refused answer again.
     */
    REPLACED,
    /**
     * The player cancelled the ask, with the cancel word, the cancel link or by closing the anvil, and its origin did
     * not open; or {@link Menus#shutdown()} ended the ask.
     */
    CANCELLED
}
