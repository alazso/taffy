package so.alaz.taffy.gui;

import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

final class DebounceTest {

    private static final Instant START = Instant.parse("2026-11-15T12:00:00Z");
    private static final UUID ALEX = UUID.randomUUID();
    private static final UUID SAM = UUID.randomUUID();

    @TempDir
    Path folder;

    private final GuiFakes.MutableClock clock = new GuiFakes.MutableClock(START);
    private final Debounce debounce = new Debounce(() -> Duration.ofMillis(250), clock);

    @Test
    void aSecondClickWithinTheWindowIsDroppedAndOneAtTheWindowPasses() {
        assertThat(debounce.pass(ALEX, "home:open")).isTrue();
        clock.advance(Duration.ofMillis(249));
        assertThat(debounce.pass(ALEX, "home:open")).isFalse();
        clock.advance(Duration.ofMillis(1));
        assertThat(debounce.pass(ALEX, "home:open")).isTrue();
    }

    @Test
    void anotherButtonAndAnotherPlayerPassInsideTheWindow() {
        assertThat(debounce.pass(ALEX, "home:open")).isTrue();

        assertThat(debounce.pass(ALEX, "home:options")).isTrue();
        assertThat(debounce.pass(SAM, "home:open")).isTrue();
    }

    @Test
    void aDroppedClickDoesNotMoveTheWindow() {
        debounce.pass(ALEX, "row:9");
        clock.advance(Duration.ofMillis(200));
        assertThat(debounce.pass(ALEX, "row:9")).isFalse();

        clock.advance(Duration.ofMillis(50));

        assertThat(debounce.pass(ALEX, "row:9")).isTrue();
    }

    @Test
    void forgetClearsAPlayerAndTheWindowIsReadEachTime() {
        Duration[] window = {Duration.ofMillis(500)};
        Debounce configured = new Debounce(() -> window[0], clock);
        configured.pass(ALEX, "home:open");
        clock.advance(Duration.ofMillis(300));
        assertThat(configured.pass(ALEX, "home:open")).isFalse();

        configured.forget(ALEX);

        assertThat(configured.pass(ALEX, "home:open")).isTrue();
        window[0] = Duration.ofMillis(100);
        clock.advance(Duration.ofMillis(100));
        assertThat(configured.pass(ALEX, "home:open")).isTrue();
    }

    @Test
    void settledIsTrueOnceTheWindowPassedSinceAnInstant() {
        assertThat(debounce.settled(clock.instant())).isFalse();
        clock.advance(Duration.ofMillis(250));
        assertThat(debounce.settled(START)).isTrue();
    }

    @Test
    void aClickSoonAfterAMenuOpensIsDroppedAndActsOnceTheWindowPassed() {
        Sample sample = new Sample(folder);
        sample.harness.open(sample.viewer, Sample.KIND.id());

        sample.harness.advance(Duration.ofMillis(100));
        sample.viewer.click(10, ClickType.LEFT);
        assertThat(sample.acts).hasValue(0);

        sample.harness.advance(Duration.ofMillis(160));
        sample.viewer.click(10, ClickType.LEFT);
        assertThat(sample.acts).hasValue(1);
    }

    @Test
    void theConfiguredWindowAppliesPerButton() {
        Sample sample = new Sample(folder, new MenuSettings(45, MenuSettings.DEFAULTS.filler(),
            Duration.ofMillis(500), MenuSettings.DEFAULTS.loadingItem()));
        sample.harness.advance(Duration.ofMillis(300));

        sample.viewer.click(10, ClickType.LEFT);
        sample.viewer.click(11, ClickType.LEFT);
        sample.harness.advance(Duration.ofMillis(400));
        sample.viewer.click(10, ClickType.RIGHT);

        assertThat(sample.acts).hasValue(1);
        assertThat(sample.lefts).hasValue(1);
        sample.harness.advance(Duration.ofMillis(100));
        sample.viewer.click(10, ClickType.RIGHT);
        assertThat(sample.acts).hasValue(2);
    }
}
