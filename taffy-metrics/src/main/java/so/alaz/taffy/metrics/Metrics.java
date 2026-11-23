package so.alaz.taffy.metrics;

import dev.faststats.SdkInfo;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.DiagnosticReport;
import so.alaz.taffy.platform.ErrorReports;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * A live metrics submission, returned by {@link MetricsBuilder#start()}. Wraps the providers that started.
 * {@link #shutdown()} must be called on plugin disable.
 *
 * <p>A plugin starts its metrics once per enable and keeps the handle through a config reload. {@link #shutdown()}
 * does not unregister the {@code ServerExceptionEvent} listeners FastStats registered for the plugin: they stay
 * registered until the plugin is disabled, so each further start in the same enable adds to them.
 *
 * <p>When FastStats starts with its error service, {@link MetricsBuilder#start()} routes the plugin's
 * {@link ErrorReports} to {@link #report} and {@link #reportUnhandled}, and each report goes to FastStats error
 * tracking: reports of one kind from one source merge into one error with a count, and each error carries its
 * source as the {@code source} attribute.
 * Two reports are of one kind when their exception classes, stack frames and messages match once the anonymize
 * rules have hidden addresses, UUIDs and the like, through the whole cause chain. An IPv4 address, an IPv6 address
 * and a run of digits in a message each count as the same whatever their value, so {@code timeout after 5003ms}
 * and {@code timeout after 71ms} are one kind, sent with the message of the first report. Each source sends at
 * most 32 kinds for the life of this handle, and a report of any further kind from that source is dropped; a handle
 * from a new {@link MetricsBuilder#start()} starts with none. When a source holds its 32nd kind, one warning that
 * names the source is logged on the plugin's logger. The first 64 sources get a tracker of their own; reports from
 * any further source are sent without a source, and those sources share one limit of 32 kinds. Once the first
 * report has arrived, every scheduled error submission (every 30 minutes) and the final send in {@link #shutdown()}
 * post an {@code errors} array, even when it is empty.
 *
 * <p>A report made through {@link ErrorReports#reportUnhandled}, as {@code Scheduler} makes for a task that throws,
 * is sent with {@code handled} set to {@code false}, as FastStats marks the errors it catches itself. FastStats does
 * not merge such errors into one with a count, so the first unhandled report of a kind from a source is sent, and
 * a later unhandled report of that kind from that source is not sent for the life of this handle.
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
 * reports elsewhere installs one target that passes each report to {@link #report} and to its own handler, with
 * {@link ErrorReports#install(java.util.function.BiConsumer, java.util.function.BiConsumer)} when unhandled
 * reports are to reach {@link #reportUnhandled}.
 *
 * <p>The state methods report what each provider is actually doing, for a plugin's diagnostics command. They
 * read cached state without blocking and never throw, so they are safe on any thread. On an inert handle and
 * after {@link #shutdown()} they read {@code false} or {@code null}. {@link #diagnostics()} gives them as the lines
 * of a {@link DiagnosticReport} section.
 */
public final class Metrics {

    /** How long {@link #shutdown()} waits for the final FastStats send. */
    static final Duration FINAL_SEND_WAIT = Duration.ofSeconds(5);

    /** The name of the daemon thread the final FastStats send runs on. */
    static final String FINAL_SEND_THREAD = "taffy-metrics-final-send";

    private final org.bstats.bukkit.@Nullable Metrics bStats;
    final @Nullable FastStatsRun fastStats;
    final @Nullable SourceTrackers sources;
    final @Nullable TaffyUsage usage;
    private final ErrorReports errors;
    private final AtomicBoolean shutDown = new AtomicBoolean();

    Metrics(org.bstats.bukkit.@Nullable Metrics bStats, @Nullable FastStatsRun fastStats,
            @Nullable TaffyUsage usage, ErrorReports errors, Logger logger) {
        this.bStats = bStats;
        this.fastStats = fastStats;
        this.usage = usage;
        this.sources = fastStats == null ? null
            : fastStats.errorTrackerService().map(service -> new SourceTrackers(service, logger)).orElse(null);
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
     * The FastStats SDK name and version, such as {@code bukkit 0.33.0}.
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
     * What each provider is doing, as the lines of a {@link DiagnosticReport} section, read from the state methods
     * of this handle. It reads cached state without blocking and never throws, so it is safe on any thread. The
     * lines hold no token, service id or server id.
     *
     * <ul>
     *   <li>{@code bStats}: {@code on} while {@link #bStatsStarted()}, otherwise {@code not running};</li>
     *   <li>{@code FastStats}: {@code not running} when {@link #fastStatsSdk()} is {@code null}, and otherwise the
     *       SDK name and version, a comma and one of {@code opted out on this server},
     *       {@code not submitting metrics}, {@code submitting metrics without the plugin's charts} and
     *       {@code submitting metrics and the plugin's charts}, as in
     *       {@code bukkit 0.33.0, submitting metrics and the plugin's charts};</li>
     *   <li>{@code FastStats error tracking}: {@code on} while {@link #fastStatsErrorTracking()}, otherwise
     *       {@code off}.</li>
     * </ul>
     *
     * <p>A provider the plugin did not configure, one its owner turned off and every provider after
     * {@link #shutdown()} read {@code not running}. A plugin with a switch of its own for metrics says so in a line
     * of its own.
     *
     * @return the three lines, unmodifiable
     */
    public List<DiagnosticReport.Line> diagnostics() {
        String sdk = fastStatsSdk();
        String fastStatsState;
        if (sdk == null) {
            fastStatsState = "not running";
        } else if (!fastStatsEnabled()) {
            fastStatsState = sdk + ", opted out on this server";
        } else if (!fastStatsSubmitting()) {
            fastStatsState = sdk + ", not submitting metrics";
        } else if (!fastStatsCharts()) {
            fastStatsState = sdk + ", submitting metrics without the plugin's charts";
        } else {
            fastStatsState = sdk + ", submitting metrics and the plugin's charts";
        }
        return List.of(
            new DiagnosticReport.Line("bStats", bStatsStarted() ? "on" : "not running"),
            new DiagnosticReport.Line("FastStats", fastStatsState),
            new DiagnosticReport.Line("FastStats error tracking", fastStatsErrorTracking() ? "on" : "off"));
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
        report(source, error, true);
    }

    /**
     * Sends a report to FastStats error tracking under {@code source} with {@code handled} set to {@code false},
     * as the class description states: once for its kind. For an error the plugin does not deal with and rethrows
     * or lets escape. {@link MetricsBuilder#start()} routes {@link ErrorReports#reportUnhandled} here. Does
     * nothing on a handle without a FastStats error service and after {@link #shutdown()}. Never throws.
     *
     * @param source where the error escaped, as a short stable key; the rule {@link #report} gives applies
     * @param error the error
     */
    public void reportUnhandled(String source, Throwable error) {
        report(source, error, false);
    }

    private void report(String source, Throwable error, boolean handled) {
        SourceTrackers trackers = sources;
        if (trackers == null || shutDown.get()) {
            return;
        }
        try {
            trackers.report(source, error, handled);
        } catch (RuntimeException | LinkageError ignored) {
            // Reporting an error must never raise one.
        }
    }

    /**
     * Stops submission for every provider that started. In order: the error sink is cleared, the Taffy usage chart
     * stops without a final send, bStats stops, and FastStats sends its last errors and metrics and stops. A step
     * that fails does not stop the next. Idempotent, and never throws. The JVM default uncaught exception handler is
     * left as it is.
     *
     * <p>The final send makes one request for errors and one for metrics on a daemon thread named
     * {@code taffy-metrics-final-send}, and this call waits at most 5 seconds for it, holding no lock. A send that
     * finishes sooner ends the wait. Each request has 3 second connect and request timeouts, which do not cover a
     * stalled name lookup or a response that never finishes. When the 5 seconds pass first, this call returns and
     * the send goes on, on that thread, until it finishes or the JVM exits, which can be after the plugin's class
     * loader was closed. The chart suppliers the final send reads run on that thread. An interrupt, also one already
     * set on the calling thread when this is called, ends the wait at once and stays set.
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
            quietly(() -> finalSend(fastStats));
        }
    }

    /** Shuts {@code context} down on a daemon thread and waits at most {@link #FINAL_SEND_WAIT} for it. */
    private static void finalSend(FastStatsRun context) {
        Thread sender = new Thread(() -> quietly(context::shutdown), FINAL_SEND_THREAD);
        sender.setDaemon(true);
        sender.start();
        try {
            sender.join(FINAL_SEND_WAIT);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** Reads one fact from the FastStats context, or {@code false} when it cannot be read. */
    private boolean reads(Predicate<FastStatsRun> fact) {
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
