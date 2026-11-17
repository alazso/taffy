package so.alaz.taffy.metrics;

import dev.faststats.ErrorTracker;
import dev.faststats.ErrorTrackerService;
import dev.faststats.bukkit.BukkitContext;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ErrorReports;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Collects a plugin's metrics credentials and charts, then on {@link #start()} starts each provider whose
 * credential is set and routes every chart to it. A provider that fails to start is logged as one warning
 * through the owner's logger and skipped without failing plugin enable.
 *
 * <p>The owner's switch, read from the plugin's own config, is given at construction. With it off,
 * {@link #start()} touches no metrics SDK and returns an inert {@link Metrics}.
 *
 * <p>A plugin passes {@code false} as the switch in its own unit tests: bStats refuses to start from classes
 * that were not relocated, which they are not in a plugin's test run, and FastStats writes
 * {@code plugins/faststats/config.properties} under whatever folder the mocked server gives.
 *
 * <p>A null owner, error sink or chart throws {@link NullPointerException}.
 *
 * <p>A builder starts once. After {@link #start()}, every other call on it throws
 * {@link IllegalStateException}.
 *
 * <p>The builder is not thread-safe. Build and start it on one thread.
 */
public final class MetricsBuilder {

    static final String BSTATS_FAILED = "bStats metrics failed to start";
    static final String FASTSTATS_FAILED = "FastStats metrics failed to start";

    /** The form FastStats requires of a project token. */
    static final Pattern TOKEN = Pattern.compile("[a-z0-9]{32}");

    /** The resource FastStats reads its SDK name and version from when it creates a context. */
    static final String SDK_PROPERTIES = "META-INF/faststats.properties";

    private final Plugin owner;
    private final ErrorReports errors;
    private final boolean enabled;
    private final List<MetricChart> charts = new ArrayList<>();
    private int bStatsServiceId;
    private @Nullable String fastStatsToken;
    private boolean started;

    /**
     * Starts an empty builder with no provider.
     *
     * @param owner the plugin the metrics belong to; its logger receives the start warnings
     * @param errors the plugin's error sink
     * @param enabled the owner's switch: {@code false} starts nothing
     */
    public MetricsBuilder(Plugin owner, ErrorReports errors, boolean enabled) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.errors = Objects.requireNonNull(errors, "errors");
        this.enabled = enabled;
    }

    /**
     * Reports to bStats under {@code serviceId}, the plugin's bStats service id. A value of 0 or below leaves
     * bStats off, so an unset id needs no check by the caller.
     *
     * @param serviceId the bStats service id
     * @return this builder
     * @throws IllegalStateException if {@link #start()} was already called
     */
    public MetricsBuilder bStats(int serviceId) {
        checkNotStarted();
        this.bStatsServiceId = serviceId;
        return this;
    }

    /**
     * Reports to FastStats under {@code token}, the plugin's FastStats project token, with error tracking on.
     * A {@code null} or blank token leaves FastStats off, so an unset token needs no check by the caller. A
     * token that does not match {@code [a-z0-9]{32}}, such as a placeholder, or a jar without
     * {@code META-INF/faststats.properties}, is refused at {@link #start()} and logged as one warning, before
     * FastStats reads or writes any file.
     *
     * <p>The server owner decides what FastStats sends in {@code plugins/faststats/config.properties}, which
     * every plugin on the server shares: {@code enabled}, {@code submitMetrics}, {@code submitErrors}, and
     * {@code submitAdditionalMetrics}, which when off leaves every chart added with {@link #addChart} out of
     * the submissions. On the first start of a server with no such file, FastStats writes it and sends nothing
     * until the next start.
     *
     * @param token the FastStats project token, or {@code null}
     * @return this builder
     * @throws IllegalStateException if {@link #start()} was already called
     */
    public MetricsBuilder fastStats(@Nullable String token) {
        checkNotStarted();
        this.fastStatsToken = token == null || token.isBlank() ? null : token;
        return this;
    }

    /**
     * Adds a chart, reported to every provider that starts.
     *
     * @param chart the chart
     * @return this builder
     * @throws IllegalArgumentException if a chart with the same id was already added
     * @throws IllegalStateException if {@link #start()} was already called
     */
    public MetricsBuilder addChart(MetricChart chart) {
        Objects.requireNonNull(chart, "chart");
        checkNotStarted();
        for (MetricChart added : charts) {
            if (added.id.equals(chart.id)) {
                throw new IllegalArgumentException("duplicate chart id '" + chart.id + "'");
            }
        }
        charts.add(chart);
        return this;
    }

    /**
     * Starts every provider whose credential is set, when the owner's switch is on, and returns the handle. Call
     * it once, at the end of enable.
     *
     * <p>When FastStats starts with its error service, the handle is installed into the error sink: the reports
     * the sink still holds are delivered to FastStats error tracking on the calling thread, and every later
     * report goes there too, as {@link Metrics} describes. The sink holds reports only until its first install or
     * clear, so they reach FastStats only when nothing was installed into the sink and it was not cleared before
     * this call; a target the plugin installed earlier has already received them. Otherwise the error sink is
     * cleared, which discards the held reports, and nothing receives later reports. There is no error service when
     * the switch is off, no token is set, FastStats fails to start, the server is on its first run, the server was
     * started with {@code -Dfaststats.enabled=false}, or the server owner turned off {@code enabled} or
     * {@code submitErrors} in {@code plugins/faststats/config.properties}. Either way, a target the plugin installed
     * into the error sink itself is replaced and receives nothing after this call.
     *
     * <p>When the server owner turned bStats off in {@code plugins/bStats/config.yml}, the bStats instance is shut
     * down again at once and {@link Metrics#bStatsStarted()} reads {@code false}.
     *
     * <p>With the switch on, this also starts the Taffy usage chart that the package documentation describes, beside
     * each provider that started here, and the handle stops it at {@link Metrics#shutdown()}.
     *
     * @return the handle, inert when nothing started; never {@code null}
     * @throws IllegalStateException if this builder was already started
     */
    public Metrics start() {
        checkNotStarted();
        started = true;
        if (!enabled) {
            errors.clear();
            return new Metrics(null, null, null, null, errors);
        }
        Logger logger = owner.getLogger();
        org.bstats.bukkit.Metrics bStats = bStatsServiceId > 0
            ? startBStats(owner, bStatsServiceId, charts, logger, BSTATS_FAILED) : null;
        if (bStats != null && !bStatsEnabled(owner)) {
            quietly(bStats::shutdown);
            bStats = null;
        }
        BukkitContext fastStats = fastStatsToken != null
            ? startFastStats(owner, fastStatsToken, charts, true, logger, FASTSTATS_FAILED) : null;
        ErrorTracker globalTracker = fastStats == null ? null
            : fastStats.errorTrackerService().map(ErrorTrackerService::globalErrorTracker).orElse(null);
        TaffyUsage usage = TaffyUsage.start(owner, bStats != null, fastStats != null);
        Metrics handle = new Metrics(bStats, fastStats, globalTracker, usage, errors);
        if (handle.sources != null) {
            errors.install(handle::report);
        } else {
            errors.clear();
        }
        return handle;
    }

    private void checkNotStarted() {
        if (started) {
            throw new IllegalStateException("metrics already started");
        }
    }

    /**
     * Starts bStats with {@code charts}, or logs one warning on {@code logger}, {@code failure} followed by the
     * cause's message, and returns {@code null}.
     */
    static org.bstats.bukkit.@Nullable Metrics startBStats(Plugin owner, int serviceId, List<MetricChart> charts,
                                                          Logger logger, String failure) {
        org.bstats.bukkit.Metrics metrics = null;
        try {
            metrics = new org.bstats.bukkit.Metrics(owner, serviceId);
            for (MetricChart chart : charts) {
                metrics.addCustomChart(BStatsCharts.toChart(chart));
            }
            return metrics;
        } catch (RuntimeException | LinkageError ex) {
            if (metrics != null) {
                quietly(metrics::shutdown);
            }
            logger.warning(failure + ": " + ex.getMessage());
            return null;
        }
    }

    /**
     * Whether the server owner left bStats on: the {@code enabled} key of {@code plugins/bStats/config.yml}, read as
     * bStats reads it. A file that cannot be read counts as on, as it does for bStats.
     */
    static boolean bStatsEnabled(Plugin owner) {
        try {
            File config = new File(new File(owner.getDataFolder().getParentFile(), "bStats"), "config.yml");
            return YamlConfiguration.loadConfiguration(config).getBoolean("enabled", true);
        } catch (RuntimeException | LinkageError ex) {
            return true;
        }
    }

    /**
     * Creates and readies a FastStats context with {@code charts}, or logs one warning on {@code logger},
     * {@code failure} followed by the cause's message, and returns {@code null}. A token that does not match
     * {@link #TOKEN} is refused before FastStats reads or writes its config file, and so is a class loader that does
     * not hold {@code META-INF/faststats.properties}, so a refused start leaves the server's first run to the next
     * plugin that starts FastStats.
     *
     * <p>With {@code errorTracking}, the context gets a context-aware global tracker carrying the
     * {@link Anonymizers} rules, which attaches the JVM default uncaught exception handler as it is created. The
     * handler is detached again when the context builds no error service (a first run, or the server owner opted out)
     * and when the start fails. A start that fails after the context exists also shuts the context down, with
     * submission turned off first so that the shutdown sends nothing.
     */
    static @Nullable BukkitContext startFastStats(Plugin owner, String token, List<MetricChart> charts,
                                                  boolean errorTracking, Logger logger, String failure) {
        return startFastStats(owner, token, charts, errorTracking, logger, failure,
            BukkitContext.class.getClassLoader());
    }

    /**
     * Starts FastStats as {@link #startFastStats(Plugin, String, List, boolean, Logger, String)} does, checking for
     * {@link #SDK_PROPERTIES} through {@code sdkLoader}, the class loader FastStats reads it through.
     */
    static @Nullable BukkitContext startFastStats(Plugin owner, String token, List<MetricChart> charts,
                                                  boolean errorTracking, Logger logger, String failure,
                                                  ClassLoader sdkLoader) {
        if (!TOKEN.matcher(token).matches()) {
            logger.warning(failure + ": Invalid token '" + token + "', must match '" + TOKEN.pattern() + "'");
            return null;
        }
        if (sdkLoader.getResource(SDK_PROPERTIES) == null) {
            logger.warning(failure + ": Resource '/" + SDK_PROPERTIES + "' not found");
            return null;
        }
        ErrorTracker globalTracker = null;
        BukkitContext context = null;
        try {
            if (errorTracking) {
                globalTracker = ErrorTracker.contextAware();
                Anonymizers.apply(globalTracker);
            }
            BukkitContext.Factory factory = new BukkitContext.Factory(owner, token)
                .metrics(metricsFactory -> FastStatsCharts.addTo(metricsFactory, charts).create());
            if (globalTracker != null) {
                factory = factory.errorTrackerService(globalTracker);
            }
            context = factory.create();
            if (globalTracker != null && context.errorTrackerService().isEmpty()) {
                globalTracker.detachErrorContext();
            }
            context.ready();
            return context;
        } catch (RuntimeException | LinkageError ex) {
            if (context != null) {
                BukkitContext failed = context;
                // FastStats 0.31.0 skips every send while SimpleContext.submissionActive is false;
                // shutdown() otherwise makes a final send.
                quietly(() -> failed.submissionActive = false);
                quietly(failed::shutdown);
            }
            if (globalTracker != null) {
                quietly(globalTracker::detachErrorContext);
            }
            logger.warning(failure + ": " + ex.getMessage());
            return null;
        }
    }

    /** Runs one cleanup step of a failed start and ignores its failure. */
    private static void quietly(Runnable step) {
        try {
            step.run();
        } catch (RuntimeException | LinkageError ignored) {
            // The failed start's own warning reports the failure.
        }
    }
}
