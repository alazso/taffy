package so.alaz.taffy.hooks;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The hologram plugins Taffy places text holograms through. A later version may add constants, so a {@code switch}
 * over this enum keeps a {@code default} branch.
 */
public enum HologramPlugin {
    /** DecentHolograms 2.10 or newer, key {@code decentholograms}. */
    DECENTHOLOGRAMS("DecentHolograms", "decentholograms", "eu.decentsoftware.holograms.api.DHAPI", true),
    /** FancyHolograms 2, key {@code fancyholograms}. FancyHolograms 3 has another API and reads as missing it. */
    FANCYHOLOGRAMS("FancyHolograms", "fancyholograms", "de.oliver.fancyholograms.api.FancyHologramsPlugin", true),
    /** Holograms by TheNextLvl, key {@code holograms}. */
    HOLOGRAMS("Holograms", "holograms", "net.thenextlvl.hologram.HologramProvider", true),
    /**
     * HologramLib, key {@code hologramlib}. It needs PacketEvents installed, and fills a placeholder when a player
     * starts seeing the hologram and when its lines are replaced. HologramLib 1.8.5 does not enable on Paper 26.2
     * and 26.3: its {@code onEnable} throws {@link NoSuchMethodError} for {@code UnsafeValues.nextEntityId()}, so
     * this hook is untested there and detection reads {@link HookState#NOT_ENABLED}.
     */
    HOLOGRAMLIB("HologramLib", "hologramlib", "com.maximde.hologramlib.HologramLib", true);

    private final String plugin;
    private final String key;
    private final String probe;
    private final boolean placeholdersPerViewer;

    HologramPlugin(String plugin, String key, String probe, boolean placeholdersPerViewer) {
        this.plugin = plugin;
        this.key = key;
        this.probe = probe;
        this.placeholdersPerViewer = placeholdersPerViewer;
    }

    /**
     * The name the plugin is installed under, as listed under {@code dependencies.server}.
     *
     * @return the plugin name, such as {@code DecentHolograms}
     */
    public String plugin() {
        return plugin;
    }

    /**
     * The lower case key that names the plugin in a configuration file.
     *
     * @return the key, such as {@code decentholograms}
     */
    public String key() {
        return key;
    }

    /**
     * Whether the plugin fills in PlaceholderAPI placeholders such as {@code %player_name%} for each viewer when
     * PlaceholderAPI is installed. Where it does not, a placeholder in a line shows as written.
     *
     * @return {@code true} for every plugin in this version
     */
    public boolean placeholdersPerViewer() {
        return placeholdersPerViewer;
    }

    // A class of the plugin's API that the integration needs.
    String probe() {
        return probe;
    }

    /**
     * Finds the plugin a key names, ignoring case and surrounding white space.
     *
     * @param hint a key such as {@code fancyholograms}
     * @return the plugin, or {@code null} if the hint is {@code null}, blank or names no plugin
     */
    public static @Nullable HologramPlugin byKey(@Nullable String hint) {
        if (hint == null) {
            return null;
        }
        String wanted = hint.trim().toLowerCase(Locale.ROOT);
        for (HologramPlugin plugin : values()) {
            if (plugin.key.equals(wanted)) {
                return plugin;
            }
        }
        return null;
    }
}
