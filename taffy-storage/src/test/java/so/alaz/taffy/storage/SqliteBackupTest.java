package so.alaz.taffy.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import so.alaz.taffy.test.CapturedLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class SqliteBackupTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T10:15:30.123Z"), ZoneOffset.UTC);

    private static final String COPY = "data-9.9.9-20261102-101530.db";

    @TempDir
    private Path folder;

    private final CapturedLog log = new CapturedLog();
    private Connection connection;

    @BeforeEach
    void open() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("data.db"));
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE owner_table (id INTEGER)");
            statement.execute("INSERT INTO owner_table VALUES (42)");
        }
    }

    @AfterEach
    void closeConnection() throws SQLException {
        connection.close();
    }

    @Test
    void anExistingDatabaseIsCopiedBeforeAMigrationAndOldCopiesArePruned() throws Exception {
        Path backups = Files.createDirectories(folder.resolve("backups"));
        for (int day = 1; day <= 3; day++) {
            Path old = Files.writeString(backups.resolve("data-0.0." + (4 - day) + "-2026010" + day + "-000000.db"), "old");
            Files.setLastModifiedTime(old, FileTime.fromMillis(day * 86_400_000L));
        }
        Files.writeString(backups.resolve("notes.txt"), "an owner file");
        Files.writeString(backups.resolve("data-before-upgrade.db"), "an owner copy");

        backup(backups, 2).run(connection);

        assertThat(names(backups)).containsExactly("data-0.0.1-20260103-000000.db", COPY, "data-before-upgrade.db",
            "notes.txt");
        assertThat(ownerRows(backups.resolve(COPY))).isEqualTo(42);
        assertThat(log.lines()).containsExactly(
            "INFO Copied the database to backups/" + COPY + " before upgrading its layout.");
    }

    @Test
    void theFreshCopySurvivesAnOlderCopyDatedInTheFuture() throws Exception {
        Path backups = Files.createDirectories(folder.resolve("backups"));
        Path future = Files.writeString(backups.resolve("data-0.0.1-20260101-000000.db"), "old");
        Files.setLastModifiedTime(future, FileTime.fromMillis(System.currentTimeMillis() + 3_600_000L));

        backup(backups, 1).run(connection);

        assertThat(names(backups)).containsExactly(COPY);
    }

    @Test
    void aMissingFolderIsCreatedAndACopyOfTheSameSecondIsReplaced() throws Exception {
        Path backups = folder.resolve("backups");
        backup(backups, 3).run(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute("UPDATE owner_table SET id = 7");
        }

        backup(backups, 3).run(connection);

        assertThat(names(backups)).containsExactly(COPY);
        assertThat(ownerRows(backups.resolve(COPY))).isEqualTo(7);
    }

    @Test
    void aQuoteInTheFolderNameIsEscapedInTheCopyStatement() throws Exception {
        Path backups = folder.resolve("owner's backups");

        backup(backups, 1).run(connection);

        assertThat(ownerRows(backups.resolve(COPY))).isEqualTo(42);
        assertThat(log.lines()).containsExactly(
            "INFO Copied the database to owner's backups/" + COPY + " before upgrading its layout.");
    }

    @Test
    void anOldCopyThatCannotBeRemovedIsWarnedAboutAndTheNewCopyIsKept() throws Exception {
        Path backups = Files.createDirectories(folder.resolve("backups"));
        Path stuck = Files.createDirectories(backups.resolve("data-0.0.1-20200101-000000.db"));
        Files.writeString(stuck.resolve("x"), "held");
        Files.setLastModifiedTime(stuck, FileTime.fromMillis(86_400_000L));

        backup(backups, 1).run(connection);

        assertThat(ownerRows(backups.resolve(COPY))).isEqualTo(42);
        assertThat(stuck).isDirectory();
        assertThat(log.lines()).hasSize(2);
        assertThat(log.lines().get(0))
            .isEqualTo("INFO Copied the database to backups/" + COPY + " before upgrading its layout.");
        assertThat(log.lines().get(1)).startsWith("WARNING Could not remove old database backups in " + backups
            + ": java.nio.file.DirectoryNotEmptyException");
    }

    @Test
    void zeroKeepTakesNoCopy() throws Exception {
        backup(folder.resolve("backups"), 0).run(connection);

        assertThat(Files.exists(folder.resolve("backups"))).isFalse();
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aFolderPathThatIsAFileStopsTheStart() throws Exception {
        Path file = Files.writeString(folder.resolve("backups"), "not a folder");

        assertThatThrownBy(() -> backup(file, 1).run(connection))
            .isInstanceOf(StorageException.class)
            .hasMessage("cannot prepare " + file.resolve(COPY) + " for the database backup")
            .hasCauseInstanceOf(IOException.class);
        assertThat(log.lines()).isEmpty();
    }

    private SqliteBackup backup(Path backups, int keep) {
        return new SqliteBackup(backups, "9.9.9", keep, CLOCK, log.logger);
    }

    private static List<String> names(Path backups) throws IOException {
        try (Stream<Path> files = Files.list(backups)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static int ownerRows(Path copy) throws SQLException {
        try (Connection opened = DriverManager.getConnection("jdbc:sqlite:" + copy);
             Statement statement = opened.createStatement();
             ResultSet result = statement.executeQuery("SELECT id FROM owner_table")) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }
}
