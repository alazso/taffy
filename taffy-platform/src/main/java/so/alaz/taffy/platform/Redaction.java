package so.alaz.taffy.platform;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Hides secrets and personal data in free text, such as the message of an exception, before the text is shown
 * where others can read it. {@link #hide} applies these rules, in this order:
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
 *   <li>an email address whose last label has two or more characters, with a {@code +} in the name before the
 *       {@code @}, as in {@code john+tag@example.org};</li>
 *   <li>a bearer token, and what follows {@code Authorization: Basic} in either letter case and 1 to 4 spaces,
 *       also with a {@code =} or {@code ,} for the {@code :}, a quote after the key, up to four spaces around that
 *       sign and up to two quotes or opening brackets before {@code Basic}, as in
 *       {@code {Authorization=[Basic dXNlcjpwYXNz]}} or {@code setHeader("Authorization", "Basic dXNlcjpwYXNz")}.
 *       One word of letters with no upper case letter after its first stays, so
 *       {@code Authorization: Basic authentication is not supported} reads as written;</li>
 *   <li>an AWS access key id;</li>
 *   <li>a UUID in either letter case, with its dashes, also inside a longer word such as
 *       {@code data_<uuid>}, or as 32 hex digits without them that no further hex digit touches;</li>
 *   <li>the quoted user and host of a MySQL or MariaDB account, as in {@code 'mc_prod'@'db.example.net'};</li>
 *   <li>the double-quoted name after the word {@code user} or {@code role} in either letter case, as PostgreSQL
 *       writes it in {@code password authentication failed for user "mc_prod"}, when the name is 1 to 64
 *       characters without whitespace;</li>
 *   <li>an {@code api_key}, {@code apikey}, {@code token} or {@code secret} query value;</li>
 *   <li>the value after {@code =} or {@code :} of a key that holds {@code password}, {@code passwd},
 *       {@code pwd}, {@code passphrase}, {@code secret}, {@code token}, {@code api_key}, {@code access_key},
 *       {@code private_key}, {@code license_key} or {@code licence_key} in either letter case, with {@code -} or
 *       nothing in place of the {@code _}, and the value after the {@code =} of a key that ends in {@code pass}
 *       with no letter before it, such as {@code pass=} or {@code db_pass=}, or in {@code Pass} after a lower
 *       case letter, such as {@code dbPass=}. Such a key in quotes is a key before a {@code :} too, as in
 *       {@code "db_pass": "x"}, and its value of 1 to 3 digits stays before either sign, so
 *       {@code pass=3 fail=2} reads as written. A {@code =>} counts as the {@code =}. The value is one word or a
 *       quoted text. It is the rest of the line when a key that ends in {@code password}, {@code passwd},
 *       {@code pwd} or {@code passphrase} starts its line with up to 64 characters before that word, also after
 *       the {@code -} of a list item, and the value starts with no quote, as in {@code password: my pass word}.
 *       A value that starts with {@code -} and a space is a list: it is the rest of that line and every
 *       following line that starts with {@code -} and a space, also past blank lines and lines that hold only a
 *       {@code #} comment, so the items below {@code tokens:} are hidden as one value. The value {@code YES} or
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
 *       {@code FROM} or {@code INTO} that {@code the}, {@code an}, {@code this}, {@code that}, {@code these},
 *       {@code those}, {@code your}, {@code my}, {@code our} or {@code their} follows starts no statement, so
 *       {@code Could not delete from the cache} stays, when spaces and a letter come after that word and none of
 *       these stands in the 200 characters after it, on its line or a later one and before the next of the verbs
 *       above: {@code WHERE}, {@code VALUES (}, {@code DEFAULT VALUES}, {@code RETURNING}, {@code ORDER BY},
 *       {@code GROUP BY}, {@code HAVING}, {@code LIMIT} and a digit, {@code SET} with a name and a {@code =},
 *       {@code JOIN} or {@code USING} with {@code ON} at most three words later, {@code --} or the start of a
 *       block comment. After {@code SELECT} this holds only when 1 to 8 words of letters stand between it and
 *       {@code FROM}, so {@code Please select a kit from the list} stays and {@code SELECT * FROM the list} is
 *       hidden. A statement whose table has one of the ten words as its name and that holds none of those, such
 *       as {@code SELECT name FROM the t}, stays as well. Any other sentence that holds one of these starts,
 *       such as {@code Could not delete from disk cache}, is hidden from that word on;</li>
 *   <li>a {@code file:} URL that no letter or digit precedes, up to the next whitespace or quote, and a Windows
 *       path that starts with a drive letter and a backslash, or with a drive letter that no letter or digit
 *       precedes and one {@code /}, with two backslashes, a server name of letters, digits, {@code _},
 *       {@code .}, {@code $} and {@code -} and a backslash, as in
 *       {@code \\files\share\John\x.yml}, or with {@code Users} in either letter case that no letter or digit
 *       precedes and a backslash, as in {@code ..\Users\John\x.yml}, which is hidden from {@code Users} on.
 *       Each of the first 64 folder names of the path holds up to three single
 *       spaces, as in {@code C:\Users\John Smith\Desktop\x.yml}. A space ends the path when no {@code \} or
 *       {@code /} follows within three more words, and when the word before it ends in a file extension (a dot,
 *       a letter and up to four more letters or digits), so {@code C:\srv\x.yml is not valid/readable} keeps
 *       its words. A path without such an extension takes the words up to that slash with it, as in
 *       {@code C:\srv\data and logs/x};</li>
 *   <li>an absolute Unix path of one segment and a further slash or more, such as {@code /home/} or
 *       {@code /etc/hosts}. {@code /home} alone stays. Each of the next 64 folder names holds up to three single
 *       spaces between words of letters, digits, {@code _}, {@code .} and {@code -}, as in
 *       {@code /home/john smith/server/x}, and a space ends the path as it does in the rule before. A space
 *       also ends it before the lower case word {@code to}, {@code at}, {@code in}, {@code is}, {@code on},
 *       {@code from}, {@code after}, {@code with}, {@code uses}, {@code has}, {@code was} or {@code not}, so
 *       {@code Failed to save /srv/world after 3 attempts/retries} keeps the words after the path;</li>
 *   <li>the id and token of a Discord webhook URL that starts with {@code https://} or {@code http://} on
 *       {@code discord.com} or {@code discordapp.com}, also with a {@code canary} or {@code ptb} label or an API
 *       version, and its query;</li>
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
 *       in {@code no pg_hba.conf entry for host "10.0.0.5", user "mc_prod", database "survival_prod"}, or the
 *       single-quoted one, as MySQL and MariaDB write it in {@code Unknown database 'survival_prod'}, when the
 *       name is 1 to 64 characters without whitespace;</li>
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
 *       {@code host shop.balance};</li>
 *   <li>a token that no letter, digit, {@code _} or {@code -} precedes and that starts with {@code ghp_},
 *       {@code gho_}, {@code ghu_}, {@code ghs_}, {@code ghr_}, {@code github_pat_}, {@code xoxa-}, {@code xoxb-},
 *       {@code xoxe-}, {@code xoxo-}, {@code xoxp-}, {@code xoxr-} or {@code xoxs-} and 8 or more further
 *       characters, or with {@code sk_live_} or {@code rk_live_} and 16 or more, and a JSON Web Token: two parts
 *       that each start with {@code eyJ} and a third part, which may be empty, with a {@code .} between them.</li>
 * </ol>
 *
 * <p>Outside a URI and those words, a host name of one label, such as {@code cache} or {@code localhost}, stays,
 * and so does a host name with another ending.
 *
 * <p>After the rules, {@link #hide} replaces each IPv4 and IPv6 address with {@code [address hidden]}. A run of two
 * or more groups of up to four hex digits that each end in a colon reads as an IPv6 address, a time of day such as
 * {@code 12:34:56} included.
 *
 * <p>The rules read text written by a JDBC driver, a connection pool, an HTTP client and the like. They are no proof
 * that nothing personal is left: a name in a sentence of its own, such as a player name, stays.
 *
 * <p>Every rule runs in time linear in the length of the text. Thread-safe: every method may be called from any
 * thread.
 */
public final class Redaction {

    /**
     * One rule: a regular expression of {@link Pattern} and the replacement for each match, in the form
     * {@link java.util.regex.Matcher#replaceAll(String)} reads.
     *
     * @param pattern the regular expression
     * @param replacement the replacement, which may name groups of the pattern as {@code $1}
     */
    public record Rule(String pattern, String replacement) {

        /**
         * Checks both parts.
         *
         * @throws NullPointerException if {@code pattern} or {@code replacement} is {@code null}
         */
        public Rule {
            Objects.requireNonNull(pattern, "pattern");
            Objects.requireNonNull(replacement, "replacement");
        }
    }

    /** What {@link #hide} puts in place of an IPv4 or IPv6 address. */
    public static final String ADDRESS = "[address hidden]";

    private static final String HOST_ENDINGS =
        "com|net|org|io|gg|dev|me|co|xyz|eu|de|uk|us|fr|nl|ca|au|ru|br|pl|internal|local|lan";

    private static final String PACKAGE_ROOTS = "java|javax|jdk|sun|kotlin|com|org|net";

    private static final String SECRET_NAMES =
        "password|passwd|pwd|passphrase|api[_-]?key|access[_-]?key|private[_-]?key|licen[sc]e[_-]?key";

    private static final String SECRET_WORDS = "secret|token";

    private static final String SPACED_SECRET_NAMES = "password|passwd|pwd|passphrase";

    private static final String DETERMINERS = "(?:the|an|this|that|these|those|your|my|our|their)(?=[ \\t]+[a-z])"
        + "(?!(?:(?!\\b(?:select|insert|update|delete|replace|merge|create|alter|drop)\\b)[\\s\\S]){0,200}?"
        + "(?:--|/\\*|\\b(?:where\\b|values\\s*\\(|default\\s+values\\b|returning\\b|(?:order|group)\\s+by\\b"
        + "|having\\b|limit\\s+\\d|set\\s+[\\w.`\"\\[\\]]{1,64}\\s*="
        + "|(?:join|using)\\s+\\S{1,64}(?:\\s+\\S{1,64}){0,2}\\s+on\\b)))";

    private static final String PASS_KEY = "(?:(?<![a-z])pass|(?<=(?-i:[a-z]))(?-i:Pass))";

    private static final String NO_COUNT = "(?!\\s*\\d{1,3}(?![\\w.-]))";

    private static final String FOLDER_END = "to|at|in|is|on|from|after|with|uses|has|was|not";

    private static final String PATH_NAME = "[^\\s\"'\\\\/:]+";

    private static final String PATH_FOLDERS = "(?:(?:(?![^\\s\"'\\\\/:]*\\.[A-Za-z][A-Za-z0-9]{0,4} )" + PATH_NAME
        + " ){0,3}" + PATH_NAME + "[\\\\/]){0,64}";

    private static final String UNIX_FOLDERS = "(?:(?:(?![\\w.-]*\\.[A-Za-z][A-Za-z0-9]{0,4} )[\\w.-]+ "
        + "(?!(?:" + FOLDER_END + ")(?![\\w.-]))){0,3}[\\w.-]+/){0,64}";

    private static final String FILE_ENDINGS =
        "yml|yaml|json|properties|toml|conf|cfg|xml|txt|log|csv|sql|db|sqlite|dat|jar|class|java|lock|tmp|bak";

    private static final String NAME_END = "(?![\\w-]|\\.[\\w-])";

    /**
     * Each rule, applied in this order.
     *
     * <p>The jdbc rule reads to the next whitespace and the placeholders hold a space, so it runs before the rules
     * that match inside a jdbc URL. The named host rule reads the text before a match, which a replacement of the
     * same rule leaves as it was. The Discord webhook rule needs the host name, so it runs before the host name
     * rules. The file URL and Windows path rule runs before the Unix path rule and the URI host rule, which
     * match inside what it hides.
     */
    private static final List<Rule> RULES = List.of(
        new Rule("jdbc:\\w+:(?:(?i:file):)?(?:[A-Za-z]:[\\\\/]|/(?!/))" + PATH_FOLDERS + "[^\\s\"']*|jdbc:[^\\s\"']+",
            "jdbc:[redacted]"),
        new Rule("(?i)([a-z][a-z0-9+.-]{0,31}://)[^/\\s:@]*:(?!\\d{1,5}(?![^\\s/?#\\[\\]{}\"]))"
            + "(?:(?:(?!://)\\S){0,128}|[^\\s/]*)@", "$1[credentials hidden]@"),
        new Rule("(?<![\\w.+-])[\\w.+-]+@[\\w-][\\w.-]*\\.[\\w-]{2,}(?![\\w-])", "[email hidden]"),
        new Rule("(?:(Bearer) |((?i:Authorization[\"']?\\s{0,4}[:=,]\\s{0,4}[\\[({\"']{0,2}Basic))[ \\t]{1,4}"
            + "(?![A-Za-z][a-z]*+(?![A-Za-z0-9._~+/=-])))[A-Za-z0-9._~+/=-]+", "$1$2 [token hidden]"),
        new Rule("AKIA[0-9A-Z]{16}", "[aws-key hidden]"),
        new Rule("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
            + "|(?<![0-9a-f])[0-9a-f]{32}(?![0-9a-f])", "[uuid hidden]"),
        new Rule("'[^'\\s]*'@'[^'\\s]*'", "[user hidden]@[host hidden]"),
        new Rule("(?i)(\\b(?:user|role)\\s+)\"[^\"\\s]{1,64}\"", "$1[user hidden]"),
        new Rule("([?&](?:api_?key|token|secret)=)[^&\\s]+", "$1[redacted]"),
        new Rule("(?im)(^[ \\t]*(?:-[ \\t]+)?[\"']?[\\w.-]{0,64}?(?:" + SPACED_SECRET_NAMES + ")[\"']?[ \\t]*(?::|=>?+)"
            + "[ \\t]*+)(?!(?:yes|no)\\b)(?![\"'])[^\\n]+"
            + "|((?:(?:" + SECRET_NAMES + "|" + SECRET_WORDS + ")[\\w-]{0,20}[\"']?\\s*=>?+"
            + "|" + PASS_KEY + "[\"']?\\s*=>?+" + NO_COUNT
            + "|[\"'][\\w.-]{0,20}?" + PASS_KEY + "[\"']\\s*:" + NO_COUNT
            + "|(?:" + SECRET_NAMES + ")[\\w-]{0,20}[\"']?\\s*:"
            + "|(?<=[\\w.-])(?:" + SECRET_WORDS + ")[\\w-]{0,20}[\"']?\\s*:"
            + "|(?:" + SECRET_WORDS + ")(?!s?(?![\\w-]))[\\w-]{1,20}[\"']?\\s*:"
            + "|[\"'](?:" + SECRET_WORDS + ")s?[\"']\\s*:"
            + "|^[ \\t]*(?:-[ \\t]+)?(?:" + SECRET_WORDS + ")s?\\s*:"
            + "|(?<=[^\\w\\s][ \\t]{0,8})(?:" + SECRET_WORDS + ")s?\\s*:"
            + "|(?:" + SECRET_WORDS + ")s?:(?=[ \\t]{0,8}[\"']?(?=[^\\s\"'&,;)]{6})[^\\s\"'&,;)\\d]{0,64}\\d))\\s*)"
            + "(?!(?:yes|no)\\b)(?:-[ \\t]+[^\\n]*+"
            + "(?:\\n(?:[ \\t]*+(?:#[^\\n]*+|\\r)?\\n)*+[ \\t]*+-[ \\t]++[^\\n]*+)*+"
            + "|\"[^\"\\n]*\"|'[^'\\n]*'|[^\\s\"'&,;)]+)", "$1$2[redacted]"),
        new Rule("(?i)\\b(?:SELECT\\s(?!(?:[a-z]+\\s){1,8}FROM\\s" + DETERMINERS + ")[^\\n]{0,200}?\\sFROM\\s"
            + "|(?:INSERT\\s+(?:OR\\s+\\w+\\s+|IGNORE\\s+)?INTO|DELETE\\s+FROM|REPLACE\\s+INTO|MERGE\\s+INTO)\\s"
            + "(?!" + DETERMINERS + ")"
            + "|UPDATE\\s+\\S+\\s+SET\\s|(?:CREATE|ALTER|DROP)\\s+TABLE\\s)[^\\n]{0,200}", "[sql hidden]"),
        new Rule("(?<![A-Za-z0-9])(?i:file):/+[^\\s\"']+|(?:[A-Za-z]:\\\\|(?<![A-Za-z0-9])[A-Za-z]:/(?!/)"
            + "|(?<!\\\\)\\\\\\\\[\\w.$-]+\\\\|(?<![A-Za-z0-9])(?i:Users)\\\\)"
            + PATH_FOLDERS + "[^\\s\"']*", "[path hidden]"),
        new Rule("(?<![\\w./:-])/[\\w.-]+/" + UNIX_FOLDERS + "[\\w./-]*", "[path hidden]"),
        new Rule("(?i)(https?://(?:(?:canary|ptb)\\.)?discord(?:app)?\\.com/api/(?:v\\d+/)?webhooks/)"
            + "\\d+/[\\w-]+(?:\\?\\S*)?",
            "$1[id hidden]/[token hidden]"),
        new Rule("(?i)(?<![\\w.-])(?!(?:" + PACKAGE_ROOTS + ")\\.)(?![a-z]{2}\\.[a-z]{2}(?![\\w-]|\\.[\\w-]))"
            + "[\\w.-]+\\.(?:" + HOST_ENDINGS + ")" + NAME_END, "[host hidden]"),
        new Rule("(?i)(\\bdatabase\\s+)(?:\"[^\"\\s]{1,64}\"|'[^'\\s]{1,64}')", "$1[database hidden]"),
        new Rule("(?i)(://(?:\\[credentials hidden\\]@)?)(?:[\\w.~%+-]{0,64}@)?(?!localhost(?![\\w.-]))[\\w.-]+",
            "$1[host hidden]"),
        new Rule("(?i)((?:UnknownHostException:|\\b(?:connect|connecting|connection|connected|route) to"
            + "|\\bhost(?: is known)?) [\"'(]?"
            + "|(?:host(?:name)?|address)[\"']?\\s?[=:]\\s?[\"'(\\[{]{0,2}"
            + "|\\b(?:refused|timed out(?: after \\d{1,9} ?ms)?): "
            + "|\\. (?<=address=\\(host=[^()\\s]{1,255}\\)" + "(?:\\([^()\\s]{0,64}\\))?".repeat(4) + "\\. ))"
            + "(?!\\d+\\.\\d)(?![\\w.-]*\\.(?-i:[A-Z])[\\w-]*" + NAME_END + ")"
            + "(?![\\w.-]*\\.(?:" + FILE_ENDINGS + ")" + NAME_END + ")(?![\\w.-]*\\()"
            + "[\\w-]+\\.[\\w.-]*[\\w-]", "$1[host hidden]"),
        new Rule("(?<![\\w-])(?:gh[pousr]_[A-Za-z0-9]{8,}|github_pat_\\w{8,}|xox[abeoprs]-[A-Za-z0-9-]{8,}"
            + "|[sr]k_live_[A-Za-z0-9]{16,}|eyJ[\\w-]{5,}\\.eyJ[\\w-]{5,}\\.[\\w-]*)", "[token hidden]")
    );

    private static final Pattern[] PATTERNS = RULES.stream().map(rule -> Pattern.compile(rule.pattern()))
        .toArray(Pattern[]::new);

    private static final Pattern IPV4 = Pattern.compile("(?<![\\w.])\\d{1,3}(?:\\.\\d{1,3}){3}(?![\\w.])");

    private static final Pattern IPV6 =
        Pattern.compile("(?i)(?<![\\w:.])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![\\w:])");

    private Redaction() {
    }

    /**
     * The rules of the class description, in the order they apply, without the address rule of {@link #hide}. For
     * code that hands them to something that applies regular expressions itself.
     *
     * @return the rules, unmodifiable
     */
    public static List<Rule> rules() {
        return RULES;
    }

    /**
     * {@code text} with every rule applied and each IPv4 and IPv6 address replaced by {@value #ADDRESS}.
     *
     * @param text the text to hide in
     * @return the hidden form of {@code text}
     * @throws NullPointerException if {@code text} is {@code null}
     */
    public static String hide(String text) {
        Objects.requireNonNull(text, "text");
        String result = text;
        for (int i = 0; i < PATTERNS.length; i++) {
            result = PATTERNS[i].matcher(result).replaceAll(RULES.get(i).replacement());
        }
        result = IPV4.matcher(result).replaceAll(ADDRESS);
        return IPV6.matcher(result).replaceAll(ADDRESS);
    }
}
