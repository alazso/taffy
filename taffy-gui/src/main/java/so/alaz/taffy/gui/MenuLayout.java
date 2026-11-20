package so.alaz.taffy.gui;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
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
 * <p>Build a layout with {@link #of(String, int)} and the methods that take a value, such as
 * {@code MenuLayout.of("shop", 6).pager(PagerLayout.of(slots)).items(items)}; each gives a copy and leaves this one
 * unchanged.
 *
 * <p>{@link #items()} keeps the order the items were given in. Items may overlap: when two place an icon in the same
 * slot, the one placed later wins. The items without an action are placed first, in the order of {@link #items()};
 * then each action is placed when the menu draws it, its items in the order of {@link #items()}.
 *
 * <p>Two layouts are equal when every value is equal; the order of the items is not compared.
 */
public final class MenuLayout {

    private final String menu;
    private final int rows;
    private final @Nullable Material filler;
    private final @Nullable PagerLayout pager;
    private final Map<String, ItemLayout> items;
    private final @Nullable String title;

    private MenuLayout(String menu, int rows, @Nullable Material filler, @Nullable PagerLayout pager,
                       Map<String, ItemLayout> items, @Nullable String title) {
        this.menu = Objects.requireNonNull(menu, "menu");
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException("the " + menu + " menu has 1 to 6 rows, not " + rows);
        }
        int size = rows * 9;
        for (ItemLayout item : items.values()) {
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
        this.rows = rows;
        this.filler = filler;
        this.pager = pager;
        this.items = items;
        this.title = title;
    }

    /**
     * Gives a layout with no items, no list, the menus' default filler and its title read from the lang file.
     *
     * @param menu the id of the menu this layout is for
     * @param rows the number of chest rows, from 1 to 6
     * @return the layout
     * @throws NullPointerException if {@code menu} is {@code null}
     * @throws IllegalArgumentException if {@code rows} is outside 1 to 6
     */
    public static MenuLayout of(String menu, int rows) {
        return new MenuLayout(menu, rows, null, null, Map.of(), null);
    }

    /**
     * Gives the id of the menu this layout is for.
     *
     * @return the menu id
     */
    public String menu() {
        return menu;
    }

    /**
     * Gives the number of chest rows.
     *
     * @return the rows, from 1 to 6
     */
    public int rows() {
        return rows;
    }

    /**
     * Gives the item in every slot nothing else fills.
     *
     * @return the filler, or {@code null} for the menus' default filler
     */
    public @Nullable Material filler() {
        return filler;
    }

    /**
     * Gives where the menu shows its list.
     *
     * @return the list layout, or {@code null} for a menu without a list
     */
    public @Nullable PagerLayout pager() {
        return pager;
    }

    /**
     * Gives the items, each keyed by its own {@link ItemLayout#id()}.
     *
     * @return the items in the order they were given; unmodifiable
     */
    public Map<String, ItemLayout> items() {
        return items;
    }

    /**
     * Gives the layout's own title.
     *
     * @return the title as MiniMessage, or {@code null} to read it from the lang file
     */
    public @Nullable String title() {
        return title;
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

    /**
     * Gives this layout with another filler. {@link Menus#register(Menu)} and {@link Menus#reload(Map)} refuse a
     * material that is not an item, air included.
     *
     * @param filler the item in every slot nothing else fills, or {@code null} for the menus' default filler
     * @return a copy of this layout with {@code filler} replaced
     */
    public MenuLayout filler(@Nullable Material filler) {
        return new MenuLayout(menu, rows, filler, pager, items, title);
    }

    /**
     * Gives this layout with its list in other slots.
     *
     * @param pager where the menu shows its list, or {@code null} for a menu without a list
     * @return a copy of this layout with {@code pager} replaced
     * @throws IllegalArgumentException if a list slot is outside 0 to {@code rows * 9 - 1}
     */
    public MenuLayout pager(@Nullable PagerLayout pager) {
        return new MenuLayout(menu, rows, filler, pager, items, title);
    }

    /**
     * Gives this layout with other items.
     *
     * @param items the items, in the order {@link #items()} keeps; every slot of every item is from 0 to
     *     {@code rows * 9 - 1}
     * @return a copy of this layout with its items replaced
     * @throws NullPointerException if {@code items} or an item is {@code null}
     * @throws IllegalArgumentException if two items have the same id, or an item slot is outside 0 to
     *     {@code rows * 9 - 1}
     */
    public MenuLayout items(Collection<ItemLayout> items) {
        Map<String, ItemLayout> byId = new LinkedHashMap<>();
        for (ItemLayout item : items) {
            Objects.requireNonNull(item, "item");
            if (byId.putIfAbsent(item.id(), item) != null) {
                throw new IllegalArgumentException("item " + item.id() + " is in the " + menu + " menu twice");
            }
        }
        return new MenuLayout(menu, rows, filler, pager, Collections.unmodifiableMap(byId), title);
    }

    /**
     * Gives this layout with one more item, or with the item of the same id replaced in its place.
     *
     * @param item the item; each of its slots is from 0 to {@code rows * 9 - 1}
     * @return a copy of this layout that holds {@code item}
     * @throws NullPointerException if {@code item} is {@code null}
     * @throws IllegalArgumentException if a slot of the item is outside 0 to {@code rows * 9 - 1}
     */
    public MenuLayout item(ItemLayout item) {
        Objects.requireNonNull(item, "item");
        Map<String, ItemLayout> byId = new LinkedHashMap<>(items);
        byId.put(item.id(), item);
        return new MenuLayout(menu, rows, filler, pager, Collections.unmodifiableMap(byId), title);
    }

    /**
     * Gives this layout with another title, trusted owner text that replaces the lang title in every language.
     *
     * @param title the title as MiniMessage, or {@code null} to read it from the lang file
     * @return a copy of this layout with {@code title} replaced
     */
    public MenuLayout title(@Nullable String title) {
        return new MenuLayout(menu, rows, filler, pager, items, title);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MenuLayout that && menu.equals(that.menu) && rows == that.rows
            && filler == that.filler && Objects.equals(pager, that.pager) && items.equals(that.items)
            && Objects.equals(title, that.title);
    }

    @Override
    public int hashCode() {
        return Objects.hash(menu, rows, filler, pager, items, title);
    }

    @Override
    public String toString() {
        return "MenuLayout[menu=" + menu + ", rows=" + rows + ", filler=" + filler + ", pager=" + pager + ", items="
            + items + ", title=" + title + "]";
    }
}
