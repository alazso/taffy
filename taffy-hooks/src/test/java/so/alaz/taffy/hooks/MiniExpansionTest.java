package so.alaz.taffy.hooks;

import io.github.miniplaceholders.api.Expansion;
import io.github.miniplaceholders.api.MiniPlaceholders;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class MiniExpansionTest {

    private final Player player = mock(Player.class);

    MiniExpansionTest() {
        when(player.getName()).thenReturn("Alex");
    }

    @AfterEach
    void unregisterLeftovers() {
        Expansion left = MiniPlaceholders.expansionByName("shop");
        if (left != null) {
            left.unregister();
        }
    }

    private static Runnable register() {
        Map<String, Function<Player, @Nullable String>> players = Map.of(
            "name", viewer -> "<red>" + viewer.getName(),
            "nothing", viewer -> null);
        Map<String, Supplier<@Nullable String>> globals = Map.of("online", () -> "<bold>5");
        return MiniExpansion.register("shop", "Ana, Bo", "2.1", players, globals);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void registerAddsTheExpansionWithItsAuthorAndVersion() {
        int before = MiniPlaceholders.expansionCount();

        register();

        Expansion expansion = MiniPlaceholders.expansionByName("shop");
        assertThat(expansion).isNotNull();
        assertThat(expansion.registered()).isTrue();
        assertThat(expansion.author()).isEqualTo("Ana, Bo");
        assertThat(expansion.version()).isEqualTo("2.1");
        assertThat(MiniPlaceholders.expansionCount()).isEqualTo(before + 1);
        assertThat(MiniPlaceholders.audienceGlobalPlaceholders().has("shop_name")).isTrue();
        assertThat(MiniPlaceholders.audienceGlobalPlaceholders().has("shop_online")).isTrue();
    }

    @Test
    void aPlayerTagRendersTheValueAsPlainText() {
        register();

        Component rendered = MiniMessage.miniMessage().deserialize("Hi <shop_name>", player,
            MiniPlaceholders.audienceGlobalPlaceholders());

        assertThat(plain(rendered)).isEqualTo("Hi <red>Alex");
    }

    @Test
    void aGlobalTagRendersWithoutAnAudience() {
        register();

        Component rendered = MiniMessage.miniMessage().deserialize("<shop_online> online",
            MiniPlaceholders.globalPlaceholders());

        assertThat(plain(rendered)).isEqualTo("<bold>5 online");
    }

    @Test
    void aNullValueLeavesTheTagUnresolved() {
        register();

        Component rendered = MiniMessage.miniMessage().deserialize("<shop_nothing>", player,
            MiniPlaceholders.audienceGlobalPlaceholders());

        assertThat(plain(rendered)).isEqualTo("<shop_nothing>");
    }

    @Test
    void theActionLeavesAnotherExpansionOfTheSameName() {
        Runnable unregister = register();
        MiniPlaceholders.expansionByName("shop").unregister();
        Expansion other = Expansion.builder("shop").build();
        other.register();

        unregister.run();

        assertThat(other.registered()).isTrue();
        assertThat(MiniPlaceholders.expansionByName("shop")).isSameAs(other);
    }

    @Test
    void theActionUnregistersAndASecondRunDoesNotThrow() {
        Runnable unregister = register();

        unregister.run();

        assertThat(MiniPlaceholders.expansionByName("shop")).isNull();
        assertThat(MiniPlaceholders.audienceGlobalPlaceholders().has("shop_name")).isFalse();
        assertThatCode(unregister::run).doesNotThrowAnyException();
    }
}
