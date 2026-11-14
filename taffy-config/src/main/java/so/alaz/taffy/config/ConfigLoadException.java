package so.alaz.taffy.config;

/**
 * A configuration that could not be loaded.
 *
 * <p>The message is written for the server owner as a clause without a full stop at the end, such as
 * {@code config.yml is not saved as UTF-8}, so it reads after a lead-in. {@link ConfigHolder#reload()} puts its
 * own lead-in before it; a plugin that logs the failure of the first load gives one too, such as
 * {@code "The settings could not be loaded: " + message}.
 */
public final class ConfigLoadException extends Exception {

    /** A failure with the message for the server owner and the exception that caused it. */
    public ConfigLoadException(String message, Throwable cause) {
        super(message, cause);
    }

    /** A failure with the message for the server owner and no cause. */
    public ConfigLoadException(String message) {
        super(message);
    }
}
