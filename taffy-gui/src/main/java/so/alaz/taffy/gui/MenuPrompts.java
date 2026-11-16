package so.alaz.taffy.gui;

import org.bukkit.inventory.InventoryView;

import java.util.UUID;

/**
 * The prompt calls menus make: opening a menu ends a pending prompt, a quit forgets it, a view a prompt opened (an
 * anvil) is the prompt's own, so the leak rule leaves it alone, and the menus' shutdown ends every prompt first.
 */
interface MenuPrompts {

    /** No prompts: ending, forgetting and shutting down do nothing, and no view is a prompt's own. */
    MenuPrompts NONE = new MenuPrompts() {
        @Override
        public void end(UUID player) {
        }

        @Override
        public void quit(UUID player) {
        }

        @Override
        public boolean owns(InventoryView view) {
            return false;
        }

        @Override
        public void shutdown() {
        }
    };

    /**
     * Ends the player's pending prompt without a message and without reopening anything.
     *
     * @param player the id of the player
     */
    void end(UUID player);

    /**
     * Forgets the pending prompt of a player who quit.
     *
     * @param player the id of the player
     */
    void quit(UUID player);

    /**
     * Tells whether a prompt opened the view.
     *
     * @param view an open inventory view
     * @return {@code true} if the view is a prompt's own
     */
    boolean owns(InventoryView view);

    /** Ends every pending prompt without a message, as the menus shut down. */
    void shutdown();
}
