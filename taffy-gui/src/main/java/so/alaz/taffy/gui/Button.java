package so.alaz.taffy.gui;

import org.bukkit.event.inventory.ClickType;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * A slot that acts on a click. {@code key} names it for the debounce; an exclusive button is dropped while the session
 * is {@link MenuSession#busy()}, and a load its handler starts keeps the session busy until the load ends.
 */
record Button(Icon icon, Set<ClickType> clicks, String key, boolean exclusive, @Nullable ClickHandler handler) {

    Button {
        clicks = Set.copyOf(clicks);
    }
}
