package so.alaz.taffy.metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import so.alaz.taffy.test.JvmDefaults;

import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class MetricChartTest {

    @Test
    void stringSetsItsTypeAndSupplier() {
        Supplier<String> supplier = () -> "value";

        MetricChart chart = MetricChart.string("storage_type", supplier);

        assertThat(chart.id).isEqualTo("storage_type");
        assertThat(chart.type).isEqualTo(MetricChart.Type.STRING);
        assertThat(chart.stringSupplier).isSameAs(supplier);
        assertThat(chart.intSupplier).isNull();
        assertThat(chart.boolSupplier).isNull();
        assertThat(chart.listSupplier).isNull();
        assertThat(chart.counterSupplier).isNull();
    }

    @Test
    void numberSetsItsTypeAndSupplier() {
        Supplier<Integer> supplier = () -> 3;

        MetricChart chart = MetricChart.number("item_count", supplier);

        assertThat(chart.id).isEqualTo("item_count");
        assertThat(chart.type).isEqualTo(MetricChart.Type.NUMBER);
        assertThat(chart.intSupplier).isSameAs(supplier);
        assertThat(chart.stringSupplier).isNull();
        assertThat(chart.boolSupplier).isNull();
        assertThat(chart.listSupplier).isNull();
        assertThat(chart.counterSupplier).isNull();
    }

    @Test
    void boolSetsItsTypeAndSupplier() {
        Supplier<Boolean> supplier = () -> true;

        MetricChart chart = MetricChart.bool("feature_on", supplier);

        assertThat(chart.id).isEqualTo("feature_on");
        assertThat(chart.type).isEqualTo(MetricChart.Type.BOOL);
        assertThat(chart.boolSupplier).isSameAs(supplier);
        assertThat(chart.stringSupplier).isNull();
        assertThat(chart.intSupplier).isNull();
        assertThat(chart.listSupplier).isNull();
        assertThat(chart.counterSupplier).isNull();
    }

    @Test
    void stringListSetsItsTypeAndSupplier() {
        Supplier<List<String>> supplier = () -> List.of("a", "b");

        MetricChart chart = MetricChart.stringList("hooks", supplier);

        assertThat(chart.id).isEqualTo("hooks");
        assertThat(chart.type).isEqualTo(MetricChart.Type.STRING_LIST);
        assertThat(chart.listSupplier).isSameAs(supplier);
        assertThat(chart.stringSupplier).isNull();
        assertThat(chart.intSupplier).isNull();
        assertThat(chart.boolSupplier).isNull();
        assertThat(chart.counterSupplier).isNull();
    }

    @Test
    void counterSetsItsTypeAndTotal() {
        LongSupplier total = () -> 7L;

        MetricChart chart = MetricChart.counter("events_2", total);

        assertThat(chart.id).isEqualTo("events_2");
        assertThat(chart.type).isEqualTo(MetricChart.Type.COUNTER);
        assertThat(chart.counterSupplier).isSameAs(total);
        assertThat(chart.stringSupplier).isNull();
        assertThat(chart.intSupplier).isNull();
        assertThat(chart.boolSupplier).isNull();
        assertThat(chart.listSupplier).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Item_count", "item-count", "item count", " ", "", "items\n", "ıtems"})
    void everyFactoryRejectsAnIdOutsideThePattern(String id) {
        String message = "invalid chart id '" + id + "': expected ^[a-z0-9_]+$";

        assertThatIllegalArgumentException().isThrownBy(() -> MetricChart.string(id, () -> "v")).withMessage(message);
        assertThatIllegalArgumentException().isThrownBy(() -> MetricChart.number(id, () -> 1)).withMessage(message);
        assertThatIllegalArgumentException().isThrownBy(() -> MetricChart.bool(id, () -> true)).withMessage(message);
        assertThatIllegalArgumentException().isThrownBy(() -> MetricChart.stringList(id, List::of))
            .withMessage(message);
        assertThatIllegalArgumentException().isThrownBy(() -> MetricChart.counter(id, () -> 0L)).withMessage(message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"client", "core_count", "java_vendor", "java_version", "os_arch", "os_name", "os_version",
        "game_version", "online_mode", "player_count", "plugin_version", "server_type", "platform_version"})
    void everyFactoryRejectsAnIdFastStatsFillsItself(String id) {
        String message = "chart id '" + id + "' is reserved by FastStats";

        assertThatIllegalArgumentException().isThrownBy(() -> MetricChart.string(id, () -> "v")).withMessage(message);
        assertThatIllegalArgumentException().isThrownBy(() -> MetricChart.number(id, () -> 1)).withMessage(message);
        assertThatIllegalArgumentException().isThrownBy(() -> MetricChart.bool(id, () -> true)).withMessage(message);
        assertThatIllegalArgumentException().isThrownBy(() -> MetricChart.stringList(id, List::of))
            .withMessage(message);
        assertThatIllegalArgumentException().isThrownBy(() -> MetricChart.counter(id, () -> 0L)).withMessage(message);
    }

    @Test
    void anIdThatOnlyStartsWithAReservedIdIsAccepted() {
        assertThat(MetricChart.number("player_count_peak", () -> 1).id).isEqualTo("player_count_peak");
        assertThat(MetricChart.string("server_types", () -> "v").id).isEqualTo("server_types");
    }

    @Test
    void everyFactoryRejectsANullId() {
        assertThatNullPointerException().isThrownBy(() -> MetricChart.string(null, () -> "v")).withMessage("id");
        assertThatNullPointerException().isThrownBy(() -> MetricChart.number(null, () -> 1)).withMessage("id");
        assertThatNullPointerException().isThrownBy(() -> MetricChart.bool(null, () -> true)).withMessage("id");
        assertThatNullPointerException().isThrownBy(() -> MetricChart.stringList(null, List::of)).withMessage("id");
        assertThatNullPointerException().isThrownBy(() -> MetricChart.counter(null, () -> 0L)).withMessage("id");
    }

    @Test
    void everyFactoryRejectsANullSupplier() {
        assertThatNullPointerException().isThrownBy(() -> MetricChart.string("id", null)).withMessage("supplier");
        assertThatNullPointerException().isThrownBy(() -> MetricChart.number("id", null)).withMessage("supplier");
        assertThatNullPointerException().isThrownBy(() -> MetricChart.bool("id", null)).withMessage("supplier");
        assertThatNullPointerException().isThrownBy(() -> MetricChart.stringList("id", null)).withMessage("supplier");
        assertThatNullPointerException().isThrownBy(() -> MetricChart.counter("id", null)).withMessage("total");
    }

    @Test
    void theIdCheckIgnoresTheDefaultLocale() throws Exception {
        for (Locale locale : List.of(JvmDefaults.TURKISH, Locale.ROOT)) {
            JvmDefaults.withLocale(locale, () -> {
                assertThat(MetricChart.string("title_items", () -> "v").id).isEqualTo("title_items");
                assertThatIllegalArgumentException().isThrownBy(() -> MetricChart.string("TITLE_ITEMS", () -> "v"));
                assertThatIllegalArgumentException().isThrownBy(() -> MetricChart.string("tıtle", () -> "v"));
            });
        }
    }
}
