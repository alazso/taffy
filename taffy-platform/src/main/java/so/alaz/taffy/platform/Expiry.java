package so.alaz.taffy.platform;

import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Parses an optional expiry setting. An empty or blank value means "never expires"; otherwise the
 * value is a relative duration in the form {@link Durations} accepts ({@code 30d}, {@code 12h},
 * {@code 90m}), a plain date ({@code 2026-12-31}, which expires at the end of that day in the system
 * default time zone), a local date-time ({@code 2026-12-31T23:59:59}), or a full ISO-8601 instant
 * ({@code 2026-12-31T23:59:59Z}).
 */
public final class Expiry {

    /**
     * The forms {@link #resolve} accepts, in lower case and without a full stop, as they follow
     * {@code expected} in its message.
     */
    public static final String EXPECTED = "a date (2026-12-31), a date-time (2026-12-31T23:59:59), an ISO-8601"
        + " instant (2026-12-31T23:59:59Z) or a relative duration such as 30d, 12h or 90m";

    /**
     * The forms {@link #resolveStart} accepts, in lower case and without a full stop, as they follow
     * {@code expected} in its message.
     */
    public static final String EXPECTED_START = "a date (2026-12-31), a date-time (2026-12-31T10:00:00) or an"
        + " ISO-8601 instant";

    private Expiry() {
    }

    /**
     * Resolves the configured expiry into an absolute instant, relative to {@code now}.
     *
     * @return the expiry instant, or {@code null} when the value is {@code null} or blank and so never expires
     * @throws IllegalArgumentException when the value is neither blank, a relative duration nor an
     *     absolute moment, with the message {@code invalid expiry '<raw>': expected } followed by
     *     {@link #EXPECTED}
     */
    public static @Nullable Instant resolve(@Nullable String raw, Instant now) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        Duration relative = Durations.parseOrNull(value);
        if (relative != null) {
            return now.plus(relative);
        }
        Instant absolute = parseAbsolute(value, true);
        if (absolute != null) {
            return absolute;
        }
        throw new IllegalArgumentException("invalid expiry '" + raw + "': expected " + EXPECTED);
    }

    /**
     * Parses an absolute moment: a full instant, a local date-time, or a plain date, or returns
     * {@code null} for anything else. A plain date resolves to the end of that day when
     * {@code dateAtEndOfDay} (an expiry, usable through the date) and to its start otherwise
     * (a start time).
     */
    private static @Nullable Instant parseAbsolute(String value, boolean dateAtEndOfDay) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // Not a full instant; falls through to a local date-time.
        }
        try {
            return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
            // Not a local date-time; falls through to a plain date.
        }
        try {
            LocalDate date = LocalDate.parse(value);
            return (dateAtEndOfDay ? date.plusDays(1) : date)
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /** Returns {@code true} if {@code expiry} is non-null and not after {@code now}. */
    public static boolean isExpired(@Nullable Instant expiry, Instant now) {
        return expiry != null && !expiry.isAfter(now);
    }

    /**
     * Resolves a start time. Accepts the same absolute formats as {@link #resolve}, but a plain date
     * means the start of that day (an expiry means its end), and relative durations are not accepted:
     * a start time has no reference instant to count from.
     *
     * @return the start instant, or {@code null} when {@code null} or blank (no start time is set)
     * @throws IllegalArgumentException when the value is neither blank nor an absolute moment, with the
     *     message {@code invalid start '<raw>': expected } followed by {@link #EXPECTED_START}
     */
    public static @Nullable Instant resolveStart(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Instant start = parseAbsolute(raw.trim(), false);
        if (start == null) {
            throw new IllegalArgumentException("invalid start '" + raw + "': expected " + EXPECTED_START);
        }
        return start;
    }

    /**
     * Whether {@code raw} is a relative duration (such as {@code 30d}), as opposed to blank or an
     * absolute moment. A relative expiry is measured from the instant passed to {@link #resolve}.
     */
    public static boolean isRelative(@Nullable String raw) {
        return raw != null && !raw.isBlank() && Durations.parseOrNull(raw.trim()) != null;
    }
}
