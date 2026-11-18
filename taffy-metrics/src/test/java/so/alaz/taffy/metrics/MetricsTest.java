package so.alaz.taffy.metrics;

import com.destroystokyo.paper.event.server.ServerExceptionEvent;
import com.destroystokyo.paper.exception.ServerPluginException;
import dev.faststats.ErrorTracker;
import dev.faststats.bukkit.BukkitContext;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.event.Listener;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
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

    private final Metrics neither = new Metrics(null, null, null, errors);

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
        Metrics metrics = new Metrics(bStats, null, null, errors);

        assertThat(metrics.bStatsStarted()).isTrue();
        assertThatCode(metrics::shutdown).doesNotThrowAnyException();
        assertThatCode(metrics::shutdown).doesNotThrowAnyException();
        assertThat(metrics.bStatsStarted()).isFalse();
        assertThat(fixture.log.lines()).isEmpty();
    }

    @Test
    void shutdownStopsBStatsOnce() {
        RecordingBStats bStats = new RecordingBStats(fixture.plugin, null);
        Metrics metrics = new Metrics(bStats, null, null, errors);

        metrics.shutdown();
        metrics.shutdown();

        assertThat(bStats.shutdowns).isEqualTo(1);
    }

    @Test
    void aBStatsShutdownThatThrowsDoesNotEscape() {
        for (Throwable failure : List.of(new IllegalStateException("broken"), new NoClassDefFoundError("gone"))) {
            RecordingBStats bStats = new RecordingBStats(fixture.plugin, failure);
            Metrics metrics = new Metrics(bStats, null, null, errors);

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
        assertThat(metrics.fastStats.errorTrackerService()).isEmpty();
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
    void startAndShutdownLeaveNoDefaultHandlerInPlaceWhenNoneIsSet() throws IOException {
        Thread.setDefaultUncaughtExceptionHandler(null);

        Metrics metrics = startFastStats(true, true, true);

        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isNull();
        assertThat(globalTracker(metrics).isContextAttached()).isFalse();
        metrics.shutdown();
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isNull();
    }

    @Test
    void startAndShutdownLeaveAnInstalledDefaultHandlerInPlace() throws IOException {
        Thread.UncaughtExceptionHandler installed = (thread, error) -> {
        };
        Thread.setDefaultUncaughtExceptionHandler(installed);

        Metrics metrics = startFastStats(true, true, true);

        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(installed);
        assertThat(globalTracker(metrics).isContextAttached()).isFalse();
        metrics.shutdown();
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(installed);
    }

    @Test
    void shutdownLeavesAHandlerInstalledAfterStartInPlace() throws IOException {
        Thread.UncaughtExceptionHandler later = (thread, error) -> {
        };
        Metrics metrics = startFastStats(true, true, true);
        Thread.setDefaultUncaughtExceptionHandler(later);

        metrics.shutdown();

        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(later);
    }

    @Test
    void twoHandlesShutDownInEitherOrderLeaveTheDefaultHandlerInPlace() throws IOException {
        Thread.UncaughtExceptionHandler installed = (thread, error) -> {
        };
        fixture.writeFastStatsConfig(true, true, true);
        for (Thread.UncaughtExceptionHandler before : Arrays.asList(null, installed)) {
            for (boolean firstStartedStopsFirst : new boolean[] {true, false}) {
                Thread.setDefaultUncaughtExceptionHandler(before);
                Metrics first = startWithOwnSink();
                Metrics second = startWithOwnSink();
                assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(before);

                (firstStartedStopsFirst ? first : second).shutdown();
                assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(before);
                Thread.UncaughtExceptionHandler between = (thread, error) -> {
                };
                Thread.setDefaultUncaughtExceptionHandler(between);
                (firstStartedStopsFirst ? second : first).shutdown();
                assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(between);
            }
        }
    }

    @Test
    void anUncaughtExceptionOnAPluginThreadReachesTheDefaultHandlerAndIsNotSent() throws Exception {
        List<Throwable> received = new CopyOnWriteArrayList<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> received.add(error));
        Metrics metrics = startFastStats(true, true, true);
        IllegalStateException uncaught = new IllegalStateException("worker broken");

        Thread worker = new Thread(() -> {
            throw uncaught;
        });
        worker.start();
        worker.join(TimeUnit.SECONDS.toMillis(10));
        errors.report("storage", new IllegalStateException("storage offline"));
        metrics.shutdown();

        assertThat(received).containsExactly(uncaught);
        assertThat(sink.requests).filteredOn(request -> request.path().equals("/v1/error")).singleElement()
            .extracting(LoopbackSink.Request::body).asString()
            .contains("storage offline")
            .doesNotContain("worker broken");
    }

    @Test
    void aServerExceptionOfThePluginReachesFastStatsAndOneOfAnotherPluginDoesNot() throws Exception {
        Metrics metrics = startFastStats(true, true, true);
        ArgumentCaptor<Listener> listeners = ArgumentCaptor.captor();
        verify(fixture.pluginManager, times(2)).registerEvents(listeners.capture(), any(Plugin.class));
        Plugin other = mock(Plugin.class);

        for (Listener listener : listeners.getAllValues()) {
            Method onServerException = listener.getClass()
                .getMethod("onServerException", ServerExceptionEvent.class);
            onServerException.setAccessible(true);
            onServerException.invoke(listener, serverException(fixture.plugin, "listener broken"));
            onServerException.invoke(listener, serverException(other, "other plugin broken"));
        }
        metrics.shutdown();

        assertThat(sink.requests).filteredOn(request -> request.path().equals("/v1/error")).singleElement()
            .extracting(LoopbackSink.Request::body).asString()
            .contains("listener broken")
            .contains("\"handled\":false")
            .doesNotContain("other plugin broken");
    }

    @Test
    void aFastStatsShutdownThatThrowsLeavesTheDefaultHandlerInPlace() throws IOException {
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
        Metrics metrics = new Metrics(null, owner, usage, errors);
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
        Metrics metrics = new Metrics(null, null, usage, errors);

        metrics.shutdown();
        metrics.shutdown();

        verify(fixture.task, times(1)).cancel();
        assertThat(sink.requests).isEmpty();
    }

    /** Starts FastStats over the fixture's current config with an error sink of its own. */
    private Metrics startWithOwnSink() {
        Metrics metrics = new MetricsBuilder(fixture.plugin, new ErrorReports(), true)
            .fastStats(MetricsFixture.FASTSTATS_TOKEN)
            .start();
        started.add(metrics);
        assertThat(metrics.sources).isNotNull();
        return metrics;
    }

    /** The global tracker of the FastStats error service of {@code metrics}. */
    private static ErrorTracker globalTracker(Metrics metrics) {
        assertThat(metrics.fastStats).isNotNull();
        return metrics.fastStats.errorTrackerService().orElseThrow().globalErrorTracker();
    }

    /**
     * A server exception event, as Paper fires it, for an exception that {@code plugin} is responsible for, whose
     * cause carries {@code message}.
     */
    private static ServerExceptionEvent serverException(Plugin plugin, String message) {
        ServerExceptionEvent event = mock(ServerExceptionEvent.class);
        when(event.getException())
            .thenReturn(new ServerPluginException("plugin failed", new IllegalStateException(message), plugin));
        return event;
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
