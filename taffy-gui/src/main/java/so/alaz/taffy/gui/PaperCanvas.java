package so.alaz.taffy.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.function.Function;

// Writes icons into a menu inventory. Every stack it writes carries the preview marker. A slot whose icon is unchanged
// is written again only when the inventory no longer holds a marked stack there.
final class PaperCanvas implements Canvas {

    private final Inventory inventory;
    private final Function<Icon, ItemStack> stacks;
    private final PreviewMarker marker;
    private final Icon[] written;

    PaperCanvas(Inventory inventory, Function<Icon, ItemStack> stacks, PreviewMarker marker) {
        this.inventory = inventory;
        this.stacks = stacks;
        this.marker = marker;
        this.written = new Icon[inventory.getSize()];
    }

    @Override
    public void put(int slot, Icon icon) {
        if (icon.equals(written[slot]) && marker.marked(inventory.getItem(slot))) {
            return;
        }
        inventory.setItem(slot, marker.mark(stacks.apply(icon)));
        written[slot] = icon;
    }
}
