package so.alaz.taffy.gui;

/** What a menu button does when clicked. */
@FunctionalInterface
public interface ClickHandler {

    /**
     * Acts on the click. Runs inside the click event on the viewer's entity thread, so it opens, closes and redraws
     * menus through {@link Menus#openLater(org.bukkit.entity.Player, Route)}, {@link Menus#closeLater} and
     * {@link Menus#rerenderLater}, and asks for text through {@link Prompts#askLater}, which run on the next tick.
     *
     * @param click the click
     */
    void click(Click click);
}
