package so.alaz.taffy.gui;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where a menu shows its list: the slots the list fills, in order, and the slot and item it shows when the list is
 * empty.
 *
 * @param slots the slots the list fills, in the order entries are placed; not empty, each from 0 to 53, no slot twice
 * @param emptySlot the slot that shows the empty state, one of {@code slots}, or {@code null} for no empty state
 * @param emptyMaterial the item of the empty state, or {@code null} for paper; {@link Menus#register(Menu)} and
 *     {@link Menus#reload(java.util.Map)} refuse a material that is not an item, air included
 */
public record PagerLayout(List<Integer> slots, @Nullable Integer emptySlot, @Nullable Material emptyMaterial) {

    /**
     * Copies the slots and checks them.
     *
     * @throws NullPointerException if {@code slots} or one of its slots is {@code null}
     * @throws IllegalArgumentException if {@code slots} is empty, a slot is outside 0 to 53 or repeated, or
     *     {@code emptySlot} is not one of {@code slots}
     */
    public PagerLayout {
        slots = List.copyOf(slots);
        if (slots.isEmpty()) {
            throw new IllegalArgumentException("a list fills at least one slot");
        }
        Set<Integer> seen = new HashSet<>();
        for (int slot : slots) {
            if (slot < 0 || slot > 53) {
                throw new IllegalArgumentException("list slot " + slot + " is not a slot from 0 to 53");
            }
            if (!seen.add(slot)) {
                throw new IllegalArgumentException("list slot " + slot + " is listed twice");
            }
        }
        if (emptySlot != null && !seen.contains(emptySlot)) {
            throw new IllegalArgumentException("empty slot " + emptySlot + " is not one of the list slots");
        }
    }
}
