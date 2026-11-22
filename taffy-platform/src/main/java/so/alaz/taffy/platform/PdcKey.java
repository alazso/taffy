package so.alaz.taffy.platform;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataHolder;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

/**
 * Typed read/write access to a single persistent-data key.
 *
 * <p>{@code P} is the primitive storage type and {@code C} the complex (API) type, matching
 * {@link PersistentDataType}. Writes and removals mutate the holder's container in place, so a write to an
 * {@link org.bukkit.inventory.meta.ItemMeta}, or a removal from one, reaches its item only through
 * {@code ItemStack#setItemMeta}.
 */
public final class PdcKey<P, C> {

    private final NamespacedKey key;
    private final PersistentDataType<P, C> type;

    public PdcKey(NamespacedKey key, PersistentDataType<P, C> type) {
        this.key = key;
        this.type = type;
    }

    /** The key this instance reads and writes. */
    public NamespacedKey key() {
        return key;
    }

    /**
     * Reads the value from the holder's container, or {@code null} if absent. Throws
     * {@link IllegalArgumentException} when the key holds a value of another type.
     */
    public @Nullable C get(PersistentDataHolder holder) {
        return holder.getPersistentDataContainer().get(key, type);
    }

    /**
     * Reads the value from the holder's container, or {@code fallback} if absent. Throws
     * {@link IllegalArgumentException} when the key holds a value of another type.
     */
    public C getOrDefault(PersistentDataHolder holder, C fallback) {
        C value = get(holder);
        return value != null ? value : fallback;
    }

    /** Whether the holder's container holds this key with the matching type. */
    public boolean has(PersistentDataHolder holder) {
        return holder.getPersistentDataContainer().has(key, type);
    }

    public void set(PersistentDataHolder holder, C value) {
        holder.getPersistentDataContainer().set(key, type, value);
    }

    /**
     * Removes the key from the holder's container, whatever the type of its value. A holder without the key
     * is left unchanged. A removal from an {@link org.bukkit.inventory.meta.ItemMeta} reaches its item only
     * through {@code ItemStack#setItemMeta}.
     */
    public void remove(PersistentDataHolder holder) {
        holder.getPersistentDataContainer().remove(key);
    }
}
