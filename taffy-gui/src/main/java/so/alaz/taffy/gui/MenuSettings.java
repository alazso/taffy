package so.alaz.taffy.gui;

import org.bukkit.Material;
import so.alaz.taffy.platform.Durations;

import java.time.Duration;
import java.util.Objects;

/**
 * The limits and items every menu shares. The plugin starts from {@link #DEFAULTS} and replaces what its own settings
 * set, such as {@code MenuSettings.DEFAULTS.pageSize(28).filler(Material.BLACK_STAINED_GLASS_PANE)}; each method that
 * takes a value gives a copy and leaves this one unchanged. The settings stay fixed for the life of the {@link Menus}
 * built with them. {@link Menus#register(Menu)} refuses a {@code filler} or {@code loadingItem} that is not an item,
 * air included.
 *
 * <p>Two settings are equal when every value is equal.
 */
public final class MenuSettings {

    /** The largest page size, five chest rows. */
    public static final int MAX_PAGE_SIZE = 45;
    /** The shortest click debounce. */
    public static final Duration MIN_DEBOUNCE = Duration.ofMillis(50);
    /** The longest click debounce. */
    public static final Duration MAX_DEBOUNCE = Duration.ofSeconds(2);

    /** A page size of 45, gray glass panes as filler, a click debounce of 250ms and a clock while loading. */
    public static final MenuSettings DEFAULTS = new MenuSettings(MAX_PAGE_SIZE, Material.GRAY_STAINED_GLASS_PANE,
        Duration.ofMillis(250), Material.CLOCK);

    private final int pageSize;
    private final Material filler;
    private final Duration clickDebounce;
    private final Material loadingItem;

    private MenuSettings(int pageSize, Material filler, Duration clickDebounce, Material loadingItem) {
        Objects.requireNonNull(clickDebounce, "clickDebounce");
        Objects.requireNonNull(loadingItem, "loadingItem");
        Objects.requireNonNull(filler, "filler");
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page size is 1 to " + MAX_PAGE_SIZE + ", not " + pageSize);
        }
        if (clickDebounce.compareTo(MIN_DEBOUNCE) < 0 || clickDebounce.compareTo(MAX_DEBOUNCE) > 0) {
            throw new IllegalArgumentException("click debounce is " + Durations.format(MIN_DEBOUNCE) + " to "
                + Durations.format(MAX_DEBOUNCE) + ", not " + Durations.format(clickDebounce));
        }
        this.pageSize = pageSize;
        this.filler = filler;
        this.clickDebounce = clickDebounce;
        this.loadingItem = loadingItem;
    }

    /**
     * Gives the most list entries one page shows.
     *
     * @return the page size, from 1 to {@link #MAX_PAGE_SIZE}
     */
    public int pageSize() {
        return pageSize;
    }

    /**
     * Gives the item in every slot nothing else fills, unless the menu's layout names its own.
     *
     * @return the filler
     */
    public Material filler() {
        return filler;
    }

    /**
     * Gives how long a second click on the same button, or any click right after a menu opens, is dropped.
     *
     * @return the click debounce, from {@link #MIN_DEBOUNCE} to {@link #MAX_DEBOUNCE}
     */
    public Duration clickDebounce() {
        return clickDebounce;
    }

    /**
     * Gives the item a slot shows while its content loads.
     *
     * @return the loading item
     */
    public Material loadingItem() {
        return loadingItem;
    }

    /**
     * Gives these settings with another page size.
     *
     * @param pageSize the most list entries one page shows, from 1 to {@link #MAX_PAGE_SIZE}
     * @return a copy of these settings with {@code pageSize} replaced
     * @throws IllegalArgumentException if {@code pageSize} is outside its range
     */
    public MenuSettings pageSize(int pageSize) {
        return new MenuSettings(pageSize, filler, clickDebounce, loadingItem);
    }

    /**
     * Gives these settings with another filler.
     *
     * @param filler the item in every slot nothing else fills
     * @return a copy of these settings with {@code filler} replaced
     * @throws NullPointerException if {@code filler} is {@code null}
     */
    public MenuSettings filler(Material filler) {
        return new MenuSettings(pageSize, filler, clickDebounce, loadingItem);
    }

    /**
     * Gives these settings with another click debounce.
     *
     * @param clickDebounce how long a second click on the same button, or any click right after a menu opens, is
     *     dropped, from {@link #MIN_DEBOUNCE} to {@link #MAX_DEBOUNCE}
     * @return a copy of these settings with {@code clickDebounce} replaced
     * @throws NullPointerException if {@code clickDebounce} is {@code null}
     * @throws IllegalArgumentException if {@code clickDebounce} is outside its range
     */
    public MenuSettings clickDebounce(Duration clickDebounce) {
        return new MenuSettings(pageSize, filler, clickDebounce, loadingItem);
    }

    /**
     * Gives these settings with another loading item.
     *
     * @param loadingItem the item a slot shows while its content loads
     * @return a copy of these settings with {@code loadingItem} replaced
     * @throws NullPointerException if {@code loadingItem} is {@code null}
     */
    public MenuSettings loadingItem(Material loadingItem) {
        return new MenuSettings(pageSize, filler, clickDebounce, loadingItem);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MenuSettings that && pageSize == that.pageSize && filler == that.filler
            && clickDebounce.equals(that.clickDebounce) && loadingItem == that.loadingItem;
    }

    @Override
    public int hashCode() {
        return Objects.hash(pageSize, filler, clickDebounce, loadingItem);
    }

    @Override
    public String toString() {
        return "MenuSettings[pageSize=" + pageSize + ", filler=" + filler + ", clickDebounce=" + clickDebounce
            + ", loadingItem=" + loadingItem + "]";
    }
}
