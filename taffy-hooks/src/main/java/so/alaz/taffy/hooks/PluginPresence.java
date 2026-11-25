package so.alaz.taffy.hooks;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.Nullable;

// classLookup looks through the loader of this class, which is the loader of the plugin that shades the module.
final class PluginPresence implements Presence {

    private final PluginManager plugins;

    PluginPresence(PluginManager plugins) {
        this.plugins = plugins;
    }

    @Override
    public boolean installed(String plugin) {
        return plugins.getPlugin(plugin) != null;
    }

    @Override
    public boolean enabled(String plugin) {
        return plugins.isPluginEnabled(plugin);
    }

    @Override
    public ClassLookup classLookup(String name) {
        return ClassLookup.of(name, PluginPresence.class.getClassLoader());
    }

    @Override
    public ClassLookup classLookupIn(Plugin plugin, String name) {
        return ClassLookup.of(name, plugin.getClass().getClassLoader());
    }

    @Override
    public String version(String plugin) {
        Plugin found = plugins.getPlugin(plugin);
        return found == null ? "unknown" : found.getPluginMeta().getVersion();
    }

    @Override
    public @Nullable Plugin plugin(String name) {
        return plugins.getPlugin(name);
    }
}
