package so.alaz.taffy.metrics;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * A provider-agnostic custom metric, defined once and reported to every metrics provider the plugin
 * starts: as a bStats chart and as a FastStats metric. A plugin adds it with
 * {@link MetricsBuilder#addChart(MetricChart)}.
 *
 * <p>Every id matches {@code ^[a-z0-9_]+$} and is not one of the ids FastStats fills itself in every report,
 * which it would keep in place of the chart: {@code client}, {@code core_count}, {@code java_vendor},
 * {@code java_version}, {@code os_arch}, {@code os_name}, {@code os_version}, {@code game_version},
 * {@code online_mode}, {@code player_count}, {@code plugin_version}, {@code server_type} and
 * {@code platform_version}. A factory given another id throws {@link IllegalArgumentException}, and a null id
 * or supplier throws {@link NullPointerException}.
 *
 * <p>The supplier contract, for every factory:
 * <ul>
 *   <li>A supplier only reads in-memory state. It never resets what it reads, never blocks and never
 *       touches storage.</li>
 *   <li>It may run on any thread: bStats reads it on the server's main thread (its own thread on Folia),
 *       FastStats on its asynchronous submission task, again whenever it assembles an error report, and
 *       once more during the final send at disable, which may come after the plugin's storage has
 *       closed.</li>
 *   <li>A supplier that throws makes that value absent from that report. On FastStats the throwable is
 *       also reported to the plugin's global error tracker, when FastStats error tracking is on, on every
 *       read that throws.</li>
 * </ul>
 */
public final class MetricChart {

    private static final Pattern ID = Pattern.compile("^[a-z0-9_]+$");

    /** The ids FastStats writes into every report before the custom metrics; it skips a chart with one of them. */
    static final Set<String> RESERVED = Set.of("client", "core_count", "java_vendor", "java_version", "os_arch",
        "os_name", "os_version", "game_version", "online_mode", "player_count", "plugin_version", "server_type",
        "platform_version");

    enum Type {STRING, NUMBER, BOOL, STRING_LIST, COUNTER}

    final String id;
    final Type type;
    @Nullable final Supplier<@Nullable String> stringSupplier;
    @Nullable final Supplier<@Nullable Integer> intSupplier;
    @Nullable final Supplier<@Nullable Boolean> boolSupplier;
    @Nullable final Supplier<List<String>> listSupplier;
    @Nullable final LongSupplier counterSupplier;

    private MetricChart(String id, Type type, @Nullable Supplier<@Nullable String> stringSupplier,
                        @Nullable Supplier<@Nullable Integer> intSupplier,
                        @Nullable Supplier<@Nullable Boolean> boolSupplier,
                        @Nullable Supplier<List<String>> listSupplier, @Nullable LongSupplier counterSupplier) {
        this.id = id;
        this.type = type;
        this.stringSupplier = stringSupplier;
        this.intSupplier = intSupplier;
        this.boolSupplier = boolSupplier;
        this.listSupplier = listSupplier;
        this.counterSupplier = counterSupplier;
    }

    /**
     * A single string value (bStats SimplePie, FastStats string metric). A null value is left out of the
     * report; bStats also leaves out an empty string.
     *
     * @param id the chart id, matching {@code ^[a-z0-9_]+$}
     * @param supplier reads the value, following the supplier contract of this class
     * @return the chart
     * @throws IllegalArgumentException if the id does not match the pattern or is reserved
     */
    public static MetricChart string(String id, Supplier<@Nullable String> supplier) {
        return new MetricChart(checkedId(id), Type.STRING, Objects.requireNonNull(supplier, "supplier"),
            null, null, null, null);
    }

    /**
     * A single integer value, reported as is (bStats SingleLineChart, FastStats number metric). A null
     * value is left out of the report; bStats also leaves out 0.
     *
     * @param id the chart id, matching {@code ^[a-z0-9_]+$}
     * @param supplier reads the value, following the supplier contract of this class
     * @return the chart
     * @throws IllegalArgumentException if the id does not match the pattern or is reserved
     */
    public static MetricChart number(String id, Supplier<@Nullable Integer> supplier) {
        return new MetricChart(checkedId(id), Type.NUMBER, null, Objects.requireNonNull(supplier, "supplier"),
            null, null, null);
    }

    /**
     * A boolean value (bStats SimplePie of {@code true} or {@code false}, FastStats bool metric). A null
     * value is left out of the report.
     *
     * @param id the chart id, matching {@code ^[a-z0-9_]+$}
     * @param supplier reads the value, following the supplier contract of this class
     * @return the chart
     * @throws IllegalArgumentException if the id does not match the pattern or is reserved
     */
    public static MetricChart bool(String id, Supplier<@Nullable Boolean> supplier) {
        return new MetricChart(checkedId(id), Type.BOOL, null, null, Objects.requireNonNull(supplier, "supplier"),
            null, null);
    }

    /**
     * A list of strings (bStats AdvancedPie of how often each string occurs, FastStats string array
     * metric). The supplier returns an empty list, never null, when there is nothing to report.
     *
     * @param id the chart id, matching {@code ^[a-z0-9_]+$}
     * @param supplier reads the values, following the supplier contract of this class
     * @return the chart
     * @throws IllegalArgumentException if the id does not match the pattern or is reserved
     */
    public static MetricChart stringList(String id, Supplier<List<String>> supplier) {
        return new MetricChart(checkedId(id), Type.STRING_LIST, null, null, null,
            Objects.requireNonNull(supplier, "supplier"), null);
    }

    /**
     * An event count backed by a monotonically increasing {@code total} (bStats SingleLineChart,
     * FastStats number metric). The total is a running sum that is never reset, such as
     * {@code LongAdder::sum}. Each provider keeps its own position on the total, so the charts show activity
     * per interval and no event is split between providers. bStats reports the increase since it last read the
     * chart: an increase it read for a submission that then failed is lost. FastStats reports the increase
     * since its last accepted submission: an increase in a submission that arrived without FastStats seeing it
     * accepted is reported again in the next one. A total that goes down reports 0 for that interval.
     *
     * @param id the chart id, matching {@code ^[a-z0-9_]+$}
     * @param total reads the running total, following the supplier contract of this class
     * @return the chart
     * @throws IllegalArgumentException if the id does not match the pattern or is reserved
     */
    public static MetricChart counter(String id, LongSupplier total) {
        return new MetricChart(checkedId(id), Type.COUNTER, null, null, null, null,
            Objects.requireNonNull(total, "total"));
    }

    private static String checkedId(String id) {
        Objects.requireNonNull(id, "id");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid chart id '" + id + "': expected ^[a-z0-9_]+$");
        }
        if (RESERVED.contains(id)) {
            throw new IllegalArgumentException("chart id '" + id + "' is reserved by FastStats");
        }
        return id;
    }
}
