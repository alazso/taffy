package so.alaz.taffy.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/** Finds the settings that changed in a reloaded configuration but take effect only after a restart. */
public final class RestartKeys {

    private RestartKeys() {
    }

    /**
     * The restart-only paths whose value in {@code after} differs from the one in {@code before}, sorted.
     *
     * <p>{@code restartOnly} is asked about every path of both configurations that holds a value. A path is
     * dotted and relative to the section passed, such as {@code storage.type} when the root is passed. It is
     * never asked about a path that holds a section, so a section present in both is not reported: the keys
     * beneath it are. A list is one value. Values compare by content: a list entry by entry in order, a group of
     * keys inside a list key by key, and a {@code !!binary} value byte by byte. Any other value compares with
     * {@link Object#equals}, so a number written in another form counts as a change: {@code 3306} and
     * {@code 3306.0} differ. A value that only one of the two has is reported; an empty section is not.
     *
     * <p>Values are read with {@link ConfigurationSection#get(String)}, which answers a missing key with the
     * default of a configuration that has defaults attached. A key removed from such a configuration is not
     * reported while its default equals the earlier value. The configurations {@link StrictYaml} and
     * {@link ConfigLoader} return have no defaults.
     *
     * <p>{@code before} is the configuration loaded at startup, not the one from the previous reload. A key
     * set back to its startup value is then no longer reported, and a second reload of an unchanged file
     * still reports what the first one did.
     */
    public static List<String> changed(
        ConfigurationSection before, ConfigurationSection after, Predicate<String> restartOnly) {
        Set<String> paths = new TreeSet<>();
        collect(before, restartOnly, paths);
        collect(after, restartOnly, paths);
        return paths.stream()
            .filter(path -> !Values.same(before.get(path), after.get(path)))
            .toList();
    }

    private static void collect(ConfigurationSection config, Predicate<String> restartOnly, Set<String> into) {
        for (String path : config.getKeys(true)) {
            if (!config.isConfigurationSection(path) && restartOnly.test(path)) {
                into.add(path);
            }
        }
    }
}
