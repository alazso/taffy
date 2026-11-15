package so.alaz.taffy.storage;

/**
 * A storage operation that could not be completed.
 *
 * <p>The failure a server owner has to act on, such as a missing library or a migration script that changed. The
 * message is written for the owner as one sentence without a full stop at the end, so it reads after a lead-in the
 * plugin gives when it logs the failure.
 */
public final class StorageException extends Exception {

    /** A failure with the message for the server owner and no cause. */
    public StorageException(String message) {
        super(message);
    }

    /** A failure with the message for the server owner and the exception that caused it. */
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
