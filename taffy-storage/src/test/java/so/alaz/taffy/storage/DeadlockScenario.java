package so.alaz.taffy.storage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two retrying transactions on a server database that lock the same two rows in opposite order. Each holds its first
 * row until the other holds its own, so each then waits for the row the other holds and the database rolls one of
 * them back to break the deadlock.
 */
final class DeadlockScenario {

    private DeadlockScenario() {
    }

    /**
     * Runs the two transactions on {@code storage}, which needs two database threads, and checks that the one the
     * database rolled back ran again and that each added its amount to both rows exactly once.
     *
     * @param connection a connection of its own to the database of {@code storage}, in auto-commit mode
     * @param table the name of the table to create and drop
     */
    static void run(Storage storage, Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (id INTEGER NOT NULL PRIMARY KEY, total BIGINT NOT NULL)");
            try {
                statement.execute("INSERT INTO " + table + " VALUES (1, 0), (2, 0)");
                CyclicBarrier bothLocked = new CyclicBarrier(2);
                AtomicInteger attempts = new AtomicInteger();
                AtomicInteger committed = new AtomicInteger();

                CompletableFuture<Integer> upward = adding(storage, table, 1, 2, 1, bothLocked, attempts, committed);
                CompletableFuture<Integer> downward = adding(storage, table, 2, 1, 10, bothLocked, attempts, committed);

                assertThat(List.of(upward.get(60, TimeUnit.SECONDS), downward.get(60, TimeUnit.SECONDS)))
                    .as("the attempts each transaction took").containsExactlyInAnyOrder(1, 2);
                assertThat(attempts).hasValue(3);
                assertThat(committed).hasValue(2);
                assertThat(totals(statement, table)).containsExactly(11L, 11L);
            } finally {
                statement.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }

    // Adds amount to the row first and then to the row second, and gives the number of attempts it took. Only the
    // first attempt waits, after it has locked its first row, until the other transaction has locked its own.
    private static CompletableFuture<Integer> adding(Storage storage, String table, int first, int second,
                                                     long amount, CyclicBarrier bothLocked, AtomicInteger attempts,
                                                     AtomicInteger committed) {
        AtomicInteger own = new AtomicInteger();
        String add = "UPDATE " + table + " SET total = total + ? WHERE id = ?";
        return storage.asyncRetrying(tx -> {
            attempts.incrementAndGet();
            int attempt = own.incrementAndGet();
            tx.afterCommit(committed::incrementAndGet);
            tx.update(add, params -> params.integer(amount).integer(first));
            if (attempt == 1) {
                try {
                    bothLocked.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException | BrokenBarrierException | TimeoutException unmet) {
                    throw new IllegalStateException("the other transaction did not lock its first row", unmet);
                }
            }
            tx.update(add, params -> params.integer(amount).integer(second));
            return attempt;
        });
    }

    private static List<Long> totals(Statement statement, String table) throws SQLException {
        List<Long> totals = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery("SELECT total FROM " + table + " ORDER BY id")) {
            while (rows.next()) {
                totals.add(rows.getLong(1));
            }
        }
        return totals;
    }
}
