package so.alaz.taffy.gui;

import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// The jobs menu open for Alex, past the open arming window. buy (slot 10) is exclusive and starts the load of the
// next future in loads with no busy call, claim (11) is exclusive and holds busy itself for work that ends when the
// test runs the callback in claims, tidy (12) is exclusive and starts nothing, crash (13) is exclusive, starts a load
// and throws, and plain (14) is not exclusive.
final class ExclusiveClickTest {

    private static final MenuKind JOBS = MenuKind.of("jobs", false, "buy", "claim", "tidy", "crash", "plain");

    private static final MenuLayout LAYOUT = MenuLayout.of(JOBS.id(), 3).items(List.of(
        MenuHarness.item("buy", 10, Material.EMERALD, "buy"),
        MenuHarness.item("claim", 11, Material.CHEST, "claim"),
        MenuHarness.item("tidy", 12, Material.HOPPER, "tidy"),
        MenuHarness.item("crash", 13, Material.TNT, "crash"),
        MenuHarness.item("plain", 14, Material.PAPER, "plain")));

    @TempDir
    Path folder;

    private MenuHarness harness;
    private MenuHarness.Viewer viewer;
    private final TestMenu menu = new TestMenu(JOBS);
    private final List<CompletableFuture<String>> loads = new ArrayList<>();
    private final List<Runnable> claims = new ArrayList<>();
    private final List<Boolean> busyInside = new ArrayList<>();
    private final AtomicInteger buys = new AtomicInteger();
    private final AtomicInteger tidies = new AtomicInteger();
    private final AtomicInteger plains = new AtomicInteger();

    @BeforeEach
    void open() {
        harness = new MenuHarness(folder, MenuSettings.DEFAULTS, Map.of(JOBS.id(), LAYOUT));
        menu.render = frame -> {
            MenuSession<Integer> session = frame.session();
            frame.action("buy", Frame.Spec.click(click -> {
                busyInside.add(session.busy());
                buys.incrementAndGet();
                harness.menus.load(session, next(), (s, value) -> s.data(value));
            }).exclusively());
            frame.action("claim", Frame.Spec.click(click -> {
                session.busy(true);
                claims.add(() -> session.busy(false));
            }).exclusively());
            frame.action("tidy", Frame.Spec.click(click -> tidies.incrementAndGet()).exclusively());
            frame.action("crash", Frame.Spec.click(click -> {
                harness.menus.load(session, next(), (s, value) -> s.data(value));
                throw new IllegalStateException("boom");
            }).exclusively());
            frame.action("plain", Frame.Spec.click(click -> plains.incrementAndGet()));
        };
        harness.menus.register(menu);
        viewer = harness.viewer("Alex");
        harness.open(viewer, JOBS.id());
        harness.advance(Sample.SETTLED);
    }

    private CompletableFuture<String> next() {
        CompletableFuture<String> load = new CompletableFuture<>();
        loads.add(load);
        return load;
    }

    private MenuSession<Integer> session() {
        return menu.sessions.getLast();
    }

    private void click(int slot) {
        viewer.click(slot, ClickType.LEFT);
        harness.advance(Sample.SETTLED);
    }

    @Test
    void anExclusiveButtonHoldsTheSessionWhileTheLoadItsHandlerStartedRunsWithNoBusyCall() {
        click(10);

        assertThat(busyInside).containsExactly(false);
        assertThat(session().busy()).isTrue();

        click(10);
        click(12);
        click(14);

        assertThat(buys).hasValue(1);
        assertThat(tidies).hasValue(0);
        assertThat(plains).hasValue(1);

        loads.getFirst().complete("bought");

        assertThat(session().busy()).isFalse();
        assertThat(session().data()).isEqualTo("bought");
        click(10);
        assertThat(buys).hasValue(2);
        assertThat(busyInside).containsExactly(false, false);
    }

    @Test
    void aFailedOrCancelledLoadReleasesTheSession() {
        click(10);
        loads.getFirst().completeExceptionally(new IllegalStateException("down"));

        assertThat(session().busy()).isFalse();

        click(10);
        assertThat(session().busy()).isTrue();
        loads.getLast().cancel(false);

        assertThat(session().busy()).isFalse();
        click(10);
        assertThat(buys).hasValue(3);
    }

    @Test
    void aLoadThatEndsInsideTheHandlerLeavesTheSessionFree() {
        menu.render = frame -> {
            MenuSession<Integer> session = frame.session();
            frame.action("buy", Frame.Spec.click(click -> {
                buys.incrementAndGet();
                harness.menus.load(session, CompletableFuture.completedFuture("now"), (s, value) -> s.data(value));
            }).exclusively());
        };
        harness.menus.rerender(session());

        click(10);

        assertThat(session().data()).isEqualTo("now");
        assertThat(session().busy()).isFalse();
        click(10);
        assertThat(buys).hasValue(2);
    }

    @Test
    void anExclusiveButtonWhoseHandlerStartsNoLoadLeavesTheSessionFree() {
        click(12);

        assertThat(session().busy()).isFalse();
        click(12);
        assertThat(tidies).hasValue(2);
    }

    @Test
    void aLoadStartedBeforeTheClickDoesNotHoldTheSessionForAHandlerThatStartsNone() {
        harness.menus.load(session(), next(), (s, value) -> s.data(value));

        click(12);

        assertThat(session().loading()).isTrue();
        assertThat(session().busy()).isFalse();
        click(12);
        assertThat(tidies).hasValue(2);
    }

    @Test
    void aHandlersOwnHoldLastsPastItsReturnUntilTheHandlersWorkReleasesIt() {
        click(11);

        assertThat(session().busy()).isTrue();

        click(11);
        click(10);
        click(12);
        click(14);

        assertThat(claims).hasSize(1);
        assertThat(buys).hasValue(0);
        assertThat(tidies).hasValue(0);
        assertThat(plains).hasValue(1);
        assertThat(session().busy()).isTrue();

        claims.getFirst().run();

        assertThat(session().busy()).isFalse();
        click(11);
        assertThat(claims).hasSize(2);
    }

    @Test
    void busyFalseReleasesASessionHeldForALoad() {
        click(10);

        session().busy(false);

        assertThat(session().busy()).isFalse();
        click(12);
        assertThat(tidies).hasValue(1);
    }

    @Test
    void anExclusiveHandlerThatThrowsAfterStartingALoadLeavesTheSessionFree() {
        click(13);

        assertThat(session().busy()).isFalse();
        assertThat(harness.reports).singleElement()
            .satisfies(report -> assertThat(report.source()).isEqualTo("gui.click"));
        click(12);
        assertThat(tidies).hasValue(1);
    }

    @Test
    void aLoadStartedByAButtonThatIsNotExclusiveDoesNotHoldTheSession() {
        menu.render = frame -> {
            MenuSession<Integer> session = frame.session();
            frame.action("plain", Frame.Spec.click(click ->
                harness.menus.load(session, next(), (s, value) -> s.data(value))));
            frame.action("tidy", Frame.Spec.click(click -> tidies.incrementAndGet()).exclusively());
        };
        harness.menus.rerender(session());

        click(14);

        assertThat(session().busy()).isFalse();
        click(12);
        assertThat(tidies).hasValue(1);
    }
}
