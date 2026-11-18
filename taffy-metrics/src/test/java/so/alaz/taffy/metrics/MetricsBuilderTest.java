package so.alaz.taffy.metrics;

import dev.faststats.bukkit.BukkitContext;
import org.bstats.charts.CustomChart;
import org.bstats.json.JsonObjectBuilder;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.test.CapturedLog;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetricsBuilderTest {

    @TempDir
    Path plugins;

    private MetricsFixture fixture;
    private LoopbackSink sink;
    private Thread.@Nullable UncaughtExceptionHandler savedHandler;
    private final ErrorReports errors = new ErrorReports();
    private final List<String> delivered = new ArrayList<>();
    private final List<Metrics> started = new ArrayList<>();
    private final List<BukkitContext> contexts = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        System.clearProperty("faststats.first-run");
        savedHandler = Thread.getDefaultUncaughtExceptionHandler();
        sink = new LoopbackSink();
        sink.point();
        fixture = new MetricsFixture(plugins);
        errors.install((source, error) -> delivered.add(source));
    }

    @AfterEach
    void tearDown() {
        for (Metrics metrics : started) {
            metrics.shutdown();
        }
        contexts.forEach(BukkitContext::shutdown);
        sink.close();
        System.clearProperty("faststats.first-run");
        Thread.setDefaultUncaughtExceptionHandler(savedHandler);
    }

    /** Starts {@code builder} and shuts the handle down after the test. */
    private Metrics start(MetricsBuilder builder) {
        Metrics metrics = builder.start();
        started.add(metrics);
        return metrics;
    }

    @Test
    void theSwitchOffGivesAnInertHandleAndTouchesNoSdk() throws IOException {
        Metrics metrics = new MetricsBuilder(fixture.plugin, errors, false)
            .bStats(MetricsFixture.BSTATS_SERVICE_ID)
            .fastStats(MetricsFixture.FASTSTATS_TOKEN)
            .addChart(MetricChart.number("item_count", () -> 1))
            .start();

        assertThat(metrics).isNotNull();
        assertThat(metrics.bStatsStarted()).isFalse();
        assertThat(metrics.fastStatsSdk()).isNull();
        assertThat(metrics.usage).isNull();
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(savedHandler);
        assertThat(fixture.bStatsConfigText()).isEqualTo(MetricsFixture.BSTATS_CONFIG);
        assertThat(plugins.resolve("faststats")).doesNotExist();
        assertThat(plugins.resolve("Owner")).doesNotExist();
        verify(fixture.plugin, never()).getDataFolder();
        assertThat(fixture.log.lines()).isEmpty();
    }

    @Test
    void theSwitchOnStartsTheUsageChartBesideEachProviderThatStarted() throws IOException {
        fixture.enableBStats();
        fixture.writeFastStatsConfig(true, true, true);

        Metrics metrics = start(new MetricsBuilder(fixture.plugin, errors, true)
            .bStats(MetricsFixture.BSTATS_SERVICE_ID)
            .fastStats(MetricsFixture.FASTSTATS_TOKEN));

        assertThat(metrics.usage).isNotNull();
        assertThat(metrics.bStatsStarted()).isTrue();
        assertThat(metrics.usage.bStats).isNotNull();
        assertThat(metrics.usage.fastStats).isNotNull().isNotSameAs(metrics.fastStats);
        assertThat(metrics.usage.fastStats.errorTrackerService()).isEmpty();
        assertThat(fixture.log.lines()).isEmpty();
    }

    @Test
    void aPluginOnBStatsAloneGetsNoTaffyFastStatsContext() throws IOException {
        fixture.enableBStats();
        Metrics metrics = start(new MetricsBuilder(fixture.plugin, errors, true)
            .bStats(MetricsFixture.BSTATS_SERVICE_ID));

        assertThat(metrics.usage).isNotNull();
        assertThat(metrics.usage.bStats).isNotNull();
        assertThat(metrics.usage.fastStats).isNull();
        assertThat(plugins.resolve("faststats")).doesNotExist();
        assertThat(fixture.log.lines()).isEmpty();
    }

    @Test
    void aPluginOnFastStatsAloneGetsNoTaffyBStatsInstance() throws IOException {
        fixture.writeFastStatsConfig(true, true, true);

        Metrics metrics = start(new MetricsBuilder(fixture.plugin, errors, true)
            .fastStats(MetricsFixture.FASTSTATS_TOKEN));

        assertThat(metrics.usage).isNotNull();
        assertThat(metrics.usage.bStats).isNull();
        assertThat(metrics.usage.fastStats).isNotNull();
        verify(fixture.plugin, never()).getDataFolder();
    }

    @Test
    void aBStatsTheServerOwnerTurnedOffStartsNoTaffyBStatsInstance() {
        Metrics metrics = start(new MetricsBuilder(fixture.plugin, errors, true)
            .bStats(MetricsFixture.BSTATS_SERVICE_ID));

        assertThat(metrics.bStatsStarted()).isFalse();
        assertThat(metrics.usage).isNotNull();
        assertThat(metrics.usage.bStats).isNull();
    }

    @Test
    void theSwitchOffClearsTheErrorSink() {
        new MetricsBuilder(fixture.plugin, errors, false).start();

        errors.report("storage", new IllegalStateException());

        assertThat(delivered).isEmpty();
    }

    @Test
    void aPositiveServiceIdStartsBStats() throws IOException {
        fixture.enableBStats();
        Metrics metrics = new MetricsBuilder(fixture.plugin, errors, true)
            .bStats(MetricsFixture.BSTATS_SERVICE_ID)
            .start();

        assertThat(metrics.bStatsStarted()).isTrue();
        assertThat(fixture.bStatsConfigText()).isEqualTo(MetricsFixture.BSTATS_ENABLED_CONFIG);
        assertThat(fixture.log.lines()).isEmpty();
        metrics.shutdown();
    }

    @Test
    void aServerWhoseOwnerTurnedBStatsOffReadsBStatsNotStarted() throws IOException {
        Set<Thread> before = bStatsThreads();

        Metrics metrics = start(new MetricsBuilder(fixture.plugin, errors, true)
            .bStats(MetricsFixture.BSTATS_SERVICE_ID));

        assertThat(metrics.bStatsStarted()).isFalse();
        assertThat(bStatsThreads()).isEqualTo(before);
        assertThat(fixture.bStatsConfigText()).isEqualTo(MetricsFixture.BSTATS_CONFIG);
        assertThat(fixture.log.lines()).isEmpty();
    }

    @Test
    void bStatsEnabledReadsTheOwnersSwitchAsBStatsDoes() throws IOException {
        assertThat(MetricsBuilder.bStatsEnabled(fixture.plugin)).isFalse();

        fixture.enableBStats();
        assertThat(MetricsBuilder.bStatsEnabled(fixture.plugin)).isTrue();

        Files.writeString(fixture.bStatsConfig, "serverUuid: 00000000-0000-4000-8000-000000000000\n");
        assertThat(MetricsBuilder.bStatsEnabled(fixture.plugin)).isTrue();

        Files.delete(fixture.bStatsConfig);
        assertThat(MetricsBuilder.bStatsEnabled(fixture.plugin)).isTrue();

        when(fixture.plugin.getDataFolder()).thenReturn(null);
        assertThat(MetricsBuilder.bStatsEnabled(fixture.plugin)).isTrue();
    }

    @Test
    void aServiceIdOfZeroOrBelowSkipsBStatsSilently() {
        for (int serviceId : new int[] {0, -1, Integer.MIN_VALUE}) {
            Metrics metrics = new MetricsBuilder(fixture.plugin, errors, true).bStats(serviceId).start();

            assertThat(metrics.bStatsStarted()).as("service id %d", serviceId).isFalse();
        }
        verify(fixture.plugin, never()).getDataFolder();
        assertThat(fixture.log.lines()).isEmpty();
    }

    @Test
    void noBStatsCallLeavesBStatsOff() {
        Metrics metrics = new MetricsBuilder(fixture.plugin, errors, true)
            .addChart(MetricChart.number("item_count", () -> 1))
            .start();

        assertThat(metrics.bStatsStarted()).isFalse();
        verify(fixture.plugin, never()).getDataFolder();
    }

    @Test
    void theLastServiceIdGivenWins() {
        Metrics metrics = new MetricsBuilder(fixture.plugin, errors, true)
            .bStats(MetricsFixture.BSTATS_SERVICE_ID)
            .bStats(0)
            .start();

        assertThat(metrics.bStatsStarted()).isFalse();
    }

    @Test
    void aBStatsThatCannotStartLogsOneWarningAndEnableGoesOn() {
        when(fixture.plugin.getDataFolder()).thenReturn(null);

        Metrics metrics = new MetricsBuilder(fixture.plugin, errors, true)
            .bStats(MetricsFixture.BSTATS_SERVICE_ID)
            .start();

        assertThat(metrics.bStatsStarted()).isFalse();
        assertThat(fixture.log.lines()).singleElement().asString()
            .startsWith("WARNING bStats metrics failed to start: ");
    }

    @Test
    void anUnrelocatedBStatsIsRefusedAndLoggedOnce() {
        String previous = System.getProperty("bstats.relocatecheck");
        System.clearProperty("bstats.relocatecheck");
        try {
            Metrics metrics = new MetricsBuilder(fixture.plugin, errors, true)
                .bStats(MetricsFixture.BSTATS_SERVICE_ID)
                .start();

            assertThat(metrics.bStatsStarted()).isFalse();
            assertThat(fixture.log.lines())
                .containsExactly("WARNING bStats metrics failed to start: "
                    + "bStats Metrics class has not been relocated correctly!");
        } finally {
            if (previous == null) {
                System.clearProperty("bstats.relocatecheck");
            } else {
                System.setProperty("bstats.relocatecheck", previous);
            }
        }
    }

    @Test
    void aLinkageErrorFromTheSdkIsLoggedOnce() {
        when(fixture.plugin.getDataFolder()).thenThrow(new NoClassDefFoundError("org/bstats/MetricsBase"));

        assertThat(MetricsBuilder.startBStats(fixture.plugin, MetricsFixture.BSTATS_SERVICE_ID, List.of(),
            fixture.log.logger,
            MetricsBuilder.BSTATS_FAILED)).isNull();

        assertThat(fixture.log.lines())
            .containsExactly("WARNING bStats metrics failed to start: org/bstats/MetricsBase");
    }

    @Test
    void aChartThatFailsToConvertShutsTheBStatsInstanceDown() throws IOException, InterruptedException {
        fixture.enableBStats();
        Set<Thread> before = bStatsThreads();
        org.bstats.bukkit.Metrics running = MetricsBuilder.startBStats(fixture.plugin,
            MetricsFixture.BSTATS_SERVICE_ID, List.of(), fixture.log.logger, MetricsBuilder.BSTATS_FAILED);
        assertThat(running).isNotNull();
        Set<Thread> runningThreads = bStatsThreads();
        runningThreads.removeAll(before);
        assertThat(runningThreads).hasSize(1);
        running.shutdown();
        awaitEnd(runningThreads);

        Set<Thread> beforeFailure = bStatsThreads();
        assertThat(MetricsBuilder.startBStats(fixture.plugin, MetricsFixture.BSTATS_SERVICE_ID,
            Collections.singletonList(null), fixture.log.logger,
            MetricsBuilder.BSTATS_FAILED)).isNull();

        Set<Thread> failedThreads = bStatsThreads();
        failedThreads.removeAll(beforeFailure);
        awaitEnd(failedThreads);
        assertThat(fixture.log.lines()).singleElement().asString()
            .startsWith("WARNING bStats metrics failed to start: ");
    }

    /** Waits up to ten seconds for each of {@code threads} to end, and asserts that they did. */
    private static void awaitEnd(Set<Thread> threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(thread.isAlive()).as(thread.getName()).isFalse();
        }
    }

    /** The live threads bStats schedules its submissions on. */
    private static Set<Thread> bStatsThreads() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.getName().equals("bStats-Metrics"))
            .collect(Collectors.toCollection(HashSet::new));
    }

    @Test
    void startBStatsWarnsOnTheLoggerItIsGiven() {
        when(fixture.plugin.getDataFolder()).thenReturn(null);
        CapturedLog other = new CapturedLog();

        assertThat(MetricsBuilder.startBStats(fixture.plugin, MetricsFixture.BSTATS_SERVICE_ID, List.of(),
            other.logger, MetricsBuilder.BSTATS_FAILED)).isNull();

        assertThat(fixture.log.lines()).isEmpty();
        assertThat(other.lines()).hasSize(1);
    }

    @Test
    void everyChartReachesBStats() throws ReflectiveOperationException {
        org.bstats.bukkit.Metrics bStats = MetricsBuilder.startBStats(fixture.plugin,
            MetricsFixture.BSTATS_SERVICE_ID, List.of(
                MetricChart.string("storage_type", () -> "sqlite"),
                MetricChart.number("item_count", () -> 42),
                MetricChart.counter("events", () -> 3L)),
            fixture.log.logger, MetricsBuilder.BSTATS_FAILED);

        assertThat(bStats).isNotNull();
        try {
            assertThat(chartsOf(bStats)).containsExactlyInAnyOrder(
                "{\"chartId\":\"storage_type\",\"data\":{\"value\":\"sqlite\"}}",
                "{\"chartId\":\"item_count\",\"data\":{\"value\":42}}",
                "{\"chartId\":\"events\",\"data\":{\"value\":3}}");
        } finally {
            bStats.shutdown();
        }
    }

    @Test
    void aNullOrBlankTokenSkipsFastStatsSilently() {
        for (String token : new String[] {null, "", "   "}) {
            Metrics metrics = start(new MetricsBuilder(fixture.plugin, errors, true).fastStats(token));

            assertThat(metrics.fastStatsSdk()).as("token '%s'", token).isNull();
            assertThat(metrics.fastStats).isNull();
        }
        assertThat(plugins.resolve("faststats")).doesNotExist();
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(savedHandler);
        assertThat(fixture.log.lines()).isEmpty();
    }

    @Test
    void theLastTokenGivenWins() throws IOException {
        fixture.writeFastStatsConfig(true, true, true);

        Metrics metrics = start(new MetricsBuilder(fixture.plugin, errors, true)
            .fastStats(MetricsFixture.FASTSTATS_TOKEN)
            .fastStats(" "));

        assertThat(metrics.fastStats).isNull();
    }

    @Test
    void aMalformedTokenLogsOneWarningWhileBStatsStillStarts() throws IOException {
        fixture.enableBStats();
        Metrics metrics = start(new MetricsBuilder(fixture.plugin, errors, true)
            .bStats(MetricsFixture.BSTATS_SERVICE_ID)
            .fastStats("Not-A-Token"));

        assertThat(metrics.bStatsStarted()).isTrue();
        assertThat(metrics.fastStatsSdk()).isNull();
        assertThat(metrics.sources).isNull();
        assertThat(fixture.log.lines()).containsExactly(
            "WARNING FastStats metrics failed to start: Invalid token 'Not-A-Token', must match '[a-z0-9]{32}'");
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(savedHandler);
    }

    @Test
    void aMalformedTokenWritesNoFastStatsConfigSoTheNextStartKeepsItsFirstRun() throws IOException {
        Metrics refused = start(new MetricsBuilder(fixture.plugin, errors, true).fastStats("CHANGE_ME_TOKEN"));

        assertThat(refused.fastStats).isNull();
        assertThat(plugins.resolve("faststats")).doesNotExist();
        assertThat(System.getProperty("faststats.first-run")).isNull();
        assertThat(fixture.log.lines()).containsExactly(
            "WARNING FastStats metrics failed to start: Invalid token 'CHANGE_ME_TOKEN', must match '[a-z0-9]{32}'");

        Metrics next = start(new MetricsBuilder(fixture.plugin, new ErrorReports(), true)
            .fastStats(MetricsFixture.FASTSTATS_TOKEN));

        assertThat(next.fastStats).isNotNull();
        assertThat(next.fastStats.metrics()).isEmpty();
        assertThat(next.fastStats.errorTrackerService()).isEmpty();
        assertThat(System.getProperty("faststats.first-run")).isEqualTo("true");
        next.shutdown();
        assertThat(sink.requests).isEmpty();
    }

    @Test
    void aLoaderWithoutFastStatsPropertiesWritesNoFastStatsConfigSoTheNextStartKeepsItsFirstRun() throws IOException {
        BukkitContext refused;
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            refused = MetricsBuilder.startFastStats(fixture.plugin, MetricsFixture.FASTSTATS_TOKEN, List.of(), true,
                fixture.log.logger, MetricsBuilder.FASTSTATS_FAILED, empty);
        }

        assertThat(refused).isNull();
        assertThat(plugins.resolve("faststats")).doesNotExist();
        assertThat(System.getProperty("faststats.first-run")).isNull();
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(savedHandler);
        assertThat(fixture.log.lines()).containsExactly(
            "WARNING FastStats metrics failed to start: Resource '/META-INF/faststats.properties' not found");

        Metrics next = start(new MetricsBuilder(fixture.plugin, new ErrorReports(), true)
            .fastStats(MetricsFixture.FASTSTATS_TOKEN));

        assertThat(next.fastStats).isNotNull();
        assertThat(next.fastStats.metrics()).isEmpty();
        assertThat(next.fastStats.errorTrackerService()).isEmpty();
        assertThat(System.getProperty("faststats.first-run")).isEqualTo("true");
        next.shutdown();
        assertThat(sink.requests).isEmpty();
    }

    @Test
    void theReservedChartIdsAreExactlyTheFieldsFastStatsFillsItself() throws IOException {
        fixture.writeFastStatsConfig(true, true, true);
        Metrics metrics = start(new MetricsBuilder(fixture.plugin, errors, true)
            .fastStats(MetricsFixture.FASTSTATS_TOKEN));

        metrics.shutdown();

        String body = sink.requests.getFirst().body();
        String data = body.substring(body.indexOf("\"data\":{") + "\"data\":{".length());
        Set<String> keys = new HashSet<>();
        Matcher key = Pattern.compile("\"([a-z0-9_]+)\":").matcher(data);
        while (key.find()) {
            keys.add(key.group(1));
        }
        assertThat(keys).isEqualTo(MetricChart.RESERVED);
    }

    @Test
    void anOptedOutServerGivesAContextWithNoServicesAndLeavesTheHandlerAlone() throws IOException {
        fixture.writeFastStatsConfig(false, true, true);

        Metrics metrics = start(new MetricsBuilder(fixture.plugin, errors, true)
            .fastStats(MetricsFixture.FASTSTATS_TOKEN)
            .addChart(MetricChart.number("item_count", () -> 1)));

        assertThat(metrics.fastStats).isNotNull();
        assertThat(metrics.fastStats.metrics()).isEmpty();
        assertThat(metrics.fastStats.errorTrackerService()).isEmpty();
        assertThat(metrics.sources).isNull();
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(savedHandler);
        metrics.shutdown();
        assertThat(sink.requests).isEmpty();
        assertThat(fixture.log.lines()).isEmpty();
    }

    @Test
    void aFirstRunGivesAContextWithNoServicesAndLeavesTheHandlerAlone() {
        Metrics metrics = start(new MetricsBuilder(fixture.plugin, errors, true)
            .fastStats(MetricsFixture.FASTSTATS_TOKEN)
            .addChart(MetricChart.number("item_count", () -> 1)));

        assertThat(metrics.fastStats).isNotNull();
        assertThat(metrics.fastStats.metrics()).isEmpty();
        assertThat(metrics.fastStats.errorTrackerService()).isEmpty();
        assertThat(metrics.sources).isNull();
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(savedHandler);
        assertThat(fixture.fastStatsConfig).isRegularFile();
        assertThat(System.getProperty("faststats.first-run")).isEqualTo("true");
        metrics.shutdown();
        assertThat(sink.requests).isEmpty();
        assertThat(fixture.log.lines()).noneMatch(line -> line.startsWith("WARNING"));
    }

    @Test
    void anEnabledServerGivesBothServicesAndReadiesTheContext() throws IOException {
        fixture.writeFastStatsConfig(true, true, true);

        Metrics metrics = start(new MetricsBuilder(fixture.plugin, errors, true)
            .fastStats(MetricsFixture.FASTSTATS_TOKEN));

        assertThat(metrics.fastStats).isNotNull();
        assertThat(metrics.fastStats.metrics()).isPresent();
        assertThat(metrics.fastStats.errorTrackerService()).isPresent();
        assertThat(metrics.sources).isNotNull();
        assertThat(metrics.fastStats.errorTrackerService().orElseThrow().globalErrorTracker().isContextAttached())
            .isFalse();
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(savedHandler);
        verify(fixture.asyncScheduler, times(3))
            .runAtFixedRate(any(Plugin.class), any(), anyLong(), anyLong(), any(TimeUnit.class));
        verify(fixture.pluginManager, times(2)).registerEvents(any(Listener.class), any(Plugin.class));
        assertThat(sink.requests).isEmpty();
        assertThat(fixture.log.lines()).isEmpty();
    }

    @Test
    void everyChartReachesFastStats() throws IOException {
        fixture.writeFastStatsConfig(true, true, true);
        Metrics metrics = start(new MetricsBuilder(fixture.plugin, errors, true)
            .fastStats(MetricsFixture.FASTSTATS_TOKEN)
            .addChart(MetricChart.string("storage_type", () -> "sqlite"))
            .addChart(MetricChart.number("item_count", () -> 42))
            .addChart(MetricChart.counter("events", () -> 3L)));

        metrics.shutdown();

        assertThat(sink.requests).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/v1/collect");
            assertThat(request.body())
                .contains("\"project_name\":\"Owner\"")
                .contains("\"identifier\":\"" + MetricsFixture.SERVER_ID + "\"")
                .contains("\"storage_type\":\"sqlite\"")
                .contains("\"item_count\":42")
                .contains("\"events\":3")
                .contains("\"plugin_version\":\"1.0.0\"");
        });
    }

    @Test
    void aContextWhoseReadyFailsIsShutDownAndLeavesTheHandlerAlone() throws IOException {
        fixture.writeFastStatsConfig(true, true, true);
        for (Throwable failure : List.of(new IllegalStateException("listener refused"),
            new NoClassDefFoundError("listener refused"))) {
            fixture.log.records.clear();
            sink.requests.clear();
            doThrow(failure).when(fixture.pluginManager).registerEvents(any(Listener.class), any(Plugin.class));

            Metrics metrics = start(new MetricsBuilder(fixture.plugin, errors, true)
                .fastStats(MetricsFixture.FASTSTATS_TOKEN));

            assertThat(metrics.fastStats).isNull();
            assertThat(metrics.sources).isNull();
            assertThat(fixture.log.lines())
                .containsExactly("WARNING FastStats metrics failed to start: listener refused");
            assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(savedHandler);
            assertThat(sink.requests).isEmpty();
        }
        verify(fixture.task, times(4)).cancel();
    }

    @Test
    void aFailedStartWhoseShutdownAlsoFailsLeavesTheHandlerAlone() throws IOException {
        fixture.writeFastStatsConfig(true, true, true);
        doThrow(new IllegalStateException("listener refused"))
            .when(fixture.pluginManager).registerEvents(any(Listener.class), any(Plugin.class));
        when(fixture.task.cancel()).thenThrow(new IllegalStateException("cancel refused"));

        Metrics metrics = start(new MetricsBuilder(fixture.plugin, errors, true)
            .fastStats(MetricsFixture.FASTSTATS_TOKEN));

        assertThat(metrics.fastStats).isNull();
        assertThat(fixture.log.lines())
            .containsExactly("WARNING FastStats metrics failed to start: listener refused");
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(savedHandler);
    }

    @Test
    void aConfigThatCannotBeWrittenLogsOneWarningAndLeavesTheHandlerAlone() throws IOException {
        Files.writeString(plugins.resolve("faststats"), "not a folder");

        Metrics metrics = start(new MetricsBuilder(fixture.plugin, errors, true)
            .fastStats(MetricsFixture.FASTSTATS_TOKEN));

        assertThat(metrics.fastStats).isNull();
        assertThat(fixture.log.lines()).singleElement().asString()
            .startsWith("WARNING FastStats metrics failed to start: ");
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(savedHandler);
    }

    @Test
    void startFastStatsWithoutErrorTrackingAttachesNoHandler() throws IOException {
        fixture.writeFastStatsConfig(true, true, true);

        BukkitContext context = MetricsBuilder.startFastStats(fixture.plugin, MetricsFixture.FASTSTATS_TOKEN,
            List.of(MetricChart.number("item_count", () -> 1)), false, fixture.log.logger,
            MetricsBuilder.FASTSTATS_FAILED);

        assertThat(context).isNotNull();
        contexts.add(context);
        assertThat(context.metrics()).isPresent();
        assertThat(context.errorTrackerService()).isEmpty();
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(savedHandler);
        assertThat(fixture.log.lines()).isEmpty();
    }

    @Test
    void startFastStatsWithErrorTrackingLeavesTheDefaultHandlerAsItFoundIt() throws IOException {
        fixture.writeFastStatsConfig(true, true, true);
        Thread.UncaughtExceptionHandler installed = (thread, error) -> {
        };
        for (Thread.UncaughtExceptionHandler before : Arrays.asList(null, installed)) {
            Thread.setDefaultUncaughtExceptionHandler(before);

            BukkitContext context = MetricsBuilder.startFastStats(fixture.plugin, MetricsFixture.FASTSTATS_TOKEN,
                List.of(), true, fixture.log.logger, MetricsBuilder.FASTSTATS_FAILED);

            assertThat(context).isNotNull();
            contexts.add(context);
            assertThat(context.errorTrackerService().orElseThrow().globalErrorTracker().isContextAttached())
                .isFalse();
            assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(before);
        }
        assertThat(fixture.log.lines()).isEmpty();
    }

    @Test
    void startFastStatsWarnsOnTheLoggerItIsGiven() {
        CapturedLog other = new CapturedLog();

        assertThat(MetricsBuilder.startFastStats(fixture.plugin, "bad", List.of(), true, other.logger,
            MetricsBuilder.FASTSTATS_FAILED)).isNull();

        assertThat(fixture.log.lines()).isEmpty();
        assertThat(other.lines()).hasSize(1);
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(savedHandler);
    }

    @Test
    void startReplacesTheEarlierTargetWhenFastStatsErrorTrackingStarts() throws IOException {
        fixture.writeFastStatsConfig(true, true, true);
        Metrics metrics = start(new MetricsBuilder(fixture.plugin, errors, true)
            .bStats(MetricsFixture.BSTATS_SERVICE_ID)
            .fastStats(MetricsFixture.FASTSTATS_TOKEN));

        errors.report("storage", new IllegalStateException("storage offline"));

        assertThat(delivered).isEmpty();
        metrics.shutdown();
        assertThat(errorBodies()).singleElement().asString()
            .contains("storage offline")
            .contains("\"source\":\"storage\"");
    }

    @Test
    void startClearsTheErrorSinkWhenOnlyBStatsStarts() throws IOException {
        fixture.enableBStats();
        Metrics metrics = start(new MetricsBuilder(fixture.plugin, errors, true)
            .bStats(MetricsFixture.BSTATS_SERVICE_ID));

        errors.report("storage", new IllegalStateException());

        assertThat(metrics.bStatsStarted()).isTrue();
        assertThat(delivered).isEmpty();
    }

    @Test
    void aReportHeldBeforeStartReachesFastStatsAtShutdown() throws IOException {
        fixture.writeFastStatsConfig(true, true, true);
        ErrorReports fresh = new ErrorReports();
        fresh.report("hook.example", new IllegalStateException("hook failed"));

        Metrics metrics = start(new MetricsBuilder(fixture.plugin, fresh, true)
            .fastStats(MetricsFixture.FASTSTATS_TOKEN));
        assertThat(sink.requests).isEmpty();
        metrics.shutdown();

        assertThat(errorBodies()).singleElement().asString()
            .contains("hook failed")
            .contains("\"source\":\"hook.example\"");
    }

    @Test
    void aReportHeldBeforeStartIsDiscardedWhenErrorTrackingDoesNotStart() throws IOException {
        fixture.writeFastStatsConfig(true, true, false);
        List<ErrorReports> sinks = List.of(new ErrorReports(), new ErrorReports(), new ErrorReports());
        sinks.forEach(fresh -> fresh.report("hook.example", new IllegalStateException("hook failed")));

        Metrics noErrorSubmission = start(new MetricsBuilder(fixture.plugin, sinks.get(0), true)
            .fastStats(MetricsFixture.FASTSTATS_TOKEN));
        start(new MetricsBuilder(fixture.plugin, sinks.get(1), false).fastStats(MetricsFixture.FASTSTATS_TOKEN));
        start(new MetricsBuilder(fixture.plugin, sinks.get(2), true).bStats(MetricsFixture.BSTATS_SERVICE_ID));

        assertThat(noErrorSubmission.fastStats).isNotNull();
        for (ErrorReports fresh : sinks) {
            List<String> received = new ArrayList<>();
            fresh.install((source, error) -> received.add(source));
            fresh.report("storage", new IllegalStateException());

            assertThat(received).containsExactly("storage");
        }
        noErrorSubmission.shutdown();
        assertThat(errorBodies()).isEmpty();
    }

    @Test
    void aSecondStartIsRefused() {
        MetricsBuilder builder = new MetricsBuilder(fixture.plugin, errors, false);
        builder.start();

        assertThatIllegalStateException().isThrownBy(builder::start).withMessage("metrics already started");
    }

    @Test
    void changingTheBuilderAfterStartIsRefused() {
        MetricsBuilder builder = new MetricsBuilder(fixture.plugin, errors, true);
        start(builder);

        assertThatIllegalStateException().isThrownBy(() -> builder.bStats(MetricsFixture.BSTATS_SERVICE_ID))
            .withMessage("metrics already started");
        assertThatIllegalStateException().isThrownBy(() -> builder.fastStats(MetricsFixture.FASTSTATS_TOKEN))
            .withMessage("metrics already started");
        assertThatIllegalStateException()
            .isThrownBy(() -> builder.addChart(MetricChart.number("item_count", () -> 1)))
            .withMessage("metrics already started");
    }

    @Test
    void startClearsTheErrorSinkWhenNothingStarts() {
        new MetricsBuilder(fixture.plugin, errors, true).start();

        errors.report("storage", new IllegalStateException());

        assertThat(delivered).isEmpty();
    }

    @Test
    void aSecondChartWithTheSameIdIsRejected() {
        MetricsBuilder builder = new MetricsBuilder(fixture.plugin, errors, true)
            .addChart(MetricChart.number("item_count", () -> 1));

        assertThatIllegalArgumentException()
            .isThrownBy(() -> builder.addChart(MetricChart.string("item_count", () -> "x")))
            .withMessage("duplicate chart id 'item_count'");
        assertThat(builder.addChart(MetricChart.number("other_count", () -> 2))).isSameAs(builder);
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThatNullPointerException()
            .isThrownBy(() -> new MetricsBuilder(null, errors, true))
            .withMessage("owner");
        assertThatNullPointerException()
            .isThrownBy(() -> new MetricsBuilder(fixture.plugin, null, true))
            .withMessage("errors");
        assertThatNullPointerException()
            .isThrownBy(() -> new MetricsBuilder(fixture.plugin, errors, true).addChart(null))
            .withMessage("chart");
    }

    @Test
    void theBuilderMethodsReturnTheBuilder() {
        MetricsBuilder builder = new MetricsBuilder(fixture.plugin, errors, true);

        assertThat(builder.bStats(1)).isSameAs(builder);
        assertThat(builder.fastStats(MetricsFixture.FASTSTATS_TOKEN)).isSameAs(builder);
        assertThat(builder.addChart(MetricChart.bool("feature_on", () -> true))).isSameAs(builder);
    }

    /** The bodies of the error submissions the sink received, in order. */
    private List<String> errorBodies() {
        return sink.requests.stream()
            .filter(request -> request.path().equals("/v1/error"))
            .map(LoopbackSink.Request::body)
            .toList();
    }

    /** The request JSON of every custom chart a started bStats instance holds, read from its private state. */
    private static List<String> chartsOf(org.bstats.bukkit.Metrics bStats) throws ReflectiveOperationException {
        Object base = field(org.bstats.bukkit.Metrics.class, "metricsBase").get(bStats);
        Collection<?> charts = (Collection<?>) field(base.getClass(), "customCharts").get(base);
        List<String> json = new ArrayList<>();
        for (Object chart : charts) {
            JsonObjectBuilder.JsonObject data = ((CustomChart) chart).getRequestJsonObject((message, error) -> {
            }, false);
            json.add(String.valueOf(data));
        }
        return json;
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
