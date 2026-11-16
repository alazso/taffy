package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Creates the inventory a menu session shows, one per viewer, and the canvas that draws into it. It is called on the
 * viewer's entity thread.
 */
interface Inventories {

    /**
     * Creates one menu inventory.
     *
     * @param holder the holder the inventory reports
     * @param size the number of slots, a multiple of 9 from 9 to 54
     * @param title the title shown above the slots
     * @return the inventory and the canvas that draws into it
     * @throws IllegalArgumentException if the implementation refuses {@code size}
     */
    Surface create(InventoryHolder holder, int size, Component title);

    /**
     * A menu inventory and the canvas that draws icons into it.
     *
     * @param inventory the inventory opened for the viewer
     * @param canvas writes icons into {@code inventory}
     */
    record Surface(Inventory inventory, Canvas canvas) {
    }
}
