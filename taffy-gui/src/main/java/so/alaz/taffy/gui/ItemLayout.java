package so.alaz.taffy.gui;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * An item a menu layout places. An item with an action is a button the menu draws for that action; an item without
 * one is decoration, drawn as given. A name or lore set here is trusted MiniMessage from the owner and replaces the
 * lang text in every language.
 *
 * @param id the item's id within its menu
 * @param slots the slots the item fills; not empty
 * @param material the item shown
 * @param action the menu action the item stands for, or {@code null} for decoration
 * @param permission the permission a viewer needs to see the item, or {@code null} for none
 * @param enabled whether the item is shown at all
 * @param name the name as MiniMessage, or {@code null} to read it from the lang file
 * @param lore the lore lines as MiniMessage, or {@code null} to read them from the lang file
 */
public record ItemLayout(String id, List<Integer> slots, Material material, @Nullable String action,
                         @Nullable String permission, boolean enabled, @Nullable String name,
                         @Nullable List<String> lore) {

    /**
     * Copies the slots and lore and checks the slots.
     *
     * @throws NullPointerException if {@code id}, {@code material}, {@code slots}, a slot or a lore line is
     *     {@code null}
     * @throws IllegalArgumentException if {@code slots} is empty
     */
    public ItemLayout {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(material, "material");
        slots = List.copyOf(slots);
        if (slots.isEmpty()) {
            throw new IllegalArgumentException("item " + id + " fills no slot");
        }
        lore = lore == null ? null : List.copyOf(lore);
    }

    /**
     * Gives this item shown or hidden.
     *
     * @param on whether the item is shown
     * @return a copy of this item with {@code enabled} set to {@code on}
     */
    public ItemLayout enabled(boolean on) {
        return new ItemLayout(id, slots, material, action, permission, on, name, lore);
    }
}
