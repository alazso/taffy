package so.alaz.taffy.hooks;

import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.SimulatedClasspath;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class PluginPresenceTest {

    private final PluginManager plugins = mock(PluginManager.class);
    private final PluginPresence presence = new PluginPresence(plugins);

    private Plugin installed(String name, String version, boolean enabled) {
        Plugin plugin = mock(Plugin.class);
        PluginMeta meta = mock(PluginMeta.class);
        when(meta.getVersion()).thenReturn(version);
        when(plugin.getPluginMeta()).thenReturn(meta);
        when(plugin.isEnabled()).thenReturn(enabled);
        when(plugins.getPlugin(name)).thenReturn(plugin);
        when(plugins.isPluginEnabled(name)).thenReturn(enabled);
        return plugin;
    }

    @Test
    void anInstalledPluginIsReadFromThePluginManager() {
        Plugin vault = installed("Vault", "1.7.3", true);
        installed("Nexo", "1.29.1", false);

        assertThat(presence.installed("Vault")).isTrue();
        assertThat(presence.enabled("Vault")).isTrue();
        assertThat(presence.plugin("Vault")).isSameAs(vault);
        assertThat(presence.version("Vault")).isEqualTo("1.7.3");
        assertThat(presence.installed("Nexo")).isTrue();
        assertThat(presence.enabled("Nexo")).isFalse();
        assertThat(presence.version("Nexo")).isEqualTo("1.29.1");
    }

    @Test
    void aMissingPluginIsNotInstalledNotEnabledAndOfUnknownVersion() {
        assertThat(presence.installed("Oraxen")).isFalse();
        assertThat(presence.enabled("Oraxen")).isFalse();
        assertThat(presence.plugin("Oraxen")).isNull();
        assertThat(presence.version("Oraxen")).isEqualTo("unknown");
    }

    @Test
    void aClassOnTheClasspathIsPresentAndAnUnknownOneIsNot() {
        assertThat(presence.classLookup("java.lang.String")).isEqualTo(ClassLookup.PRESENT);
        assertThat(presence.classLookup("org.junit.jupiter.api.Test")).isEqualTo(ClassLookup.PRESENT);
        assertThat(presence.classLookup("so.alaz.taffy.hooks.NoSuchApi")).isEqualTo(ClassLookup.ABSENT);
    }

    @Test
    void aClassIsLookedUpThroughTheLoaderThatDefinedThisClass() throws Exception {
        ClassLoader loader = SimulatedClasspath.hiding("so.alaz.taffy.hooks.", "org.junit.");
        Class<?> copy = Class.forName(PluginPresence.class.getName(), true, loader);
        Constructor<?> constructor = copy.getDeclaredConstructor(PluginManager.class);
        constructor.setAccessible(true);
        Object hidden = constructor.newInstance(plugins);
        Method classLookup = copy.getDeclaredMethod("classLookup", String.class);
        classLookup.setAccessible(true);
        Method present = classLookup.getReturnType().getDeclaredMethod("present");
        present.setAccessible(true);

        assertThat(present.invoke(classLookup.invoke(hidden, "org.junit.jupiter.api.Test"))).isEqualTo(false);
        assertThat(present.invoke(classLookup.invoke(hidden, "java.lang.String"))).isEqualTo(true);
        assertThat(presence.classLookup("org.junit.jupiter.api.Test")).isEqualTo(ClassLookup.PRESENT);
    }

    @Test
    void aClassIsLookedUpInAPluginThroughTheLoaderThatDefinedThePluginsClass() {
        ClassLoader loader = SimulatedClasspath.hiding("so.alaz.taffy.hooks.", "org.junit.");
        Plugin hidden = (Plugin) Proxy.newProxyInstance(loader, new Class<?>[] {Plugin.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException(method.getName());
            });
        Plugin visible = (Plugin) Proxy.newProxyInstance(PluginPresenceTest.class.getClassLoader(),
            new Class<?>[] {Plugin.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException(method.getName());
            });

        assertThat(presence.classLookupIn(hidden, "org.junit.jupiter.api.Test")).isEqualTo(ClassLookup.ABSENT);
        assertThat(presence.classLookupIn(hidden, "java.lang.String")).isEqualTo(ClassLookup.PRESENT);
        assertThat(presence.classLookupIn(visible, "org.junit.jupiter.api.Test")).isEqualTo(ClassLookup.PRESENT);
        assertThat(presence.classLookupIn(visible, "so.alaz.taffy.hooks.NoSuchApi")).isEqualTo(ClassLookup.ABSENT);
    }

    @Test
    void aLookupInAPluginWhoseLoaderThrowsGivesWhatItThrew() {
        IllegalStateException closed = new IllegalStateException(
            "The plugin classloader for HologramLib has thrown a zip file error.");
        Plugin broken = (Plugin) Proxy.newProxyInstance(ClosedLoader.of(closed), new Class<?>[] {Plugin.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException(method.getName());
            });

        ClassLookup lookup = presence.classLookupIn(broken, "eu.decentsoftware.holograms.api.DHAPI");

        assertThat(lookup.present()).isFalse();
        assertThat(lookup.failure()).isSameAs(closed);
        assertThat(presence.classLookupIn(broken, "java.lang.String")).isEqualTo(ClassLookup.PRESENT);
    }
}
