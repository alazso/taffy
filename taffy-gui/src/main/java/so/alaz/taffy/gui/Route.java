package so.alaz.taffy.gui;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * A menu with the state it shows and the route its back button returns to.
 *
 * @param menu the id of the menu
 * @param state the state the menu shows; an instance of the {@link Menu#stateType()} of the menu registered under
 *     {@code menu}, and of the state type of that menu
 * @param back the route the back button returns to, or {@code null} when the menu has no back route
 */
public record Route(String menu, Object state, @Nullable Route back) {

    /**
     * Checks the parts.
     *
     * @throws NullPointerException if {@code menu} or {@code state} is {@code null}
     */
    public Route {
        Objects.requireNonNull(menu, "menu");
        Objects.requireNonNull(state, "state");
    }
}
