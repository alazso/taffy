package so.alaz.taffy.hooks;

import io.github.miniplaceholders.api.Expansion;
import io.github.miniplaceholders.api.MiniPlaceholders;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

final class MiniExpansion {

    private MiniExpansion() {
    }

    // MiniPlaceholders throws when an expansion that is not registered is unregistered, and its registered() and
    // unregister() match any expansion of the same name, so the action first looks for this exact instance.
    // A name and a prefix that are equal share one tag, since MiniPlaceholders keeps one resolver for each tag.
    static Runnable register(String identifier, String author, String version,
                             Map<String, Function<Player, @Nullable String>> players,
                             Map<String, Supplier<@Nullable String>> globals, PlaceholderPrefixes prefixes) {
        ApiLink.staticMethod(MiniPlaceholders.class, "expansionsAvailable", Collection.class);
        Expansion.Builder builder = Expansion.builder(identifier).author(author).version(version);
        for (String key : union(globals.keySet(), prefixes.globals().keySet())) {
            Supplier<@Nullable String> exact = globals.get(key);
            Function<String, @Nullable String> prefix = prefixes.globals().get(key);
            builder.globalPlaceholder(key, (queue, context) -> {
                String rest = prefix == null ? null : rest(queue);
                if (rest != null) {
                    return textTag(prefix.apply(rest));
                }
                return exact == null ? null : textTag(exact.get());
            });
        }
        for (String key : union(players.keySet(), prefixes.players().keySet())) {
            Function<Player, @Nullable String> exact = players.get(key);
            BiFunction<Player, String, @Nullable String> prefix = prefixes.players().get(key);
            builder.audiencePlaceholder(Player.class, key, (player, queue, context) -> {
                String rest = prefix == null ? null : rest(queue);
                if (rest != null) {
                    return textTag(prefix.apply(player, rest));
                }
                return exact == null ? null : textTag(exact.apply(player));
            });
        }
        Expansion expansion = builder.build();
        expansion.register();
        return () -> {
            if (MiniPlaceholders.expansionsAvailable().stream().anyMatch(registered -> registered == expansion)) {
                expansion.unregister();
            }
        };
    }

    private static Set<String> union(Set<String> names, Set<String> prefixes) {
        Set<String> all = new LinkedHashSet<>(names);
        all.addAll(prefixes);
        return all;
    }

    /** The arguments of the tag joined with {@code :}, or {@code null} when it has none. */
    private static @Nullable String rest(ArgumentQueue queue) {
        if (!queue.hasNext()) {
            return null;
        }
        StringBuilder rest = new StringBuilder(queue.pop().value());
        while (queue.hasNext()) {
            rest.append(':').append(queue.pop().value());
        }
        return rest.toString();
    }

    // Inserted as a text component, so markup in the value is shown as written.
    private static @Nullable Tag textTag(@Nullable String value) {
        return value == null ? null : Tag.inserting(Component.text(value));
    }
}
