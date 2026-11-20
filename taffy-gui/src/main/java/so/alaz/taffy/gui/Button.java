package so.alaz.taffy.gui;

import org.bukkit.event.inventory.ClickType;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * A slot that acts on a click. {@code key} names it for the debounce. {@code keyed} tells whether the key names what
 * the button acts on, a layout item or the key of a list entry, and not only the slot it is in; a button that is not
 * keyed is told from the one its slot showed before by its icon. An exclusive button is dropped while the session is
 * {@link MenuSession#busy()}, and a load its handler starts keeps the session busy until the load ends.
 */
record Button(Icon icon, Set<ClickType> clicks, String key, boolean keyed, boolean exclusive,
              @Nullable ClickHandler handler) {

    Button {
        clicks = Set.copyOf(clicks);
    }
}
