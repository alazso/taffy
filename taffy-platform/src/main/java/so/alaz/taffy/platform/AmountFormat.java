package so.alaz.taffy.platform;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Objects;

/**
 * How an amount of money is written for a player: a symbol, a number pattern and a count of decimal places.
 *
 * <p>The pattern is a {@link DecimalFormat} pattern such as {@code #,##0.00}, in which {@code ,} and {@code .}
 * stand for the grouping and decimal separators of the locale the amount is written in. Without a pattern the
 * number has grouped whole digits and exactly {@code scale} decimal places, as {@code #,##0.00} writes it for a
 * scale of 2.
 *
 * <p>A format holds no {@link DecimalFormat}: each call builds its own, so one format can be used from any
 * thread.
 *
 * @param symbol the currency symbol {@link #format} writes directly before the number, or an empty text for none
 * @param pattern the number pattern, as {@link #checkPattern} accepts it, or {@code null} for grouped whole
 *     digits with exactly {@code scale} decimal places
 * @param scale the decimal places an amount is shown to at least when it holds them, from 0 to
 *     {@value #MAX_SCALE}
 */
public record AmountFormat(String symbol, @Nullable String pattern, int scale) {

    /**
     * The accepted pattern, in lower case and without a full stop, as it follows {@code expected} in the message
     * of {@link #checkPattern}.
     */
    public static final String EXPECTED =
        "a number pattern such as #,##0.00 with no percent, per mille, currency sign or exponent";

    /** The largest scale a format takes. */
    public static final int MAX_SCALE = 18;

    // Percent, per mille (U+2030), the currency sign (U+00A4) and the exponent letter of a DecimalFormat pattern.
    private static final String REFUSED = "%‰¤E";

    /**
     * Checks the parts.
     *
     * @throws NullPointerException if {@code symbol} is {@code null}
     * @throws IllegalArgumentException if {@code scale} is outside 0 to {@value #MAX_SCALE}, or if
     *     {@code pattern} is not {@code null} and {@link #checkPattern} refuses it, with the message of that
     *     method
     */
    public AmountFormat {
        Objects.requireNonNull(symbol, "symbol");
        if (scale < 0 || scale > MAX_SCALE) {
            throw new IllegalArgumentException("scale must be from 0 to " + MAX_SCALE + ": " + scale);
        }
        if (pattern != null) {
            checkPattern(pattern);
        }
    }

    /**
     * Checks a number pattern, such as one an owner wrote in config. A pattern is accepted when it is not blank,
     * {@link DecimalFormat} accepts it, and it holds none of {@code %}, the per mille sign (U+2030), the currency
     * sign (U+00A4) and {@code E}, quoted or not, which make {@link DecimalFormat} multiply the amount, add a
     * currency symbol of the locale or write an exponent. The pattern is not trimmed.
     *
     * @return {@code value}
     * @throws IllegalArgumentException when {@code value} is {@code null} or not accepted, with the message
     *     {@code invalid number pattern '<value>': expected } followed by {@link #EXPECTED}
     */
    public static String checkPattern(@Nullable String value) {
        if (value == null || value.isBlank() || value.chars().anyMatch(ch -> REFUSED.indexOf(ch) >= 0)) {
            throw invalid(value);
        }
        try {
            new DecimalFormat(value, DecimalFormatSymbols.getInstance(Locale.ROOT));
        } catch (IllegalArgumentException malformed) {
            throw invalid(value);
        }
        return value;
    }

    /**
     * Writes the amount as a number, without the symbol, with the digits, the separators and the minus sign of
     * {@code locale}.
     *
     * <p>The number has as many decimal places as the pattern allows, or {@code scale} when that is more, so an
     * amount is never shown with fewer decimal places than it holds up to {@code scale}. An amount with more
     * decimal places than that is rounded with {@link RoundingMode#HALF_EVEN}, and an amount that rounds to zero
     * is written as zero without a minus sign. The amount is never read as a {@code double}.
     *
     * @throws NullPointerException if {@code amount} or {@code locale} is {@code null}
     */
    public String number(BigDecimal amount, Locale locale) {
        Objects.requireNonNull(amount, "amount");
        DecimalFormat format = new DecimalFormat(pattern == null ? derived() : pattern,
            DecimalFormatSymbols.getInstance(Objects.requireNonNull(locale, "locale")));
        int decimals = Math.max(format.getMaximumFractionDigits(), scale);
        format.setMaximumFractionDigits(decimals);
        format.setRoundingMode(RoundingMode.HALF_EVEN);
        // DecimalFormat writes a minus sign before a negative amount it rounds to zero.
        return format.format(amount.scale() > decimals ? amount.setScale(decimals, RoundingMode.HALF_EVEN) : amount);
    }

    /**
     * Writes the amount as {@link #number} does, with {@link #symbol} directly before it: {@code $1,250.50} for
     * the symbol {@code $}. With an empty symbol this is {@link #number}.
     *
     * @throws NullPointerException if {@code amount} or {@code locale} is {@code null}
     */
    public String format(BigDecimal amount, Locale locale) {
        return symbol + number(amount, locale);
    }

    private String derived() {
        return scale > 0 ? "#,##0." + "0".repeat(scale) : "#,##0";
    }

    private static IllegalArgumentException invalid(@Nullable String value) {
        return new IllegalArgumentException("invalid number pattern '" + value + "': expected " + EXPECTED);
    }
}
