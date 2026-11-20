package so.alaz.taffy.metrics;

import dev.faststats.SdkInfo;
import dev.faststats.bukkit.BukkitContext;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ErrorReports;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * A live metrics submission, returned by {@link MetricsBuilder#start()}. Wraps the providers that started.
 * {@link #shutdown()} must be called on plugin disable.
 *
 * <p>A plugin starts its metrics once per enable and keeps the handle through a config reload. {@link #shutdown()}
 * does not unregister the {@code ServerExceptionEvent} listeners FastStats registered for the plugin: they stay
 * registered until the plugin is disabled, so each further start in the same enable adds to them.
 *
 * <p>When FastStats starts with its error service, {@link MetricsBuilder#start()} routes the plugin's
 * {@link ErrorReports} to {@link #report}, and each report goes to FastStats error tracking: reports of one kind from
 * one source merge into one error with a count, and each error carries its source as the {@code source} attribute.
 * Two reports are of one kind when their exception classes, stack frames and messages match once the anonymize
 * rules have hidden addresses, UUIDs and the like, through the whole cause chain. Each source sends at most 32
 * kinds for the life of this handle, and a report of any further kind from that source is dropped; a handle from a
 * new {@link MetricsBuilder#start()} starts with none. The first 64 sources get a tracker of their own; reports from
 * any further source are sent without a source, and those sources share one limit of 32 kinds. Once the first
 * report has arrived, every scheduled error submission (every 30 minutes) and the final send in {@link #shutdown()}
 * post an {@code errors} array, even when it is empty.
 *
 * <p>FastStats clears every report it holds once a scheduled error submission is accepted, so a report made while
 * that send is in flight is lost. A later report of the same kind is sent as usual, with its count starting again.
 * The final send in {@link #shutdown()} has no such window, because the error sink is cleared before it.
 *
 * <p>FastStats error tracking also receives each exception Paper reports for the plugin in a
 * {@code ServerExceptionEvent}, without a source. No JVM default uncaught exception handler is installed, replaced or
 * restored: an uncaught exception on a thread the plugin starts itself is printed by the JVM as usual and is not
 * sent. A plugin that wants such an error tracked catches it and reports it through its {@link ErrorReports}.
 *
 * <p>The error sink has one target. A target the plugin installs into it after {@link MetricsBuilder#start()}
 * replaces this handle, and FastStats receives no further report through the sink. A plugin that also wants its
 * reports elsewhere installs one target that passes each report to {@link #report} and to its own handler.
 *
 * <p>The state methods report what each provider is actually doing, for a plugin's diagnostics command. They
 * read cached state without blocking and never throw, so they are safe on any thread. On an inert handle and
 * after {@link #shutdown()} they read {@code false} or {@code null}.
 */
public final class Metrics {

    private final org.bstats.bukkit.@Nullable Metrics bStats;
    final @Nullable BukkitContext fastStats;
    final @Nullable SourceTrackers sources;
    final @Nullable TaffyUsage usage;
    private final ErrorReports errors;
    private final AtomicBoolean shutDown = new AtomicBoolean();

    Metrics(org.bstats.bukkit.@Nullable Metrics bStats, @Nullable BukkitContext fastStats,
            @Nullable TaffyUsage usage, ErrorReports errors) {
        this.bStats = bStats;
        this.fastStats = fastStats;
        this.usage = usage;
        this.sources = fastStats == null ? null
            : fastStats.errorTrackerService().map(SourceTrackers::new).orElse(null);
        this.errors = errors;
    }

    /**
     * Whether bStats submission started and has not been shut down. It did not start when the server owner turned
     * bStats off with {@code enabled: false} in {@code plugins/bStats/config.yml}.
     *
     * @return {@code true} while bStats runs
     */
    public boolean bStatsStarted() {
        return bStats != null && !shutDown.get();
    }

    /**
     * The FastStats SDK name and version, such as {@code bukkit 0.32.2}.
     *
     * @return the name and version, or {@code null} when FastStats did not start or was shut down
     */
    public @Nullable String fastStatsSdk() {
        if (fastStats == null || shutDown.get()) {
            return null;
        }
        try {
            SdkInfo info = fastStats.getSdkInfo();
            return info.getName() + " " + info.getVersion();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    /**
     * Whether FastStats is enabled for this server. It is off when the server owner opted out in
     * {@code plugins/faststats/config.properties} or with {@code -Dfaststats.enabled=false}.
     *
     * @return {@code true} while FastStats runs and is enabled
     */
    public boolean fastStatsEnabled() {
        return reads(context -> context.getConfig().enabled());
    }

    /**
     * Whether FastStats submits metrics: its metrics service was created and metrics are enabled. The plugin's
     * charts are part of each submission only when {@link #fastStatsCharts()} reads {@code true}.
     *
     * @return {@code true} while FastStats submits metrics
     */
    public boolean fastStatsSubmitting() {
        return reads(context -> context.metrics().isPresent() && context.getConfig().submitMetrics());
    }

    /**
     * Whether FastStats submits the plugin's charts: it submits metrics and the server owner left
     * {@code submitAdditionalMetrics} on in {@code plugins/faststats/config.properties}. With that key off,
     * FastStats sends only the fields it fills itself.
     *
     * @return {@code true} while FastStats submits the plugin's charts
     */
    public boolean fastStatsCharts() {
        return reads(context -> context.metrics().isPresent() && context.getConfig().submitMetrics()
            && context.getConfig().additionalMetrics());
    }

    /**
     * Whether FastStats submits error reports: its error service was created and tracking is enabled. This is
     * the answer to whether a report handed to {@link #report} will be sent, with one exception it cannot see: the
     * Error Tracking switch in the project's settings on the FastStats dashboard, which discards reports when off.
     * It does not see the error sink either: after the plugin installs a target of its own that does not pass
     * reports to {@link #report}, a report made through the sink is not sent.
     *
     * @return {@code true} while FastStats submits error reports
     */
    public boolean fastStatsErrorTracking() {
        return reads(context -> context.errorTrackerService().isPresent()
            && context.getConfig().errorTracking());
    }

    /**
     * Sends a handled report to FastStats error tracking under {@code source}, as the class description states.
     * Plugin code reports through its {@link ErrorReports}; this method is for a target that passes each report
     * to FastStats and somewhere else as well. Does nothing on a handle without a FastStats error service and
     * after {@link #shutdown()}. Never throws.
     *
     * @param source where the error was handled, as a short stable key; it is sent unchanged as the
     *     {@code source} attribute, so it follows the rule {@link ErrorReports#report} gives for a source key
     * @param error the error
     */
    public void report(String source, Throwable error) {
        SourceTrackers trackers = sources;
        if (trackers == null || shutDown.get()) {
            return;
        }
        try {
            trackers.report(source, error);
        } catch (RuntimeException | LinkageError ignored) {
            // Reporting an error must never raise one.
        }
    }

    /**
     * Stops submission for every provider that started. In order: the error sink is cleared, the Taffy usage chart
     * stops without a final send, bStats stops, and FastStats sends its last errors and metrics on the calling thread
     * and stops. A step that fails does not stop the next. Idempotent, and never throws. The JVM default uncaught
     * exception handler is left as it is.
     *
     * <p>The final send makes one request for errors and one for metrics, on the calling thread. Each has 3 second
     * connect and request timeouts, which do not cover a stalled name lookup or a response that never finishes, so on
     * a stalled network this call can block the disabling thread for tens of seconds. No thread is started to bound
     * it, since such a thread would outlive the plugin's class loader.
     */
    public void shutdown() {
        if (!shutDown.compareAndSet(false, true)) {
            return;
        }
        quietly(errors::clear);
        if (usage != null) {
            quietly(usage::shutdown);
        }
        if (bStats != null) {
            quietly(bStats::shutdown);
        }
        if (fastStats != null) {
            quietly(fastStats::shutdown);
        }
    }

    /** Reads one fact from the FastStats context, or {@code false} when it cannot be read. */
    private boolean reads(Predicate<BukkitContext> fact) {
        if (fastStats == null || shutDown.get()) {
            return false;
        }
        try {
            return fact.test(fastStats);
        } catch (RuntimeException | LinkageError ex) {
            return false;
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
