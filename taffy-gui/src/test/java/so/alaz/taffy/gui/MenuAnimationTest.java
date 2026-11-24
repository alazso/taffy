package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// The list menu for Alex with the rows A (slot 9) and B (slot 10). A row shows the session's state plus one as its
// amount unless its name is in still, and a click on it adds its name to clicked. With keyed set a row carries its
// name as its key, and with rekeyed set its name and the state. count is a step that adds one to the state and asks
// for another frame.
final class MenuAnimationTest {

    private static final Duration TICK = Duration.ofMillis(50);

    @TempDir
    Path folder;

    private MenuHarness harness;
    private MenuHarness.Viewer viewer;
    private final TestMenu menu = new TestMenu(MenuHarness.LIST);
    private final List<String> listings = List.of("A", "B");
    private final List<String> clicked = new ArrayList<>();
    private final Set<String> still = new HashSet<>();
    private boolean keyed;
    private boolean rekeyed;
    private int steps;
    private final Predicate<MenuSession<Integer>> count = session -> {
        steps++;
        session.state(session.state() + 1);
        return true;
    };

    @BeforeEach
    void build() {
        harness = new MenuHarness(folder);
        menu.render = frame -> frame.list(listings.size(), 0, index -> row(listings.get(index), frame.state()));
        harness.menus.register(menu);
        viewer = harness.viewer("Alex");
    }

    private Frame.Entry row(String name, int state) {
        Icon icon = new Icon.Button(Material.PAPER, still.contains(name) ? 1 : state + 1, Component.text(name),
            List.of(), false);
        Frame.Entry entry = Frame.Entry.click(icon, click -> clicked.add(name));
        if (rekeyed) {
            return entry.key(name + ":" + state);
        }
        return keyed ? entry.key(name) : entry;
    }

    private MenuSession<Integer> open() {
        harness.open(viewer, MenuHarness.LIST.id());
        harness.advance(Sample.SETTLED);
        return menu.sessions.getLast();
    }

    private int amount(int slot) {
        return ((Icon.Button) viewer.icon(slot)).amount();
    }

    static Stream<Arguments> periods() {
        return Stream.of(
            Arguments.of(Duration.ZERO, 1L),
            Arguments.of(Duration.ofSeconds(-5), 1L),
            Arguments.of(Duration.ofNanos(-1), 1L),
            Arguments.of(Duration.ofNanos(1), 1L),
            Arguments.of(Duration.ofMillis(50), 1L),
            Arguments.of(Duration.ofMillis(50).plusNanos(1), 2L),
            Arguments.of(Duration.ofMillis(120), 3L),
            Arguments.of(Duration.ofSeconds(3), 60L),
            Arguments.of(Duration.ofSeconds(Long.MAX_VALUE / 20 - 1, 999_999_999), Long.MAX_VALUE / 20 * 20),
            Arguments.of(Duration.ofSeconds(Long.MAX_VALUE / 20), Long.MAX_VALUE),
            Arguments.of(Duration.ofSeconds(Long.MAX_VALUE), Long.MAX_VALUE));
    }

    @ParameterizedTest
    @MethodSource("periods")
    void aPeriodIsCountedInWholeTicksRoundedUpAndIsAtLeastOne(Duration period, long ticks) {
        assertThat(Menus.ticks(period)).isEqualTo(ticks);
    }

    @Test
    void theStepRunsOncePerPeriodAndTheSessionIsDrawnAfterIt() {
        MenuSession<Integer> session = open();
        int renders = menu.renders;

        harness.menus.animate(session, Duration.ofMillis(120), count);

        assertThat(steps).isZero();
        harness.tick(2);
        assertThat(steps).isZero();
        assertThat(menu.renders).isEqualTo(renders);
        assertThat(harness.tasks.queue).hasSize(1);
        harness.tick(1);
        assertThat(steps).isEqualTo(1);
        assertThat(menu.renders).isEqualTo(renders + 1);
        assertThat(amount(9)).isEqualTo(2);
        harness.tick(2);
        assertThat(steps).isEqualTo(1);
        harness.tick(1);
        assertThat(steps).isEqualTo(2);
        assertThat(amount(9)).isEqualTo(3);
        assertThat(harness.tasks.queue).hasSize(1);
    }

