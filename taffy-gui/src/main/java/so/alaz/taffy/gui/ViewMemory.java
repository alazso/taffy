package so.alaz.taffy.gui;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a viewer last chose in each menu (a tab, a sort), kept until the viewer quits and the menus forget them.
 * Safe to use from any thread.
 */
public final class ViewMemory {

    private final ConcurrentHashMap<UUID, Map<String, Object>> views = new ConcurrentHashMap<>();

    ViewMemory() {
    }

    /**
     * Reads the value kept for a viewer and menu.
     *
     * @param viewer the id of the viewer
     * @param menu the id of the menu
     * @param type the type the value must have
     * @param <T> the type of the value
     * @return the value put for the menu, or {@code null} when there is none or it is not a {@code type}
     */
    public <T> @Nullable T get(UUID viewer, String menu, Class<T> type) {
        Map<String, Object> menus = views.get(viewer);
        Object value = menus == null ? null : menus.get(menu);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    /**
     * Keeps a value for a viewer and menu, replacing the one kept before.
     *
     * @param viewer the id of the viewer
     * @param menu the id of the menu
     * @param value the value to keep
     * @throws NullPointerException if an argument is {@code null}
     */
    public void put(UUID viewer, String menu, Object value) {
        views.computeIfAbsent(viewer, id -> new ConcurrentHashMap<>()).put(menu, value);
    }

    /**
     * Forgets every value kept for a viewer.
     *
     * @param viewer the id of the viewer
     */
    void forget(UUID viewer) {
        views.remove(viewer);
    }
}
