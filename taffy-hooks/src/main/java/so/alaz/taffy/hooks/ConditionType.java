package so.alaz.taffy.hooks;

import org.jetbrains.annotations.Nullable;

/**
 * Builds the {@link Condition} of one entry of a config list. A plugin registers one under a name with
 * {@link ConditionRegistry#register}, and {@link ConditionRegistry#build} calls it for every entry whose
 * {@code type} is that name, on the thread that called {@code build}.
 *
 * @since 0.4.14
 */
@FunctionalInterface
public interface ConditionType {

    /**
     * Reads the keys of one entry and builds its condition. A key that is missing or unusable is passed to
     * {@link ConditionConfig#error}, or read through a {@link ConditionConfig} method that reports it, and is not
     * thrown. An exception thrown here is caught by {@link ConditionRegistry#build}, which logs it, reports it and
     * passes it on as an error of the entry.
     *
     * @param config the entry
     * @return the condition, or {@code null} when an error stopped it. An entry with an error denies every
     *     player whatever is returned, and a {@code null} without an error is passed on as an error of the entry
     * @since 0.4.14
     */
    @Nullable
    Condition create(ConditionConfig config);
}
