package so.alaz.taffy.gui;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Where a menu shows its list: the slots the list fills, in order, and the slot and item it shows when the list is
 * empty. Build one with {@link #of(List)} and the methods that take a value, such as
 * {@code PagerLayout.of(slots).emptySlot(22)}; each gives a copy and leaves this one unchanged.
 *
 * <p>Two list layouts are equal when every value is equal.
 */
public final class PagerLayout {

    private final List<Integer> slots;
    private final @Nullable Integer emptySlot;
    private final @Nullable Material emptyMaterial;

    private PagerLayout(List<Integer> slots, @Nullable Integer emptySlot, @Nullable Material emptyMaterial) {
        this.slots = List.copyOf(slots);
        if (this.slots.isEmpty()) {
            throw new IllegalArgumentException("a list fills at least one slot");
        }
        Set<Integer> seen = new HashSet<>();
        for (int slot : this.slots) {
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
        this.emptySlot = emptySlot;
        this.emptyMaterial = emptyMaterial;
    }

    /**
     * Gives a list in these slots with no empty state.
     *
     * @param slots the slots the list fills, in the order entries are placed; not empty, each from 0 to 53, no slot
     *     twice
     * @return the list layout
     * @throws NullPointerException if {@code slots} or one of its slots is {@code null}
     * @throws IllegalArgumentException if {@code slots} is empty, or a slot is outside 0 to 53 or repeated
     */
    public static PagerLayout of(List<Integer> slots) {
        return new PagerLayout(slots, null, null);
    }

    /**
     * Gives the slots the list fills, in the order entries are placed.
     *
     * @return the slots; not empty, unmodifiable
     */
    public List<Integer> slots() {
        return slots;
    }

    /**
     * Gives the slot that shows the empty state.
     *
     * @return one of {@link #slots()}, or {@code null} for no empty state
     */
    public @Nullable Integer emptySlot() {
        return emptySlot;
    }

    /**
     * Gives the item of the empty state.
     *
     * @return the item, or {@code null} for paper
     */
    public @Nullable Material emptyMaterial() {
        return emptyMaterial;
    }

    /**
     * Gives this list with its empty state in another slot.
     *
     * @param emptySlot the slot that shows the empty state, one of {@link #slots()}, or {@code null} for no empty
     *     state
     * @return a copy of this list layout with {@code emptySlot} replaced
     * @throws IllegalArgumentException if {@code emptySlot} is not one of {@link #slots()}
     */
    public PagerLayout emptySlot(@Nullable Integer emptySlot) {
        return new PagerLayout(slots, emptySlot, emptyMaterial);
    }

    /**
     * Gives this list with another item as its empty state. {@link Menus#register(Menu)} and
     * {@link Menus#reload(java.util.Map)} refuse a material that is not an item, air included.
     *
     * @param emptyMaterial the item of the empty state, or {@code null} for paper
     * @return a copy of this list layout with {@code emptyMaterial} replaced
     */
    public PagerLayout emptyMaterial(@Nullable Material emptyMaterial) {
        return new PagerLayout(slots, emptySlot, emptyMaterial);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PagerLayout that && slots.equals(that.slots)
            && Objects.equals(emptySlot, that.emptySlot) && emptyMaterial == that.emptyMaterial;
    }

    @Override
    public int hashCode() {
        return Objects.hash(slots, emptySlot, emptyMaterial);
    }

    @Override
    public String toString() {
        return "PagerLayout[slots=" + slots + ", emptySlot=" + emptySlot + ", emptyMaterial=" + emptyMaterial + "]";
    }
}
