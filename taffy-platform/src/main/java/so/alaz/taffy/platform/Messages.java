package so.alaz.taffy.platform;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.util.Ticks;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Player messages read from {@code lang/<locale>.yml} files in a plugin's data folder.
 *
 * <p>Each file holds MiniMessage templates under dotted keys ({@code command.give.success}). A YAML list is kept as
 * its lines, one per entry, with an empty entry for a {@code null} one, and an empty list is a message of no lines;
 * every other value that is not a section is kept as text, as one line even when the text holds newlines, as a block
 * scalar does. A message of several lines renders with the lines joined by a newline, or as one component
 * per line through {@link #lines(Locale, String, TagResolver...)}. The plugin supplies its bundled English file: it
 * is written to {@code lang/en.yml} when that file is absent, and it is the last place a key is looked up, so a
 * translation may leave keys out.
 *
 * <p>The folder is {@code lang} unless the load names another one, as
 * {@link #load(Path, String, Supplier, Logger, String, boolean)} does. A plugin that bundles more languages than
 * English names them through {@link #load(Path, String, Collection, Function, Logger, String, boolean)}.
 *
 * <p>A key resolves through, in order: the file of the exact locale ({@code pt_br}), the file of its language
 * ({@code pt}), the file of the default locale, then the bundled English text. The first two are used only when
 * per-player languages are on and a locale is given. A file name, the default locale and a viewer's locale are all
 * matched in lower case with {@code -} read as {@code _}, so {@code pt-BR} finds {@code pt_br.yml}.
 *
 * <p>A template writes a value as {@code <name>} or as {@code {name}}, which reads as {@code <name>} for every name
 * a call supplies. The prefix is the template under the key {@code prefix}, its lines joined by a newline when it
 * is a list. It is never put in front of a message: it appears where a template writes {@code <prefix>} or
 * {@code {prefix}}, and is empty when no file has the key. {@link #withChatPrefix} gives a {@code Messages} that
 * puts a prefix from the plugin in front of what {@link #send} sends.
 *
 * <p>A key that no file has renders as the visible marker {@code <red>missing:key}. A section-sign code in a
 * template, in the prefix or in a value supplied as {@code Placeholder.parsed} is rendered as
 * {@link LegacyText#sectionToMiniMessage} converts it.
 *
 * <p>Every method that takes no viewer may be called from any thread. The methods that take a viewer or a player
 * follow the thread rule of {@link Text}. {@link #reload()} replaces every loaded file at once.
 */
public final class Messages {

    /** The folder inside the data folder that holds the language files, unless the load names another one. */
    public static final String FOLDER = "lang";

    /** The locale of the bundled file, and the default locale when none is configured. */
    public static final String FALLBACK = "en";

    private static final String PREFIX = "prefix";
    private static final String NEWLINE = "\n";
    private static final String EXTENSION = ".yml";
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");
    private static final Set<String> YAML_WORDS = Set.of("true", "false", "yes", "no", "on", "off", "null", "y", "n");

    /** The file and the key list of each warning about keys shown in another language that was logged. */
    private static final Set<String> FALL_THROUGH_WARNED = ConcurrentHashMap.newKeySet();

    private final Source source;
    private final @Nullable Supplier<@Nullable String> chatPrefix;
    private final boolean namesIgnoringCase;

    /** What every {@code Messages} derived from one load shares. Its monitor guards a reload and a file write. */
    private static final class Source {
        private final Path folder;
        private final String folderName;
        /** The bundled language codes as the plugin wrote them, English first. */
        private final List<String> codes;
        private final Function<String, @Nullable InputStream> opener;
        private final Logger logger;
        private final String defaultLocale;
        private final boolean perPlayer;
        private final boolean lenient;
        /** Whether the load named its bundled languages, so that each one backs the owner's file of that code. */
        private final boolean perLocale;
        /** Locale code in lower case to the bundled templates of that language, in the order of the file. */
        private final Map<String, Map<String, List<String>>> bundled;
        private final Map<String, List<String>> fallback;
        private volatile Loaded loaded = new Loaded(Map.of(), Map.of());

        private Source(
            Path folder,
            String folderName,
            List<String> codes,
            Function<String, @Nullable InputStream> opener,
            Logger logger,
            String defaultLocale,
            boolean perPlayer,
            boolean lenient,
            boolean perLocale,
            Map<String, Map<String, List<String>>> bundled
        ) {
            this.folder = folder;
            this.folderName = folderName;
            this.codes = codes;
            this.opener = opener;
            this.logger = logger;
            this.defaultLocale = defaultLocale;
            this.perPlayer = perPlayer;
            this.lenient = lenient;
            this.perLocale = perLocale;
            this.bundled = bundled;
            this.fallback = bundled.get(FALLBACK);
        }
    }

    /**
     * One read of the folder. Replaced as a whole and never changed afterwards.
     *
     * @param locales locale code in lower case to the templates served for that code
     * @param files   locale code in lower case to the name of the file of that code
     */
    private record Loaded(Map<String, Map<String, List<String>>> locales, Map<String, String> files) {
    }

    private Messages(Source source, @Nullable Supplier<@Nullable String> chatPrefix, boolean namesIgnoringCase) {
        this.source = source;
        this.chatPrefix = chatPrefix;
        this.namesIgnoringCase = namesIgnoringCase;
    }

    /**
     * Loads the messages with English as the default locale and per-player languages on.
     *
     * @throws IllegalStateException as {@link #load(Path, Supplier, Logger, String, boolean)} does
     * @see #load(Path, Supplier, Logger, String, boolean)
     */
    public static Messages load(Path dataFolder, Supplier<@Nullable InputStream> bundled, Logger logger) {
        return load(dataFolder, bundled, logger, FALLBACK, true);
    }

    /**
     * Writes the bundled English file into {@code dataFolder/lang/en.yml} when that file is absent, then reads every
     * {@code .yml} file in that folder. A file that cannot be read is skipped with a warning, and the keys each file
     * lacks are listed in one warning per file. A file that holds a byte sequence that is not UTF-8, as one saved in
     * a legacy code page such as Windows-1252 does, is skipped as a whole with the warning
     * {@code lang/<name> is not saved as UTF-8 (the first byte that is not UTF-8 is on line <n>), so its messages
     * are not used.}; {@link #loadLenient} reads such a file. Two files whose names read as one locale, such as
     * {@code pt-BR.yml} and {@code pt_br.yml}, are reported with a warning that names the one used: the one the
     * folder listing gives last. A folder that cannot be written or listed is reported with a warning and the
     * bundled English text is used. A default locale other than en that has no file is reported with a warning as
     * well.
     *
     * @param bundled       opens the bundled English file, a new stream on each call
     * @param defaultLocale the language used when the viewer's own does not apply, read as
     *                      {@link #normalizeLocale} describes: {@code null} or blank means en
     * @param perPlayer     whether a message is served in the language of the locale it is asked for
     * @throws IllegalStateException when the bundled file is missing, is not valid UTF-8 or is not valid YAML, or
     *     when {@link Relocation#check()} fails, because the plugin's jar holds Taffy in its original package
     */
    public static Messages load(
        Path dataFolder,
        Supplier<@Nullable InputStream> bundled,
        Logger logger,
        @Nullable String defaultLocale,
        boolean perPlayer
    ) {
        return load(dataFolder, FOLDER, List.of(FALLBACK), code -> bundled.get(), logger, defaultLocale, perPlayer,
            false, false);
    }

    /**
     * Loads the messages as {@link #load(Path, Supplier, Logger, String, boolean)} does, from
     * {@code dataFolder/<folder>} in place of {@code dataFolder/lang}: the bundled English file is written to
     * {@code <folder>/en.yml} when that file is absent, every {@code .yml} file of that folder is read, and each
     * warning names {@code <folder>}.
     *
     * @param folder the name of the folder inside {@code dataFolder}, one path segment such as {@code locales}
     * @throws IllegalArgumentException when {@code folder} is blank, is {@code .}, or holds {@code /}, {@code \},
     *     {@code :} or {@code ..}
     * @throws IllegalStateException as {@link #load(Path, Supplier, Logger, String, boolean)} does
     */
    public static Messages load(
        Path dataFolder,
        String folder,
        Supplier<@Nullable InputStream> bundled,
        Logger logger,
        @Nullable String defaultLocale,
        boolean perPlayer
    ) {
        return load(dataFolder, segment(folder, "folder"), List.of(FALLBACK), code -> bundled.get(), logger,
            defaultLocale, perPlayer, false, false);
    }

    /**
     * Loads the messages of a plugin that bundles a file for each language in {@code bundledLocales} next to its
     * English one, from {@code dataFolder/<folder>}. It differs from
     * {@link #load(Path, String, Supplier, Logger, String, boolean)} in these ways:
     *
     * <ul>
     * <li>The load and each {@link #reload()} write every bundled file whose language has no file in the folder,
     *     as {@code <folder>/<code>.yml} with the code as {@code bundledLocales} writes it. A file whose name
     *     reads as the same locale counts as the file of that language, so an owner's {@code pt-BR.yml} or
     *     {@code PT_br.yml} keeps the bundled {@code pt_BR} from being written beside it.</li>
     * <li>At each step of the lookup, a key the owner's file of that locale lacks is taken from the bundled file of
     *     the same code, before the next step: for {@code pt_br} the order is the owner's {@code pt_br.yml}, the
     *     bundled {@code pt_br}, the owner's {@code pt.yml}, the bundled {@code pt}, the default locale's file,
     *     its bundled file, then the bundled English text. A bundled language is served when its file is gone as
     *     well, and {@link #loadedLanguages()} lists it.</li>
     * <li>The warning about the keys a file lacks lists only the keys that are then shown in another language, as
     *     {@code <folder>/<name> lacks <keys>; those messages are shown in another language.}, and is logged once
     *     for each file and list of keys while the server runs, not on each reload.</li>
     * </ul>
     *
     * @param folder         the name of the folder inside {@code dataFolder}, one path segment such as {@code lang}
     * @param bundledLocales the codes of the bundled language files other than English, such as {@code de} and
     *                       {@code pt_BR}; a code that reads as en is ignored
     * @param bundled        opens the bundled file of a code, a new stream on each call; it is asked for {@code en}
     *                       and for each code of {@code bundledLocales} as written there
     * @throws IllegalArgumentException when {@code folder} or a code is blank, is {@code .}, or holds {@code /},
     *     {@code \}, {@code :} or {@code ..}, or when two codes read as one locale
     * @throws IllegalStateException when a bundled file is missing, is not valid UTF-8 or is not valid YAML, or
     *     when {@link Relocation#check()} fails, because the plugin's jar holds Taffy in its original package
     */
    public static Messages load(
        Path dataFolder,
        String folder,
        Collection<String> bundledLocales,
        Function<String, @Nullable InputStream> bundled,
        Logger logger,
        @Nullable String defaultLocale,
        boolean perPlayer
    ) {
        List<String> codes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        codes.add(FALLBACK);
        seen.add(FALLBACK);
        for (String locale : bundledLocales) {
            String code = code(segment(locale, "bundled locale"));
            if (code.equals(FALLBACK)) {
                continue;
            }
            if (!seen.add(code)) {
                throw new IllegalArgumentException("two bundled locales read as " + code);
            }
            codes.add(locale);
        }
        return load(dataFolder, segment(folder, "folder"), List.copyOf(codes), bundled, logger, defaultLocale,
            perPlayer, false, true);
    }

    /**
     * Loads the messages as {@link #load(Path, Supplier, Logger, String, boolean)} does, except that a language file
     * with a byte sequence that is not UTF-8 is not skipped: each such sequence is read as the replacement character
     * U+FFFD, so the keys of the file stay in use and only the characters saved in another encoding are lost. One
     * warning per file and per load or reload names the file and the first line with a replaced character:
     * {@code lang/<name> is not saved as UTF-8, so each byte that is not UTF-8 is read as the replacement character
     * U+FFFD; the first is on line <n>. Save the file as UTF-8.} A file that is not valid YAML once it is read that
     * way is still skipped, and the bundled file is still read strictly.
     *
     * @throws IllegalStateException when the bundled file is missing, is not valid UTF-8 or is not valid YAML, or
     *     when {@link Relocation#check()} fails, because the plugin's jar holds Taffy in its original package
     */
    public static Messages loadLenient(
        Path dataFolder,
        Supplier<@Nullable InputStream> bundled,
        Logger logger,
        @Nullable String defaultLocale,
        boolean perPlayer
    ) {
        return load(dataFolder, FOLDER, List.of(FALLBACK), code -> bundled.get(), logger, defaultLocale, perPlayer,
            true, false);
    }

    private static Messages load(
        Path dataFolder,
        String folderName,
        List<String> codes,
        Function<String, @Nullable InputStream> opener,
        Logger logger,
        @Nullable String defaultLocale,
        boolean perPlayer,
        boolean lenient,
        boolean perLocale
    ) {
        Map<String, Map<String, List<String>>> bundled = new HashMap<>();
        for (String code : codes) {
            byte[] content = bytes(() -> opener.apply(code));
            Map<String, List<String>> values = content == null || malformedLine(content) > 0 ? null : values(content);
            if (values == null) {
                throw new IllegalStateException("the bundled " + code + EXTENSION + " is missing or unreadable");
            }
            bundled.put(code(code), Collections.unmodifiableMap(values));
        }
        Relocation.check();
        Messages messages = new Messages(
            new Source(dataFolder.resolve(folderName), folderName, codes, opener, logger,
                normalizeLocale(defaultLocale), perPlayer, lenient, perLocale, bundled),
            null, false);
        messages.reload();
        return messages;
    }

    /** {@code value} when it is one path segment that names a file or folder, as a load requires. */
    private static String segment(String value, String what) {
        if (value.isBlank() || value.equals(".") || value.contains("..") || value.indexOf('/') >= 0
            || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0) {
            throw new IllegalArgumentException("the " + what + " '" + value + "' is not one path segment");
        }
        return value;
    }

    /**
     * A configured language code as it is matched against the file names: lower case, with {@code -} read as
     * {@code _} and blank read as en.
     */
    public static String normalizeLocale(@Nullable String value) {
        return value == null || value.isBlank() ? FALLBACK : code(value);
    }

    private static String code(String locale) {
        return locale.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    /**
     * Returns a {@code Messages} over the same files that puts {@code prefix} in front of each message
     * {@link #send} sends. The prefix is a MiniMessage template the plugin supplies, such as a setting of its
     * {@code config.yml}, read on each send: it is written in front of the message's template and rendered with
     * it, so its PlaceholderAPI placeholders are filled in, a tag it leaves open applies to the message, and a
     * supplied value is inserted as it is in any render. {@code null} or an empty text means no prefix. Only
     * {@link #send} adds it: a render, an action bar and a title do not, and the prefix of the lang files still
     * appears only where a template writes {@code <prefix>}. A message of several lines gets it once, in front of
     * its first line, and an empty message, which is not sent, gets none.
     *
     * <p>This instance is left as it is. The returned one shares its files: a {@link #reload()} or an
     * {@link #addMissingKeys()} through either applies to both.
     */
    public Messages withChatPrefix(Supplier<@Nullable String> prefix) {
        return new Messages(source, prefix, namesIgnoringCase);
    }

    /**
     * Returns a {@code Messages} over the same files that also reads a {@code {name}} written with upper-case
     * letters, in a template, in the prefix and in the prefix of {@link #withChatPrefix}: the name is matched
     * against the supplied values in lower case, so {@code {crateName}} reads as {@code <cratename>} when a value
     * named {@code cratename} is supplied. A {@code {name}} that no value supplies stays as written, and a
     * supplied value is inserted as it is without this setting: a {@code {name}} inside it is not rewritten.
     *
     * <p>This instance is left as it is. The returned one shares its files: a {@link #reload()} or an
     * {@link #addMissingKeys()} through either applies to both.
     *
     * @see Text#withBracedNamesIgnoringCase()
     */
    public Messages withBracedNamesIgnoringCase() {
        return namesIgnoringCase ? this : new Messages(source, chatPrefix, true);
    }

    /** The default locale given at load, as {@link #normalizeLocale} reads it. */
    public String defaultLocale() {
        return source.defaultLocale;
    }

    /** Whether a message is served in the language of the locale it is asked for, as it was given at load. */
    public boolean perPlayer() {
        return source.perPlayer;
    }

    /**
     * Reads the language folder again, with the same warnings as the load, and writes {@code lang/en.yml} again when
     * it is absent, as it writes each other bundled file the load named. The one warning that is not repeated is
     * the one about keys shown in another language, which a load that names its bundled languages logs once. The
     * bundled text is not read again. Calls do not overlap, and a render that runs during a call sees either all
     * the old files or all the new ones.
     */
    public void reload() {
        synchronized (source) {
            String shown = source.folderName;
            writeAbsent();
            Map<String, Map<String, List<String>>> owned = new HashMap<>();
            Map<String, String> names = new HashMap<>();
            Map<String, String> skipped = new HashMap<>();
            if (Files.isDirectory(source.folder)) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(source.folder, "*" + EXTENSION)) {
                    for (Path file : files) {
                        String name = file.getFileName().toString();
                        String locale = code(name.substring(0, name.length() - EXTENSION.length()));
                        byte[] content = bytes(() -> open(file));
                        int malformedLine = content == null ? 0 : malformedLine(content);
                        if (malformedLine > 0 && !source.lenient) {
                            source.logger.warning(shown + "/" + name + " is not saved as UTF-8 (the first byte that"
                                + " is not UTF-8 is on line " + malformedLine + "), so its messages are not used.");
                            skipped.put(locale, name);
                            continue;
                        }
                        if (malformedLine > 0) {
                            source.logger.warning(shown + "/" + name + " is not saved as UTF-8, so each byte that is"
                                + " not UTF-8 is read as the replacement character U+FFFD; the first is on line "
                                + malformedLine + ". Save the file as UTF-8.");
                        }
                        Map<String, List<String>> values = content == null ? null : values(content);
                        if (values == null) {
                            source.logger.warning(shown + "/" + name + " is not valid YAML saved as UTF-8, so its"
                                + " messages are not used.");
                            skipped.put(locale, name);
                            continue;
                        }
                        Set<String> missing = new TreeSet<>(source.fallback.keySet());
                        missing.removeAll(values.keySet());
                        if (!missing.isEmpty() && !source.perLocale) {
                            source.logger.warning(shown + "/" + name + " lacks " + String.join(", ", missing)
                                + "; those messages" + fallback(locale));
                        }
                        String replaced = names.put(locale, name);
                        if (replaced != null) {
                            source.logger.warning(shown + "/" + replaced + " and " + shown + "/" + name + " are both"
                                + " read as the language " + locale + ", so only " + shown + "/" + name + " is used."
                                + " Remove or rename one of them.");
                        }
                        owned.put(locale, values);
                    }
                } catch (IOException | DirectoryIteratorException failure) {
                    // A failure while iterating arrives unchecked, with the I/O error as its cause.
                    Throwable reason = failure instanceof DirectoryIteratorException ? failure.getCause() : failure;
                    owned.clear();
                    names.clear();
                    skipped.clear();
                    source.logger.warning("Could not read the " + shown + " folder: " + reason.getMessage()
                        + ". Messages use the bundled English text.");
                }
            }
            Map<String, Map<String, List<String>>> locales = source.perLocale ? backed(owned) : owned;
            if (!source.defaultLocale.equals(FALLBACK) && !locales.containsKey(source.defaultLocale)) {
                source.logger.warning("The default language " + source.defaultLocale + " has no " + shown + "/"
                    + source.defaultLocale + ".yml, so the bundled English text is used in its place.");
            }
            if (source.perLocale) {
                warnShownInAnotherLanguage(names, locales);
            }
            skipped.putAll(names);
            source.loaded = new Loaded(locales, skipped);
        }
    }

    /**
     * Writes each bundled file that the folder lacks, and stops at the first one that cannot be written. After a
     * load that named its bundled languages, a file whose name reads as the locale of a code counts as its file.
     */
    private void writeAbsent() {
        String writing = FALLBACK;
        try {
            Files.createDirectories(source.folder);
            Set<String> present = new HashSet<>();
            if (source.perLocale) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(source.folder, "*" + EXTENSION)) {
                    for (Path file : files) {
                        String name = file.getFileName().toString();
                        present.add(code(name.substring(0, name.length() - EXTENSION.length())));
                    }
                }
            }
            for (String code : source.codes) {
                writing = code;
                Path file = source.folder.resolve(code + EXTENSION);
                if (present.contains(code(code)) || !Files.notExists(file)) {
                    continue;
                }
                try (InputStream in = source.opener.apply(code)) {
                    if (in != null) {
                        Files.copy(in, file);
                    }
                }
            }
        } catch (IOException | DirectoryIteratorException failure) {
            // A failure while iterating arrives unchecked, with the I/O error as its cause.
            Throwable reason = failure instanceof DirectoryIteratorException ? failure.getCause() : failure;
            source.logger.warning("Could not write " + source.folderName + "/" + writing + EXTENSION + ": "
                + reason.getMessage() + ". Messages use the bundled "
                + (writing.equals(FALLBACK) ? "English text." : "text."));
        }
    }

    /** Each owner's file laid over the bundled file of its code, and each bundled language without a file. */
    private Map<String, Map<String, List<String>>> backed(Map<String, Map<String, List<String>>> owned) {
        Map<String, Map<String, List<String>>> locales = new HashMap<>(owned);
        source.bundled.forEach((code, shipped) -> locales.put(code, over(shipped, owned.get(code))));
        return locales;
    }

    private static Map<String, List<String>> over(
        Map<String, List<String>> shipped,
        @Nullable Map<String, List<String>> owner
    ) {
        if (owner == null) {
            return shipped;
        }
        Map<String, List<String>> both = new HashMap<>(shipped);
        both.putAll(owner);
        return both;
    }

    /**
     * Warns, once for each file and list of keys, about the keys of the bundled English file that a render for
     * the locale of a file in {@code names} takes from a file of another language.
     */
    private void warnShownInAnotherLanguage(
        Map<String, String> names,
        Map<String, Map<String, List<String>>> locales
    ) {
        Map<String, List<String>> byDefault = locales.get(source.defaultLocale);
        names.forEach((locale, name) -> {
            String language = language(locale);
            Map<String, List<String>> own = locales.get(locale);
            Map<String, List<String>> ofLanguage = locales.get(language);
            Set<String> elsewhere = new TreeSet<>();
            for (String key : source.fallback.keySet()) {
                if (own.containsKey(key) || ofLanguage != null && ofLanguage.containsKey(key)) {
                    continue;
                }
                String served = byDefault != null && byDefault.containsKey(key)
                    ? language(source.defaultLocale)
                    : FALLBACK;
                if (!served.equals(language)) {
                    elsewhere.add(key);
                }
            }
            String keys = String.join(", ", elsewhere);
            String logged = source.folder.resolve(name).toAbsolutePath() + NEWLINE + keys;
            if (!elsewhere.isEmpty() && FALL_THROUGH_WARNED.add(logged)) {
                source.logger.warning(source.folderName + "/" + name + " lacks " + keys
                    + "; those messages are shown in another language.");
            }
        });
    }

    private static String language(String locale) {
        int region = locale.indexOf('_');
        return region < 0 ? locale : locale.substring(0, region);
    }

    /**
     * Appends each key of the bundled English file that the English language file lacks, as
     * {@link #addMissingKeys(String)} does for {@code en}.
     *
     * @return the keys that were added, in the order of the bundled file; unmodifiable
     */
    public List<String> addMissingKeys() {
        return addMissingKeys(FALLBACK);
    }

    /**
     * Appends each key that the bundled file of {@code locale} has and the file of that locale in the folder
     * lacks, with its bundled text, at the end of that file. The file's own bytes stay as they are: its lines,
     * comments, order, encoding and byte order mark are kept, and the added lines end as the file's lines do.
     * Each key is added in its dotted form ({@code shop.sold: "..."}), a list as a YAML list. The new content is
     * written to {@code <name>.tmp} beside the file and moved over it in one step. The file is then read again,
     * with no other file read and no load warning repeated.
     *
     * <p>Nothing is written, and the list is empty, when the file lacks no key, when the file is absent or was
     * removed since it was read, or when the plugin bundles no file of that locale, as with every locale but en
     * after a load that names no bundled languages: a file is never given the text of another language. A key the
     * file writes with no value, as {@code key:} or {@code key: ~}, counts as one the file has and is not added. A
     * file that is not valid YAML or is not saved as UTF-8 is left as it is and reported with the warning
     * {@code <folder>/<name> is not valid YAML saved as UTF-8, so no key was added to it.} A file whose end does
     * not take the keys is left as it is with the warning
     * {@code <folder>/<name> was not changed, because <keys> cannot be added at its end.}, which lists every key
     * the file lacks. That is a file that holds text under a key the bundled file uses as a section, one that
     * ends inside a block scalar with no line break after its last line, one that ends with the document end
     * marker {@code ...}, and one that writes its keys as a flow mapping in braces. A file that cannot be written
     * is reported as {@code Could not add keys to <folder>/<name>: <reason>.} Calls and reloads do not overlap.
     *
     * @param locale the language of the file, read as {@link #normalizeLocale} describes
     * @return the keys that were added, in the order of the bundled file; unmodifiable
     */
    public List<String> addMissingKeys(String locale) {
        synchronized (source) {
            String code = normalizeLocale(locale);
            Map<String, List<String>> shipped = source.bundled.get(code);
            Loaded loaded = source.loaded;
            String name = loaded.files().get(code);
            if (shipped == null || name == null) {
                return List.of();
            }
            String shown = source.folderName + "/" + name;
            Path file = source.folder.resolve(name);
            if (Files.notExists(file)) {
                return List.of();
            }
            byte[] content = bytes(() -> open(file));
            Map<String, List<String>> values = content == null || malformedLine(content) > 0 ? null : values(content);
            if (content == null || values == null) {
                source.logger.warning(shown + " is not valid YAML saved as UTF-8, so no key was added to it.");
                return List.of();
            }
            Set<String> valueless = valueless(content);
            List<String> added = shipped.keySet().stream()
                .filter(key -> !values.containsKey(key) && !valueless.contains(key))
                .toList();
            if (added.isEmpty()) {
                return added;
            }
            byte[] extended = extended(content, added, shipped);
            Map<String, List<String>> result = values(extended);
            if (result == null || !result.entrySet().containsAll(values.entrySet())
                || !added.stream().allMatch(key -> shipped.get(key).equals(result.get(key)))) {
                source.logger.warning(shown + " was not changed, because " + String.join(", ", added)
                    + " cannot be added at its end.");
                return List.of();
            }
            try {
                replace(Files.isSymbolicLink(file) ? file.toRealPath() : file, extended);
            } catch (IOException failure) {
                source.logger.warning("Could not add keys to " + shown + ": " + failure.getMessage() + ".");
                return List.of();
            }
            Map<String, Map<String, List<String>>> locales = new HashMap<>(loaded.locales());
            locales.put(code, source.perLocale ? over(shipped, result) : result);
            source.loaded = new Loaded(locales, loaded.files());
            return added;
        }
    }

    /** The dotted keys that {@code content} writes with no value, which {@link #values} leaves out. */
    private static Set<String> valueless(byte[] content) {
        Set<String> keys = new HashSet<>();
        try {
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(Integer.MAX_VALUE);
            valueless("", new Yaml(options).compose(new StringReader(new String(content, StandardCharsets.UTF_8))),
                keys);
        } catch (RuntimeException unreadable) {
            keys.clear();
        }
        return keys;
    }

    private static void valueless(String parent, @Nullable Node node, Set<String> keys) {
        if (!(node instanceof MappingNode mapping)) {
            return;
        }
        for (NodeTuple entry : mapping.getValue()) {
            if (!(entry.getKeyNode() instanceof ScalarNode name)) {
                continue;
            }
            String key = parent + name.getValue();
            if (Tag.NULL.equals(entry.getValueNode().getTag())) {
                keys.add(key);
            } else {
                valueless(key + ".", entry.getValueNode(), keys);
            }
        }
    }

    /** {@code content} with an entry for each key of {@code added} after it, in the line ending of the content. */
    private static byte[] extended(byte[] content, List<String> added, Map<String, List<String>> shipped) {
        String text = new String(content, StandardCharsets.UTF_8);
        String end = text.contains("\r\n") ? "\r\n" : NEWLINE;
        StringBuilder entries = new StringBuilder();
        if (content.length > 0 && content[content.length - 1] != '\n') {
            entries.append(end);
        }
        for (String key : added) {
            List<String> lines = shipped.get(key);
            boolean plain = PLAIN_KEY.matcher(key).matches() && !YAML_WORDS.contains(key.toLowerCase(Locale.ROOT));
            entries.append(plain ? key : quoted(key)).append(':');
            if (lines.size() == 1) {
                entries.append(' ').append(quoted(lines.getFirst()));
            } else if (lines.isEmpty()) {
                entries.append(" []");
            } else {
                lines.forEach(line -> entries.append(end).append("  - ").append(quoted(line)));
            }
            entries.append(end);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + entries.length());
        out.writeBytes(content);
        out.writeBytes(entries.toString().getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /** {@code value} as a double-quoted YAML scalar on one line. */
    private static String quoted(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    boolean printable = character >= 0x20 && (character < 0x7F || character > 0x9F)
                        && character != 0x2028 && character != 0x2029 && character != 0xFEFF;
                    if (printable) {
                        out.append(character);
                    } else {
                        out.append("\\u").append(HexFormat.of().withUpperCase().toHexDigits(character));
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /**
     * Writes {@code content} to a temporary file beside {@code target}, flushes it to the device and moves it
     * over {@code target}, with the POSIX permissions of {@code target}.
     */
    private static void replace(Path target, byte[] content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            try (channel) {
                ByteBuffer bytes = ByteBuffer.wrap(content);
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                // The bytes reach the device before the rename, so a power loss after it cannot leave an empty
                // file.
                channel.force(true);
            }
            if (Files.getFileStore(target).supportsFileAttributeView(PosixFileAttributeView.class)) {
                Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(target));
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * The locale codes of the language files that are loaded, as {@link #normalizeLocale} reads them. After a load
     * that names its bundled languages, each of those is among them, with or without a file.
     */
    public Set<String> loadedLanguages() {
        return Set.copyOf(source.loaded.locales().keySet());
    }

    /**
     * The template for {@code key} as it is written in the file, with nothing filled in and the lines of a list
     * joined by a newline.
     *
     * @param locale the viewer's locale, or {@code null} for the console and other output without a viewer
     * @return the MiniMessage template, or {@code <red>missing:key} when no file has the key
     */
    public String template(@Nullable Locale locale, String key) {
        return template(resolutionChain(locale), key);
    }

    /**
     * Renders the message for {@code key} with {@code values}, without PlaceholderAPI or MiniPlaceholders. A
     * MiniPlaceholders tag stays as written here, also a global one that needs no viewer:
     * {@link #render(Text, Player, String, TagResolver...)} with a {@code null} viewer resolves those.
     *
     * <p>The caller picks how each value goes in: {@code Placeholder.component} or {@code Placeholder.unparsed} for
     * player-typed text, which is then inserted as it is, and {@code Placeholder.parsed} for a value that carries
     * MiniMessage on purpose. The prefix is supplied under the name {@code prefix}. A value of that name in
     * {@code values} takes its place.
     *
     * @param locale the viewer's locale, or {@code null} for the console and other output without a viewer
     */
    public Component render(@Nullable Locale locale, String key, TagResolver... values) {
        List<Map<String, List<String>>> chain = resolutionChain(locale);
        return parse(template(chain, key), resolvers(chain, values));
    }

    /**
     * Renders the message for {@code key} as {@link #render(Locale, String, TagResolver...)} does, one component per
     * line: a list gives one component per entry and any other value gives one component, which keeps any newline
     * its text holds. A tag opened on one line does not carry into the next.
     *
     * @param locale the viewer's locale, or {@code null} for the console and other output without a viewer
     * @return the lines in order, or the single line {@code <red>missing:key} when no file has the key; unmodifiable
     */
    public List<Component> lines(@Nullable Locale locale, String key, TagResolver... values) {
        List<Map<String, List<String>>> chain = resolutionChain(locale);
        TagResolver all = resolvers(chain, values);
        List<String> lines = lookup(chain, key);
        if (lines == null) {
            return List.of(parse(missing(key), all));
        }
        return lines.stream().map(line -> parse(line, all)).toList();
    }

    /**
     * Whether {@code key} resolves for {@code locale}: whether one of the files a render for that locale looks in,
     * the bundled English text included, holds it, so that the render gives that text and not the missing-key marker.
     *
     * @param locale the viewer's locale, or {@code null} for the console and other output without a viewer
     */
    public boolean has(@Nullable Locale locale, String key) {
        return lookup(resolutionChain(locale), key) != null;
    }

    private TagResolver resolvers(List<Map<String, List<String>>> chain, TagResolver... values) {
        TagResolver supplied = TagResolver.resolver(values);
        return withPrefix(Text.aliasBraces(prefix(chain), supplied, namesIgnoringCase), supplied);
    }

    private Component parse(String template, TagResolver all) {
        String converted = LegacyText.sectionToMiniMessage(Text.aliasBraces(template, all, namesIgnoringCase));
        return MINI.deserialize(converted, Text.sectionSafe(all));
    }

    /**
     * Renders the message for {@code key} in the viewer's language through {@code text}, so PlaceholderAPI and
     * MiniPlaceholders apply as {@link Text#render(String, Player, TagResolver...)} describes. PlaceholderAPI runs
     * on the template and on the prefix, never on {@code values}. What a placeholder returns is inserted as text
     * in both, as {@link Text} describes, unless {@code text} came from {@link Text#withPlaceholderFormatting()}.
     *
     * <p>The prefix is supplied under the name {@code prefix}. A value of that name in {@code values} takes its
     * place.
     *
     * @param viewer the player the message is for, or {@code null} for the console and other output without a viewer
     */
    public Component render(Text text, @Nullable Player viewer, String key, TagResolver... values) {
        List<Map<String, List<String>>> chain = resolutionChain(locale(viewer));
        return render(text, viewer, chain, template(chain, key), values);
    }

    /**
     * Renders {@code template}, a MiniMessage template that is not a key, the way
     * {@link #render(Text, Player, String, TagResolver...)} renders the template of a key: with the prefix of the
     * viewer's language supplied under the name {@code prefix} next to {@code values}. What
     * {@link Text#render(String, Player, TagResolver...)} states about a supplied {@code {name}} inside a tag
     * argument or a PlaceholderAPI placeholder holds for {@code template}.
     *
     * @param viewer the player the message is for, or {@code null} for the console and other output without a viewer
     */
    public Component renderTemplate(Text text, @Nullable Player viewer, String template, TagResolver... values) {
        return render(text, viewer, resolutionChain(locale(viewer)), template, values);
    }

    /**
     * Renders the message for {@code key} as {@link #render(Text, Player, String, TagResolver...)} does, with
     * {@code sender} as the viewer when it is a player and with no viewer otherwise, and sends it to {@code sender}
     * as one chat message. A message of several lines is sent as one message with its lines joined by a newline.
     * An empty message, one whose lines joined by a newline are an empty text (an empty text, a list of no lines
     * or a list of one empty line), is not sent. A key that no file has sends the missing-key marker. A
     * {@code Messages} from {@link #withChatPrefix} puts its prefix in front. Belongs on the thread that owns
     * {@code sender} when it is a player.
     */
    public void send(Text text, CommandSender sender, String key, TagResolver... values) {
        Player viewer = sender instanceof Player player ? player : null;
        List<Map<String, List<String>>> chain = resolutionChain(locale(viewer));
        String template = template(chain, key);
        if (template.isEmpty()) {
            return;
        }
        String front = chatPrefix == null ? null : chatPrefix.get();
        sender.sendMessage(render(text, viewer, chain, front == null ? template : front + template, values));
    }

    /**
     * Renders the message for {@code key} as {@link #render(Text, Player, String, TagResolver...)} does, with
     * {@code player} as the viewer, and shows it to that player as an action bar. An empty message, as
     * {@link #send} describes it, is not shown, so the action bar the player sees stays. The prefix of
     * {@link #withChatPrefix} is not added. Belongs on the thread that owns the player.
     */
    public void sendActionBar(Text text, Player player, String key, TagResolver... values) {
        List<Map<String, List<String>>> chain = resolutionChain(locale(player));
        String template = template(chain, key);
        if (!template.isEmpty()) {
            player.sendActionBar(render(text, player, chain, template, values));
        }
    }

    /**
     * Renders the messages for {@code titleKey} and {@code subtitleKey} as
     * {@link #render(Text, Player, String, TagResolver...)} does, with {@code player} as the viewer, and shows
     * them to that player as a title with {@code times}. A {@code null} {@code subtitleKey} gives an empty
     * subtitle. Nothing is shown when the title and the subtitle are both empty messages. The prefix of
     * {@link #withChatPrefix} is not added. Belongs on the thread that owns the player.
     */
    public void showTitle(
        Text text,
        Player player,
        String titleKey,
        @Nullable String subtitleKey,
        Title.Times times,
        TagResolver... values
    ) {
        List<Map<String, List<String>>> chain = resolutionChain(locale(player));
        String title = template(chain, titleKey);
        String subtitle = subtitleKey == null ? "" : template(chain, subtitleKey);
        if (title.isEmpty() && subtitle.isEmpty()) {
            return;
        }
        player.showTitle(Title.title(
            render(text, player, chain, title, values), render(text, player, chain, subtitle, values), times));
    }

    /**
     * Shows the title written as one lang entry: {@code <key>.title} and {@code <key>.subtitle} are the messages,
     * rendered and shown as {@link #showTitle(Text, Player, String, String, Title.Times, TagResolver...)} does, and
     * {@code <key>.fadeIn}, {@code <key>.stay} and {@code <key>.fadeOut} are the times as whole numbers of ticks.
     * A time that no file has, that is not a whole number of at least 0, or that is too long to count in
     * milliseconds, is the one of {@link Title#DEFAULT_TIMES}. A {@code <key>.subtitle} that no file has gives an
     * empty subtitle, and a {@code <key>.title} that no file has shows the missing-key marker. Belongs on the
     * thread that owns the player.
     */
    public void showTitle(Text text, Player player, String key, TagResolver... values) {
        List<Map<String, List<String>>> chain = resolutionChain(locale(player));
        Title.Times times = Title.Times.times(
            ticks(chain, key + ".fadeIn", Title.DEFAULT_TIMES.fadeIn()),
            ticks(chain, key + ".stay", Title.DEFAULT_TIMES.stay()),
            ticks(chain, key + ".fadeOut", Title.DEFAULT_TIMES.fadeOut()));
        String subtitle = key + ".subtitle";
        showTitle(text, player, key + ".title", lookup(chain, subtitle) == null ? null : subtitle, times, values);
    }

    private static Duration ticks(List<Map<String, List<String>>> chain, String key, Duration absent) {
        List<String> lines = lookup(chain, key);
        if (lines == null || lines.size() != 1) {
            return absent;
        }
        try {
            long ticks = Long.parseLong(lines.getFirst().strip());
            return ticks < 0 ? absent : Duration.ofMillis(Math.multiplyExact(ticks, Ticks.SINGLE_TICK_DURATION_MS));
        } catch (NumberFormatException | ArithmeticException notTicks) {
            return absent;
        }
    }

    private Component render(
        Text text,
        @Nullable Player viewer,
        List<Map<String, List<String>>> chain,
        String template,
        TagResolver... values
    ) {
        Text renderer = namesIgnoringCase ? text.withBracedNamesIgnoringCase() : text;
        return renderer.renderWithPrefix(template, prefix(chain), viewer, TagResolver.resolver(values));
    }

    private static TagResolver withPrefix(String prefix, TagResolver supplied) {
        // The later resolver answers first, so a supplied value named prefix wins.
        return TagResolver.resolver(Placeholder.parsed(PREFIX, LegacyText.sectionToMiniMessage(prefix)), supplied);
    }

    private static String prefix(List<Map<String, List<String>>> chain) {
        List<String> prefix = lookup(chain, PREFIX);
        return prefix == null ? "" : String.join(NEWLINE, prefix);
    }

    private static String template(List<Map<String, List<String>>> chain, String key) {
        List<String> template = lookup(chain, key);
        return template == null ? missing(key) : String.join(NEWLINE, template);
    }

    private static String missing(String key) {
        return "<red>missing:" + key;
    }

    private static @Nullable Locale locale(@Nullable Player viewer) {
        return viewer == null ? null : viewer.locale();
    }

    /** Maps to look in, most specific first, always ending at the bundled English text. */
    private List<Map<String, List<String>>> resolutionChain(@Nullable Locale locale) {
        List<Map<String, List<String>>> chain = new ArrayList<>(3);
        Map<String, Map<String, List<String>>> langs = source.loaded.locales();
        if (source.perPlayer && locale != null) {
            Map<String, List<String>> exact = langs.get(code(locale.toString()));
            if (exact != null) {
                chain.add(exact);
            }
            Map<String, List<String>> language = langs.get(code(locale.getLanguage()));
            if (language != null && language != exact) {
                chain.add(language);
            }
        }
        Map<String, List<String>> def = langs.get(source.defaultLocale);
        if (def != null && !chain.contains(def)) {
            chain.add(def);
        }
        chain.add(source.fallback);
        return chain;
    }

    private static @Nullable List<String> lookup(List<Map<String, List<String>>> chain, String key) {
        for (Map<String, List<String>> map : chain) {
            List<String> value = map.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** The end of the warning about a file that lacks keys: where those keys resolve instead. */
    private String fallback(String locale) {
        String shown = source.folderName;
        String defaultLocale = source.defaultLocale;
        if (locale.equals(defaultLocale)) {
            return " use the bundled English text.";
        }
        int region = locale.indexOf('_');
        if (defaultLocale.equals(FALLBACK)) {
            return region < 0 ? " use the English text."
                : " fall back to " + shown + "/" + locale.substring(0, region) + ".yml, then to English.";
        }
        String byDefault = shown + "/" + defaultLocale + ".yml, then to the bundled English text.";
        if (region < 0 || locale.substring(0, region).equals(defaultLocale)) {
            return " fall back to " + byDefault;
        }
        return " fall back to " + shown + "/" + locale.substring(0, region) + ".yml, then to " + byDefault;
    }

    private static @Nullable InputStream open(Path file) {
        try {
            return Files.newInputStream(file);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** The bytes of the source, or {@code null} when it is missing or cannot be read. */
    private static byte @Nullable [] bytes(Supplier<@Nullable InputStream> source) {
        try (InputStream in = source.get()) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException unreadable) {
            return null;
        }
    }

    /**
     * The line, counted from 1, of the first byte sequence of {@code content} that is not UTF-8, or 0 when
     * {@code content} is UTF-8. A line ends at a line feed.
     */
    private static int malformedLine(byte[] content) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        ByteBuffer in = ByteBuffer.wrap(content);
        CharBuffer out = CharBuffer.allocate(8192);
        CoderResult result = decoder.decode(in, out, true);
        while (result.isOverflow()) {
            out.clear();
            result = decoder.decode(in, out, true);
        }
        if (!result.isError()) {
            return 0;
        }
        int line = 1;
        for (int index = 0; index < in.position(); index++) {
            if (content[index] == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * Flat keys with their lines in the order of the file, or {@code null} when {@code content} is not valid YAML.
     * A byte sequence that is not UTF-8 is read as the replacement character U+FFFD.
     */
    private static @Nullable Map<String, List<String>> values(byte[] content) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(new String(content, StandardCharsets.UTF_8));
            Map<String, List<String>> values = new LinkedHashMap<>();
            for (String key : yaml.getKeys(true)) {
                Object value = yaml.get(key);
                if (yaml.isConfigurationSection(key) || value == null) {
                    continue;
                }
                if (value instanceof List<?> list) {
                    values.put(key, list.stream().map(entry -> entry == null ? "" : String.valueOf(entry)).toList());
                } else {
                    values.put(key, List.of(String.valueOf(value)));
                }
            }
            return values;
        } catch (InvalidConfigurationException | RuntimeException unreadable) {
            return null;
        }
    }
}
