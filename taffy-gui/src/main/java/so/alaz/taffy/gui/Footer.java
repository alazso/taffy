package so.alaz.taffy.gui;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.Nullable;

import java.util.function.IntFunction;

/**
 * The footer actions {@link MenuKind#BACK}, {@link MenuKind#PREVIOUS_PAGE}, {@link MenuKind#NEXT_PAGE},
 * {@link MenuKind#REFRESH} and {@link MenuKind#CLOSE}, drawn wherever the menu's layout places them. A footer action
 * the menu's kind does not have, or that does not apply, shows filler.
 *
 * <p>Back opens the session's back route. Without one it opens the home menu named in {@link Menus.Parts#home()},
 * unless there is no home, the session is the home menu or the home menu is not registered. The page arrows read
 * {@code <page>} (counting from 1) and {@code <pages>} in their text.
 */
final class Footer {

    private Footer() {
    }

    /**
     * Draws the footer actions of one render.
     *
     * @param frame the render
     * @param page the page the list shows, or {@code null} on a menu without a list
     * @param turn gives the state that shows the requested page, or {@code null} on a menu without a list
     * @param refresh what refresh does, or {@code null} when the menu has nothing to reload; it is exclusive
     * @param <S> the type of the state the menu shows
     */
    static <S> void draw(Frame<S> frame, Pager.@Nullable Page page, @Nullable IntFunction<S> turn,
                         @Nullable ClickHandler refresh) {
        Menus menus = frame.menus();
        MenuSession<S> session = frame.session();
        Route back = session.back();
        String homeId = menus.home();
        boolean toHome = back == null && homeId != null && !homeId.equals(session.id()) && menus.registered(homeId);
        if (frame.kind().has(MenuKind.BACK) && (back != null || toHome)) {
            frame.action(MenuKind.BACK, Frame.Spec.click(click -> {
                if (back != null) {
                    menus.openLater(click.player(), back);
                } else {
                    menus.openLater(click.player(), homeId, null);
                }
            }));
        }
        if (page != null && turn != null) {
            TagResolver number = Placeholder.unparsed("page", Integer.toString(page.index() + 1));
            TagResolver pages = Placeholder.unparsed("pages", Integer.toString(page.pages()));
            if (page.previous() && frame.kind().has(MenuKind.PREVIOUS_PAGE)) {
                frame.action(MenuKind.PREVIOUS_PAGE, Frame.Spec.click(click -> turn(menus, session, turn,
                    page.index() - 1)).with(number, pages));
            }
            if (page.next() && frame.kind().has(MenuKind.NEXT_PAGE)) {
                frame.action(MenuKind.NEXT_PAGE, Frame.Spec.click(click -> turn(menus, session, turn,
                    page.index() + 1)).with(number, pages));
            }
        }
        if (refresh != null && frame.kind().has(MenuKind.REFRESH)) {
            frame.action(MenuKind.REFRESH, Frame.Spec.click(refresh).exclusively());
        }
        if (frame.kind().has(MenuKind.CLOSE)) {
            frame.action(MenuKind.CLOSE, Frame.Spec.click(click -> menus.closeLater(click.player())));
        }
    }

    private static <S> void turn(Menus menus, MenuSession<S> session, IntFunction<S> turn, int page) {
        session.state(turn.apply(page));
        menus.rerenderLater(session);
    }
}
