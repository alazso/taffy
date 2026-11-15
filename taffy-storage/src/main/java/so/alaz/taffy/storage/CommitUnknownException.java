package so.alaz.taffy.storage;

import java.sql.SQLException;

/**
 * The commit statement of a transaction failed, so whether the transaction was written is unknown.
 *
 * <p>Every earlier failure in a transaction arrives as its own exception and means nothing was written. After
 * this one the caller reads the state back to learn whether the transaction took effect.
 */
public final class CommitUnknownException extends SQLException {

    /**
     * Wraps the failure of a commit statement, keeping its SQLState and vendor code.
     *
     * @param cause the failure of the commit statement
     * @throws NullPointerException if the cause is {@code null}
     */
    public CommitUnknownException(SQLException cause) {
        super("the commit failed, so whether the transaction was written is unknown: " + cause.getMessage(),
            cause.getSQLState(), cause.getErrorCode(), cause);
    }
}
