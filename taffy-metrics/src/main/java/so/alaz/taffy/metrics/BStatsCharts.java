package so.alaz.taffy.metrics;

import org.bstats.charts.AdvancedPie;
import org.bstats.charts.CustomChart;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Converts {@link MetricChart}s to bStats charts. */
final class BStatsCharts {

    private BStatsCharts() {
    }

    /** The bStats chart for {@code chart}; a counter gets a mark of its own, advanced on each read. */
    static CustomChart toChart(MetricChart chart) {
        return switch (chart.type) {
            case STRING -> new SimplePie(chart.id, () -> chart.stringSupplier.get());
            case NUMBER -> new SingleLineChart(chart.id, () -> chart.intSupplier.get());
            case BOOL -> new SimplePie(chart.id, () -> {
                Boolean value = chart.boolSupplier.get();
                return value == null ? null : value.toString();
            });
            case STRING_LIST -> new AdvancedPie(chart.id, () -> frequency(chart.listSupplier.get()));
            case COUNTER -> {
                // bStats reads each chart once per submission and has no success callback: advance on read.
                CounterMark mark = new CounterMark(chart.counterSupplier);
                yield new SingleLineChart(chart.id, () -> (int) Math.min(Integer.MAX_VALUE, mark.takeDelta()));
            }
        };
    }

    /** How often each value occurs in {@code values}. */
    static Map<String, Integer> frequency(List<String> values) {
        Map<String, Integer> counts = new HashMap<>();
        for (String value : values) {
            counts.merge(value, 1, Integer::sum);
        }
        return counts;
    }
}
