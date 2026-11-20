package so.alaz.taffy.gui;

import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import so.alaz.taffy.test.FakeItems;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class ClickGuardTest {

    private static final int BUTTON = 10;
    private static final int FILLER = 20;
    private static final int BOTTOM = 54 + 5;

    @TempDir
    Path folder;

    static Stream<Arguments> everyClickWithEveryAction() {
        return Arrays.stream(ClickType.values()).flatMap(click -> Arrays.stream(InventoryAction.values())
            .map(action -> Arguments.of(click, action)));
    }

    @ParameterizedTest
    @MethodSource("everyClickWithEveryAction")
    void everyClickIsCancelledAndOnlyAPlainClickOnAButtonActs(ClickType click, InventoryAction action) {
        Sample sample = new Sample(folder);

        for (int raw : new int[] {FILLER, 4, 14, BOTTOM, -999}) {
            assertThat(sample.viewer.click(raw, click, action).isCancelled()).isTrue();
        }
        assertThat(sample.acts).hasValue(0);
        InventoryClickEvent onButton = sample.viewer.click(BUTTON, click, action);

        assertThat(onButton.isCancelled()).isTrue();
        boolean plain = List.of(ClickType.LEFT, ClickType.SHIFT_LEFT, ClickType.RIGHT, ClickType.SHIFT_RIGHT)
            .contains(click);
        assertThat(sample.acts).hasValue(plain ? 1 : 0);
        sample.harness.advance(Sample.SETTLED);
        assertThat(sample.viewer.click(BUTTON, ClickType.LEFT).isCancelled()).isTrue();
        assertThat(sample.acts).hasValue(plain ? 2 : 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, -1})
    void aNumberKeyOrOffhandSwapOnAButtonIsCancelledAndDoesNothing(int key) {
        Sample sample = new Sample(folder);
        ClickType click = key < 0 ? ClickType.SWAP_OFFHAND : ClickType.NUMBER_KEY;

        InventoryClickEvent event = sample.viewer.click(BUTTON, click, InventoryAction.HOTBAR_SWAP, key);

        assertThat(event.isCancelled()).isTrue();
        assertThat(sample.acts).hasValue(0);
        sample.viewer.click(BUTTON, ClickType.LEFT);
        assertThat(sample.acts).hasValue(1);
    }

    @Test
    void aDoubleClickCollectingFromTheBottomAndAShiftClickFromTheBottomAreCancelled() {
        Sample sample = new Sample(folder);

        assertThat(sample.viewer.click(BOTTOM, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR)
            .isCancelled()).isTrue();
        assertThat(sample.viewer.click(BOTTOM, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY)
            .isCancelled()).isTrue();
        assertThat(sample.viewer.click(BUTTON, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR)
            .isCancelled()).isTrue();
        assertThat(sample.acts).hasValue(0);
    }

    @Test
    void middleDropCreativeBorderUnknownAndBundleOnFillerClicksDoNothing() {
        Sample sample = new Sample(folder);
        List<Object[]> clicks = List.of(
            new Object[] {BUTTON, ClickType.MIDDLE, InventoryAction.CLONE_STACK},
            new Object[] {BUTTON, ClickType.DROP, InventoryAction.DROP_ONE_SLOT},
            new Object[] {BUTTON, ClickType.CONTROL_DROP, InventoryAction.DROP_ALL_SLOT},
            new Object[] {BUTTON, ClickType.CREATIVE, InventoryAction.PLACE_ALL},
            new Object[] {-999, ClickType.WINDOW_BORDER_LEFT, InventoryAction.NOTHING},
            new Object[] {-999, ClickType.WINDOW_BORDER_RIGHT, InventoryAction.NOTHING},
            new Object[] {BUTTON, ClickType.UNKNOWN, InventoryAction.UNKNOWN},
            new Object[] {FILLER, ClickType.RIGHT, InventoryAction.PICKUP_FROM_BUNDLE});

        for (Object[] each : clicks) {
            assertThat(sample.viewer.click((int) each[0], (ClickType) each[1], (InventoryAction) each[2])
                .isCancelled()).isTrue();
        }
        assertThat(sample.acts).hasValue(0);

        sample.viewer.click(BUTTON, ClickType.LEFT);
        assertThat(sample.acts).hasValue(1);
    }

    @Test
    void aLeftOnlyButtonIgnoresARightClick() {
        Sample sample = new Sample(folder);

        sample.viewer.click(11, ClickType.RIGHT);
        sample.viewer.click(11, ClickType.SHIFT_LEFT);
        assertThat(sample.lefts).hasValue(0);

        sample.viewer.click(11, ClickType.LEFT);
        assertThat(sample.lefts).hasValue(1);
    }

    @Test
    void aButtonAcceptingClicksOutsideThePlainOnesReceivesOnlyThePlainOnes() {
        Sample sample = new Sample(folder);
        TestMenu menu = new TestMenu(MenuHarness.MAIN);
        List<ClickType> received = new ArrayList<>();
        menu.render = frame -> frame.action(MenuKind.CLOSE, Frame.Spec.click(click -> received.add(click.type()))
            .clicks(Set.of(ClickType.MIDDLE, ClickType.DROP, ClickType.DOUBLE_CLICK, ClickType.LEFT)));
        sample.harness.menus.register(menu);
        sample.harness.open(sample.viewer, MenuHarness.MAIN.id());
        sample.harness.advance(Sample.SETTLED);

        sample.viewer.click(53, ClickType.MIDDLE, InventoryAction.CLONE_STACK);
        sample.viewer.click(53, ClickType.DROP, InventoryAction.DROP_ONE_SLOT);
        sample.viewer.click(53, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR);
        sample.viewer.click(53, ClickType.LEFT);

        assertThat(received).containsExactly(ClickType.LEFT);
    }

    @Test
    void slotsOutsideTheTopAreIgnored() {
        Sample sample = new Sample(folder);

        for (int raw : new int[] {54, -1, 54 + 35}) {
            assertThat(sample.viewer.click(raw, ClickType.LEFT).isCancelled()).isTrue();
        }

        assertThat(sample.acts).hasValue(0);
        assertThat(sample.harness.log.lines()).isEmpty();
    }

    @Test
    void displayFillerAndDecorationSlotsHaveNoButton() {
        Sample sample = new Sample(folder);

        assertThat(sample.session().buttons()).containsOnlyKeys(10, 11, 12, 13, 15, 53);
        assertThat(sample.viewer.click(14, ClickType.LEFT).isCancelled()).isTrue();
        assertThat(sample.viewer.click(4, ClickType.LEFT).isCancelled()).isTrue();
    }

    @Test
    void theHighestHandlerCancelsAgainAnEventAnotherPluginUncancelled() {
        Sample sample = new Sample(folder);
        InventoryClickEvent event = sample.viewer.click(FILLER, ClickType.LEFT);

        event.setCancelled(false);
        sample.harness.listener.recancelClick(event);

        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    void aThrowingHandlerLeavesTheEventCancelledLogsReportsPlaysFailAndClearsBusy() {
        Sample sample = new Sample(folder);

        InventoryClickEvent event = sample.viewer.click(15, ClickType.LEFT);

        assertThat(event.isCancelled()).isTrue();
        assertThat(sample.harness.log.lines()).singleElement().asString()
            .startsWith("WARNING gui: sample button fail failed for Alex: java.lang.IllegalStateException: boom");
        Throwable thrown = sample.harness.log.records.getFirst().getThrown();
        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("boom");
        assertThat(sample.harness.reports).containsExactly(new MenuHarness.Report("gui.click", thrown));
        assertThat(sample.viewer.heard()).containsExactly("ui.button.click", "entity.villager.no");
        assertThat(sample.session().busy()).isFalse();
    }

    @Test
    void aThrowingHandlerLeavesTheHoldAnotherButtonTook() {
        Sample sample = new Sample(folder);
        sample.viewer.click(12, ClickType.LEFT);
        sample.harness.advance(Sample.SETTLED);

        sample.viewer.click(15, ClickType.LEFT);

        assertThat(sample.harness.reports).singleElement()
            .satisfies(report -> assertThat(report.source()).isEqualTo("gui.click"));
        assertThat(sample.session().busy()).isTrue();
        sample.harness.advance(Sample.SETTLED);
        sample.viewer.click(13, ClickType.LEFT);
        assertThat(sample.works).hasValue(1);
    }

    @Test
    void aClickOnAClosedSessionsViewDoesNothing() {
        Sample sample = new Sample(folder);
        FakeView old = sample.viewer.current();
        sample.viewer.player.closeInventory(InventoryCloseEvent.Reason.PLAYER);

        InventoryClickEvent event = sample.viewer.clickOn(old, BUTTON, ClickType.LEFT, InventoryAction.PICKUP_ALL,
            -1);

        assertThat(event.isCancelled()).isTrue();
        assertThat(sample.acts).hasValue(0);
    }

    @Test
    void anotherPlayerClickingTheViewersMenuIsCancelledAndRunsNothing() {
        Sample sample = new Sample(folder);
        MenuHarness.Viewer admin = sample.harness.viewer("Admin");
        FakeView shared = new FakeView(sample.viewer.current().getTopInventory(), admin.inventory, admin.player);

        InventoryClickEvent event = admin.clickOn(shared, BUTTON, ClickType.LEFT, InventoryAction.PICKUP_ALL, -1);

        assertThat(event.isCancelled()).isTrue();
        assertThat(sample.acts).hasValue(0);
        sample.viewer.click(BUTTON, ClickType.LEFT);
        assertThat(sample.acts).hasValue(1);
    }

    @Test
    void aClickInAnotherPluginsInventoryIsNotCancelled() {
        Sample sample = new Sample(folder);
        Inventory chest = mock(Inventory.class);
        when(chest.getHolder(false)).thenReturn(mock(InventoryHolder.class));
        when(chest.getSize()).thenReturn(27);
        sample.viewer.foreign(chest);

        InventoryClickEvent event = sample.viewer.click(3, ClickType.LEFT);
        sample.harness.listener.recancelClick(event);

        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    void anExclusiveButtonIsDroppedWhileAnotherExclusiveActionIsBusyAndOthersStillAct() {
        Sample sample = new Sample(folder);

        sample.viewer.click(12, ClickType.LEFT);
        sample.harness.advance(Sample.SETTLED);
        sample.viewer.click(13, ClickType.LEFT);
        sample.viewer.click(12, ClickType.LEFT);
        sample.viewer.click(BUTTON, ClickType.LEFT);

        assertThat(sample.works).hasValue(1);
        assertThat(sample.acts).hasValue(1);
        sample.session().busy(false);
        sample.viewer.click(13, ClickType.LEFT);
        assertThat(sample.works).hasValue(2);
    }

    @Test
    void withTwoMenusOverOneViewerOnlyTheMenusThatOpenedTheSessionRunsTheHandlerAndEndsIt() {
        Sample sample = new Sample(folder);
        Menus admin = sample.harness.another();
        TestMenu menu = new TestMenu(MenuHarness.MAIN);
        AtomicInteger clicks = new AtomicInteger();
        menu.render = frame -> frame.action(MenuKind.CLOSE, Frame.Spec.click(click -> clicks.incrementAndGet()));
        admin.register(menu);
        assertThat(sample.harness.registered).containsExactly(sample.harness.listener, admin.menuListener());

        sample.viewer.click(BUTTON, ClickType.LEFT);

        assertThat(sample.acts).hasValue(1);
        assertThat(sample.viewer.heard()).containsExactly("ui.button.click");

        admin.openLater(sample.viewer.player, MenuHarness.MAIN.id(), null);
        sample.harness.drain();
        sample.harness.advance(Sample.SETTLED);
        MenuSession<Integer> session = menu.sessions.getLast();
        assertThat(sample.menu.closes).containsExactly(true);
        assertThat(session.owner()).isSameAs(admin);
        assertThat(admin.session(sample.viewer.id)).containsSame(session);
        assertThat(sample.harness.menus.session(sample.viewer.id)).isEmpty();

        InventoryClickEvent event = sample.viewer.click(53, ClickType.LEFT);

        assertThat(event.isCancelled()).isTrue();
        assertThat(clicks).hasValue(1);
        assertThat(sample.viewer.heard()).containsExactly("ui.button.click", "ui.button.click");

        sample.viewer.player.closeInventory(InventoryCloseEvent.Reason.PLAYER);

        assertThat(menu.closes).containsExactly(true);
        assertThat(sample.menu.closes).containsExactly(true);
        assertThat(session.closed()).isTrue();
        assertThat(admin.session(sample.viewer.id)).isEmpty();
        assertThat(sample.harness.log.lines()).isEmpty();
    }

    @Test
    void aListenerCancelsAClickAndADragInAMenuOfOtherMenusAndRunsNothing() {
        MenuHarness harness = new MenuHarness(folder);
        Menus admin = harness.another();
        TestMenu menu = new TestMenu(MenuHarness.MAIN);
        AtomicInteger clicks = new AtomicInteger();
        menu.render = frame -> frame.action(MenuKind.CLOSE, Frame.Spec.click(click -> clicks.incrementAndGet()));
        admin.register(menu);
        MenuHarness.Viewer viewer = harness.viewer("Alex");
        admin.openLater(viewer.player, MenuHarness.MAIN.id(), null);
        harness.drain();
        harness.advance(Sample.SETTLED);
        FakeView view = viewer.current();

        InventoryClickEvent click = new InventoryClickEvent(view, view.getSlotType(53), 53, ClickType.LEFT,
            InventoryAction.PICKUP_ALL);
        harness.listener.onClick(click);
        InventoryDragEvent drag = new InventoryDragEvent(view, null, FakeItems.item(Material.STONE, 4), false,
            Map.of(53, FakeItems.item(Material.STONE, 4)));
        harness.listener.onDrag(drag);
        harness.listener.onClose(new InventoryCloseEvent(view, InventoryCloseEvent.Reason.PLAYER));

        assertThat(click.isCancelled()).isTrue();
        assertThat(drag.isCancelled()).isTrue();
        assertThat(clicks).hasValue(0);
        assertThat(viewer.heard()).isEmpty();
        assertThat(menu.closes).isEmpty();
        assertThat(menu.sessions.getLast().closed()).isFalse();
        assertThat(admin.session(viewer.id)).isPresent();
    }

    @Test
    void anAcceptedClickPlaysTheClickSound() {
        Sample sample = new Sample(folder);

        sample.viewer.click(BUTTON, ClickType.RIGHT);
        sample.viewer.click(FILLER, ClickType.LEFT);

        assertThat(sample.viewer.heard()).containsExactly("ui.button.click");
    }

    @Test
    void onlyThePlainClicksAreDispatchable() {
        assertThat(Arrays.stream(ClickType.values()).filter(ClickGuard::dispatchable))
            .containsExactlyInAnyOrder(ClickType.LEFT, ClickType.SHIFT_LEFT, ClickType.RIGHT, ClickType.SHIFT_RIGHT);
        assertThat(ClickGuard.LEFT_ONLY).containsExactly(ClickType.LEFT);
    }
}
