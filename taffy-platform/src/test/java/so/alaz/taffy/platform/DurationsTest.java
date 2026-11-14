package so.alaz.taffy.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class DurationsTest {

    @Test
    void parsesEveryUnit() {
        assertThat(Durations.parse("250ms")).isEqualTo(Duration.ofMillis(250));
        assertThat(Durations.parse("30s")).isEqualTo(Duration.ofSeconds(30));
        assertThat(Durations.parse("2m")).isEqualTo(Duration.ofMinutes(2));
        assertThat(Durations.parse("48h")).isEqualTo(Duration.ofHours(48));
        assertThat(Durations.parse("7d")).isEqualTo(Duration.ofDays(7));
        assertThat(Durations.parse("0s")).isEqualTo(Duration.ZERO);
    }

    @Test
    void acceptsUnitsInAnyLetterCase() {
        assertThat(Durations.parse("5S")).isEqualTo(Duration.ofSeconds(5));
        assertThat(Durations.parse("7D")).isEqualTo(Duration.ofDays(7));
        assertThat(Durations.parse("250MS")).isEqualTo(Duration.ofMillis(250));
        assertThat(Durations.parse("250Ms")).isEqualTo(Duration.ofMillis(250));
        assertThat(Durations.parse("2M")).isEqualTo(Duration.ofMinutes(2));
        assertThat(Durations.parse("48H")).isEqualTo(Duration.ofHours(48));
    }

    @Test
    void ignoresSurroundingWhitespace() {
        assertThat(Durations.parse(" 15s ")).isEqualTo(Duration.ofSeconds(15));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "5", "s", "5x", "-5s", "+5s", "1.5h", "5 s", "1w", "9223372036854775807d",
        "999999999999999999d", "999999999999999999s", "9999999999999999m", "999999999999999h", "106751991167d",
        "0000000000000000005s", "\u0665s"})
    void rejectsAnythingElse(String value) {
        assertThat(Durations.parseOrNull(value)).isNull();
    }

    @Test
    void theLargestAcceptedDurationStillFitsInEpochMillis() {
        Duration largest = Durations.parse("999999999999999999ms");

        assertThat(largest.toMillis()).isEqualTo(999_999_999_999_999_999L);
        assertThat(java.time.Instant.now().plus(largest).toEpochMilli()).isPositive();
    }

    @Test
    void theLongestAcceptedDurationIsHalfTheMillisecondRange() {
        Duration longest = Durations.parse("4611686018427387s");

        assertThat(longest).isEqualTo(Duration.ofMillis(Long.MAX_VALUE / 2).truncatedTo(ChronoUnit.SECONDS));
        assertThat(Durations.parseOrNull("4611686018427388s")).isNull();
        assertThat(Durations.parse("76861433640456m")).isEqualTo(Duration.ofMinutes(76_861_433_640_456L));
        assertThat(Durations.parseOrNull("76861433640457m")).isNull();
        assertThat(Instant.now().plus(longest).toEpochMilli()).isPositive();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "250ms", "1500ms", "30s", "90s", "2m", "36h", "7d"})
    void formatWritesTheLargestWholeUnitAndParsesBack(String text) {
        Duration duration = Durations.parse(text);

        assertThat(Durations.format(duration)).isEqualTo(text);
        assertThat(Durations.parse(Durations.format(duration))).isEqualTo(duration);
    }

    @Test
    void formatPrefersTheLargestUnit() {
        assertThat(Durations.format(Duration.ofHours(24))).isEqualTo("1d");
        assertThat(Durations.format(Duration.ofMinutes(120))).isEqualTo("2h");
        assertThat(Durations.format(Duration.ofSeconds(60))).isEqualTo("1m");
    }

    @Test
    void formatWritesAListInBrackets() {
        List<Duration> durations = List.of(Duration.ofSeconds(30), Duration.ofHours(24), Duration.ofMillis(1500));

        assertThat(Durations.format(durations)).isEqualTo("[30s, 1d, 1500ms]");
        assertThat(Durations.format(List.of())).isEqualTo("[]");
    }

    @Test
    void formatWritesAnUpperCaseInputInLowerCase() {
        assertThat(Durations.format(Durations.parse("7D"))).isEqualTo("7d");
    }

    @Test
    void formatWritesANegativeDurationInAFormParseRejects() {
        String text = Durations.format(Duration.ofSeconds(-90));

        assertThat(text).isEqualTo("-90s");
        assertThat(Durations.parseOrNull(text)).isNull();
    }

    @Test
    void formatThrowsWhenTheMillisecondsDoNotFitInALong() {
        assertThatThrownBy(() -> Durations.format(Duration.ofSeconds(Long.MAX_VALUE)))
            .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void rejectsANumberWithNoUnit() {
        assertThat(Durations.parseOrNull(30)).isNull();
    }

    @Test
    void parseNamesTheValueAndTheExpectedForm() {
        assertThatThrownBy(() -> Durations.parse("soon"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'soon'")
            .hasMessageContaining("ms, s, m, h or d")
            .hasMessage("invalid duration 'soon': expected " + Durations.EXPECTED)
            .hasMessageEndingWith(Durations.EXPECTED);
        assertThat(Durations.EXPECTED).isEqualTo("a whole number followed by ms, s, m, h or d (for example 30s)");
    }

    @Test
    void parseRejectsNullWithTheSameErrorAsAnyOtherValue() {
        assertThatThrownBy(() -> Durations.parse(null))
            .isExactlyInstanceOf(IllegalArgumentException.class)
            .hasMessage("invalid duration 'null': expected " + Durations.EXPECTED);
    }
}
