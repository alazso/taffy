package so.alaz.taffy.storage;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One database transaction, handed to a {@link TxWork} for the length of that work.
 *
 * <p>A transaction is confined to the database thread that opened it and is open only while its work runs. Every
 * method that touches the database, and {@link #afterCommit}, throws {@link IllegalStateException} when it is
 * called from another thread or after the work has returned.
 */
public final class Tx {

    /** Binds the values of a statement's placeholders. */
    @FunctionalInterface
    public interface Binder {

        /** A binder for a statement without placeholders. */
        Binder NONE = params -> {
        };

        /**
         * Binds the values of the statement.
         *
         * @param params the placeholders of the statement, bound in order
         * @throws SQLException if the driver refuses a value
         */
        void bind(Params params) throws SQLException;
    }

    /**
     * Turns the current row of a query result into a value.
     *
     * @param <T> the type of the value
     */
    @FunctionalInterface
    public interface Mapper<T> {

        /**
         * Reads the current row.
         *
         * @param row the row the result is positioned on
         * @return the value of the row
         * @throws SQLException if a column cannot be read
         */
        T map(Row row) throws SQLException;
    }

    private final Connection connection;
    private final Dialect dialect;
    private final String prefix;
    private final Instant now;
    private final Thread owner = Thread.currentThread();
    private final List<Runnable> afterCommit = new ArrayList<>();
    private boolean open = true;

    Tx(Connection connection, Dialect dialect, String prefix, Instant now) {
        this.connection = connection;
        this.dialect = dialect;
        this.prefix = prefix;
        this.now = now;
    }

    /**
     * The database this transaction runs on.
     *
     * @return the dialect
     */
    public Dialect dialect() {
        return dialect;
    }

    /**
     * The full name of a table: the table prefix followed by the name.
     *
     * @param name the table name without the prefix
     * @return the prefixed name, to concatenate into SQL
     */
    public String table(String name) {
        return prefix + name;
    }

    /**
     * The instant of the storage clock taken when this transaction opened. It is the same for every call during
     * one transaction.
     *
     * @return the instant the transaction opened
     */
    public Instant now() {
        return now;
    }

    /**
     * Runs an {@code INSERT}, {@code UPDATE} or {@code DELETE} statement.
     *
     * <p>The count differs between dialects. On MySQL and MariaDB an {@code UPDATE} counts only the rows whose
     * values it changed, so a row it matches and writes with its current values counts 0. SQLite and PostgreSQL
     * count every row the statement matches. {@link Dialect#upsert} gives the counts of an upsert.
     *
     * @param sql the statement, with a {@code ?} for each value
     * @param binder binds the values
     * @return the number of rows the statement counts, as described above
     * @throws SQLException if the statement fails
     * @throws IllegalStateException if called from another thread or after the work has returned
     */
    public int update(String sql, Binder binder) throws SQLException {
        check();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(new Params(statement, dialect));
            return statement.executeUpdate();
        }
    }

    /**
     * Runs an {@code INSERT} of one row and returns the key the database generated for that row, such as the value
     * of an {@code AUTO_INCREMENT}, {@code BIGSERIAL} or {@code INTEGER PRIMARY KEY} column.
     *
     * <p>The statement is prepared with {@code keyColumn} named as the column of the generated key:
     * <ul>
     *   <li>PostgreSQL returns the value of that column, wherever the table declares it. It matches the name
     *       exactly as its catalog holds it: in lower case for a column created without quotes, and in the letter
     *       case of the quoted name, written here without the quotes, for a column created with them. A name the
     *       table does not have fails the statement.</li>
     *   <li>MySQL and MariaDB ignore the name and return the value of the {@code AUTO_INCREMENT} column of the
     *       table.</li>
     *   <li>SQLite ignores the name and returns the rowid of the row. That is the value of an
     *       {@code INTEGER PRIMARY KEY} column, and a table without such a column has a rowid too.</li>
     * </ul>
     *
     * <p>The method throws {@link SQLException}, with the statement at the end of the message, when:
     * <ul>
     *   <li>the statement does not begin with {@code INSERT}. Nothing is sent to the database then.</li>
     *   <li>the statement counts another number of rows than 1: an insert that wrote no row, such as
     *       {@code INSERT IGNORE} or {@code ON CONFLICT DO NOTHING} over an existing row, an insert of several
     *       rows, and on MySQL and MariaDB an {@code ON DUPLICATE KEY UPDATE} that found the row, which counts 2
     *       or 0.</li>
     *   <li>the database returns no key: on MySQL and MariaDB a table without an {@code AUTO_INCREMENT} column,
     *       on PostgreSQL a key column that holds {@code NULL}.</li>
     *   <li>the key is not a whole number that fits a {@code long}, such as a text or {@code UUID} column named on
     *       PostgreSQL.</li>
     * </ul>
     * In the last three cases the statement has run and what it wrote is in the open transaction, so the work lets
     * the exception through and the transaction rolls back.
     *
     * <p>Three results are not detected. On SQLite an insert into a {@code WITHOUT ROWID} table, and an
     * {@code ON CONFLICT DO UPDATE} that updated the existing row, return the rowid of an earlier insert of the
     * connection. On PostgreSQL an {@code ON CONFLICT DO UPDATE} that updated the existing row returns the key of
     * that row. On MariaDB a key of a {@code BIGINT UNSIGNED} column above {@link Long#MAX_VALUE} comes back
     * negative, where MySQL throws. Work that upserts reads the key with a query.
     *
     * <p>The row and its key belong to the transaction: when it rolls back, no row has the key. MySQL, MariaDB
     * and PostgreSQL do not give that key to a later insert; SQLite gives it again.
     *
     * <p>As every statement of a transaction, the insert runs on the database thread that runs the work, on Folia
     * too, and never on a server thread.
     *
     * @param sql the {@code INSERT} statement of one row, with a {@code ?} for each value
     * @param keyColumn the name of the column that holds the generated key
     * @param binder binds the values
     * @return the generated key of the inserted row
     * @throws SQLException if the statement fails, or in the cases listed above
     * @throws NullPointerException if {@code keyColumn} is {@code null}
     * @throws IllegalArgumentException if {@code keyColumn} is blank
     * @throws IllegalStateException if called from another thread or after the work has returned
     * @since 0.4.15
     */
    public long insert(String sql, String keyColumn, Binder binder) throws SQLException {
        check();
        if (Objects.requireNonNull(keyColumn, "keyColumn").isBlank()) {
            throw new IllegalArgumentException("keyColumn is blank; name the column of the generated key");
        }
        if (!sql.stripLeading().regionMatches(true, 0, "INSERT", 0, 6)) {
            throw new SQLException("insert runs a statement that begins with INSERT: " + sql);
        }
        try (PreparedStatement statement = connection.prepareStatement(sql, new String[] {keyColumn})) {
            binder.bind(new Params(statement, dialect));
            int rows = statement.executeUpdate();
            if (rows != 1) {
                throw new SQLException("the statement counted " + rows
                    + " rows, and insert returns the key of exactly one inserted row: " + sql);
            }
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return key(keys.next() ? keys.getObject(1) : null, sql);
            }
        }
    }

    static long key(@Nullable Object key, String sql) throws SQLException {
        if (key == null) {
            throw new SQLException("the statement generated no key: " + sql);
        }
        if (key instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).longValueExact();
            } catch (ArithmeticException | NumberFormatException unfit) {
                throw new SQLException(unfit(key, sql), unfit);
            }
        }
        throw new SQLException(unfit(key, sql));
    }

    private static String unfit(Object key, String sql) {
        return "the generated key is a " + key.getClass().getName()
            + " that is not a whole number that fits a long: " + sql;
    }

    /**
     * Runs one {@code INSERT}, {@code UPDATE} or {@code DELETE} statement once for each binder, as a JDBC batch
     * inside this transaction, so a server receives the rows in fewer round trips than one {@link #update} for
     * each. An empty list runs nothing and returns an empty array.
     *
     * <p>Each binder binds every placeholder of the statement. A JDBC driver runs a placeholder a binder leaves out
     * with the value the binder before it bound, so a binder that binds another number of values than the first
     * one makes the method throw {@link SQLException} before any run is sent.
     *
     * <p>The result holds one element for each binder, in the order of the list: the count {@link #update} gives
     * for that run of the statement, or {@link Statement#SUCCESS_NO_INFO} where the driver ran the row and does
     * not know its count. MySQL connections are opened with {@code rewriteBatchedStatements=true}, so the driver
     * sends a batch as one multi-row statement or as several statements in one packet, and reports
     * {@link Statement#SUCCESS_NO_INFO} for the rows of a rewritten batch. Work that needs the count of every row
     * on MySQL runs {@link #update} for each. A plugin that passes {@code rewriteBatchedStatements} through
     * {@link StorageSettings.Server#properties} replaces that value: with {@code false} the MySQL driver sends
     * each run by itself and counts it.
     *
     * <p>When a run of the statement fails, the method throws what the driver threw, usually a
     * {@link java.sql.BatchUpdateException}. Which runs of the batch the open transaction then holds is not
     * defined, because it depends on the database, the driver and the statement. SQLite holds the runs before the
     * failing one. MySQL and MariaDB, as storage opens them, hold no run of an {@code INSERT} batch; of an
     * {@code UPDATE} or {@code DELETE} batch they hold the runs before the failing one and also run the ones after
     * it. On PostgreSQL the failed statement ends the transaction. So the work lets the exception through, and
     * every run is rolled back with the rest of its transaction. Work that catches the exception and returns
     * reads a row back before it relies on it.
     *
     * @param sql the statement, with a {@code ?} for each value
     * @param binders binds the values of one run of the statement each
     * @return the count of each run, in order, as described above
     * @throws SQLException if a binder or a run of the statement fails, or a binder binds another number of values
     *     than the first one
     * @throws NullPointerException if the list or one of its binders is {@code null}
     * @throws IllegalStateException if called from another thread or after the work has returned
     */
    public int[] batch(String sql, List<Binder> binders) throws SQLException {
        check();
        List<Binder> runs = List.copyOf(binders);
        if (runs.isEmpty()) {
            return new int[0];
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int placeholders = 0;
            for (int run = 0; run < runs.size(); run++) {
                Params params = new Params(statement, dialect);
                runs.get(run).bind(params);
                if (run == 0) {
                    placeholders = params.bound();
                } else if (params.bound() != placeholders) {
                    throw new SQLException("the binder at index " + run + " bound " + params.bound()
                        + " values and the first binder bound " + placeholders
                        + "; each binder of a batch binds every placeholder: " + sql);
                }
                statement.addBatch();
            }
            return statement.executeBatch();
        }
    }

    /**
     * Runs a query and maps every row of its result, in the order the result returns them.
     *
     * @param <T> the type of a mapped row
     * @param sql the query, with a {@code ?} for each value
     * @param binder binds the values
     * @param mapper maps one row
     * @return the mapped rows, empty when the query found none
     * @throws SQLException if the query or the mapper fails
     * @throws IllegalStateException if called from another thread or after the work has returned
     */
    public <T> List<T> query(String sql, Binder binder, Mapper<T> mapper) throws SQLException {
        check();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(new Params(statement, dialect));
            try (ResultSet result = statement.executeQuery()) {
                Row row = new Row(result, dialect);
                List<T> rows = new ArrayList<>();
                while (result.next()) {
                    rows.add(mapper.map(row));
                }
                return rows;
            }
        }
    }

    /**
     * Runs a query that finds at most one row.
     *
     * @param <T> the type of the mapped row
     * @param sql the query, with a {@code ?} for each value
     * @param binder binds the values
     * @param mapper maps the row
     * @return the mapped row, or empty when the query found none or the mapper returned {@code null}
     * @throws SQLException if the query or the mapper fails, or the query found more than one row
     * @throws IllegalStateException if called from another thread or after the work has returned
     */
    public <T> Optional<T> queryOne(String sql, Binder binder, Mapper<T> mapper) throws SQLException {
        List<T> rows = query(sql, binder, mapper);
        if (rows.size() > 1) {
            throw new SQLException("expected at most one row, found " + rows.size() + ": " + sql);
        }
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.getFirst());
    }

    /**
     * Runs a statement without values, such as DDL.
     *
     * @param sql the statement
     * @throws SQLException if the statement fails
     * @throws IllegalStateException if called from another thread or after the work has returned
     */
    public void execute(String sql) throws SQLException {
        check();
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * Adds an action to run once this transaction has committed. The actions run on the database thread after the
     * commit and before the future of the work completes, in the order they were added. They never run for a
     * transaction that rolled back or whose commit has an unknown outcome. Work that
     * {@link Storage#asyncRetrying} or {@link Storage#readRetrying} runs again gets a new transaction for each run,
     * so only the actions added during the run that committed take effect.
     *
     * @param action the action
     * @throws NullPointerException if the action is {@code null}
     * @throws IllegalStateException if called from another thread or after the work has returned
     */
    public void afterCommit(Runnable action) {
        check();
        afterCommit.add(Objects.requireNonNull(action, "action"));
    }

    List<Runnable> committedActions() {
        return afterCommit;
    }

    void close() {
        open = false;
    }

    private void check() {
        if (!open) {
            throw new IllegalStateException("this transaction has ended");
        }
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("a transaction runs only on the database thread that opened it, not on "
                + Thread.currentThread().getName());
        }
    }
}
