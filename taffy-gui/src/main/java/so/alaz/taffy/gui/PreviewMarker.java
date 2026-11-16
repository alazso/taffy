package so.alaz.taffy.gui;

import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * The persistent-data key on every stack a menu or prompt anvil shows. {@link #mark} is the only code that writes it;
 * {@link #marked} reads the key with any value type, so a copy whose value was changed still counts.
 *
 * <p>{@link Menus} builds it once from {@link Menus.Parts#preview()}, the plugin's own key, and shares it with the
 * menu inventories, the menu listener and the prompt anvil, so two plugins that each carry this library mark their
 * stacks apart.
 */
final class PreviewMarker {

    private final NamespacedKey key;

    /**
     * A marker writing and reading the given key.
     *
     * @param key the key every shown stack carries
     * @throws NullPointerException if {@code key} is {@code null}
     */
    PreviewMarker(NamespacedKey key) {
        this.key = Objects.requireNonNull(key, "key");
    }

    /**
     * The key this marker writes and reads.
     *
     * @return the key given to the constructor
     */
    NamespacedKey key() {
        return key;
    }

    /**
     * Writes the key as {@code BYTE 1} into the stack's persistent data.
     *
     * @param stack the stack to mark
     * @return the same stack, which now carries the key
     */
    ItemStack mark(ItemStack stack) {
        stack.editPersistentDataContainer(data -> data.set(key, PersistentDataType.BYTE, (byte) 1));
        return stack;
    }

    /**
     * Whether the stack carries the key, with any value type.
     *
     * @param stack the stack to check
     * @return {@code false} for {@code null}, an empty stack and a stack without a data container
     */
    boolean marked(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        PersistentDataContainerView data = stack.getPersistentDataContainer();
        return data != null && data.has(key);
    }
}
