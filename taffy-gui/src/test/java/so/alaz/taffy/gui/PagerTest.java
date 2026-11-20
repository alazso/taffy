package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class PagerTest {

    @TempDir
    Path folder;

    @Test
    void capacityIsTheSmallerOfThePageSizeAndTheListSlots() {
        assertThat(Pager.capacity(45, 36)).isEqualTo(36);
        assertThat(Pager.capacity(20, 36)).isEqualTo(20);
        assertThat(Pager.capacity(45, 45)).isEqualTo(45);
    }

    @Test
    void anEmptyListHasOneEmptyPage() {
        assertThat(Pager.page(0, 36, 0)).isEqualTo(new Pager.Page(0, 1, 0, 0));
    }

    @Test
    void rowsSplitIntoPagesOfCapacity() {
        assertThat(Pager.page(36, 36, 0)).isEqualTo(new Pager.Page(0, 1, 0, 36));
        assertThat(Pager.page(37, 36, 1)).isEqualTo(new Pager.Page(1, 2, 36, 37));
    }

    @Test
    void aRequestedPageClampsIntoRange() {
        assertThat(Pager.page(37, 36, 5).index()).isEqualTo(1);
        assertThat(Pager.page(37, 36, -1).index()).isZero();
    }

    @Test
    void theFirstPageHasNextTheMiddleBothAndTheLastPrevious() {
        assertThat(Pager.page(80, 36, 0)).extracting(Pager.Page::previous, Pager.Page::next).containsExactly(false,
            true);
        assertThat(Pager.page(80, 36, 1)).extracting(Pager.Page::previous, Pager.Page::next).containsExactly(true,
            true);
        assertThat(Pager.page(80, 36, 2)).extracting(Pager.Page::previous, Pager.Page::next).containsExactly(true,
            false);
    }

    @Test
    void aCapacityBelowOneIsRefused() {
        assertThatThrownBy(() -> Pager.page(3, 0, 0)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a page holds at least one row, not 0");
    }

    @Test
    void aNegativeCountIsRefused() {
        assertThatThrownBy(() -> Pager.page(-1, 36, 0)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a list holds at least zero entries, not -1");
    }

    @Test
    void aPageTellsWhetherAPageComesBeforeOrAfterIt() {
        assertThat(new Pager.Page(0, 1, 0, 0)).extracting(Pager.Page::previous, Pager.Page::next)
            .containsExactly(false, false);
        assertThat(new Pager.Page(0, 3, 0, 36)).extracting(Pager.Page::previous, Pager.Page::next)
            .containsExactly(false, true);
        assertThat(new Pager.Page(2, 3, 72, 80)).extracting(Pager.Page::previous, Pager.Page::next)
            .containsExactly(true, false);
    }

    @Test
    void aPageRefusesNumbersThatDoNotDescribeOne() {
        assertThatThrownBy(() -> new Pager.Page(0, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a list has at least one page, not 0");
        assertThatThrownBy(() -> new Pager.Page(-1, 2, 0, 0)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("page -1 is outside 0 to 1");
        assertThatThrownBy(() -> new Pager.Page(2, 2, 0, 0)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("page 2 is outside 0 to 1");
        assertThatThrownBy(() -> new Pager.Page(0, 1, -1, 0)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a page starts at entry 0 or later, not -1");
        assertThatThrownBy(() -> new Pager.Page(0, 1, 5, 4)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a page ends at or after its start 5, not at 4");
    }

    @Test
    void aPageWithItsIndexAndPageCountSwappedIsRefused() {
        assertThat(new Pager.Page(1, 3, 0, 0).index()).isEqualTo(1);
        assertThatThrownBy(() -> new Pager.Page(3, 1, 0, 0)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("page 3 is outside 0 to 0");
    }

    @Test
    void aCountNearTheIntegerLimitSplitsWithoutOverflow() {
        int count = Integer.MAX_VALUE;
        int pages = 59_652_324;

        assertThat(Pager.page(count, 36, 0)).isEqualTo(new Pager.Page(0, pages, 0, 36));
        assertThat(Pager.page(count, 36, Integer.MAX_VALUE))
            .isEqualTo(new Pager.Page(pages - 1, pages, 2_147_483_628, count));
        assertThat(Pager.page(count, 1, Integer.MAX_VALUE))
            .isEqualTo(new Pager.Page(count - 1, count, count - 1, count));
        assertThat(Pager.page(count, Integer.MAX_VALUE, 3))
            .isEqualTo(new Pager.Page(0, 1, 0, count));
    }

    @Test
    void theListLayoutShows36RowsAPageAndThePagerButtonsAtTheBounds() {
        MenuHarness harness = new MenuHarness(folder);
        list(harness, 80);
        MenuHarness.Viewer viewer = harness.viewer("Alex");
        harness.open(viewer, MenuHarness.LIST.id());

        assertThat(shown(viewer)).isEqualTo(IntStream.range(0, 36).mapToObj(row -> "Row " + row).toList());
        assertThat(viewer.filler(48)).isTrue();
        assertThat(viewer.name(50)).isEqualTo("Next page");
        assertThat(viewer.lore(50)).containsExactly("Page 1 of 3");
        assertThat(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 45, 46, 47, 49, 51, 52)).allMatch(viewer::filler);

        harness.advance(Sample.SETTLED);
        viewer.click(50, ClickType.LEFT);
        assertThat(shown(viewer).getFirst()).isEqualTo("Row 0");
        harness.drain();
        assertThat(shown(viewer)).hasSize(36).first().isEqualTo("Row 36");
        assertThat(viewer.lore(48)).containsExactly("Page 2 of 3");
        assertThat(viewer.lore(50)).containsExactly("Page 2 of 3");

        harness.advance(Sample.SETTLED);
        viewer.click(50, ClickType.LEFT);
        harness.drain();
        assertThat(shown(viewer)).isEqualTo(IntStream.range(72, 80).mapToObj(row -> "Row " + row).toList());
        assertThat(IntStream.range(17, 45).boxed().toList()).allMatch(viewer::filler);
        assertThat(viewer.filler(50)).isTrue();
        assertThat(viewer.name(48)).isEqualTo("Previous page");

        harness.advance(Sample.SETTLED);
        viewer.click(48, ClickType.LEFT);
        harness.drain();
        assertThat(shown(viewer).getFirst()).isEqualTo("Row 36");
    }

    @Test
    void aRequestedPagePastTheEndShowsTheLastPageAndTheArrowsStepFromIt() {
        MenuHarness harness = new MenuHarness(folder);
        list(harness, 40);
        MenuHarness.Viewer viewer = harness.viewer("Alex");

        harness.menus.openLater(viewer.player, new Route(MenuHarness.LIST.id(), 5, null));
        harness.drain();
        assertThat(shown(viewer)).containsExactly("Row 36", "Row 37", "Row 38", "Row 39");

        harness.advance(Sample.SETTLED);
        viewer.click(48, ClickType.LEFT);
        harness.drain();
        assertThat(viewer.session().state()).isEqualTo(0);
        assertThat(shown(viewer).getFirst()).isEqualTo("Row 0");
    }

    @Test
    void aSmallerPageSizeLeavesTheOtherListSlotsAsFiller() {
        MenuHarness harness = new MenuHarness(folder, MenuSettings.DEFAULTS.pageSize(20), Map.of());
        list(harness, 30);
        MenuHarness.Viewer viewer = harness.viewer("Alex");
        harness.open(viewer, MenuHarness.LIST.id());

        assertThat(shown(viewer)).hasSize(20);
        assertThat(IntStream.range(29, 45).boxed().toList()).allMatch(viewer::filler);
        assertThat(viewer.lore(50)).containsExactly("Page 1 of 2");
    }

    private static void list(MenuHarness harness, int rows) {
        TestMenu menu = new TestMenu(MenuHarness.LIST);
        List<Frame.Entry> entries = IntStream.range(0, rows).mapToObj(row -> Frame.Entry.display(new Icon.Button(
            Material.STONE, 1, Component.text("Row " + row), List.of(), false))).toList();
        menu.render = frame -> frame.footer(frame.list(entries, frame.state()), page -> page, null);
        harness.menus.register(menu);
    }

    private static List<String> shown(MenuHarness.Viewer viewer) {
        return IntStream.range(9, 45).mapToObj(viewer::name).filter(name -> name != null && name.startsWith("Row"))
            .toList();
    }
}
