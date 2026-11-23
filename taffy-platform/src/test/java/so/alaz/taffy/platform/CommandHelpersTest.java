package so.alaz.taffy.platform;

import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

final class CommandHelpersTest {

    private static final UUID ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

    private final Server server = mock(Server.class);

    @Test
    void permissionAsksTheSenderOfTheSourceForTheNode() {
        CommandSourceStack source = mock(CommandSourceStack.class);
        CommandSender sender = mock(CommandSender.class);
        Entity executor = mock(Entity.class);
        when(source.getSender()).thenReturn(sender);
        when(source.getExecutor()).thenReturn(executor);
        when(executor.hasPermission(anyString())).thenReturn(true);
        when(sender.hasPermission("shop.admin")).thenReturn(true);
        Predicate<CommandSourceStack> admin = CommandHelpers.permission("shop.admin");
        Predicate<CommandSourceStack> reload = CommandHelpers.permission("shop.reload");

        assertThat(admin.test(source)).isTrue();
        assertThat(reload.test(source)).isFalse();
        assertThatThrownBy(() -> CommandHelpers.permission(null))
            .isExactlyInstanceOf(NullPointerException.class).hasMessage("node");
    }

    @Test
    void matchingKeepsTheCandidatesThatStartWithTheTypedTextInTheirOrder() {
        List<String> ids = List.of("Gold", "diamond", "GOLDEN", "silver", "go", "Gold");

        assertThat(CommandHelpers.matching("go", ids)).containsExactly("Gold", "GOLDEN", "go", "Gold");
        assertThat(CommandHelpers.matching("GOLD", ids)).containsExactly("Gold", "GOLDEN", "Gold");
        assertThat(CommandHelpers.matching("", ids)).containsExactlyElementsOf(ids);
        assertThat(CommandHelpers.matching("old", ids)).isEmpty();
        assertThat(CommandHelpers.matching("golden apple", ids)).isEmpty();
        assertThatThrownBy(() -> CommandHelpers.matching("x", ids).add("y"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void matchingIgnoresCaseUnderTheRootLocale() {
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertThat(CommandHelpers.matching("I", List.of("item", "INFO"))).containsExactly("item", "INFO");
            assertThat(CommandHelpers.matching("i", List.of("ITEM"))).containsExactly("ITEM");
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void matchingRefusesNull() {
        assertThatThrownBy(() -> CommandHelpers.matching(null, List.of()))
            .isExactlyInstanceOf(NullPointerException.class).hasMessage("typed");
        assertThatThrownBy(() -> CommandHelpers.matching("", null))
            .isExactlyInstanceOf(NullPointerException.class).hasMessage("candidates");
        assertThatThrownBy(() -> CommandHelpers.matching("a", Arrays.asList("b", null)))
            .isExactlyInstanceOf(NullPointerException.class).hasMessage("candidate");
    }

    @Test
    void suggestOffersTheMatchesForTheRemainingInput() {
        SuggestionsBuilder builder = new SuggestionsBuilder("shop give Go", "shop give ".length());

        CompletableFuture<Suggestions> future =
            CommandHelpers.suggest(builder, List.of("Gold", "diamond", "golden"));

        assertThat(future).isCompleted();
        assertThat(future.join().getList()).extracting(Suggestion::getText).containsExactly("Gold", "golden");
        assertThat(future.join().getRange().getStart()).isEqualTo("shop give ".length());
        assertThatThrownBy(() -> CommandHelpers.suggest(null, List.of()))
            .isExactlyInstanceOf(NullPointerException.class).hasMessage("builder");
    }

    @Test
    void targetReadsAUuidWithoutLookingAtNames() {
        OfflinePlayer byId = mock(OfflinePlayer.class);
        when(server.getOfflinePlayer(ID)).thenReturn(byId);

        assertThat(CommandHelpers.target(server, ID.toString())).isSameAs(byId);
        assertThat(CommandHelpers.target(server, ID.toString().toUpperCase(Locale.ROOT))).isSameAs(byId);
        verify(server, never()).getPlayerExact(anyString());
        verify(server, never()).getOfflinePlayerIfCached(anyString());
    }

    @Test
    void targetTakesOnlyTheFullFormOfAUuid() {
        assertThat(CommandHelpers.target(server, "1-1-1-1-1")).isNull();

        verify(server, never()).getOfflinePlayer(any(UUID.class));
        verify(server).getPlayerExact("1-1-1-1-1");
    }

    @Test
    void targetPrefersAnOnlinePlayerOverTheCache() {
        Player online = mock(Player.class);
        when(server.getPlayerExact("Steve")).thenReturn(online);

        assertThat(CommandHelpers.target(server, "Steve")).isSameAs(online);
        verify(server, never()).getOfflinePlayerIfCached(anyString());
    }

    @Test
    void targetFallsBackToTheProfileCacheAndNeverToANameLookup() {
        OfflinePlayer cached = mock(OfflinePlayer.class);
        when(server.getOfflinePlayerIfCached("Alex")).thenReturn(cached);

        assertThat(CommandHelpers.target(server, "Alex")).isSameAs(cached);
        assertThat(CommandHelpers.target(server, "Nobody")).isNull();
        verify(server, never()).getOfflinePlayer(anyString());
    }

    @Test
    void targetGivesNoPlayerForABlankText() {
        assertThat(CommandHelpers.target(server, "")).isNull();
        assertThat(CommandHelpers.target(server, "  ")).isNull();

        verifyNoInteractions(server);
        assertThatThrownBy(() -> CommandHelpers.target(null, "Steve"))
            .isExactlyInstanceOf(NullPointerException.class).hasMessage("server");
        assertThatThrownBy(() -> CommandHelpers.target(server, null))
            .isExactlyInstanceOf(NullPointerException.class).hasMessage("input");
    }
}
