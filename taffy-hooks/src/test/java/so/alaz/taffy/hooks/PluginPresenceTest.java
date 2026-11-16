package so.alaz.taffy.hooks;

import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.CapturedLog;
import so.alaz.taffy.test.SimulatedClasspath;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class PluginPresenceTest {

    private final CapturedLog log = new CapturedLog();
    private final PluginManager plugins = mock(PluginManager.class);
    private final PluginPresence presence = new PluginPresence(plugins, log.logger);

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
        assertThat(presence.classPresent("java.lang.String")).isTrue();
        assertThat(presence.classPresent("org.junit.jupiter.api.Test")).isTrue();
        assertThat(presence.classPresent("so.alaz.taffy.hooks.NoSuchApi")).isFalse();
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aClassIsLookedUpThroughTheLoaderThatDefinedThisClass() throws Exception {
        ClassLoader loader = SimulatedClasspath.hiding("so.alaz.taffy.hooks.", "org.junit.");
        Class<?> copy = Class.forName(PluginPresence.class.getName(), true, loader);
        Constructor<?> constructor = copy.getDeclaredConstructor(PluginManager.class, Logger.class);
        constructor.setAccessible(true);
        Object hidden = constructor.newInstance(plugins, log.logger);
        Method classPresent = copy.getDeclaredMethod("classPresent", String.class);
        classPresent.setAccessible(true);

        assertThat(classPresent.invoke(hidden, "org.junit.jupiter.api.Test")).isEqualTo(false);
        assertThat(classPresent.invoke(hidden, "java.lang.String")).isEqualTo(true);
        assertThat(presence.classPresent("org.junit.jupiter.api.Test")).isTrue();
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

        assertThat(presence.classPresentIn(hidden, "org.junit.jupiter.api.Test")).isFalse();
        assertThat(presence.classPresentIn(hidden, "java.lang.String")).isTrue();
        assertThat(presence.classPresentIn(visible, "org.junit.jupiter.api.Test")).isTrue();
        assertThat(presence.classPresentIn(visible, "so.alaz.taffy.hooks.NoSuchApi")).isFalse();
        assertThat(log.lines()).isEmpty();
    }
}
