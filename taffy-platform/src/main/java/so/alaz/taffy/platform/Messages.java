package so.alaz.taffy.platform;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.logging.Logger;

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
 * <p>A key resolves through, in order: the file of the exact locale ({@code pt_br}), the file of its language
 * ({@code pt}), the file of the default locale, then the bundled English text. The first two are used only when
 * per-player languages are on and a locale is given. A file name, the default locale and a viewer's locale are all
 * matched in lower case with {@code -} read as {@code _}, so {@code pt-BR} finds {@code pt_br.yml}.
 *
 * <p>A template writes a value as {@code <name>} or as {@code {name}}, which reads as {@code <name>} for every name
 * a call supplies. The prefix is the template under the key {@code prefix}, its lines joined by a newline when it
 * is a list. It is never put in front of a message: it appears where a template writes {@code <prefix>} or
 * {@code {prefix}}, and is empty when no file has the key.
 *
 * <p>A key that no file has renders as the visible marker {@code <red>missing:key}. A section-sign code in a
 * template, in the prefix or in a value supplied as {@code Placeholder.parsed} is rendered as
 * {@link LegacyText#sectionToMiniMessage} converts it.
 *
 * <p>Every method that takes no viewer may be called from any thread. The methods that take a viewer follow the
 * thread rule of {@link Text}. {@link #reload()} replaces every loaded file at once.
 */
public final class Messages {

    /** The folder inside the data folder that holds the language files. */
    public static final String FOLDER = "lang";

    /** The locale of the bundled file, and the default locale when none is configured. */
    public static final String FALLBACK = "en";

    private static final String PREFIX = "prefix";
    private static final String NEWLINE = "\n";
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Path folder;
    private final Supplier<@Nullable InputStream> bundled;
    private final Logger logger;
    private final String defaultLocale;
    private final boolean perPlayer;
    private final boolean lenient;
    private final Map<String, List<String>> fallback;

    /** Locale code in lower case to that file's templates. Replaced as a whole and never changed afterwards. */
    private volatile Map<String, Map<String, List<String>>> locales = Map.of();

    private Messages(
        Path folder,
        Supplier<@Nullable InputStream> bundled,
        Logger logger,
        String defaultLocale,
        boolean perPlayer,
        boolean lenient,
        Map<String, List<String>> fallback
    ) {
        this.folder = folder;
        this.bundled = bundled;
        this.logger = logger;
        this.defaultLocale = defaultLocale;
        this.perPlayer = perPlayer;
        this.lenient = lenient;
        this.fallback = fallback;
    }

    /**
     * Loads the messages with English as the default locale and per-player languages on.
     *
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
     * @throws IllegalStateException when the bundled file is missing, is not valid UTF-8 or is not valid YAML
     */
    public static Messages load(
        Path dataFolder,
        Supplier<@Nullable InputStream> bundled,
        Logger logger,
        @Nullable String defaultLocale,
        boolean perPlayer
    ) {
        return load(dataFolder, bundled, logger, defaultLocale, perPlayer, false);
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
     * @throws IllegalStateException when the bundled file is missing, is not valid UTF-8 or is not valid YAML
     */
    public static Messages loadLenient(
        Path dataFolder,
        Supplier<@Nullable InputStream> bundled,
        Logger logger,
        @Nullable String defaultLocale,
        boolean perPlayer
    ) {
        return load(dataFolder, bundled, logger, defaultLocale, perPlayer, true);
    }

    private static Messages load(
        Path dataFolder,
        Supplier<@Nullable InputStream> bundled,
        Logger logger,
        @Nullable String defaultLocale,
        boolean perPlayer,
        boolean lenient
    ) {
        byte[] content = bytes(bundled);
        Map<String, List<String>> fallback = content == null || malformedLine(content) > 0 ? null : values(content);
        if (fallback == null) {
            throw new IllegalStateException("the bundled " + FALLBACK + ".yml is missing or unreadable");
        }
        Messages messages = new Messages(
            dataFolder.resolve(FOLDER), bundled, logger, normalizeLocale(defaultLocale), perPlayer, lenient, fallback);
        messages.reload();
        return messages;
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

    /** The default locale given at load, as {@link #normalizeLocale} reads it. */
    public String defaultLocale() {
        return defaultLocale;
    }

    /** Whether a message is served in the language of the locale it is asked for, as it was given at load. */
    public boolean perPlayer() {
        return perPlayer;
    }

    /**
     * Reads the language folder again, with the same warnings as the load, and writes {@code lang/en.yml} again when
     * it is absent. The bundled English text is not read again. Calls do not overlap, and a render that runs
     * during a call sees either all the old files or all the new ones.
     */
    public synchronized void reload() {
        Path english = folder.resolve(FALLBACK + ".yml");
        try {
            Files.createDirectories(folder);
            if (Files.notExists(english)) {
                try (InputStream in = bundled.get()) {
                    if (in != null) {
                        Files.copy(in, english);
                    }
                }
            }
        } catch (IOException failure) {
            logger.warning("Could not write " + FOLDER + "/" + FALLBACK + ".yml: " + failure.getMessage()
                + ". Messages use the bundled English text.");
        }
        Map<String, Map<String, List<String>>> loaded = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        if (Files.isDirectory(folder)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.yml")) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    String locale = code(name.substring(0, name.length() - ".yml".length()));
                    byte[] content = bytes(() -> open(file));
                    int malformedLine = content == null ? 0 : malformedLine(content);
                    if (malformedLine > 0 && !lenient) {
                        logger.warning(FOLDER + "/" + name + " is not saved as UTF-8 (the first byte that is not UTF-8"
                            + " is on line " + malformedLine + "), so its messages are not used.");
                        continue;
                    }
                    if (malformedLine > 0) {
                        logger.warning(FOLDER + "/" + name + " is not saved as UTF-8, so each byte that is not UTF-8"
                            + " is read as the replacement character U+FFFD; the first is on line " + malformedLine
                            + ". Save the file as UTF-8.");
                    }
                    Map<String, List<String>> values = content == null ? null : values(content);
                    if (values == null) {
                        logger.warning(FOLDER + "/" + name + " is not valid YAML saved as UTF-8, so its messages are"
                            + " not used.");
                        continue;
                    }
                    Set<String> missing = new TreeSet<>(fallback.keySet());
                    missing.removeAll(values.keySet());
                    if (!missing.isEmpty()) {
                        logger.warning(FOLDER + "/" + name + " lacks " + String.join(", ", missing) + "; those messages"
                            + fallback(locale));
                    }
                    String replaced = names.put(locale, name);
                    if (replaced != null) {
                        logger.warning(FOLDER + "/" + replaced + " and " + FOLDER + "/" + name + " are both read as"
                            + " the language " + locale + ", so only " + FOLDER + "/" + name + " is used. Remove or"
                            + " rename one of them.");
                    }
                    loaded.put(locale, values);
                }
            } catch (IOException | DirectoryIteratorException failure) {
                // A failure while iterating arrives unchecked, with the I/O error as its cause.
                Throwable reason = failure instanceof DirectoryIteratorException ? failure.getCause() : failure;
                loaded.clear();
                logger.warning("Could not read the " + FOLDER + " folder: " + reason.getMessage()
                    + ". Messages use the bundled English text.");
            }
        }
        if (!defaultLocale.equals(FALLBACK) && !loaded.containsKey(defaultLocale)) {
            logger.warning("The default language " + defaultLocale + " has no " + FOLDER + "/" + defaultLocale
                + ".yml, so the bundled English text is used in its place.");
        }
        this.locales = loaded;
    }

    /** The locale codes of the language files that are loaded, as {@link #normalizeLocale} reads them. */
    public Set<String> loadedLanguages() {
        return Set.copyOf(locales.keySet());
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

    private static TagResolver resolvers(List<Map<String, List<String>>> chain, TagResolver... values) {
        TagResolver supplied = TagResolver.resolver(values);
        return withPrefix(Text.aliasBraces(prefix(chain), supplied), supplied);
    }

    private static Component parse(String template, TagResolver all) {
        String converted = LegacyText.sectionToMiniMessage(Text.aliasBraces(template, all));
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

    private static Component render(
        Text text,
        @Nullable Player viewer,
        List<Map<String, List<String>>> chain,
        String template,
        TagResolver... values
    ) {
        return text.renderWithPrefix(template, prefix(chain), viewer, TagResolver.resolver(values));
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
        Map<String, Map<String, List<String>>> langs = locales;
        if (perPlayer && locale != null) {
            Map<String, List<String>> exact = langs.get(code(locale.toString()));
            if (exact != null) {
                chain.add(exact);
            }
            Map<String, List<String>> language = langs.get(code(locale.getLanguage()));
            if (language != null && language != exact) {
                chain.add(language);
            }
        }
        Map<String, List<String>> def = langs.get(defaultLocale);
        if (def != null && !chain.contains(def)) {
            chain.add(def);
        }
        chain.add(fallback);
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
        if (locale.equals(defaultLocale)) {
            return " use the bundled English text.";
        }
        int region = locale.indexOf('_');
        if (defaultLocale.equals(FALLBACK)) {
            return region < 0 ? " use the English text."
                : " fall back to " + FOLDER + "/" + locale.substring(0, region) + ".yml, then to English.";
        }
        String byDefault = FOLDER + "/" + defaultLocale + ".yml, then to the bundled English text.";
        if (region < 0 || locale.substring(0, region).equals(defaultLocale)) {
            return " fall back to " + byDefault;
        }
        return " fall back to " + FOLDER + "/" + locale.substring(0, region) + ".yml, then to " + byDefault;
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
     * Flat keys with their lines, or {@code null} when {@code content} is not valid YAML. A byte sequence that is
     * not UTF-8 is read as the replacement character U+FFFD.
     */
    private static @Nullable Map<String, List<String>> values(byte[] content) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(new String(content, StandardCharsets.UTF_8));
            Map<String, List<String>> values = new HashMap<>();
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
