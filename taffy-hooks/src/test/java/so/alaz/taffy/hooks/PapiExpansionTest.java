package so.alaz.taffy.hooks;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import so.alaz.taffy.test.CapturedLog;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class PapiExpansionTest {

    private final Player online = mock(Player.class);
    private final OfflinePlayer joined = mock(OfflinePlayer.class);
    private final OfflinePlayer away = mock(OfflinePlayer.class);

    PapiExpansionTest() {
        when(online.getName()).thenReturn("Alex");
        when(joined.getPlayer()).thenReturn(online);
        when(joined.getName()).thenReturn("Alex");
        when(away.getName()).thenReturn("Sam");
    }

    private static PapiExpansion expansion() {
        Map<String, Function<Player, @Nullable String>> players = Map.of(
            "name", player -> "player " + player.getName(),
            "shared", player -> "player shared",
            "nothing", player -> null);
        Map<String, Function<OfflinePlayer, @Nullable String>> offline = Map.of(
            "last_seen", player -> "seen " + player.getName(),
            "shared", player -> "offline shared");
        Map<String, Supplier<@Nullable String>> globals = Map.of(
            "online", () -> "5",
            "shared", () -> "global shared");
        return new PapiExpansion("shop", "Ana, Bo", "2.1", players, offline, globals);
    }

    @Test
    void describesItselfAndSurvivesAPlaceholderApiReload() {
        PapiExpansion expansion = expansion();

        assertThat(expansion.getIdentifier()).isEqualTo("shop");
        assertThat(expansion.getAuthor()).isEqualTo("Ana, Bo");
        assertThat(expansion.getVersion()).isEqualTo("2.1");
        assertThat(expansion.persist()).isTrue();
        assertThat(expansion).isNotInstanceOf(Listener.class);
    }

    @Test
    void aGlobalPlaceholderResolvesWithOrWithoutAPlayer() {
        PapiExpansion expansion = expansion();

        assertThat(expansion.onRequest(null, "online")).isEqualTo("5");
        assertThat(expansion.onRequest(away, "online")).isEqualTo("5");
    }

    @Test
    void aGlobalPlaceholderWinsOverAnOfflineOneAndAnOfflineOneOverAPlayerOne() {
        PapiExpansion expansion = expansion();
        PapiExpansion withoutGlobal = new PapiExpansion("shop", "Ana", "2.1",
            Map.of("shared", player -> "player shared"), Map.of("shared", player -> "offline shared"), Map.of());

        assertThat(expansion.onRequest(joined, "shared")).isEqualTo("global shared");
        assertThat(withoutGlobal.onRequest(joined, "shared")).isEqualTo("offline shared");
    }

    @Test
    void anOfflinePlaceholderResolvesForAPlayerWhoIsNotOnlineAndGivesNothingWithoutAPlayer() {
        PapiExpansion expansion = expansion();

        assertThat(expansion.onRequest(away, "last_seen")).isEqualTo("seen Sam");
        assertThat(expansion.onRequest(null, "last_seen")).isNull();
    }

    @Test
    void aPlayerPlaceholderResolvesOnlyForAnOnlinePlayer() {
        PapiExpansion expansion = expansion();

        assertThat(expansion.onRequest(joined, "name")).isEqualTo("player Alex");
        assertThat(expansion.onRequest(away, "name")).isNull();
        assertThat(expansion.onRequest(null, "name")).isNull();
        assertThat(expansion.onRequest(joined, "nothing")).isNull();
    }

    @Test
    void anUnknownOrDifferentlyCasedNameGivesNothing() {
        PapiExpansion expansion = expansion();

        assertThat(expansion.onRequest(joined, "missing")).isNull();
        assertThat(expansion.onRequest(joined, "ONLINE")).isNull();
        assertThat(expansion.onRequest(joined, "")).isNull();
    }

    @Test
    void registerHandsTheExpansionToPlaceholderApiAndTheActionUnregistersIt() {
        CapturedLog log = new CapturedLog();
        Map<String, Supplier<@Nullable String>> globals = Map.of("online", () -> "5");
        try (MockedConstruction<PapiExpansion> constructed = mockConstruction(PapiExpansion.class,
            (mock, context) -> {
                when(mock.register()).thenReturn(true);
                when(mock.holdsIdentifier()).thenReturn(true);
            })) {

            Runnable unregister = PapiExpansion.register("shop", "Ana", "2.1", Map.of(), Map.of(), globals,
                log.logger);

            assertThat(constructed.constructed()).hasSize(1);
            PapiExpansion expansion = constructed.constructed().getFirst();
            verify(expansion).register();
            verify(expansion, never()).unregister();
            assertThat(unregister).isNotNull();

            unregister.run();

            verify(expansion).unregister();
        }
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aRefusedRegistrationIsWarnedAndGivesNoAction() {
        CapturedLog log = new CapturedLog();
        try (MockedConstruction<PapiExpansion> constructed = mockConstruction(PapiExpansion.class,
            (mock, context) -> when(mock.register()).thenReturn(false))) {

            Runnable unregister = PapiExpansion.register("shop", "Ana", "2.1", Map.of(), Map.of(), Map.of(),
                log.logger);

            assertThat(unregister).isNull();
            verify(constructed.constructed().getFirst(), never()).unregister();
        }
        assertThat(log.lines()).containsExactly("WARNING PlaceholderAPI refused the placeholders under the identifier"
            + " shop.");
    }

    @Test
    void theActionLeavesAnExpansionThatReplacedThisOneUnderTheIdentifier() {
        CapturedLog log = new CapturedLog();
        try (MockedConstruction<PapiExpansion> constructed = mockConstruction(PapiExpansion.class,
            (mock, context) -> {
                when(mock.register()).thenReturn(true);
                when(mock.holdsIdentifier()).thenReturn(false);
            })) {

            Runnable unregister = PapiExpansion.register("shop", "Ana", "2.1", Map.of(), Map.of(), Map.of(),
                log.logger);
            unregister.run();

            PapiExpansion expansion = constructed.constructed().getFirst();
            verify(expansion).holdsIdentifier();
            verify(expansion, never()).unregister();
        }
        assertThat(log.lines()).isEmpty();
    }
}
