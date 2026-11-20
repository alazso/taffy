package so.alaz.taffy.gui;

import org.bukkit.Material;
import so.alaz.taffy.platform.Durations;

import java.time.Duration;
import java.util.Objects;

/**
 * The limits and items every menu shares. The plugin reads them from its own settings and builds this record; they
 * stay fixed for the life of the {@link Menus} built with them. {@link Menus#register(Menu)} refuses a
 * {@code filler} or {@code loadingItem} that is not an item, air included.
 *
 * @param pageSize the most list entries one page shows, from 1 to {@link #MAX_PAGE_SIZE}
 * @param filler the item in every slot nothing else fills, unless the menu's layout names its own
 * @param clickDebounce how long a second click on the same button, or any click right after a menu opens, is
 *     dropped, from {@link #MIN_DEBOUNCE} to {@link #MAX_DEBOUNCE}
 * @param loadingItem the item a slot shows while its content loads
 */
public record MenuSettings(int pageSize, Material filler, Duration clickDebounce, Material loadingItem) {

    /** The largest page size, five chest rows. */
    public static final int MAX_PAGE_SIZE = 45;
    /** The shortest click debounce. */
    public static final Duration MIN_DEBOUNCE = Duration.ofMillis(50);
    /** The longest click debounce. */
    public static final Duration MAX_DEBOUNCE = Duration.ofSeconds(2);

    /** A page size of 45, gray glass panes as filler, a click debounce of 250ms and a clock while loading. */
    public static final MenuSettings DEFAULTS = new MenuSettings(MAX_PAGE_SIZE, Material.GRAY_STAINED_GLASS_PANE,
        Duration.ofMillis(250), Material.CLOCK);

    /**
     * Checks the parts.
     *
     * @throws NullPointerException if {@code filler}, {@code clickDebounce} or {@code loadingItem} is {@code null}
     * @throws IllegalArgumentException if {@code pageSize} or {@code clickDebounce} is outside its range
     */
    public MenuSettings {
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
    }
}
