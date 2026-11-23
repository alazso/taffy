package so.alaz.taffy.metrics;

import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import so.alaz.taffy.test.CapturedLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A plugin named {@code Owner} whose data folder is {@code <plugins>/Owner}, on a mocked server whose plugins folder
 * is {@code <plugins>}. The constructor writes {@code <plugins>/bStats/config.yml} with a server id and
 * {@code enabled: false}, so a bStats instance started over this fixture schedules no submission.
 * {@code isEnabled()} reads {@code true} on the thread that built the fixture, which FastStats requires of the
 * thread that readies a context, and {@code false} on every other thread, so the check bStats makes on its own
 * thread before any send stops it.
 *
 * <p>The server's async scheduler is a mock that runs nothing and returns {@link #task} for every repeating task,
 * so a FastStats context over this fixture sends only inside its {@code shutdown()}. FastStats reads
 * {@code <plugins>/faststats/config.properties}, which {@link #writeFastStatsConfig} writes; without it a context
 * sees a first run.
 */
final class MetricsFixture {

    /** A positive bStats service id that names no real service. */
    static final int BSTATS_SERVICE_ID = Integer.MAX_VALUE;

    static final String BSTATS_CONFIG = "enabled: false\nserverUuid: 00000000-0000-4000-8000-000000000000\n";

    static final String BSTATS_ENABLED_CONFIG = BSTATS_CONFIG.replace("enabled: false", "enabled: true");

    /** A well-formed FastStats project token that names no real project. */
    static final String FASTSTATS_TOKEN = "0123456789abcdef0123456789abcdef";

    static final String SERVER_ID = "00000000-0000-4000-8000-000000000001";

    final Path plugins;
    final Path bStatsConfig;
    final Path fastStatsConfig;
    final CapturedLog log = new CapturedLog();
    final Server server = mock(Server.class);
    final Plugin plugin = mock(Plugin.class);
    final PluginManager pluginManager = mock(PluginManager.class);
    final AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
    final ScheduledTask task = mock(ScheduledTask.class);

    MetricsFixture(Path plugins) throws IOException {
        this.plugins = plugins;
        bStatsConfig = plugins.resolve("bStats").resolve("config.yml");
        fastStatsConfig = plugins.resolve("faststats").resolve("config.properties");
        Files.createDirectories(bStatsConfig.getParent());
        Files.writeString(bStatsConfig, BSTATS_CONFIG);
        when(server.getPluginsFolder()).thenReturn(plugins.toFile());
        when(server.getName()).thenReturn("Paper");
        when(server.getVersion()).thenReturn("git-Paper-1 (MC: 26.3)");
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        when(asyncScheduler.runAtFixedRate(any(Plugin.class), any(), anyLong(), anyLong(), any(TimeUnit.class)))
            .thenReturn(task);
        PluginMeta meta = mock(PluginMeta.class);
        when(meta.getVersion()).thenReturn("1.0.0");
        when(plugin.getPluginMeta()).thenReturn(meta);
        when(plugin.getName()).thenReturn("Owner");
        Thread builtOn = Thread.currentThread();
        when(plugin.isEnabled()).thenAnswer(call -> Thread.currentThread() == builtOn);
        when(plugin.getLogger()).thenReturn(log.logger);
        when(plugin.getDataFolder()).thenReturn(plugins.resolve("Owner").toFile());
        when(plugin.getServer()).thenReturn(server);
    }

    /** Writes the bStats config with {@code enabled: true}, so a bStats instance over it schedules a submission. */
    void enableBStats() throws IOException {
        Files.writeString(bStatsConfig, BSTATS_ENABLED_CONFIG);
    }

    /** The bStats config as it is on disk now. */
    String bStatsConfigText() throws IOException {
        return Files.readString(bStatsConfig);
    }

    /** Writes a complete, current FastStats config, so a context over this fixture is not on its first run. */
    void writeFastStatsConfig(boolean enabled, boolean submitMetrics, boolean submitErrors) throws IOException {
        writeFastStatsConfig(enabled, submitMetrics, submitErrors, true);
    }

    /** As {@link #writeFastStatsConfig(boolean, boolean, boolean)}, with {@code submitAdditionalMetrics} as given. */
    void writeFastStatsConfig(boolean enabled, boolean submitMetrics, boolean submitErrors, boolean additionalMetrics)
        throws IOException {
        Files.createDirectories(fastStatsConfig.getParent());
        Files.writeString(fastStatsConfig, "configVersion=3\n"
            + "serverId=" + SERVER_ID + "\n"
            + "enabled=" + enabled + "\n"
            + "submitMetrics=" + submitMetrics + "\n"
            + "submitErrors=" + submitErrors + "\n"
            + "submitAdditionalMetrics=" + additionalMetrics + "\n"
            + "debug=false\n");
    }
}
