package so.alaz.taffy.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.Relocation;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Reads a folder of YAML files in which each file is one entry, such as one menu or one kit per file.
 *
 * <p>A file counts when its name ends in {@code .yml} in any letter case, has at least one character before
 * that suffix and does not start with a dot. Its id is the name without those four characters, in the letter
 * case the file has. A name that starts with a dot is skipped without a line, as the hidden files and the
 * {@code ._<name>.yml} files macOS writes beside a file on a foreign file system are. A regular file whose name
 * ends in {@code .yaml} is not read: {@link #load} reports it with a line that tells the owner to rename it.
 * Files in subfolders are not read, and {@link #load} reports a subfolder whose name ends in {@code .yml} as a
 * file that cannot be read. Each file is read with {@link StrictYaml#read}, or with
 * {@link StrictYaml#readLenient} after {@link #lenient}, and handed to a {@link Parser}. A file that fails is
 * reported in {@link Loaded#errors()} and the other files still load.
 *
 * <p>The folder is never written to and nothing is kept between calls, so one instance may be used from any
 * thread. The reads block the calling thread, and the consumers given to the constructor and to
 * {@link #lenient} are called on it.
 */
public final class YamlFolder {

    private static final String SUFFIX = ".yml";
    private static final String LONG_SUFFIX = ".yaml";

    /**
     * Turns one file into an entry.
     *
     * <p>A parser that reads the file through a {@link ConfigReader} passes it a warning consumer that puts
     * {@code fileName} and {@code ": "} before each line, so every warning names its file as the error lines of
     * {@link Loaded#errors()} do. When the reader has errors, it throws {@link ConfigLoadException} with the
     * error lines joined by a space, which {@link YamlFolder} reports as the one line of that file.
     *
     * @param <T> the entry
     */
    @FunctionalInterface
    public interface Parser<T> {

        /**
         * The entry the file describes, never {@code null}.
         *
         * @param id the file name without its {@code .yml} suffix
         * @param fileName the name of the file, as the error lines of {@link Loaded#errors()} write it
         * @param yaml the parsed file
         * @throws ConfigLoadException when the file does not describe a usable entry. The message is shown to
         *     the owner after the file name.
         */
        T parse(String id, String fileName, YamlConfiguration yaml) throws ConfigLoadException;
    }

    /**
     * The entries that loaded and a line for each file that did not. Both are unmodifiable copies.
     *
     * @param entries the entries by id, in the order of their file names
     * @param errors one line for each file that failed, in the order of the file names
     * @param <T> the entry
     */
    public record Loaded<T>(Map<String, T> entries, List<String> errors) {

        /** Copies {@code entries}, keeping its iteration order, and {@code errors}. */
        public Loaded {
            entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
            errors = List.copyOf(errors);
        }
    }

    private final Path folder;
    private final @Nullable BiConsumer<String, Throwable> failures;
    private final @Nullable Consumer<String> lenientWarnings;

    /**
     * A reader of the files directly inside {@code folder}.
     *
     * @throws IllegalStateException when {@link Relocation#check()} fails, because the plugin's jar holds Taffy
     *     in its original package
     */
    public YamlFolder(Path folder) {
        this(folder, null, null);
    }

    /**
     * A reader of the files directly inside {@code folder} that hands over each failure the plugin did not
     * raise itself.
     *
     * @param failures receives the name of the file and the exception whenever the read or the parser throws a
     *     {@link RuntimeException} other than through {@link ConfigLoadException}, such as a
     *     {@link NullPointerException} from a bug in the parser, so the plugin can log it with its stack
     *     trace. The file still gets its line in {@link Loaded#errors()} and the other files still load. An
     *     exception the consumer throws leaves {@link #load} or {@link #loadOne}.
     * @throws IllegalStateException when {@link Relocation#check()} fails, because the plugin's jar holds Taffy
     *     in its original package
     */
    public YamlFolder(Path folder, BiConsumer<String, Throwable> failures) {
        this(folder, Objects.requireNonNull(failures, "failures"), null);
    }

    private YamlFolder(
        Path folder,
        @Nullable BiConsumer<String, Throwable> failures,
        @Nullable Consumer<String> lenientWarnings) {
        Relocation.check();
        this.folder = folder;
        this.failures = failures;
        this.lenientWarnings = lenientWarnings;
    }

    /**
     * A copy of this reader that reads each file with {@link StrictYaml#readLenient}: a byte sequence that is
     * not UTF-8 is read as the replacement character U+FFFD and the file still loads, where this reader
     * reports such a file as an error. This reader is not changed.
     *
     * @param warnings receives the line of {@link StrictYaml#readLenient} for each file that held such bytes,
     *     which names the file and the first line with a replaced character
     */
    public YamlFolder lenient(Consumer<String> warnings) {
        return new YamlFolder(folder, failures, Objects.requireNonNull(warnings, "warnings"));
    }

    /**
     * Reads every file of the folder.
     *
     * <p>Files are read in the order of their names as {@link String#compareTo} sorts them. When two names give
     * the same id, as {@code kit.yml} and {@code kit.YML} can on a case-sensitive file system, the entry read
     * later replaces the earlier one.
     *
     * <p>A file that fails adds one line to {@link Loaded#errors()}:
     * <ul>
     *   <li>When it cannot be read or parsed, the line is the message of {@link StrictYaml#read}, which names
     *       the file.</li>
     *   <li>When the parser throws {@link ConfigLoadException}, the line is the file name, {@code ": "}, then
     *       the message.</li>
     *   <li>When the read or the parser throws any other {@link RuntimeException}, the line is the file name,
     *       {@code ": "}, the simple class name of the exception, a space, then its message. A parser that
     *       returns {@code null} counts as one that throws {@link NullPointerException}. The exception is also
     *       passed to the consumer given to {@link #YamlFolder(Path, BiConsumer)}.</li>
     *   <li>A regular file whose name ends in {@code .yaml} in any letter case, has at least one character
     *       before that suffix and does not start with a dot is not read. Its line is the file name, then
     *       {@code ": rename it to <name>.yml to have it read"}, with the name it would have.</li>
     * </ul>
     *
     * @return the loaded entries and the error lines; both empty when the folder does not exist or is not a
     *     directory
     * @throws ConfigLoadException when the folder cannot be listed. No file has been read then. The message
     *     is {@code "cannot read the folder "}, the name of the folder, {@code ": "}, then the simple class name
     *     of the I/O failure, followed by a space and its reason when it states one. The path of the folder is
     *     not in the message, and the I/O failure is the cause.
     */
    public <T> Loaded<T> load(Parser<T> parser) throws ConfigLoadException {
        Map<String, T> entries = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return new Loaded<>(entries, errors);
        }
        for (Path file : list()) {
            String name = name(file);
            if (hasSuffix(name, LONG_SUFFIX)) {
                errors.add(name + ": rename it to " + name.substring(0, name.length() - LONG_SUFFIX.length())
                    + SUFFIX + " to have it read");
                continue;
            }
            read(file, name, name.substring(0, name.length() - SUFFIX.length()), parser, entries, errors);
        }
        return new Loaded<>(entries, errors);
    }

    /**
     * Reads the one file {@code <id>.yml} of the folder, with the suffix in lower case. It fails as a file of
     * {@link #load} does.
     *
     * <p>The entry is keyed by {@code id} as given. On a file system that ignores letter case, such as those of
     * Windows and macOS, {@code id} may match a file whose name has another letter case, and the key then
     * differs from the id {@link #load} gives the same file.
     *
     * @return the entry or the error line, or {@code null} when {@code id} is empty or starts with a dot, when
     *     it is not a plain file name (it names another folder, or holds a character the file system does not
     *     allow), or when the folder holds no regular file by that name
     */
    public <T> @Nullable Loaded<T> loadOne(String id, Parser<T> parser) {
        if (id.isEmpty() || id.startsWith(".")) {
            return null;
        }
        String name = id + SUFFIX;
        Path file;
        try {
            file = folder.resolve(name);
        } catch (InvalidPathException notAName) {
            return null;
        }
        if (!name.equals(String.valueOf(file.getFileName())) || !Files.isRegularFile(file)) {
            return null;
        }
        Map<String, T> entries = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        read(file, name, id, parser, entries, errors);
        return new Loaded<>(entries, errors);
    }

    private List<Path> list() throws ConfigLoadException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> directory = Files.newDirectoryStream(folder)) {
            for (Path file : directory) {
                String name = name(file);
                if (name.startsWith(".")) {
                    continue;
                }
                if (hasSuffix(name, SUFFIX) || (hasSuffix(name, LONG_SUFFIX) && Files.isRegularFile(file))) {
                    files.add(file);
                }
            }
        } catch (IOException | DirectoryIteratorException failure) {
            // A failure while iterating arrives unchecked, with the I/O error as its cause.
            Throwable reason = failure instanceof DirectoryIteratorException ? failure.getCause() : failure;
            Path named = folder.getFileName() == null ? folder : folder.getFileName();
            throw new ConfigLoadException("cannot read the folder " + named + ": " + StrictYaml.describe(reason),
                reason);
        }
        files.sort(Comparator.comparing(YamlFolder::name));
        return files;
    }

    private static boolean hasSuffix(String name, String suffix) {
        return name.length() > suffix.length() && name.toLowerCase(Locale.ROOT).endsWith(suffix);
    }

    private <T> void read(Path file, String name, String id, Parser<T> parser, Map<String, T> entries,
                          List<String> errors) {
        try {
            YamlConfiguration yaml;
            try {
                yaml = lenientWarnings == null
                    ? StrictYaml.read(file, name)
                    : StrictYaml.readLenient(file, name, lenientWarnings);
            } catch (ConfigLoadException unreadable) {
                errors.add(unreadable.getMessage());
                return;
            }
            entries.put(id, Objects.requireNonNull(parser.parse(id, name, yaml), "the parser returned null"));
        } catch (ConfigLoadException failure) {
            errors.add(name + ": " + failure.getMessage());
        } catch (RuntimeException failure) {
            errors.add(name + ": " + failure.getClass().getSimpleName() + " " + failure.getMessage());
            if (failures != null) {
                failures.accept(name, failure);
            }
        }
    }

    private static String name(Path file) {
        return String.valueOf(file.getFileName());
    }
}
