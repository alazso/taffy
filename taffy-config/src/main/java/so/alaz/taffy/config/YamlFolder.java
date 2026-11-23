package so.alaz.taffy.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.Relocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads a folder of YAML files in which each file is one entry, such as one menu or one kit per file.
 *
 * <p>A file counts when its name ends in {@code .yml} in any letter case, has at least one character before
 * that suffix and does not start with a dot. Its id is the name without those four characters, in the letter
 * case the file has. A name that starts with a dot is skipped without a line, as the hidden files and the
 * {@code ._<name>.yml} files macOS writes beside a file on a foreign file system are. A regular file whose name
 * ends in {@code .yaml} is not read: {@link #load} reports it with a line that tells the owner to rename it.
 * Unless the reader was built to read subfolders, files in subfolders are not read, and {@link #load} reports a
 * subfolder whose name ends in {@code .yml} as a file that cannot be read. Each file is read with
 * {@link StrictYaml#read}, or with {@link StrictYaml#readLenient} after {@link #lenient}, and handed to a
 * {@link Parser}. A file that fails is reported in {@link Loaded#errors()} and the other files still load.
 * {@link #loadReport} reads the folder the same way and also gives each failure, and each problem the parser
 * hands over for a file that loaded, as a {@link ConfigProblem}. The line of {@link StrictYaml#readLenient} is
 * not among them: it reaches only the consumer given to {@link #lenient}.
 *
 * <p>A reader built with {@link #YamlFolder(Path)} or {@link #YamlFolder(Path, BiConsumer)} never writes to the
 * folder. Nothing is kept between calls, so one instance may be used from any thread. The reads block the
 * calling thread, and the consumers given to the constructor and to {@link #lenient} are called on it.
 *
 * <h2>Files with a version</h2>
 *
 * <p>A reader built with {@link #YamlFolder(Path, Logger, String, int, ConfigUpgrade, boolean)} reads a layout
 * version from each file, under the key the plugin names, as {@link ConfigLoader} reads {@code config-version}:
 * a number, or text holding a whole number of at most nine digits, and 0 for a file without a usable one.
 *
 * <ul>
 *   <li>A file of the current version is read as it is and never written.</li>
 *   <li>An older file is upgraded before the parser sees it: the steps of the {@link ConfigUpgrade} registered
 *       under its version up to, not including, the current one run on the parsed file, and the version key is
 *       set to the current version, after the keys the file has when it had none. The previous file is then
 *       written, byte for byte, to {@code <file name>.v<N>.bak} beside it (N is the old version, and a copy of
 *       that name is replaced) and the upgraded file is saved, both as {@link AtomicWriter} writes a file. The
 *       save rewrites the file as {@link ConfigLoader} describes for {@code config.yml}. The logger receives
 *       {@code Upgraded <name> from version 1 to 2; the previous file is kept as <file name>.v1.bak.}</li>
 *   <li>When that save fails, the file stays as it was, byte for byte, the parser still gets the upgraded file,
 *       and the next load tries again. The logger receives a warning that starts with
 *       {@code Could not save the upgraded <name>: } and states the reason. A file is not saved when its text
 *       would not be read back as it is, when it changed on the disk since it was read, when it holds bytes
 *       that are not UTF-8 and was read through {@link #lenient}, or when the version key is a dotted path
 *       beneath a key of the file that holds a value, which stays as the owner wrote it.</li>
 *   <li>When a step throws, the file is not changed, the parser does not see it and the file adds the line
 *       {@code <name>: the step that upgrades it from version 1 failed: }, then the simple class name of what
 *       the step threw and its message. The logger receives a warning with what the step threw, so its stack
 *       trace is in the log.</li>
 *   <li>A file newer than the current version is never handed to the parser, upgraded or written. It adds the
 *       line
 *       {@code <name>: <version key> is 3, newer than the version 2 this build knows, so the file is not read}.
 *       </li>
 * </ul>
 *
 * <p>Such a reader writes to the folder only inside {@link #load}, {@link #loadReport} and {@link #loadOne},
 * on the calling thread. Two loads of one folder that overlap both load every entry, and the one that comes
 * second to a file logs that it could not save it.
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

    /**
     * Turns one file into an entry and the problems found in it, for {@link #loadReport}.
     *
     * <p>A parser that reads the file through a {@link ConfigReader} builds it with
     * {@link ConfigReader#ConfigReader(org.bukkit.configuration.ConfigurationSection, String, Consumer)} and
     * {@code fileName} as the source, so every problem names its file. When the reader has errors, it throws
     * {@link ConfigLoadException#withProblems(String, List)} with the error lines joined by a space and
     * {@link ConfigReader#problems()}. Otherwise it returns the entry with {@link ConfigReader#problems()},
     * which then holds the warnings of the file.
     *
     * @param <T> the entry
     */
    @FunctionalInterface
    public interface ReportingParser<T> {

        /**
         * The entry the file describes and the problems of a file that loads, never {@code null}.
         *
         * @param id the file name without its {@code .yml} suffix
         * @param fileName the name of the file, as the error lines of {@link Report#errors()} write it
         * @param yaml the parsed file
         * @throws ConfigLoadException when the file does not describe a usable entry. The message is shown to
         *     the owner after the file name, and {@link ConfigLoadException#problems()} are added to
         *     {@link Report#problems()}.
         */
        Parsed<T> parse(String id, String fileName, YamlConfiguration yaml) throws ConfigLoadException;
    }

    /**
     * What a {@link ReportingParser} made of one file that loads.
     *
     * @param entry the entry, never {@code null}
     * @param problems the problems of the file, such as the warnings of its {@link ConfigReader}, copied in
     *     their order
     * @param <T> the entry
     */
    public record Parsed<T>(T entry, List<ConfigProblem> problems) {

        /** Copies {@code problems}. */
        public Parsed {
            problems = List.copyOf(problems);
        }
    }

    /**
     * The entries that loaded, a line for each file that did not and the problems of the load. All three are
     * unmodifiable copies.
     *
     * @param entries the entries by id, in the order of their file names
     * @param errors one line for each file that failed, in the order of the file names, as
     *     {@link Loaded#errors()} holds them
     * @param problems the problems of every file, in the order of the file names. A file that failed gives one
     *     {@link ConfigProblem.Severity#ERROR} record with the name of the file, no path and its line of
     *     {@code errors} as the message, followed by the problems of the {@link ConfigLoadException} its parser
     *     threw. The record of a file whose name ends in {@code .yaml} has the fix
     *     {@code rename it to <name>.yml}, and the others have none. A file that loaded gives the problems of
     *     its {@link Parsed}. When two files give the same id and the later entry replaces the earlier one, the
     *     problems of both files are kept, each under the name of its file.
     * @param <T> the entry
     */
    public record Report<T>(Map<String, T> entries, List<String> errors, List<ConfigProblem> problems) {

        /** Copies {@code entries}, keeping its iteration order, {@code errors} and {@code problems}. */
        public Report {
            entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
            errors = List.copyOf(errors);
            problems = List.copyOf(problems);
        }
    }

    private final Path folder;
    private final @Nullable BiConsumer<String, Throwable> failures;
    private final @Nullable Consumer<String> lenientWarnings;
    private final @Nullable Versions versions;
    private final boolean recursive;

    /** How a reader treats the layout version of a file. */
    private record Versions(Logger logger, String key, int current, ConfigUpgrade upgrades) {
    }

    /**
     * A reader of the files directly inside {@code folder}.
     *
     * @throws IllegalStateException when {@link Relocation#check()} fails, because the plugin's jar holds Taffy
     *     in its original package
     */
    public YamlFolder(Path folder) {
        this(folder, null, null, null, false);
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
        this(folder, Objects.requireNonNull(failures, "failures"), null, null, false);
    }

    /**
     * A reader of files that carry a layout version, which upgrades an older file and does not read a newer
     * one, as the class description states.
     *
     * @param logger receives one line for each file that was upgraded, for each upgrade that could not be saved
     *     and for each step that threw, on the thread that loads
     * @param versionKey the key that holds the version of a file, as a full dotted path, such as
     *     {@code crate-version}
     * @param currentVersion the version of the layout the plugin reads, 1 or more
     * @param upgrades the steps, each registered under the version it upgrades from; steps under
     *     {@code currentVersion} or above never run
     * @param recursive whether the files in subfolders are read too. The name of such a file, in the lines and
     *     for the parser, is its path from {@code folder} with {@code /} between the parts, such as
     *     {@code rare/gold.yml}, and its id is that name without the suffix, {@code rare/gold}. The files are
     *     read in the order of those names. A folder whose name starts with a dot is skipped with everything
     *     in it, a folder reached through a symbolic link is not entered, and a folder whose name ends in
     *     {@code .yml} is entered like any other. With {@code false} only the files directly inside
     *     {@code folder} are read.
     * @throws IllegalArgumentException when {@code versionKey} is blank or {@code currentVersion} is below 1
     * @throws IllegalStateException when {@link Relocation#check()} fails, because the plugin's jar holds Taffy
     *     in its original package
     */
    public YamlFolder(Path folder, Logger logger, String versionKey, int currentVersion, ConfigUpgrade upgrades,
                      boolean recursive) {
        this(folder, null, null, versions(logger, versionKey, currentVersion, upgrades), recursive);
    }

    private YamlFolder(
        Path folder,
        @Nullable BiConsumer<String, Throwable> failures,
        @Nullable Consumer<String> lenientWarnings,
        @Nullable Versions versions,
        boolean recursive) {
        Relocation.check();
        this.folder = folder;
        this.failures = failures;
        this.lenientWarnings = lenientWarnings;
        this.versions = versions;
        this.recursive = recursive;
    }

    private static Versions versions(Logger logger, String versionKey, int currentVersion, ConfigUpgrade upgrades) {
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(upgrades, "upgrades");
        if (versionKey.isBlank()) {
            throw new IllegalArgumentException("the version key is blank");
        }
        if (currentVersion < 1) {
            throw new IllegalArgumentException("the current version is " + currentVersion + "; expected 1 or more");
        }
        return new Versions(logger, versionKey, currentVersion, upgrades);
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
        return new YamlFolder(folder, failures, Objects.requireNonNull(warnings, "warnings"), versions, recursive);
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
        Report<T> report = loadReport(reporting(parser));
        return new Loaded<>(report.entries(), report.errors());
    }

    /**
     * Reads every file of the folder as {@link #load} does, and gives the problems of the load as records
     * beside the same entries and error lines. A file that loads with warnings has its entry in
     * {@link Report#entries()} and its warnings in {@link Report#problems()}, and adds no line to
     * {@link Report#errors()}.
     *
     * @return the loaded entries, the error lines and the problems; all empty when the folder does not exist or
     *     is not a directory
     * @throws ConfigLoadException when the folder cannot be listed, as {@link #load} throws it
     */
    public <T> Report<T> loadReport(ReportingParser<T> parser) throws ConfigLoadException {
        Map<String, T> entries = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        List<ConfigProblem> problems = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return new Report<>(entries, errors, problems);
        }
        for (Listed listed : list()) {
            String name = listed.name();
            if (hasSuffix(name, LONG_SUFFIX)) {
                String fix = "rename it to " + name.substring(0, name.length() - LONG_SUFFIX.length()) + SUFFIX;
                fail(name, name + ": " + fix + " to have it read", fix, errors, problems);
                continue;
            }
            read(listed.file(), name, name.substring(0, name.length() - SUFFIX.length()), parser, entries, errors,
                problems);
        }
        return new Report<>(entries, errors, problems);
    }

    /**
     * Reads the one file {@code <id>.yml} of the folder, with the suffix in lower case. It fails as a file of
     * {@link #load} does.
     *
     * <p>The entry is keyed by {@code id} as given. On a file system that ignores letter case, such as those of
     * Windows and macOS, {@code id} may match a file whose name has another letter case, and the key then
     * differs from the id {@link #load} gives the same file.
     *
     * <p>A reader that reads subfolders takes the id {@link #load} gives a file there, such as {@code rare/gold}.
     * A reader of files with a version upgrades the file as {@link #load} does.
     *
     * @return the entry or the error line, or {@code null} when {@code id} is empty or starts with a dot, when
     *     it is not a plain file name (it names another folder, or holds a character the file system does not
     *     allow), or when the folder holds no regular file by that name. For a reader that reads subfolders,
     *     {@code null} also when a part of {@code id} between two {@code /} is empty, starts with a dot or is
     *     not a plain name.
     */
    public <T> @Nullable Loaded<T> loadOne(String id, Parser<T> parser) {
        String name = id + SUFFIX;
        Path file = folder;
        for (String part : recursive ? name.split("/", -1) : new String[] {name}) {
            if (part.isEmpty() || part.startsWith(".")
                || (file != folder && !Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS))) {
                return null;
            }
            try {
                file = file.resolve(part);
            } catch (InvalidPathException notAName) {
                return null;
            }
            if (!part.equals(String.valueOf(file.getFileName()))) {
                return null;
            }
        }
        if (id.isEmpty() || !Files.isRegularFile(file)) {
            return null;
        }
        Map<String, T> entries = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        read(file, name, id, reporting(parser), entries, errors, new ArrayList<>());
        return new Loaded<>(entries, errors);
    }

    private static <T> ReportingParser<T> reporting(Parser<T> parser) {
        return (id, name, yaml) ->
            new Parsed<>(Objects.requireNonNull(parser.parse(id, name, yaml), "the parser returned null"), List.of());
    }

    /** A file of the folder with the name it has in the lines: its path from the folder, with {@code /}. */
    private record Listed(Path file, String name) {
    }

    private List<Listed> list() throws ConfigLoadException {
        List<Listed> files = new ArrayList<>();
        list(folder, "", files);
        files.sort(Comparator.comparing(Listed::name));
        return files;
    }

    private void list(Path directory, String prefix, List<Listed> files) throws ConfigLoadException {
        List<Path> subfolders = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path file : entries) {
                String name = name(file);
                if (name.startsWith(".")) {
                    continue;
                }
                if (recursive && Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                    subfolders.add(file);
                } else if (hasSuffix(name, SUFFIX) || (hasSuffix(name, LONG_SUFFIX) && Files.isRegularFile(file))) {
                    files.add(new Listed(file, prefix + name));
                }
            }
        } catch (IOException | DirectoryIteratorException failure) {
            // A failure while iterating arrives unchecked, with the I/O error as its cause.
            Throwable reason = failure instanceof DirectoryIteratorException ? failure.getCause() : failure;
            Path named = directory.getFileName() == null ? directory : directory.getFileName();
            throw new ConfigLoadException("cannot read the folder " + named + ": " + StrictYaml.describe(reason),
                reason);
        }
        for (Path subfolder : subfolders) {
            list(subfolder, prefix + name(subfolder) + "/", files);
        }
    }

    private static boolean hasSuffix(String name, String suffix) {
        return name.length() > suffix.length() && name.toLowerCase(Locale.ROOT).endsWith(suffix);
    }

    private <T> void read(Path file, String name, String id, ReportingParser<T> parser, Map<String, T> entries,
                          List<String> errors, List<ConfigProblem> problems) {
        try {
            YamlConfiguration yaml;
            try {
                byte[] content = StrictYaml.bytes(file, name);
                yaml = lenientWarnings == null
                    ? StrictYaml.parse(StrictYaml.decode(content, name), name)
                    : StrictYaml.parseLenient(content, name, lenientWarnings);
                if (versions != null && !upgraded(file, name, content, yaml, versions, errors, problems)) {
                    return;
                }
            } catch (ConfigLoadException unreadable) {
                fail(name, unreadable.getMessage(), null, errors, problems);
                return;
            }
            Parsed<T> parsed = Objects.requireNonNull(parser.parse(id, name, yaml), "the parser returned null");
            entries.put(id, Objects.requireNonNull(parsed.entry(), "the parser returned null"));
            problems.addAll(parsed.problems());
        } catch (ConfigLoadException failure) {
            fail(name, name + ": " + failure.getMessage(), null, errors, problems);
            problems.addAll(failure.problems());
        } catch (RuntimeException failure) {
            fail(name, name + ": " + failure.getClass().getSimpleName() + " " + failure.getMessage(), null, errors,
                problems);
            if (failures != null) {
                failures.accept(name, failure);
            }
        }
    }

    /**
     * Brings {@code yaml} to the current version and saves it, or adds the line of a file that is not read.
     *
     * @return whether the parser gets {@code yaml}
     */
    private boolean upgraded(Path file, String name, byte[] content, YamlConfiguration yaml, Versions versions,
                             List<String> errors, List<ConfigProblem> problems) {
        int version = ConfigLoader.version(yaml.get(versions.key()), 0);
        if (version > versions.current()) {
            fail(name, name + ": " + versions.key() + " is " + version + ", newer than the version "
                + versions.current() + " this build knows, so the file is not read", null, errors, problems);
            return false;
        }
        if (version == versions.current()) {
            return true;
        }
        try {
            versions.upgrades().run(yaml, version, versions.current());
        } catch (ConfigUpgrade.Failure failure) {
            fail(name, name + ": the step that upgrades it from version " + failure.version() + " failed: "
                + failure.reason(), null, errors, problems);
            versions.logger().log(Level.WARNING, "The step that upgrades " + name + " from version "
                + failure.version() + " failed, so the file is not read.", failure.getCause());
            return false;
        }
        Path backup = file.resolveSibling(file.getFileName() + ".v" + version + ".bak");
        try {
            if (ConfigLoader.underScalar(yaml, versions.key())) {
                throw new ConfigLoadException(versions.key() + " lies beneath a key that holds a value, so the"
                    + " version cannot be written");
            }
            yaml.set(versions.key(), versions.current());
            if (StrictYaml.malformedLine(content) != 0) {
                throw new ConfigLoadException("it is not saved as UTF-8");
            }
            byte[] text = ConfigLoader.upgradedText(yaml, name).getBytes(StandardCharsets.UTF_8);
            try {
                if (!AtomicWriter.replaceKeeping(file, content, backup, text)) {
                    throw new ConfigLoadException("the file changed while it was read");
                }
            } catch (IOException failure) {
                throw new ConfigLoadException("cannot write it: " + StrictYaml.describe(failure), failure);
            }
            versions.logger().info("Upgraded " + name + " from version " + version + " to " + versions.current()
                + "; the previous file is kept as " + backup.getFileName() + ".");
        } catch (ConfigLoadException failure) {
            versions.logger().warning("Could not save the upgraded " + name + ": " + failure.getMessage()
                + ". The upgraded entry is used and the save is tried again the next time the file is loaded.");
        }
        return true;
    }

    private static void fail(String name, String line, @Nullable String fix, List<String> errors,
                             List<ConfigProblem> problems) {
        errors.add(line);
        problems.add(new ConfigProblem(name, null, ConfigProblem.Severity.ERROR, line, fix));
    }

    private static String name(Path file) {
        return String.valueOf(file.getFileName());
    }
}
