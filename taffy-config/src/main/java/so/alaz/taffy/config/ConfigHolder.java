package so.alaz.taffy.config;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Holds a plugin's loaded settings and replaces them on a reload only when the new ones load.
 *
 * <p>The loader and the failure consumer are given once, to {@link #load}, and are used for every reload.
 * A reload whose loader throws {@link ConfigLoadException} keeps the settings in effect, so settings the loader
 * rejects never replace working ones. A loader treats settings with errors as a failure by throwing
 * {@link ConfigLoadException}, so they are kept out the same way as a file that does not parse. A value that
 * {@link ConfigReader} replaces with its fallback and a warning does not fail the reload: the new settings
 * hold the fallback.
 *
 * <p>{@link #reload()} takes no arguments and returns whether the new settings are in effect, so
 * {@code holder::reload} serves as a {@link java.util.function.BooleanSupplier} and as a
 * {@link java.util.concurrent.Callable Callable&lt;Boolean&gt;}. The holder itself serves as a
 * {@link Supplier} of the settings in effect.
 *
 * <p>The loader runs on the thread that calls {@link #load} or {@link #reload()} and blocks it while it reads
 * files. {@link #get()} may be called from any thread. The settings are handed out as they are, so {@code T}
 * should be immutable, such as a record.
 *
 * @param <T> the plugin's settings
 */
public final class ConfigHolder<T> implements Supplier<T> {

    /**
     * Reads the settings anew from their files.
     *
     * @param <T> the plugin's settings
     */
    @FunctionalInterface
    public interface Loader<T> {

        /**
         * The settings as the files now hold them, never {@code null}.
         *
         * @throws ConfigLoadException when the settings cannot be used. The message is shown to the owner.
         */
        T load() throws ConfigLoadException;
    }

    private final Loader<T> loader;
    private final Consumer<String> failures;
    private final T startup;
    private volatile T current;

    private ConfigHolder(Loader<T> loader, Consumer<String> failures, T startup) {
        this.loader = loader;
        this.failures = failures;
        this.startup = startup;
        this.current = startup;
    }

    /**
     * Runs the first load and returns a holder of its settings.
     *
     * @param loader reads the settings, now and on every {@link #reload()}
     * @param failures receives the line for each failed reload, on the thread that called {@link #reload()}
     * @throws ConfigLoadException when the first load fails, which is passed on as the loader threw it and is
     *     not given to {@code failures}
     * @throws NullPointerException when the loader returns {@code null}
     */
    public static <T> ConfigHolder<T> load(Loader<T> loader, Consumer<String> failures) throws ConfigLoadException {
        return new ConfigHolder<>(loader, failures, loaded(loader));
    }

    /** The settings in effect: those of the first load, or of the last reload that succeeded. */
    @Override
    public T get() {
        return current;
    }

    /**
     * The settings of the first load, which never change. Settings that take effect only after a restart are
     * read from here, and {@link RestartKeys} compares against the configuration they were read from.
     *
     * <p>The holder keeps only {@code T}. To name the changed restart-only keys, {@code T} keeps the
     * {@link org.bukkit.configuration.ConfigurationSection} it was read from, and the sections of
     * {@code startup()} and {@link #get()} are passed to {@link RestartKeys#changed}.
     */
    public T startup() {
        return startup;
    }

    /**
     * Runs the loader again and puts its settings in effect. Reloads never overlap: a call waits while another
     * one runs.
     *
     * <p>When the loader throws {@link ConfigLoadException} the settings in effect stay, the line
     * {@code The reload failed, so the settings in effect stay active and no value read by this reload is used:
     * <message>} is passed to the failure consumer and {@code false} is returned. A warning the reader gave during
     * that reload, which names a fallback as used, therefore did not take effect. A {@link RuntimeException} from
     * the loader is thrown on to the caller and also keeps the settings in effect, as does a {@code null} from the
     * loader, which throws {@link NullPointerException}.
     *
     * @return {@code true} when the new settings are in effect
     */
    public synchronized boolean reload() {
        T loaded;
        try {
            loaded = loaded(loader);
        } catch (ConfigLoadException failure) {
            failures.accept("The reload failed, so the settings in effect stay active and no value read by this"
                + " reload is used: " + failure.getMessage());
            return false;
        }
        current = loaded;
        return true;
    }

    private static <T> T loaded(Loader<T> loader) throws ConfigLoadException {
        return Objects.requireNonNull(loader.load(), "the loader returned null");
    }
}
