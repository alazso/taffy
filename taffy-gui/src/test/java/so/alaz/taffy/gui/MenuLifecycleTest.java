package so.alaz.taffy.gui;

import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.bukkit.entity.Player;
import so.alaz.taffy.platform.EntityThread;
import so.alaz.taffy.platform.EntityUnavailableException;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.platform.Messages;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

final class MenuLifecycleTest {

    private static final MenuKind PLAIN = MenuKind.of("plain", false, "show", MenuKind.CLOSE);

    private static final MenuLayout PLAIN_LAYOUT = plainLayout();

    @TempDir
    Path folder;

    @Test
    void openRunsOnTheNextTickOnly() {
        Sample sample = new Sample(folder);
        MenuHarness.Viewer sam = sample.harness.viewer("Sam");

        sample.harness.menus.openLater(sam.player, Sample.KIND.id(), null);

        assertThat(sam.view).isNull();
        assertThat(sample.harness.inventories.created).hasSize(1);
        sample.harness.drain();
        assertThat(sam.session()).isNotNull();
        assertThat(sample.harness.prompts.ended).contains(sam.id);
    }

    @Test
    void everyTopSlotHoldsAnIconAfterRenderAndTwoViewersGetTwoInventories() {
        Sample sample = new Sample(folder);
        MenuHarness.Viewer sam = sample.harness.viewer("Sam");
        sample.harness.open(sam, Sample.KIND.id());

        GuiFakes.FakeCanvas canvas = sample.harness.inventories.canvases.get(sample.viewer.current().getTopInventory());
        assertThat(canvas.slots).hasSize(54).doesNotContainNull();
        assertThat(canvas.writes).isEqualTo(54);
        assertThat(canvas.slots[20]).isEqualTo(new Icon.Filler(Material.GRAY_STAINED_GLASS_PANE));
        assertThat(sample.viewer.current().getTopInventory()).isNotSameAs(sam.current().getTopInventory());
        assertThat(sample.viewer.session()).isNotSameAs(sam.session());
        assertThat(sample.harness.menus.session(sam.id)).containsSame(sam.session());
    }

    @Test
    void decorationsShowAsWrittenAndATitleInTheLayoutWins() {
        Sample sample = new Sample(folder);

        assertThat(sample.viewer.name(4)).isEqualTo("Decoration");
        assertThat(MenuHarness.plain(sample.harness.inventories.titles.getFirst())).isEqualTo("Sample");
        assertThat(sample.viewer.name(10)).isEqualTo("Act");
        assertThat(((Icon.Button) sample.viewer.icon(11)).material()).isEqualTo(Material.DIAMOND);
        assertThat(sample.viewer.name(11)).isEqualTo("missing:gui.sample.left.name");
    }

    @Test
    void aDecorationNeedsItsPermissionAndAnEnabledItem() {
        MenuKind kind = MenuKind.of("decor", false);
        Map<String, ItemLayout> items = new LinkedHashMap<>();
        items.put("open", ItemLayout.of("open", List.of(0), Material.STONE).name("Open"));
        items.put("staff", ItemLayout.of("staff", List.of(1), Material.STONE).permission("taffy.test.staff")
            .name("Staff"));
        items.put("off", ItemLayout.of("off", List.of(2), Material.STONE).enabled(false).name("Off"));
        MenuHarness harness = new MenuHarness(folder, MenuSettings.DEFAULTS, Map.of(kind.id(),
            MenuLayout.of(kind.id(), 1).filler(Material.BLACK_STAINED_GLASS_PANE).items(items.values())
                .title("Decor")));
        harness.menus.register(new TestMenu(kind));
        MenuHarness.Viewer allowed = harness.viewer("Alex");
        MenuHarness.Viewer denied = harness.viewer("Sam");
        denied.denied.add("taffy.test.staff");

        harness.open(allowed, kind.id());
        harness.open(denied, kind.id());

        Icon filler = new Icon.Filler(Material.BLACK_STAINED_GLASS_PANE);
        assertThat(allowed.name(0)).isEqualTo("Open");
        assertThat(allowed.name(1)).isEqualTo("Staff");
        assertThat(allowed.icon(2)).isEqualTo(filler);
        assertThat(denied.name(0)).isEqualTo("Open");
        assertThat(denied.icon(1)).isEqualTo(filler);
        assertThat(denied.icon(2)).isEqualTo(filler);
    }

    @Test
    void aRouteWhoseStateIsNotOfTheMenusStateTypeIsRefusedBeforeAnythingOpens() {
        Sample sample = new Sample(folder);
        sample.menu.stateType = Integer.class;
        Route wrong = new Route(Sample.KIND.id(), "seven", null);
        int created = sample.harness.inventories.created.size();

        assertThatThrownBy(() -> sample.harness.menus.open(sample.viewer.player, wrong))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the sample menu shows a state of the type java.lang.Integer, not java.lang.String");
        assertThatThrownBy(() -> sample.harness.menus.openLater(sample.viewer.player, wrong))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the sample menu shows a state of the type java.lang.Integer, not java.lang.String");

        assertThat(sample.harness.tasks.queue).isEmpty();
        assertThat(sample.harness.inventories.created).hasSize(created);
        assertThat(sample.menu.closes).isEmpty();
        sample.harness.menus.open(sample.viewer.player, new Route(Sample.KIND.id(), 7, null));
        assertThat(sample.session().state()).isEqualTo(7);
    }

    @Test
    void aMenuWithoutAStateTypeAcceptsEveryRouteAndFailsInItsRender() {
        Sample sample = new Sample(folder);
        sample.menu.render = frame -> {
            Integer page = frame.state();
            frame.footer(null, null, null);
        };

        assertThatThrownBy(() -> sample.harness.menus.open(sample.viewer.player,
            new Route(Sample.KIND.id(), "seven", null))).isInstanceOf(ClassCastException.class);
    }

    @Test
    void theTitleComesFromTheLangFileWithoutAnOverride() {
        MenuHarness harness = new MenuHarness(folder);
        harness.menus.register(new TestMenu(MenuHarness.MAIN));

        harness.open(harness.viewer("Alex"), MenuHarness.MAIN.id());

        assertThat(MenuHarness.plain(harness.inventories.titles.getFirst())).isEqualTo("Home");
    }

