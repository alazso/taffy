package so.alaz.taffy.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Copies a SQLite database into the given folder before a migration changes it, as
 * {@code data-<version>-<UTC yyyyMMdd-HHmmss>.db}, where every character of the version other than an ASCII letter,
 * a digit, a dot, an underscore or a hyphen is written as an underscore, and keeps the newest {@code keep} copies
 * of that pattern by modification time, the new copy among them. Files of another name in the folder are left
 * alone. With {@code keep} 0 it takes no copy.
 */
final class SqliteBackup implements Migrator.Backup {

    private static final DateTimeFormatter BACKUP_STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

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
        try (Stream<Path> files = Files.list(folder)) {
            List<Path> backups = files
                .filter(path -> !path.equals(target))
                .filter(path -> path.getFileName().toString().matches("data-.+-\\d{8}-\\d{6}\\.db"))
                .sorted(Comparator.comparing(SqliteBackup::modified).reversed())
                .toList();
            for (Path old : backups.subList(Math.min(keep - 1, backups.size()), backups.size())) {
                Files.deleteIfExists(old);
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
