package so.alaz.taffy.hooks;

import org.jetbrains.annotations.Nullable;

/**
 * Receives each error {@link ConditionRegistry#build} finds in a list of conditions. The three arguments are those
 * of {@code ConfigReader.error(String, String, String)} in {@code taffy-config}, so a plugin that reads its file
 * through a {@code ConfigReader} passes {@code reader::error} and finds every error in the reader's
 * {@code errors()} and {@code problems()}.
 *
 * @since 0.4.14
 */
@FunctionalInterface
public interface ConditionProblems {

    /**
     * Takes one error.
     *
     * @param path the key that is wrong, such as {@code conditions[1].type}: the path given to
     *     {@link ConditionRegistry#build}, the index of the entry counted from 0 inside square brackets, and the key
     *     inside the entry after a dot, or the path of the list or of the entry alone when that is what is wrong
     * @param message what is wrong, as it reads after the path, without a full stop at the end
     * @param fix what the owner has to do, without a full stop at the end, or {@code null}
     * @since 0.4.14
     */
    void error(String path, String message, @Nullable String fix);
}
