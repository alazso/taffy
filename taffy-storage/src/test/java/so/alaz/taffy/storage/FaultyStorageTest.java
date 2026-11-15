package so.alaz.taffy.storage;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class FaultyStorageTest {

    private static final List<Migration> MIGRATIONS = List.of(new Migration(1, "entries"));

    private static final Map<String, String> SCRIPTS = Map.of("db/migration/sqlite/V1__entries.sql",
        "CREATE TABLE entries (id INTEGER PRIMARY KEY);\n");

    @TempDir
    private Path folder;

    @Test
    void aCommitThatFailsAfterWritingIsUnknownAndTheRowIsThereAfterReopen() {
        FaultyStorage first = open();
        first.faults().failCommit(1, true, false);

        assertThatThrownBy(() -> insert(first, 1).join())
            .cause().isInstanceOf(CommitUnknownException.class)
            .hasRootCauseMessage("injected commit failure");
        first.close();

        try (FaultyStorage next = first.reopen()) {
            assertThat(ids(next)).containsExactly(1L);
        }
    }

    @Test
    void aCommitThatFailsBeforeWritingIsUnknownAndNothingIsWritten() {
        try (FaultyStorage storage = open()) {
            storage.faults().failCommit(1, false, false);

            assertThatThrownBy(() -> insert(storage, 1).join())
                .cause().isInstanceOf(CommitUnknownException.class)
                .hasRootCauseMessage("injected commit failure");

            assertThat(ids(storage)).isEmpty();
            assertThat(storage.faults().commits()).isEqualTo(2);
        }
    }

    @Test
    void aFailedStatementRollsBackTheWholeTransaction() {
        try (FaultyStorage storage = open()) {
            storage.faults().failStatement(2);

            assertThatThrownBy(() -> storage.storage().async(tx -> {
                tx.update("INSERT INTO entries (id) VALUES (1)", Tx.Binder.NONE);
                return tx.update("INSERT INTO entries (id) VALUES (2)", Tx.Binder.NONE);
            }).join())
                .cause().isInstanceOf(SQLException.class).isNotInstanceOf(CommitUnknownException.class)
                .hasMessage("injected statement failure");

            assertThat(ids(storage)).isEmpty();
        }
    }

    @Test
    void killRefusesLaterWorkAndTheNextProcessSeesOnlyWhatCommittedBefore() {
        FaultyStorage first = open();
        insert(first, 1).join();

        first.kill();

        assertThat(first.faults().dead()).isTrue();
        assertThatThrownBy(() -> insert(first, 2).join()).cause().isInstanceOf(RejectedExecutionException.class);
        try (FaultyStorage next = first.reopen()) {
            assertThat(next.faults().dead()).isFalse();
            insert(next, 3).join();
            assertThat(ids(next)).containsExactly(1L, 3L);
        }
    }

    @Test
    void aDeadProcessFailsTheWorkOfAStorageThatIsStillOpen() {
        try (FaultyStorage storage = open()) {
            storage.faults().kill();

            assertThatThrownBy(() -> insert(storage, 1).join())
                .cause().isInstanceOf(SQLException.class).hasMessage("the process is dead");
        }
    }

    private FaultyStorage open() {
        return FaultyStorage.open(folder, StorageContract.CLOCK, MIGRATIONS, FaultyStorageTest::script);
    }

    private static @Nullable InputStream script(String path) {
        String text = SCRIPTS.get(path);
        return text == null ? null : new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static CompletableFuture<Integer> insert(FaultyStorage storage, int id) {
        return storage.storage().async(tx -> tx.update("INSERT INTO entries (id) VALUES (?)",
            params -> params.integer(id)));
    }

    private static List<Long> ids(FaultyStorage storage) {
        return storage.storage().read(tx -> tx.query("SELECT id FROM entries ORDER BY id", Tx.Binder.NONE,
            row -> row.integer("id"))).join();
    }
}