    @Test
    void aZeroOrNegativePeriodIsOneTick() {
        MenuSession<Integer> session = open();

        harness.menus.animate(session, Duration.ZERO, count);
        harness.tick(2);
        assertThat(steps).isEqualTo(2);

        harness.menus.animate(session, Duration.ofSeconds(-1), count);
        harness.tick(2);
        assertThat(steps).isEqualTo(4);
    }

    @Test
    void everyTickIsATaskOfTheViewerAndTheStepGetsTheSession() {
        MenuSession<Integer> session = open();
        int before = harness.tasks.players.size();
        List<MenuSession<Integer>> seen = new ArrayList<>();

        harness.menus.animate(session, TICK, stepped -> seen.add(stepped));
        harness.tick(3);

        assertThat(harness.tasks.players.subList(before, harness.tasks.players.size())).hasSize(4)
            .containsOnly(viewer.player);
        assertThat(seen).containsExactly(session, session, session);
    }

    @Test
    void aNullArgumentIsRefusedAndLeavesARunningAnimationAlone() {
        MenuSession<Integer> session = open();
        harness.menus.animate(session, TICK, count);

        assertThatNullPointerException().isThrownBy(() -> harness.menus.animate(null, TICK, count))
            .withMessage("session");
        assertThatNullPointerException().isThrownBy(() -> harness.menus.animate(session, null, count))
            .withMessage("period");
        assertThatNullPointerException().isThrownBy(() -> harness.menus.animate(session, TICK, null))
            .withMessage("step");

        assertThat(harness.tasks.queue).hasSize(1);
        harness.tick(1);
        assertThat(steps).isEqualTo(1);
    }

    @Test
    void aStepThatReturnsFalseHasItsFrameDrawnAndEndsTheAnimation() {
        MenuSession<Integer> session = open();

        harness.menus.animate(session, TICK, stepped -> {
            steps++;
            stepped.state(7);
            return false;
        });
        harness.tick(1);

        assertThat(amount(9)).isEqualTo(8);
        assertThat(harness.tasks.queue).isEmpty();
        harness.tick(3);
        assertThat(steps).isEqualTo(1);
    }

    @Test
    void aClosedSessionIsNotAnimatedOnAnyThread() {
        MenuSession<Integer> session = open();
        viewer.player.closeInventory(InventoryCloseEvent.Reason.PLAYER);
        harness.drain();
        harness.owned = player -> false;

        harness.menus.animate(session, TICK, count);

        assertThat(harness.tasks.queue).isEmpty();
        assertThat(session.animation()).isZero();
    }

    @Test
    void nothingIsAnimatedOnceShutdownRan() {
        MenuSession<Integer> session = open();
        harness.owned = player -> false;
        harness.menus.shutdown();
        harness.owned = player -> true;
        assertThat(session.closed()).isFalse();

        harness.menus.animate(session, TICK, count);

        assertThat(harness.tasks.queue).isEmpty();
    }

    @Test
    void animateOffTheViewersThreadThrowsAndLeavesARunningAnimationAlone() {
        MenuSession<Integer> session = open();
        harness.menus.animate(session, TICK, count);
        harness.owned = player -> false;

        assertThatIllegalStateException().isThrownBy(() -> harness.menus.animate(session, TICK, stepped -> false))
            .withMessage("Menus.animate must run on the viewer's entity thread; call it from Menu.opened or a "
                + "ClickHandler, or hop there through EntityThread.call");

        assertThat(harness.tasks.queue).hasSize(1);
        harness.owned = player -> true;
        harness.tick(2);
        assertThat(steps).isEqualTo(2);
    }

    @Test
    void aSessionOfAnotherMenusIsRefused() {
        MenuSession<Integer> session = open();
        Menus other = harness.another();

        assertThatIllegalArgumentException().isThrownBy(() -> other.animate(session, TICK, count))
            .withMessage("another Menus opened the list menu");

        assertThat(harness.tasks.queue).isEmpty();
    }

    @Test
    void aSecondAnimateReplacesTheFirst() {
        MenuSession<Integer> session = open();
        List<String> ran = new ArrayList<>();

        harness.menus.animate(session, TICK, stepped -> ran.add("first"));
        harness.tick(1);
        harness.menus.animate(session, TICK, stepped -> ran.add("second"));
        harness.tick(3);

        assertThat(ran).containsExactly("first", "second", "second", "second");
        assertThat(harness.tasks.queue).hasSize(1);
    }

