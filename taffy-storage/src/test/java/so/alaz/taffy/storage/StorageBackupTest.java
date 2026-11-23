package so.alaz.taffy.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import so.alaz.taffy.platform.ErrorReports;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

final class StorageBackupTest {

    private static final String NAME = "backup-probe";

    private static final String COPY = "manual-nightly-20261102-101530.db";

    @TempDir
    private Path folder;

    private final CapturedLog log = new CapturedLog();
    private Storage storage;
    private boolean closed;

    @BeforeEach
    void start() {
        storage = Storage.start(TestPlugins.plugin(NAME, folder, log.logger), new ErrorReports(),
            StorageSettings.sqlite("data.db"), List.of(), StorageContract.CLOCK).join();
        storage.async(tx -> {
            tx.execute("CREATE TABLE notes (id INTEGER PRIMARY KEY)");
            return null;
        }).join();
    }

    @AfterEach
    void close() {
        if (!closed) {
            assertThat(storage.close(Duration.ofSeconds(5))).isTrue();
        }
    }

    @Test
    void aBackupHoldsEveryWriteQueuedBeforeItAndNoneQueuedAfterIt() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<CompletableFuture<Integer>> writes = new ArrayList<>();
        writes.add(storage.async(tx -> {
            await(release);
            return tx.update("INSERT INTO notes VALUES (1)", Tx.Binder.NONE);
        }));
        writes.add(insert(2));
        CompletableFuture<Path> backup = storage.backup("nightly", 1);
        CompletableFuture<String> completedOn = backup.thenApply(done -> Thread.currentThread().getName());
        writes.add(insert(3));
        CompletableFuture<String> reader = storage.read(tx -> Thread.currentThread().getName());

        assertThat(reader.get(5, TimeUnit.SECONDS)).startsWith(NAME + "-db-");
        assertThat(backup).isNotDone();
        release.countDown();
        Path copy = backup.get(10, TimeUnit.SECONDS);
        CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).join();

        assertThat(copy).isEqualTo(folder.resolve("backups").resolve(COPY));
        assertThat(ids(copy)).containsExactly(1, 2);
        assertThat(ids(folder.resolve("data.db"))).containsExactly(1, 2, 3);
        assertThat(names()).containsExactly(COPY);
        assertThat(completedOn.join()).startsWith(NAME + "-db-").doesNotContain("writer");
        assertThat(log.lines()).containsExactly("INFO Copied the database to backups/" + COPY + ".");
    }

    @Test
    void aBackupKeepsTheNewestManualCopiesAndLeavesEveryOtherFile() throws Exception {
        Path backups = Files.createDirectories(folder.resolve("backups"));
        for (int day = 1; day <= 3; day++) {
            Path old = Files.writeString(
                backups.resolve("manual-nightly-2026010" + day + "-000000.db"), "old");
            Files.setLastModifiedTime(old, FileTime.fromMillis(day * 86_400_000L));
        }
        for (String other : List.of("data-9.9.9-20260101-000000.db", "manual-notes.txt", "manual-by-hand.db",
            "manual-import-20260101-000000.db", "manual-nightly-20260102-000000-20260101-000000.db",
            "manual-import-20260101-000000.db.tmp", "manual-nightly-20260109-000000.db.tmp")) {
            Files.writeString(backups.resolve(other), "other");
        }

        storage.backup("nightly", 2).join();

        assertThat(names()).containsExactlyInAnyOrder("data-9.9.9-20260101-000000.db", "manual-by-hand.db",
            "manual-import-20260101-000000.db", "manual-import-20260101-000000.db.tmp",
            "manual-nightly-20260102-000000-20260101-000000.db", "manual-nightly-20260103-000000.db", COPY,
            "manual-notes.txt");
    }

    @Test
    void aSecondBackupInTheSameSecondReplacesTheFirst() throws Exception {
        storage.backup("nightly", 5).join();
        insert(9).join();

        Path copy = storage.backup("nightly", 5).join();

        assertThat(names()).containsExactly(COPY);
        assertThat(ids(copy)).containsExactly(9);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "two words", "a/b", "..\\up", "café",
        "a-label-that-is-one-character-longer-than-the-sixty-four-allowed0"})
    void aLabelOutsideItsRangeIsRefusedOnTheCallingThread(String label) {
        assertThatThrownBy(() -> storage.backup(label, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the backup label is \"" + label + "\"; use 1 to 64 ASCII letters, digits, dots, underscores"
                + " and hyphens");
        assertThat(folder.resolve("backups")).doesNotExist();
    }

    @Test
    void aBackupThatKeepsNoCopyAndANullLabelAreRefused() {
        assertThatThrownBy(() -> storage.backup("nightly", 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("keep is 0; a backup keeps at least 1 copy");
        assertThatThrownBy(() -> storage.backup(null, 1))
            .isInstanceOf(NullPointerException.class).hasMessage("label");
    }

    @Test
    void storageOverADataSourceHasNoFileToCopy() {
        Storage over = Storage.over(TestPlugins.plugin(NAME, log.logger), new ErrorReports(), Dialect.SQLITE,
            mock(javax.sql.DataSource.class), "", StorageContract.CLOCK);
        try {
            assertThatThrownBy(() -> over.backup("nightly", 1).join())
                .isInstanceOf(CompletionException.class)
                .cause().isInstanceOf(StorageException.class)
                .hasMessage("storage over a data source has no database file of its own to copy; back the file up"
                    + " with the tool that owns it");
        } finally {
            over.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void aBackupAskedForAfterCloseIsRejected() {
        assertThat(storage.close(Duration.ofSeconds(5))).isTrue();
        closed = true;

        assertThatThrownBy(() -> storage.backup("nightly", 1).join())
            .isInstanceOf(CompletionException.class)
            .cause().isInstanceOf(RejectedExecutionException.class);
        assertThat(folder.resolve("backups")).doesNotExist();
    }

    private CompletableFuture<Integer> insert(int id) {
        return storage.async(tx -> tx.update("INSERT INTO notes VALUES (?)", params -> params.intValue(id)));
    }

    private static void await(CountDownLatch latch) throws SQLException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new SQLException("the write was never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SQLException(interrupted);
        }
    }

    private List<String> names() throws IOException {
        try (Stream<Path> files = Files.list(folder.resolve("backups"))) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static List<Integer> ids(Path database) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT id FROM notes ORDER BY id")) {
            List<Integer> ids = new ArrayList<>();
            while (rows.next()) {
                ids.add(rows.getInt(1));
            }
            return ids;
        }
    }
}
