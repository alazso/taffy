package so.alaz.taffy.gui;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.Messages;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

// A harness with the sample menu registered and open for one viewer, past the open arming window. act (slot 10)
// takes any plain click, left (11) a left click only, work-a (12) and work-b (13) are exclusive and set busy, show
// (14) is display only, fail (15) throws, deco (4) is decoration, and the footer has back (45) and close (53).
final class Sample {

    static final MenuKind KIND = MenuKind.of("sample", false, "act", "left", "work", "show", "fail",
        MenuKind.BACK, MenuKind.CLOSE);

    static final MenuLayout LAYOUT = layout();

    static final Duration SETTLED = Duration.ofMillis(300);

    final MenuHarness harness;
    final TestMenu menu = new TestMenu(KIND);
    final MenuHarness.Viewer viewer;
    final AtomicInteger acts = new AtomicInteger();
    final AtomicInteger lefts = new AtomicInteger();
    final AtomicInteger works = new AtomicInteger();

    Sample(Path folder) {
        this(folder, MenuSettings.DEFAULTS);
    }

    Sample(Path folder, MenuSettings settings) {
        this(folder, settings, null);
    }

    Sample(Path folder, Messages messages) {
        this(folder, MenuSettings.DEFAULTS, messages);
    }

    private Sample(Path folder, MenuSettings settings, @Nullable Messages messages) {
        harness = new MenuHarness(folder, settings, Map.of(KIND.id(), LAYOUT), MenuHarness.MAIN.id(), messages);
        menu.render = frame -> {
            frame.action("act", Frame.Spec.click(click -> acts.incrementAndGet()));
            frame.action("left", Frame.Spec.click(click -> lefts.incrementAndGet()).clicks(ClickGuard.LEFT_ONLY));
            frame.action("work", Frame.Spec.click(click -> {
                works.incrementAndGet();
                click.session().busy(true);
            }).exclusively());
            frame.action("show", Frame.Spec.display());
            frame.action("fail", Frame.Spec.click(click -> {
                click.session().busy(true);
                throw new IllegalStateException("boom");
            }));
            frame.footer(null, null, null);
        };
        harness.menus.register(menu);
        viewer = harness.viewer("Alex");
        harness.open(viewer, KIND.id());
        harness.advance(SETTLED);
    }

    private static MenuLayout layout() {
        Map<String, ItemLayout> items = new LinkedHashMap<>();
        for (ItemLayout item : List.of(
            ItemLayout.of("deco", List.of(4), Material.STONE).name("<red>Decoration"),
            ItemLayout.of("act", List.of(10), Material.EMERALD).action("act").name("Act"),
            MenuHarness.item("left", 11, Material.DIAMOND, "left"),
            MenuHarness.item("work-a", 12, Material.CLOCK, "work"),
            MenuHarness.item("work-b", 13, Material.CLOCK, "work"),
            MenuHarness.item("show", 14, Material.PAPER, "show"),
            MenuHarness.item("fail", 15, Material.BARRIER, "fail"),
            MenuHarness.item("back", 45, Material.OAK_DOOR, MenuKind.BACK),
            MenuHarness.item("close", 53, Material.BARRIER, MenuKind.CLOSE))) {
            items.put(item.id(), item);
        }
        return MenuLayout.of(KIND.id(), 6).items(items.values()).title("<gold>Sample");
    }

    MenuSession<?> session() {
        MenuSession<?> session = viewer.session();
        if (session == null) {
            throw new IllegalStateException("the sample menu is not open");
        }
        return session;
    }
}
