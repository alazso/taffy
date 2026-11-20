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
 * <p>Build an item with {@link #of(String, List, Material)} and the methods that take a value, such as
 * {@code ItemLayout.of("buy", List.of(13), Material.EMERALD).action("buy")}; each gives a copy and leaves this one
 * unchanged.
 *
 * <p>Two items are equal when every value is equal.
 */
public final class ItemLayout {

    private final String id;
    private final List<Integer> slots;
    private final Material material;
    private final @Nullable String action;
    private final @Nullable String permission;
    private final boolean enabled;
    private final @Nullable String name;
    private final @Nullable List<String> lore;

    private ItemLayout(String id, List<Integer> slots, Material material, @Nullable String action,
                       @Nullable String permission, boolean enabled, @Nullable String name,
                       @Nullable List<String> lore) {
        this.id = Objects.requireNonNull(id, "id");
        this.material = Objects.requireNonNull(material, "material");
        this.slots = List.copyOf(slots);
        if (this.slots.isEmpty()) {
            throw new IllegalArgumentException("item " + id + " fills no slot");
        }
        this.action = action;
        this.permission = permission;
        this.enabled = enabled;
        this.name = name;
        this.lore = lore == null ? null : List.copyOf(lore);
    }

    /**
     * Gives a shown item with no action, permission, name or lore.
     *
     * @param id the item's id within its menu
     * @param slots the slots the item fills; not empty
     * @param material the item shown; {@link Menus#register(Menu)} and {@link Menus#reload(java.util.Map)} refuse a
     *     material that is not an item, air included
     * @return the item
     * @throws NullPointerException if {@code id}, {@code material}, {@code slots} or a slot is {@code null}
     * @throws IllegalArgumentException if {@code slots} is empty
     */
    public static ItemLayout of(String id, List<Integer> slots, Material material) {
        return new ItemLayout(id, slots, material, null, null, true, null, null);
    }

    /**
     * Gives the item's id within its menu.
     *
     * @return the id
     */
    public String id() {
        return id;
    }

    /**
     * Gives the slots the item fills.
     *
     * @return the slots; not empty, unmodifiable
     */
    public List<Integer> slots() {
        return slots;
    }

    /**
     * Gives the item shown.
     *
     * @return the material
     */
    public Material material() {
        return material;
    }

    /**
     * Gives the menu action the item stands for.
     *
     * @return the action, or {@code null} for decoration
     */
    public @Nullable String action() {
        return action;
    }

    /**
     * Gives the permission a viewer needs to see the item. It decides only whether the item is drawn, each time the
     * menu draws; a click is not checked against it, so a viewer who lost the permission since the last draw can
     * still click the button, and a handler whose action needs a permission checks it itself.
     *
     * @return the permission, or {@code null} for none
     */
    public @Nullable String permission() {
        return permission;
    }

    /**
     * Tells whether the item is shown at all.
     *
     * @return whether the item is shown
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Gives the item's own name.
     *
     * @return the name as MiniMessage, or {@code null} to read it from the lang file
     */
    public @Nullable String name() {
        return name;
    }

    /**
     * Gives the item's own lore.
     *
     * @return the lore lines as MiniMessage, unmodifiable, or {@code null} to read them from the lang file
     */
    public @Nullable List<String> lore() {
        return lore;
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

    @Override
    public boolean equals(Object other) {
        return other instanceof ItemLayout that && id.equals(that.id) && slots.equals(that.slots)
            && material == that.material && Objects.equals(action, that.action)
            && Objects.equals(permission, that.permission) && enabled == that.enabled
            && Objects.equals(name, that.name) && Objects.equals(lore, that.lore);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, slots, material, action, permission, enabled, name, lore);
    }

    @Override
    public String toString() {
        return "ItemLayout[id=" + id + ", slots=" + slots + ", material=" + material + ", action=" + action
            + ", permission=" + permission + ", enabled=" + enabled + ", name=" + name + ", lore=" + lore + "]";
    }
}
