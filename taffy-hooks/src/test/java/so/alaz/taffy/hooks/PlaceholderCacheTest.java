package so.alaz.taffy.hooks;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.CapturedLog;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class PlaceholderCacheTest {

    private static final String WARNING = "WARNING A placeholder value could not be refreshed, so its last known"
        + " value is kept (";

    private final CapturedLog log = new CapturedLog();
    private final List<CompletableFuture<@Nullable String>> started = new ArrayList<>();
    private long now = 1_000;

    // Stands in for a dispatch to another thread: each call starts one read that the test completes.
    private final Supplier<CompletableFuture<@Nullable String>> refresh = () -> {
        CompletableFuture<@Nullable String> future = new CompletableFuture<>();
        started.add(future);
        return future;
    };

    private PlaceholderCache<String> cache(int seconds, int maximumEntries) {
        return new PlaceholderCache<>(Duration.ofSeconds(seconds), maximumEntries, log.logger, () -> now);
    }

    private void pass(Duration time) {
        now += time.toNanos();
    }

    @Test
    void theFirstRequestGivesNoValueAndStartsOneRefresh() {
        PlaceholderCache<String> cache = cache(5, 10);

        assertThat(cache.get("ann", refresh)).isNull();
        assertThat(cache.get("ann", refresh)).isNull();
        assertThat(started).hasSize(1);

        started.getFirst().complete("100");

        assertThat(cache.get("ann", refresh)).isEqualTo("100");
        assertThat(started).hasSize(1);
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aRefreshThatCompletesAtOnceIsReturnedByTheSameCall() {
        PlaceholderCache<String> cache = cache(5, 10);

        assertThat(cache.get("ann", () -> CompletableFuture.completedFuture("100"))).isEqualTo("100");
    }

    @Test
    void aStaleValueIsReturnedWhileOneRefreshRuns() {
        PlaceholderCache<String> cache = cache(5, 10);
        cache.get("ann", refresh);
        started.getFirst().complete("100");

        pass(Duration.ofSeconds(5).minusNanos(1));
        assertThat(cache.get("ann", refresh)).isEqualTo("100");
        assertThat(started).hasSize(1);

        pass(Duration.ofNanos(1));
        assertThat(cache.get("ann", refresh)).isEqualTo("100");
        assertThat(cache.get("ann", refresh)).isEqualTo("100");
        assertThat(started).hasSize(2);

        started.get(1).complete("250");

        assertThat(cache.get("ann", refresh)).isEqualTo("250");
        assertThat(started).hasSize(2);
    }

    @Test
    void theTimeToLiveCountsFromTheCompletionOfTheRefresh() {
        PlaceholderCache<String> cache = cache(5, 10);
        cache.get("ann", refresh);
        pass(Duration.ofSeconds(4));
        started.getFirst().complete("100");

        pass(Duration.ofSeconds(4));

        assertThat(cache.get("ann", refresh)).isEqualTo("100");
        assertThat(started).hasSize(1);
    }

    @Test
    void eachKeyHasItsOwnValueAndRefresh() {
        PlaceholderCache<String> cache = cache(5, 10);

        cache.get("ann", refresh);
        cache.get("bo", refresh);
        started.get(1).complete("7");

        assertThat(started).hasSize(2);
        assertThat(cache.get("ann", refresh)).isNull();
        assertThat(cache.get("bo", refresh)).isEqualTo("7");
    }

    @Test
    void aRefreshThatGivesNullLeavesTheKeyWithoutAValueUntilItIsStale() {
        PlaceholderCache<String> cache = cache(5, 10);
        cache.get("ann", refresh);
        started.getFirst().complete("100");
        pass(Duration.ofSeconds(5));
        cache.get("ann", refresh);

        started.get(1).complete(null);

        assertThat(cache.get("ann", refresh)).isNull();
        assertThat(started).hasSize(2);
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aFailedRefreshKeepsTheLastValueIsWarnedAndIsTriedAgainOneTimeToLiveLater() {
        PlaceholderCache<String> cache = cache(5, 10);
        cache.get("ann", refresh);
        started.getFirst().complete("100");
        pass(Duration.ofSeconds(5));
        cache.get("ann", refresh);

        started.get(1).completeExceptionally(new IllegalStateException("closed"));

        assertThat(cache.get("ann", refresh)).isEqualTo("100");
        pass(Duration.ofSeconds(4));
        assertThat(cache.get("ann", refresh)).isEqualTo("100");
        assertThat(started).hasSize(2);
        pass(Duration.ofSeconds(1));
        assertThat(cache.get("ann", refresh)).isEqualTo("100");
        assertThat(started).hasSize(3);
        assertThat(log.lines()).containsExactly(WARNING + "java.lang.IllegalStateException: closed).");
        assertThat(log.records.getFirst().getThrown()).hasMessage("closed");
    }

    @Test
    void aFirstRefreshThatFailsGivesNoValue() {
        PlaceholderCache<String> cache = cache(5, 10);
        cache.get("ann", refresh);

        started.getFirst().completeExceptionally(new IllegalStateException("closed"));

        assertThat(cache.get("ann", refresh)).isNull();
        assertThat(started).hasSize(1);
    }

    @Test
    void aSupplierThatThrowsOrReturnsNoFutureIsAFailedRefresh() {
        PlaceholderCache<String> cache = cache(5, 10);

        assertThat(cache.get("ann", () -> {
            throw new IllegalStateException("no scheduler");
        })).isNull();
        assertThat(cache.get("bo", () -> null)).isNull();
        assertThat(cache.get("cy", () -> {
            throw new NoClassDefFoundError("gone");
        })).isNull();

        assertThat(cache.get("ann", refresh)).isNull();
        assertThat(cache.get("bo", refresh)).isNull();
        assertThat(cache.get("cy", refresh)).isNull();
        assertThat(started).isEmpty();
        pass(Duration.ofSeconds(5));
        cache.get("ann", refresh);
        cache.get("bo", refresh);
        assertThat(started).hasSize(2);
        assertThat(log.lines()).containsExactly(WARNING + "java.lang.IllegalStateException: no scheduler).");
    }

    @Test
    void failuresOfSeveralKeysShareOneWarningPerWindow() {
        PlaceholderCache<String> cache = cache(5, 10);

        for (String key : List.of("ann", "bo", "cy")) {
            cache.get(key, () -> CompletableFuture.failedFuture(new IllegalStateException("closed")));
        }
        pass(Duration.ofSeconds(5));
        cache.get("ann", () -> CompletableFuture.failedFuture(new IllegalStateException("closed")));

        assertThat(log.lines()).containsExactly(WARNING + "java.lang.IllegalStateException: closed).");
    }

    @Test
    void aNewKeyBeyondTheMaximumDropsTheKeyAskedForLongestAgo() {
        PlaceholderCache<String> cache = cache(5, 2);
        cache.get("ann", () -> CompletableFuture.completedFuture("1"));
        cache.get("bo", () -> CompletableFuture.completedFuture("2"));
        cache.get("ann", refresh);

        cache.get("cy", () -> CompletableFuture.completedFuture("3"));

        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.get("ann", refresh)).isEqualTo("1");
        assertThat(cache.get("cy", refresh)).isEqualTo("3");
        assertThat(started).isEmpty();
        assertThat(cache.get("bo", refresh)).isNull();
        assertThat(started).hasSize(1);
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void aKeyWhoseFirstRefreshStillRunsCountsTowardsTheMaximumAndIsNotDropped() {
        PlaceholderCache<String> cache = cache(5, 2);

        for (int i = 0; i < 50; i++) {
            assertThat(cache.get("key-" + i, refresh)).isNull();
        }

        assertThat(cache.size()).isEqualTo(2);
        assertThat(started).hasSize(2);

        started.get(1).complete("b");
        started.getFirst().complete("a");

        assertThat(cache.get("key-0", refresh)).isEqualTo("a");
        assertThat(cache.get("key-1", refresh)).isEqualTo("b");
        assertThat(started).hasSize(2);
    }

    @Test
    void aKeyWhoseRefreshRunsIsNotLoadedASecondTimeWhenNewKeysArrive() {
        PlaceholderCache<String> cache = cache(5, 1);

        cache.get("ann", refresh);
        assertThat(cache.get("bo", refresh)).isNull();
        assertThat(cache.get("ann", refresh)).isNull();

        assertThat(started).hasSize(1);
        started.getFirst().complete("100");
        assertThat(cache.get("ann", refresh)).isEqualTo("100");

        assertThat(cache.get("bo", refresh)).isNull();
        assertThat(started).hasSize(2);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void aNewKeyDropsTheEldestKeyWithNoRefreshRunning() {
        PlaceholderCache<String> cache = cache(5, 2);
        cache.get("ann", () -> CompletableFuture.completedFuture("1"));
        cache.get("bo", () -> CompletableFuture.completedFuture("2"));
        pass(Duration.ofSeconds(5));
        cache.get("ann", refresh);
        cache.get("bo", () -> CompletableFuture.completedFuture("3"));

        cache.get("cy", () -> CompletableFuture.completedFuture("4"));

        assertThat(cache.size()).isEqualTo(2);
        started.getFirst().complete("5");
        assertThat(cache.get("ann", refresh)).isEqualTo("5");
        assertThat(cache.get("cy", refresh)).isEqualTo("4");
        assertThat(started).hasSize(1);
    }

    @Test
    void anErrorFromTheSupplierLeavesGetAndTheKeyIsRefreshedAgainOneTimeToLiveLater() {
        PlaceholderCache<String> cache = cache(5, 10);
        cache.get("ann", () -> CompletableFuture.completedFuture("100"));
        pass(Duration.ofSeconds(5));

        assertThatThrownBy(() -> cache.get("ann", () -> {
            throw new AssertionError("broken");
        })).isInstanceOf(AssertionError.class).hasMessage("broken");

        assertThat(cache.get("ann", refresh)).isEqualTo("100");
        assertThat(started).isEmpty();
        pass(Duration.ofSeconds(5));
        assertThat(cache.get("ann", refresh)).isEqualTo("100");
        assertThat(started).hasSize(1);
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void forgetDropsTheKeyAndARefreshStillRunningStoresNothing() {
        PlaceholderCache<String> cache = cache(5, 10);
        cache.get("ann", refresh);
        started.getFirst().complete("100");
        pass(Duration.ofSeconds(5));
        cache.get("ann", refresh);

        cache.forget("ann");
        cache.forget("never");
        started.get(1).complete("250");

        assertThat(cache.size()).isZero();
        assertThat(cache.get("ann", refresh)).isNull();
        assertThat(started).hasSize(3);
        started.get(2).complete("300");
        assertThat(cache.get("ann", refresh)).isEqualTo("300");
    }

    @Test
    void clearDropsEveryKeyAndTheCacheWorksAgain() {
        PlaceholderCache<String> cache = cache(5, 10);
        cache.get("ann", () -> CompletableFuture.completedFuture("1"));
        cache.get("bo", refresh);

        cache.clear();
        started.getFirst().complete("2");

        assertThat(cache.size()).isZero();
        assertThat(cache.get("bo", refresh)).isNull();
        assertThat(cache.get("ann", () -> CompletableFuture.completedFuture("5"))).isEqualTo("5");
    }

    @Test
    void getDoesNotWaitForARefreshThatNeverCompletes() {
        PlaceholderCache<String> cache = new PlaceholderCache<>(Duration.ofMillis(1), 10, log.logger);

        assertThat(cache.get("ann", CompletableFuture::new)).isNull();
        assertThat(cache.get("ann", refresh)).isNull();
        assertThat(started).isEmpty();
    }

    @Test
    void aTimeToLiveLongerThanNanosecondsCanCountNeverGoesStale() {
        PlaceholderCache<String> cache = new PlaceholderCache<>(Duration.ofDays(200_000 * 365L), 10, log.logger,
            () -> now);
        cache.get("ann", () -> CompletableFuture.completedFuture("1"));

        pass(Duration.ofDays(36_500));

        assertThat(cache.get("ann", refresh)).isEqualTo("1");
        assertThat(started).isEmpty();
    }

    @Test
    void aTimeToLiveOfZeroOrLessAndAMaximumBelowOneAreRefused() {
        assertThatThrownBy(() -> new PlaceholderCache<String>(Duration.ZERO, 10, log.logger))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("invalid time-to-live PT0S: expected more than zero");
        assertThatThrownBy(() -> new PlaceholderCache<String>(Duration.ofSeconds(-3), 10, log.logger))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("invalid time-to-live PT-3S: expected more than zero");
        assertThatThrownBy(() -> new PlaceholderCache<String>(Duration.ofSeconds(5), 0, log.logger))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("invalid maximum of 0 entries: expected at least one");
    }

    @Test
    void aNullKeyRefreshOrLoggerIsRefusedByName() {
        PlaceholderCache<String> cache = cache(5, 10);

        assertThatThrownBy(() -> cache.get(null, refresh)).isInstanceOf(NullPointerException.class).hasMessage("key");
        assertThatThrownBy(() -> cache.get("ann", null)).isInstanceOf(NullPointerException.class)
            .hasMessage("refresh");
        assertThatThrownBy(() -> cache.forget(null)).isInstanceOf(NullPointerException.class).hasMessage("key");
        assertThatThrownBy(() -> new PlaceholderCache<String>(Duration.ofSeconds(5), 10, null))
            .isInstanceOf(NullPointerException.class).hasMessage("logger");
        assertThatThrownBy(() -> new PlaceholderCache<String>(null, 10, log.logger))
            .isInstanceOf(NullPointerException.class).hasMessage("timeToLive");
    }
}
