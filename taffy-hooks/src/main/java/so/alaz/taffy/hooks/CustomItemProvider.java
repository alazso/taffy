package so.alaz.taffy.hooks;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * One custom item plugin, both ways: builds the item for a native id and reads the native id of an item. Ids are
 * the plugin's own, without the {@code <key>:} prefix. Call on the thread that owns the item.
 */
interface CustomItemProvider {

    /**
     * Whether the plugin has loaded its items. While it has not, {@link #create} cannot answer yet, and {@link #id}
     * can only when {@link #idsReady} says so.
     *
     * @return {@code true} unless the plugin is still loading its items
     */
    default boolean ready() {
        return true;
    }

    /**
     * Whether {@link #id} can answer now. A plugin that reads the id from the item's own data answers before it has
     * loaded its items.
     *
     * @return {@link #ready()}, unless the plugin reads ids while it is still loading its items
     */
    default boolean idsReady() {
        return ready();
    }

    /**
     * Whether the plugin can tell its items apart from others, so that {@link #id} can give an id at all.
     *
     * @return {@code true} unless {@link #id} gives {@code null} for every item
     */
    default boolean readsIds() {
        return true;
    }

    /**
     * Builds the item for a native id.
     *
     * @param id the plugin's own id of the item
     * @return the item, or {@code null} if the plugin does not know the id
     */
    @Nullable ItemStack create(String id);

    /**
     * Reads the native id of an item.
     *
     * @param item the item to read
     * @return the plugin's own id of the item, or {@code null} if the item is not one of the plugin's
     */
    @Nullable String id(ItemStack item);
}
