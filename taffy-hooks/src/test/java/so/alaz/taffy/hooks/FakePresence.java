package so.alaz.taffy.hooks;

import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every plugin installed, enabled, of version {@code 1.0} and serving its API unless a test removes it. A class in
 * {@link #absentClasses} is in neither the owner's nor the plugin's loader; one in {@link #invisibleClasses} is in the
 * plugin's loader only. A lookup of a class in {@link #failingClasses} through the owner's loader, or in
 * {@link #failingInPlugin} through the plugin's loader, throws what the map holds, as a lookup does that reaches the
 * closed loader of a plugin that failed to enable. Each bound plugin is a mock made on first request and kept in
 * {@link #plugins}, enabled unless its name was in {@link #disabled} then.
 */
final class FakePresence implements Presence {
    final Set<String> missing = new HashSet<>();
    final Set<String> disabled = new HashSet<>();
    final Set<String> absentClasses = new HashSet<>();
    final Set<String> invisibleClasses = new HashSet<>();
    final Set<String> unloaded = new HashSet<>();
    final Map<String, Throwable> failingClasses = new HashMap<>();
    final Map<String, Throwable> failingInPlugin = new HashMap<>();
    final List<String> probes = new ArrayList<>();
    final Map<String, Plugin> plugins = new HashMap<>();

    @Override
    public boolean installed(String plugin) {
        return !missing.contains(plugin);
    }

    @Override
    public boolean enabled(String plugin) {
        return !disabled.contains(plugin);
    }

    @Override
    public ClassLookup classLookup(String name) {
        probes.add(name);
        Throwable failure = failingClasses.get(name);
        if (failure != null) {
            return ClassLookup.failed(failure);
        }
        return absentClasses.contains(name) || invisibleClasses.contains(name) ? ClassLookup.ABSENT
            : ClassLookup.PRESENT;
    }

    @Override
    public ClassLookup classLookupIn(Plugin plugin, String name) {
        Throwable failure = failingInPlugin.get(name);
        if (failure != null) {
            return ClassLookup.failed(failure);
        }
        return invisibleClasses.contains(name) ? ClassLookup.PRESENT : ClassLookup.ABSENT;
    }

    @Override
    public String version(String plugin) {
        return "1.0";
    }

    @Override
    public @Nullable Plugin plugin(String name) {
        if (unloaded.contains(name)) {
            return null;
        }
        return plugins.computeIfAbsent(name, key -> {
            Plugin plugin = mock(Plugin.class);
            PluginMeta meta = mock(PluginMeta.class);
            when(meta.getVersion()).thenReturn("1.0");
            when(plugin.getPluginMeta()).thenReturn(meta);
            when(plugin.isEnabled()).thenReturn(!disabled.contains(key));
            return plugin;
        });
    }
}
