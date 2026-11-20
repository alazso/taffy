package so.alaz.taffy.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Loads a plugin's {@code config.yml} from its data folder and brings an older file up to the layout of the
 * file shipped inside the plugin jar.
 *
 * <p>The shipped file carries its layout version under {@code config-version} as a whole number from 1 to
 * 2147483647, written without quotes. A shipped file without one fails every {@link #load()}, the first one
 * included, so a plugin cannot ship a file whose owners never get an upgrade.
 *
 * <p>In the file in the data folder the version may also be written as text, and a number is read as an
 * {@code int}: a decimal number counts as its whole part, and a number above 2147483647 is not supported. A file
 * with no such key, or with a value that is neither a number nor text holding a whole number of at most nine
 * digits, counts as the baseline version: 0, or the version given to {@link #baselineVersion(int)}. What
 * {@link #load()} does depends on the two versions:
 *
 * <ul>
 *   <li>No file: the shipped text is written byte for byte and loaded.
 *   <li>An older file: every shipped key the file does not have is added with its comments, the version is set
 *       to the shipped one, the previous file is written to {@code config.yml.v<N>.bak} beside it (N is the old
 *       version, and a copy of that name from an earlier upgrade is replaced) and the upgraded file is saved.
 *       Keys and values the owner has are kept, unknown keys included. A shipped key is not added beneath a
 *       path where the owner's file holds a plain value. The copy holds the file exactly as it was, byte for
 *       byte.
 *       <p>YAML drops a key whose value is empty or null while it parses the file, so such a key counts as
 *       missing: it gets the shipped value and comments when the shipped file has the key, and is left out of
 *       the saved file otherwise.
 *   <li>The same version: the file is read as it is, even when a shipped key is missing from it.
 *   <li>A newer file: the file is read as it is and a warning is logged.
 * </ul>
 *
 * <p>The save of an upgrade writes the file anew from the parsed settings, so every value is kept and the way it
 * is written can change:
 *
 * <ul>
 *   <li>A text without a line break is written on one line whatever its length, and is never folded over
 *       several lines.
 *   <li>Quotes are not kept: a text that needs none is written without them, and one that needs them gets the
 *       quotes the writer picks, so {@code "0451"} becomes {@code '0451'}. A text of several lines is written
 *       as a block ({@code |}), a folded block ({@code >}) included.
 *   <li>A number, a date and {@code true}/{@code false} are written in the plain form of their value, so
 *       {@code 0x10} becomes {@code 16}, {@code yes} becomes {@code true} and {@code 2026-12-31} becomes
 *       {@code 2026-12-31T00:00:00Z}.
 *   <li>A key the upgrade adds is written after the keys the owner's file has in that section, not at the place
 *       it has in the shipped file.
 *   <li>A comment inside a list is not written back, the indent of a list can change, and a comment or blank
 *       line elsewhere can move.
 *   <li>Every line ends in a line feed, and a byte order mark at the start of the file is not written back.
 * </ul>
 *
 * <p>Before anything is written, the text of the upgraded file is parsed again and compared with the upgraded
 * settings. When the text cannot be produced, or a value would not be read back as it is (a text with a control
 * character, which the writer turns into {@code !!binary}, is one), nothing is written, no copy is made and the
 * failure is handled as a failed save.
 *
 * <p>When the copy or the save of an upgrade fails, a warning is logged, the file stays as the owner left it
 * and the upgraded settings are returned, so the next load tries the upgrade again. The copy and the save each
 * go through a temporary file in the same folder that is flushed to the device and then replaces the target,
 * and the copy is on the device before the save replaces the file. The save follows a symbolic link to the file
 * it names and keeps the POSIX permissions of the file where the file system has them, and the copy gets those
 * permissions too. A file written where none existed gets the permissions the file system gives any new file.
 * The temporary file is {@code config.yml.tmp} (for a symbolic link, the name of the file it leads to followed
 * by {@code .tmp}), and that of the copy is the name of the copy followed by {@code .tmp}; one left behind by a
 * write that was cut off is removed by the next load or the next write of that name.
 *
 * <p>The loader holds no state between loads. Calls to {@link #load()} must not overlap.
 */
public final class ConfigLoader {

    /** The name of the settings file in the data folder. */
    public static final String FILE_NAME = "config.yml";

    /** The key that holds the layout version of the file. */
    public static final String VERSION_KEY = "config-version";

    private static final String SHIPPED = "the " + FILE_NAME + " inside the plugin jar";

    private final Path file;
    private final Supplier<@Nullable InputStream> shipped;
    private final Logger logger;
    private final Map<String, Set<String>> ownerKeyed;
    private final int baseline;

    /**
     * A loader with no owner-keyed section: an upgrade adds every shipped key the file does not have.
     *
     * @param dataFolder the plugin's data folder, which is created when the file is first written
     * @param shipped opens the {@code config.yml} inside the plugin jar, or gives {@code null} when the jar has
     *     none. It is asked on every load and the stream is closed by the loader.
     * @param logger receives one line for a written default file, an upgrade, a failed save and a newer file
     */
    public ConfigLoader(Path dataFolder, Supplier<@Nullable InputStream> shipped, Logger logger) {
        this(dataFolder, shipped, logger, Map.of());
    }

    /**
     * A loader that leaves the entries of owner-keyed sections to the owner during an upgrade.
     *
     * @param dataFolder the plugin's data folder, which is created when the file is first written
     * @param shipped opens the {@code config.yml} inside the plugin jar, or gives {@code null} when the jar has
     *     none. It is asked on every load and the stream is closed by the loader.
     * @param logger receives one line for a written default file, an upgrade, a failed save and a newer file
     * @param ownerKeyed the sections whose entries the owner names, each as a full dotted path, with the keys
     *     under it that are not entries. When the file already holds such a section with at least one key, a
     *     shipped entry the owner does not have is not added, so a renamed or removed entry does not come back;
     *     missing keys inside an entry the owner kept are still added. An empty section gets the shipped
     *     entries, and so does a file without the section. No listed section may lie inside another listed
     *     section. The map and its sets are copied.
     * @throws IllegalArgumentException when a listed path is blank, or when one listed section lies inside
     *     another, as {@code kits.special} lies inside {@code kits}
     */
    public ConfigLoader(
        Path dataFolder,
        Supplier<@Nullable InputStream> shipped,
        Logger logger,
        Map<String, Set<String>> ownerKeyed) {
        this(dataFolder.toAbsolutePath().resolve(FILE_NAME), shipped, logger, copyOf(ownerKeyed), 0);
    }

    private ConfigLoader(
        Path file,
        Supplier<@Nullable InputStream> shipped,
        Logger logger,
        Map<String, Set<String>> ownerKeyed,
        int baseline) {
        this.file = file;
        this.shipped = shipped;
        this.logger = logger;
        this.ownerKeyed = ownerKeyed;
        this.baseline = baseline;
    }

    private static Map<String, Set<String>> copyOf(Map<String, Set<String>> ownerKeyed) {
        Map<String, Set<String>> copy = new HashMap<>();
        ownerKeyed.forEach((section, fixed) -> copy.put(section, Set.copyOf(fixed)));
        for (String section : copy.keySet()) {
            if (section.isBlank()) {
                throw new IllegalArgumentException("an owner-keyed section has a blank path");
            }
            for (String other : copy.keySet()) {
                if (other.startsWith(section + ".")) {
                    throw new IllegalArgumentException("the owner-keyed section " + other
                        + " lies inside the owner-keyed section " + section);
                }
            }
        }
        return Map.copyOf(copy);
    }

    /**
     * A copy of this loader that reads a file in the data folder without a usable {@code config-version} as
     * {@code version}, for a plugin whose earlier releases shipped a {@code config.yml} without the key. With
     * the baseline at the version of the layout those releases wrote, such a file is left as it is while the
     * shipped file has that version, and is upgraded from it, with the copy named
     * {@code config.yml.v<version>.bak}, once the shipped version is higher. A file that holds a version is not
     * affected. This loader is not changed.
     *
     * @param version the version such a file counts as, 0 or more; 0 is what a loader has before this call
     * @throws IllegalArgumentException when {@code version} is negative
     */
    public ConfigLoader baselineVersion(int version) {
        if (version < 0) {
            throw new IllegalArgumentException("the baseline version is " + version + "; expected 0 or more");
        }
        return new ConfigLoader(file, shipped, logger, ownerKeyed, version);
    }

    /**
     * Reads the settings file, writing or upgrading it first as the class description states. The file I/O
     * blocks the calling thread.
     *
     * <p>It may be called again for a reload. Each call reads the shipped file and the file in the data folder
     * anew, writes the file again when it is missing and repeats an upgrade that was not saved.
     *
     * @return the settings with the comments of the file kept, a new object on every call
     * @throws ConfigLoadException when the shipped file is absent, unreadable, not UTF-8 or not valid YAML,
     *     when it has no whole number of 1 or more under {@code config-version} (the message is
     *     {@code the config.yml inside the plugin jar has no whole number of 1 or more under config-version}),
     *     when the file in the data folder cannot be read, is not UTF-8 or is not valid YAML, or when a missing
     *     file cannot be written. A file that exists in the data folder is not changed in any of these cases.
     *     A message about a file that cannot be read or written names the file as {@code config.yml}, without
     *     its path, and has the I/O failure as its cause.
     */
    public YamlConfiguration load() throws ConfigLoadException {
        try {
            Files.deleteIfExists(temporary(target()));
        } catch (IOException leftoverKept) {
            // A leftover that cannot be removed here is removed or refused by the next write.
        }
        String shippedText = readShipped();
        YamlConfiguration defaults = StrictYaml.parse(shippedText, SHIPPED);
        if (!(defaults.get(VERSION_KEY) instanceof Integer shippedVersion) || shippedVersion < 1) {
            throw new ConfigLoadException(SHIPPED + " has no whole number of 1 or more under " + VERSION_KEY);
        }
        if (Files.notExists(file)) {
            write(shippedText);
            logger.info("Wrote the default " + FILE_NAME + ", so the default settings are in effect.");
            return StrictYaml.parse(shippedText, FILE_NAME);
        }
        byte[] previous = StrictYaml.bytes(file, FILE_NAME);
        YamlConfiguration current = StrictYaml.parse(StrictYaml.decode(previous, FILE_NAME), FILE_NAME);
        int currentVersion = version(current.get(VERSION_KEY), baseline);
        if (currentVersion < shippedVersion) {
            List<String> added = addMissing(current, defaults);
            current.set(VERSION_KEY, shippedVersion);
            Path backup = file.resolveSibling(FILE_NAME + ".v" + currentVersion + ".bak");
            try {
                String upgraded = upgradedText(current);
                copy(previous, backup);
                write(upgraded);
                logger.info("Upgraded " + FILE_NAME + " from version " + currentVersion + " to " + shippedVersion
                    + (added.isEmpty() ? "" : " and added " + String.join(", ", added))
                    + "; the previous file is kept as " + backup.getFileName() + ".");
            } catch (ConfigLoadException failure) {
                logger.warning("Could not save the upgraded " + FILE_NAME + ": " + failure.getMessage()
                    + ". The upgraded settings are used and the save is tried again the next time the settings are"
                    + " loaded.");
            }
        } else if (currentVersion > shippedVersion) {
            logger.warning(FILE_NAME + " is version " + currentVersion + ", newer than the version " + shippedVersion
                + " this build knows, so it is read as it is and nothing is changed.");
        }
        return current;
    }

    /** The text {@code current} is saved as, checked to load back to the values {@code current} holds. */
    private static String upgradedText(YamlConfiguration current) throws ConfigLoadException {
        String text;
        try {
            current.options().width(Integer.MAX_VALUE);
            text = current.saveToString();
        } catch (RuntimeException failure) {
            throw new ConfigLoadException("its settings cannot be written as YAML (" + StrictYaml.reason(failure)
                + ")");
        }
        YamlConfiguration written;
        try {
            written = StrictYaml.parse(text, "the upgraded " + FILE_NAME);
        } catch (ConfigLoadException unreadable) {
            throw new ConfigLoadException("its settings cannot be written as YAML (" + unreadable.getMessage() + ")");
        }
        Map<String, Object> before = current.getValues(true);
        Map<String, Object> after = written.getValues(true);
        Set<String> paths = new LinkedHashSet<>(before.keySet());
        paths.addAll(after.keySet());
        for (String path : paths) {
            Object kept = before.get(path);
            Object read = after.get(path);
            if (kept instanceof ConfigurationSection && read instanceof ConfigurationSection) {
                continue;
            }
            if (!Values.same(kept, read)) {
                throw new ConfigLoadException("the value of " + path + " would not be read back as it is");
            }
        }
        return text;
    }

    private List<String> addMissing(YamlConfiguration current, YamlConfiguration defaults) {
        Map<String, Set<String>> kept = new HashMap<>();
        ownerKeyed.forEach((section, fixed) -> {
            if (current.isConfigurationSection(section)
                && !current.getConfigurationSection(section).getKeys(false).isEmpty()) {
                kept.put(section, current.getConfigurationSection(section).getKeys(false));
            }
        });
        List<String> added = new ArrayList<>();
        for (String path : defaults.getKeys(true)) {
            if (current.contains(path, true) || underScalar(current, path) || ownerLeftOut(kept, path)) {
                continue;
            }
            if (defaults.isConfigurationSection(path)) {
                current.createSection(path);
            } else {
                current.set(path, defaults.get(path));
                if (!path.equals(VERSION_KEY)) {
                    added.add(path);
                }
            }
            current.setComments(path, defaults.getComments(path));
            current.setInlineComments(path, defaults.getInlineComments(path));
        }
        return added;
    }

    static int version(@Nullable Object raw, int baseline) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text && text.strip().matches("-?\\d{1,9}")) {
            return Integer.parseInt(text.strip());
        }
        return baseline;
    }

    private boolean ownerLeftOut(Map<String, Set<String>> kept, String path) {
        for (Map.Entry<String, Set<String>> section : kept.entrySet()) {
            String prefix = section.getKey() + ".";
            if (path.startsWith(prefix)) {
                String entry = path.substring(prefix.length()).split("\\.", 2)[0];
                return !ownerKeyed.get(section.getKey()).contains(entry) && !section.getValue().contains(entry);
            }
        }
        return false;
    }

    private static boolean underScalar(YamlConfiguration current, String path) {
        int dot = path.lastIndexOf('.');
        while (dot > 0) {
            String parent = path.substring(0, dot);
            if (current.contains(parent, true) && !current.isConfigurationSection(parent)) {
                return true;
            }
            dot = parent.lastIndexOf('.');
        }
        return false;
    }

    private String readShipped() throws ConfigLoadException {
        try (InputStream in = shipped.get()) {
            if (in == null) {
                throw new ConfigLoadException("the plugin jar has no " + FILE_NAME);
            }
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(in.readAllBytes())).toString();
        } catch (CharacterCodingException notUtf8) {
            throw new ConfigLoadException(SHIPPED + " is not saved as UTF-8", notUtf8);
        } catch (IOException failure) {
            throw new ConfigLoadException("cannot read " + SHIPPED, failure);
        }
    }

    private void copy(byte[] previous, Path backup) throws ConfigLoadException {
        try {
            replace(backup, previous, target());
        } catch (IOException failure) {
            throw new ConfigLoadException(
                "cannot copy it to " + backup.getFileName() + ": " + StrictYaml.describe(failure), failure);
        }
    }

    private void write(String text) throws ConfigLoadException {
        try {
            Path target = target();
            Files.createDirectories(target.getParent());
            replace(target, text.getBytes(StandardCharsets.UTF_8), target);
        } catch (IOException failure) {
            throw new ConfigLoadException("cannot write " + FILE_NAME + ": " + StrictYaml.describe(failure), failure);
        }
    }

    /**
     * Writes {@code content} to a temporary file beside {@code target}, flushes it to the device and moves it
     * over {@code target}. The file gets the POSIX permissions of {@code permissionsOf} when that file exists.
     */
    private static void replace(Path target, byte[] content, Path permissionsOf) throws IOException {
        Path temporary = temporary(target);
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
            if (Files.exists(permissionsOf)
                && Files.getFileStore(permissionsOf).supportsFileAttributeView(PosixFileAttributeView.class)) {
                Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(permissionsOf));
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

    private Path target() throws IOException {
        return Files.isSymbolicLink(file) ? file.toRealPath() : file;
    }

    private static Path temporary(Path target) {
        return target.resolveSibling(target.getFileName() + ".tmp");
    }
}
