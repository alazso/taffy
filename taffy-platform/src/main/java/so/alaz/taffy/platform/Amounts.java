package so.alaz.taffy.platform;

import java.math.BigDecimal;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Reads an amount of money a player typed, such as {@code 1500}, {@code 2.5}, {@code 1k}, {@code 2.5m} or
 * {@code 1b}.
 *
 * <p>The accepted form is ASCII digits with at most one decimal separator, with a digit on at least one side of
 * it, followed by nothing or by one of {@code k} (a thousand), {@code m} (a million) and {@code b} (a thousand
 * million) in either letter case. Whitespace around the input is ignored. The decimal separator is that of the
 * player's locale, and a full stop is read as one too unless it is the grouping separator of that locale. A
 * plus sign, an exponent, a space before the suffix, two suffixes and digits other than {@code 0} to {@code 9}
 * are not accepted.
 *
 * <p>{@link #parse} is not the inverse of {@link AmountFormat#number}: it refuses the grouping separators that
 * method writes, and the digits and the minus sign it writes in a locale that has its own, such as
 * {@code ar-SA} or {@code fa-IR}.
 */
public final class Amounts {

    /** The longest input {@link #parse} reads, in characters, once the whitespace around it is removed. */
    public static final int MAX_LENGTH = 64;

    private static final Pattern NUMBER = Pattern.compile("[0-9]+\\.?[0-9]*|\\.[0-9]+");

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1_000);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final BigDecimal THOUSAND_MILLION = BigDecimal.valueOf(1_000_000_000);

    private Amounts() {
    }

    /** What {@link #parse} made of an input: an {@link Amount} or a {@link Refused}. */
    public sealed interface Result {
    }

    /**
     * The amount the input stands for.
     *
     * @param value the amount, zero or more. One that {@link Amounts#parse} returns is exact and not rounded,
     *     with no trailing zero among its decimal places and a scale of zero or more.
     */
    public record Amount(BigDecimal value) implements Result {

        /**
         * Checks the value.
         *
         * @throws NullPointerException if {@code value} is {@code null}
         * @throws IllegalArgumentException if {@code value} is below zero
         */
        public Amount {
            Objects.requireNonNull(value, "value");
            if (value.signum() < 0) {
                throw new IllegalArgumentException("value must not be below zero: " + value);
            }
        }
    }

    /**
     * The input was refused.
     *
     * @param reason why
     */
    public record Refused(Reason reason) implements Result {

        /**
         * Checks the reason.
         *
         * @throws NullPointerException if {@code reason} is {@code null}
         */
        public Refused {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * Why an input was refused, for the plugin to choose its own message by. A later release may add a reason, so
     * a {@code switch} over the reasons needs a {@code default} branch.
     */
    public enum Reason {

        /** The input is empty or only whitespace. */
        EMPTY,

        /** The input is longer than {@link Amounts#MAX_LENGTH} characters. */
        TOO_LONG,

        /** The input is not in the accepted form and holds no grouping separator that would explain it. */
        NOT_A_NUMBER,

        /**
         * The input would be in the accepted form without its grouping separators: the grouping separator of
         * the locale, a comma that is not the decimal separator of the locale, or a full stop where the locale
         * groups with one. Where the locale groups with a space, a no-break space (U+00A0) or a narrow no-break
         * space (U+202F), each of the three between two digits is a grouping separator too. {@code 1,000} is a
         * thousand in one locale and one in another, so it is never guessed.
         */
        GROUPING_SEPARATOR,

        /** The input is in the accepted form after a minus sign, {@code -0} included. */
        NEGATIVE,

        /** The amount is above the maximum the caller gave. */
        OVER_MAXIMUM
    }

    /**
     * Reads {@code input} as an amount from zero to {@code maximum}.
     *
     * <p>The amount is exact: the digits are read into a {@link BigDecimal}, multiplied by the suffix and
     * compared with {@code maximum}, with no {@code double} and no rounding, so {@code 0.0001k} is {@code 0.1}
     * and an amount keeps every decimal place the player typed. The caller rounds it to the scale of its
     * currency. An amount above zero can round to zero there, such as {@code 0.004} at two decimal places, so
     * a caller that needs an amount above zero checks {@link BigDecimal#signum()} after it rounds. An input
     * equal to {@code maximum} is accepted.
     *
     * <p>The reasons are checked in the order {@link Reason#EMPTY}, {@link Reason#TOO_LONG},
     * {@link Reason#NOT_A_NUMBER}, {@link Reason#GROUPING_SEPARATOR}, {@link Reason#NEGATIVE},
     * {@link Reason#OVER_MAXIMUM}.
     *
     * @param input what the player typed
     * @param locale the player's locale, whose decimal and grouping separators are read
     * @param maximum the largest amount accepted, zero or more
     * @return an {@link Amount}, or a {@link Refused} with the reason
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code maximum} is below zero
     */
    public static Result parse(String input, Locale locale, BigDecimal maximum) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(maximum, "maximum");
        if (maximum.signum() < 0) {
            throw new IllegalArgumentException("maximum must not be below zero: " + maximum);
        }
        String text = input.strip();
        if (text.isEmpty()) {
            return new Refused(Reason.EMPTY);
        }
        if (text.length() > MAX_LENGTH) {
            return new Refused(Reason.TOO_LONG);
        }
        // Character.toLowerCase maps the Kelvin sign (U+212A) to k, so the letters are matched as written.
        BigDecimal multiplier = switch (text.charAt(text.length() - 1)) {
            case 'k', 'K' -> THOUSAND;
            case 'm', 'M' -> MILLION;
            case 'b', 'B' -> THOUSAND_MILLION;
            default -> BigDecimal.ONE;
        };
        int end = multiplier.equals(BigDecimal.ONE) ? text.length() : text.length() - 1;
        boolean negative = text.charAt(0) == '-';
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(locale);
        char decimal = symbols.getDecimalSeparator();
        char grouping = symbols.getGroupingSeparator();
        boolean spaceGroups = isSpace(grouping);
        StringBuilder number = new StringBuilder(end);
        boolean grouped = false;
        for (int i = negative ? 1 : 0; i < end; i++) {
            char ch = text.charAt(i);
            if (ch == decimal) {
                number.append('.');
            } else if (ch == grouping || ch == ',') {
                grouped = true;
            } else if (spaceGroups && isSpace(ch) && i > 0 && i + 1 < end
                && isDigit(text.charAt(i - 1)) && isDigit(text.charAt(i + 1))) {
                grouped = true;
            } else {
                number.append(ch);
            }
        }
        if (!NUMBER.matcher(number).matches()) {
            return new Refused(Reason.NOT_A_NUMBER);
        }
        if (grouped) {
            return new Refused(Reason.GROUPING_SEPARATOR);
        }
        if (negative) {
            return new Refused(Reason.NEGATIVE);
        }
        BigDecimal value = new BigDecimal(number.toString()).multiply(multiplier);
        if (value.compareTo(maximum) > 0) {
            return new Refused(Reason.OVER_MAXIMUM);
        }
        if (value.scale() > 0) {
            value = value.stripTrailingZeros();
        }
        return new Amount(value.scale() < 0 ? value.setScale(0) : value);
    }

    private static boolean isSpace(char ch) {
        return ch == ' ' || ch == '\u00a0' || ch == '\u202f';
    }

    private static boolean isDigit(char ch) {
        return ch >= '0' && ch <= '9';
    }
}
