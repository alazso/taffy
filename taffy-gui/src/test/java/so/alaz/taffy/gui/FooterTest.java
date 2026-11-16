package so.alaz.taffy.gui;

import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

final class FooterTest {

    @TempDir
    Path folder;

    @Test
    void backReopensTheRouteTheMenuCameFromWithItsState() {
        MenuHarness harness = new MenuHarness(folder);
        harness.menus.register(footed(MenuHarness.LIST));
        harness.menus.register(footed(MenuHarness.OPTIONS));
        MenuHarness.Viewer viewer = harness.viewer("Alex");
        Route from = new Route(MenuHarness.LIST.id(), 2, null);

        harness.menus.openLater(viewer.player, MenuHarness.OPTIONS.id(), from);
        harness.drain();
        assertThat(viewer.name(45)).isEqualTo("Back");
        assertThat(viewer.lore(45)).containsExactly("Return to the previous menu.");
        harness.advance(Sample.SETTLED);
        viewer.click(45, ClickType.LEFT);
        harness.drain();

        assertThat(viewer.session()).isNotNull();
        assertThat(viewer.session().route()).isEqualTo(from);
    }

    @Test
    void backOpensTheHomeMenuWhenTheMenuHasNoRouteAndTheHomeMenuHasNoBack() {
        MenuHarness harness = new MenuHarness(folder);
        harness.menus.register(footed(MenuHarness.MAIN));
        harness.menus.register(footed(MenuHarness.OPTIONS));
        MenuHarness.Viewer viewer = harness.viewer("Alex");

        harness.open(viewer, MenuHarness.OPTIONS.id());
        harness.advance(Sample.SETTLED);
        viewer.click(45, ClickType.LEFT);
        harness.drain();

        assertThat(viewer.session().id()).isEqualTo("main");
        assertThat(viewer.filler(45)).isTrue();
        assertThat(viewer.name(53)).isEqualTo("Close");
    }

    @Test
    void backIsFillerWithoutARouteWhenTheHomeMenuIsNotRegistered() {
        MenuHarness harness = new MenuHarness(folder);
        harness.menus.register(footed(MenuHarness.OPTIONS));
        MenuHarness.Viewer viewer = harness.viewer("Alex");

        harness.open(viewer, MenuHarness.OPTIONS.id());

        assertThat(viewer.filler(45)).isTrue();
    }

    @Test
    void backIsFillerWithoutARouteWhenThereIsNoHomeMenu() {
        MenuHarness harness = new MenuHarness(folder, MenuSettings.DEFAULTS, Map.of(), null);
        harness.menus.register(footed(MenuHarness.MAIN));
        harness.menus.register(footed(MenuHarness.OPTIONS));
        MenuHarness.Viewer viewer = harness.viewer("Alex");

        harness.open(viewer, MenuHarness.OPTIONS.id());

        assertThat(viewer.filler(45)).isTrue();
        assertThat(viewer.name(53)).isEqualTo("Close");
    }

    @Test
    void refreshShowsWithAHandlerAndIsExclusive() {
        MenuHarness harness = new MenuHarness(folder);
        AtomicInteger refreshes = new AtomicInteger();
        TestMenu list = new TestMenu(MenuHarness.LIST);
        list.render = frame -> frame.footer(null, null, click -> {
            refreshes.incrementAndGet();
            click.session().busy(true);
        });
        harness.menus.register(list);
        MenuHarness.Viewer viewer = harness.viewer("Alex");
        harness.open(viewer, MenuHarness.LIST.id());
        harness.advance(Sample.SETTLED);

        assertThat(viewer.name(51)).isEqualTo("Refresh");
        assertThat(viewer.session().buttons().get(51).exclusive()).isTrue();
        assertThat(viewer.session().buttons().get(53).exclusive()).isFalse();
        viewer.click(51, ClickType.LEFT);
        assertThat(viewer.session().busy()).isTrue();
        harness.advance(Sample.SETTLED);
        viewer.click(51, ClickType.LEFT);

        assertThat(refreshes).hasValue(1);
        viewer.session().busy(false);
        viewer.click(51, ClickType.LEFT);
        assertThat(refreshes).hasValue(2);
    }

    @Test
    void aMenusOwnKeyForAFooterActionWinsOverTheFooterTextInThatLanguage() throws IOException {
        Files.createDirectories(folder.resolve("lang"));
        Files.writeString(folder.resolve("lang/de.yml"), "gui.list.close.name: Schliessen");
        MenuHarness harness = new MenuHarness(folder);
        harness.menus.register(footed(MenuHarness.LIST));
        MenuHarness.Viewer english = harness.viewer("Alex");
        MenuHarness.Viewer german = harness.viewer("Sam");
        german.locale = Locale.GERMAN;

        harness.open(english, MenuHarness.LIST.id());
        harness.open(german, MenuHarness.LIST.id());

        assertThat(MenuHarness.LIST.textBases(MenuKind.CLOSE)).containsExactly("gui.list.close", "gui.footer.close");
        assertThat(english.name(53)).isEqualTo("Close");
        assertThat(german.name(53)).isEqualTo("Schliessen");
    }

    private static TestMenu footed(MenuKind kind) {
        TestMenu menu = new TestMenu(kind);
        menu.render = frame -> frame.footer(null, null, null);
        return menu;
    }

    @Test
    void theFramesFooterPlacesWhatFooterDrawPlaces() {
        MenuHarness harness = new MenuHarness(folder);
        TestMenu viaFrame = new TestMenu(MenuHarness.OPTIONS);
        viaFrame.render = frame -> frame.footer(null, null, null);
        TestMenu viaFooter = new TestMenu(MenuHarness.MAIN);
        viaFooter.render = frame -> Footer.draw(frame, null, null, null);
        harness.menus.register(viaFrame);
        harness.menus.register(viaFooter);
        MenuHarness.Viewer alex = harness.viewer("Alex");
        MenuHarness.Viewer sam = harness.viewer("Sam");
        Route back = new Route(MenuHarness.OPTIONS.id(), 0, null);

        harness.menus.openLater(alex.player, MenuHarness.OPTIONS.id(), back);
        harness.menus.openLater(sam.player, MenuHarness.MAIN.id(), back);
        harness.drain();

        assertThat(alex.session().buttons().keySet()).containsExactlyInAnyOrder(45, 53);
        assertThat(sam.session().buttons().keySet()).isEqualTo(alex.session().buttons().keySet());
        assertThat(alex.name(45)).isEqualTo(sam.name(45)).isEqualTo("Back");
        assertThat(alex.name(53)).isEqualTo(sam.name(53)).isEqualTo("Close");
    }
}
