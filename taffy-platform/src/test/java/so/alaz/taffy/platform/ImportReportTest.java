package so.alaz.taffy.platform;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ImportReportTest {

    @Test
    void anEmptyReportCountsNothingAndHoldsNoLine() {
        ImportReport report = ImportReport.builder().build();

        assertThat(report.read()).isZero();
        assertThat(report.importedCount()).isZero();
        assertThat(report.skippedCount()).isZero();
        assertThat(report.imported()).isEmpty();
        assertThat(report.skipped()).isEmpty();
        assertThat(report.warnings()).isEmpty();
    }

    @Test
    void aLineCountsOneEntryAndKeepsItsTextAndOrder() {
        ImportReport report = ImportReport.builder()
            .read(2).read(1)
            .imported("<red>gold").imported("silver")
            .skipped("vip: the id is taken")
            .warning("options.glow has no equivalent").warning("&cold")
            .build();

        assertThat(report.read()).isEqualTo(3);
        assertThat(report.imported()).containsExactly("<red>gold", "silver");
        assertThat(report.importedCount()).isEqualTo(2);
        assertThat(report.skipped()).containsExactly("vip: the id is taken");
        assertThat(report.skippedCount()).isEqualTo(1);
        assertThat(report.warnings()).containsExactly("options.glow has no equivalent", "&cold");
    }

    @Test
    void entriesCountedWithoutALineAddToTheOnesWithALine() {
        ImportReport report = ImportReport.builder()
            .imported(40).imported("gold").imported(2)
            .skipped(5).skipped("vip: the id is taken").skipped(0)
            .build();

        assertThat(report.importedCount()).isEqualTo(43);
        assertThat(report.imported()).containsExactly("gold");
        assertThat(report.skippedCount()).isEqualTo(6);
        assertThat(report.skipped()).containsExactly("vip: the id is taken");
    }

    @Test
    void aBuiltReportDoesNotChangeWhenTheBuilderIsAddedToAndItsListsCannotBeChanged() {
        ImportReport.Builder builder = ImportReport.builder().read(1).imported("gold");
        ImportReport first = builder.build();

        ImportReport second = builder.read(1).imported("silver").skipped("vip").warning("careful").build();

        assertThat(first.read()).isEqualTo(1);
        assertThat(first.imported()).containsExactly("gold");
        assertThat(first.skipped()).isEmpty();
        assertThat(first.warnings()).isEmpty();
        assertThat(second.read()).isEqualTo(2);
        assertThat(second.imported()).containsExactly("gold", "silver");
        assertThatThrownBy(() -> second.imported().add("more")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> second.skipped().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> second.warnings().add("more")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aNullLineAndANegativeCountAreRefused() {
        ImportReport.Builder builder = ImportReport.builder();

        assertThatThrownBy(() -> builder.imported((String) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.skipped((String) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.warning(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.read(-1)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("count is negative: -1");
        assertThatThrownBy(() -> builder.imported(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.skipped(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(builder.build()).isEqualTo(ImportReport.builder().build());
    }

    @Test
    void aCountPastTheLargestIntIsRefused() {
        assertThatThrownBy(() -> ImportReport.builder().read(Integer.MAX_VALUE).read(1))
            .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> ImportReport.builder().imported(Integer.MAX_VALUE).imported(1))
            .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> ImportReport.builder().skipped(Integer.MAX_VALUE).skipped(1))
            .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> ImportReport.builder().imported(Integer.MAX_VALUE).imported("gold").build())
            .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> ImportReport.builder().skipped(Integer.MAX_VALUE).skipped("vip").build())
            .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void reportsAreEqualWhenTheirCountsAndLinesAre() {
        ImportReport report = ImportReport.builder().read(3).imported("gold").skipped(1).warning("careful").build();

        assertThat(report)
            .isEqualTo(ImportReport.builder().read(3).imported("gold").skipped(1).warning("careful").build())
            .hasSameHashCodeAs(
                ImportReport.builder().read(3).imported("gold").skipped(1).warning("careful").build())
            .isNotEqualTo(ImportReport.builder().read(4).imported("gold").skipped(1).warning("careful").build())
            .isNotEqualTo(ImportReport.builder().read(3).imported(1).skipped(1).warning("careful").build())
            .isNotEqualTo(ImportReport.builder().read(3).imported("gold").imported(1).skipped(1)
                .warning("careful").build())
            .isNotEqualTo(ImportReport.builder().read(3).imported("gold").skipped(2).warning("careful").build())
            .isNotEqualTo(ImportReport.builder().read(3).imported("gold").skipped("vip").warning("careful").build())
            .isNotEqualTo(ImportReport.builder().read(3).imported("gold").skipped(1).build())
            .isNotEqualTo(List.of("gold"))
            .isNotEqualTo(null);
        assertThat(report).hasToString("ImportReport[read=3, imported=1, skipped=1, warnings=1]");
    }
}
