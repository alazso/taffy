package so.alaz.taffy.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

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

/**
 * Reads a folder of YAML files in which each file is one entry, such as one menu or one kit per file.
 *
 * <p>A file counts when its name ends in {@code .yml} in any letter case and has at least one character before
 * that suffix. Its id is the name without those four characters, in the letter case the file has. Files in
 * subfolders are not read, and {@link #load} reports a subfolder whose name ends in {@code .yml} as a file that
 * cannot be read. Each file is read with {@link StrictYaml#read} and handed to a {@link Parser}. A
 * file that fails is reported in {@link Loaded#errors()} and the other files still load.
 *
 * <p>The folder is never written to and nothing is kept between calls, so one instance may be used from any
 * thread. The reads block the calling thread.
 */
public final class YamlFolder {

    private static final String SUFFIX = ".yml";

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

    /** A reader of the files directly inside {@code folder}. */
    public YamlFolder(Path folder) {
        this.folder = folder;
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
     *       returns {@code null} counts as one that throws {@link NullPointerException}.</li>
     * </ul>
     *
     * @return the loaded entries and the error lines; both empty when the folder does not exist or is not a
     *     directory
     * @throws ConfigLoadException when the folder cannot be listed. No file has been read then. The message
     *     is {@code "cannot read the folder "}, the path of the folder, {@code ": "}, then the simple class name
     *     and the message of the I/O failure.
     */
    public <T> Loaded<T> load(Parser<T> parser) throws ConfigLoadException {
        Map<String, T> entries = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return new Loaded<>(entries, errors);
        }
        for (Path file : list()) {
            String name = name(file);
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
     * @return the entry or the error line, or {@code null} when {@code id} is empty, {@code .} or {@code ..},
     *     when it is not a plain file name (it names another folder, or holds a character the file system does
     *     not allow), or when the folder holds no regular file by that name
     */
    public <T> @Nullable Loaded<T> loadOne(String id, Parser<T> parser) {
        if (id.isEmpty() || id.equals(".") || id.equals("..")) {
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
                if (name.length() > SUFFIX.length() && name.toLowerCase(Locale.ROOT).endsWith(SUFFIX)) {
                    files.add(file);
                }
            }
        } catch (IOException | DirectoryIteratorException failure) {
            // A failure while iterating arrives unchecked, with the I/O error as its cause.
            Throwable reason = failure instanceof DirectoryIteratorException ? failure.getCause() : failure;
            throw new ConfigLoadException("cannot read the folder " + folder + ": "
                + reason.getClass().getSimpleName() + " " + reason.getMessage(), reason);
        }
        files.sort(Comparator.comparing(YamlFolder::name));
        return files;
    }

    private static <T> void read(Path file, String name, String id, Parser<T> parser, Map<String, T> entries,
                                 List<String> errors) {
        try {
            YamlConfiguration yaml;
            try {
                yaml = StrictYaml.read(file, name);
            } catch (ConfigLoadException unreadable) {
                errors.add(unreadable.getMessage());
                return;
            }
            entries.put(id, Objects.requireNonNull(parser.parse(id, name, yaml), "the parser returned null"));
        } catch (ConfigLoadException failure) {
            errors.add(name + ": " + failure.getMessage());
        } catch (RuntimeException failure) {
            errors.add(name + ": " + failure.getClass().getSimpleName() + " " + failure.getMessage());
        }
    }

    private static String name(Path file) {
        return String.valueOf(file.getFileName());
    }
}
