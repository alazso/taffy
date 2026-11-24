package so.alaz.taffy.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The importers of a plugin by id, for its import command: {@link #byId} finds the one an owner named, and
 * {@link #presentIds()} and {@link #allIds()} give the ids the command suggests.
 *
 * <pre>{@code
 * Importers importers = Importers.create(getLogger(), errors)
 *     .register(new CrazyVouchersImporter(getDataFolder().toPath(), scheduler));
 *
 * // in the command, with the id the owner typed
 * Importer importer = importers.byId(id).filter(found -> importers.presentIds().contains(found.id())).orElse(null);
 * if (importer == null) {
 *     sender.sendMessage(notFound);
 *     return;
 * }
 * importer.run(dryRun).whenComplete((report, failure) -> scheduler.global(() -> show(sender, report, failure)));
 * }</pre>
 *
 * <p>The registry runs no import and takes no backup: the plugin calls {@link Importer#run} itself, after the backup
 * it wants before a real run.
 *
 * <p>Thread-safe: every method may be called from any thread, on Folia too. {@link #presentIds()} calls
 * {@link Importer#present()} on the calling thread.
 *
 * @since 0.4.15
 */
public final class Importers {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final String SOURCE = "importer.";

    private record Entry(String id, String key, Importer importer) {
    }

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final Object registering = new Object();
    private final ThrowLog throwLog;
    private final ErrorReports errors;

    private Importers(Logger logger, ErrorReports errors) {
        this.throwLog = new ThrowLog(logger);
        this.errors = errors;
    }

    /**
     * A registry without importers.
     *
     * @param logger the plugin's logger, which receives a warning when {@link Importer#present()} throws
     * @param errors receives what {@link Importer#present()} threw, under {@code importer.<id>} with the id in lower
     *     case
     * @return the registry
     * @throws NullPointerException if an argument is {@code null}
     * @since 0.4.15
     */
    public static Importers create(Logger logger, ErrorReports errors) {
        return new Importers(Objects.requireNonNull(logger, "logger"), Objects.requireNonNull(errors, "errors"));
    }

    /**
     * Adds an importer under its {@link Importer#id() id}, which is read once, here. An id that is already
     * registered, in any letter case, is refused and the registry keeps the importer it has: an id names one source.
     *
     * @param importer the importer
     * @return this registry
     * @throws NullPointerException if {@code importer} or its id is {@code null}
     * @throws IllegalArgumentException if the id is not 1 to 64 ASCII letters, digits, dots, underscores and
     *     hyphens, or if an importer is registered under it
     * @since 0.4.15
     */
    public Importers register(Importer importer) {
        Objects.requireNonNull(importer, "importer");
        String id = Objects.requireNonNull(importer.id(), "the importer's id");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("The importer id '" + id
                + "' is not 1 to 64 ASCII letters, digits, dots, underscores and hyphens");
        }
        String key = id.toLowerCase(Locale.ROOT);
        synchronized (registering) {
            for (Entry entry : entries) {
                if (entry.key().equals(key)) {
                    throw new IllegalArgumentException("An importer is already registered under the id '"
                        + entry.id() + "'");
                }
            }
            entries.add(new Entry(id, key, importer));
        }
        return this;
    }

    /**
     * The importer registered under an id, without regard to case, whether or not its source is present.
     *
     * @param id the id, as an owner typed it
     * @return the importer that was registered, or empty when none has this id
     * @throws NullPointerException if {@code id} is {@code null}
     * @since 0.4.15
     */
    public Optional<Importer> byId(String id) {
        String key = Objects.requireNonNull(id, "id").toLowerCase(Locale.ROOT);
        for (Entry entry : entries) {
            if (entry.key().equals(key)) {
                return Optional.of(entry.importer());
            }
        }
        return Optional.empty();
    }

    /**
     * The ids of the importers whose source is present, in the order they were registered, each as its importer gave
     * it when it was registered.
     *
     * <p>{@link Importer#present()} is called for every importer in each call, on the calling thread, and nothing is
     * cached, so a source that was installed or removed since the last call is seen. An importer whose
     * {@code present()} throws counts as absent: what it threw is logged as a warning, at most once a minute for
     * each importer with its stack the first time, and reported to the {@link ErrorReports} under
     * {@code importer.<id>}. An {@link Error} other than a {@link LinkageError} propagates.
     *
     * @return an unmodifiable copy
     * @since 0.4.15
     */
    public List<String> presentIds() {
        List<String> ids = new ArrayList<>();
        for (Entry entry : entries) {
            if (present(entry)) {
                ids.add(entry.id());
            }
        }
        return List.copyOf(ids);
    }

    /**
     * The ids of every registered importer, in the order they were registered, each as its importer gave it when it
     * was registered. No importer is called.
     *
     * @return an unmodifiable copy
     * @since 0.4.15
     */
    public List<String> allIds() {
        List<String> ids = new ArrayList<>();
        for (Entry entry : entries) {
            ids.add(entry.id());
        }
        return List.copyOf(ids);
    }

    private boolean present(Entry entry) {
        try {
            return entry.importer().present();
        } catch (Exception | LinkageError ex) {
            throwLog.warn(entry.key(), "The importer " + entry.id()
                + " could not tell whether its source is present, so it counts as absent (" + ex + ").", ex);
            errors.report(SOURCE + entry.key(), ex);
            return false;
        }
    }
}
