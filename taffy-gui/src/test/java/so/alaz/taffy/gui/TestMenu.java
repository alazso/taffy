package so.alaz.taffy.gui;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

// A menu whose state is a page number and whose render, open and close steps a test sets.
final class TestMenu implements Menu<Integer> {

    private final MenuKind kind;
    Consumer<Frame<Integer>> render = frame -> {
    };
    Consumer<MenuSession<Integer>> opened = session -> {
    };
    Consumer<Boolean> closing = left -> {
    };
    final List<Boolean> closes = new ArrayList<>();
    final List<MenuSession<Integer>> sessions = new ArrayList<>();
    int renders;

    TestMenu(MenuKind kind) {
        this.kind = kind;
    }

    @Override
    public MenuKind kind() {
        return kind;
    }

    @Override
    public Integer initial(Player viewer) {
        return 0;
    }

    @Override
    public void render(Frame<Integer> frame) {
        renders++;
        render.accept(frame);
    }

    @Override
    public void opened(MenuSession<Integer> session, Player viewer) {
        sessions.add(session);
        opened.accept(session);
    }

    @Override
    public void closed(MenuSession<Integer> session, boolean left) {
        closes.add(left);
        closing.accept(left);
    }
}
