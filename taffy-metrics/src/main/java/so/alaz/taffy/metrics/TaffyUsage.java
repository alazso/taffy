package so.alaz.taffy.metrics;

import dev.faststats.bukkit.BukkitContext;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Taffy's own usage metrics, started from every plugin that shades Taffy: a second bStats instance under Taffy's
 * service id and a second, metrics-only FastStats context under Taffy's token, each reporting one chart,
 * {@code taffy_version}. Each runs only beside the plugin's own instance of the same provider, so Taffy adds no
 * provider, file or notice that the plugin's own metrics do not. A provider whose credential is unset starts nothing
 * and logs nothing.
 */
final class TaffyUsage {

    /** Taffy's bStats service id. */
    static final int BSTATS_SERVICE_ID = 35379;

    /** Taffy's FastStats project token. */
    static final String FASTSTATS_TOKEN = "4f4f68898b814744b648ad6def9208db";

    static final String VERSION_CHART = "taffy_version";

    final org.bstats.bukkit.@Nullable Metrics bStats;
    final @Nullable BukkitContext fastStats;
    private final AtomicBoolean shutDown = new AtomicBoolean();

    private TaffyUsage(org.bstats.bukkit.@Nullable Metrics bStats, @Nullable BukkitContext fastStats) {
        this.bStats = bStats;
        this.fastStats = fastStats;
    }

    /**
     * Starts Taffy's usage metrics on {@code owner} under Taffy's own credentials: bStats when {@code bStats} is
     * true and FastStats when {@code fastStats} is true, each true when the plugin's own instance of that provider
     * started.
     */
    static TaffyUsage start(Plugin owner, boolean bStats, boolean fastStats) {
        return start(owner, bStats ? BSTATS_SERVICE_ID : 0, fastStats ? FASTSTATS_TOKEN : "");
    }

    /**
     * Starts bStats when {@code serviceId} is above 0 and FastStats, with no error tracking, when {@code token} is
     * not blank. A provider that fails to start logs one warning on the owner's logger and is skipped.
     */
    static TaffyUsage start(Plugin owner, int serviceId, String token) {
        List<MetricChart> charts = List.of(versionChart());
        Logger logger = owner.getLogger();
        org.bstats.bukkit.Metrics bStats = serviceId > 0
            ? MetricsBuilder.startBStats(owner, serviceId, charts, logger,
                "Taffy usage metrics failed to start on bStats")
            : null;
        BukkitContext fastStats = token.isBlank() ? null
            : MetricsBuilder.startFastStats(owner, token, charts, false, logger,
                "Taffy usage metrics failed to start on FastStats");
        return new TaffyUsage(bStats, fastStats);
    }

    /** The one chart Taffy reports: the Taffy version this copy was built as. */
    static MetricChart versionChart() {
        return MetricChart.string(VERSION_CHART, () -> TaffyVersion.VERSION);
    }

    /**
     * Stops both providers. FastStats is stopped with submission turned off first, so it makes no final send.
     * Idempotent, and never throws.
     */
    void shutdown() {
        if (!shutDown.compareAndSet(false, true)) {
            return;
        }
        BukkitContext context = fastStats;
        if (context != null) {
            quietly(() -> context.submissionActive = false);
            quietly(context::shutdown);
        }
        org.bstats.bukkit.Metrics running = bStats;
        if (running != null) {
            quietly(running::shutdown);
        }
    }

    private static void quietly(Runnable step) {
        try {
            step.run();
        } catch (RuntimeException | LinkageError ignored) {
            // Shutting down metrics must never throw out of disable.
        }
    }
}
