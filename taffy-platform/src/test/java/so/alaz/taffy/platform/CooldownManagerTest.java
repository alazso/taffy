package so.alaz.taffy.platform;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CooldownManagerTest {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Runnable NOTHING = () -> {
    };

    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);
    private final AtomicReference<Runnable> duringClockRead = new AtomicReference<>(NOTHING);

    // The clock runs duringClockRead once, which stands in for another thread acting in the middle of a call.
    private final CooldownManager manager = new CooldownManager(() -> {
        duringClockRead.getAndSet(NOTHING).run();
        return now.get();
    });

    private void advance(Duration time) {
        now.addAndGet(time.toMillis());
    }

    @Test
    void remainingIsTheSetLengthLessTheTimePassed() {
        manager.set("daily", HOUR);

        assertThat(manager.remaining("daily")).isEqualTo(HOUR);
        assertThat(manager.isOnCooldown("daily")).isTrue();

        advance(Duration.ofMinutes(20));

        assertThat(manager.remaining("daily")).isEqualTo(Duration.ofMinutes(40));
    }

    @Test
    void aCooldownIsOverOnceTheClockReachesItsExpiry() {
        manager.set("daily", HOUR);

        advance(HOUR.minusMillis(1));
        assertThat(manager.remaining("daily")).isEqualTo(Duration.ofMillis(1));
        assertThat(manager.isOnCooldown("daily")).isTrue();

        advance(Duration.ofMillis(1));
        assertThat(manager.remaining("daily")).isEqualTo(Duration.ZERO);
        assertThat(manager.isOnCooldown("daily")).isFalse();

        advance(HOUR);
        assertThat(manager.remaining("daily")).isEqualTo(Duration.ZERO);
        assertThat(manager.isOnCooldown("daily")).isFalse();
    }

    @Test
    void anUnknownKeyHasNoCooldown() {
        assertThat(manager.isOnCooldown("never set")).isFalse();
        assertThat(manager.remaining("never set")).isEqualTo(Duration.ZERO);
    }

    @Test
    void settingAgainRestartsTheCooldownWithTheNewLength() {
        manager.set("daily", HOUR.multipliedBy(3));
        manager.set("daily", HOUR);

        assertThat(manager.remaining("daily")).isEqualTo(HOUR);
    }

    @Test
    void aLengthOfZeroOrLessClearsARunningCooldown() {
        manager.set("zero", HOUR);
        manager.set("negative", HOUR);

        manager.set("zero", Duration.ZERO);
        manager.set("negative", HOUR.negated());

        assertThat(manager.isOnCooldown("zero")).isFalse();
        assertThat(manager.isOnCooldown("negative")).isFalse();
        assertThat(manager.remaining("zero")).isEqualTo(Duration.ZERO);
    }

    @Test
    void aPositiveLengthBelowOneMillisecondCountsAsZero() {
        manager.set("daily", HOUR);

        manager.set("daily", Duration.ofNanos(999_999));
        manager.extend("weekly", Duration.ofNanos(999_999));

        assertThat(manager.isOnCooldown("daily")).isFalse();
        assertThat(manager.isOnCooldown("weekly")).isFalse();
    }

    @Test
    void aLengthPastTheEndOfTheClockRangeLastsUntilThatEnd() {
        Duration untilTheEnd = Duration.ofMillis(Long.MAX_VALUE - now.get());

        manager.set("max millis", Duration.ofMillis(Long.MAX_VALUE));
        manager.set("beyond a long", Duration.ofSeconds(Long.MAX_VALUE));
        manager.extend("extended", Duration.ofSeconds(Long.MAX_VALUE));

        assertThat(manager.remaining("max millis")).isEqualTo(untilTheEnd);
        assertThat(manager.remaining("beyond a long")).isEqualTo(untilTheEnd);
        assertThat(manager.remaining("extended")).isEqualTo(untilTheEnd);
    }

    @Test
    void aNegativeLengthBeyondALongClears() {
        manager.set("daily", HOUR);

        manager.set("daily", Duration.ofSeconds(Long.MIN_VALUE));
        manager.extend("weekly", Duration.ofSeconds(Long.MIN_VALUE));

        assertThat(manager.isOnCooldown("daily")).isFalse();
        assertThat(manager.isOnCooldown("weekly")).isFalse();
    }

    @Test
    void extendKeepsTheLaterExpiry() {
        manager.set("daily", HOUR.multipliedBy(2));
        manager.extend("daily", HOUR);
        assertThat(manager.remaining("daily")).isEqualTo(HOUR.multipliedBy(2));
        manager.extend("daily", HOUR.multipliedBy(4));
        assertThat(manager.remaining("daily")).isEqualTo(HOUR.multipliedBy(4));
    }

    @Test
    void extendStartsACooldownWhenNoneIsRunning() {
        manager.extend("daily", HOUR);
        assertThat(manager.isOnCooldown("daily")).isTrue();
        assertThat(manager.remaining("daily")).isEqualTo(HOUR);
    }

    @Test
    void extendIgnoresANonPositiveLength() {
        manager.set("daily", HOUR);
        manager.extend("daily", HOUR.negated());
        manager.extend("weekly", Duration.ZERO);
        assertThat(manager.remaining("daily")).isEqualTo(HOUR);
        assertThat(manager.isOnCooldown("weekly")).isFalse();
    }

    @Test
    void readingAnExpiredCooldownKeepsOneSetMeanwhile() {
        manager.set("daily", HOUR);
        advance(HOUR.multipliedBy(2));
        duringClockRead.set(() -> manager.set("daily", HOUR.multipliedBy(3)));

        assertThat(manager.remaining("daily")).isEqualTo(Duration.ZERO);

        assertThat(manager.remaining("daily")).isEqualTo(HOUR.multipliedBy(3));
    }

    @Test
    void keysAreComparedByEquality() {
        record Key(String player, String what) {
        }
        manager.set(new Key("ann", "daily"), HOUR);

        assertThat(manager.isOnCooldown(new Key("ann", "daily"))).isTrue();
        assertThat(manager.isOnCooldown(new Key("ann", "weekly"))).isFalse();
    }

    @Test
    void thePublicConstructorReadsTheSystemClock() {
        CooldownManager system = new CooldownManager();

        system.set("daily", HOUR);

        assertThat(system.remaining("daily")).isBetween(Duration.ofMinutes(59), HOUR);
    }
}
