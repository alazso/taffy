package so.alaz.taffy.metrics;

import org.bstats.charts.CustomChart;
import org.bstats.json.JsonObjectBuilder;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BStatsChartsTest {

    private final List<Throwable> logged = new ArrayList<>();

    @Test
    void aStringChartSendsItsValue() {
        assertThat(read(BStatsCharts.toChart(MetricChart.string("storage_type", () -> "sqlite"))))
            .isEqualTo("{\"chartId\":\"storage_type\",\"data\":{\"value\":\"sqlite\"}}");
    }

    @Test
    void aNullOrEmptyStringIsLeftOut() {
        assertThat(read(BStatsCharts.toChart(MetricChart.string("storage_type", () -> null)))).isNull();
        assertThat(read(BStatsCharts.toChart(MetricChart.string("storage_type", () -> "")))).isNull();
    }

    @Test
    void aNumberChartSendsItsValue() {
        assertThat(read(BStatsCharts.toChart(MetricChart.number("item_count", () -> 42))))
            .isEqualTo("{\"chartId\":\"item_count\",\"data\":{\"value\":42}}");
    }

    @Test
    void aZeroOrNullNumberIsLeftOut() {
        assertThat(read(BStatsCharts.toChart(MetricChart.number("item_count", () -> 0)))).isNull();
        assertThat(read(BStatsCharts.toChart(MetricChart.number("item_count", () -> null)))).isNull();
    }

    @Test
    void aBoolChartSendsTrueOrFalseAsText() {
        assertThat(read(BStatsCharts.toChart(MetricChart.bool("feature_on", () -> true))))
            .isEqualTo("{\"chartId\":\"feature_on\",\"data\":{\"value\":\"true\"}}");
        assertThat(read(BStatsCharts.toChart(MetricChart.bool("feature_on", () -> false))))
            .isEqualTo("{\"chartId\":\"feature_on\",\"data\":{\"value\":\"false\"}}");
    }

    @Test
    void aNullBoolIsLeftOut() {
        CustomChart chart = BStatsCharts.toChart(MetricChart.bool("feature_on", () -> null));

        assertThat(chart.getRequestJsonObject((message, error) -> logged.add(error), true)).isNull();
        assertThat(logged).isEmpty();
    }

    @Test
    void aStringListChartSendsHowOftenEachValueOccurs() {
        assertThat(read(BStatsCharts.toChart(MetricChart.stringList("hooks", () -> List.of("b", "a", "b", "b")))))
            .isEqualTo("{\"chartId\":\"hooks\",\"data\":{\"values\":{\"a\":1,\"b\":3}}}");
    }

    @Test
    void anEmptyStringListIsLeftOut() {
        assertThat(read(BStatsCharts.toChart(MetricChart.stringList("hooks", List::of)))).isNull();
    }

    @Test
    void frequencyCountsEachValue() {
        assertThat(BStatsCharts.frequency(List.of("x", "y", "x"))).isEqualTo(Map.of("x", 2, "y", 1));
        assertThat(BStatsCharts.frequency(List.of())).isEmpty();
    }

    @Test
    void aCounterSendsTheIncreaseSinceItsLastReadAndAdvancesOnRead() {
        AtomicLong total = new AtomicLong(5);
        CustomChart chart = BStatsCharts.toChart(MetricChart.counter("events", total::get));

        assertThat(read(chart)).isEqualTo("{\"chartId\":\"events\",\"data\":{\"value\":5}}");
        total.set(8);
        assertThat(read(chart)).isEqualTo("{\"chartId\":\"events\",\"data\":{\"value\":3}}");
        assertThat(read(chart)).isNull();
    }

    @Test
    void eachConversionOfACounterHasItsOwnMark() {
        AtomicLong total = new AtomicLong(4);
        MetricChart counter = MetricChart.counter("events", total::get);
        CustomChart first = BStatsCharts.toChart(counter);
        CustomChart second = BStatsCharts.toChart(counter);

        assertThat(read(first)).isEqualTo("{\"chartId\":\"events\",\"data\":{\"value\":4}}");
        assertThat(read(second)).isEqualTo("{\"chartId\":\"events\",\"data\":{\"value\":4}}");
    }

    @Test
    void aCounterIncreasePastTheIntRangeIsClamped() {
        CustomChart chart = BStatsCharts.toChart(MetricChart.counter("events", () -> Integer.MAX_VALUE + 10L));

        assertThat(read(chart)).isEqualTo("{\"chartId\":\"events\",\"data\":{\"value\":" + Integer.MAX_VALUE + "}}");
    }

    @Test
    void aThrowingSupplierIsLeftOutAndPassedToTheErrorLogger() {
        IllegalStateException failure = new IllegalStateException("broken");
        AtomicReference<String> message = new AtomicReference<>();
        CustomChart chart = BStatsCharts.toChart(MetricChart.string("storage_type", () -> {
            throw failure;
        }));

        JsonObjectBuilder.JsonObject json = chart.getRequestJsonObject((text, error) -> {
            message.set(text);
            logged.add(error);
        }, true);

        assertThat(json).isNull();
        assertThat(message.get()).contains("storage_type");
        assertThat(logged).containsExactly(failure);
    }

    @Test
    void aThrowingSupplierOfEveryTypeIsLeftOut() {
        List<MetricChart> charts = List.of(
            MetricChart.string("a", () -> {
                throw new IllegalStateException();
            }),
            MetricChart.number("b", () -> {
                throw new IllegalStateException();
            }),
            MetricChart.bool("c", () -> {
                throw new IllegalStateException();
            }),
            MetricChart.stringList("d", () -> {
                throw new IllegalStateException();
            }),
            MetricChart.counter("e", () -> {
                throw new IllegalStateException();
            }));

        assertThat(charts).allSatisfy(chart -> assertThat(read(BStatsCharts.toChart(chart))).isNull());
    }

    private @Nullable String read(CustomChart chart) {
        JsonObjectBuilder.JsonObject json = chart.getRequestJsonObject((message, error) -> logged.add(error), false);
        return json == null ? null : json.toString();
    }
}
