package so.alaz.taffy.storage;

import java.sql.SQLException;

/**
 * Database work that runs inside one transaction on a database thread.
 *
 * <p>The work uses its {@link Tx} only on the thread it is called on and only until it returns. Effects outside the
 * database belong in {@link Tx#afterCommit}, so they happen only once the transaction is written.
 *
 * <p>Work passed to {@link Storage#asyncRetrying} or {@link Storage#readRetrying} may run more than once, each time
 * with a new {@link Tx}, when the database rolls a transaction back. Each run must then be safe to repeat, which
 * holds when its only effect outside the database is an {@link Tx#afterCommit} action.
 *
 * @param <T> the type of the result
 */
@FunctionalInterface
public interface TxWork<T> {

    /**
     * Runs the work. Throwing rolls the transaction back.
     *
     * @param tx the open transaction
     * @return the result of the work
     * @throws SQLException if a statement fails
     */
    T run(Tx tx) throws SQLException;
}
