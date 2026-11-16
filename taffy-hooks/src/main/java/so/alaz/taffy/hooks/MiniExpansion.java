package so.alaz.taffy.hooks;

import io.github.miniplaceholders.api.Expansion;
import io.github.miniplaceholders.api.MiniPlaceholders;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

final class MiniExpansion {

    private MiniExpansion() {
    }

    // MiniPlaceholders throws when an expansion that is not registered is unregistered, and its registered() and
    // unregister() match any expansion of the same name, so the action first looks for this exact instance.
    static Runnable register(String identifier, String author, String version,
                             Map<String, Function<Player, @Nullable String>> players,
                             Map<String, Supplier<@Nullable String>> globals) {
        ApiLink.staticMethod(MiniPlaceholders.class, "expansionsAvailable", Collection.class);
        Expansion.Builder builder = Expansion.builder(identifier).author(author).version(version);
        globals.forEach((key, supplier) -> builder.globalPlaceholder(key, (queue, context) -> textTag(supplier.get())));
        players.forEach((key, resolver) -> builder.audiencePlaceholder(Player.class, key,
            (player, queue, context) -> textTag(resolver.apply(player))));
        Expansion expansion = builder.build();
        expansion.register();
        return () -> {
            if (MiniPlaceholders.expansionsAvailable().stream().anyMatch(registered -> registered == expansion)) {
                expansion.unregister();
            }
        };
    }

    // Inserted as a text component, so markup in the value is shown as written.
    private static @Nullable Tag textTag(@Nullable String value) {
        return value == null ? null : Tag.inserting(Component.text(value));
    }
}
