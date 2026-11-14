package so.alaz.taffy.platform;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Checks whether items fit into an inventory's storage slots without changing them. */
public final class InventoryFit {

    private InventoryFit() {
    }

    /**
     * Whether every stack in {@code stacks} fits into {@code storage} when the stacks are added in order.
     *
     * <p>Follows {@code Inventory.addItem}: similar partial stacks fill first, then empty slots, each slot up to the
     * smaller of the item's and the inventory's stack size. {@code storage} is not changed.
     *
     * <p>Keeps no state. The items are read on the calling thread, so live inventory slots need the thread that
     * owns the inventory.
     *
     * @param storage the storage slots, where a {@code null} or empty item is a free slot
     * @param inventoryMax the inventory's maximum stack size
     */
    public static boolean fits(@Nullable ItemStack[] storage, int inventoryMax, List<ItemStack> stacks) {
        @Nullable ItemStack[] slots = storage.clone();
        int[] amounts = new int[slots.length];
        for (int index = 0; index < slots.length; index++) {
            ItemStack slot = slots[index];
            if (slot == null || slot.isEmpty()) {
                slots[index] = null;
            } else {
                amounts[index] = slot.getAmount();
            }
        }
        for (ItemStack stack : stacks) {
            int max = Math.min(stack.getMaxStackSize(), inventoryMax);
            int remaining = stack.getAmount();
            for (int index = 0; index < slots.length && remaining > 0; index++) {
                ItemStack slot = slots[index];
                if (slot != null && amounts[index] < max && slot.isSimilar(stack)) {
                    int moved = Math.min(remaining, max - amounts[index]);
                    amounts[index] += moved;
                    remaining -= moved;
                }
            }
            for (int index = 0; index < slots.length && remaining > 0; index++) {
                if (slots[index] == null) {
                    int moved = Math.min(remaining, max);
                    slots[index] = stack;
                    amounts[index] = moved;
                    remaining -= moved;
                }
            }
            if (remaining > 0) {
                return false;
            }
        }
        return true;
    }
}
