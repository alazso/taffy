package so.alaz.taffy.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/**
 * A click on a menu button.
 *
 * @param session the session whose button was clicked
 * @param player the player who clicked
 * @param type the click type
 * @param slot the raw slot of the menu inventory that was clicked
 */
public record Click(MenuSession<?> session, Player player, ClickType type, int slot) {
}
