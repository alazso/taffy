package so.alaz.taffy.config;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.Durations;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Reads typed values out of a configuration and reports every value it cannot use.
 *
 * <p>Problems come in two tiers. A warning is passed to the consumer given to the constructor at once: the
 * value is replaced with the fallback of the call and reading goes on. An error is collected and returned by
 * {@link #errors()}, and the caller decides what a configuration with errors means. No method throws for a
 * value it cannot use.
 *
 * <p>A warning about a value has the form {@code <path> is <value>; expected <what>, so <fallback> is used.},
 * with the value written as {@link #describe} writes it. A key that is absent gives the fallback with no
 * warning. A single value is a text, a number, {@code true}/{@code false} or a date.
 *
 * <p>A blank text (empty or only whitespace) reads as an absent key in {@link #integer}, {@link #optionalInteger},
 * {@link #whole}, {@link #decimal}, {@link #bool}, {@link #duration}, {@link #durations}, {@link #strings},
 * {@link #parsed} and {@link #choice}: it gives the fallback with no warning. {@link #text}, {@link #quoted} and
 * {@link #strictText} return a blank text as it is, and {@link #requiredText} reports it as an error.
 *
 * <p>Every reader that gives text reads an unquoted date such as {@code 2026-12-31} as that date written
 * {@code yyyy-MM-dd}, and treats an unquoted date with a time as {@link #parsed} does: {@link #text} and
 * {@link #strings} warn and use the fallback or skip the entry, {@link #requiredText} and {@link #strictText} add
 * an error, and {@link #quoted} adds its error for any unquoted date. An unquoted date and time whose offset brings
 * it to midnight UTC, such as {@code 2026-12-31T19:00:00-05:00}, cannot be told apart from a date: {@link #text},
 * {@link #strings}, {@link #requiredText}, {@link #strictText} and {@link #parsed} read it as the UTC date,
 * {@code 2027-01-01}, with no warning.
 *
 * <p>Every path a method is called with is recorded and returned by {@link #readPaths()}, whether or not the
 * key exists. Paths are dotted and relative to the section given to the constructor.
 *
 * <p>The two list readers differ. {@link #durations} treats an empty list as invalid and uses its whole
 * fallback when one entry is not a duration. {@link #strings} accepts an empty list and skips only the entries
 * that are not single values and the unquoted dates with a time.
 *
 * <p>One instance serves one load of one configuration. It is not thread-safe.
 */
public final class ConfigReader {

    private static final Set<String> YAML_TRUE = Set.of("TRUE", "YES", "ON");
    private static final Set<String> YAML_FALSE = Set.of("FALSE", "NO", "OFF");
    private static final String QUOTE_DATE_TIME =
        " must be in quotes, because YAML reads a date and time written without them in UTC";
    private static final int DESCRIBE_LIMIT = 100;

    private final ConfigurationSection root;
    private final Consumer<String> warnings;
    private final Set<String> read = new LinkedHashSet<>();
    private final List<String> errors = new ArrayList<>();

    /**
     * @param root the section every path is relative to
     * @param warnings receives each warning line as it is found, on the calling thread
     */
    public ConfigReader(ConfigurationSection root, Consumer<String> warnings) {
        this.root = root;
        this.warnings = warnings;
    }

    /**
     * The whole number at {@code path}, or {@code fallback} when the key is absent. Only a value YAML reads as
     * an {@code int} is accepted: a text, a decimal and a whole number outside the {@code int} range each give
     * {@code fallback} and a warning.
     */
    public int integer(String path, int fallback) {
        Object value = present(path);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Integer number) {
            return number;
        }
        return invalid(path, value, "a whole number", fallback);
    }

    /**
     * Reads as {@link #integer(String, int)} does, then checks the number against {@code min} and {@code max},
     * both included. A number outside them gives {@code fallback} and the warning
     * {@code <path> is <value>; expected <min> to <max>, so <fallback> is used.} A value that is not a whole
     * number gives only the warning of {@link #integer(String, int)}.
     *
     * @throws IllegalArgumentException when {@code min} is greater than {@code max} or {@code fallback} is
     *     outside them
     */
    public int integer(String path, int fallback, int min, int max) {
        if (min > max || fallback < min || fallback > max) {
            throw new IllegalArgumentException(
                "the fallback " + fallback + " of " + path + " is not within " + min + " to " + max);
        }
        int value = integer(path, fallback);
        if (value < min || value > max) {
            warn(path + " is " + value + "; expected " + min + " to " + max + ", so " + fallback + " is used.");
            return fallback;
        }
        return value;
    }

    /**
     * The whole number at {@code path}, or {@code fallback} when the key is absent. A value YAML reads as an
     * {@code int} or a {@code long} is accepted: a text, a decimal and a whole number outside the {@code long}
     * range each give {@code fallback} and a warning.
     */
    public long whole(String path, long fallback) {
        Object value = present(path);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        return invalid(path, value, "a whole number", fallback);
    }

    /**
     * The number at {@code path} as a {@code double}, or {@code fallback} when the key is absent. Any value YAML
     * reads as a number is accepted, a whole number included, as long as it is finite. A text, {@code .inf},
     * {@code .nan} and anything else that is not a number give {@code fallback} and a warning.
     */
    public double decimal(String path, double fallback) {
        Object value = present(path);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
            return number.doubleValue();
        }
        return invalid(path, value, "a number", fallback);
    }

    /**
     * The constant of the enum of {@code fallback} named by the text at {@code path}, or {@code fallback} when
     * the key is absent. The text is matched without its surrounding whitespace and in any letter case, using
     * {@link Locale#ROOT}, so the result does not depend on the default locale. Apart from letter case a name
     * matches only as the constant is written: a hyphen does not stand for an underscore.
     *
     * <p>A text that names no constant, a number, {@code true}/{@code false}, a list and a group of keys each
     * give {@code fallback} and the warning {@code <path> is <value>; expected a, b or c, so <fallback> is
     * used.}, where the constants and the fallback are written in lower case, in declaration order.
     *
     * <p>YAML reads an unquoted {@code yes}, {@code no}, {@code on}, {@code off}, {@code true} and {@code false}
     * as {@code true} or {@code false}, so a constant with one of those names matches only when the owner writes
     * it in quotes. When the value is {@code true} or {@code false} and a constant has a name YAML reads as that
     * value, the warning ends with {@code YAML reads an unquoted yes, no, on or off as true or false, so write
     * the value in quotes, for example "<constant>".}
     */
    public <E extends Enum<E>> E choice(String path, E fallback) {
        Object value = present(path);
        if (value == null) {
            return fallback;
        }
        E[] constants = fallback.getDeclaringClass().getEnumConstants();
        if (value instanceof String text) {
            String wanted = text.trim().toUpperCase(Locale.ROOT);
            for (E constant : constants) {
                if (constant.name().toUpperCase(Locale.ROOT).equals(wanted)) {
                    return constant;
                }
            }
        }
        List<String> names = new ArrayList<>();
        for (E constant : constants) {
            names.add(lowerCase(constant));
        }
        int last = names.size() - 1;
        String expected = last == 0
            ? names.getFirst()
            : String.join(", ", names.subList(0, last)) + " or " + names.get(last);
        String line = path + " is " + describe(value) + "; expected " + expected + ", so " + lowerCase(fallback)
            + " is used.";
        if (value instanceof Boolean flag) {
            Set<String> words = flag ? YAML_TRUE : YAML_FALSE;
            for (E constant : constants) {
                if (words.contains(constant.name().toUpperCase(Locale.ROOT))) {
                    line += " YAML reads an unquoted yes, no, on or off as true or false, so write the value in"
                        + " quotes, for example \"" + lowerCase(constant) + "\".";
                    break;
                }
            }
        }
        warn(line);
        return fallback;
    }

    /**
     * The whole number at {@code path}, or {@code null} when the key is absent. Any other value gives
     * {@code null} and a warning that ends with {@code fallbackText} as what is used in its place.
     */
    public @Nullable Integer optionalInteger(String path, String fallbackText) {
        Object value = present(path);
        if (value == null || value instanceof Integer) {
            return (Integer) value;
        }
        warnInvalid(path, value, "a whole number", fallbackText);
        return null;
    }

    /**
     * The single value at {@code path} as text, with an unquoted date written as {@code 2026-12-31}. A key that
     * is absent, a blank text, a list and a group of keys each add an error and give {@code null}. The error is
     * {@code <path> is missing; <fix>.} for an absent key and {@code <path> is <value>; <fix>.} otherwise. An
     * unquoted date with a time adds the error {@code <path> must be in quotes, because YAML reads a date and time
     * written without them in UTC.} and gives {@code null}.
     *
     * @param fix what the owner has to do, without a full stop at the end
     */
    public @Nullable String requiredText(String path, String fix) {
        Object value = value(path);
        if (isDateTime(value)) {
            error(path + QUOTE_DATE_TIME + ".");
            return null;
        }
        String text = singleText(value);
        if (text != null && !text.isBlank()) {
            return text;
        }
        error(path + (value == null ? " is missing" : " is " + describe(value)) + "; " + fix + ".");
        return null;
    }

    /**
     * The single value at {@code path} as text, with an unquoted date written as {@code 2026-12-31}, or
     * {@code fallback} when the key is absent. A list and a group of keys add the error
     * {@code <path> is <value>; expected <expected>.} and give {@code null}. An unquoted date with a time adds the
     * error {@code <path> must be in quotes, because YAML reads a date and time written without them in UTC.} and
     * gives {@code null}.
     */
    public @Nullable String strictText(String path, @Nullable String fallback, String expected) {
        Object value = value(path);
        if (value == null) {
            return fallback;
        }
        if (isDateTime(value)) {
            error(path + QUOTE_DATE_TIME + ".");
            return null;
        }
        String text = singleText(value);
        if (text != null) {
            return text;
        }
        error(path + " is " + describe(value) + "; expected " + expected + ".");
        return null;
    }

    /**
     * The single value at {@code path} as text, or {@code fallback} when the key is absent. A number and
     * {@code true}/{@code false} read as their text form, an unquoted date as {@code 2026-12-31}, and a blank
     * text is returned as it is. An unquoted date with a time gives {@code fallback} and the warning
     * {@code <path> must be in quotes, because YAML reads a date and time written without them in UTC, so
     * <fallback> is used.} A list and a group of keys give {@code fallback} and a warning.
     */
    public String text(String path, String fallback) {
        Object value = value(path);
        if (value == null) {
            return fallback;
        }
        if (isDateTime(value)) {
            warn(path + QUOTE_DATE_TIME + ", so " + fallback + " is used.");
            return fallback;
        }
        String text = singleText(value);
        return text != null ? text : invalid(path, value, "a single value", fallback);
    }

    /**
     * Reads as {@link #text} does, for a value that has to be written in quotes. A value YAML read as a number,
     * a date or {@code true}/{@code false} may no longer be what the file says ({@code 0451} is read as a number
     * and {@code 2026-12-31T10:00:00} as a time in UTC), so it adds the error {@code <path> must be in quotes,
     * because YAML changes an unquoted value it reads as a number, a date or true/false.} and gives
     * {@code fallback}.
     */
    public String quoted(String path, String fallback) {
        Object value = value(path);
        if (value instanceof Number || value instanceof Boolean || value instanceof Date) {
            error(path + " must be in quotes, because YAML changes an unquoted value it reads as a number, a date"
                + " or true/false.");
            return fallback;
        }
        return text(path, fallback);
    }

    /**
     * The {@code true} or {@code false} at {@code path}, or {@code fallback} when the key is absent. Only a
     * value YAML reads as a boolean is accepted: anything else gives {@code fallback} and a warning.
     */
    public boolean bool(String path, boolean fallback) {
        Object value = present(path);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        return invalid(path, value, "true or false", fallback);
    }

    /**
     * The duration at {@code path} in the form {@link Durations#parseOrNull} accepts, or {@code fallback} when
     * the key is absent. Anything else gives {@code fallback} and a warning that names
     * {@link Durations#EXPECTED} as the expected form and writes the fallback as
     * {@link Durations#format(Duration)} does.
     */
    public Duration duration(String path, Duration fallback) {
        Object value = present(path);
        if (value == null) {
            return fallback;
        }
        Duration duration = Durations.parseOrNull(value);
        if (duration != null) {
            return duration;
        }
        warnInvalid(path, value, Durations.EXPECTED, Durations.format(fallback));
        return fallback;
    }

    /**
     * Reads as {@link #duration(String, Duration)} does, then checks the duration against {@code min} and
     * {@code max}, both included. A duration outside them gives {@code fallback} and the warning
     * {@code <path> is <value>; expected <min> to <max>, so <fallback> is used.}, with each duration written
     * as {@link Durations#format(Duration)} does. A value that is not a duration gives only the warning of
     * {@link #duration(String, Duration)}.
     *
     * @throws IllegalArgumentException when {@code min} is longer than {@code max} or {@code fallback} is
     *     outside them
     */
    public Duration duration(String path, Duration fallback, Duration min, Duration max) {
        if (min.compareTo(max) > 0 || fallback.compareTo(min) < 0 || fallback.compareTo(max) > 0) {
            throw new IllegalArgumentException("the fallback " + Durations.format(fallback) + " of " + path
                + " is not within " + Durations.format(min) + " to " + Durations.format(max));
        }
        Duration value = duration(path, fallback);
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            warn(path + " is " + Durations.format(value) + "; expected " + Durations.format(min) + " to "
                + Durations.format(max) + ", so " + Durations.format(fallback) + " is used.");
            return fallback;
        }
        return value;
    }

    /**
     * The list of durations at {@code path}, such as {@code [2s, 5s, 15s]}, or {@code fallback} when the key is
     * absent. A value that is not a list, an empty list and a list with an entry that is not a duration each
     * give {@code fallback} as a whole and one warning.
     *
     * @return an unmodifiable list, or {@code fallback} itself
     */
    public List<Duration> durations(String path, List<Duration> fallback) {
        Object value = present(path);
        if (value == null) {
            return fallback;
        }
        if (value instanceof List<?> list && !list.isEmpty()) {
            List<Duration> durations = new ArrayList<>();
            for (Object entry : list) {
                Duration duration = Durations.parseOrNull(entry);
                if (duration == null) {
                    break;
                }
                durations.add(duration);
            }
            if (durations.size() == list.size()) {
                return List.copyOf(durations);
            }
        }
        warnInvalid(path, value, "a list of durations such as [2s, 5s, 15s]", Durations.format(fallback));
        return fallback;
    }

    /**
     * The list of single values at {@code path}, each as text and in file order, or {@code fallback} when the
     * key is absent. An empty list is valid. An unquoted date entry is kept as {@code 2026-12-31}. An entry that
     * is not a single value is skipped with the warning
     * {@code <path> has <entry>, which is not a single value, so it is skipped.}, an unquoted date with a time
     * with the warning {@code <path> has <entry>, which must be in quotes, because YAML reads a date and time
     * written without them in UTC, so it is skipped.}, and the other entries are kept. A value that is not a list
     * gives {@code fallback} and a warning.
     *
     * @return an unmodifiable list, or {@code fallback} itself
     */
    public List<String> strings(String path, List<String> fallback) {
        Object value = present(path);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof List<?> list)) {
            warnInvalid(path, value, "a list such as [A, B]", fallback.toString());
            return fallback;
        }
        List<String> strings = new ArrayList<>();
        for (Object entry : list) {
            String text = singleText(entry);
            if (text != null) {
                strings.add(text);
            } else if (isDateTime(entry)) {
                warn(path + " has " + describe(entry) + ", which" + QUOTE_DATE_TIME + ", so it is skipped.");
            } else {
                warn(path + " has " + (entry == null ? "an empty entry" : describe(entry)) + ", which is not a"
                    + " single value, so it is skipped.");
            }
        }
        return List.copyOf(strings);
    }

    /**
     * The single value at {@code path}, turned into a {@code T} by {@code parser}, or {@code fallback} when the
     * key is absent. {@code parser} receives the text form of the value and rejects it by throwing
     * {@link IllegalArgumentException}. A rejected value, a list and a group of keys each give {@code fallback}
     * and a warning built from {@code expected}. The message of the exception is not shown, and {@code parser}
     * is not called for a list or a group of keys.
     *
     * <p>An unquoted date such as {@code 2026-12-31}, which YAML reads as midnight UTC, is a single value here
     * too, and {@code parser} receives it written {@code yyyy-MM-dd}, as YAML read it. YAML reads an unquoted
     * date with a time as UTC whether the text names a zone or not, so such a value gives {@code fallback} and
     * the warning {@code <path> must be in quotes, because YAML reads a date and time written without them in
     * UTC, so <fallback> is used.} An unquoted date and time at midnight UTC, such as {@code 2026-12-31T00:00:00Z}, is
     * indistinguishable from the date and reaches {@code parser} as {@code 2026-12-31}.
     *
     * <p>The warning writes the fallback with {@link String#valueOf(Object)}, so {@code T} needs a
     * {@code toString} an owner can read. {@link #parsed(String, Object, String, String, Function)} takes the
     * text of the fallback instead.
     *
     * @param expected the accepted form, as it reads after {@code "expected "} in the warning
     */
    public <T> T parsed(String path, T fallback, String expected, Function<String, T> parser) {
        return parse(path, fallback, null, expected, parser);
    }

    /**
     * Reads as {@link #parsed(String, Object, String, Function)} does, with {@code fallbackText} in place of the
     * fallback in every warning.
     *
     * @param fallbackText the fallback as the owner reads it, such as {@code 30s} or {@code never}
     * @param expected the accepted form, as it reads after {@code "expected "} in the warning
     */
    public <T> T parsed(
        String path, T fallback, String fallbackText, String expected, Function<String, T> parser) {
        return parse(path, fallback, fallbackText, expected, parser);
    }

    private <T> T parse(
        String path, T fallback, @Nullable String fallbackText, String expected, Function<String, T> parser) {
        Object value = present(path);
        if (value == null) {
            return fallback;
        }
        String shown = fallbackText == null ? String.valueOf(fallback) : fallbackText;
        if (isDateTime(value)) {
            warn(path + QUOTE_DATE_TIME + ", so " + shown + " is used.");
            return fallback;
        }
        String text = singleText(value);
        if (text == null) {
            warnInvalid(path, value, expected, shown);
            return fallback;
        }
        try {
            return parser.apply(text);
        } catch (IllegalArgumentException rejected) {
            warnInvalid(path, value, expected, shown);
            return fallback;
        }
    }

    /**
     * The names of the keys directly beneath the group of keys at {@code path}, in file order. An absent key
     * gives an empty set. A value that is not a group of keys gives an empty set and the warning
     * {@code <path> should hold keys beneath it, so its value is ignored.}
     */
    public Set<String> children(String path) {
        read.add(path);
        ConfigurationSection section = root.getConfigurationSection(path);
        if (section != null) {
            return section.getKeys(false);
        }
        if (root.contains(path)) {
            warn(path + " should hold keys beneath it, so its value is ignored.");
        }
        return Set.of();
    }

    /** Whether {@code path} is absent or holds a group of keys. */
    public boolean absentOrSection(String path) {
        read.add(path);
        return !root.contains(path) || root.isConfigurationSection(path);
    }

    /** Whether {@code path} holds a group of keys. */
    public boolean isSection(String path) {
        read.add(path);
        return root.isConfigurationSection(path);
    }

    /** Passes {@code message} to the warning consumer. */
    public void warn(String message) {
        warnings.accept(message);
    }

    /** Adds {@code message} to {@link #errors()}. */
    public void error(String message) {
        errors.add(message);
    }

    /** An unmodifiable copy of the errors collected so far, in the order they were added. */
    public List<String> errors() {
        return List.copyOf(errors);
    }

    /**
     * The paths read so far, each once, in the order of their first read. The set is an unmodifiable view that
     * follows later reads.
     */
    public Set<String> readPaths() {
        return Collections.unmodifiableSet(read);
    }

    /**
     * The leaf keys of the root that are not in {@link #readPaths()}, in file order, as an unmodifiable list. A
     * leaf key is one that does not hold a group of keys; a list is one leaf, since its entries are not keys.
     *
     * <p>Only the path a method is called with is recorded, so the leaves beneath a group of keys read whole
     * through {@link #value} or {@link #children} are listed as unread. A caller that reads a group that way
     * leaves its paths out itself. A configuration from {@link ConfigLoader} holds
     * {@link ConfigLoader#VERSION_KEY}, which is listed unless it was read.
     */
    public List<String> unreadKeys() {
        return root.getKeys(true).stream()
            .filter(path -> !root.isConfigurationSection(path))
            .filter(path -> !read.contains(path))
            .toList();
    }

    /** The section given to the constructor. Nothing is recorded as read. */
    public ConfigurationSection root() {
        return root;
    }

    /**
     * The raw value at {@code path}, recorded as read: a {@link ConfigurationSection} for a group of keys, a
     * {@link List} for a list, the value itself for a single value, or {@code null} when the key is absent.
     */
    public @Nullable Object value(String path) {
        read.add(path);
        return root.isConfigurationSection(path) ? root.getConfigurationSection(path) : root.get(path);
    }

    /**
     * How a warning or an error writes a value: {@code a group of keys} for a {@link ConfigurationSection}; a
     * text inside double quotes, cut to its first 100 characters followed by {@code ...} when longer; a
     * {@link Date} in UTC as {@code 2026-12-31} when it falls on midnight and as {@code 2026-12-31T23:59:59Z}
     * otherwise; and anything else as {@link String#valueOf(Object)} writes it. When that is longer than 100
     * characters, a list is written as {@code a list of <n> entries} (or {@code a list of 1 entry}), a
     * {@link Map}, such as a group of keys inside a list, as {@code a group of <n> keys} (or
     * {@code a group of 1 key}), and any other value as its first 100 characters followed by {@code ...}.
     */
    public static String describe(@Nullable Object value) {
        if (value instanceof ConfigurationSection) {
            return "a group of keys";
        }
        if (value instanceof Date date) {
            return dateText(date);
        }
        if (value instanceof String text) {
            return text.length() > DESCRIBE_LIMIT ? "\"" + cut(text) + "\"..." : "\"" + text + "\"";
        }
        String written = String.valueOf(value);
        if (written.length() <= DESCRIBE_LIMIT) {
            return written;
        }
        if (value instanceof List<?> list) {
            return list.size() == 1 ? "a list of 1 entry" : "a list of " + list.size() + " entries";
        }
        if (value instanceof Map<?, ?> map) {
            return map.size() == 1 ? "a group of 1 key" : "a group of " + map.size() + " keys";
        }
        return cut(written) + "...";
    }

    /** The first {@code DESCRIBE_LIMIT} characters of {@code text}, one fewer where that splits a surrogate pair. */
    private static String cut(String text) {
        int end = Character.isHighSurrogate(text.charAt(DESCRIBE_LIMIT - 1))
            && Character.isLowSurrogate(text.charAt(DESCRIBE_LIMIT)) ? DESCRIBE_LIMIT - 1 : DESCRIBE_LIMIT;
        return text.substring(0, end);
    }

    /**
     * The text form of a single value: a text as it is, a number and {@code true}/{@code false} as their
     * {@code toString}, and a date at midnight UTC as {@code 2026-12-31}. Anything else gives {@code null}.
     */
    private static @Nullable String singleText(@Nullable Object value) {
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return value instanceof Date date && midnightUtc(date) ? dateText(date) : null;
    }

    /** Whether {@code value} is a date that YAML read from an unquoted date with a time not at midnight UTC. */
    private static boolean isDateTime(@Nullable Object value) {
        return value instanceof Date date && !midnightUtc(date);
    }

    /** YAML builds a date written without a time as midnight UTC, so that instant is written back as a date. */
    private static String dateText(Date date) {
        Instant instant = date.toInstant();
        return midnightUtc(date) ? LocalDate.ofInstant(instant, ZoneOffset.UTC).toString() : instant.toString();
    }

    private static boolean midnightUtc(Date date) {
        Instant instant = date.toInstant();
        return instant.equals(instant.truncatedTo(ChronoUnit.DAYS));
    }

    /** {@link #value}, with a blank text read as absent. */
    private @Nullable Object present(String path) {
        Object value = value(path);
        return value instanceof String text && text.isBlank() ? null : value;
    }

    private static String lowerCase(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT);
    }

    private <T> T invalid(String path, Object value, String expected, T fallback) {
        warnInvalid(path, value, expected, String.valueOf(fallback));
        return fallback;
    }

    private void warnInvalid(String path, Object value, String expected, String fallback) {
        warn(path + " is " + describe(value) + "; expected " + expected + ", so " + fallback + " is used.");
    }
}
