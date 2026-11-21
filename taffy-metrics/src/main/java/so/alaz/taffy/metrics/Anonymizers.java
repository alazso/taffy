package so.alaz.taffy.metrics;

import dev.faststats.ErrorTracker;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * The anonymize rules every FastStats error tracker of this module gets, in the order they apply:
 *
 * <ol>
 *   <li>a whole jdbc URL, up to the next whitespace or quote. A jdbc URL whose third part is an absolute path,
 *       also behind {@code file:}, such as {@code jdbc:sqlite:C:\Users\John Smith\server\data.db} or
 *       {@code jdbc:sqlite:/home/john/My Server/data.db}, is read with the folder names of rule 12, spaces
 *       included;</li>
 *   <li>the user name and password before the {@code @} of a URI of any scheme, such as
 *       {@code redis://default:hunter2@cache:6379}, when both are present. The password runs to the last
 *       {@code @} before the next whitespace when that {@code @} is at most 128 characters in and no
 *       {@code ://} comes before it, a {@code /} included, and otherwise to the last {@code @} before the next
 *       {@code /}. What follows the {@code :} is read as a port when it is 1 to 5 digits that end the text or
 *       that whitespace or one of {@code / ? # [ ] { } "} follows, as in
 *       {@code http://localhost:8080/users/a@b} or {@code [redis://cache-1:6379]}, so a password of that shape
 *       stays;</li>
 *   <li>an email address whose last label has two or more characters;</li>
 *   <li>a bearer token;</li>
 *   <li>an AWS access key id;</li>
 *   <li>a UUID in either letter case, with its dashes, also inside a longer word such as
 *       {@code data_<uuid>}, or as 32 hex digits without them that no further hex digit touches;</li>
 *   <li>the quoted user and host of a MySQL or MariaDB account, as in {@code 'mc_prod'@'db.example.net'};</li>
 *   <li>the double-quoted name after the word {@code user} or {@code role} in either letter case, as PostgreSQL
 *       writes it in {@code password authentication failed for user "mc_prod"}, when the name is 1 to 64
 *       characters without whitespace;</li>
 *   <li>an {@code api_key}, {@code apikey}, {@code token} or {@code secret} query value;</li>
 *   <li>the value after {@code =} or {@code :} of a key that holds {@code password}, {@code passwd},
 *       {@code pwd}, {@code secret}, {@code token}, {@code api_key}, {@code access_key} or {@code private_key}
 *       in either letter case, with {@code -} or nothing in place of the {@code _}. The value {@code YES} or
 *       {@code NO} stays, so {@code (using password: YES)} reads as the driver wrote it. The plain word
 *       {@code token}, {@code tokens}, {@code secret} or {@code secrets} before a {@code :} is no key when a
 *       letter, a digit or a {@code _} and whitespace come before it, so {@code Invalid token: expected ;}
 *       stays. It is a key when it is quoted, when it starts its line, also after the {@code -} of a list item,
 *       after any other character and up to 8 spaces or tabs, as in {@code Loaded {token: abc}},
 *       {@code login failed, token: abc} or {@code discord: token: abc}, and when its value has 6 or more
 *       characters with a digit among the first 65, as in {@code Bot token: abc123}. Such a word is a key before a
 *       {@code =}, and so is a longer name around it before either sign, such as {@code bot-token},
 *       {@code discord.token}, {@code tokenValue} or {@code secretKey};</li>
 *   <li>an SQL statement that starts with {@code SELECT ... FROM}, {@code INSERT INTO} (also with
 *       {@code IGNORE} or {@code OR} and a word between the two), {@code UPDATE ... SET},
 *       {@code DELETE FROM}, {@code REPLACE INTO}, {@code MERGE INTO}, {@code CREATE TABLE},
 *       {@code ALTER TABLE} or {@code DROP TABLE} in either letter case, with what follows that start on the
 *       line, up to 200 characters. One of these verbs without the rest, as in
 *       {@code Failed to update balance for shop 12}, stays, and so does a statement of another shape. A
 *       sentence that holds one of these starts, such as {@code Please select a kit from the list}, is hidden
 *       from that word on;</li>
 *   <li>a {@code file:} URL that no letter or digit precedes, up to the next whitespace or quote, and a Windows
 *       path that starts with a drive letter and a backslash, or with a drive letter that no letter or digit
 *       precedes and one {@code /}. Each of the first 64 folder names of the path holds up to three single
 *       spaces, as in {@code C:\Users\John Smith\Desktop\x.yml}. A space ends the path when no {@code \} or
 *       {@code /} follows within three more words, and when the word before it ends in a file extension (a dot,
 *       a letter and up to four more letters or digits), so {@code C:\srv\x.yml is not valid/readable} keeps
 *       its words. A path without such an extension takes the words up to that slash with it, as in
 *       {@code C:\srv\data and logs/x};</li>
 *   <li>an absolute Unix path of one segment and a further slash or more, such as {@code /home/} or
 *       {@code /etc/hosts}. {@code /home} alone stays;</li>
 *   <li>the id and token of a Discord webhook URL that starts with {@code https://} on {@code discord.com} or
 *       {@code discordapp.com}, also with a {@code canary} or {@code ptb} label or an API version, and its
 *       query;</li>
 *   <li>a host name of two or more labels that ends in {@code com}, {@code net}, {@code org}, {@code io},
 *       {@code gg}, {@code dev}, {@code me}, {@code co}, {@code xyz}, {@code eu}, {@code de}, {@code uk},
 *       {@code us}, {@code fr}, {@code nl}, {@code ca}, {@code au}, {@code ru}, {@code br}, {@code pl},
 *       {@code internal}, {@code local} or {@code lan}. A name that a further label follows, such as the class
 *       name {@code org.apache.commons.io.FileUtils}, stays. So does a name whose first label is
 *       {@code java}, {@code javax}, {@code jdk}, {@code sun}, {@code kotlin}, {@code com}, {@code org} or
 *       {@code net}, such as {@code java.io} or {@code com.example.Me}, and a name of two labels of two
 *       letters each, such as {@code pt.br}. Any other dotted name whose last label is one of these endings is
 *       hidden, a file name, a class name and a configuration key included, such as {@code messages.de},
 *       {@code me.john.Me} or {@code shop.me};</li>
 *   <li>the double-quoted name after the word {@code database} in either letter case, as PostgreSQL writes it
 *       in {@code no pg_hba.conf entry for host "10.0.0.5", user "mc_prod", database "survival_prod"}, when
 *       the name is 1 to 64 characters without whitespace;</li>
 *   <li>the host after the {@code ://} of a URI of any scheme, with any ending and also of one label, such as
 *       {@code cache} in {@code redis://cache:6379}, together with a user name before an {@code @}. The host
 *       {@code localhost} stays;</li>
 *   <li>a dotted name of two or more labels with any ending that follows {@code UnknownHostException:},
 *       {@code connect to}, {@code connecting to}, {@code connection to}, {@code connected to},
 *       {@code route to}, {@code host} or {@code host is known} in either letter case and one space, also
 *       behind an opening quote or parenthesis, such as {@code db.myserver.host} in
 *       {@code Connection to db.myserver.host:5432 refused}. The same holds after a key that ends in
 *       {@code host}, {@code hostname} or {@code address} and a {@code =} or {@code :}, also with a quote
 *       around the key, one space on either side of the sign and up to two opening quotes or brackets, as in
 *       {@code address=(host=db.myserver.host)}, {@code No route to host: db.myserver.host} or
 *       {@code "host":"db.myserver.host"}, after {@code refused:}, {@code timed out:} or
 *       {@code timed out after 30000 ms:} and one space, and after the {@code .} and space that end the
 *       {@code address=(host=...)(port=...)(type=...)} of a MariaDB driver message, which repeats the host
 *       there. A name stays when it starts with digits, a dot and a digit, such as {@code 1.21.4}, when its
 *       last label starts with an upper case letter, such as {@code so.alaz.shop.Sync}, when a {@code (}
 *       follows it, such as {@code player.getName()}, and when its last label is {@code yml}, {@code yaml},
 *       {@code json}, {@code properties}, {@code toml}, {@code conf}, {@code cfg}, {@code xml}, {@code txt},
 *       {@code log}, {@code csv}, {@code sql}, {@code db}, {@code sqlite}, {@code dat}, {@code jar},
 *       {@code class}, {@code java}, {@code lock}, {@code tmp} or {@code bak}, such as {@code config.yml}. Any
 *       other dotted name after these words is hidden, such as {@code shop.balance} in
 *       {@code host shop.balance}.</li>
 * </ol>
 *
 * <p>Outside a URI and those words, a host name of one label, such as {@code cache} or {@code localhost}, stays,
 * and so does a host name with another ending.
 *
 * <p>They come on top of what the SDK already hides by itself in every report: IPv4 and IPv6 addresses, the
 * user name in a home directory path, the password in a jdbc URL, and the JVM's {@code user.name}. The SDK
 * applies the rules of this class before its own, so its Discord webhook rule finds no {@code discord.com} once
 * the host name rule has run, and the webhook rule of this class takes its place.
 *
 * <p>Every rule runs in time linear in the length of the text.
 */
final class Anonymizers {

    private static final String HOST_ENDINGS =
        "com|net|org|io|gg|dev|me|co|xyz|eu|de|uk|us|fr|nl|ca|au|ru|br|pl|internal|local|lan";

    private static final String PACKAGE_ROOTS = "java|javax|jdk|sun|kotlin|com|org|net";

    private static final String SECRET_NAMES = "password|passwd|pwd|api[_-]?key|access[_-]?key|private[_-]?key";

    private static final String SECRET_WORDS = "secret|token";

    private static final String PATH_NAME = "[^\\s\"'\\\\/:]+";

    private static final String PATH_FOLDERS = "(?:(?:(?![^\\s\"'\\\\/:]*\\.[A-Za-z][A-Za-z0-9]{0,4} )" + PATH_NAME
        + " ){0,3}" + PATH_NAME + "[\\\\/]){0,64}";

    private static final String FILE_ENDINGS =
        "yml|yaml|json|properties|toml|conf|cfg|xml|txt|log|csv|sql|db|sqlite|dat|jar|class|java|lock|tmp|bak";

    private static final String NAME_END = "(?![\\w-]|\\.[\\w-])";

    /**
     * Each rule as a regular expression and its replacement, applied in this order.
     *
     * <p>The jdbc rule reads to the next whitespace and the placeholders hold a space, so it runs before the rules
     * that match inside a jdbc URL. The last rule reads the text before a match, which a replacement of the same
     * rule leaves as it was. The Discord webhook rule needs the host name, so it runs before the host name
     * rules. The file URL and Windows path rule runs before the Unix path rule and the URI host rule, which
     * match inside what it hides.
     */
    static final String[][] RULES = {
        {"jdbc:\\w+:(?:(?i:file):)?(?:[A-Za-z]:[\\\\/]|/(?!/))" + PATH_FOLDERS + "[^\\s\"']*|jdbc:[^\\s\"']+",
            "jdbc:[redacted]"},
        {"(?i)([a-z][a-z0-9+.-]{0,31}://)[^/\\s:@]*:(?!\\d{1,5}(?![^\\s/?#\\[\\]{}\"]))"
            + "(?:(?:(?!://)\\S){0,128}|[^\\s/]*)@", "$1[credentials hidden]@"},
        {"(?<![\\w.-])[\\w.-]+@[\\w-][\\w.-]*\\.[\\w-]{2,}(?![\\w-])", "[email hidden]"},
        {"Bearer [A-Za-z0-9._~+/=-]+", "Bearer [token hidden]"},
        {"AKIA[0-9A-Z]{16}", "[aws-key hidden]"},
        {"(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
            + "|(?<![0-9a-f])[0-9a-f]{32}(?![0-9a-f])", "[uuid hidden]"},
        {"'[^'\\s]*'@'[^'\\s]*'", "[user hidden]@[host hidden]"},
        {"(?i)(\\b(?:user|role)\\s+)\"[^\"\\s]{1,64}\"", "$1[user hidden]"},
        {"([?&](?:api_?key|token|secret)=)[^&\\s]+", "$1[redacted]"},
        {"(?im)((?:(?:" + SECRET_NAMES + "|" + SECRET_WORDS + ")[\\w-]{0,20}[\"']?\\s*="
            + "|(?:" + SECRET_NAMES + ")[\\w-]{0,20}[\"']?\\s*:"
            + "|(?<=[\\w.-])(?:" + SECRET_WORDS + ")[\\w-]{0,20}[\"']?\\s*:"
            + "|(?:" + SECRET_WORDS + ")(?!s?(?![\\w-]))[\\w-]{1,20}[\"']?\\s*:"
            + "|[\"'](?:" + SECRET_WORDS + ")s?[\"']\\s*:"
            + "|^[ \\t]*(?:-[ \\t]+)?(?:" + SECRET_WORDS + ")s?\\s*:"
            + "|(?<=[^\\w\\s][ \\t]{0,8})(?:" + SECRET_WORDS + ")s?\\s*:"
            + "|(?:" + SECRET_WORDS + ")s?:(?=[ \\t]{0,8}[\"']?(?=[^\\s\"'&,;)]{6})[^\\s\"'&,;)\\d]{0,64}\\d))\\s*)"
            + "(?!(?:yes|no)\\b)(?:\"[^\"\\n]*\"|'[^'\\n]*'|[^\\s\"'&,;)]+)", "$1[redacted]"},
        {"(?i)\\b(?:SELECT\\s[^\\n]{0,200}?\\sFROM\\s|INSERT\\s+(?:OR\\s+\\w+\\s+|IGNORE\\s+)?INTO\\s"
            + "|UPDATE\\s+\\S+\\s+SET\\s|DELETE\\s+FROM\\s|REPLACE\\s+INTO\\s|MERGE\\s+INTO\\s"
            + "|(?:CREATE|ALTER|DROP)\\s+TABLE\\s)[^\\n]{0,200}", "[sql hidden]"},
        {"(?<![A-Za-z0-9])(?i:file):/+[^\\s\"']+|(?:[A-Za-z]:\\\\|(?<![A-Za-z0-9])[A-Za-z]:/(?!/))"
            + PATH_FOLDERS + "[^\\s\"']*", "[path hidden]"},
        {"(?<![\\w./:-])/[\\w.-]+/[\\w./-]*", "[path hidden]"},
        {"(?i)(https://(?:(?:canary|ptb)\\.)?discord(?:app)?\\.com/api/(?:v\\d+/)?webhooks/)\\d+/[\\w-]+(?:\\?\\S*)?",
            "$1[id hidden]/[token hidden]"},
        {"(?i)(?<![\\w.-])(?!(?:" + PACKAGE_ROOTS + ")\\.)(?![a-z]{2}\\.[a-z]{2}(?![\\w-]|\\.[\\w-]))"
            + "[\\w.-]+\\.(?:" + HOST_ENDINGS + ")" + NAME_END, "[host hidden]"},
        {"(?i)(\\bdatabase\\s+)\"[^\"\\s]{1,64}\"", "$1[database hidden]"},
        {"(?i)(://(?:\\[credentials hidden\\]@)?)(?:[\\w.~%+-]{0,64}@)?(?!localhost(?![\\w.-]))[\\w.-]+",
            "$1[host hidden]"},
        {"(?i)((?:UnknownHostException:|\\b(?:connect|connecting|connection|connected|route) to"
            + "|\\bhost(?: is known)?) [\"'(]?"
            + "|(?:host(?:name)?|address)[\"']?\\s?[=:]\\s?[\"'(\\[{]{0,2}"
            + "|\\b(?:refused|timed out(?: after \\d{1,9} ?ms)?): "
            + "|\\. (?<=address=\\(host=[^()\\s]{1,255}\\)" + "(?:\\([^()\\s]{0,64}\\))?".repeat(4) + "\\. ))"
            + "(?!\\d+\\.\\d)(?![\\w.-]*\\.(?-i:[A-Z])[\\w-]*" + NAME_END + ")"
            + "(?![\\w.-]*\\.(?:" + FILE_ENDINGS + ")" + NAME_END + ")(?![\\w.-]*\\()"
            + "[\\w-]+\\.[\\w.-]*[\\w-]", "$1[host hidden]"},
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
