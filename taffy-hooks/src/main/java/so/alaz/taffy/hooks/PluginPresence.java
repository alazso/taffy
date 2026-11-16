package so.alaz.taffy.hooks;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.Classes;

import java.util.logging.Logger;

// classPresent looks through the loader of this class, which is the loader of the plugin that shades the module.
final class PluginPresence implements Presence {

    private final PluginManager plugins;
    private final Logger logger;

    PluginPresence(PluginManager plugins, Logger logger) {
        this.plugins = plugins;
        this.logger = logger;
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
    public boolean classPresent(String name) {
        return Classes.present(name, PluginPresence.class, logger);
    }

    @Override
    public boolean classPresentIn(Plugin plugin, String name) {
        return Classes.present(name, plugin.getClass(), logger);
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
