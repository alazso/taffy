package so.alaz.taffy.hooks;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

interface Presence {

    boolean installed(String plugin);

    boolean enabled(String plugin);

    // Through the loader of the plugin that shades the module, without initializing the class. Nothing is logged.
    ClassLookup classLookup(String name);

    // Through the loader of the given plugin, without initializing the class. Nothing is logged.
    ClassLookup classLookupIn(Plugin plugin, String name);

    String version(String plugin);

    @Nullable Plugin plugin(String name);
}
