package so.alaz.taffy.config;

import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.Objects;

/**
 * One warning or error found in a configuration, in parts that code can list, count and sort.
 *
 * <p>{@link ConfigReader#problems()} gives one record for each line the reader reports, and
 * {@link YamlFolder#loadReport} one for each file that failed beside those its parser hands over. The message is
 * the line the owner reads in the log, so it names the path and ends with the fix when the line does. Code
 * reads the other components and never takes a line apart.
 *
 * @param file the name of the file, such as {@code config.yml}, or {@code null} when the reader that found the
 *     problem was built without one
 * @param path the key the problem is about, written as the message writes it, such as {@code limits.homes} or
 *     {@code rewards[1].type}, or {@code null} when the problem is about the whole file or names no key
 * @param severity whether a fallback is in use or the configuration cannot be used
 * @param message the full line for the owner
 * @param fix what the owner has to do, without a full stop at the end, or {@code null} when the source of the
 *     problem states none
 */
public record ConfigProblem(
    @Nullable String file, @Nullable String path, Severity severity, String message, @Nullable String fix)
    implements Serializable {

    /** How much a problem costs the owner. */
    public enum Severity {

        /** The value was replaced with a fallback or skipped, and the configuration is in use. */
        WARNING,

        /** The file or the entry was not loaded. */
        ERROR
    }

    /** @throws NullPointerException when {@code severity} or {@code message} is {@code null} */
    public ConfigProblem {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
    }
}
