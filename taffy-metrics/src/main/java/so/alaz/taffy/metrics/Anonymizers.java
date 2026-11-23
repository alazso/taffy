package so.alaz.taffy.metrics;

import dev.faststats.ErrorTracker;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.Redaction;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * The anonymize rules every FastStats error tracker of this module gets: the rules of {@link Redaction#rules()},
 * in their order.
 *
 * <p>They come on top of what the SDK already hides by itself in every report: IPv4 and IPv6 addresses, the
 * user name in a home directory path, the password in a jdbc URL, and the JVM's {@code user.name}. The SDK
 * applies the rules of this class before its own, so its Discord webhook rule finds no {@code discord.com} once
 * the host name rule has run, and the webhook rule of this class takes its place.
 */
final class Anonymizers {

    /** Each rule of {@link Redaction#rules()} as a regular expression and its replacement, in their order. */
    static final String[][] RULES = Redaction.rules().stream()
        .map(rule -> new String[] {rule.pattern(), rule.replacement()})
        .toArray(String[][]::new);

    private static final Pattern[] PATTERNS = Arrays.stream(RULES).map(rule -> Pattern.compile(rule[0]))
        .toArray(Pattern[]::new);

    private Anonymizers() {
    }

    /**
     * {@code text} with every rule applied, as a tracker carrying the rules shows it.
     *
     * @return the hidden form of {@code text}, or {@code null} when it is {@code null}
     */
    static @Nullable String anonymize(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (int i = 0; i < PATTERNS.length; i++) {
            result = PATTERNS[i].matcher(result).replaceAll(RULES[i][1]);
        }
        return result;
    }

    /**
     * Adds every rule to {@code tracker}.
     *
     * @return the tracker the last {@code anonymize} call returned, which is {@code tracker}
     */
    static ErrorTracker apply(ErrorTracker tracker) {
        ErrorTracker result = tracker;
        for (String[] rule : RULES) {
            result = result.anonymize(rule[0], rule[1]);
        }
        return result;
    }
}
