package so.alaz.taffy.config;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Loads a plugin's {@code config.yml} from its data folder and brings an older file up to the layout of the
 * file shipped inside the plugin jar.
 *
 * <p>The shipped file carries its layout version as a whole number under {@code config-version}. In the file in
 * the data folder the version may also be written as text. A file with no such key, or with a value that is
 * neither a number nor text holding a whole number of at most nine digits, counts as version 0. In both files
 * a number is read as an {@code int}: a decimal number counts as its whole part, and a number above 2147483647
 * is not supported. What {@link #load()} does depends on the two versions:
 *
 * <ul>
 *   <li>No file: the shipped text is written byte for byte and loaded.
 *   <li>An older file: every shipped key the file does not have is added with its comments, the version is set
 *       to the shipped one, the previous file is copied to {@code config.yml.v<N>.bak} beside it (N is the old
 *       version, and a copy of that name from an earlier upgrade is replaced) and the upgraded file is saved.
 *       Keys and values the owner has are kept, unknown keys included. A shipped key is not added beneath a
 *       path where the owner's file holds a plain value. The save writes the file anew from the parsed
 *       settings, so its formatting can change and a comment inside a list is not written back. The copy holds
 *       the file exactly as it was.
 *       <p>YAML drops a key whose value is empty or null while it parses the file, so such a key counts as
 *       missing: it gets the shipped value and comments when the shipped file has the key, and is left out of
 *       the saved file otherwise.
 *   <li>The same version: the file is read as it is, even when a shipped key is missing from it.
 *   <li>A newer file: the file is read as it is and a warning is logged.
 * </ul>
 *
 * <p>When the copy or the save of an upgrade fails, a warning is logged, the file stays as the owner left it
 * and the upgraded settings are returned, so the next load tries the upgrade again. The save goes through a
 * temporary file in the same folder that is flushed to the device and then replaces the target. It follows a
 * symbolic link to the file it names and keeps the POSIX permissions of the file where the file system has
 * them. A file written where none existed gets the permissions the file system gives any new file. The
 * temporary file is {@code config.yml.tmp} (for a symbolic link, the name of the file it leads to followed by
 * {@code .tmp}); one left behind by a write that was cut off is removed by the next load.
 *
 * <p>The loader holds no state between loads. Calls to {@link #load()} must not overlap.
 */
public final class ConfigLoader {

    /** The name of the settings file in the data folder. */
    public static final String FILE_NAME = "config.yml";

    /** The key that holds the layout version of the file. */
    public static final String VERSION_KEY = "config-version";

    private final Path file;
    private final Supplier<@Nullable InputStream> shipped;
    private final Logger logger;
    private final Map<String, Set<String>> ownerKeyed;

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
     */
    public ConfigLoader(
        Path dataFolder,
        Supplier<@Nullable InputStream> shipped,
        Logger logger,
        Map<String, Set<String>> ownerKeyed) {
        this.file = dataFolder.toAbsolutePath().resolve(FILE_NAME);
        this.shipped = shipped;
        this.logger = logger;
        Map<String, Set<String>> copy = new HashMap<>();
        ownerKeyed.forEach((section, fixed) -> copy.put(section, Set.copyOf(fixed)));
        this.ownerKeyed = Map.copyOf(copy);
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
     *     when the file in the data folder cannot be read, is not UTF-8 or is not valid YAML, or when a missing
     *     file cannot be written. A file that exists in the data folder is not changed in any of these cases.
     */
    public YamlConfiguration load() throws ConfigLoadException {
        try {
            Files.deleteIfExists(temporary(target()));
        } catch (IOException leftoverKept) {
            // A leftover that cannot be removed here is removed or refused by the next write.
        }
        String shippedText = readShipped();
        YamlConfiguration defaults = StrictYaml.parse(shippedText, "the " + FILE_NAME + " inside the plugin jar");
        if (Files.notExists(file)) {
            write(shippedText);
            logger.info("Wrote the default " + FILE_NAME + ", so the default settings are in effect.");
            return StrictYaml.parse(shippedText, FILE_NAME);
        }
        YamlConfiguration current = StrictYaml.read(file, FILE_NAME);
        int shippedVersion = defaults.getInt(VERSION_KEY);
        int currentVersion = version(current.get(VERSION_KEY));
        if (currentVersion < shippedVersion) {
            List<String> added = addMissing(current, defaults);
            current.set(VERSION_KEY, shippedVersion);
            Path backup = file.resolveSibling(FILE_NAME + ".v" + currentVersion + ".bak");
            try {
                copy(backup);
                write(current.saveToString());
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

    static int version(@Nullable Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text && text.strip().matches("-?\\d{1,9}")) {
            return Integer.parseInt(text.strip());
        }
        return 0;
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
            throw new ConfigLoadException("the " + FILE_NAME + " inside the plugin jar is not saved as UTF-8", notUtf8);
        } catch (IOException failure) {
            throw new ConfigLoadException("cannot read the " + FILE_NAME + " inside the plugin jar", failure);
        }
    }

    private void copy(Path backup) throws ConfigLoadException {
        try {
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new ConfigLoadException("cannot copy it to " + backup.getFileName() + ": " + describe(failure),
                failure);
        }
    }

    private void write(String text) throws ConfigLoadException {
        try {
            Path target = target();
            Files.createDirectories(target.getParent());
            Path temporary = temporary(target);
            Files.deleteIfExists(temporary);
            FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                try (channel) {
                    ByteBuffer bytes = StandardCharsets.UTF_8.encode(text);
                    while (bytes.hasRemaining()) {
                        channel.write(bytes);
                    }
                    // The bytes reach the device before the rename, so a power loss after it cannot leave an empty file.
                    channel.force(true);
                }
                if (Files.exists(target)
                    && Files.getFileStore(target).supportsFileAttributeView(PosixFileAttributeView.class)) {
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
        } catch (IOException failure) {
            throw new ConfigLoadException("cannot write " + file + ": " + describe(failure), failure);
        }
    }

    private Path target() throws IOException {
        return Files.isSymbolicLink(file) ? file.toRealPath() : file;
    }

    private static Path temporary(Path target) {
        return target.resolveSibling(target.getFileName() + ".tmp");
    }

    private static String describe(IOException failure) {
        return failure.getClass().getSimpleName() + " " + failure.getMessage();
    }
}
