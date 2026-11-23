package so.alaz.taffy.config;

import java.util.List;

/**
 * A configuration that could not be loaded.
 *
 * <p>The message is written for the server owner as a clause without a full stop at the end, such as
 * {@code config.yml is not saved as UTF-8}, so it reads after a lead-in. {@link ConfigHolder#reload()} puts its
 * own lead-in before it; a plugin that logs the failure of the first load gives one too, such as
 * {@code "The settings could not be loaded: " + message}.
 */
public final class ConfigLoadException extends Exception {

    private final List<ConfigProblem> problems;

    /** A failure with the message for the server owner and the exception that caused it. */
    public ConfigLoadException(String message, Throwable cause) {
        super(message, cause);
        this.problems = List.of();
    }

    /** A failure with the message for the server owner and no cause. */
    public ConfigLoadException(String message) {
        super(message);
        this.problems = List.of();
    }

    private ConfigLoadException(String message, List<ConfigProblem> problems, boolean copied) {
        super(message);
        this.problems = copied ? problems : List.copyOf(problems);
    }

    /**
     * A failure with the message for the server owner, no cause and the problems found before it, such as
     * {@link ConfigReader#problems()} of the reader whose errors make up the message.
     * {@link YamlFolder#loadReport} adds them to the problems of the load.
     *
     * @param problems copied in their order
     */
    public static ConfigLoadException withProblems(String message, List<ConfigProblem> problems) {
        return new ConfigLoadException(message, List.copyOf(problems), true);
    }

    /**
     * The problems given to {@link #withProblems(String, List)}, as an unmodifiable list. It is empty for a
     * failure built with a constructor.
     */
    public List<ConfigProblem> problems() {
        return problems;
    }
}
