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
 * <p>Build an item with {@link #of(String, List, Material)} and the withers, which name each optional text; the
 * canonical constructor takes the three optional texts by position.
 *
 * @param id the item's id within its menu
 * @param slots the slots the item fills; not empty
 * @param material the item shown; {@link Menus#register(Menu)} and {@link Menus#reload(java.util.Map)} refuse a
 *     material that is not an item, air included
 * @param action the menu action the item stands for, or {@code null} for decoration
 * @param permission the permission a viewer needs to see the item, or {@code null} for none. It decides only whether
 *     the item is drawn, each time the menu draws; a click is not checked against it, so a viewer who lost the
 *     permission since the last draw can still click the button, and a handler whose action needs a permission checks
 *     it itself
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
     * Gives a shown item with no action, permission, name or lore.
     *
     * @param id the item's id within its menu
     * @param slots the slots the item fills; not empty
     * @param material the item shown
     * @return the item
     * @throws NullPointerException if {@code id}, {@code material}, {@code slots} or a slot is {@code null}
     * @throws IllegalArgumentException if {@code slots} is empty
     */
    public static ItemLayout of(String id, List<Integer> slots, Material material) {
        return new ItemLayout(id, slots, material, null, null, true, null, null);
    }

    /**
     * Gives this item standing for another action.
     *
     * @param action the menu action the item stands for, or {@code null} for decoration
     * @return a copy of this item with {@code action} replaced
     */
    public ItemLayout action(@Nullable String action) {
        return new ItemLayout(id, slots, material, action, permission, enabled, name, lore);
    }

    /**
     * Gives this item behind another permission, which decides only whether the item is drawn.
     *
     * @param permission the permission a viewer needs to see the item, or {@code null} for none
     * @return a copy of this item with {@code permission} replaced
     */
    public ItemLayout permission(@Nullable String permission) {
        return new ItemLayout(id, slots, material, action, permission, enabled, name, lore);
    }

    /**
     * Gives this item with another name.
     *
     * @param name the name as MiniMessage, or {@code null} to read it from the lang file
     * @return a copy of this item with {@code name} replaced
     */
    public ItemLayout name(@Nullable String name) {
        return new ItemLayout(id, slots, material, action, permission, enabled, name, lore);
    }

    /**
     * Gives this item with other lore.
     *
     * @param lore the lore lines as MiniMessage, or {@code null} to read them from the lang file
     * @return a copy of this item with {@code lore} replaced
     * @throws NullPointerException if a lore line is {@code null}
     */
    public ItemLayout lore(@Nullable List<String> lore) {
        return new ItemLayout(id, slots, material, action, permission, enabled, name, lore);
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
