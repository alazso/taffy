package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * A menu: its kind, the state it opens with, and how it draws a state. Every method runs on the viewer's entity
 * thread.
 *
 * @param <S> the type of the state the menu shows
 */
public interface Menu<S> {

    /**
     * Gives the menu's kind, whose id names the menu and its layout.
     *
     * @return the kind
     */
    MenuKind kind();

    /**
     * Gives the state the menu opens with when no route names one.
     *
     * @param viewer the player the menu opens for
     * @return the state, never {@code null}
     */
    S initial(Player viewer);

    /**
     * Gives a type every state of the menu is an instance of. {@link Menus#open(Player, Route)} and
     * {@link Menus#openLater(Player, Route)} refuse a route whose state is not an instance of it. By default it is
     * {@link Object}, which accepts every state; a state of another type than {@code S} then fails inside
     * {@link #render(Frame)} with a {@link ClassCastException}.
     *
     * @return the type, such as {@code Integer.class} for a {@code Menu<Integer>}; not a primitive type
     */
    default Class<? super S> stateType() {
        return Object.class;
    }

    /**
     * Gives the title of the inventory. By default it is the layout's own title, or the lang key
     * {@code gui.<id>.title}. The title is read once, when the menu opens; a redraw keeps it, so a new title takes a
     * new open, such as {@link Menus#openLater(Player, Route)} with {@link MenuSession#route()}.
     *
     * @param frame the render the title is for
     * @return the title
     */
    default Component title(Frame<S> frame) {
        return frame.text().title(frame.locale(), frame.layout(), "gui." + kind().id() + ".title");
    }

    /**
     * Places the actions, list rows and states of one render. Every slot left untouched shows the filler.
     *
     * @param frame the render
     */
    void render(Frame<S> frame);

    /**
     * Runs after the inventory opened; this is where a menu starts its loads through
     * {@link Menus#load(MenuSession, java.util.concurrent.CompletableFuture, java.util.function.BiConsumer)}. Does
     * nothing by default.
     *
     * @param session the session that opened
     * @param viewer the player who sees it
     */
    default void opened(MenuSession<S> session, Player viewer) {
    }

    /**
     * Runs once the session ended. Does nothing by default.
     *
     * <p>The session never opens again after this call, whatever {@code left} is: a menu that opens in its place, and
     * the origin a prompt opens when its ask ends, is a new session built from a {@link Route}. What the session held
     * outside its state, such as escrowed items, is therefore released here or moved out, for a prompt into its
     * {@link PromptRequest}, whose {@link PromptRequest#abandoned()} runs when the ask ends and the prompts open no
     * menu.
     *
     * @param session the session that ended
     * @param left {@code true} when the viewer left on their own (closed it, quit, died, or another plugin's menu
     *     replaced it), {@code false} when this {@link Menus} opened another menu in its place, a prompt built over
     *     it took over through {@link Prompts#ask(Player, PromptRequest)}, or it is stopping
     */
    default void closed(MenuSession<S> session, boolean left) {
    }
}
