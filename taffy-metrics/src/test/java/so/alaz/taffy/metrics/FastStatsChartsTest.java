package so.alaz.taffy.metrics;

import dev.faststats.data.Metric;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class FastStatsChartsTest {

    private final RecordingFactory factory = new RecordingFactory();

    @Test
    void everyChartTypeBecomesAMetricWithItsIdAndValue() throws Exception {
        factory.add(List.of(
            MetricChart.string("storage_type", () -> "sqlite"),
            MetricChart.number("item_count", () -> 42),
            MetricChart.bool("feature_on", () -> true),
            MetricChart.stringList("hooks", () -> List.of("b", "a", "b")),
            MetricChart.counter("events", () -> 6L)));

        assertThat(factory.metrics).extracting(Metric::getId)
            .containsExactly("storage_type", "item_count", "feature_on", "hooks", "events");
        assertThat(data(0)).isEqualTo("\"sqlite\"");
        assertThat(data(1)).isEqualTo("42");
        assertThat(data(2)).isEqualTo("true");
        assertThat(data(3)).isEqualTo("[\"b\",\"a\",\"b\"]");
        assertThat(data(4)).isEqualTo("6");
    }

    @Test
    void aFalseBoolIsSent() throws Exception {
        factory.add(List.of(MetricChart.bool("feature_on", () -> false)));

        assertThat(data(0)).isEqualTo("false");
    }

    @Test
    void aNullValueIsLeftOut() throws Exception {
        factory.add(List.of(
            MetricChart.string("storage_type", () -> null),
            MetricChart.number("item_count", () -> null),
            MetricChart.bool("feature_on", () -> null)));

        assertThat(data(0)).isNull();
        assertThat(data(1)).isNull();
        assertThat(data(2)).isNull();
    }

    @Test
    void aThrowingSupplierThrowsFromTheMetricRead() {
        factory.add(List.of(MetricChart.number("item_count", () -> {
            throw new IllegalStateException("broken");
        })));

        assertThatIllegalStateException().isThrownBy(() -> factory.metrics.getFirst().getData()).withMessage("broken");
    }

    @Test
    void noFlushCallbackWithoutACounter() {
        factory.add(List.of(
            MetricChart.string("storage_type", () -> "sqlite"),
            MetricChart.number("item_count", () -> 1)));

        assertThat(factory.flushes).isEmpty();
    }

    @Test
    void noChartsAddNothing() {
        factory.add(List.of());

        assertThat(factory.metrics).isEmpty();
        assertThat(factory.flushes).isEmpty();
    }

    @Test
    void aCounterReadsTheSameIncreaseUntilTheFlushRuns() throws Exception {
        AtomicLong total = new AtomicLong(5);
        factory.add(List.of(MetricChart.counter("events", total::get)));

        assertThat(factory.flushes).hasSize(1);
        assertThat(data(0)).isEqualTo("5");
        assertThat(data(0)).isEqualTo("5");
        factory.flushes.getFirst().run();
        assertThat(data(0)).isEqualTo("0");
    }

    @Test
    void anIncrementBetweenTheReadAndTheFlushIsReportedNextTime() throws Exception {
        AtomicLong total = new AtomicLong(5);
        factory.add(List.of(MetricChart.counter("events", total::get)));

        assertThat(data(0)).isEqualTo("5");
        total.set(9);
        factory.flushes.getFirst().run();

        assertThat(data(0)).isEqualTo("4");
    }

    @Test
    void oneFlushCommitsEveryCounter() throws Exception {
        AtomicLong first = new AtomicLong(3);
        AtomicLong second = new AtomicLong(7);
        factory.add(List.of(
            MetricChart.counter("first_events", first::get),
            MetricChart.string("storage_type", () -> "sqlite"),
            MetricChart.counter("second_events", second::get)));

        assertThat(factory.flushes).hasSize(1);
        assertThat(data(0)).isEqualTo("3");
        assertThat(data(2)).isEqualTo("7");
        factory.flushes.getFirst().run();

        assertThat(data(0)).isEqualTo("0");
        assertThat(data(2)).isEqualTo("0");
    }

    @Test
    void eachConversionOfACounterHasItsOwnMark() throws Exception {
        MetricChart counter = MetricChart.counter("events", () -> 4L);
        RecordingFactory other = new RecordingFactory();
        factory.add(List.of(counter));
        other.add(List.of(counter));

        assertThat(data(0)).isEqualTo("4");
        factory.flushes.getFirst().run();

        assertThat(data(0)).isEqualTo("0");
        assertThat(other.metrics.getFirst().getData()).map(Object::toString).contains("4");
    }

    private @Nullable String data(int index) throws Exception {
        return factory.metrics.get(index).getData().map(Object::toString).orElse(null);
    }

    /** Records the metrics and the flush callback of each conversion, as a FastStats metrics factory receives them. */
    private static final class RecordingFactory {

        final List<Metric<?>> metrics = new ArrayList<>();
        final List<Runnable> flushes = new ArrayList<>();

        void add(List<MetricChart> charts) {
            FastStatsCharts.Converted converted = FastStatsCharts.convert(charts);
            metrics.addAll(converted.metrics());
            if (converted.flush() != null) {
                flushes.add(converted.flush());
            }
        }
    }
}
