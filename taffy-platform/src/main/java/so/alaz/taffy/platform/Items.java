package so.alaz.taffy.platform;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Inventory delivery. {@link #giveOrDrop} must run on the thread that owns the player. */
public final class Items {

    private Items() {
    }

    /**
     * Adds the item to the player's inventory, dropping whatever does not fit at their feet.
     *
     * <p>{@code item} is left unchanged: a copy is added and split across the slots, so the same stack can be
     * given to several players.
     */
    public static void giveOrDrop(Player player, ItemStack item) {
        player.getInventory().addItem(item.clone()).values()
            .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }
}
