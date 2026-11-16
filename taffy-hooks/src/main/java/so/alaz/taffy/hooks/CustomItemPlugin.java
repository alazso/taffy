package so.alaz.taffy.hooks;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/** The custom item plugins Taffy reads and builds items with, in resolution order. */
public enum CustomItemPlugin {
    /** ItemsAdder, key {@code itemsadder}, also written {@code ia}. */
    ITEMSADDER("ItemsAdder", "itemsadder", "dev.lone.itemsadder.api.CustomStack", "ia"),
    /** Nexo, key {@code nexo}. */
    NEXO("Nexo", "nexo", "com.nexomc.nexo.api.NexoItems"),
    /** Oraxen, key {@code oraxen}. */
    ORAXEN("Oraxen", "oraxen", "io.th0rgal.oraxen.api.OraxenItems"),
    /** HeadDatabase, key {@code headdatabase}, also written {@code hdb}. */
    HEADDATABASE("HeadDatabase", "headdatabase", "me.arcaniax.hdb.api.HeadDatabaseAPI", "hdb");

    private final String plugin;
    private final String key;
    private final String probe;
    private final List<String> aliases;

    CustomItemPlugin(String plugin, String key, String probe, String... aliases) {
        this.plugin = plugin;
        this.key = key;
        this.probe = probe;
        this.aliases = List.of(aliases);
    }

    /**
     * The name the plugin is installed under.
     *
     * @return the plugin name, such as {@code ItemsAdder}
     */
    public String plugin() {
        return plugin;
    }

    /**
     * The lower case key that starts the ids of this plugin's items, as in {@code nexo:ruby}.
     *
     * @return the key, such as {@code nexo}
     */
    public String key() {
        return key;
    }

    // A class of the plugin's API that the integration needs.
    String probe() {
        return probe;
    }

    /**
     * Finds the plugin a hint names by its key or an alias, ignoring case and surrounding white space.
     *
     * @param hint a key such as {@code itemsadder}, or an alias such as {@code ia} or {@code hdb}
     * @return the plugin, or {@code null} if the hint is {@code null}, blank or names no plugin
     */
    public static @Nullable CustomItemPlugin byKey(@Nullable String hint) {
        if (hint == null) {
            return null;
        }
        String wanted = hint.trim().toLowerCase(Locale.ROOT);
        for (CustomItemPlugin plugin : values()) {
            if (plugin.key.equals(wanted) || plugin.aliases.contains(wanted)) {
                return plugin;
            }
        }
        return null;
    }
}
