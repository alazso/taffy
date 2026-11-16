package so.alaz.taffy.metrics;

import dev.faststats.ErrorTracker;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * The anonymize rules every FastStats error tracker of this module gets: email addresses, bearer tokens, AWS
 * access key ids, UUIDs, {@code api_key}, {@code apikey}, {@code token} and {@code secret} query values, whole
 * jdbc URLs, SQL statements and Windows paths.
 *
 * <p>They come on top of what the SDK already hides by itself in every report: IPv4 and IPv6 addresses, the
 * user name in a home directory path, Discord webhook ids and tokens, the password in a jdbc URL, and the
 * JVM's {@code user.name}.
 */
final class Anonymizers {

    /** Each rule as a regular expression and its replacement, applied in this order. */
    static final String[][] RULES = {
        {"[\\w.-]+@([\\w-]+\\.)+[\\w-]{2,4}", "[email hidden]"},
        {"Bearer [A-Za-z0-9._~+/=-]+", "Bearer [token hidden]"},
        {"AKIA[0-9A-Z]{16}", "[aws-key hidden]"},
        {"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "[uuid hidden]"},
        {"([?&](?:api_?key|token|secret)=)[^&\\s]+", "$1[redacted]"},
        {"jdbc:[^\\s\"']+", "jdbc:[redacted]"},
        {"(?i)\\b(SELECT|INSERT|UPDATE|DELETE)\\b[^\\n]{0,200}", "[sql hidden]"},
        {"[A-Za-z]:\\\\[^\\s\"']+", "[path hidden]"},
    };

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