    @Test
    void aButtonNeedsItsPermissionAndAnEnabledItemAndTheLayoutFillerFillsTheRest() {
        MenuHarness harness = plainHarness();
        TestMenu menu = new TestMenu(PLAIN);
        menu.render = frame -> frame.action("show", Frame.Spec.display().with(Placeholder.unparsed("count", "3")));
        harness.menus.register(menu);
        MenuHarness.Viewer allowed = harness.viewer("Alex");
        MenuHarness.Viewer denied = harness.viewer("Sam");
        denied.denied.add("taffy.test.secret");

        harness.open(allowed, PLAIN.id());
        harness.open(denied, PLAIN.id());

        assertThat(allowed.name(0)).isEqualTo("Shown 3");
        assertThat(allowed.icon(1)).isInstanceOf(Icon.Button.class);
        assertThat(allowed.icon(2)).isEqualTo(new Icon.Filler(Material.BLACK_STAINED_GLASS_PANE));
        assertThat(denied.icon(1)).isEqualTo(new Icon.Filler(Material.BLACK_STAINED_GLASS_PANE));
        assertThat(allowed.icon(26)).isEqualTo(new Icon.Filler(Material.BLACK_STAINED_GLASS_PANE));
        assertThat(harness.inventories.created.getFirst().getSize()).isEqualTo(27);
    }

    @Test
    void anActionOutsideTheMenusSetIsRefused() {
        MenuHarness harness = plainHarness();
        TestMenu menu = new TestMenu(PLAIN);
        menu.render = frame -> frame.action("browse", Frame.Spec.display());
        harness.menus.register(menu);
        MenuHarness.Viewer viewer = harness.viewer("Alex");

        assertThatThrownBy(() -> harness.menus.open(viewer.player, PLAIN.id(), null))
            .hasMessage("the plain menu has no action browse");
        harness.open(viewer, PLAIN.id());

        assertThat(viewer.session()).isNull();
        assertThat(harness.reports).singleElement().satisfies(report -> {
            assertThat(report.source()).isEqualTo("gui.render");
            assertThat(report.error()).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the plain menu has no action browse");
            assertThat(harness.log.records.getFirst().getThrown()).isSameAs(report.error());
        });
        assertThat(harness.log.lines()).containsExactly("WARNING gui: a menu failed to draw for Alex:"
            + " java.lang.IllegalArgumentException: the plain menu has no action browse");
    }

    @Test
    void aRedrawThatFailsOnTheNextTickIsLoggedAndReported() {
        Sample sample = new Sample(folder);
        IllegalStateException failure = new IllegalStateException("broken");
        sample.menu.render = frame -> {
            throw failure;
        };

        sample.harness.menus.rerenderLater(sample.session());
        sample.harness.drain();

        assertThat(sample.harness.reports).containsExactly(new MenuHarness.Report("gui.render", failure));
        assertThat(sample.harness.log.records.getLast().getThrown()).isSameAs(failure);
    }

