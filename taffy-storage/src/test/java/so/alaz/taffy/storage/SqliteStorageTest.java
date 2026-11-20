package so.alaz.taffy.storage;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import so.alaz.taffy.platform.ErrorReports;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class SqliteStorageTest extends StorageContract {

    private static final String NAME = "sqlite-probe";

    @TempDir
    private Path folder;

    private Storage storage;

    @Override
    Storage storage() {
        if (storage == null) {
            storage = Storage.start(TestPlugins.plugin(NAME, folder, Logger.getAnonymousLogger()), new ErrorReports(),
                settings(), List.of(), CLOCK).join();
        }
        return storage;
    }

    @Override
    String name() {
        return NAME;
    }

    @Override
    Dialect dialect() {
        return Dialect.SQLITE;
    }

    @Override
    CompletableFuture<Storage> starting(String prefix, List<Migration> migrations, Map<String, String> scripts,
                                        @Nullable Baseline baseline) {
        return TestPlugins.start(TestPlugins.plugin(NAME, folder, Logger.getAnonymousLogger(),
            ProbeSchema.source(scripts)), settings(), migrations, baseline, CLOCK);
    }

    @Override
    Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("data.db"));
    }

    @Override
    void assertNoBackup() {
        assertThat(folder.resolve("backups")).doesNotExist();
    }

    @Override
    String createProbe(String table) {
        return "CREATE TABLE " + table + " (id BLOB NOT NULL PRIMARY KEY, state VARCHAR(16) NOT NULL, amount BIGINT,"
            + " at BIGINT, doc TEXT, data BLOB, wide TEXT)";
    }

    @AfterEach
    void close() {
        assertThat(storage.close(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void aFailureAtTheCommitStatementHasAnUnknownOutcome() {
        storage().async(tx -> {
            tx.execute("CREATE TABLE parent (id INTEGER PRIMARY KEY)");
            tx.execute("CREATE TABLE child (id INTEGER PRIMARY KEY,"
                + " parent INTEGER REFERENCES parent (id) DEFERRABLE INITIALLY DEFERRED)");
            return null;
        }).join();

        assertThatThrownBy(() -> storage().async(tx -> tx.update("INSERT INTO child VALUES (1, 99)", Tx.Binder.NONE))
            .join()).hasCauseInstanceOf(CommitUnknownException.class)
            .cause().hasMessageStartingWith("the commit failed, so whether the transaction was written is unknown");
        assertThatThrownBy(() -> storage().async(tx -> tx.update("INSERT INTO missing VALUES (1)", Tx.Binder.NONE))
            .join()).cause().isInstanceOf(SQLException.class).isNotInstanceOf(CommitUnknownException.class);
        assertThat(storage().read(tx -> tx.query("SELECT id FROM child", Tx.Binder.NONE, row -> row.integer("id")))
            .join()).isEmpty();
    }

    @Test
    void everyConnectionCarriesTheDurabilityPragmas() {
        List<Object> writer = storage().async(SqliteStorageTest::pragmas).join();
        List<Object> reader = storage().read(SqliteStorageTest::pragmas).join();

        assertThat(writer).containsExactly("wal", 2L, 5000L, 1L);
        assertThat(reader).isEqualTo(writer);
    }

    @Test
    void readsUseTheirOwnReadOnlyConnections() {
        String thread = storage().read(tx -> Thread.currentThread().getName()).join();

        assertThat(thread).startsWith(NAME + "-db-");
        assertThatThrownBy(() -> storage().read(tx -> tx.update(
            "INSERT INTO " + probe() + " (id, state) VALUES (?, ?)",
            params -> params.uuid(java.util.UUID.randomUUID()).text("A"))).join())
            .rootCause().hasMessageContaining("SQLITE_READONLY");
        assertThat(count()).isZero();
    }

    private static StorageSettings settings() {
        return new StorageSettings(Dialect.SQLITE, "data.db", null,
            new StorageSettings.Pool(4, 1, Duration.ofSeconds(5)), 2, 5);
    }

    private static List<Object> pragmas(Tx tx) throws SQLException {
        return List.of(
            tx.queryOne("PRAGMA journal_mode", Tx.Binder.NONE, row -> row.text("journal_mode")).orElseThrow(),
            tx.queryOne("PRAGMA synchronous", Tx.Binder.NONE, row -> row.integer("synchronous")).orElseThrow(),
            tx.queryOne("PRAGMA busy_timeout", Tx.Binder.NONE, row -> row.integer("timeout")).orElseThrow(),
            tx.queryOne("PRAGMA foreign_keys", Tx.Binder.NONE, row -> row.integer("foreign_keys")).orElseThrow());
    }
}
