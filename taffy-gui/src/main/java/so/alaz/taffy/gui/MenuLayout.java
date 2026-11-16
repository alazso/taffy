package so.alaz.taffy.gui;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How one menu looks: its size, filler, list and items. The plugin builds it, in code or from a file it reads, and
 * passes the menus' layouts keyed by menu id; each key must equal the layout's {@link #menu()}, and the menus refuse
 * a mismatch with {@link IllegalArgumentException} when they are built.
 *
 * <p>{@link #items()} keeps the order it was given. Items may overlap: when two place an icon in the same slot, the
 * one placed later wins. The items without an action are placed first, in the order of {@link #items()}; then each
 * action is placed when the menu draws it, its items in the order of {@link #items()}.
 *
 * @param menu the id of the menu this layout is for
 * @param rows the number of chest rows, from 1 to 6
 * @param filler the item in every slot nothing else fills, or {@code null} for the menus' default filler
 * @param pager where the menu shows its list, or {@code null} for a menu without a list
 * @param items the items, each keyed by its own {@link ItemLayout#id()}; every slot of every item is from 0 to
 *     {@code rows * 9 - 1}
 * @param title the title as MiniMessage, trusted owner text that replaces the lang title in every language, or
 *     {@code null} to read it from the lang file
 */
public record MenuLayout(String menu, int rows, @Nullable Material filler, @Nullable PagerLayout pager,
                         Map<String, ItemLayout> items, @Nullable String title) {

    /**
     * Copies the items and checks the size and slots.
     *
     * @throws NullPointerException if {@code menu}, {@code items}, an item key or an item is {@code null}
     * @throws IllegalArgumentException if {@code rows} is outside 1 to 6, an item key differs from that item's id, or
     *     an item slot or list slot is outside 0 to {@code rows * 9 - 1}
     */
    public MenuLayout {
        Objects.requireNonNull(menu, "menu");
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException("the " + menu + " menu has 1 to 6 rows, not " + rows);
        }
        items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
        int size = rows * 9;
        for (Map.Entry<String, ItemLayout> entry : items.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "item key");
            ItemLayout item = Objects.requireNonNull(entry.getValue(), "item " + key);
            if (!key.equals(item.id())) {
                throw new IllegalArgumentException("item " + item.id() + " is under the key " + key + " in the "
                    + menu + " menu");
            }
            for (int slot : item.slots()) {
                if (slot < 0 || slot >= size) {
                    throw new IllegalArgumentException("item " + item.id() + " slot " + slot + " is outside the "
                        + menu + " menu of " + size + " slots");
                }
            }
        }
        if (pager != null) {
            for (int slot : pager.slots()) {
                if (slot >= size) {
                    throw new IllegalArgumentException("list slot " + slot + " is outside the " + menu + " menu of "
                        + size + " slots");
                }
            }
        }
    }

    /**
     * Gives the number of slots in the menu.
     *
     * @return {@code rows * 9}
     */
    public int size() {
        return rows * 9;
    }

    /**
     * Gives the items that stand for an action.
     *
     * @param action the menu action
     * @return the items whose action is {@code action}, in the order of {@link #items()}
     */
    public List<ItemLayout> items(String action) {
        return items.values().stream().filter(item -> action.equals(item.action())).toList();
    }
}
