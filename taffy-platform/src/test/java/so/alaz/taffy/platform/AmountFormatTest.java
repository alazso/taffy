package so.alaz.taffy.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class AmountFormatTest {

    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private static final AmountFormat DOLLARS = new AmountFormat("$", null, 2);

    private static String number(AmountFormat format, String amount) {
        return format.number(new BigDecimal(amount), Locale.US);
    }

    @Test
    void withoutAPatternTheNumberIsGroupedWithExactlyScaleDecimals() {
        assertThat(number(DOLLARS, "1234567.5")).isEqualTo("1,234,567.50");
        assertThat(number(DOLLARS, "0")).isEqualTo("0.00");
        assertThat(number(DOLLARS, "5")).isEqualTo("5.00");
        assertThat(number(new AmountFormat("", null, 0), "1234567")).isEqualTo("1,234,567");
        assertThat(number(new AmountFormat("", null, 4), "0.5")).isEqualTo("0.5000");
    }

    @Test
    void theSeparatorsAreThoseOfTheLocaleNotOfTheDefaultLocale() {
        BigDecimal amount = new BigDecimal("1234567.5");

        assertThat(Locale.getDefault().getLanguage()).isEqualTo("tr");
        assertThat(DOLLARS.number(amount, Locale.US)).isEqualTo("1,234,567.50");
        assertThat(DOLLARS.number(amount, Locale.ROOT)).isEqualTo("1,234,567.50");
        assertThat(DOLLARS.number(amount, Locale.GERMANY)).isEqualTo("1.234.567,50");
        assertThat(DOLLARS.number(amount, TURKISH)).isEqualTo("1.234.567,50");
        assertThat(DOLLARS.number(amount, Locale.FRANCE)).isEqualTo("1 234 567,50");
        assertThat(DOLLARS.number(amount, Locale.forLanguageTag("de-CH"))).isEqualTo("1’234’567.50");
    }

    @Test
    void theDigitsAreThoseOfTheLocale() {
        assertThat(DOLLARS.number(new BigDecimal("1234.5"), Locale.forLanguageTag("ar-SA")))
            .isEqualTo("١٬٢٣٤٫٥٠");
    }

    @Test
    void anAmountWithMoreDecimalsThanTheScaleIsRoundedHalfEven() {
        assertThat(number(DOLLARS, "0.125")).isEqualTo("0.12");
        assertThat(number(DOLLARS, "0.135")).isEqualTo("0.14");
        assertThat(number(DOLLARS, "0.1250001")).isEqualTo("0.13");
        assertThat(number(DOLLARS, "-0.125")).isEqualTo("-0.12");
        assertThat(number(DOLLARS, "-0.135")).isEqualTo("-0.14");
        assertThat(number(new AmountFormat("", null, 0), "1234566.5")).isEqualTo("1,234,566");
        assertThat(number(new AmountFormat("", null, 0), "1234567.5")).isEqualTo("1,234,568");
    }

    @Test
    void aPatternWithFewerDecimalsThanTheScaleStillShowsTheDecimalsTheAmountHoldsUpToTheScale() {
        AmountFormat format = new AmountFormat("", "#,##0.##", 4);

        assertThat(number(format, "1")).isEqualTo("1");
        assertThat(number(format, "1.5")).isEqualTo("1.5");
        assertThat(number(format, "1.50")).isEqualTo("1.5");
        assertThat(number(format, "1.2345")).isEqualTo("1.2345");
        assertThat(number(format, "1.23456")).isEqualTo("1.2346");
        assertThat(number(format, "1.23445")).isEqualTo("1.2344");
        assertThat(number(new AmountFormat("", "#,##0", 2), "1500.25")).isEqualTo("1,500.25");
        assertThat(number(new AmountFormat("", "#,##0", 2), "1500")).isEqualTo("1,500");
    }

    @Test
    void aPatternWithMoreDecimalsThanTheScaleKeepsItsOwn() {
        AmountFormat format = new AmountFormat("", "0.000", 2);

        assertThat(number(format, "1.5")).isEqualTo("1.500");
        assertThat(number(format, "1.2345")).isEqualTo("1.234");
        assertThat(number(format, "1.2355")).isEqualTo("1.236");
    }

    @Test
    void aPatternMayHoldQuotedTextAndANegativeForm() {
        assertThat(number(new AmountFormat("", "#,##0.00 'coins'", 2), "1500")).isEqualTo("1,500.00 coins");
        assertThat(number(new AmountFormat("", "#,##0.00;(#)", 2), "-12.5")).isEqualTo("(12.50)");
        assertThat(number(new AmountFormat("", "#,##,##0.00", 2), "1234567")).isEqualTo("1,234,567.00");
    }

    @Test
    void aNegativeAmountKeepsItsMinusSign() {
        assertThat(number(DOLLARS, "-12.5")).isEqualTo("-12.50");
        assertThat(number(DOLLARS, "-0.01")).isEqualTo("-0.01");
        assertThat(DOLLARS.number(new BigDecimal("-12.5"), Locale.forLanguageTag("nb-NO"))).isEqualTo("−12,50");
    }

    @Test
    void anAmountThatRoundsToZeroHasNoMinusSign() {
        assertThat(number(DOLLARS, "-0.004")).isEqualTo("0.00");
        assertThat(number(DOLLARS, "-0.005")).isEqualTo("0.00");
        assertThat(number(DOLLARS, "-0.0051")).isEqualTo("-0.01");
        assertThat(number(DOLLARS, "-0.00")).isEqualTo("0.00");
        assertThat(number(new AmountFormat("", "#,##0.##", 2), "-0.004")).isEqualTo("0");
        assertThat(number(new AmountFormat("", "#,##0.00;(#)", 2), "-0.004")).isEqualTo("0.00");
        assertThat(number(new AmountFormat("", null, 0), "-0.4")).isEqualTo("0");
    }

    @Test
    void anAmountNoDoubleHoldsIsWrittenExactly() {
        assertThat(number(DOLLARS, "9007199254740993")).isEqualTo("9,007,199,254,740,993.00");
        assertThat(number(DOLLARS, "123456789012345678901234567890.125"))
            .isEqualTo("123,456,789,012,345,678,901,234,567,890.12");
        assertThat(number(DOLLARS, "0.3")).isEqualTo("0.30");
        assertThat(number(new AmountFormat("", null, 18), "0.100000000000000001")).isEqualTo("0.100000000000000001");
        assertThat(number(DOLLARS, "1E+3")).isEqualTo("1,000.00");
    }

    @Test
    void formatWritesTheSymbolDirectlyBeforeTheNumber() {
        assertThat(DOLLARS.format(new BigDecimal("1250.5"), Locale.US)).isEqualTo("$1,250.50");
        assertThat(DOLLARS.format(new BigDecimal("1250.5"), Locale.GERMANY)).isEqualTo("$1.250,50");
        assertThat(DOLLARS.format(new BigDecimal("-5"), Locale.US)).isEqualTo("$-5.00");
        assertThat(new AmountFormat("", null, 2).format(new BigDecimal("1250.5"), Locale.US)).isEqualTo("1,250.50");
    }

    @ParameterizedTest
    @ValueSource(strings = {"#,##0.00", "#,##0", "0", "#", "#.##", "0.000", "#,##0.00;(#)", "'$'#,##0",
        "#,##0.00 'coins'", "#,##,##0.0#"})
    void checkPatternReturnsAnAcceptedPattern(String pattern) {
        assertThat(AmountFormat.checkPattern(pattern)).isSameAs(pattern);
        assertThat(new AmountFormat("", pattern, 2).pattern()).isSameAs(pattern);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "0%", "#,##0.00%", "0‰", "¤#,##0.00", "0.###E0", "'E'0", "0.0.0",
        "#,", ",", "'", ";", "0#"})
    void checkPatternRefusesAnythingElse(String pattern) {
        assertThatThrownBy(() -> AmountFormat.checkPattern(pattern))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("invalid number pattern '" + pattern + "': expected " + AmountFormat.EXPECTED);
    }

    @Test
    void theExpectedTextIsAsciiAndNamesWhatIsRefused() {
        assertThat(AmountFormat.EXPECTED)
            .isEqualTo("a number pattern such as #,##0.00 with no percent, per mille, currency sign or exponent")
            .matches("[ -~]+");
    }

    @Test
    void theConstructorRefusesAPatternCheckPatternRefuses() {
        assertThatThrownBy(() -> new AmountFormat("$", "0%", 2))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("invalid number pattern '0%': expected " + AmountFormat.EXPECTED);
        assertThatThrownBy(() -> new AmountFormat("$", " ", 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theConstructorRefusesAScaleOutsideItsRangeAndANullSymbol() {
        assertThat(new AmountFormat("", null, 0).scale()).isZero();
        assertThat(new AmountFormat("", null, AmountFormat.MAX_SCALE).scale()).isEqualTo(18);
        assertThatThrownBy(() -> new AmountFormat("", null, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("scale must be from 0 to 18: -1");
        assertThatThrownBy(() -> new AmountFormat("", null, 19))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("scale must be from 0 to 18: 19");
        assertThatThrownBy(() -> new AmountFormat(null, null, 2))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("symbol");
    }

    @Test
    void aNullAmountOrLocaleIsRefused() {
        assertThatThrownBy(() -> DOLLARS.number(null, Locale.US))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("amount");
        assertThatThrownBy(() -> DOLLARS.format(BigDecimal.ONE, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("locale");
    }

    @Test
    void oneFormatWritesTheSameTextFromManyThreads() throws Exception {
        AmountFormat format = new AmountFormat("$", "#,##0.00", 2);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<String>>> runs = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                Locale locale = thread % 2 == 0 ? Locale.US : Locale.GERMANY;
                BigDecimal amount = new BigDecimal(thread % 2 == 0 ? "1234567.125" : "-7654321.135");
                Callable<List<String>> run = () -> {
                    List<String> written = new ArrayList<>();
                    for (int i = 0; i < 2_000; i++) {
                        written.add(format.format(amount, locale));
                    }
                    return written;
                };
                runs.add(pool.submit(run));
            }
            for (int thread = 0; thread < threads; thread++) {
                assertThat(runs.get(thread).get())
                    .containsOnly(thread % 2 == 0 ? "$1,234,567.12" : "$-7.654.321,14");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
