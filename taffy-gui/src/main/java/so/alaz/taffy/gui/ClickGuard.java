package so.alaz.taffy.gui;

import org.bukkit.event.inventory.ClickType;

import java.util.Set;

/**
 * The click types a menu button can act on. Every other click type, number keys, drops, double clicks and creative
 * clicks included, never reaches a button.
 */
public final class ClickGuard {

    /** Left and right clicks, with or without shift: the clicks a button acts on. */
    public static final Set<ClickType> ANY_PLAIN = Set.of(ClickType.LEFT, ClickType.SHIFT_LEFT, ClickType.RIGHT,
        ClickType.SHIFT_RIGHT);
    /** A plain left click alone, for a button that ignores every other click. */
    public static final Set<ClickType> LEFT_ONLY = Set.of(ClickType.LEFT);

    private ClickGuard() {
    }

    /**
     * Tells whether a click type can reach a button.
     *
     * @param click the click type of an inventory click
     * @return {@code true} if the click is one of {@link #ANY_PLAIN}
     */
    static boolean dispatchable(ClickType click) {
        return ANY_PLAIN.contains(click);
    }
}
