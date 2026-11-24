package so.alaz.taffy.hooks;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The NPC plugins Taffy reads NPCs through. A later version may add constants, so a {@code switch} over this enum
 * keeps a {@code default} branch. Each constant states what an NPC id is for its plugin, which is the text
 * {@link NpcProvider#location(String)} takes and a click listener receives.
 *
 * @since 0.4.12
 */
public enum NpcPlugin {
    /**
     * Citizens 2, key {@code citizens}. An NPC id is the number Citizens gives the NPC and shows in
     * {@code /npc list}, written in decimal with no sign and no leading zero, as in {@code 12}. Only the NPCs of
     * Citizens' default registry are read, which are the ones an owner makes with {@code /npc create}: an NPC another
     * plugin keeps in a registry of its own has a number of that registry and is neither found nor passed on.
     */
    CITIZENS("Citizens", "citizens", "net.citizensnpcs.api.CitizensAPI"),
    /**
     * FancyNpcs 2, key {@code fancynpcs}. An NPC id is the name the owner gave the NPC in {@code /npc create}, as in
     * {@code shopkeeper}. FancyNpcs finds a name in any case, and a click passes the name as FancyNpcs holds it.
     * While FancyNpcs' {@code player-npcs} feature is on, two players may each hold an NPC of one name: the lookup
     * then gives the first FancyNpcs finds, and a click on either passes the same name.
     */
    FANCYNPCS("FancyNpcs", "fancynpcs", "de.oliver.fancynpcs.api.FancyNpcsPlugin"),
    /**
     * ZNPCsPlus 2, key {@code znpcsplus}. An NPC id is the id the owner gave the NPC in {@code /npc create}, as in
     * {@code shopkeeper}. ZNPCsPlus keeps an id in lower case and finds it in any case, and a click passes it in
     * lower case.
     */
    ZNPCSPLUS("ZNPCsPlus", "znpcsplus", "lol.pyr.znpcsplus.api.NpcApiProvider");

    private final String plugin;
    private final String key;
    private final String probe;

    NpcPlugin(String plugin, String key, String probe) {
        this.plugin = plugin;
        this.key = key;
        this.probe = probe;
    }

    /**
     * The name the plugin is installed under, as listed under {@code dependencies.server}.
     *
     * @return the plugin name, such as {@code Citizens}
     */
    public String plugin() {
        return plugin;
    }

    /**
     * The lower case key that names the plugin in a configuration file.
     *
     * @return the key, such as {@code citizens}
     */
    public String key() {
        return key;
    }

    // A class of the plugin's API that the integration needs.
    String probe() {
        return probe;
    }

    /**
     * Finds the plugin a key names, ignoring case and surrounding white space.
     *
     * @param hint a key such as {@code fancynpcs}
     * @return the plugin, or {@code null} if the hint is {@code null}, blank or names no plugin
     */
    public static @Nullable NpcPlugin byKey(@Nullable String hint) {
        if (hint == null) {
            return null;
        }
        String wanted = hint.trim().toLowerCase(Locale.ROOT);
        for (NpcPlugin plugin : values()) {
            if (plugin.key.equals(wanted)) {
                return plugin;
            }
        }
        return null;
    }
}
