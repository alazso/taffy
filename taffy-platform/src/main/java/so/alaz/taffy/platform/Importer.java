package so.alaz.taffy.platform;

import java.util.concurrent.CompletableFuture;

/**
 * Brings the data of another plugin into the plugin that implements it: one implementation for each source, such as
 * one that reads CrazyVouchers files. The plugin registers its importers with {@link Importers} and runs one from its
 * import command.
 *
 * <p>The implementation holds everything the run needs: where the source data is, how it is read, and where the
 * result is written. Taffy calls none of these methods on its own. {@link Importers} calls {@link #id()} once when the
 * importer is registered and {@link #present()} in {@link Importers#presentIds()}; the plugin calls
 * {@link #displayName()} and {@link #run}.
 *
 * <p>A run never takes a backup. A plugin that wants one takes it before the real run, for example with
 * {@code Storage.backup} of {@code taffy-storage}, and calls {@code run(false)} once that future has completed.
 *
 * @since 0.4.15
 */
public interface Importer {

    /**
     * The id an owner types to name this source, such as {@code crazyvouchers}: 1 to 64 ASCII letters, digits, dots,
     * underscores and hyphens, written in lower case. Ids are matched without regard to case, so two importers of one
     * registry cannot differ in case only. The id is a constant: every call returns the same text, from any thread.
     *
     * @return the id
     * @since 0.4.15
     */
    String id();

    /**
     * The name of the source as its author writes it, such as {@code CrazyVouchers}, for the messages of the plugin.
     * It is plain text and a constant, returned from any thread.
     *
     * @return the name
     * @since 0.4.15
     */
    String displayName();

    /**
     * Whether the source has data to import from on this server, such as whether its folder exists.
     *
     * <p>The answer is read each time and is not cached, and a command suggests ids through it while an owner types,
     * so the call is cheap and does not block: it opens no database and parses no file. It may be called from any
     * thread, on Folia too, so it reads only what any thread may read, such as whether a folder exists or whether a
     * plugin is enabled, and no world, chunk or entity. It does not throw.
     *
     * @return {@code true} if a run would find data
     * @since 0.4.15
     */
    boolean present();

    /**
     * Reads the source and, unless this is a dry run, writes what it read into the plugin.
     *
     * <p>A dry run reads and checks the source as a real run does and writes nothing: no file, no row and no state of
     * the plugin changes, and its report says what a real run would do with the data as it is now. A run does not
     * change the source's own files or tables in either case.
     *
     * <p>The call may come from any thread, on Folia too, and returns without blocking: the importer moves its work
     * to the thread that work needs, an async thread for files and a database. It never throws. A run that could not
     * finish completes the future exceptionally, and a run that finished completes it with a report, never with
     * {@code null}; an entry that could not be imported is a skipped line or a warning of the report and does not
     * fail the run. A source that is not {@link #present() present} gives an exceptional future or a report that
     * says so in a warning.
     *
     * <p>The future completes on a thread the importer chooses, usually the one its work ran on. The caller moves to
     * the thread it needs before it touches the server, with {@link Scheduler#global} for the console or
     * {@link Scheduler#entity} for a player.
     *
     * @param dryRun {@code true} to write nothing
     * @return a future of the report of the run
     * @since 0.4.15
     */
    CompletableFuture<ImportReport> run(boolean dryRun);
}
