package so.alaz.taffy.platform;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The pieces a plugin's commands repeat: the permission check of a Brigadier node, the candidates that start
 * with what was typed, and the player a typed name or UUID stands for.
 *
 * <p>{@link #permission} and {@link #suggest} serve a command registered through Paper's Brigadier command API.
 * {@link #matching} takes and returns plain text, so it also serves the tab completion of a Bukkit command, and
 * {@link #target} serves either.
 *
 * <p>No method here reads the typed text as MiniMessage or as a legacy colour code, and none sends a message.
 * The class holds no state.
 */
public final class CommandHelpers {

    private static final Pattern UUID_TEXT = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private CommandHelpers() {
    }

    /**
     * The check for {@code requires} of a Brigadier node: whether the sender of the source holds the permission
     * node. The sender is {@link CommandSourceStack#getSender()}, so for a command run through {@code /execute as}
     * it is the one who ran the command, not the entity it runs as.
     *
     * @throws NullPointerException if the node is null
     */
    public static Predicate<CommandSourceStack> permission(String node) {
        Objects.requireNonNull(node, "node");
        return source -> source.getSender().hasPermission(node);
    }

    /**
     * The candidates that start with the typed text, in the order the collection gives them, each as it was
     * given. Letter case is ignored, with both sides lower-cased under {@link Locale#ROOT}. An empty typed text
     * matches every candidate. A candidate that is given twice is returned twice.
     *
     * <p>The candidates are returned to whoever asked, so the caller passes only names that sender may see: this
     * method cannot tell a hidden player's name from any other text.
     *
     * @return a list that cannot be changed
     * @throws NullPointerException if the typed text, the collection or a candidate is null
     */
    public static List<String> matching(String typed, Collection<String> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        String prefix = Objects.requireNonNull(typed, "typed").toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String candidate : candidates) {
            if (Objects.requireNonNull(candidate, "candidate").toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(candidate);
            }
        }
        return List.copyOf(matches);
    }

    /**
     * Suggests the candidates that {@link #matching} returns for {@link SuggestionsBuilder#getRemaining()}, in
     * that order, and builds the answer. It is the body of a Brigadier {@code suggests} callback:
     * {@code (context, builder) -> CommandHelpers.suggest(builder, ids)}.
     *
     * @return the completed future of {@link SuggestionsBuilder#buildFuture()}
     * @throws NullPointerException if the builder, the collection or a candidate is null
     */
    public static CompletableFuture<Suggestions> suggest(SuggestionsBuilder builder, Collection<String> candidates) {
        Objects.requireNonNull(builder, "builder");
        for (String candidate : matching(builder.getRemaining(), candidates)) {
            builder.suggest(candidate);
        }
        return builder.buildFuture();
    }

    /**
     * The player a typed text stands for, without a lookup at Mojang, so the call does not wait on the network.
     * The first of these that applies gives the answer:
     * <ol>
     *   <li>a UUID in its 36 character form with dashes, in either letter case: the player of that UUID from
     *       {@link Server#getOfflinePlayer(UUID)}. The server returns a player for every UUID, also one that has
     *       never joined, whose {@link OfflinePlayer#getName()} is null;</li>
     *   <li>the exact name of an online player, ignoring letter case, from {@link Server#getPlayerExact}: that
     *       {@link Player};</li>
     *   <li>a name the server holds in its profile cache, from {@link Server#getOfflinePlayerIfCached}.</li>
     * </ol>
     * A blank text and a name that is neither online nor cached give null, so a mistyped name stands for no
     * player.
     *
     * <p>An online player is found whether or not the sender can see them: a caller that must not reveal a
     * hidden player checks {@link Player#canSee} on the result before it answers.
     *
     * @return the player, or null when the text stands for none
     * @throws NullPointerException if the server or the text is null
     */
    public static @Nullable OfflinePlayer target(Server server, String input) {
        Objects.requireNonNull(server, "server");
        if (Objects.requireNonNull(input, "input").isBlank()) {
            return null;
        }
        if (UUID_TEXT.matcher(input).matches()) {
            return server.getOfflinePlayer(UUID.fromString(input));
        }
        Player online = server.getPlayerExact(input);
        return online != null ? online : server.getOfflinePlayerIfCached(input);
    }
}