    @Test
    void aLayoutThatDoesNotFitItsKindIsRefusedAtRegisterAndAtReload() {
        MenuHarness harness = plainHarness();
        MenuKind listed = MenuKind.of(PLAIN.id(), true, "show", MenuKind.CLOSE);

        assertThatThrownBy(() -> harness.menus.register(new TestMenu(listed)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the plain menu shows a list, but its layout has none");
        assertThat(harness.menus.registered(PLAIN.id())).isFalse();
        harness.menus.register(new TestMenu(PLAIN));
        Map<String, ItemLayout> typo = new LinkedHashMap<>(PLAIN_LAYOUT.items());
        typo.put("shw", ItemLayout.of("shw", List.of(5), Material.PAPER).action("shw"));
        Map<String, MenuLayout> layouts = new LinkedHashMap<>(harness.layouts);
        layouts.put(PLAIN.id(), MenuLayout.of(PLAIN.id(), 3).items(typo.values()));

        assertThatThrownBy(() -> harness.menus.reload(layouts))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("item shw of the plain menu has the action shw, which the menu does not have");
        MenuHarness.Viewer viewer = harness.viewer("Alex");
        harness.open(viewer, PLAIN.id());
        assertThat(viewer.session().layout()).isSameAs(PLAIN_LAYOUT);
    }

    @Test
    void aLayoutMaterialThatIsNotAnItemIsRefusedAtRegisterAndAtReload() {
        Map<String, ItemLayout> wall = new LinkedHashMap<>(PLAIN_LAYOUT.items());
        wall.put("torch", MenuHarness.item("torch", 5, Material.WALL_TORCH, null).enabled(false));
        MenuLayout badItem = MenuLayout.of(PLAIN.id(), 3).items(wall.values());
        MenuLayout badFiller = MenuLayout.of(PLAIN.id(), 3).filler(Material.WATER).items(PLAIN_LAYOUT.items().values());
        MenuLayout badEmpty = MenuLayout.of(PLAIN.id(), 3)
            .pager(PagerLayout.of(List.of(9)).emptySlot(9).emptyMaterial(Material.AIR))
            .items(PLAIN_LAYOUT.items().values());
        MenuHarness refused = new MenuHarness(folder, MenuSettings.DEFAULTS, Map.of(PLAIN.id(), badItem));

        assertThatThrownBy(() -> refused.menus.register(new TestMenu(PLAIN)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("item torch of the plain menu has the material WALL_TORCH, which is not an item");
        assertThat(refused.menus.registered(PLAIN.id())).isFalse();

        MenuHarness harness = plainHarness();
        harness.menus.register(new TestMenu(PLAIN));
        Map<String, MenuLayout> layouts = new LinkedHashMap<>(harness.layouts);
        layouts.put(PLAIN.id(), badFiller);
        assertThatThrownBy(() -> harness.menus.reload(layouts))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the filler of the plain menu has the material WATER, which is not an item");
        layouts.put(PLAIN.id(), badEmpty);
        assertThatThrownBy(() -> harness.menus.reload(layouts))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the empty state of the plain menu has the material AIR, which is not an item");
        layouts.put(PLAIN.id(), badItem);
        assertThatThrownBy(() -> harness.menus.reload(layouts))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("item torch of the plain menu has the material WALL_TORCH, which is not an item");
        MenuHarness.Viewer viewer = harness.viewer("Alex");
        harness.open(viewer, PLAIN.id());
        assertThat(viewer.session().layout()).isSameAs(PLAIN_LAYOUT);
    }

    @Test
    void aSettingsMaterialThatIsNotAnItemIsRefusedWhenAMenuRegisters() {
        MenuHarness filler = new MenuHarness(folder, MenuSettings.DEFAULTS.filler(Material.WATER),
            Map.of(PLAIN.id(), PLAIN_LAYOUT));
        MenuHarness loading = new MenuHarness(folder, MenuSettings.DEFAULTS.filler(Material.STONE)
            .loadingItem(Material.AIR), Map.of(PLAIN.id(), PLAIN_LAYOUT));

        assertThatThrownBy(() -> filler.menus.register(new TestMenu(PLAIN)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the filler of the menu settings has the material WATER, which is not an item");
        assertThatThrownBy(() -> loading.menus.register(new TestMenu(PLAIN)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the loading item of the menu settings has the material AIR, which is not an item");
        assertThat(loading.menus.registered(PLAIN.id())).isFalse();
    }

    @Test
    void aFooterActionTheKindLacksMayStandInTheLayout() {
        MenuHarness harness = new MenuHarness(folder);

        harness.menus.register(new TestMenu(MenuHarness.MAIN));

        assertThat(MenuHarness.LAYOUTS.get(MenuHarness.MAIN.id()).items(MenuKind.REFRESH)).isNotEmpty();
        assertThat(MenuHarness.MAIN.has(MenuKind.REFRESH)).isFalse();
        assertThat(harness.menus.registered(MenuHarness.MAIN.id())).isTrue();
    }

    @Test
    void keysListTheLangKeysTheMenusReadWithNoOtherTextToFallBackTo() {
        MenuKind shop = MenuKind.of("shop", false, "buy", MenuKind.BACK).text("buy", "gui.common.buy");
        MenuKind head = MenuKind.of("head", false, "skull").text("skull", null);

        assertThat(Menus.keys(List.of(MenuHarness.MAIN, shop, head))).containsExactly("gui.error", "gui.main.title",
            "gui.footer.back.name", "gui.footer.close.name", "gui.shop.title", "gui.common.buy.name",
            "gui.head.title");
        List<String> list = Menus.keys(List.of(MenuHarness.LIST));
        assertThat(list).containsExactly("gui.error", "gui.common.loading.name", "gui.list.title",
            "gui.footer.back.name", "gui.footer.previous-page.name", "gui.footer.next-page.name",
            "gui.footer.refresh.name", "gui.footer.close.name");
        MenuHarness harness = new MenuHarness(folder);
        assertThat(list).allMatch(key -> harness.messages.has(Locale.ENGLISH, key));
        assertThat(Menus.keys(List.of())).containsExactly("gui.error");
    }

    @Test
    void registeringNeedsAGivenLayoutAndAnUnusedId() {
        MenuHarness harness = new MenuHarness(folder);
        harness.menus.register(new TestMenu(MenuHarness.MAIN));

        assertThatThrownBy(() -> harness.menus.register(new TestMenu(MenuHarness.MAIN)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the main menu is already registered");
        assertThatThrownBy(() -> harness.menus.register(new TestMenu(PLAIN)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("no layout was given for the plain menu");
        assertThatThrownBy(() -> harness.menus.open(harness.viewer("Alex").player, "options", null))
            .hasMessage("no menu is registered as options");
        assertThat(harness.menus.registered("main")).isTrue();
        assertThat(harness.menus.registered("options")).isFalse();
    }

    @Test
    void aLayoutGivenUnderAnotherMenusIdIsRefused() {
        MenuHarness harness = new MenuHarness(folder);
        Map<String, MenuLayout> swapped = Map.of("options", MenuHarness.LAYOUTS.get("main"));

        assertThatThrownBy(() -> new MenuHarness(folder, MenuSettings.DEFAULTS, swapped))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the layout given for the options menu is the layout of the main menu");
        assertThatThrownBy(() -> harness.menus.reload(swapped))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the layout given for the options menu is the layout of the main menu");
    }

    @Test
    void openLaterRefusesAnUnregisteredMenuAtTheCall() {
        MenuHarness harness = new MenuHarness(folder);
        MenuHarness.Viewer viewer = harness.viewer("Alex");

        assertThatThrownBy(() -> harness.menus.openLater(viewer.player, "options", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("no menu is registered as options");
        assertThatThrownBy(() -> harness.menus.openLater(viewer.player, new Route("options", 0, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("no menu is registered as options");
        assertThat(harness.tasks.queue).isEmpty();
    }

    @Test
    void everyPartButHomeIsRequired() {
        Plugin plugin = MenuHarness.plugin(mock(Server.class), Logger.getAnonymousLogger());
        Messages messages = mock(Messages.class);
        GuiFakes.FakeTasks tasks = new GuiFakes.FakeTasks();
        EntityThread entity = mock(EntityThread.class);
        ErrorReports errors = new ErrorReports();
        Menus.Parts parts = Menus.Parts.of(plugin, messages, tasks, entity, errors);

        assertThatNullPointerException().isThrownBy(() -> Menus.Parts.of(null, messages, tasks, entity, errors))
            .withMessage("plugin");
        assertThatNullPointerException().isThrownBy(() -> Menus.Parts.of(plugin, (Messages) null, tasks, entity,
            errors)).withMessage("messages");
        assertThatNullPointerException().isThrownBy(() -> Menus.Parts.of(plugin, (TextSource) null, tasks, entity,
            errors)).withMessage("text");
        assertThatNullPointerException().isThrownBy(() -> Menus.Parts.of(plugin, messages, null, entity, errors))
            .withMessage("tasks");
        assertThatNullPointerException().isThrownBy(() -> Menus.Parts.of(plugin, messages, tasks, null, errors))
            .withMessage("entity");
        assertThatNullPointerException().isThrownBy(() -> Menus.Parts.of(plugin, messages, tasks, entity, null))
            .withMessage("errors");
        assertThatNullPointerException().isThrownBy(() -> parts.layouts(null));
        assertThatNullPointerException().isThrownBy(() -> parts.settings(null)).withMessage("settings");
        assertThatNullPointerException().isThrownBy(() -> parts.locales(null)).withMessage("locales");
        assertThatNullPointerException().isThrownBy(() -> parts.sounds(null)).withMessage("sounds");
        assertThatNullPointerException().isThrownBy(() -> parts.server(null)).withMessage("server");
        assertThatNullPointerException().isThrownBy(() -> parts.preview(null)).withMessage("preview");
        assertThatNullPointerException().isThrownBy(() -> parts.logger(null)).withMessage("logger");
        assertThat(parts.home("main").home(null).home()).isNull();
    }

    @Test
    void partsStartFromTheDefaultsAndEachMethodReplacesOnlyItsOwnPart() {
        Server server = mock(Server.class);
        Menus.Parts parts = parts(server);
        Supplier<Map<Cue, Sound>> sounds = () -> MenuHarness.SOUNDS;
        Function<Player, Locale> locales = player -> Locale.GERMAN;
        MenuSettings settings = MenuSettings.DEFAULTS.pageSize(9);
        Player player = mock(Player.class);
        when(player.locale()).thenReturn(Locale.FRENCH);

        Menus.Parts changed = parts.layouts(MenuHarness.LAYOUTS).settings(settings).locales(locales).sounds(sounds);

        assertThat(parts.layouts()).isEmpty();
        assertThat(parts.settings()).isEqualTo(MenuSettings.DEFAULTS);
        assertThat(parts.locales().apply(player)).isEqualTo(Locale.FRENCH);
        assertThat(parts.sounds().get()).isEmpty();
        assertThat(parts.home()).isEqualTo("main");
        assertThat(parts(server).home(null).home()).isNull();
        assertThat(changed.layouts()).isEqualTo(MenuHarness.LAYOUTS);
        assertThat(changed.settings()).isSameAs(settings);
        assertThat(changed.locales()).isSameAs(locales);
        assertThat(changed.sounds()).isSameAs(sounds);
        assertThat(changed.home()).isEqualTo("main");
        assertThat(changed.plugin()).isSameAs(parts.plugin());
        assertThat(changed.server()).isSameAs(server);
        assertThat(changed.preview()).isEqualTo(parts.preview());
        assertThat(changed.logger()).isSameAs(parts.logger());
        assertThat(changed.text()).isSameAs(parts.text());
        assertThat(changed.tasks()).isSameAs(parts.tasks());
        assertThat(changed.entity()).isSameAs(parts.entity());
        assertThat(changed.errors()).isSameAs(parts.errors());
    }

    @Test
    void theServerThePreviewKeyAndTheLoggerAreThePluginsOwnUnlessReplaced() {
        Server server = mock(Server.class);
        Logger logger = Logger.getAnonymousLogger();
        Plugin plugin = MenuHarness.plugin(server, logger);
        Menus.Parts parts = Menus.Parts.of(plugin, mock(Messages.class), new GuiFakes.FakeTasks(),
            mock(EntityThread.class), new ErrorReports());
        Server other = mock(Server.class);
        Logger quiet = Logger.getAnonymousLogger();
        NamespacedKey key = new NamespacedKey("taffytest", "copy");

        assertThat(parts.plugin()).isSameAs(plugin);
        assertThat(parts.server()).isSameAs(server);
        assertThat(parts.logger()).isSameAs(logger);
        assertThat(parts.preview()).isEqualTo(new NamespacedKey("taffytest", "preview"));
        assertThat(parts.server(other).server()).isSameAs(other);
        assertThat(parts.server(other).logger()).isSameAs(logger);
        assertThat(parts.logger(quiet).logger()).isSameAs(quiet);
        assertThat(parts.logger(quiet).preview()).isEqualTo(parts.preview());
        assertThat(parts.preview(key).preview()).isEqualTo(key);
        assertThat(parts.preview(key).server()).isSameAs(server);
        assertThat(parts.preview(key).plugin()).isSameAs(plugin);
    }

    @Test
    void thePublicConstructorRegistersTheMenusListenerOnceAsAListenerOfThePlugin() {
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        when(server.getPluginManager()).thenReturn(manager);
        Menus.Parts parts = parts(server);

        Menus menus = new Menus(parts);

        verify(manager).registerEvents(menus.menuListener(), parts.plugin());
        verifyNoMoreInteractions(manager);
        assertThat(menus.marker().key()).isEqualTo(new NamespacedKey("taffytest", "preview"));
    }

    @Test
    void noMenusExistWhenTheServerRefusesTheListenerSoAMenuCannotOpenUnregistered() {
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        when(server.getPluginManager()).thenReturn(manager);
        doThrow(new IllegalPluginAccessException("Plugin attempted to register a listener while not enabled"))
            .when(manager).registerEvents(any(Listener.class), any(Plugin.class));

        assertThatThrownBy(() -> new Menus(parts(server))).isInstanceOf(IllegalPluginAccessException.class);
    }

    @Test
    void everyMenusIsRegisteredBeforeItCanOpenAMenu() {
        Sample sample = new Sample(folder);

        assertThat(sample.harness.registered).containsExactly(sample.harness.listener);
        assertThat(sample.viewer.session()).isNotNull();
    }

    @Test
    void theDeprecatedListenerHasNoHandlerSoRegisteringItCannotRegisterTheMenusTwice() {
        MenuHarness harness = new MenuHarness(folder);

        @SuppressWarnings("deprecation")
        Listener given = harness.menus.listener();

        assertThat(given).isNotInstanceOf(MenuListener.class).isNotSameAs(harness.listener);
        assertThat(Arrays.stream(given.getClass().getMethods())
            .filter(method -> method.isAnnotationPresent(EventHandler.class))).isEmpty();
        assertThat(Arrays.stream(given.getClass().getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(EventHandler.class))).isEmpty();
        assertThat(harness.registered).containsExactly(harness.listener);
    }

    @Test
    void thePublicConstructorRefusesNullParts() {
        assertThatNullPointerException().isThrownBy(() -> new Menus(null)).withMessage("parts");
    }

    @Test
    void theOwnershipCheckOfThePublicConstructorAsksTheServer() {
        Server server = mock(Server.class);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        Player player = mock(Player.class);
        when(server.isOwnedByCurrentRegion(player)).thenReturn(true);

        assertThat(new Menus(parts(server)).ownedHere().test(player)).isTrue();

        verify(server).isOwnedByCurrentRegion(player);
    }

    @Test
    void playPlaysTheMappedSoundAndNothingForAnUnmappedCue() {
        Map<Cue, Sound> sounds = new EnumMap<>(Cue.class);
        sounds.put(Cue.SUCCESS, MenuHarness.SOUNDS.get(Cue.SUCCESS));
        MenuHarness harness = new MenuHarness(folder);
        Menus menus = new Menus(Menus.Parts.of(harness.plugin, harness.messages, harness.tasks, harness.entity,
            harness.errors).layouts(harness.layouts).sounds(() -> sounds), marker -> harness.inventories,
            player -> true, material -> true, harness.clock, listener -> {
            });
        MenuHarness.Viewer viewer = harness.viewer("Alex");

        menus.play(viewer.player, Cue.SUCCESS);
        menus.play(viewer.player, Cue.FAIL);
        sounds.put(Cue.FAIL, MenuHarness.SOUNDS.get(Cue.FAIL));
        menus.play(viewer.player, Cue.FAIL);

        assertThat(viewer.heard()).containsExactly("entity.experience_orb.pickup", "entity.villager.no");
    }

    @Test
    void shutdownEndsThePromptsBeforeAnyMenuClosesAndASecondShutdownDoesNothingMore() {
        Sample sample = new Sample(folder);
        sample.menu.closing = left -> sample.harness.prompts.calls.add("closed");

        sample.harness.menus.shutdown();
        sample.harness.menus.shutdown();

        assertThat(sample.harness.prompts.calls).containsExactly("shutdown", "closed");
        assertThat(sample.viewer.closes).containsExactly(InventoryCloseEvent.Reason.PLUGIN);
        assertThat(sample.menu.closes).containsExactly(false);
    }

    @Test
    void shutdownClosesTheMenusEvenWhenEndingThePromptsThrows() {
        Sample sample = new Sample(folder);
        sample.harness.menus.prompts(new MenuPrompts() {
            @Override
            public void end(UUID player) {
            }

            @Override
            public void quit(UUID player) {
            }

            @Override
            public boolean owns(InventoryView view) {
                return false;
            }

            @Override
            public void shutdown() {
                throw new IllegalStateException("boom");
            }
        });

        assertThatThrownBy(() -> sample.harness.menus.shutdown()).isInstanceOf(IllegalStateException.class)
            .hasMessage("boom");

        assertThat(sample.viewer.closes).containsExactly(InventoryCloseEvent.Reason.PLUGIN);
        assertThat(sample.menu.closes).containsExactly(false);
        assertThat(sample.harness.menus.stopping()).isTrue();
    }

    private static Menus.Parts parts(Server server) {
        return Menus.Parts.of(MenuHarness.plugin(server, Logger.getAnonymousLogger()), mock(Messages.class),
            new GuiFakes.FakeTasks(), mock(EntityThread.class), new ErrorReports()).home("main");
    }

    @Test
    void aHandlerClosingTheMenuClosesItOnTheNextTick() {
        Sample sample = new Sample(folder);

        sample.viewer.click(53, ClickType.LEFT);

        assertThat(sample.viewer.view).isNotNull();
        sample.harness.drain();
        assertThat(sample.viewer.view).isNull();
        assertThat(sample.viewer.closes).containsExactly(InventoryCloseEvent.Reason.PLUGIN);
        assertThat(sample.menu.closes).containsExactly(true);
    }

    @Test
    void closeLaterLeavesAViewOpenedOverTheClosedMenuBeforeTheTick() {
        Sample sample = new Sample(folder);
        sample.viewer.click(53, ClickType.LEFT);
        sample.viewer.player.closeInventory(InventoryCloseEvent.Reason.PLAYER);
        FakeView chest = sample.viewer.foreign(GuiFakes.foreign(27));

        sample.harness.drain();

        assertThat(sample.viewer.current()).isSameAs(chest);
        assertThat(sample.viewer.closes).containsExactly(InventoryCloseEvent.Reason.PLAYER);
    }

    @ParameterizedTest
    @EnumSource(value = InventoryCloseEvent.Reason.class, names = {"DISCONNECT", "DEATH", "PLAYER", "UNKNOWN"})
    void aCloseForAnyReasonEndsTheSessionAndTheViewerLeft(InventoryCloseEvent.Reason reason) {
        Sample sample = new Sample(folder);
        MenuSession<?> session = sample.session();

        sample.viewer.player.closeInventory(reason);

        assertThat(session.closed()).isTrue();
        assertThat(sample.menu.closes).containsExactly(true);
        assertThat(sample.harness.menus.session(sample.viewer.id)).isEmpty();
    }

    @Test
    void aClosedStepThatThrowsIsLoggedWithItsCauseAndReported() {
        Sample sample = new Sample(folder);
        IllegalStateException failure = new IllegalStateException("broken");
        sample.menu.closing = left -> {
            throw failure;
        };

        sample.viewer.player.closeInventory(InventoryCloseEvent.Reason.PLAYER);

        assertThat(sample.harness.log.lines()).containsExactly("WARNING gui: sample failed to close for Alex:"
            + " java.lang.IllegalStateException: broken");
        assertThat(sample.harness.log.records.getFirst().getThrown()).isSameAs(failure);
        assertThat(sample.harness.reports).containsExactly(new MenuHarness.Report("gui.close", failure));
        assertThat(sample.harness.menus.session(sample.viewer.id)).isEmpty();
    }

    @Test
    void openingAnotherMenuEndsTheFirstWithoutTheViewerLeaving() {
        Sample sample = new Sample(folder);
        TestMenu other = new TestMenu(MenuHarness.MAIN);
        sample.harness.menus.register(other);

        sample.harness.open(sample.viewer, MenuHarness.MAIN.id());

        assertThat(sample.menu.closes).containsExactly(false);
        assertThat(sample.viewer.closes).containsExactly(InventoryCloseEvent.Reason.OPEN_NEW);
        assertThat(sample.harness.menus.session(sample.viewer.id).orElseThrow().id()).isEqualTo("main");
    }

    @Test
    void anOpenAnotherPluginCancelsRunsThePreviousMenusLeftStepOnce() {
        Sample sample = new Sample(folder);
        TestMenu other = new TestMenu(MenuHarness.MAIN);
        sample.harness.menus.register(other);
        sample.viewer.refuseOpen = true;

        sample.harness.open(sample.viewer, MenuHarness.MAIN.id());

        assertThat(sample.menu.closes).containsExactly(true);
        assertThat(other.sessions).isEmpty();
        assertThat(sample.harness.menus.session(sample.viewer.id)).isEmpty();
    }

    @Test
    void anOpenRefusedWithNoViewOpenLeavesNoSession() {
        MenuHarness harness = new MenuHarness(folder);
        TestMenu menu = new TestMenu(MenuHarness.MAIN);
        harness.menus.register(menu);
        MenuHarness.Viewer viewer = harness.viewer("Alex");
        viewer.refuseOpen = true;

        harness.open(viewer, MenuHarness.MAIN.id());

        assertThat(viewer.view).isNull();
        assertThat(menu.sessions).isEmpty();
        assertThat(harness.menus.session(viewer.id)).isEmpty();
    }

    @Test
    void handoffWithoutAnOpenMenuRunsTheReplacement() {
        MenuHarness harness = new MenuHarness(folder);
        MenuHarness.Viewer viewer = harness.viewer("Alex");

        assertThat(harness.menus.handoff(viewer.player, () -> true)).isTrue();
        assertThat(harness.menus.handoff(viewer.player, () -> false)).isFalse();
    }

    @Test
    void aLoadAppliesOnTheEntityThreadAndDrawsAgainClearingBusy() {
        Fixture fixture = new Fixture(folder);
        fixture.session().busy(true);

        fixture.first.complete("ready");

        assertThat(fixture.session().data()).isEqualTo("ready");
        assertThat(fixture.session().busy()).isFalse();
        assertThat(fixture.viewer.lore(0)).containsExactly("ready");
    }

    @Test
    void aLoadCompletingAfterCloseIsDropped() {
        Fixture fixture = new Fixture(folder);
        MenuSession<?> session = fixture.session();
        int renders = fixture.menu.renders;
        fixture.viewer.player.closeInventory(InventoryCloseEvent.Reason.PLAYER);

        fixture.first.complete("late");

        assertThat(session.data()).isNull();
        assertThat(fixture.menu.renders).isEqualTo(renders);
    }

    @Test
    void aLoadOvertakenByALaterLoadIsDropped() {
        Fixture fixture = new Fixture(folder);
        MenuSession<Integer> session = fixture.session();
        CompletableFuture<String> second = new CompletableFuture<>();
        fixture.harness.menus.load(session, second, (s, value) -> s.data(value));

        fixture.first.complete("stale");
        assertThat(session.data()).isNull();
        second.complete("fresh");
        assertThat(session.data()).isEqualTo("fresh");
    }

    @Test
    void aFailedLoadIsLoggedReportedToldAndClearsBusy() {
        Fixture fixture = new Fixture(folder);
        fixture.session().busy(true);
        IllegalStateException failure = new IllegalStateException("database down");

        fixture.first.completeExceptionally(failure);

        assertThat(fixture.harness.log.lines()).containsExactly("WARNING gui: plain could not load for Alex:"
            + " java.lang.IllegalStateException: database down");
        assertThat(fixture.harness.log.records.getFirst().getThrown()).isSameAs(failure);
        assertThat(fixture.harness.reports).containsExactly(new MenuHarness.Report("gui.load", failure));
        assertThat(fixture.viewer.said()).containsExactly("Something went wrong. Try again in a moment.");
        assertThat(fixture.viewer.heard()).containsExactly("entity.villager.no");
        assertThat(fixture.session().busy()).isFalse();
    }

    @Test
    void aLoadFailingAfterTheMenuClosedIsLoggedAndReportedButNotTold() {
        Fixture fixture = new Fixture(folder);
        fixture.viewer.player.closeInventory(InventoryCloseEvent.Reason.PLAYER);
        IllegalStateException failure = new IllegalStateException("database down");

        fixture.first.completeExceptionally(failure);

        assertThat(fixture.harness.reports).containsExactly(new MenuHarness.Report("gui.load", failure));
        assertThat(fixture.viewer.said()).isEmpty();
        assertThat(fixture.viewer.heard()).isEmpty();
    }

    @Test
    void aStaleLoadsFailureLeavesBusyToTheLaterLoadAndTellsNothing() {
        Fixture fixture = new Fixture(folder);
        MenuSession<Integer> session = fixture.session();
        CompletableFuture<String> second = new CompletableFuture<>();
        fixture.harness.menus.load(session, second, (s, value) -> s.data(value));
        session.busy(true);
        IllegalStateException failure = new IllegalStateException("database down");

        fixture.first.completeExceptionally(failure);

        assertThat(session.busy()).isTrue();
        assertThat(fixture.viewer.said()).isEmpty();
        assertThat(fixture.harness.reports).containsExactly(new MenuHarness.Report("gui.load", failure));
        second.complete("fresh");
        assertThat(session.busy()).isFalse();
        assertThat(session.data()).isEqualTo("fresh");
    }

    @Test
    void aCancelledLoadIsNoFailureAndClearsBusy() {
        Fixture fixture = new Fixture(folder);
        fixture.session().busy(true);

        fixture.first.cancel(false);

        assertThat(fixture.harness.log.lines()).isEmpty();
        assertThat(fixture.harness.reports).isEmpty();
        assertThat(fixture.viewer.said()).isEmpty();
        assertThat(fixture.viewer.heard()).isEmpty();
        assertThat(fixture.session().busy()).isFalse();
    }

    @Test
    void aLoadFailingAfterShutdownIsNeitherLoggedNorReportedNorTold() {
        Fixture fixture = new Fixture(folder);
        fixture.harness.menus.shutdown();

        fixture.first.completeExceptionally(new RejectedExecutionException("storage closed before this work ran"));

        assertThat(fixture.harness.log.lines()).isEmpty();
        assertThat(fixture.harness.reports).isEmpty();
        assertThat(fixture.viewer.said()).isEmpty();
    }

    @Test
    void aLoadForAViewerWhoLeftTheServerIsSilent() {
        Fixture fixture = new Fixture(folder);
        CompletableFuture<String> gone = new CompletableFuture<>();
        MenuSession<Integer> session = fixture.session();
        fixture.harness.menus.load(session, gone, (s, value) -> s.data(value));
        fixture.viewer.offline();

        fixture.first.complete("first");
        gone.completeExceptionally(new EntityUnavailableException(fixture.viewer.id));

        assertThat(fixture.harness.log.lines()).isEmpty();
        assertThat(fixture.harness.reports).isEmpty();
        assertThat(fixture.viewer.said()).isEmpty();
        assertThat(session.data()).isNull();
    }

    @Test
    void aLoadWhoseViewerLeftBeforeItAppliesIsSilent() {
        Fixture fixture = new Fixture(folder);
        MenuSession<Integer> session = fixture.session();
        int renders = fixture.menu.renders;
        fixture.viewer.offline();

        fixture.first.complete("late");

        assertThat(fixture.harness.log.lines()).isEmpty();
        assertThat(fixture.harness.reports).isEmpty();
        assertThat(fixture.viewer.said()).isEmpty();
        assertThat(session.data()).isNull();
        assertThat(fixture.menu.renders).isEqualTo(renders);
    }

    @Test
    void aFailedSourceForAViewerWhoLeftIsLoggedAndReportedButNotTold() {
        Fixture fixture = new Fixture(folder);
        IllegalStateException failure = new IllegalStateException("database down");
        fixture.viewer.offline();

        fixture.first.completeExceptionally(failure);

        assertThat(fixture.harness.log.lines()).containsExactly("WARNING gui: plain could not load for Alex:"
            + " java.lang.IllegalStateException: database down");
        assertThat(fixture.harness.reports).containsExactly(new MenuHarness.Report("gui.load", failure));
        assertThat(fixture.viewer.said()).isEmpty();
        assertThat(fixture.viewer.heard()).isEmpty();
    }

    @Test
    void anApplyThatThrowsIsLoggedReportedAndTold() {
        Fixture fixture = new Fixture(folder);
        MenuSession<Integer> session = fixture.session();
        IllegalStateException failure = new IllegalStateException("broken");
        CompletableFuture<String> source = new CompletableFuture<>();
        fixture.harness.menus.load(session, source, (s, value) -> {
            throw failure;
        });
        session.busy(true);

        source.complete("value");

        assertThat(fixture.harness.log.lines()).containsExactly("WARNING gui: plain could not load for Alex:"
            + " java.lang.IllegalStateException: broken");
        assertThat(fixture.harness.reports).containsExactly(new MenuHarness.Report("gui.load", failure));
        assertThat(fixture.viewer.said()).containsExactly("Something went wrong. Try again in a moment.");
        assertThat(fixture.viewer.heard()).containsExactly("entity.villager.no");
        assertThat(session.busy()).isFalse();
    }

    @Test
    void shutdownClosesOwnedMenusNowAndSchedulesNothing() {
        Sample sample = new Sample(folder);
        MenuHarness.Viewer elsewhere = sample.harness.viewer("Sam");
        sample.harness.open(elsewhere, Sample.KIND.id());
        sample.harness.owned = player -> player != elsewhere.player;

        sample.harness.menus.shutdown();

        assertThat(sample.viewer.closes).containsExactly(InventoryCloseEvent.Reason.PLUGIN);
        assertThat(sample.menu.closes).containsExactly(false);
        assertThat(elsewhere.closes).isEmpty();
        assertThat(sample.harness.tasks.queue).isEmpty();
        assertThat(sample.harness.menus.session(elsewhere.id)).isEmpty();
        assertThat(sample.harness.menus.stopping()).isTrue();
        sample.harness.open(sample.viewer, Sample.KIND.id());
        assertThat(sample.viewer.view).isNull();
    }

    @Test
    void shutdownLeavesAViewerTheThreadDoesNotOwnWithAnEmptyMenu() {
        Sample sample = new Sample(folder);
        MenuHarness.Viewer elsewhere = sample.harness.viewer("Sam");
        sample.harness.open(elsewhere, Sample.KIND.id());
        sample.harness.owned = player -> player != elsewhere.player;
        assertThat(elsewhere.name(10)).isEqualTo("Act");

        sample.harness.menus.shutdown();

        assertThat(elsewhere.session()).isNotNull();
        assertThat(sample.harness.inventories.canvases.get(elsewhere.current().getTopInventory()).slots)
            .containsOnlyNulls();
        assertThat(sample.menu.closes).containsExactly(false);
    }

    @Test
    void shutdownRunsTheClosedStepItselfWhenNoCloseEventArrives() {
        Sample sample = new Sample(folder);
        sample.harness.listening = false;

        sample.harness.menus.shutdown();

        assertThat(sample.viewer.closes).containsExactly(InventoryCloseEvent.Reason.PLUGIN);
        assertThat(sample.menu.closes).containsExactly(false);
        assertThat(sample.harness.menus.session(sample.viewer.id)).isEmpty();
        assertThat(sample.harness.tasks.queue).isEmpty();
    }

    @Test
    void shutdownSkipsAViewerWhoIsNoLongerOnline() {
        Sample sample = new Sample(folder);
        sample.viewer.online = false;

        sample.harness.menus.shutdown();

        assertThat(sample.viewer.closes).isEmpty();
    }

    @Test
    void reloadLeavesAnOpenSessionOnItsLayoutAndTheNextOpenUsesTheNewOne() {
        Sample sample = new Sample(folder);
        MenuSession<?> open = sample.session();
        MenuLayout old = sample.harness.layouts.get(Sample.KIND.id());
        Map<String, ItemLayout> items = new LinkedHashMap<>(old.items());
        ItemLayout act = items.get("act");
        items.put("act", ItemLayout.of("act", List.of(19), act.material()).action(act.action()).name(act.name()));
        Map<String, MenuLayout> layouts = new LinkedHashMap<>(sample.harness.layouts);
        layouts.put(Sample.KIND.id(), MenuLayout.of(old.menu(), old.rows()).filler(old.filler()).pager(old.pager())
            .items(items.values()).title(old.title()));

        sample.harness.menus.reload(layouts);
        sample.harness.menus.rerender(open);
        assertThat(sample.viewer.name(10)).isEqualTo("Act");
        assertThat(open.layout()).isSameAs(old);

        sample.harness.open(sample.viewer, Sample.KIND.id());
        assertThat(sample.viewer.name(19)).isEqualTo("Act");
        assertThat(sample.viewer.filler(10)).isTrue();
        assertThat(sample.session().layout().items().get("act").slots()).containsExactly(19);
    }

    @Test
    void reloadRefusesAMapWithoutARegisteredMenuAndKeepsTheOldLayouts() {
        MenuHarness harness = new MenuHarness(folder);
        harness.menus.register(new TestMenu(MenuHarness.MAIN));
        MenuHarness.Viewer viewer = harness.viewer("Alex");
        MenuLayout options = MenuHarness.LAYOUTS.get(MenuHarness.OPTIONS.id());

        assertThatThrownBy(() -> harness.menus.reload(Map.of(MenuHarness.OPTIONS.id(), options)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("no layout was given for the main menu");
        harness.open(viewer, MenuHarness.MAIN.id());

        assertThat(viewer.session()).isNotNull();
        assertThat(viewer.session().layout()).isSameAs(MenuHarness.LAYOUTS.get(MenuHarness.MAIN.id()));
    }

    @Test
    void forgetClearsTheDebounceTheViewMemoryAndTheSession() {
        Sample sample = new Sample(folder);
        assertThat(sample.harness.menus.debounce().pass(sample.viewer.id, "sample:act")).isTrue();
        sample.harness.menus.memory().put(sample.viewer.id, "sample", "tab");

        sample.harness.menus.forget(sample.viewer.id);

        assertThat(sample.harness.menus.debounce().pass(sample.viewer.id, "sample:act")).isTrue();
        assertThat(sample.harness.menus.memory().get(sample.viewer.id, "sample", String.class)).isNull();
        assertThat(sample.harness.menus.session(sample.viewer.id)).isEmpty();
    }

    @Test
    void quitForgetsTheDebounceTheViewMemoryAndTheSessionAndEndsThePrompt() {
        Sample sample = new Sample(folder);
        sample.viewer.click(10, ClickType.LEFT);
        sample.harness.menus.memory().put(sample.viewer.id, "sample", "tab");

        sample.harness.listener.onQuit(new PlayerQuitEvent(sample.viewer.player, Component.empty(),
            PlayerQuitEvent.QuitReason.DISCONNECTED));

        assertThat(sample.acts).hasValue(1);
        assertThat(sample.harness.menus.debounce().pass(sample.viewer.id, "sample:act")).isTrue();
        assertThat(sample.harness.menus.memory().get(sample.viewer.id, "sample", String.class)).isNull();
        assertThat(sample.harness.prompts.quit).containsExactly(sample.viewer.id);
        assertThat(sample.harness.menus.session(sample.viewer.id)).isEmpty();
    }

    @Test
    void aCloseEventFromAnotherViewerOfTheInventoryDoesNotEndTheSession() {
        Sample sample = new Sample(folder);
        MenuHarness.Viewer admin = sample.harness.viewer("Admin");
        FakeView shared = new FakeView(sample.viewer.current().getTopInventory(), admin.inventory, admin.player);

        sample.harness.listener.onClose(new InventoryCloseEvent(shared, InventoryCloseEvent.Reason.PLAYER));

        assertThat(sample.session().closed()).isFalse();
        assertThat(sample.menu.closes).isEmpty();
        assertThat(sample.harness.menus.session(sample.viewer.id)).containsSame(sample.session());
    }

    @Test
    void aRerenderOfAClosedSessionDoesNothing() {
        Sample sample = new Sample(folder);
        MenuSession<?> session = sample.session();
        sample.viewer.player.closeInventory(InventoryCloseEvent.Reason.PLAYER);
        int renders = sample.menu.renders;

        sample.harness.menus.rerenderLater(session);
        sample.harness.drain();

        assertThat(sample.menu.renders).isEqualTo(renders);
    }

    @Test
    void theRouteCarriesTheMenuItsStateAndItsBack() {
        Sample sample = new Sample(folder);
        Route back = new Route("main", 0, null);
        sample.harness.menus.openLater(sample.viewer.player, new Route(Sample.KIND.id(), 4, back));
        sample.harness.drain();

        MenuSession<?> session = sample.session();
        assertThat(session.route()).isEqualTo(new Route(Sample.KIND.id(), 4, back));
        assertThat(session.generation()).isEqualTo(1);
        assertThat(session.openedAt()).isEqualTo(GuiFakes.Ticker.START + Sample.SETTLED.toNanos());
        assertThat(session.menu()).isSameAs(sample.menu);
        assertThatThrownBy(() -> new Route("main", null, null)).isInstanceOf(NullPointerException.class);
    }

    private MenuHarness plainHarness() {
        return new MenuHarness(folder, MenuSettings.DEFAULTS, Map.of(PLAIN.id(), PLAIN_LAYOUT));
    }

    private static MenuLayout plainLayout() {
        Map<String, ItemLayout> items = new LinkedHashMap<>();
        items.put("show", ItemLayout.of("show", List.of(0), Material.PAPER).action("show").name("Shown <count>"));
        items.put("secret", ItemLayout.of("secret", List.of(1), Material.PAPER).action("show")
            .permission("taffy.test.secret"));
        items.put("hidden", ItemLayout.of("hidden", List.of(2), Material.PAPER).action("show").enabled(false));
        items.put("close", MenuHarness.item("close", 26, Material.BARRIER, MenuKind.CLOSE));
        return MenuLayout.of(PLAIN.id(), 3).filler(Material.BLACK_STAINED_GLASS_PANE).items(items.values())
            .title("Plain");
    }

    // The plain menu open for Alex with one load in flight; show (slot 0) has the loaded value as its lore.
    private static final class Fixture {

        final MenuHarness harness;
        final TestMenu menu = new TestMenu(PLAIN);
        final CompletableFuture<String> first = new CompletableFuture<>();
        final MenuHarness.Viewer viewer;

        Fixture(Path folder) {
            harness = new MenuHarness(folder, MenuSettings.DEFAULTS, Map.of(PLAIN.id(), PLAIN_LAYOUT));
            menu.opened = session -> harness.menus.load(session, first, (s, value) -> s.data(value));
            menu.render = frame -> frame.action("show", Frame.Spec.display().lore(frame.session().data() == null
                ? List.of() : List.of(Component.text(String.valueOf(frame.session().data())))));
            harness.menus.register(menu);
            viewer = harness.viewer("Alex");
            harness.open(viewer, PLAIN.id());
        }

        MenuSession<Integer> session() {
            return menu.sessions.getLast();
        }
    }
}
