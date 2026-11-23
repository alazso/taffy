package so.alaz.taffy.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class AmountsTest {

    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private static final Locale SAUDI = Locale.forLanguageTag("ar-SA");

    private static final BigDecimal MAXIMUM = new BigDecimal("922337203685477.5807");

    private static Amounts.Result parse(String input, Locale locale) {
        return Amounts.parse(input, locale, MAXIMUM);
    }

    private static Amounts.Result amount(String value) {
        return new Amounts.Amount(new BigDecimal(value));
    }

    private static Amounts.Result refused(Amounts.Reason reason) {
        return new Amounts.Refused(reason);
    }

    @ParameterizedTest
    @CsvSource({
        "1500, 1500", "2.5, 2.5", "0, 0", "0.0, 0", "007, 7", "5., 5", ".5, 0.5", "1.50, 1.5", "0.0001, 0.0001",
        "1k, 1000", "2.5k, 2500", "2.5m, 2500000", "1b, 1000000000", "1.5b, 1500000000", "0k, 0", ".5k, 500",
        "5.k, 5000", "0.0001k, 0.1", "1.2345678k, 1234.5678", "0.00000000001b, 0.01"})
    void readsAPlainNumberAndTheThreeSuffixes(String input, String expected) {
        assertThat(parse(input, Locale.US)).isEqualTo(amount(expected));
    }

    @Test
    void theAmountHasNoTrailingDecimalZeroAndNoNegativeScale() {
        BigDecimal thousand = ((Amounts.Amount) parse("1.0k", Locale.US)).value();
        BigDecimal half = ((Amounts.Amount) parse("0.50", Locale.US)).value();
        BigDecimal zero = ((Amounts.Amount) parse("0.000", Locale.US)).value();
        BigDecimal million = ((Amounts.Amount) parse("1m", Locale.US)).value();

        assertThat(thousand.toString()).isEqualTo("1000");
        assertThat(half.toString()).isEqualTo("0.5");
        assertThat(zero.toString()).isEqualTo("0");
        assertThat(million.toString()).isEqualTo("1000000");
    }

    @Test
    void theSuffixIsReadInEitherLetterCase() {
        assertThat(parse("1K", Locale.US)).isEqualTo(amount("1000"));
        assertThat(parse("2.5M", Locale.US)).isEqualTo(amount("2500000"));
        assertThat(parse("1B", Locale.US)).isEqualTo(amount("1000000000"));
        assertThat(parse("1K", TURKISH)).isEqualTo(amount("1000"));
    }

    @Test
    void whitespaceAroundTheInputIsIgnored() {
        assertThat(parse(" 1k ", Locale.US)).isEqualTo(amount("1000"));
        assertThat(parse("\t2.5\n", Locale.US)).isEqualTo(amount("2.5"));
        assertThat(parse(" 1k ", Locale.US)).isEqualTo(amount("1000"));
    }

    @Test
    void theDecimalSeparatorIsThatOfTheLocale() {
        assertThat(Locale.getDefault().getLanguage()).isEqualTo("tr");
        assertThat(parse("2,5", Locale.GERMANY)).isEqualTo(amount("2.5"));
        assertThat(parse("1,5k", Locale.GERMANY)).isEqualTo(amount("1500"));
        assertThat(parse("1,5k", TURKISH)).isEqualTo(amount("1500"));
        assertThat(parse("2,5m", Locale.FRANCE)).isEqualTo(amount("2500000"));
        assertThat(parse("2٫5", SAUDI)).isEqualTo(amount("2.5"));
        assertThat(parse("2.5", Locale.US)).isEqualTo(amount("2.5"));
    }

    @Test
    void aFullStopIsADecimalSeparatorWhereTheLocaleDoesNotGroupWithIt() {
        assertThat(parse("2.5", Locale.FRANCE)).isEqualTo(amount("2.5"));
        assertThat(parse("2.5k", SAUDI)).isEqualTo(amount("2500"));
        assertThat(parse("2.5", Locale.GERMANY)).isEqualTo(refused(Amounts.Reason.GROUPING_SEPARATOR));
        assertThat(parse("2.5", TURKISH)).isEqualTo(refused(Amounts.Reason.GROUPING_SEPARATOR));
    }

    @Test
    void aGroupingSeparatorIsRefusedAndNeverGuessed() {
        Amounts.Result grouping = refused(Amounts.Reason.GROUPING_SEPARATOR);

        assertThat(parse("1,000", Locale.US)).isEqualTo(grouping);
        assertThat(parse("1,5k", Locale.US)).isEqualTo(grouping);
        assertThat(parse("1,000.50", Locale.US)).isEqualTo(grouping);
        assertThat(parse("1,5", Locale.ROOT)).isEqualTo(grouping);
        assertThat(parse("1.000", Locale.GERMANY)).isEqualTo(grouping);
        assertThat(parse("1.000,50", Locale.GERMANY)).isEqualTo(grouping);
        assertThat(parse("1.000k", TURKISH)).isEqualTo(grouping);
        assertThat(parse("1 000", Locale.FRANCE)).isEqualTo(grouping);
        assertThat(parse("1’000", Locale.forLanguageTag("de-CH"))).isEqualTo(grouping);
        assertThat(parse("1,000", Locale.forLanguageTag("de-CH"))).isEqualTo(grouping);
        assertThat(parse("1٬000", SAUDI)).isEqualTo(grouping);
        assertThat(parse("-1,000", Locale.US)).isEqualTo(grouping);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e9", "1E9", "1e-2", "1.5.5k", "1.5.5", "1kk", "1km", "0x10", "0b1", "+1", "+1k",
        "+0", "k", "m", "b", "K", "-k", "-", "+", ".", "-.", ".k", "1 k", "1 000", "1_000", "1t", "1T", "1q",
        "NaN", "nan", "Infinity", "-Infinity", "infinity", "abc", "1a", "a1", "1-", "--1", "1+", "$5", "5$",
        "1,5,x", ",", "1d", "1f", "1L", "0x1p3", "١٢٣", "١k", "１２", "1K",
        "−1", "1 000", "1'000", "5%", "1/2", "1k5", "k1", "1.5e3k", "\u0000"})
    void refusesAsNotANumber(String input) {
        assertThat(parse(input, Locale.US)).isEqualTo(refused(Amounts.Reason.NOT_A_NUMBER));
    }

    @Test
    void aSecondDecimalSeparatorIsNotANumberInALocaleThatWritesAComma() {
        assertThat(parse("1,5,5k", Locale.GERMANY)).isEqualTo(refused(Amounts.Reason.NOT_A_NUMBER));
        assertThat(parse("1.5,5", Locale.FRANCE)).isEqualTo(refused(Amounts.Reason.NOT_A_NUMBER));
        assertThat(parse("١٫٥", SAUDI)).isEqualTo(refused(Amounts.Reason.NOT_A_NUMBER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-0", "-0.0", "-0k", "-1k", "-2.5m", "-.5", " -5 ", "-0.0001"})
    void aMinusSignBeforeANumberIsNegativeEvenBeforeZero(String input) {
        assertThat(parse(input, Locale.US)).isEqualTo(refused(Amounts.Reason.NEGATIVE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n", " "})
    void anInputOfOnlyWhitespaceIsEmpty(String input) {
        assertThat(parse(input, Locale.US)).isEqualTo(refused(Amounts.Reason.EMPTY));
    }

    @Test
    void anInputOverTheLongestLengthIsTooLongWhateverItHolds() {
        BigDecimal huge = BigDecimal.TEN.pow(100);

        assertThat(Amounts.MAX_LENGTH).isEqualTo(64);
        assertThat(Amounts.parse("9".repeat(64), Locale.US, huge)).isEqualTo(amount("9".repeat(64)));
        assertThat(Amounts.parse("  " + "9".repeat(64) + "  ", Locale.US, huge)).isEqualTo(amount("9".repeat(64)));
        assertThat(Amounts.parse("9".repeat(65), Locale.US, huge)).isEqualTo(refused(Amounts.Reason.TOO_LONG));
        assertThat(Amounts.parse("x".repeat(65), Locale.US, huge)).isEqualTo(refused(Amounts.Reason.TOO_LONG));
        assertThat(Amounts.parse("9".repeat(1_000_000), Locale.US, huge))
            .isEqualTo(refused(Amounts.Reason.TOO_LONG));
    }

    @Test
    void anAmountAboveTheMaximumIsRefusedAndTheMaximumItselfIsAccepted() {
        BigDecimal thousand = new BigDecimal("1000");

        assertThat(Amounts.parse("1000", Locale.US, thousand)).isEqualTo(amount("1000"));
        assertThat(Amounts.parse("1k", Locale.US, thousand)).isEqualTo(amount("1000"));
        assertThat(Amounts.parse("1000.00", Locale.US, thousand)).isEqualTo(amount("1000"));
        assertThat(Amounts.parse("1000.0001", Locale.US, thousand))
            .isEqualTo(refused(Amounts.Reason.OVER_MAXIMUM));
        assertThat(Amounts.parse("1.0000001k", Locale.US, thousand))
            .isEqualTo(refused(Amounts.Reason.OVER_MAXIMUM));
        assertThat(Amounts.parse("1m", Locale.US, thousand)).isEqualTo(refused(Amounts.Reason.OVER_MAXIMUM));
        assertThat(Amounts.parse("0", Locale.US, BigDecimal.ZERO)).isEqualTo(amount("0"));
        assertThat(Amounts.parse("0.0001", Locale.US, BigDecimal.ZERO))
            .isEqualTo(refused(Amounts.Reason.OVER_MAXIMUM));
    }

    @Test
    void anAmountNoLongOrDoubleHoldsIsComparedExactly() {
        assertThat(parse("922337203685477.5807", Locale.US)).isEqualTo(amount("922337203685477.5807"));
        assertThat(parse("922337203685477.5808", Locale.US)).isEqualTo(refused(Amounts.Reason.OVER_MAXIMUM));
        assertThat(parse("922337.2036854775807b", Locale.US)).isEqualTo(amount("922337203685477.5807"));
        assertThat(parse("922337.2036854775808b", Locale.US)).isEqualTo(refused(Amounts.Reason.OVER_MAXIMUM));
        assertThat(parse("9".repeat(63) + "b", Locale.US)).isEqualTo(refused(Amounts.Reason.OVER_MAXIMUM));
        assertThat(parse("0.1", Locale.US)).isEqualTo(amount("0.1"));
        assertThat(parse("0." + "0".repeat(61) + "1", Locale.US)).isEqualTo(amount("1E-62"));
    }

    @Test
    void theReasonsAreCheckedInOrder() {
        assertThat(parse("-1,000", Locale.US)).isEqualTo(refused(Amounts.Reason.GROUPING_SEPARATOR));
        assertThat(parse("-9" + "9".repeat(61) + "b", Locale.US)).isEqualTo(refused(Amounts.Reason.NEGATIVE));
        assertThat(Amounts.parse("-5", Locale.US, BigDecimal.ONE)).isEqualTo(refused(Amounts.Reason.NEGATIVE));
        assertThat(Amounts.parse("1,000", Locale.US, BigDecimal.ONE))
            .isEqualTo(refused(Amounts.Reason.GROUPING_SEPARATOR));
        assertThat(parse("-abc", Locale.US)).isEqualTo(refused(Amounts.Reason.NOT_A_NUMBER));
        assertThat(parse("abc,def", Locale.US)).isEqualTo(refused(Amounts.Reason.NOT_A_NUMBER));
    }

    @Test
    void aNegativeMaximumAndANullArgumentAreRefused() {
        assertThatThrownBy(() -> Amounts.parse("1", Locale.US, new BigDecimal("-0.01")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("maximum must not be below zero: -0.01");
        assertThatThrownBy(() -> Amounts.parse(null, Locale.US, MAXIMUM))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("input");
        assertThatThrownBy(() -> Amounts.parse("1", null, MAXIMUM))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("locale");
        assertThatThrownBy(() -> Amounts.parse("1", Locale.US, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Amounts.Amount(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Amounts.Refused(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theReasonsAreTheSixTheParserGives() {
        assertThat(Amounts.Reason.values()).containsExactly(Amounts.Reason.EMPTY, Amounts.Reason.TOO_LONG,
            Amounts.Reason.NOT_A_NUMBER, Amounts.Reason.GROUPING_SEPARATOR, Amounts.Reason.NEGATIVE,
            Amounts.Reason.OVER_MAXIMUM);
    }
}
