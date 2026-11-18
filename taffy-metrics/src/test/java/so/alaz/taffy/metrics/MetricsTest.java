package so.alaz.taffy.metrics;

import dev.faststats.ErrorTracker;
import dev.faststats.bukkit.BukkitContext;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import so.alaz.taffy.platform.ErrorReports;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetricsTest {

    @TempDir
    Path plugins;

    private MetricsFixture fixture;
    private LoopbackSink sink;
    private Thread.@Nullable UncaughtExceptionHandler savedHandler;
    private final ErrorReports errors = new ErrorReports();
    private final List<String> delivered = new ArrayList<>();
    private final List<Metrics> started = new ArrayList<>();

    private final Metrics neither = new Metrics(null, null, null, null, errors);

    @BeforeEach
    void setUp() throws IOException {
        System.clearProperty("faststats.first-run");
        savedHandler = Thread.getDefaultUncaughtExceptionHandler();
        sink = new LoopbackSink();
        sink.point();
        fixture = new MetricsFixture(plugins);
    }

    @AfterEach
    void tearDown() {
        for (Metrics metrics : started) {
            metrics.shutdown();
            if (metrics.globalTracker != null) {
                metrics.globalTracker.detachErrorContext();
            }
        }
        sink.close();
        System.clearProperty("faststats.first-run");
        Thread.setDefaultUncaughtExceptionHandler(savedHandler);
    }

    /** Starts FastStats over the fixture with the given config, through the builder, which readies the context. */
    private Metrics startFastStats(boolean enabled, boolean submitMetrics, boolean submitErrors) throws IOException {
        fixture.writeFastStatsConfig(enabled, submitMetrics, submitErrors);
        Metrics metrics = new MetricsBuilder(fixture.plugin, errors, true)
            .fastStats(MetricsFixture.FASTSTATS_TOKEN)
            .addChart(MetricChart.number("item_count", () -> 7))
            .start();
        started.add(metrics);
        assertThat(metrics.fastStats).isNotNull();
        return metrics;
    }

    @Test
    void reportsNothingStartedWhenNoProviderIsConfigured() {
        assertThat(neither.bStatsStarted()).isFalse();
        assertThat(neither.fastStatsSdk()).isNull();
        assertThat(neither.fastStatsEnabled()).isFalse();
        assertThat(neither.fastStatsSubmitting()).isFalse();
        assertThat(neither.fastStatsCharts()).isFalse();
        assertThat(neither.fastStatsErrorTracking()).isFalse();
    }

    @Test
    void shutsDownWithoutProviders() {
        assertThatCode(neither::shutdown).doesNotThrowAnyException();
        assertThatCode(neither::shutdown).doesNotThrowAnyException();
        assertThat(neither.bStatsStarted()).isFalse();
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(savedHandler);
    }

    @Test
    void shutdownClearsTheErrorSinkOfAnInertHandle() {
        errors.install((source, error) -> delivered.add(source));

        neither.shutdown();
        errors.report("storage", new IllegalStateException());

        assertThat(delivered).isEmpty();
    }

    @Test
    void aStartedBStatsReadsStartedUntilShutdown() {
        org.bstats.bukkit.Metrics bStats = MetricsBuilder.startBStats(fixture.plugin,
            MetricsFixture.BSTATS_SERVICE_ID, List.of(), fixture.log.logger, MetricsBuilder.BSTATS_FAILED);
        assertThat(bStats).isNotNull();
        Metrics metrics = new Metrics(bStats, null, null, null, errors);

        assertThat(metrics.bStatsStarted()).isTrue();
        assertThatCode(metrics::shutdown).doesNotThrowAnyException();
        assertThatCode(metrics::shutdown).doesNotThrowAnyException();
        assertThat(metrics.bStatsStarted()).isFalse();
        assertThat(fixture.log.lines()).isEmpty();
    }

    @Test
    void shutdownStopsBStatsOnce() {
        RecordingBStats bStats = new RecordingBStats(fixture.plugin, null);
        Metrics metrics = new Metrics(bStats, null, null, null, errors);

        metrics.shutdown();
        metrics.shutdown();

        assertThat(bStats.shutdowns).isEqualTo(1);
    }

    @Test
    void aBStatsShutdownThatThrowsDoesNotEscape() {
        for (Throwable failure : List.of(new IllegalStateException("broken"), new NoClassDefFoundError("gone"))) {
            RecordingBStats bStats = new RecordingBStats(fixture.plugin, failure);
            Metrics metrics = new Metrics(bStats, null, null, null, errors);

            assertThatCode(metrics::shutdown).doesNotThrowAnyException();
            assertThat(bStats.shutdowns).isEqualTo(1);
            assertThat(metrics.bStatsStarted()).isFalse();
        }
    }

    @Test
    void anEnabledServerReadsEverythingOn() throws IOException {
        Metrics metrics = startFastStats(true, true, true);

        assertThat(metrics.fastStatsSdk()).isEqualTo("bukkit 0.32.2");
        assertThat(metrics.fastStatsEnabled()).isTrue();
        assertThat(metrics.fastStatsSubmitting()).isTrue();
        assertThat(metrics.fastStatsCharts()).isTrue();
        assertThat(metrics.fastStatsErrorTracking()).isTrue();
    }

    @Test
    void anOptedOutServerReadsEverythingOffButTheSdk() throws IOException {
        Metrics metrics = startFastStats(false, true, true);

        assertThat(metrics.fastStatsSdk()).isEqualTo("bukkit 0.32.2");
        assertThat(metrics.fastStatsEnabled()).isFalse();
        assertThat(metrics.fastStatsSubmitting()).isFalse();
        assertThat(metrics.fastStatsCharts()).isFalse();
        assertThat(metrics.fastStatsErrorTracking()).isFalse();
    }

    @Test
    void aServerWithoutAdditionalMetricsSubmitsButLeavesTheChartsOut() throws IOException {
        fixture.writeFastStatsConfig(true, true, true, false);
        Metrics metrics = new MetricsBuilder(fixture.plugin, errors, true)
            .fastStats(MetricsFixture.FASTSTATS_TOKEN)
            .addChart(MetricChart.number("item_count", () -> 7))
            .start();
        started.add(metrics);

        assertThat(metrics.fastStatsSubmitting()).isTrue();
        assertThat(metrics.fastStatsCharts()).isFalse();
        metrics.shutdown();
        assertThat(sink.requests).filteredOn(request -> request.path().equals("/v1/collect")).singleElement()
            .extracting(LoopbackSink.Request::body).asString()
            .contains("\"plugin_version\":\"1.0.0\"")
            .doesNotContain("item_count");
    }

    @Test
    void aServerWithoutMetricsSubmissionReadsErrorTrackingOnly() throws IOException {
        Metrics metrics = startFastStats(true, false, true);

        assertThat(metrics.fastStatsEnabled()).isTrue();
        assertThat(metrics.fastStatsSubmitting()).isFalse();
        assertThat(metrics.fastStatsCharts()).isFalse();
        assertThat(metrics.fastStatsErrorTracking()).isTrue();
    }

    @Test
    void aServerWithoutErrorSubmissionReadsMetricsOnly() throws IOException {
        Metrics metrics = startFastStats(true, true, false);

        assertThat(metrics.fastStatsEnabled()).isTrue();
        assertThat(metrics.fastStatsSubmitting()).isTrue();
        assertThat(metrics.fastStatsErrorTracking()).isFalse();
        assertThat(metrics.globalTracker).isNull();
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(savedHandler);
    }

    @Test
    void everyFastStatsReaderReadsOffAfterShutdown() throws IOException {
        Metrics metrics = startFastStats(true, true, true);

        metrics.shutdown();

        assertThat(metrics.fastStatsSdk()).isNull();
        assertThat(metrics.fastStatsEnabled()).isFalse();
        assertThat(metrics.fastStatsSubmitting()).isFalse();
        assertThat(metrics.fastStatsCharts()).isFalse();
        assertThat(metrics.fastStatsErrorTracking()).isFalse();
    }

    @Test
    void aCounterReportsItsIncreaseAcrossScheduledSubmissionsAndTheFinalSend() throws IOException {
        fixture.writeFastStatsConfig(true, true, true);
        AtomicLong total = new AtomicLong(5);
        Metrics metrics = new MetricsBuilder(fixture.plugin, errors, true)
            .fastStats(MetricsFixture.FASTSTATS_TOKEN)
            .addChart(MetricChart.counter("events", total::get))
            .start();
        started.add(metrics);
        ArgumentCaptor<Consumer<ScheduledTask>> scheduled = ArgumentCaptor.captor();
        verify(fixture.asyncScheduler, times(3))
            .runAtFixedRate(any(Plugin.class), scheduled.capture(), anyLong(), anyLong(), any(TimeUnit.class));
        List<Consumer<ScheduledTask>> owners = scheduled.getAllValues().subList(0, 2);

        owners.forEach(submission -> submission.accept(fixture.task));
        total.set(8);
        owners.forEach(submission -> submission.accept(fixture.task));
        metrics.shutdown();

        assertThat(sink.requests).filteredOn(request -> request.path().equals("/v1/collect"))
            .extracting(LoopbackSink.Request::body)
            .satisfiesExactly(
                body -> assertThat(body).contains("\"events\":5"),
                body -> assertThat(body).contains("\"events\":3"),
                body -> assertThat(body).contains("\"events\":0"));
    }

    @Test
    void shutdownMakesOneFinalSendAndASecondShutdownSendsNothing() throws IOException {
        Metrics metrics = startFastStats(true, true, true);
        assertThat(sink.requests).isEmpty();

        metrics.shutdown();

        assertThat(sink.paths()).containsExactly("/v1/collect");
        assertThat(sink.requests.getFirst().body()).contains("\"item_count\":7");

        metrics.shutdown();

        assertThat(sink.paths()).containsExactly("/v1/collect");
    }

    @Test
    void shutdownDetachesTheGlobalTracker() throws IOException {
        Metrics metrics = startFastStats(true, true, true);
        assertThat(metrics.globalTracker).isNotNull();
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isNotSameAs(savedHandler);

        metrics.shutdown();

        assertThat(metrics.globalTracker.isContextAttached()).isFalse();
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(savedHandler);
    }

    @Test
    void shutdownLeavesAHandlerInstalledAfterStartInPlace() throws IOException {
        Metrics metrics = startFastStats(true, true, true);
        Thread.UncaughtExceptionHandler later = (thread, error) -> {
        };
        Thread.setDefaultUncaughtExceptionHandler(later);

        metrics.shutdown();

        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(later);
        assertThat(metrics.globalTracker.isContextAttached()).isTrue();
    }

    @Test
    void aTrackerLeftInTheHandlerChainRecordsNothingAfterShutdown() throws Exception {
        Metrics metrics = startFastStats(true, true, true);
        Thread.UncaughtExceptionHandler fastStatsHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(null);
        fastStatsHandler.uncaughtException(Thread.currentThread(), new IllegalStateException("before shutdown"));
        assertThat(trackedErrors(metrics.globalTracker)).contains("before shutdown");

        metrics.shutdown();
        fastStatsHandler.uncaughtException(Thread.currentThread(), new IllegalStateException("after shutdown"));

        assertThat(metrics.globalTracker.isContextAttached()).isTrue();
        assertThat(trackedErrors(metrics.globalTracker)).isEqualTo("[]");
    }

    @Test
    void shutdownLeavesTheHandlerOfAnotherSdkCopyInPlace() throws Exception {
        Metrics metrics = startFastStats(true, true, true);
        URL[] jars = {
            ErrorTracker.class.getProtectionDomain().getCodeSource().getLocation(),
            Class.forName("com.google.gson.JsonArray").getProtectionDomain().getCodeSource().getLocation()};
        try (URLClassLoader copy = new URLClassLoader(jars, ClassLoader.getPlatformClassLoader())) {
            Class<?> copiedTracker = copy.loadClass(ErrorTracker.class.getName());
            assertThat(copiedTracker).isNotSameAs(ErrorTracker.class);
            Object other = copiedTracker.getMethod("contextAware").invoke(null);
            Thread.UncaughtExceptionHandler otherHandler = Thread.getDefaultUncaughtExceptionHandler();
            assertThat(otherHandler.getClass().getName()).startsWith(ErrorTracker.class.getPackageName() + ".");
            try {
                metrics.shutdown();

                assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(otherHandler);
                assertThat(metrics.globalTracker.isContextAttached()).isTrue();
            } finally {
                copiedTracker.getMethod("detachErrorContext").invoke(other);
            }
        }
    }

    @Test
    void shutdownLeavesNoHandlerInPlaceWhenNoneIsSet() throws IOException {
        Metrics metrics = startFastStats(true, true, true);
        Thread.setDefaultUncaughtExceptionHandler(null);

        metrics.shutdown();

        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isNull();
        assertThat(metrics.globalTracker.isContextAttached()).isTrue();
    }

    @Test
    void aFastStatsShutdownThatThrowsStillDetachesTheHandler() throws IOException {
        Metrics metrics = startFastStats(true, true, true);
        when(fixture.task.cancel()).thenThrow(new IllegalStateException("cancel refused"));

        assertThatCode(metrics::shutdown).doesNotThrowAnyException();

        assertThat(sink.paths()).containsExactly("/v1/collect");
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(savedHandler);
    }

    @Test
    void aTargetInstalledBeforeShutdownReceivesNothingAfter() throws IOException {
        Metrics metrics = startFastStats(true, true, true);
        errors.install((source, error) -> delivered.add(source));

        metrics.shutdown();
        errors.report("storage", new IllegalStateException());

        assertThat(delivered).isEmpty();
    }

    @Test
    void aTargetThatPassesEachReportToTheHandleReachesFastStatsAndItsOwnHandler() throws IOException {
        Metrics metrics = startFastStats(true, true, true);
        errors.install((source, error) -> {
            metrics.report(source, error);
            delivered.add(source);
        });

        errors.report("storage", new IllegalStateException("storage offline"));
        metrics.shutdown();

        assertThat(delivered).containsExactly("storage");
        assertThat(sink.requests).filteredOn(request -> request.path().equals("/v1/error")).singleElement()
            .extracting(LoopbackSink.Request::body).asString()
            .contains("storage offline")
            .contains("\"source\":\"storage\"");
    }

    @Test
    void aReportOnAnInertHandleDoesNothing() {
        assertThat(neither.sources).isNull();

        assertThatCode(() -> neither.report("storage", new IllegalStateException())).doesNotThrowAnyException();
        neither.shutdown();
        assertThatCode(() -> neither.report("storage", new IllegalStateException())).doesNotThrowAnyException();
    }

    @Test
    void aReportWithoutAnErrorServiceSendsNothing() throws IOException {
        Metrics metrics = startFastStats(true, true, false);
        assertThat(metrics.sources).isNull();

        metrics.report("storage", new IllegalStateException("storage offline"));
        metrics.shutdown();

        assertThat(sink.paths()).containsExactly("/v1/collect");
    }

    @Test
    void aReportReachesFastStatsAtShutdownWithItsSource() throws IOException {
        Metrics metrics = startFastStats(true, true, true);
        assertThat(metrics.sources).isNotNull();
        IllegalStateException error = new IllegalStateException("storage offline");

        metrics.report("storage", error);
        metrics.report("storage", error);
        metrics.shutdown();

        assertThat(sink.requests).filteredOn(request -> request.path().equals("/v1/error")).singleElement()
            .extracting(LoopbackSink.Request::body).asString()
            .contains("storage offline")
            .contains("\"source\":\"storage\"")
            .contains("\"count\":2");
    }

    @Test
    void aReportAfterShutdownIsNotTracked() throws IOException {
        Metrics metrics = startFastStats(true, true, true);
        metrics.shutdown();

        assertThatCode(() -> metrics.report("storage", new IllegalStateException())).doesNotThrowAnyException();

        assertThat(metrics.sources.sources()).isZero();
        assertThat(sink.paths()).containsExactly("/v1/collect");
    }

    @Test
    void aReportTheSdkRefusesDoesNotEscape() throws IOException {
        Metrics metrics = startFastStats(true, true, true);

        assertThatCode(() -> metrics.report(null, new IllegalStateException())).doesNotThrowAnyException();
        assertThatCode(() -> metrics.report("storage", null)).doesNotThrowAnyException();
    }

    /**
     * The scheduled submissions run once before shutdown, so the metrics read records the supplier's throwable in
     * the global tracker and the error send inside shutdown carries it.
     */
    @Test
    void aSupplierThatThrowsIsReportedToFastStatsErrorTracking() throws IOException {
        fixture.writeFastStatsConfig(true, true, true);
        Metrics metrics = new MetricsBuilder(fixture.plugin, errors, true)
            .fastStats(MetricsFixture.FASTSTATS_TOKEN)
            .addChart(MetricChart.number("item_count", () -> {
                throw new IllegalStateException("supplier broken");
            }))
            .start();
        started.add(metrics);
        ArgumentCaptor<Consumer<ScheduledTask>> scheduled = ArgumentCaptor.captor();
        verify(fixture.asyncScheduler, times(3))
            .runAtFixedRate(any(Plugin.class), scheduled.capture(), anyLong(), anyLong(), any(TimeUnit.class));
        List<Consumer<ScheduledTask>> owners = scheduled.getAllValues().subList(0, 2);
        owners.forEach(submission -> submission.accept(fixture.task));

        metrics.shutdown();

        assertThat(sink.requests).filteredOn(request -> request.path().equals("/v1/error")).singleElement()
            .extracting(LoopbackSink.Request::body).asString()
            .contains("IllegalStateException")
            .contains("supplier broken");
    }

    @Test
    void shutdownStopsTheUsageChartWithoutItsFinalSendAndTheOwnersFastStatsStillSends() throws IOException {
        fixture.writeFastStatsConfig(true, true, true);
        TaffyUsage usage = TaffyUsage.start(fixture.plugin, MetricsFixture.BSTATS_SERVICE_ID,
            MetricsFixture.FASTSTATS_TOKEN);
        BukkitContext owner = MetricsBuilder.startFastStats(fixture.plugin, MetricsFixture.FASTSTATS_TOKEN,
            List.of(MetricChart.number("item_count", () -> 7)), false, fixture.log.logger,
            MetricsBuilder.FASTSTATS_FAILED);
        assertThat(usage.fastStats).isNotNull();
        assertThat(owner).isNotNull();
        Metrics metrics = new Metrics(null, owner, null, usage, errors);
        started.add(metrics);

        metrics.shutdown();

        assertThat(usage.fastStats.submissionActive).isFalse();
        assertThat(sink.requests).singleElement().satisfies(request -> {
            assertThat(request.path()).isEqualTo("/v1/collect");
            assertThat(request.body()).contains("\"item_count\":7").doesNotContain("taffy_version");
        });
        assertThat(fixture.log.lines()).isEmpty();
    }

    @Test
    void aSecondShutdownLeavesTheUsageChartAlone() throws IOException {
        fixture.writeFastStatsConfig(true, true, true);
        TaffyUsage usage = TaffyUsage.start(fixture.plugin, MetricsFixture.BSTATS_SERVICE_ID,
            MetricsFixture.FASTSTATS_TOKEN);
        Metrics metrics = new Metrics(null, null, null, usage, errors);

        metrics.shutdown();
        metrics.shutdown();

        verify(fixture.task, times(1)).cancel();
        assertThat(sink.requests).isEmpty();
    }

    /** The errors {@code tracker} holds now, as the JSON array FastStats would send, read through the SDK. */
    private static String trackedErrors(ErrorTracker tracker) throws ReflectiveOperationException {
        Method data = tracker.getClass().getMethod("getFullData");
        data.setAccessible(true);
        return String.valueOf(data.invoke(tracker));
    }

    /** A real bStats instance that counts its shutdowns and, when given one, throws {@code failure} from each. */
    private static final class RecordingBStats extends org.bstats.bukkit.Metrics {
        private final @Nullable Throwable failure;
        int shutdowns;

        RecordingBStats(Plugin plugin, @Nullable Throwable failure) {
            super(plugin, MetricsFixture.BSTATS_SERVICE_ID);
            this.failure = failure;
        }

        @Override
        public void shutdown() {
            shutdowns++;
            super.shutdown();
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }
}
