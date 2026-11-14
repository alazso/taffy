package so.alaz.taffy.platform;

import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parses and formats short durations written as a whole number followed by a unit, such as {@code 250ms},
 * {@code 30s}, {@code 2m}, {@code 48h} or {@code 7d}.
 *
 * <p>The accepted form is 1 to 18 ASCII digits followed by {@code ms}, {@code s}, {@code m}, {@code h} or
 * {@code d} in any letter case. Whitespace around the value is ignored. A sign, a decimal point and a space
 * between the number and the unit are not accepted. The longest accepted duration is
 * {@code Long.MAX_VALUE / 2} milliseconds.
 */
public final class Durations {

    /**
     * The accepted form, in lower case and without a full stop, as it follows {@code expected} in the message
     * of {@link #parse}.
     */
    public static final String EXPECTED = "a whole number followed by ms, s, m, h or d (for example 30s)";

    private static final Pattern FORM = Pattern.compile("(\\d{1,18})(ms|s|m|h|d)", Pattern.CASE_INSENSITIVE);

    // Half the millisecond range, so toMillis() and now plus the duration both stay within a long.
    private static final Duration MAX = Duration.ofMillis(Long.MAX_VALUE / 2);

    private Durations() {
    }

    /**
     * Parses the text form of {@code value}, read through {@code toString()} and trimmed.
     *
     * @return the duration, or {@code null} when {@code value} is {@code null}, is not in the accepted form
     *     or is longer than the longest accepted duration
     */
    public static @Nullable Duration parseOrNull(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        Matcher match = FORM.matcher(value.toString().trim());
        if (!match.matches()) {
            return null;
        }
        long amount = Long.parseLong(match.group(1));
        try {
            Duration duration = switch (match.group(2).toLowerCase(Locale.ROOT)) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                default -> Duration.ofDays(amount);
            };
            return duration.compareTo(MAX) > 0 ? null : duration;
        } catch (ArithmeticException tooLong) {
            return null;
        }
    }

    /**
     * Parses the text form of {@code value} as {@link #parseOrNull} does.
     *
     * @throws IllegalArgumentException when {@link #parseOrNull} would return {@code null}, with the message
     *     {@code invalid duration '<value>': expected } followed by {@link #EXPECTED}
     */
    public static Duration parse(@Nullable Object value) {
        Duration duration = parseOrNull(value);
        if (duration == null) {
            throw new IllegalArgumentException("invalid duration '" + value + "': expected " + EXPECTED);
        }
        return duration;
    }

    /**
     * Formats each duration as {@link #format(Duration)} does and joins the results with a comma and a space
     * inside square brackets, for example {@code [30s, 1d]}. An empty list gives {@code []}.
     */
    public static String format(List<Duration> durations) {
        return durations.stream().map(Durations::format).collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * Writes the duration as a whole number of the largest unit that divides it exactly, with the unit in
     * lower case: {@code 1d} for 24 hours, {@code 90s} for a minute and a half, {@code 1500ms} for a second
     * and a half. Zero is {@code 0s}. Any part smaller than a millisecond is dropped. A negative duration is
     * written with a minus sign, which {@link #parse} does not accept.
     *
     * @throws ArithmeticException when the duration in milliseconds does not fit in a {@code long}
     */
    public static String format(Duration duration) {
        long millis = duration.toMillis();
        if (millis != 0 && millis % 86_400_000 == 0) {
            return millis / 86_400_000 + "d";
        }
        if (millis != 0 && millis % 3_600_000 == 0) {
            return millis / 3_600_000 + "h";
        }
        if (millis != 0 && millis % 60_000 == 0) {
            return millis / 60_000 + "m";
        }
        return millis % 1_000 == 0 ? millis / 1_000 + "s" : millis + "ms";
    }
}
