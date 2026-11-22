package so.alaz.taffy.platform;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    void tryStartStartsACooldownForAFreeKey() {
        assertThat(manager.tryStart("daily", HOUR)).isTrue();

        assertThat(manager.remaining("daily")).isEqualTo(HOUR);
    }

    @Test
    void tryStartLeavesARunningCooldownAsItIs() {
        manager.set("daily", HOUR);
        advance(Duration.ofMinutes(20));

        assertThat(manager.tryStart("daily", HOUR.multipliedBy(3))).isFalse();

        assertThat(manager.remaining("daily")).isEqualTo(Duration.ofMinutes(40));
    }

    @Test
    void tryStartStartsAgainOnceTheCooldownHasExpired() {
        manager.tryStart("daily", HOUR);
        advance(HOUR);

        assertThat(manager.tryStart("daily", HOUR.multipliedBy(2))).isTrue();

        assertThat(manager.remaining("daily")).isEqualTo(HOUR.multipliedBy(2));
    }

    @Test
    void tryStartWithALengthOfZeroOrLessStartsNothingAndTellsWhetherTheKeyWasFree() {
        manager.set("running", HOUR);

        assertThat(manager.tryStart("free", Duration.ZERO)).isTrue();
        assertThat(manager.tryStart("free", HOUR.negated())).isTrue();
        assertThat(manager.tryStart("free", Duration.ofNanos(999_999))).isTrue();
        assertThat(manager.tryStart("running", Duration.ZERO)).isFalse();
        assertThat(manager.tryStart("running", HOUR.negated())).isFalse();

        assertThat(manager.isOnCooldown("free")).isFalse();
        assertThat(manager.remaining("running")).isEqualTo(HOUR);
    }

    @Test
    void tryStartWithALengthOfZeroOrLessRemovesAnExpiredCooldown() {
        manager.set("zero", HOUR);
        manager.set("negative", HOUR);
        advance(HOUR);

        assertThat(manager.tryStart("zero", Duration.ZERO)).isTrue();
        assertThat(manager.tryStart("negative", HOUR.negated())).isTrue();

        assertThat(manager.purgeExpired()).isZero();
    }

    @Test
    void tryStartWithALengthPastTheEndOfTheClockRangeLastsUntilThatEnd() {
        assertThat(manager.tryStart("daily", Duration.ofSeconds(Long.MAX_VALUE))).isTrue();

        assertThat(manager.remaining("daily")).isEqualTo(Duration.ofMillis(Long.MAX_VALUE - now.get()));
    }

    @Test
    void tryStartIsTrueForExactlyOneOfTheThreadsThatRaceForAKey() throws Exception {
        int threads = 16;
        int rounds = 500;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int round = 0; round < rounds; round++) {
                Integer key = round;
                CyclicBarrier together = new CyclicBarrier(threads);
                List<Future<Boolean>> results = new ArrayList<>();
                for (int thread = 0; thread < threads; thread++) {
                    results.add(pool.submit(() -> {
                        together.await();
                        return manager.tryStart(key, HOUR);
                    }));
                }
                int started = 0;
                for (Future<Boolean> result : results) {
                    if (result.get(10, TimeUnit.SECONDS)) {
                        started++;
                    }
                }

                assertThat(started).as("threads that started round %d", round).isEqualTo(1);
            }
        }
    }

    @Test
    void purgeExpiredRemovesTheExpiredCooldownsAndCountsThem() {
        manager.set("short", HOUR);
        manager.set("exact", HOUR.multipliedBy(2));
        manager.set("long", HOUR.multipliedBy(3));

        assertThat(manager.purgeExpired()).isZero();

        advance(HOUR.multipliedBy(2));

        assertThat(manager.purgeExpired()).isEqualTo(2);
        assertThat(manager.purgeExpired()).isZero();
        assertThat(manager.remaining("long")).isEqualTo(HOUR);
    }

    @Test
    void purgeExpiredKeepsACooldownSetAfterItReadTheClock() {
        manager.set("daily", HOUR);
        advance(HOUR.multipliedBy(2));
        duringClockRead.set(() -> manager.set("daily", HOUR.multipliedBy(3)));

        assertThat(manager.purgeExpired()).isZero();

        assertThat(manager.remaining("daily")).isEqualTo(HOUR.multipliedBy(3));
    }

    @Test
    void purgeExpiredKeepsACooldownSetBetweenItsReadOfTheExpiryAndItsRemoval() {
        AtomicReference<Runnable> duringHash = new AtomicReference<>(NOTHING);
        // ConcurrentHashMap hashes the key at the start of a remove, which purgeExpired reaches after it read the
        // expiry, and not while it iterates.
        Object key = new Object() {
            @Override
            public int hashCode() {
                duringHash.getAndSet(NOTHING).run();
                return 1;
            }
        };
        manager.set(key, HOUR);
        advance(HOUR.multipliedBy(2));
        duringHash.set(() -> manager.set(key, HOUR.multipliedBy(3)));

        assertThat(manager.purgeExpired()).isZero();

        assertThat(manager.remaining(key)).isEqualTo(HOUR.multipliedBy(3));
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