    @Test
    void aStepThatAnimatesAgainReplacesItsOwnAnimationWhateverItReturns() {
        MenuSession<Integer> session = open();
        int renders = menu.renders;
        List<String> ran = new ArrayList<>();

        harness.menus.animate(session, TICK, stepped -> {
            ran.add("first");
            harness.menus.animate(stepped, TICK, count);
            return ran.size() == 1;
        });
        harness.tick(3);

        assertThat(ran).containsExactly("first");
        assertThat(steps).isEqualTo(2);
        assertThat(menu.renders).isEqualTo(renders + 3);
        assertThat(harness.tasks.queue).hasSize(1);
    }

    @Test
    void aStepThatThrowsIsLoggedAndReportedAndEndsTheAnimationWithoutADraw() {
        MenuSession<Integer> session = open();
        int renders = menu.renders;
        IllegalStateException failure = new IllegalStateException("boom");

        harness.menus.animate(session, TICK, stepped -> {
            steps++;
            throw failure;
        });
        harness.tick(1);

        assertThat(harness.reports).containsExactly(new MenuHarness.Report("gui.render", failure));
        assertThat(harness.log.lines()).containsExactly(
            "WARNING gui: list failed to animate for Alex: java.lang.IllegalStateException: boom");
        assertThat(harness.log.records.getLast().getThrown()).isSameAs(failure);
        assertThat(menu.renders).isEqualTo(renders);
        assertThat(harness.tasks.queue).isEmpty();
        harness.tick(3);
        assertThat(steps).isEqualTo(1);
    }

    @Test
    void aDrawThatThrowsIsLoggedAndReportedAndEndsTheAnimation() {
        MenuSession<Integer> session = open();
        IllegalStateException failure = new IllegalStateException("broken");
        menu.render = frame -> {
            throw failure;
        };

        harness.menus.animate(session, TICK, count);
        harness.tick(1);

        assertThat(harness.reports).containsExactly(new MenuHarness.Report("gui.render", failure));
        assertThat(harness.log.records.getLast().getThrown()).isSameAs(failure);
        assertThat(harness.tasks.queue).isEmpty();
        harness.tick(3);
        assertThat(steps).isEqualTo(1);
    }

    @Test
    void anAnimationEndsWhenTheViewerClosesTheMenuBetweenTwoTicks() {
        MenuSession<Integer> session = open();
        int renders = menu.renders;
        harness.menus.animate(session, Duration.ofMillis(100), count);
        harness.tick(1);

        viewer.player.closeInventory(InventoryCloseEvent.Reason.PLAYER);
        harness.tick(1);

        assertThat(steps).isZero();
        assertThat(menu.renders).isEqualTo(renders);
        assertThat(harness.tasks.queue).isEmpty();
    }

    @Test
    void anAnimationEndsWhenTheViewerQuitsWithNoCloseEvent() {
        MenuSession<Integer> session = open();
        harness.menus.animate(session, TICK, count);

        harness.listener.onQuit(new PlayerQuitEvent(viewer.player, Component.empty(),
            PlayerQuitEvent.QuitReason.DISCONNECTED));
        harness.tick(1);

        assertThat(session.closed()).isFalse();
        assertThat(steps).isZero();
        assertThat(harness.tasks.queue).isEmpty();
    }

    @Test
    void anAnimationEndsWhenAnotherMenuOpensAndTheNewSessionIsNotAnimated() {
        TestMenu main = new TestMenu(MenuHarness.MAIN);
        harness.menus.register(main);
        MenuSession<Integer> session = open();
        harness.menus.animate(session, Duration.ofMillis(100), count);

        harness.menus.openLater(viewer.player, MenuHarness.MAIN.id(), null);
        harness.tick(2);

        assertThat(session.closed()).isTrue();
        assertThat(main.renders).isEqualTo(1);
        assertThat(steps).isZero();
        assertThat(harness.tasks.queue).isEmpty();
    }

    @Test
    void anAnimationEndsWhenItsMenuIsOpenedAgainAsANewSession() {
        MenuSession<Integer> session = open();
        harness.menus.animate(session, Duration.ofMillis(100), count);

        harness.menus.openLater(viewer.player, MenuHarness.LIST.id(), null);
        harness.tick(2);

        assertThat(menu.sessions).hasSize(2);
        assertThat(menu.sessions.getLast().closed()).isFalse();
        assertThat(menu.renders).isEqualTo(2);
        assertThat(steps).isZero();
        assertThat(harness.tasks.queue).isEmpty();
    }

