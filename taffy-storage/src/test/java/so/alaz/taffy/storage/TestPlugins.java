package so.alaz.taffy.storage;

import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ErrorReports;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mockito plugins that own storage in the tests: a name, the version {@link #VERSION}, a data path, a logger and the
 * migration scripts {@link Plugin#getResource} opens.
 */
final class TestPlugins {

    static final String VERSION = "9.9.9";

    private static final Path UNUSED = Path.of("unused");

    private TestPlugins() {
    }

    /** A plugin whose scripts come from {@code scripts}, which returns {@code null} for a path it does not hold. */
    static Plugin plugin(String name, Path dataPath, Logger logger,
                         Function<String, @Nullable InputStream> scripts) {
        Plugin plugin = mock(Plugin.class);
        PluginMeta meta = mock(PluginMeta.class);
        when(plugin.getName()).thenReturn(name);
        when(plugin.getPluginMeta()).thenReturn(meta);
        when(meta.getVersion()).thenReturn(VERSION);
        when(plugin.getDataPath()).thenReturn(dataPath);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getResource(anyString())).thenAnswer(call -> scripts.apply(call.getArgument(0)));
        return plugin;
    }

    /** A plugin with no migration scripts. */
    static Plugin plugin(String name, Path dataPath, Logger logger) {
        return plugin(name, dataPath, logger, path -> null);
    }

    /** A plugin with no data path in use and no migration scripts, for a server database or storage over a source. */
    static Plugin plugin(String name, Logger logger) {
        return plugin(name, UNUSED, logger);
    }

    /** Starts storage for {@code plugin}, taking over the old version table of {@code baseline} when it is given. */
    static CompletableFuture<Storage> start(Plugin plugin, StorageSettings settings, List<Migration> migrations,
                                            @Nullable Baseline baseline, Clock clock) {
        return baseline == null
            ? Storage.start(plugin, new ErrorReports(), settings, migrations, clock)
            : Storage.start(plugin, new ErrorReports(), settings, migrations, baseline, clock);
    }
}
