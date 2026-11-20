package so.alaz.taffy.metrics;

import dev.faststats.bukkit.BukkitContext;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Taffy's own usage metrics, started from a plugin that shades Taffy: a second bStats instance under Taffy's
 * service id and a second, metrics-only FastStats context under Taffy's token, each reporting one chart,
 * {@code taffy_version}. Each runs only beside the plugin's own instance of the same provider, so Taffy adds no
 * provider, file or notice that the plugin's own metrics do not. A provider whose credential is unset starts nothing
 * and logs nothing.
 *
 * <p>One plugin per server reports to each provider. Before a provider starts, this class claims it for the owner
 * in a system property, {@link #BSTATS_HOLDER} or {@link #FASTSTATS_HOLDER}, whose value is the owner's name. A
 * provider another plugin holds is not started and logs nothing. A plugin that starts while the property carries
 * its own name starts the provider. The claim is released when the provider fails to start and at
 * {@link #shutdown()}; no other plugin takes the provider over until it next starts its own metrics.
 */
final class TaffyUsage {

    /** Taffy's bStats service id. */
    static final int BSTATS_SERVICE_ID = 35379;

    /** Taffy's FastStats project token. */
    static final String FASTSTATS_TOKEN = "4f4f68898b814744b648ad6def9208db";

    static final String VERSION_CHART = "taffy_version";

    /**
     * The system property that names the plugin whose copy of Taffy reports usage to bStats. The shadow plugin
     * rewrites a string constant that starts with a relocated package name, so the key starts with none and every
     * relocated copy reads the same property.
     */
    static final String BSTATS_HOLDER = "taffy.usage.bstats";

    /** The system property that names the plugin whose copy of Taffy reports usage to FastStats. */
    static final String FASTSTATS_HOLDER = "taffy.usage.faststats";

    final org.bstats.bukkit.@Nullable Metrics bStats;
    final @Nullable BukkitContext fastStats;
    private final String holder;
    private final AtomicBoolean shutDown = new AtomicBoolean();

    private TaffyUsage(String holder, org.bstats.bukkit.@Nullable Metrics bStats,
                       @Nullable BukkitContext fastStats) {
        this.holder = holder;
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
     * not blank, each only when no other plugin holds that provider. A provider that fails to start logs one
     * warning on the owner's logger and is skipped.
     */
    static TaffyUsage start(Plugin owner, int serviceId, String token) {
        List<MetricChart> charts = List.of(versionChart());
        Logger logger = owner.getLogger();
        String holder = serviceId > 0 || !token.isBlank() ? String.valueOf(owner.getName()) : "";
        org.bstats.bukkit.Metrics bStats = null;
        if (serviceId > 0 && claim(BSTATS_HOLDER, holder)) {
            bStats = MetricsBuilder.startBStats(owner, serviceId, charts, logger,
                "Taffy usage metrics failed to start on bStats");
            if (bStats == null) {
                release(BSTATS_HOLDER, holder);
            }
        }
        BukkitContext fastStats = null;
        if (!token.isBlank() && claim(FASTSTATS_HOLDER, holder)) {
            fastStats = MetricsBuilder.startFastStats(owner, token, charts, false, logger,
                "Taffy usage metrics failed to start on FastStats");
            if (fastStats == null) {
                release(FASTSTATS_HOLDER, holder);
            }
        }
        return new TaffyUsage(holder, bStats, fastStats);
    }

    /** The one chart Taffy reports: the Taffy version this copy was built as. */
    static MetricChart versionChart() {
        return MetricChart.string(VERSION_CHART, () -> TaffyVersion.VERSION);
    }

    /** Whether {@code key} names {@code holder} after this call: it was unset, or already carried that name. */
    private static boolean claim(String key, String holder) {
        Object current = System.getProperties().putIfAbsent(key, holder);
        return current == null || current.equals(holder);
    }

    /** Unsets {@code key} when it names {@code holder}. */
    private static void release(String key, String holder) {
        System.getProperties().remove(key, holder);
    }

    /**
     * Stops both providers and releases the claim on each one this instance started. FastStats is stopped with
     * submission turned off first, so it makes no final send. Idempotent, and never throws.
     */
    void shutdown() {
        if (!shutDown.compareAndSet(false, true)) {
            return;
        }
        BukkitContext context = fastStats;
        if (context != null) {
            // FastStats 0.32.2 skips every send while SimpleContext.submissionActive is false;
            // shutdown() otherwise makes a final send.
            quietly(() -> context.submissionActive = false);
            quietly(context::shutdown);
            quietly(() -> release(FASTSTATS_HOLDER, holder));
        }
        org.bstats.bukkit.Metrics running = bStats;
        if (running != null) {
            quietly(running::shutdown);
            quietly(() -> release(BSTATS_HOLDER, holder));
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
