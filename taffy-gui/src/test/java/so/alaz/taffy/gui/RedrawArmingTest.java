package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The list menu open for Alex, past the open arming window, with one row per name in listings from slot 9 on. A click
// on a row adds its name to bought. With keyed set, each row carries its name as its key. Each row shows the number of
// the render as its amount while animated is set.
final class RedrawArmingTest {

    private static final Duration SOON = Duration.ofMillis(30);

    @TempDir
    Path folder;

    private MenuHarness harness;
    private MenuHarness.Viewer viewer;
    private final TestMenu menu = new TestMenu(MenuHarness.LIST);
    private final List<String> listings = new ArrayList<>(List.of("A", "B", "C"));
    private final List<String> bought = new ArrayList<>();
    private boolean keyed;
    private boolean animated;

    @BeforeEach
    void build() {
        harness = new MenuHarness(folder);
        menu.render = frame -> {
            frame.list(listings.size(), 0, index -> row(listings.get(index)));
            frame.action(MenuKind.CLOSE, Frame.Spec.click(click -> bought.add("close")).amount(menu.renders));
        };
        harness.menus.register(menu);
        viewer = harness.viewer("Alex");
    }

    private Frame.Entry row(String name) {
        Icon icon = new Icon.Button(Material.PAPER, animated ? menu.renders : 1, Component.text(name), List.of(),
            false);
        Frame.Entry entry = Frame.Entry.click(icon, click -> bought.add(name));
        return keyed ? entry.key(name) : entry;
    }

    private void open() {
        harness.open(viewer, MenuHarness.LIST.id());
        harness.advance(Sample.SETTLED);
    }

    private void redraw() {
        harness.menus.rerender(menu.sessions.getLast());
        harness.advance(SOON);
    }

    @Test
    void aClickOnAnUnkeyedRowThatAnotherListingJustMovedIntoIsDropped() {
        open();
        listings.remove("A");
        redraw();

        viewer.click(9, ClickType.LEFT);

        assertThat(viewer.name(9)).isEqualTo("B");
        assertThat(bought).isEmpty();
        assertThat(viewer.heard()).isEmpty();
        harness.advance(Sample.SETTLED);
        viewer.click(9, ClickType.LEFT);
        assertThat(bought).containsExactly("B");
    }

    @Test
    void anUnkeyedRowWhoseIconIsUnchangedStaysClickableAfterARedraw() {
        open();
        listings.remove("C");
        redraw();

        viewer.click(9, ClickType.LEFT);
        viewer.click(10, ClickType.LEFT);

        assertThat(bought).containsExactly("A", "B");
    }

    @Test
    void aKeyedRowThatARedrawLeftInItsSlotStaysClickableAndOneThatMovedIsDropped() {
        keyed = true;
        open();
        listings.remove("B");
        redraw();

        viewer.click(9, ClickType.LEFT);
        viewer.click(10, ClickType.LEFT);

        assertThat(viewer.name(10)).isEqualTo("C");
        assertThat(bought).containsExactly("A");
        harness.advance(Sample.SETTLED);
        viewer.click(10, ClickType.LEFT);
        assertThat(bought).containsExactly("A", "C");
    }

    @Test
    void anAnimatedRowWithAStableKeyStaysClickableAndOneWithNoKeyIsDropped() {
        animated = true;
        keyed = true;
        open();
        redraw();
        Icon first = viewer.icon(9);
        redraw();

        viewer.click(9, ClickType.LEFT);

        assertThat(viewer.icon(9)).isNotEqualTo(first);
        assertThat(bought).containsExactly("A");

        keyed = false;
        redraw();
        harness.advance(Sample.SETTLED);
        redraw();
        viewer.click(10, ClickType.LEFT);

        assertThat(bought).containsExactly("A");
    }

    @Test
    void anActionWhoseIconChangesOnARedrawStaysClickable() {
        open();
        redraw();

        viewer.click(53, ClickType.LEFT);

        assertThat(bought).containsExactly("close");
    }

    @Test
    void aRowThatAppearsInASlotWithNoButtonIsDroppedForTheWindow() {
        listings.clear();
        open();
        listings.add("A");
        redraw();

        viewer.click(9, ClickType.LEFT);

        assertThat(bought).isEmpty();
        harness.advance(Sample.SETTLED);
        viewer.click(9, ClickType.LEFT);
        assertThat(bought).containsExactly("A");
    }

    @Test
    void theClickDebounceFollowsTheKeyOfARowAndTheSlotOfARowWithNoKey() {
        keyed = true;
        listings.add(1, "A");
        open();

        assertThat(menu.sessions.getLast().buttons().get(9).key()).isEqualTo("entry:A");
        viewer.click(9, ClickType.LEFT);
        viewer.click(10, ClickType.LEFT);
        viewer.click(11, ClickType.LEFT);

        assertThat(bought).containsExactly("A", "B");

        keyed = false;
        redraw();
        harness.advance(Sample.SETTLED);
        assertThat(menu.sessions.getLast().buttons().get(9).key()).isEqualTo("row:9");
        viewer.click(9, ClickType.LEFT);
        viewer.click(10, ClickType.LEFT);

        assertThat(bought).containsExactly("A", "B", "A", "A");
    }

    @Test
    void anEntryHasNoKeyUnlessOneIsGivenAndKeepsItWhenMadeExclusive() {
        Icon icon = new Icon.Button(Material.PAPER, 1, Component.text("Row"), List.of(), false);
        Frame.Entry plain = new Frame.Entry(icon, null, Set.of(), false);

        assertThat(plain.key()).isNull();
        assertThat(Frame.Entry.click(icon, click -> { }).key()).isNull();
        assertThat(Frame.Entry.display(icon).key()).isNull();
        assertThat(plain.key("listing-7").exclusively()).isEqualTo(new Frame.Entry(icon, null, Set.of(), true,
            "listing-7"));
        assertThat(plain.key()).isNull();
        assertThatThrownBy(() -> plain.key(null)).isInstanceOf(NullPointerException.class);
    }
}
