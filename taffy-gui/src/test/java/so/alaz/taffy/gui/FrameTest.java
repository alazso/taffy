package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class FrameTest {

    private static final MenuKind SHELF = MenuKind.of("shelf", true, MenuKind.CLOSE);
    private static final MenuKind RACK = MenuKind.of("rack", true, MenuKind.CLOSE);

    // The shelf shows its empty state as a barrier in slot 9 and has no lang text for it; the rack has no empty slot.
    private static final Map<String, MenuLayout> EXTRA = Map.of(
        SHELF.id(), MenuHarness.layout(SHELF.id(),
            PagerLayout.of(List.of(9, 10, 11)).emptySlot(9).emptyMaterial(Material.BARRIER), List.of()),
        RACK.id(), MenuHarness.layout(RACK.id(), PagerLayout.of(List.of(9, 10, 11)), List.of()));

    private static final Icon ROW = new Icon.Button(Material.STONE, 1, Component.text("Row"), List.of(), false);

    @TempDir
    Path folder;

    @Test
    void listPlacesThePageOfRowsAndOnlyClickableRowsBecomeButtonsKeyedBySlot() {
        MenuHarness harness = new MenuHarness(folder);
        AtomicInteger clicks = new AtomicInteger();
        List<Frame.Entry> entries = List.of(Frame.Entry.display(ROW),
            Frame.Entry.click(ROW, click -> clicks.incrementAndGet()),
            Frame.Entry.click(ROW, click -> clicks.addAndGet(10)).exclusively());
        List<Pager.Page> pages = new ArrayList<>();
        TestMenu menu = new TestMenu(MenuHarness.LIST);
        menu.render = frame -> pages.add(frame.list(entries, frame.state()));
        harness.menus.register(menu);
        MenuHarness.Viewer viewer = harness.viewer("Alex");

        harness.open(viewer, MenuHarness.LIST.id());

        assertThat(pages).containsExactly(new Pager.Page(0, 1, 0, 3));
        assertThat(List.of(9, 10, 11)).allSatisfy(slot -> assertThat(viewer.icon(slot)).isEqualTo(ROW));
        Map<Integer, Button> buttons = viewer.session().buttons();
        assertThat(buttons).containsOnlyKeys(10, 11);
        assertThat(buttons.get(10).key()).isEqualTo("row:10");
        assertThat(buttons.get(10).clicks()).isEqualTo(ClickGuard.ANY_PLAIN);
        assertThat(buttons.get(10).exclusive()).isFalse();
        assertThat(buttons.get(11).key()).isEqualTo("row:11");
        assertThat(buttons.get(11).exclusive()).isTrue();
        harness.advance(Sample.SETTLED);
        viewer.click(10, ClickType.LEFT);
        viewer.click(11, ClickType.RIGHT);
        assertThat(clicks).hasValue(11);
    }

    @Test
    void loadingFillsTheFirstCapacitySlotsAndTakesNoClick() {
        MenuHarness harness = new MenuHarness(folder, MenuSettings.DEFAULTS.pageSize(20), Map.of());
        List<Integer> capacities = new ArrayList<>();
        TestMenu menu = new TestMenu(MenuHarness.LIST);
        menu.render = frame -> {
            frame.loading();
            capacities.add(frame.capacity());
        };
        harness.menus.register(menu);
        MenuHarness.Viewer viewer = harness.viewer("Alex");

        harness.open(viewer, MenuHarness.LIST.id());

        assertThat(capacities).containsExactly(20);
        assertThat(IntStream.range(9, 29).boxed().toList()).allSatisfy(slot -> {
            assertThat(viewer.icon(slot)).isInstanceOf(Icon.Button.class);
            assertThat(((Icon.Button) viewer.icon(slot)).material()).isEqualTo(Material.CLOCK);
            assertThat(viewer.name(slot)).isEqualTo("Loading...");
        });
        assertThat(IntStream.range(29, 45).boxed().toList()).allMatch(viewer::filler);
        assertThat(viewer.session().buttons()).isEmpty();
    }

    @Test
    void emptyShowsPaperInTheEmptySlotWithTheMenusEmptyText() {
        MenuHarness harness = new MenuHarness(folder);
        TestMenu menu = new TestMenu(MenuHarness.LIST);
        menu.render = Frame::empty;
        harness.menus.register(menu);
        MenuHarness.Viewer viewer = harness.viewer("Alex");

        harness.open(viewer, MenuHarness.LIST.id());

        assertThat(((Icon.Button) viewer.icon(22)).material()).isEqualTo(Material.PAPER);
        assertThat(viewer.name(22)).isEqualTo("Nothing here");
        assertThat(viewer.lore(22)).containsExactly("Nothing was found,", "try again later.");
        assertThat(viewer.session().buttons()).isEmpty();
    }

    @Test
    void emptyUsesTheLayoutsItemWithoutTextAndNothingWithoutAnEmptySlot() {
        MenuHarness harness = new MenuHarness(folder, MenuSettings.DEFAULTS, EXTRA);
        TestMenu shelf = new TestMenu(SHELF);
        shelf.render = Frame::empty;
        TestMenu rack = new TestMenu(RACK);
        rack.render = Frame::empty;
        harness.menus.register(shelf);
        harness.menus.register(rack);
        MenuHarness.Viewer viewer = harness.viewer("Alex");

        harness.open(viewer, SHELF.id());
        assertThat(viewer.icon(9)).isEqualTo(new Icon.Button(Material.BARRIER, 1, null, List.of(), false));

        harness.open(viewer, RACK.id());
        assertThat(IntStream.range(0, 54).boxed().toList()).allMatch(viewer::filler);
    }

    @Test
    void listByIndexAsksOnlyForTheEntriesOfThePageShown() {
        MenuHarness harness = new MenuHarness(folder);
        List<Integer> asked = new ArrayList<>();
        List<Pager.Page> pages = new ArrayList<>();
        TestMenu menu = new TestMenu(MenuHarness.LIST);
        menu.render = frame -> pages.add(frame.list(100, frame.state(), index -> {
            asked.add(index);
            return Frame.Entry.click(new Icon.Button(Material.PAPER, 1, Component.text("Row " + index), List.of(),
                false), click -> {
                });
        }));
        harness.menus.register(menu);
        MenuHarness.Viewer viewer = harness.viewer("Alex");

        harness.menus.open(viewer.player, new Route(MenuHarness.LIST.id(), 1, null));

        assertThat(pages).containsExactly(new Pager.Page(1, 3, 36, 72));
        assertThat(asked).containsExactlyElementsOf(IntStream.range(36, 72).boxed().toList());
        assertThat(viewer.name(9)).isEqualTo("Row 36");
        assertThat(viewer.name(44)).isEqualTo("Row 71");
        assertThat(viewer.session().buttons().get(9).key()).isEqualTo("row:9");
    }

    @Test
    void listByIndexAsksForNothingOnAnEmptyListAndRefusesANullEntryAndANegativeCount() {
        MenuHarness harness = new MenuHarness(folder);
        List<Pager.Page> pages = new ArrayList<>();
        List<Runnable> calls = new ArrayList<>();
        TestMenu menu = new TestMenu(MenuHarness.LIST);
        menu.render = frame -> {
            pages.add(frame.list(0, 4, index -> {
                throw new AssertionError("asked for " + index);
            }));
            calls.add(() -> frame.list(1, 0, index -> null));
            calls.add(() -> frame.list(-1, 0, index -> Frame.Entry.display(ROW)));
            calls.add(() -> frame.list(1, 0, null));
        };
        harness.menus.register(menu);

        harness.open(harness.viewer("Alex"), MenuHarness.LIST.id());

        assertThat(pages).containsExactly(new Pager.Page(0, 1, 0, 0));
        assertThatThrownBy(calls.get(0)::run).isInstanceOf(NullPointerException.class).hasMessage("entry 0");
        assertThatThrownBy(calls.get(1)::run).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a list holds at least zero entries, not -1");
        assertThatThrownBy(calls.get(2)::run).isInstanceOf(NullPointerException.class).hasMessage("entryAt");
    }

    @Test
    void aMenuWithoutAListRefusesEveryListCall() {
        MenuHarness harness = new MenuHarness(folder);
        List<Runnable> calls = new ArrayList<>();
        TestMenu menu = new TestMenu(MenuHarness.MAIN);
        menu.render = frame -> {
            calls.add(() -> frame.list(List.of(), 0));
            calls.add(() -> frame.list(0, 0, index -> Frame.Entry.display(ROW)));
            calls.add(frame::capacity);
            calls.add(frame::loading);
            calls.add(frame::empty);
        };
        harness.menus.register(menu);
        harness.open(harness.viewer("Alex"), MenuHarness.MAIN.id());

        assertThat(calls).hasSize(5).allSatisfy(call -> assertThatThrownBy(call::run)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("the main menu has no list"));
    }

    @Test
    void aSpecSetsTheAmountGlintExtraLoreClicksAndAWholeIcon() {
        MenuHarness harness = new MenuHarness(folder);
        Icon whole = new Icon.Filler(Material.BLACK_STAINED_GLASS_PANE);
        TestMenu menu = new TestMenu(MenuHarness.LIST);
        menu.render = frame -> {
            frame.action(MenuKind.BACK, Frame.Spec.display().amount(3).glint(true)
                .lore(List.of(Component.text("Extra"))));
            frame.action(MenuKind.CLOSE, Frame.Spec.click(click -> { }).icon(whole).clicks(ClickGuard.LEFT_ONLY));
        };
        harness.menus.register(menu);
        MenuHarness.Viewer viewer = harness.viewer("Alex");

        harness.open(viewer, MenuHarness.LIST.id());

        Icon.Button back = (Icon.Button) viewer.icon(45);
        assertThat(back.material()).isEqualTo(Material.OAK_DOOR);
        assertThat(back.amount()).isEqualTo(3);
        assertThat(back.glint()).isTrue();
        assertThat(viewer.name(45)).isEqualTo("Back");
        assertThat(viewer.lore(45)).containsExactly("Return to the previous menu.", "Extra");
        assertThat(viewer.session().buttons()).containsOnlyKeys(53);
        assertThat(viewer.icon(53)).isEqualTo(whole);
        assertThat(viewer.session().buttons().get(53).key()).isEqualTo("close");
        assertThat(viewer.session().buttons().get(53).clicks()).isEqualTo(ClickGuard.LEFT_ONLY);
        assertThatThrownBy(() -> Frame.Spec.click(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Frame.Entry.click(ROW, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Frame.Entry.display(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aFrameGivesTheRendersStateViewerLocaleLayoutKindSessionAndMenus() {
        MenuHarness harness = new MenuHarness(folder);
        List<Frame<Integer>> frames = new ArrayList<>();
        TestMenu menu = new TestMenu(MenuHarness.MAIN);
        menu.render = frames::add;
        harness.menus.register(menu);
        MenuHarness.Viewer viewer = harness.viewer("Alex");
        viewer.locale = Locale.GERMAN;

        harness.menus.openLater(viewer.player, new Route(MenuHarness.MAIN.id(), 7, null));
        harness.drain();

        Frame<Integer> frame = frames.getFirst();
        assertThat(frame.state()).isEqualTo(7);
        assertThat(frame.viewer()).isSameAs(viewer.player);
        assertThat(frame.locale()).isEqualTo(Locale.GERMAN);
        assertThat(frame.layout()).isSameAs(MenuHarness.LAYOUTS.get("main"));
        assertThat(frame.kind()).isEqualTo(MenuHarness.MAIN);
        assertThat(frame.session()).isSameAs(viewer.session());
        assertThat(frame.menus()).isSameAs(harness.menus);
        assertThat(frame.text()).isSameAs(harness.menus.text());
    }
}
