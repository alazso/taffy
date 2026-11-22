package so.alaz.taffy.platform;

import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.CapturedLog;
import so.alaz.taffy.test.RaceRunner;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class PlayerCacheTest {

    private record Retry(Runnable task, Duration delay) {
    }

    private final UUID alex = UUID.randomUUID();
    private final UUID sam = UUID.randomUUID();
    private final FakeDispatcher dispatcher = new FakeDispatcher();
    private final CapturedLog log = new CapturedLog();
    private final ErrorReports errors = new ErrorReports();
    private final List<String> reported = new ArrayList<>();
    private final List<Throwable> reportedErrors = new ArrayList<>();
    private final List<Retry> retries = new ArrayList<>();
    private final List<CompletableFuture<String>> loads = new CopyOnWriteArrayList<>();
    private final List<UUID> loaded = new CopyOnWriteArrayList<>();
    private final List<Listener> registered = new ArrayList<>();
    private final List<Listener> unregistered = new ArrayList<>();

    PlayerCacheTest() {
        errors.install((source, error) -> {
            reported.add(source);
            reportedErrors.add(error);
        });
    }

    @Test
    void aJoinLoadsThroughTheAsyncTagAndTheValueIsReadableOnceTheLoadEnds() {
        PlayerCache<String> cache = cache();

        cache.joined(alex);

        assertThat(dispatcher.tags()).containsExactly(ThreadTag.ASYNC);
        assertThat(loaded).containsExactly(alex);
        assertThat(cache.ready(alex)).isFalse();
        assertThat(cache.get(alex)).isEmpty();

        loads.get(0).complete("first");

        assertThat(cache.ready(alex)).isTrue();
        assertThat(cache.get(alex)).contains("first");
        assertThat(cache.ready(sam)).isFalse();
        assertThat(cache.get(sam)).isEmpty();
    }

    @Test
    void theLoaderIsNotCalledOnTheThreadOfTheJoin() {
        PlayerCache<String> cache = cache();
        dispatcher.hold();

        cache.joined(alex);

        assertThat(loaded).isEmpty();
        dispatcher.runHeld();
        assertThat(loaded).containsExactly(alex);
    }

    @Test
    void aQuitForgetsThePlayer() {
        PlayerCache<String> cache = cache();
        cache.joined(alex);
        loads.get(0).complete("first");

        cache.left(alex);

        assertThat(cache.ready(alex)).isFalse();
        assertThat(cache.get(alex)).isEmpty();
    }

    @Test
    void aResultThatArrivesAfterTheQuitIsDropped() {
        PlayerCache<String> cache = cache();
        cache.joined(alex);
        cache.left(alex);

        loads.get(0).complete("late");

        assertThat(cache.ready(alex)).isFalse();
    }

    @Test
    void aQuitBeforeTheDispatchedLoadRunsDropsItWithoutCallingTheLoader() {
        PlayerCache<String> cache = cache();
        dispatcher.hold();
        cache.joined(alex);
        cache.left(alex);

        dispatcher.runHeld();

        assertThat(loaded).isEmpty();
        assertThat(cache.ready(alex)).isFalse();
    }

    @Test
    void theResultOfTheLoadBeforeARejoinIsDropped() {
        PlayerCache<String> cache = cache();
        cache.joined(alex);
        cache.left(alex);
        cache.joined(alex);

        loads.get(0).complete("old");
        assertThat(cache.ready(alex)).isFalse();

        loads.get(1).complete("new");
        assertThat(cache.get(alex)).contains("new");
    }

    @Test
    void aFailedLoadIsReportedLoggedAndRetriedAfterADoublingDelay() {
        PlayerCache<String> cache = cache();
        IllegalStateException down = new IllegalStateException("down");
        cache.joined(alex);

        loads.get(0).completeExceptionally(down);

        assertThat(cache.ready(alex)).isFalse();
        assertThat(reported).containsExactly(PlayerCache.SOURCE);
        assertThat(reportedErrors).containsExactly(down);
        assertThat(log.lines()).singleElement().asString().startsWith("WARNING Could not load the cached value of "
            + alex);
        assertThat(log.records.get(0).getThrown()).isSameAs(down);
        assertThat(retries).extracting(Retry::delay).containsExactly(Duration.ofSeconds(2));
        assertThat(loaded).hasSize(1);

        retries.get(0).task().run();
        assertThat(loaded).containsExactly(alex, alex);
        assertThat(dispatcher.tags()).containsExactly(ThreadTag.ASYNC, ThreadTag.ASYNC);
        loads.get(1).completeExceptionally(down);
        assertThat(retries).extracting(Retry::delay).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4));
        assertThat(cache.ready(alex)).isFalse();

        retries.get(1).task().run();
        loads.get(2).complete("third");
        assertThat(cache.get(alex)).contains("third");
        assertThat(retries).hasSize(2);
        assertThat(reported).hasSize(2);
    }

    @Test
    void theRetryDelayDoublesUpToTheLongest() {
        assertThat(IntStream.rangeClosed(1, 8).mapToObj(PlayerCache::retryDelay).map(Duration::toSeconds))
            .containsExactly(2L, 4L, 8L, 16L, 32L, 64L, 128L, 256L);
        assertThat(PlayerCache.retryDelay(9)).isEqualTo(PlayerCache.LONGEST_RETRY_DELAY);
        assertThat(PlayerCache.retryDelay(64)).isEqualTo(PlayerCache.LONGEST_RETRY_DELAY);
        assertThat(PlayerCache.retryDelay(Integer.MAX_VALUE)).isEqualTo(PlayerCache.LONGEST_RETRY_DELAY);
    }

    @Test
    void aFailureWrappedByAStageIsReportedAsItsCause() {
        PlayerCache<String> cache = cache();
        IllegalStateException down = new IllegalStateException("down");
        cache.joined(alex);

        loads.get(0).completeExceptionally(new CompletionException(down));

        assertThat(reportedErrors).containsExactly(down);
    }

    @Test
    void aLoaderThatThrowsReturnsNullOrCompletesWithNullFailsTheLoad() {
        List<Function<UUID, CompletableFuture<String>>> loaders = List.of(
            player -> {
                throw new IllegalStateException("thrown");
            },
            player -> null,
            player -> CompletableFuture.completedFuture(null));
        for (Function<UUID, CompletableFuture<String>> loader : loaders) {
            retries.clear();
            PlayerCache<String> cache = cache(loader, List.of());

            cache.joined(alex);

            assertThat(cache.ready(alex)).isFalse();
            assertThat(retries).hasSize(1);
        }
        assertThat(reported).containsExactly(PlayerCache.SOURCE, PlayerCache.SOURCE, PlayerCache.SOURCE);
        assertThat(reportedErrors.get(0)).isInstanceOf(IllegalStateException.class);
        assertThat(reportedErrors.get(1)).isInstanceOf(NullPointerException.class);
        assertThat(reportedErrors.get(2)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aRetryOfAPlayerWhoQuitDoesNotLoad() {
        PlayerCache<String> cache = cache();
        cache.joined(alex);
        loads.get(0).completeExceptionally(new IllegalStateException("down"));
        cache.left(alex);

        retries.get(0).task().run();

        assertThat(loaded).hasSize(1);
        assertThat(dispatcher.tags()).hasSize(1);
    }

    @Test
    void aFailureThatArrivesAfterTheQuitIsNeitherReportedNorRetried() {
        PlayerCache<String> cache = cache();
        cache.joined(alex);
        cache.left(alex);

        loads.get(0).completeExceptionally(new IllegalStateException("down"));

        assertThat(reported).isEmpty();
        assertThat(retries).isEmpty();
    }

    @Test
    void aRejoinDuringARetryLeavesOnlyTheNewLoadRunning() {
        PlayerCache<String> cache = cache();
        cache.joined(alex);
        loads.get(0).completeExceptionally(new IllegalStateException("down"));
        cache.left(alex);
        cache.joined(alex);

        retries.get(0).task().run();

        assertThat(loaded).hasSize(2);
        loads.get(1).complete("new");
        assertThat(cache.get(alex)).contains("new");
    }

    @Test
    void aRefreshKeepsTheValueUntilTheNewLoadSucceeds() {
        PlayerCache<String> cache = cache();
        cache.joined(alex);
        loads.get(0).complete("first");

        cache.refresh(alex);

        assertThat(loaded).containsExactly(alex, alex);
        assertThat(cache.get(alex)).contains("first");
        loads.get(1).completeExceptionally(new IllegalStateException("down"));
        assertThat(cache.get(alex)).contains("first");
        assertThat(retries).hasSize(1);

        retries.get(0).task().run();
        loads.get(2).complete("second");
        assertThat(cache.get(alex)).contains("second");
    }

    @Test
    void aRefreshReplacesTheLoadStillRunning() {
        PlayerCache<String> cache = cache();
        cache.joined(alex);
        loads.get(0).completeExceptionally(new IllegalStateException("down"));

        cache.refresh(alex);
        retries.get(0).task().run();

        assertThat(loaded).hasSize(2);
        loads.get(1).complete("second");
        assertThat(cache.get(alex)).contains("second");
    }

    @Test
    void aRefreshOfAPlayerWhoIsNotOnlineDoesNothing() {
        PlayerCache<String> cache = cache();

        cache.refresh(alex);

        assertThat(loaded).isEmpty();
        assertThat(dispatcher.tags()).isEmpty();
    }

    @Test
    void aRefusedDispatchEndsTheLoadWithNoReportAndNoRetry() {
        PlayerCache<String> cache = cache();
        dispatcher.refuse();

        cache.joined(alex);

        assertThat(loaded).isEmpty();
        assertThat(reported).isEmpty();
        assertThat(retries).isEmpty();
        assertThat(cache.ready(alex)).isFalse();
    }

    @Test
    void aTimerThatThrowsIsReported() {
        IllegalArgumentException refused = new IllegalArgumentException("no timer");
        PlayerCache<String> cache = new PlayerCache<>(log.logger, errors, dispatcher,
            (task, delay) -> {
                throw refused;
            }, this::load, registered::add, unregistered::add, List::of);
        cache.joined(alex);

        loads.get(0).completeExceptionally(new IllegalStateException("down"));

        assertThat(reported).containsExactly(PlayerCache.SOURCE, PlayerCache.SOURCE);
        assertThat(reportedErrors.get(1)).isSameAs(refused);
    }

    @Test
    void thePlayersAlreadyOnlineLoadWhenTheCacheIsBuilt() {
        PlayerCache<String> cache = cache(this::load, List.of(alex, sam));

        assertThat(loaded).containsExactly(alex, sam);
        loads.get(0).completeExceptionally(new IllegalStateException("down"));
        loads.get(1).complete("sam");

        assertThat(cache.ready(alex)).isFalse();
        assertThat(cache.get(sam)).contains("sam");
        assertThat(retries).hasSize(1);
        retries.get(0).task().run();
        loads.get(2).complete("alex");
        assertThat(cache.get(alex)).contains("alex");
    }

    @Test
    void thePublicConstructorRegistersTheListenerOnTheOwnerAndLoadsTheOnlinePlayers() {
        Plugin owner = mock(Plugin.class);
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        Player player = mock(Player.class);
        when(owner.getServer()).thenReturn(server);
        when(owner.getLogger()).thenReturn(log.logger);
        when(server.getPluginManager()).thenReturn(manager);
        when(player.getUniqueId()).thenReturn(alex);
        doReturn(List.of(player)).when(server).getOnlinePlayers();

        PlayerCache<String> cache = new PlayerCache<>(owner, errors, dispatcher, this::retry, this::load);

        verify(manager).registerEvents(same(cache.listener()), same(owner));
        assertThat(loaded).containsExactly(alex);
        loads.get(0).complete("first");
        assertThat(cache.get(alex)).contains("first");
        cache.close();
        assertThat(cache.ready(alex)).isFalse();
    }

    @Test
    void aNullArgumentIsRefused() {
        Plugin owner = mock(Plugin.class);
        assertThatThrownBy(() -> new PlayerCache<String>(null, errors, dispatcher, this::retry, this::load))
            .isInstanceOf(NullPointerException.class).hasMessage("owner");
        assertThatThrownBy(() -> new PlayerCache<String>(owner, null, dispatcher, this::retry, this::load))
            .isInstanceOf(NullPointerException.class).hasMessage("errors");
        assertThatThrownBy(() -> new PlayerCache<String>(owner, errors, null, this::retry, this::load))
            .isInstanceOf(NullPointerException.class).hasMessage("dispatcher");
        assertThatThrownBy(() -> new PlayerCache<String>(owner, errors, dispatcher, null, this::load))
            .isInstanceOf(NullPointerException.class).hasMessage("timer");
        assertThatThrownBy(() -> new PlayerCache<String>(owner, errors, dispatcher, this::retry, null))
            .isInstanceOf(NullPointerException.class).hasMessage("loader");
        verify(owner, never()).getServer();
    }

    @Test
    void theListenerDeliversTheJoinAndTheQuit() throws Exception {
        PlayerCache<String> cache = cache();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(alex);
        assertThat(registered).containsExactly(cache.listener());
        assertThat(Modifier.isPublic(PlayerCache.Events.class.getModifiers())).isFalse();

        executor("onJoin", PlayerJoinEvent.class).execute(cache.listener(),
            new PlayerJoinEvent(player, Component.empty()));
        assertThat(loaded).containsExactly(alex);
        loads.get(0).complete("first");
        assertThat(cache.ready(alex)).isTrue();

        executor("onQuit", PlayerQuitEvent.class).execute(cache.listener(),
            new PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertThat(cache.ready(alex)).isFalse();
    }

    @Test
    void everyHandlerIsPublicSoPaperCanBuildItsExecutor() {
        List<Method> handlers = Arrays.stream(PlayerCache.Events.class.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(EventHandler.class)).toList();

        assertThat(handlers).hasSize(2).allMatch(method -> Modifier.isPublic(method.getModifiers()));
    }

    @Test
    void closeUnregistersTheListenerAndDropsWhatIsStillRunning() {
        PlayerCache<String> cache = cache();
        cache.joined(alex);
        cache.joined(sam);
        loads.get(0).complete("first");
        loads.get(1).completeExceptionally(new IllegalStateException("down"));

        cache.close();

        assertThat(unregistered).containsExactly(cache.listener());
        assertThat(cache.get(alex)).isEmpty();
        retries.get(0).task().run();
        assertThat(loaded).hasSize(2);

        cache.joined(alex);
        cache.refresh(alex);
        assertThat(loaded).hasSize(2);
        assertThat(cache.ready(alex)).isFalse();
        cache.close();
    }

    @Test
    void aResultThatArrivesAfterCloseIsDropped() {
        PlayerCache<String> cache = cache();
        cache.joined(alex);

        cache.close();
        loads.get(0).complete("late");

        assertThat(cache.ready(alex)).isFalse();
    }

    @Test
    void aQuitRacingTheResultLeavesNoValue() throws Exception {
        PlayerCache<String> cache = cache();
        for (int round = 0; round < 500; round++) {
            cache.joined(alex);
            CompletableFuture<String> loading = loads.get(loads.size() - 1);

            RaceRunner.race(2, contender -> {
                if (contender == 0) {
                    cache.left(alex);
                } else {
                    loading.complete("value");
                }
                return contender;
            });

            assertThat(cache.ready(alex)).as("round %d", round).isFalse();
        }
    }

    @Test
    void anOlderResultRacingARefreshNeverReplacesTheNewerOne() throws Exception {
        List<CompletableFuture<String>> older = new CopyOnWriteArrayList<>();
        PlayerCache<String> cache = cache(player -> {
            if (older.isEmpty()) {
                CompletableFuture<String> loading = new CompletableFuture<>();
                older.add(loading);
                return loading;
            }
            return CompletableFuture.completedFuture("new");
        }, List.of());
        for (int round = 0; round < 500; round++) {
            older.clear();
            cache.joined(alex);
            CompletableFuture<String> loading = older.get(0);

            RaceRunner.race(2, contender -> {
                if (contender == 0) {
                    loading.complete("old");
                } else {
                    cache.refresh(alex);
                }
                return contender;
            });

            assertThat(cache.get(alex)).as("round %d", round).contains("new");
        }
    }

    @Test
    void readersSeeNoValueOrTheLoadedOneWhileJoinsAndQuitsRace() throws Exception {
        PlayerCache<String> cache = cache(player -> CompletableFuture.completedFuture("value"), List.of());

        List<Boolean> sound = RaceRunner.race(6, contender -> {
            boolean seen = true;
            for (int round = 0; round < 2_000; round++) {
                switch (contender % 3) {
                    case 0 -> cache.joined(alex);
                    case 1 -> cache.left(alex);
                    default -> seen &= cache.get(alex).map("value"::equals).orElse(true)
                        && !cache.ready(sam);
                }
            }
            return seen;
        });

        assertThat(sound).containsOnly(true);
        cache.left(alex);
        assertThat(cache.ready(alex)).isFalse();
    }

    private PlayerCache<String> cache() {
        return cache(this::load, List.of());
    }

    private PlayerCache<String> cache(Function<UUID, CompletableFuture<String>> loader, Collection<UUID> online) {
        return new PlayerCache<>(log.logger, errors, dispatcher, this::retry, loader, registered::add,
            unregistered::add, () -> online);
    }

    private CompletableFuture<String> load(UUID player) {
        CompletableFuture<String> loading = new CompletableFuture<>();
        loaded.add(player);
        loads.add(loading);
        return loading;
    }

    private void retry(Runnable task, Duration delay) {
        retries.add(new Retry(task, delay));
    }

    private static EventExecutor executor(String handler, Class<? extends Event> event) throws Exception {
        return EventExecutor.create(PlayerCache.Events.class.getDeclaredMethod(handler, event), event);
    }
}
