package so.alaz.taffy.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Copies a SQLite database into the given folder before a migration changes it, as
 * {@code data-<version>-<UTC yyyyMMdd-HHmmss>.db}, where every character of the version other than an ASCII letter,
 * a digit, a dot, an underscore or a hyphen is written as an underscore, and keeps the newest {@code keep} copies
 * of that pattern by modification time, the new copy among them. Files of another name in the folder are left
 * alone. With {@code keep} 0 it takes no copy.
 *
 * <p>{@link #manual} takes the copy a plugin asks for, under a pattern of its own.
 */
final class SqliteBackup implements Migrator.Backup {

    private static final DateTimeFormatter BACKUP_STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private static final String MIGRATION_COPIES = "data-.+-\\d{8}-\\d{6}\\.db";

    private static final String PARTIAL = ".tmp";

    private final Path folder;
    private final String version;
    private final int keep;
    private final Clock clock;
    private final Logger logger;

    SqliteBackup(Path folder, String version, int keep, Clock clock, Logger logger) {
        this.folder = folder;
        this.version = version;
        this.keep = keep;
        this.clock = clock;
        this.logger = logger;
    }

    @Override
    public void run(Connection connection) throws SQLException, StorageException {
        if (keep == 0) {
            return;
        }
        Path target = folder.resolve("data-" + version.replaceAll("[^A-Za-z0-9._-]", "_") + "-"
            + BACKUP_STAMP.format(clock.instant()) + ".db");
        try {
            Files.createDirectories(folder);
            Files.deleteIfExists(target);
        } catch (IOException failure) {
            throw new StorageException("cannot prepare " + target + " for the database backup", failure);
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("VACUUM INTO '" + target.toString().replace("'", "''") + "'");
        }
        logger.info("Copied the database to " + folder.getFileName() + "/" + target.getFileName()
            + " before upgrading its layout.");
        prune(folder, target, MIGRATION_COPIES, false, keep, logger);
    }

    /**
     * Copies the database of {@code connection}, which must be in autocommit mode, into {@code folder} as
     * {@code manual-<label>-<UTC yyyyMMdd-HHmmss>.db} and keeps the newest {@code keep} files of that label by
     * modification time, the new copy among them. Copies under another label are left alone. The copy is written under the name of the
     * file followed by {@code .tmp} and moved into place in one step, so a file of the pattern is always a complete
     * database. A file that already has the name is replaced. Files of the pattern followed by {@code .tmp} that an
     * interrupted copy of that label left behind are removed.
     *
     * @return the file of the copy
     */
    static Path manual(Connection connection, Path folder, String label, int keep, Clock clock, Logger logger)
        throws SQLException, StorageException {
        Path target = folder.resolve("manual-" + label + "-" + BACKUP_STAMP.format(clock.instant()) + ".db");
        Path partial = folder.resolve(target.getFileName() + PARTIAL);
        try {
            Files.createDirectories(folder);
            Files.deleteIfExists(partial);
        } catch (IOException failure) {
            throw new StorageException("cannot prepare " + partial + " for the database backup", failure);
        }
        try (PreparedStatement statement = connection.prepareStatement("VACUUM INTO ?")) {
            statement.setString(1, partial.toString());
            statement.execute();
        } catch (SQLException | RuntimeException failure) {
            discard(partial, failure);
            throw failure;
        }
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            discard(partial, failure);
            throw new StorageException("cannot move the database backup to " + target, failure);
        }
        logger.info("Copied the database to " + folder.getFileName() + "/" + target.getFileName() + ".");
        String copies = "manual-" + Pattern.quote(label) + "-\\d{8}-\\d{6}\\.db";
        prune(folder, target, copies, true, keep, logger);
        return target;
    }

    private static void discard(Path partial, Throwable failure) {
        try {
            Files.deleteIfExists(partial);
        } catch (IOException undeleted) {
            failure.addSuppressed(undeleted);
        }
    }

    // Keeps the newest keep files of the pattern, the target among them. With partials, it also removes the files
    // of the pattern followed by .tmp: one copy is written at a time, so each is what an interrupted copy left.
    private static void prune(Path folder, Path target, String pattern, boolean partials, int keep, Logger logger) {
        try (Stream<Path> files = Files.list(folder)) {
            List<Path> others = files.filter(path -> !path.equals(target)).toList();
            List<Path> backups = others.stream()
                .filter(path -> path.getFileName().toString().matches(pattern))
                .sorted(Comparator.comparing(SqliteBackup::modified).reversed())
                .toList();
            for (Path old : backups.subList(Math.min(keep - 1, backups.size()), backups.size())) {
                Files.deleteIfExists(old);
            }
            for (Path other : others) {
                String name = other.getFileName().toString();
                if (partials && name.endsWith(PARTIAL)
                    && name.substring(0, name.length() - PARTIAL.length()).matches(pattern)) {
                    Files.deleteIfExists(other);
                }
            }
        } catch (IOException | RuntimeException failure) {
            logger.warning("Could not remove old database backups in " + folder + ": " + failure);
        }
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException missing) {
            return 0L;
        }
    }
}
