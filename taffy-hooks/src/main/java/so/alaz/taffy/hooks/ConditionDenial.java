package so.alaz.taffy.hooks;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * The denial of one entry of a config list: the entry's own {@code deny} text when it has one, and otherwise the
 * message of a key. A {@link ConditionType} takes one from {@link ConditionConfig#denial(String)} while it builds
 * and keeps it in its {@link Condition}.
 *
 * <p>An instance is immutable. {@link #result} reads the player, so it is called on the thread that owns the player.
 *
 * @since 0.4.14
 */
public final class ConditionDenial {

    private final @Nullable String deny;
    private final String key;
    private final ConditionRegistry.Denials denials;

    ConditionDenial(@Nullable String deny, String key, ConditionRegistry.Denials denials) {
        this.deny = deny;
        this.key = key;
        this.denials = denials;
    }

    /**
     * A denied result with the reason rendered for the player. An entry with a {@code deny} text renders that text
     * as {@code Text.render(String, Player, TagResolver...)} does: as MiniMessage, with the PlaceholderAPI
     * placeholders filled in for the player and each value written as {@code <name>} or {@code {name}}. An entry
     * without one renders the message of the key as {@code Messages.render(Text, Player, String, TagResolver...)}
     * does, in the player's language, and a key no lang file holds shows as the marker {@code missing:<key>}.
     *
     * @param player the player who was denied
     * @param values the values the text may name
     * @return a result that did not pass
     * @throws NullPointerException if {@code player} or {@code values} is {@code null}
     * @since 0.4.14
     */
    public ConditionResult result(Player player, TagResolver... values) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(values, "values");
        Component reason = deny != null ? denials.custom(deny, player, values) : denials.keyed(key, player, values);
        return ConditionResult.denied(reason);
    }
}
