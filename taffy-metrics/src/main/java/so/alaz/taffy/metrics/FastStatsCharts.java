package so.alaz.taffy.metrics;

import dev.faststats.Metrics;
import dev.faststats.data.Metric;

import java.util.ArrayList;
import java.util.List;

/** Converts {@link MetricChart}s to FastStats metrics. */
final class FastStatsCharts {

    private FastStatsCharts() {
    }

    /**
     * Adds one metric per chart to {@code factory}, in order. When at least one chart is a counter, it also
     * adds one {@code onFlush} callback that commits every counter's mark.
     *
     * @return the factory the last call returned
     */
    static Metrics.Factory addTo(Metrics.Factory factory, List<MetricChart> charts) {
        var built = factory;
        List<CounterMark> counters = new ArrayList<>();
        for (MetricChart chart : charts) {
            built = built.addMetric(toMetric(chart, counters));
        }
        if (!counters.isEmpty()) {
            // Runs only after an accepted submission, on the thread that read the metrics.
            built = built.onFlush(() -> counters.forEach(CounterMark::commit));
        }
        return built;
    }

    /** Converts a chart; a counter's mark is added to {@code counters} so {@code onFlush} can commit it. */
    private static Metric<?> toMetric(MetricChart chart, List<CounterMark> counters) {
        return switch (chart.type) {
            case STRING -> Metric.string(chart.id, () -> chart.stringSupplier.get());
            case NUMBER -> Metric.number(chart.id, () -> chart.intSupplier.get());
            case BOOL -> Metric.bool(chart.id, () -> chart.boolSupplier.get());
            case STRING_LIST -> Metric.stringArray(chart.id, () -> chart.listSupplier.get().toArray(String[]::new));
            case COUNTER -> {
                // FastStats also runs every supplier when it assembles an error report, on a separate task, so
                // a read must not advance the mark: it moves only when onFlush reports an accepted submission.
                CounterMark mark = new CounterMark(chart.counterSupplier);
                counters.add(mark);
                yield Metric.number(chart.id, mark::peekDelta);
            }
        };
    }
}
