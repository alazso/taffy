package so.alaz.taffy.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.test.CapturedLog;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class RollbackRetryTest {

    private static final String NAME = "retry-probe";

    @TempDir
    private Path folder;

    private final CapturedLog log = new CapturedLog();
    private Storage storage;

    @BeforeEach
    void start() {
        StorageSettings settings = new StorageSettings(Dialect.SQLITE, "data.db", null,
            new StorageSettings.Pool(2, 1, Duration.ofSeconds(5)), 2, 0);
        storage = Storage.start(TestPlugins.plugin(NAME, folder, log.logger), new ErrorReports(), settings, List.of(),
            StorageContract.CLOCK).join();
        storage.async(tx -> {
            tx.execute("CREATE TABLE entries (id INTEGER PRIMARY KEY)");
            tx.execute("CREATE TABLE parent (id INTEGER PRIMARY KEY)");
            tx.execute("CREATE TABLE child (id INTEGER PRIMARY KEY,"
                + " parent INTEGER REFERENCES parent (id) DEFERRABLE INITIALLY DEFERRED)");
            return null;
        }).join();
    }

    @AfterEach
    void close() {
        assertThat(storage.close(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void recognisesARolledBackTransaction() {
        assertThat(Storage.rolledBack(new SQLTransactionRollbackException("deadlock"))).isTrue();
        assertThat(Storage.rolledBack(new SQLException("deadlock found", "40001"))).isTrue();
        assertThat(Storage.rolledBack(new SQLException("deadlock detected", "40P01"))).isTrue();
        assertThat(Storage.rolledBack(
            new SQLException("wrapped", "HY000", new SQLException("deadlock", "40001")))).isTrue();
        assertThat(Storage.rolledBack(
            new SQLException("wrapped", "HY000", new SQLTransactionRollbackException("deadlock")))).isTrue();
    }

    @Test
    void leavesOtherFailuresAlone() {
        assertThat(Storage.rolledBack(new SQLException("duplicate key", "23000"))).isFalse();
        assertThat(Storage.rolledBack(new SQLException("syntax error", "42000"))).isFalse();
        assertThat(Storage.rolledBack(new SQLException("undefined table", "42P01"))).isFalse();
        assertThat(Storage.rolledBack(new SQLException("no state"))).isFalse();
        assertThat(Storage.rolledBack(new SQLException("wrapped", "HY000", new IllegalStateException("x"))))
            .isFalse();
    }

    @Test
    void aCauseChainThatLoopsEndsTheWalk() {
        SQLException first = new SQLException("first", "HY000");
        SQLException second = new SQLException("second", "HY000");
        first.initCause(second);
        second.initCause(first);
        SQLException rolledBack = new SQLException("third", "40001");
        SQLException outer = new SQLException("outer", "HY000");
        outer.initCause(rolledBack);
        rolledBack.initCause(outer);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThat(Storage.rolledBack(first)).isFalse();
            assertThat(Storage.rolledBack(outer)).isTrue();
        });
    }

    @Test
    void aCommitWithAnUnknownOutcomeNeverCountsAsRolledBackWhateverItsState() {
        CommitUnknownException unknown = new CommitUnknownException(new SQLException("serialization", "40001"));

        assertThat(unknown.getSQLState()).isEqualTo("40001");
        assertThat(Storage.rolledBack(unknown)).isFalse();
        assertThat(Storage.rolledBack(new SQLException("wrapped", "HY000", unknown))).isFalse();
    }

    @Test
    void restartsOnTheSameThreadUntilTheWorkCommits() {
        AtomicInteger attempts = new AtomicInteger();
        List<String> threads = new CopyOnWriteArrayList<>();

        String result = storage.asyncRetrying(tx -> {
            threads.add(Thread.currentThread().getName());
            tx.update("INSERT INTO entries (id) VALUES (?)", params -> params.integer(attempts.incrementAndGet()));
            if (attempts.get() < 3) {
                throw new SQLTransactionRollbackException("deadlock");
            }
            return "written";
        }).join();

        assertThat(result).isEqualTo("written");
        assertThat(attempts).hasValue(3);
        assertThat(threads).hasSize(3).allMatch(threads.getFirst()::equals);
        assertThat(entries()).containsExactly(3L);
    }

    @Test
    void givesUpAfterTheFourthAttemptAndReportsTheRollback() {
        AtomicInteger attempts = new AtomicInteger();

        CompletableFuture<Object> failed = storage.asyncRetrying(tx -> {
            throw new SQLTransactionRollbackException("deadlock " + attempts.incrementAndGet());
        });

        assertThatThrownBy(failed::join).cause()
            .isInstanceOf(SQLTransactionRollbackException.class).hasMessage("deadlock 4");
        assertThat(attempts).hasValue(4);
    }

    @Test
    void doesNotRestartAFailureTheDatabaseDidNotRollBack() {
        AtomicInteger attempts = new AtomicInteger();

        CompletableFuture<Object> failed = storage.asyncRetrying(tx -> {
            attempts.incrementAndGet();
            throw new SQLException("duplicate key", "23000");
        });

        assertThatThrownBy(failed::join).cause().isInstanceOf(SQLException.class).hasMessage("duplicate key");
        assertThat(attempts).hasValue(1);
    }

    @Test
    void doesNotRestartAFailedCommitStatement() {
        AtomicInteger attempts = new AtomicInteger();

        CompletableFuture<Integer> unknown = storage.asyncRetrying(tx -> {
            attempts.incrementAndGet();
            return tx.update("INSERT INTO child VALUES (1, 99)", Tx.Binder.NONE);
        });

        assertThatThrownBy(unknown::join).cause().isInstanceOf(CommitUnknownException.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void onlyTheActionsOfTheAttemptThatCommittedRun() {
        AtomicInteger attempts = new AtomicInteger();
        List<String> ran = new CopyOnWriteArrayList<>();

        int committed = storage.asyncRetrying(tx -> {
            int attempt = attempts.incrementAndGet();
            tx.afterCommit(() -> ran.add("attempt " + attempt));
            if (attempt < 3) {
                throw new SQLException("deadlock found", "40001");
            }
            return attempt;
        }).join();

        assertThat(committed).isEqualTo(3);
        assertThat(ran).containsExactly("attempt 3");
    }

    @Test
    void readRetryingRestartsAReadTheDatabaseRolledBack() {
        AtomicInteger attempts = new AtomicInteger();

        List<Long> found = storage.readRetrying(tx -> {
            if (attempts.incrementAndGet() < 2) {
                throw new SQLException("could not serialize access", "40001");
            }
            return tx.query("SELECT id FROM entries", Tx.Binder.NONE, row -> row.integer("id"));
        }).join();

        assertThat(found).isEmpty();
        assertThat(attempts).hasValue(2);
    }

    @Test
    void asyncAndReadRunTheWorkOnceEvenWhenTheDatabaseRolledItBack() {
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();

        CompletableFuture<Object> write = storage.async(tx -> {
            writes.incrementAndGet();
            throw new SQLTransactionRollbackException("deadlock");
        });
        CompletableFuture<Object> read = storage.read(tx -> {
            reads.incrementAndGet();
            throw new SQLTransactionRollbackException("deadlock");
        });

        assertThatThrownBy(write::join).cause().isInstanceOf(SQLTransactionRollbackException.class);
        assertThatThrownBy(read::join).cause().isInstanceOf(SQLTransactionRollbackException.class);
        assertThat(writes).hasValue(1);
        assertThat(reads).hasValue(1);
    }

    @Test
    void aFailedCommitWhoseCauseCarriesARollbackStateIsNotRestarted() throws SQLException {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        doThrow(new SQLException("could not serialize access", "40001")).when(connection).commit();
        Storage over = Storage.over(TestPlugins.plugin("retry-over", log.logger), new ErrorReports(), Dialect.POSTGRES,
            source, "p_", StorageContract.CLOCK);
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean ran = new AtomicBoolean();

        CompletableFuture<Integer> unknown = over.asyncRetrying(tx -> {
            tx.afterCommit(() -> ran.set(true));
            return attempts.incrementAndGet();
        });

        assertThatThrownBy(unknown::join).cause().isInstanceOf(CommitUnknownException.class)
            .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("40001"))
            .cause().hasMessage("could not serialize access");
        assertThat(attempts).hasValue(1);
        assertThat(ran).isFalse();
        verify(source, times(1)).getConnection();
        verify(connection, times(1)).commit();
        assertThat(over.close(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void anInterruptDuringThePauseFailsTheWorkAndKeepsTheInterruptFlag() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        Storage over = Storage.over(TestPlugins.plugin("retry-interrupt", log.logger), new ErrorReports(),
            Dialect.POSTGRES, source, "p_", StorageContract.CLOCK);
        CountDownLatch attached = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Boolean> interruptedOnCompletion = new AtomicReference<>();

        CompletableFuture<Object> failed = over.asyncRetrying(tx -> {
            attempts.incrementAndGet();
            try {
                assertThat(attached.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException unexpected) {
                throw new IllegalStateException(unexpected);
            }
            Thread.currentThread().interrupt();
            throw new SQLTransactionRollbackException("deadlock");
        });
        CompletableFuture<Object> observed = failed.whenComplete(
            (value, failure) -> interruptedOnCompletion.set(Thread.currentThread().isInterrupted()));
        attached.countDown();

        assertThatThrownBy(() -> observed.get(10, TimeUnit.SECONDS)).cause()
            .isInstanceOf(SQLException.class)
            .hasMessage("interrupted while waiting to restart a rolled back transaction")
            .hasCauseInstanceOf(InterruptedException.class)
            .satisfies(failure -> assertThat(failure.getSuppressed())
                .hasExactlyElementsOfTypes(SQLTransactionRollbackException.class)
                .extracting(Throwable::getMessage).containsExactly("deadlock"));
        assertThat(attempts).hasValue(1);
        assertThat(interruptedOnCompletion).hasValue(true);
        verify(connection).rollback();
        assertThat(over.close(Duration.ofSeconds(5))).isTrue();
    }

    private List<Long> entries() {
        return storage.read(tx -> tx.query("SELECT id FROM entries ORDER BY id", Tx.Binder.NONE,
            row -> row.integer("id"))).join();
    }
}
