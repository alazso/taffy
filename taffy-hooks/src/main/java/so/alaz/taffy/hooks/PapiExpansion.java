package so.alaz.taffy.hooks;

import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.manager.LocalExpansionManager;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

// Not a Listener: PlaceholderAPI registers a listener expansion's events itself, which Paper refuses while a plugin
// enables. register links the methods unregistering calls, except PlaceholderAPIPlugin.getLocalExpansionManager:
// linking verifies the PlaceholderAPIPlugin class, which needs classes from PlaceholderAPI's own dependencies that its
// API jar does not hold.
final class PapiExpansion extends PlaceholderExpansion {

    private final String identifier;
    private final String author;
    private final String version;
    private final Map<String, Function<Player, @Nullable String>> players;
    private final Map<String, Function<OfflinePlayer, @Nullable String>> offline;
    private final Map<String, Supplier<@Nullable String>> globals;
    private final PlaceholderPrefixes prefixes;
    private final List<String> prefixNames;

    PapiExpansion(String identifier, String author, String version,
                  Map<String, Function<Player, @Nullable String>> players,
                  Map<String, Function<OfflinePlayer, @Nullable String>> offline,
                  Map<String, Supplier<@Nullable String>> globals, PlaceholderPrefixes prefixes) {
        this.identifier = identifier;
        this.author = author;
        this.version = version;
        this.players = players;
        this.offline = offline;
        this.globals = globals;
        this.prefixes = prefixes;
        this.prefixNames = prefixes.longestFirst();
    }

    static @Nullable Runnable register(String identifier, String author, String version,
                                       Map<String, Function<Player, @Nullable String>> players,
                                       Map<String, Function<OfflinePlayer, @Nullable String>> offline,
                                       Map<String, Supplier<@Nullable String>> globals,
                                       PlaceholderPrefixes prefixes, Logger logger) {
        ApiLink.instanceMethod(PlaceholderExpansion.class, "getPlaceholderAPI", PlaceholderAPIPlugin.class);
        ApiLink.instanceMethod(LocalExpansionManager.class, "getExpansion", PlaceholderExpansion.class, String.class);
        PapiExpansion expansion = new PapiExpansion(identifier, author, version, players, offline, globals,
            prefixes);
        if (!expansion.register()) {
            logger.warning("PlaceholderAPI refused the placeholders under the identifier " + identifier + ".");
            return null;
        }
        return () -> {
            if (expansion.holdsIdentifier()) {
                expansion.unregister();
            }
        };
    }

    // PlaceholderAPI unregisters whichever expansion holds the identifier, and isRegistered() compares identifier,
    // author and version, so only this exact instance is checked for.
    boolean holdsIdentifier() {
        return getPlaceholderAPI().getLocalExpansionManager().getExpansion(identifier) == this;
    }

    @Override
    public String getIdentifier() {
        return identifier;
    }

    @Override
    public String getAuthor() {
        return author;
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, String params) {
        Supplier<@Nullable String> global = globals.get(params);
        if (global != null) {
            return global.get();
        }
        Function<OfflinePlayer, @Nullable String> anyPlayer = offline.get(params);
        if (anyPlayer != null) {
            return player == null ? null : anyPlayer.apply(player);
        }
        Function<Player, @Nullable String> handler = players.get(params);
        if (handler != null) {
            Player online = player == null ? null : player.getPlayer();
            return online == null ? null : handler.apply(online);
        }
        for (String prefix : prefixNames) {
            int end = prefix.length();
            if (params.length() > end && params.charAt(end) == '_' && params.startsWith(prefix)) {
                return prefixed(player, prefix, params.substring(end + 1));
            }
        }
        return null;
    }

    private @Nullable String prefixed(@Nullable OfflinePlayer player, String prefix, String rest) {
        Function<String, @Nullable String> global = prefixes.globals().get(prefix);
        if (global != null) {
            return global.apply(rest);
        }
        BiFunction<OfflinePlayer, String, @Nullable String> anyPlayer = prefixes.offline().get(prefix);
        if (anyPlayer != null) {
            return player == null ? null : anyPlayer.apply(player, rest);
        }
        BiFunction<Player, String, @Nullable String> handler = prefixes.players().get(prefix);
        Player online = player == null ? null : player.getPlayer();
        return handler == null || online == null ? null : handler.apply(online, rest);
    }
}
