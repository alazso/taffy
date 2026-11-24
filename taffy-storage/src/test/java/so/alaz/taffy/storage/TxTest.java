package so.alaz.taffy.storage;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TxTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:15:30.123Z");

    @TempDir
    Path folder;

    private Connection connection;
    private Tx tx;

    @BeforeEach
    void openTransaction() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("tx.db"));
        tx = new Tx(connection, Dialect.SQLITE, "p_", NOW);
        tx.execute("CREATE TABLE " + tx.table("probe") + " (id BLOB NOT NULL PRIMARY KEY, state VARCHAR(16))");
    }

    @AfterEach
    void closeConnection() throws SQLException {
        connection.close();
    }

    @Test
    void theTransactionReportsItsDialectPrefixedTablesAndOpeningInstant() {
        assertThat(tx.dialect()).isEqualTo(Dialect.SQLITE);
        assertThat(tx.table("probe")).isEqualTo("p_probe");
        assertThat(tx.now()).isEqualTo(NOW);
        assertThat(new Tx(connection, Dialect.SQLITE, "", NOW).table("probe")).isEqualTo("probe");
    }

    @Test
    void updatesCountTheirRowsAndQueriesMapEveryRowInOrder() throws SQLException {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        int inserted = tx.update("INSERT INTO p_probe (id, state) VALUES (?, ?), (?, ?)",
            params -> params.uuid(first).text("A").uuid(second).text("B"));
        int changed = tx.update("UPDATE p_probe SET state = 'C' WHERE state = ?", params -> params.text("B"));
        int none = tx.update("UPDATE p_probe SET state = 'D' WHERE state = 'missing'", Tx.Binder.NONE);
        List<String> states = tx.query("SELECT state FROM p_probe ORDER BY state", Tx.Binder.NONE,
            row -> row.text("state"));
        List<UUID> ids = tx.query("SELECT id FROM p_probe WHERE state = ?", params -> params.text("A"),
            row -> row.uuid("id"));

        assertThat(inserted).isEqualTo(2);
        assertThat(changed).isEqualTo(1);
        assertThat(none).isZero();
        assertThat(states).containsExactly("A", "C");
        assertThat(ids).containsExactly(first);
        assertThat(tx.query("SELECT id FROM p_probe WHERE state = 'missing'", Tx.Binder.NONE,
            row -> row.uuid("id"))).isEmpty();
    }

    @Test
    void queryOneGivesTheSingleRowOrEmpty() throws SQLException {
        UUID id = UUID.randomUUID();
        tx.update("INSERT INTO p_probe (id, state) VALUES (?, ?)", params -> params.uuid(id).text(null));

        assertThat(tx.queryOne("SELECT id FROM p_probe", Tx.Binder.NONE, row -> row.uuid("id"))).contains(id);
        assertThat(tx.queryOne("SELECT id FROM p_probe WHERE id = ?", params -> params.uuid(UUID.randomUUID()),
            row -> row.uuid("id"))).isEmpty();
        assertThat(tx.queryOne("SELECT state FROM p_probe", Tx.Binder.NONE, row -> row.text("state"))).isEmpty();
    }

    @Test
    void queryOneRefusesSeveralRows() throws SQLException {
        tx.update("INSERT INTO p_probe (id, state) VALUES (?, 'A'), (?, 'A')",
            params -> params.uuid(UUID.randomUUID()).uuid(UUID.randomUUID()));

        assertThatThrownBy(() -> tx.queryOne("SELECT id FROM p_probe", Tx.Binder.NONE, row -> row.uuid("id")))
            .isInstanceOf(SQLException.class)
            .hasMessage("expected at most one row, found 2: SELECT id FROM p_probe");
    }

    @Test
    void afterCommitCollectsTheActionsInTheOrderAddedWithoutRunningThem() {
        List<String> ran = new ArrayList<>();
        Runnable first = () -> ran.add("first");
        Runnable second = () -> ran.add("second");

        tx.afterCommit(first);
        tx.afterCommit(second);

        assertThat(tx.committedActions()).containsExactly(first, second);
        assertThat(ran).isEmpty();
    }

    @Test
    void aNullAfterCommitActionIsRefusedWhenItIsAdded() {
        assertThatThrownBy(() -> tx.afterCommit(null))
            .isInstanceOf(NullPointerException.class).hasMessage("action");
        assertThat(tx.committedActions()).isEmpty();
    }

    @Test
    void aTransactionCannotBeUsedFromAnotherThread() throws InterruptedException {
        AtomicReference<Throwable> execute = new AtomicReference<>();
        AtomicReference<Throwable> afterCommit = new AtomicReference<>();
        Thread other = new Thread(() -> {
            execute.set(failureOf(() -> tx.execute("SELECT 1")));
            afterCommit.set(failureOf(() -> tx.afterCommit(() -> { })));
        }, "other-worker");

        other.start();
        other.join();

        assertThat(execute.get()).isInstanceOf(IllegalStateException.class)
            .hasMessage("a transaction runs only on the database thread that opened it, not on other-worker");
        assertThat(afterCommit.get()).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("only on the database thread");
        assertThat(tx.committedActions()).isEmpty();
    }

    @Test
    void aTransactionCannotBeUsedAfterItEnds() {
        tx.close();

        assertThatThrownBy(() -> tx.execute("SELECT 1"))
            .isInstanceOf(IllegalStateException.class).hasMessage("this transaction has ended");
        assertThatThrownBy(() -> tx.update("DELETE FROM p_probe", Tx.Binder.NONE))
            .isInstanceOf(IllegalStateException.class).hasMessage("this transaction has ended");
        assertThatThrownBy(() -> tx.query("SELECT id FROM p_probe", Tx.Binder.NONE, row -> row.uuid("id")))
            .isInstanceOf(IllegalStateException.class).hasMessage("this transaction has ended");
        assertThatThrownBy(() -> tx.queryOne("SELECT id FROM p_probe", Tx.Binder.NONE, row -> row.uuid("id")))
            .isInstanceOf(IllegalStateException.class).hasMessage("this transaction has ended");
        assertThatThrownBy(() -> tx.afterCommit(() -> { }))
            .isInstanceOf(IllegalStateException.class).hasMessage("this transaction has ended");
        assertThat(tx.now()).isEqualTo(NOW);
    }

    @Test
    void aBatchCountsEachRunAndAnEmptyOneTouchesNoStatement() throws SQLException {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        int[] inserted = tx.batch("INSERT INTO p_probe (id, state) VALUES (?, ?)",
            List.of(params -> params.uuid(first).text("A"), params -> params.uuid(second).text("B")));
        int[] changed = tx.batch("UPDATE p_probe SET state = ? WHERE id = ?",
            List.of(params -> params.text("C").uuid(second), params -> params.text("C").uuid(UUID.randomUUID())));
        int[] none = tx.batch("this is not SQL", List.of());

        assertThat(inserted).containsExactly(1, 1);
        assertThat(changed).containsExactly(1, 0);
        assertThat(none).isEmpty();
        assertThat(tx.query("SELECT state FROM p_probe ORDER BY state", Tx.Binder.NONE, row -> row.text("state")))
            .containsExactly("A", "C");
    }

    @Test
    void aBatchKeepsTheRunsBeforeAFailingOneInTheOpenTransaction() throws SQLException {
        UUID first = UUID.randomUUID();
        connection.setAutoCommit(false);

        assertThatThrownBy(() -> tx.batch("INSERT INTO p_probe (id, state) VALUES (?, ?)",
            List.of(params -> params.uuid(first).text("A"), params -> params.uuid(first).text("B"))))
            .isInstanceOf(SQLException.class);

        assertThat(tx.query("SELECT state FROM p_probe", Tx.Binder.NONE, row -> row.text("state")))
            .containsExactly("A");
        connection.rollback();
        assertThat(tx.query("SELECT state FROM p_probe", Tx.Binder.NONE, row -> row.text("state"))).isEmpty();
    }

    @Test
    void aBatchRefusesABinderThatBindsFewerValuesThanTheFirstBeforeItRunsAnything() throws SQLException {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String insert = "INSERT INTO p_probe (id, state) VALUES (?, ?)";

        assertThatThrownBy(() -> tx.batch(insert,
            List.of(params -> params.uuid(first).text("FIRST"), params -> params.uuid(second))))
            .isInstanceOf(SQLException.class)
            .hasMessage("the binder at index 1 bound 1 values and the first binder bound 2; each binder of a batch"
                + " binds every placeholder: " + insert);

        assertThat(tx.query("SELECT state FROM p_probe", Tx.Binder.NONE, row -> row.text("state"))).isEmpty();
    }

    @Test
    void aBatchRefusesANullBinderAndAnEndedTransaction() {
        List<Tx.Binder> withNull = new ArrayList<>();
        withNull.add(null);

        assertThatThrownBy(() -> tx.batch("DELETE FROM p_probe", withNull)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> tx.batch("DELETE FROM p_probe", null)).isInstanceOf(NullPointerException.class);
        tx.close();
        assertThatThrownBy(() -> tx.batch("DELETE FROM p_probe", List.of()))
            .isInstanceOf(IllegalStateException.class).hasMessage("this transaction has ended");
    }

    @Test
    void anInsertReturnsTheRowidOfItsRowWhateverColumnIsNamed() throws SQLException {
        tx.execute("CREATE TABLE p_entry (label TEXT UNIQUE, id INTEGER PRIMARY KEY AUTOINCREMENT)");
        String sql = "  insert into p_entry (label) VALUES (?)";

        long first = tx.insert(sql, "id", params -> params.text("A"));
        long second = tx.insert(sql, "missing", params -> params.text("B"));
        long withoutKeyColumn = tx.insert("INSERT INTO p_probe (id, state) VALUES (?, 'A')", "id",
            params -> params.uuid(UUID.randomUUID()));

        assertThat(List.of(first, second, withoutKeyColumn)).containsExactly(1L, 2L, 1L);
        assertThat(tx.query("SELECT label FROM p_entry WHERE id = ?", params -> params.integer(second),
            row -> row.text("label"))).containsExactly("B");
    }

    @Test
    void anInsertRefusesAStatementThatCountsAnotherNumberOfRowsThanOne() throws SQLException {
        tx.execute("CREATE TABLE p_entry (label TEXT UNIQUE, id INTEGER PRIMARY KEY AUTOINCREMENT)");
        tx.insert("INSERT INTO p_entry (label) VALUES ('A')", "id", Tx.Binder.NONE);
        String ignored = "INSERT OR IGNORE INTO p_entry (label) VALUES ('A')";
        String several = "INSERT INTO p_entry (label) VALUES ('B'), ('C')";

        assertThatThrownBy(() -> tx.insert(ignored, "id", Tx.Binder.NONE)).isInstanceOf(SQLException.class)
            .hasMessage("the statement counted 0 rows, and insert returns the key of exactly one inserted row: "
                + ignored);
        assertThatThrownBy(() -> tx.insert(several, "id", Tx.Binder.NONE)).isInstanceOf(SQLException.class)
            .hasMessage("the statement counted 2 rows, and insert returns the key of exactly one inserted row: "
                + several);
    }

    @Test
    void anInsertRefusesAnotherStatementANullOrBlankKeyColumnAndAnEndedTransaction() throws SQLException {
        String insert = "INSERT INTO p_probe (id, state) VALUES (?, 'A')";
        Tx.Binder binder = params -> params.uuid(UUID.randomUUID());
        String update = "UPDATE p_probe SET state = 'B'";

        assertThatThrownBy(() -> tx.insert(update, "id", Tx.Binder.NONE)).isInstanceOf(SQLException.class)
            .hasMessage("insert runs a statement that begins with INSERT: " + update);
        assertThatThrownBy(() -> tx.insert(insert, null, binder))
            .isInstanceOf(NullPointerException.class).hasMessage("keyColumn");
        assertThatThrownBy(() -> tx.insert(insert, " ", binder)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("keyColumn is blank; name the column of the generated key");
        assertThat(tx.query("SELECT state FROM p_probe", Tx.Binder.NONE, row -> row.text("state"))).isEmpty();
        tx.close();
        assertThatThrownBy(() -> tx.insert(insert, "id", binder))
            .isInstanceOf(IllegalStateException.class).hasMessage("this transaction has ended");
    }

    @Test
    void sqliteAnswersTheRowidOfAnEarlierInsertForAnUpsertThatUpdatedAndForATableWithoutRowid()
        throws SQLException {
        tx.execute("CREATE TABLE p_entry (label TEXT UNIQUE, id INTEGER PRIMARY KEY AUTOINCREMENT)");
        tx.execute("CREATE TABLE p_bare (name TEXT PRIMARY KEY) WITHOUT ROWID");
        String upsert = "INSERT INTO p_entry (label) VALUES ('A') ON CONFLICT (label) DO UPDATE SET label = 'A'";
        long first = tx.insert(upsert, "id", Tx.Binder.NONE);
        long other = tx.insert("INSERT INTO p_entry (label) VALUES ('B')", "id", Tx.Binder.NONE);

        long updated = tx.insert(upsert, "id", Tx.Binder.NONE);
        long bare = tx.insert("INSERT INTO p_bare (name) VALUES ('A')", "name", Tx.Binder.NONE);

        assertThat(List.of(first, other, updated, bare)).containsExactly(1L, 2L, 2L, 2L);
    }

    @Test
    void aGeneratedKeyIsAWholeNumberThatFitsALong() throws SQLException {
        assertThat(Tx.key(7, "SQL")).isEqualTo(7L);
        assertThat(Tx.key(Long.MIN_VALUE, "SQL")).isEqualTo(Long.MIN_VALUE);
        assertThat(Tx.key(BigInteger.valueOf(Long.MAX_VALUE), "SQL")).isEqualTo(Long.MAX_VALUE);
        assertThat(Tx.key(new BigDecimal("12.00"), "SQL")).isEqualTo(12L);

        assertThatThrownBy(() -> Tx.key(null, "SQL")).isInstanceOf(SQLException.class)
            .hasMessage("the statement generated no key: SQL");
        assertThatThrownBy(() -> Tx.key(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE), "SQL"))
            .isInstanceOf(SQLException.class).hasCauseInstanceOf(ArithmeticException.class)
            .hasMessage("the generated key is a java.math.BigInteger that is not a whole number that fits a long:"
                + " SQL");
        assertThatThrownBy(() -> Tx.key(new BigDecimal("1.5"), "SQL")).isInstanceOf(SQLException.class)
            .hasMessage("the generated key is a java.math.BigDecimal that is not a whole number that fits a long:"
                + " SQL");
        assertThatThrownBy(() -> Tx.key(Double.NaN, "SQL")).isInstanceOf(SQLException.class)
            .hasCauseInstanceOf(NumberFormatException.class);
        assertThatThrownBy(() -> Tx.key("7", "SQL")).isInstanceOf(SQLException.class)
            .hasMessage("the generated key is a java.lang.String that is not a whole number that fits a long: SQL");
    }

    @Test
    void aFailingStatementPassesTheDriverFailureOn() {
        assertThatThrownBy(() -> tx.execute("SELECT * FROM p_missing"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("p_missing");
    }

    private static @Nullable Throwable failureOf(Call call) {
        try {
            call.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    @FunctionalInterface
    private interface Call {
        void run() throws Exception;
    }
}
