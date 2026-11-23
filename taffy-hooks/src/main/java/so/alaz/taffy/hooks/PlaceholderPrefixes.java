package so.alaz.taffy.hooks;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Function;

/** The prefix placeholders of one expansion, each kind keyed by its prefix. A handler takes the rest of the name. */
record PlaceholderPrefixes(Map<String, BiFunction<Player, String, @Nullable String>> players,
                           Map<String, BiFunction<OfflinePlayer, String, @Nullable String>> offline,
                           Map<String, Function<String, @Nullable String>> globals) {

    static final PlaceholderPrefixes NONE = new PlaceholderPrefixes(Map.of(), Map.of(), Map.of());

    /** Every prefix of any kind once, the longest first. */
    List<String> longestFirst() {
        TreeSet<String> prefixes = new TreeSet<>(
            Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));
        prefixes.addAll(players.keySet());
        prefixes.addAll(offline.keySet());
        prefixes.addAll(globals.keySet());
        return List.copyOf(prefixes);
    }
}
