package so.alaz.taffy.gui;

import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.bukkit.entity.Player;
import so.alaz.taffy.platform.EntityThread;
import so.alaz.taffy.platform.EntityUnavailableException;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.platform.Messages;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        items.put("open", new ItemLayout("open", List.of(0), Material.STONE, null, null, true, "Open", null));
        items.put("staff", new ItemLayout("staff", List.of(1), Material.STONE, null, "taffy.test.staff", true,
            "Staff", null));
        items.put("off", new ItemLayout("off", List.of(2), Material.STONE, null, null, false, "Off", null));
        MenuHarness harness = new MenuHarness(folder, MenuSettings.DEFAULTS, Map.of(kind.id(),
            new MenuLayout(kind.id(), 1, Material.BLACK_STAINED_GLASS_PANE, null, items, "Decor")));
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
        typo.put("shw", new ItemLayout("shw", List.of(5), Material.PAPER, "shw", null, true, null, null));
        Map<String, MenuLayout> layouts = new LinkedHashMap<>(harness.layouts);
        layouts.put(PLAIN.id(), new MenuLayout(PLAIN.id(), 3, null, null, typo, null));

        assertThatThrownBy(() -> harness.menus.reload(layouts))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("item shw of the plain menu has the action shw, which the menu does not have");
        MenuHarness.Viewer viewer = harness.viewer("Alex");
        harness.open(viewer, PLAIN.id());
        assertThat(viewer.session().layout()).isSameAs(PLAIN_LAYOUT);
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
    void everyPartButHomeIsRequired() throws Exception {
        RecordComponent[] components = Menus.Parts.class.getRecordComponents();
        Constructor<Menus.Parts> canonical = Menus.Parts.class.getDeclaredConstructor(
            Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
        Function<Player, Locale> locales = Player::locale;
        Supplier<Map<Cue, Sound>> sounds = Map::of;
        Object[] valid = {Map.of(), MenuSettings.DEFAULTS, mock(Messages.class), locales, mock(Server.class),
            new NamespacedKey("taffytest", "preview"), new GuiFakes.FakeTasks(), mock(EntityThread.class), sounds,
            "main", Logger.getAnonymousLogger(), new ErrorReports()};

        for (int i = 0; i < components.length; i++) {
            Object[] args = valid.clone();
            args[i] = null;
            String name = components[i].getName();
            if (name.equals("home")) {
                assertThat(canonical.newInstance(args).home()).isNull();
                continue;
            }
            assertThatThrownBy(() -> canonical.newInstance(args)).isInstanceOf(InvocationTargetException.class)
                .cause().isInstanceOf(NullPointerException.class);
            if (!name.equals("layouts")) {
                assertThatThrownBy(() -> canonical.newInstance(args)).cause().hasMessage(name);
            }
        }
    }

    @Test
    void thePublicConstructorAsksTheServerNothingWhileBuilding() {
        Server server = mock(Server.class);

        Menus menus = new Menus(parts(server));

        verifyNoInteractions(server);
        assertThat(menus.marker().key()).isEqualTo(new NamespacedKey("taffytest", "preview"));
    }

    @Test
    void thePublicConstructorRefusesNullParts() {
        assertThatNullPointerException().isThrownBy(() -> new Menus(null)).withMessage("parts");
    }

    @Test
    void theOwnershipCheckOfThePublicConstructorAsksTheServer() {
        Server server = mock(Server.class);
        Player player = mock(Player.class);
        when(server.isOwnedByCurrentRegion(player)).thenReturn(true);

        assertThat(new Menus(parts(server)).ownedHere().test(player)).isTrue();

        verify(server).isOwnedByCurrentRegion(player);
    }

    @Test
    void theListenerIsTheMenusOwnOnEveryCall() {
        MenuHarness harness = new MenuHarness(folder);

        assertThat(harness.menus.listener()).isSameAs(harness.menus.listener()).isSameAs(harness.listener);
    }

    @Test
    void playPlaysTheMappedSoundAndNothingForAnUnmappedCue() {
        Map<Cue, Sound> sounds = new EnumMap<>(Cue.class);
        sounds.put(Cue.SUCCESS, MenuHarness.SOUNDS.get(Cue.SUCCESS));
        MenuHarness harness = new MenuHarness(folder);
        Menus menus = new Menus(new Menus.Parts(harness.layouts, MenuSettings.DEFAULTS, harness.messages,
            Player::locale, mock(Server.class), new NamespacedKey("taffytest", "preview"), harness.tasks,
            harness.entity, () -> sounds, null, harness.log.logger, harness.errors), marker -> harness.inventories,
            player -> true, harness.clock);
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
        Function<Player, Locale> locales = Player::locale;
        return new Menus.Parts(Map.of(), MenuSettings.DEFAULTS, mock(Messages.class), locales, server,
            new NamespacedKey("taffytest", "preview"), new GuiFakes.FakeTasks(), mock(EntityThread.class), Map::of,
            "main", Logger.getAnonymousLogger(), new ErrorReports());
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
        items.put("act", new ItemLayout("act", List.of(19), act.material(), act.action(), null, true, act.name(),
            null));
        Map<String, MenuLayout> layouts = new LinkedHashMap<>(sample.harness.layouts);
        layouts.put(Sample.KIND.id(), new MenuLayout(old.menu(), old.rows(), old.filler(), old.pager(), items,
            old.title()));

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
        assertThat(session.openedAt()).isEqualTo(MenuHarness.START.plus(Sample.SETTLED));
        assertThat(session.menu()).isSameAs(sample.menu);
        assertThatThrownBy(() -> new Route("main", null, null)).isInstanceOf(NullPointerException.class);
    }

    private MenuHarness plainHarness() {
        return new MenuHarness(folder, MenuSettings.DEFAULTS, Map.of(PLAIN.id(), PLAIN_LAYOUT));
    }

    private static MenuLayout plainLayout() {
        Map<String, ItemLayout> items = new LinkedHashMap<>();
        items.put("show", new ItemLayout("show", List.of(0), Material.PAPER, "show", null, true, "Shown <count>",
            null));
        items.put("secret", new ItemLayout("secret", List.of(1), Material.PAPER, "show", "taffy.test.secret", true,
            null, null));
        items.put("hidden", new ItemLayout("hidden", List.of(2), Material.PAPER, "show", null, false, null, null));
        items.put("close", MenuHarness.item("close", 26, Material.BARRIER, MenuKind.CLOSE));
        return new MenuLayout(PLAIN.id(), 3, Material.BLACK_STAINED_GLASS_PANE, null, items, "Plain");
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
