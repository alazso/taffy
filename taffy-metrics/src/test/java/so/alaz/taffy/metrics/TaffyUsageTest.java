package so.alaz.taffy.metrics;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bstats.charts.CustomChart;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaffyUsageTest {

    @TempDir
    Path plugins;

    private MetricsFixture fixture;
    private LoopbackSink sink;
    private Thread.@Nullable UncaughtExceptionHandler savedHandler;
    private final List<TaffyUsage> started = new ArrayList<>();

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
        started.forEach(TaffyUsage::shutdown);
        sink.close();
        System.clearProperty("faststats.first-run");
        Thread.setDefaultUncaughtExceptionHandler(savedHandler);
    }

    /** Starts usage metrics over the fixture with the test credentials and shuts them down after the test. */
    private TaffyUsage startWithTestCredentials() {
        TaffyUsage usage = TaffyUsage.start(fixture.plugin, MetricsFixture.BSTATS_SERVICE_ID,
            MetricsFixture.FASTSTATS_TOKEN);
        started.add(usage);
        return usage;
    }

    @Test
    void taffyShipsWithItsCredentialsUnset() {
        assertThat(TaffyUsage.BSTATS_SERVICE_ID).isZero();
        assertThat(TaffyUsage.FASTSTATS_TOKEN).isEmpty();
    }

    @Test
    void theUnsetCredentialsStartNothing() throws IOException {
        fixture.writeFastStatsConfig(true, true, true);
        String fastStatsConfig = Files.readString(fixture.fastStatsConfig);

        TaffyUsage usage = TaffyUsage.start(fixture.plugin);

        assertThat(usage.bStats).isNull();
        assertThat(usage.fastStats).isNull();
        assertThat(fixture.bStatsConfigText()).isEqualTo(MetricsFixture.BSTATS_CONFIG);
        assertThat(Files.readString(fixture.fastStatsConfig)).isEqualTo(fastStatsConfig);
        assertThat(plugins.resolve("Owner")).doesNotExist();
        verify(fixture.plugin, never()).getDataFolder();
        verify(fixture.pluginManager, never()).registerEvents(any(Listener.class), any(Plugin.class));
        verify(fixture.asyncScheduler, never())
            .runAtFixedRate(any(Plugin.class), any(), anyLong(), anyLong(), any(TimeUnit.class));
        assertThat(fixture.log.lines()).isEmpty();
        assertThatCode(usage::shutdown).doesNotThrowAnyException();
        assertThatCode(usage::shutdown).doesNotThrowAnyException();
        assertThat(sink.requests).isEmpty();
    }

    @Test
    void aBlankTokenAndAServiceIdOfZeroOrBelowStartNothing() {
        for (int serviceId : new int[] {0, -1}) {
            TaffyUsage usage = TaffyUsage.start(fixture.plugin, serviceId, "  ");

            assertThat(usage.bStats).isNull();
            assertThat(usage.fastStats).isNull();
        }
        assertThat(plugins.resolve("faststats")).doesNotExist();
        assertThat(fixture.log.lines()).isEmpty();
    }

    @Test
    void testCredentialsStartBothProvidersWithMetricsOnly() throws IOException {
        fixture.writeFastStatsConfig(true, true, true);

        TaffyUsage usage = startWithTestCredentials();

        assertThat(usage.bStats).isNotNull();
        assertThat(usage.fastStats).isNotNull();
        assertThat(usage.fastStats.metrics()).isPresent();
        assertThat(usage.fastStats.errorTrackerService()).isEmpty();
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(savedHandler);
        verify(fixture.asyncScheduler, times(1))
            .runAtFixedRate(any(Plugin.class), any(), anyLong(), anyLong(), any(TimeUnit.class));
        assertThat(fixture.bStatsConfigText()).isEqualTo(MetricsFixture.BSTATS_CONFIG);
        assertThat(sink.requests).isEmpty();
        assertThat(fixture.log.lines()).isEmpty();
    }

    @Test
    void theVersionIsAVersionNumber() {
        assertThat(TaffyVersion.VERSION).matches("\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.]+)?");
    }

    @Test
    void theVersionChartReadsTheTaffyVersionOnBStats() throws ReflectiveOperationException {
        String expected = "{\"chartId\":\"taffy_version\",\"data\":{\"value\":\"" + TaffyVersion.VERSION + "\"}}";

        assertThat(String.valueOf(BStatsCharts.toChart(TaffyUsage.versionChart())
            .getRequestJsonObject((message, error) -> {
            }, false))).isEqualTo(expected);

        TaffyUsage usage = startWithTestCredentials();
        assertThat(usage.bStats).isNotNull();
        assertThat(chartsOf(usage.bStats)).containsExactly(expected);
    }

    @Test
    void theVersionChartReachesFastStats() throws IOException {
        fixture.writeFastStatsConfig(true, true, true);
        startWithTestCredentials();
        ArgumentCaptor<Consumer<ScheduledTask>> scheduled = ArgumentCaptor.captor();
        verify(fixture.asyncScheduler)
            .runAtFixedRate(any(Plugin.class), scheduled.capture(), anyLong(), anyLong(), any(TimeUnit.class));

        scheduled.getValue().accept(fixture.task);

        assertThat(sink.requests).singleElement().satisfies(request -> {
            assertThat(request.path()).isEqualTo("/v1/collect");
            assertThat(request.body())
                .contains("\"taffy_version\":\"" + TaffyVersion.VERSION + "\"")
                .contains("\"project_name\":\"Owner\"");
        });
    }

    @Test
    void shutdownAfterReadySendsNothing() throws IOException {
        fixture.writeFastStatsConfig(true, true, true);
        TaffyUsage usage = startWithTestCredentials();
        assertThat(usage.fastStats).isNotNull();
        assertThat(usage.fastStats.submissionActive).isTrue();

        usage.shutdown();
        usage.shutdown();

        assertThat(usage.fastStats.submissionActive).isFalse();
        assertThat(sink.requests).isEmpty();
        verify(fixture.task).cancel();
    }

    @Test
    void aStartedUsageRunsOneBStatsThreadThatShutdownEnds() throws IOException, InterruptedException {
        fixture.enableBStats();
        Set<Thread> before = bStatsThreads();

        TaffyUsage usage = startWithTestCredentials();

        Set<Thread> added = bStatsThreads();
        added.removeAll(before);
        assertThat(added).hasSize(1).allSatisfy(thread -> assertThat(thread.isDaemon()).isTrue());
        usage.shutdown();
        for (Thread thread : added) {
            thread.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(thread.isAlive()).as(thread.getName()).isFalse();
        }
    }

    @Test
    void aFastStatsShutdownThatThrowsStillStopsBStats() throws IOException, InterruptedException {
        fixture.enableBStats();
        fixture.writeFastStatsConfig(true, true, true);
        Set<Thread> before = bStatsThreads();
        TaffyUsage usage = startWithTestCredentials();
        Set<Thread> added = bStatsThreads();
        added.removeAll(before);
        when(fixture.task.cancel()).thenThrow(new IllegalStateException("cancel refused"));

        assertThatCode(usage::shutdown).doesNotThrowAnyException();

        for (Thread thread : added) {
            thread.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(thread.isAlive()).as(thread.getName()).isFalse();
        }
        assertThat(sink.requests).isEmpty();
    }

    @Test
    void aBStatsThatCannotStartLogsOneUsageWarningAndFastStatsStillStarts() throws IOException {
        fixture.writeFastStatsConfig(true, true, true);
        when(fixture.plugin.getDataFolder()).thenReturn(null);

        TaffyUsage usage = startWithTestCredentials();

        assertThat(usage.bStats).isNull();
        assertThat(usage.fastStats).isNotNull();
        assertThat(fixture.log.lines()).singleElement().asString()
            .startsWith("WARNING Taffy usage metrics failed to start on bStats: ");
    }

    @Test
    void aMalformedTokenLogsOneUsageWarningAndBStatsStillStarts() {
        TaffyUsage usage = TaffyUsage.start(fixture.plugin, MetricsFixture.BSTATS_SERVICE_ID, "Not-A-Token");
        started.add(usage);

        assertThat(usage.bStats).isNotNull();
        assertThat(usage.fastStats).isNull();
        assertThat(fixture.log.lines()).containsExactly("WARNING Taffy usage metrics failed to start on FastStats: "
            + "Invalid token 'Not-A-Token', must match '[a-z0-9]{32}'");
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(savedHandler);
    }

    /** The live threads bStats schedules its submissions on. */
    private static Set<Thread> bStatsThreads() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.getName().equals("bStats-Metrics"))
            .collect(Collectors.toCollection(HashSet::new));
    }

    /** The request JSON of every custom chart a started bStats instance holds, read from its private state. */
    private static List<String> chartsOf(org.bstats.bukkit.Metrics bStats) throws ReflectiveOperationException {
        Object base = field(org.bstats.bukkit.Metrics.class, "metricsBase").get(bStats);
        Collection<?> charts = (Collection<?>) field(base.getClass(), "customCharts").get(base);
        List<String> json = new ArrayList<>();
        for (Object chart : charts) {
            json.add(String.valueOf(((CustomChart) chart).getRequestJsonObject((message, error) -> {
            }, false)));
        }
        return json;
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
