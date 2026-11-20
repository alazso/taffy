package so.alaz.taffy.gui;

/**
 * How a menu asks a player for text. A later release may add constants, so a {@code switch} over the modes has a
 * {@code default} branch.
 */
public enum PromptMode {
    /** The next chat line. */
    CHAT,
    /** The name field of an anvil. */
    ANVIL
}