    @Test
    void anAnimationEndsWhenShutdownRunsBetweenTwoTicks() {
        MenuSession<Integer> session = open();
        harness.menus.animate(session, Duration.ofMillis(100), count);
        harness.tick(1);

        harness.menus.shutdown();
        harness.tick(1);

        assertThat(steps).isZero();
        assertThat(harness.tasks.queue).isEmpty();
    }

    @Test
    void anAnimationEndsAtShutdownForAViewerWhoseMenuStaysOpen() {
        MenuSession<Integer> session = open();
        harness.menus.animate(session, TICK, count);
        harness.owned = player -> false;

        harness.menus.shutdown();
        harness.owned = player -> true;
        harness.tick(1);

        assertThat(session.closed()).isFalse();
        assertThat(steps).isZero();
        assertThat(harness.tasks.queue).isEmpty();
    }

    @Test
    void aKeyedRowTakesAClickOnTheTickOfEveryFrame() {
        keyed = true;
        MenuSession<Integer> session = open();
        harness.menus.animate(session, TICK, count);

        harness.tick(1);
        Icon first = viewer.icon(9);
        viewer.click(9, ClickType.LEFT);
        harness.advance(Sample.SETTLED);
        harness.tick(1);
        viewer.click(9, ClickType.LEFT);

        assertThat(viewer.icon(9)).isNotEqualTo(first);
        assertThat(clicked).containsExactly("A", "A");
    }

    @Test
    void aRowWithNoKeyDropsAClickOnTheTickOfAFrameThatChangedItsIcon() {
        still.add("B");
        MenuSession<Integer> session = open();
        harness.menus.animate(session, TICK, count);

        harness.tick(1);
        viewer.click(9, ClickType.LEFT);
        viewer.click(10, ClickType.LEFT);

        assertThat(clicked).containsExactly("B");
        harness.advance(Sample.SETTLED);
        viewer.click(9, ClickType.LEFT);
        assertThat(clicked).containsExactly("B", "A");
    }

    @Test
    void aSlotWhoseKeyChangesInAFrameDropsAClickForTheWindow() {
        rekeyed = true;
        MenuSession<Integer> session = open();
        harness.menus.animate(session, TICK, count);

        harness.tick(1);
        viewer.click(9, ClickType.LEFT);

        assertThat(session.buttons().get(9).key()).isEqualTo("entry:A:1");
        assertThat(clicked).isEmpty();
        harness.advance(Sample.SETTLED);
        viewer.click(9, ClickType.LEFT);
        assertThat(clicked).containsExactly("A");
    }

    @Test
    void aStackWrittenWithThePreviewKeyStaysWhileTheRenderedIconOfItsSlotIsUnchanged() {
        still.add("A");
        ItemStack[] slots = new ItemStack[54];
        Menus menus = harness.another(marker -> (holder, size, title) -> {
            Inventory inventory = mock(Inventory.class);
            when(inventory.getHolder(false)).thenReturn(holder);
            when(inventory.getHolder()).thenReturn(holder);
            when(inventory.getSize()).thenReturn(size);
            when(inventory.getItem(anyInt())).thenAnswer(call -> slots[(int) call.getArgument(0)]);
            doAnswer(call -> {
                slots[(int) call.getArgument(0)] = call.getArgument(1);
                return null;
            }).when(inventory).setItem(anyInt(), any());
            return new Inventories.Surface(inventory,
                new PaperCanvas(inventory, icon -> GuiFakes.marked(Material.STONE, marker), marker));
        });
        menus.register(menu);
        menus.openLater(viewer.player, MenuHarness.LIST.id(), null);
        harness.tick(1);
        MenuSession<Integer> session = menu.sessions.getLast();
        ItemStack own = GuiFakes.marked(Material.DIAMOND, menus.marker());
        session.getInventory().setItem(9, own);
        ItemStack moving = slots[10];
        ItemStack filler = slots[0];

        menus.animate(session, TICK, count);
        harness.tick(3);

        assertThat(steps).isEqualTo(3);
        assertThat(slots[9]).isSameAs(own);
        assertThat(slots[10]).isNotNull().isNotSameAs(moving);
        assertThat(slots[0]).isSameAs(filler);
    }
}
