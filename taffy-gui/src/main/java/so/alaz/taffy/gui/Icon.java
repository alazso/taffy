package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * What one menu slot shows. An icon describes a stack; the menus build the stack from it when they draw the slot. An
 * amount is shown from 1 to the smaller of the item's maximum stack size and 99.
 */
public sealed interface Icon {

    /**
     * A button or display item. A named button hides the attribute, enchantment and similar detail lines of its item,
     * so only its own text shows.
     *
     * @param material the item shown
     * @param amount the stack size shown
     * @param name the item name, or {@code null} to hide the whole tooltip
     * @param lore the lore lines under the name; not shown when {@code name} is {@code null}
     * @param glint whether the item shows the enchantment glint
     */
    record Button(Material material, int amount, @Nullable Component name, List<Component> lore, boolean glint)
        implements Icon {

        /**
         * Copies the lore.
         *
         * @throws NullPointerException if {@code material}, {@code lore} or a lore line is {@code null}
         */
        public Button {
            Objects.requireNonNull(material, "material");
            lore = List.copyOf(lore);
        }
    }

    /**
     * A slot that holds nothing; its tooltip is hidden.
     *
     * @param material the item shown
     */
    record Filler(Material material) implements Icon {

        /**
         * Checks the material.
         *
         * @throws NullPointerException if {@code material} is {@code null}
         */
        public Filler {
            Objects.requireNonNull(material, "material");
        }
    }

    /**
     * A copy of a real item that keeps its own name and lore, with {@code extraLore} under a blank line. The base is
     * drawn as given and is never changed: contents such as a container's items or a bundle's stacks are the caller's
     * to strip before building the icon. The icon holds the base itself and compares by it, and a slot whose icon is
     * equal to the one it shows is not drawn again, so a stack that changes, such as one in a player's inventory, is
     * passed as a copy.
     *
     * @param base the item copied
     * @param amount the stack size shown
     * @param extraLore the lines added under the item's own lore, or empty to keep the lore as it is
     */
    record Real(ItemStack base, int amount, List<Component> extraLore) implements Icon {

        /**
         * Copies the extra lore.
         *
         * @throws NullPointerException if {@code base}, {@code extraLore} or an extra line is {@code null}
         */
        public Real {
            Objects.requireNonNull(base, "base");
            extraLore = List.copyOf(extraLore);
        }
    }

    /**
     * An item drawn from its saved description: the stored names and lore, then {@code extraLore} under a blank line.
     *
     * @param material the item shown
     * @param amount the stack size shown
     * @param customName the name the item was renamed to, as {@code ItemMeta.displayName()} holds it, or {@code null}
     *     for none; the client shows it in italics unless it sets its own style
     * @param itemName the item's own name, as {@code ItemMeta.itemName()} holds it, or {@code null} for the material's
     *     name; shown without italics when there is no custom name, which the client shows in its place
     * @param lore the stored lore lines, as {@code ItemMeta.lore()} holds them, shown first
     * @param extraLore the menu's own lines, shown under the stored lore after a blank line, or empty for none
     * @param glint whether the item shows the enchantment glint
     */
    record Saved(Material material, int amount, @Nullable Component customName, @Nullable Component itemName,
                 List<Component> lore, List<Component> extraLore, boolean glint) implements Icon {

        /**
         * Copies the lore lines.
         *
         * @throws NullPointerException if {@code material}, {@code lore}, {@code extraLore} or one of their lines is
         *     {@code null}
         */
        public Saved {
            Objects.requireNonNull(material, "material");
            lore = List.copyOf(lore);
            extraLore = List.copyOf(extraLore);
        }
    }
}
