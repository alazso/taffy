package so.alaz.taffy.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.Classes;
import so.alaz.taffy.platform.ErrorReports;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A plugin's database: work runs in transactions on database threads, and the schema is brought up to date by
 * the plugin's {@link Migration}s when storage starts.
 *
 * <p>Storage owns its database threads. {@link #start} creates a fixed pool of
 * {@link StorageSettings#asyncThreads()} daemon threads named {@code <name>-db-1}, {@code <name>-db-2} and so on,
 * where the name is the plugin's {@link Plugin#getName()}, and {@link #close} stops them, so a plugin passes it no
 * executor. On SQLite storage from {@link #start}, writes run on one more thread of their own, named
 * {@code <name>-db-writer}, one at a time in the order they were submitted. Each piece of work runs in a transaction
 * of its own on one of those threads, with a connection of its own, so on MySQL, MariaDB and PostgreSQL writes run
 * at the same time as each other, as {@link #async} states. On SQLite, writes go through a pool of one
 * connection named {@code <name>-writer} and reads through a pool of read-only connections named
 * {@code <name>-reader}; on a server both go through one pool named {@code <name>}.
 *
 * <p>Every SQLite connection is opened with {@code journal_mode=WAL}, {@code synchronous=FULL},
 * {@code busy_timeout=5000} and {@code foreign_keys=true}, so foreign keys are enforced, and the read connections
 * also run {@code PRAGMA query_only = ON}. The writer connection is opened with {@code transaction_mode=IMMEDIATE},
 * so a write transaction takes the file's write lock when it begins: while another process holds that lock, work
 * that reads and then writes waits for it up to the busy timeout and then reads what that process wrote. A read
 * transaction begins deferred.
 *
 * <p>MySQL and MariaDB connections are opened with {@code useAffectedRows=true}, so an {@code UPDATE} counts only
 * the rows whose values it changed (see {@link Tx#update}). Every server connection has the socket timeout of
 * {@link StorageSettings.Server#socketTimeout()} during normal work, 30 seconds unless the plugin set another: a
 * statement that waits longer for the server, a lock wait included, fails and its connection is closed. Migrations
 * run without that timeout. The {@link StorageSettings.Server#properties()} are handed to the driver with every
 * server connection.
 *
 * <p>The future of a piece of work completes on a database thread after its transaction has ended and its
 * connection is back in the pool, so code chained onto the future never holds the connection or reaches an open
 * transaction. On SQLite storage from {@link #start}, the futures of writes and of the start complete on the read
 * threads, not on the writer thread, except for a write that ends while {@link #close} drains. A completion still
 * queued on the database threads when the drain of {@link #close} runs out runs on the thread that calls it.
 *
 * <p>Work, a task from {@link #executor()}, and a stage that runs on a database thread because it was chained
 * without an executor onto a future of this storage, hold that thread while they wait. A wait there for the future
 * of other work ends only once a thread named {@code <name>-db-N} is free to run that work or, for a write on
 * SQLite storage from {@link #start}, to complete its future. While every such thread is held by a wait of that
 * kind, none of those waits ends, so with one such thread ({@link StorageSettings#asyncThreads()} at 1, or storage
 * from {@link #over}) such a wait never ends. Work on the SQLite writer thread must not wait for another write at
 * all, as {@link #async} states.
 *
 * <p>Thread-safe: every method may be called from any thread, except that {@link #close} must not be called from a
 * database thread.
 */
public final class Storage {

    private static final String ERROR_SOURCE = "storage";

    private static final int ROLLBACK_ATTEMPTS = 4;

    private static final String HIKARI_CLASS = "com.zaxxer.hikari.HikariDataSource";

    private final Dialect dialect;
    private final String prefix;
    private final Logger logger;
    private final ErrorReports errors;
    private final ExecutorService executor;
    private final ExecutorService writes;
    private final DataSource writer;
    private final DataSource reader;
    private final List<HikariDataSource> pools;
    private final Clock clock;
    private final Set<Thread> threads;
    private final ThreadLocal<@Nullable ArrayDeque<Job>> handedOn = new ThreadLocal<>();
    private volatile boolean stopping;
    private volatile boolean poolsClosing;

    private Storage(Dialect dialect, String prefix, Logger logger, ErrorReports errors,
                    Workers workers, DataSource writer, DataSource reader, List<HikariDataSource> pools,
                    Clock clock) {
        this.dialect = dialect;
        this.prefix = prefix;
        this.logger = logger;
        this.errors = errors;
        this.executor = workers.reads();
        this.writes = workers.writes();
        this.threads = workers.threads();
        this.writer = writer;
        this.reader = reader;
        this.pools = pools;
        this.clock = clock;
    }

    /**
     * Opens storage and brings its schema up to date, on one of its new database threads: on SQLite, the writer
     * thread.
     *
     * <p>The plugin's name, version, data path and logger are read on the calling thread. HikariCP and the JDBC
     * driver of the dialect must be loadable from the class loader that loaded this class; both are looked up before
     * any connection pool is built. On SQLite the database is the file {@link StorageSettings#sqliteFile()} under the
     * plugin's data path, whose folders are created. A SQLite database that already holds a table and has a pending
     * migration is first copied into the {@code backups} folder of the data path, which keeps the newest
     * {@link StorageSettings#backupKeep()} copies. Every pending script is read and split into statements before
     * that copy and before the first migration runs.
     *
     * <p>When the start fails, the pools it built and the threads it created are closed. They are also closed when
     * the returned future was already completed when the start finished, for example by {@code cancel},
     * {@code orTimeout} or {@code completeOnTimeout}: the storage built then reaches no one, so a plugin that gives
     * up on a start has nothing to close.
     *
     * <p>The returned future completes on a database thread. On SQLite it completes on one of the read threads, so a
     * stage chained onto it without an executor never holds up the writer thread. Such a stage holds that read
     * thread instead, so a wait in it for other database work never ends while no other read thread is free, as the
     * class description states.
     *
     * @param plugin the owner plugin: {@link Plugin#getName()} names the database threads and the connection pools,
     *     and on a server the default version table, which must not change between releases, as
     *     {@link StorageSettings#versionTable(String)} describes, and must not be blank, the version of
     *     {@link Plugin#getPluginMeta()} is written into the file name of a SQLite backup,
     *     {@link Plugin#getDataPath()} holds the SQLite file and its backups,
     *     {@link Plugin#getLogger()} receives the lines for applied migrations and backups and the warnings, and
     *     {@link Plugin#getResource(String)} opens each migration script at a resource path such as
     *     {@code db/migration/sqlite/V1__accounts.sql}. Storage closes each stream it opens
     * @param errors receives the failures storage catches and handles: an action after a commit that throws, and
     *     a task handed to the database threads that throws while storage closes
     * @param settings what to connect to, the pool and the number of database threads
     * @param migrations the plugin's migrations, with strictly increasing versions
     * @param clock the clock of {@link Tx#now()} and of the times recorded for migrations and backups
     * @return a future that completes with the started storage, or completes exceptionally: with a
     *     {@link StorageException} when HikariCP or the driver is not available, a migration script is missing,
     *     unreadable, not valid UTF-8, cannot be split into statements or changed after it ran, a SQLite migration
     *     leaves a broken foreign key, the database was set up by a newer version of the plugin, the table it
     *     records migrations in exists in another layout, the connection names no current database (on PostgreSQL,
     *     no current schema), or another start held the migration lock for a day; with an {@link SQLException} when
     *     the database refuses a statement, whose message names the migration and the statement when the statement
     *     belongs to a migration script; or with what the connection pool throws when it cannot connect
     * @throws NullPointerException if an argument is {@code null}, or the plugin's name, version, data path or
     *     logger is {@code null}
     * @throws IllegalArgumentException if the plugin's name is blank, the versions of {@code migrations} are not
     *     strictly increasing, or {@link StorageSettings#asyncThreads()} is above
     *     {@link StorageSettings.Pool#maxSize()}
     */
    public static CompletableFuture<Storage> start(Plugin plugin, ErrorReports errors, StorageSettings settings,
                                                   List<Migration> migrations, Clock clock) {
        return begin(plugin, errors, settings, migrations, null, clock);
    }

    /**
     * Opens storage as {@link #start(Plugin, ErrorReports, StorageSettings, List, Clock)} does, and on a database
     * with no version table of storage's yet first takes it over from the version table of the plugin's own
     * migration runner, as {@link Baseline} describes. Once storage's version table exists, the baseline is ignored.
     *
     * @param plugin the owner plugin, as for {@link #start(Plugin, ErrorReports, StorageSettings, List, Clock)}
     * @param errors receives the failures storage catches and handles, as for
     *     {@link #start(Plugin, ErrorReports, StorageSettings, List, Clock)}
     * @param settings what to connect to, the pool and the number of database threads
     * @param migrations the plugin's migrations, with strictly increasing versions
     * @param baseline the old version table to take over
     * @param clock the clock of {@link Tx#now()} and of the times recorded for migrations and backups
     * @return a future that completes with the started storage, or completes exceptionally as the future of
     *     {@link #start(Plugin, ErrorReports, StorageSettings, List, Clock)} does, and also with a
     *     {@link StorageException} when the old version table records a version higher than the highest of
     *     {@code migrations}, has no {@code version} column, holds a version that is not a whole number of 0 or
     *     more, or records a version of 1 or more that no migration in {@code migrations} has
     * @throws NullPointerException if an argument is {@code null}, or the plugin's name, version, data path or
     *     logger is {@code null}
     * @throws IllegalArgumentException if the plugin's name is blank, the versions of {@code migrations} are not
     *     strictly increasing, or {@link StorageSettings#asyncThreads()} is above
     *     {@link StorageSettings.Pool#maxSize()}
     */
    public static CompletableFuture<Storage> start(Plugin plugin, ErrorReports errors, StorageSettings settings,
                                                   List<Migration> migrations, Baseline baseline, Clock clock) {
        Objects.requireNonNull(baseline, "baseline");
        return begin(plugin, errors, settings, migrations, baseline, clock);
    }

    private static CompletableFuture<Storage> begin(Plugin plugin, ErrorReports errors, StorageSettings settings,
                                                    List<Migration> migrations, @Nullable Baseline baseline,
                                                    Clock clock) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(errors, "errors");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(migrations, "migrations");
        Objects.requireNonNull(clock, "clock");
        String name = requireName(plugin.getName());
        String version = Objects.requireNonNull(plugin.getPluginMeta().getVersion(), "version");
        Path dataFolder = Objects.requireNonNull(plugin.getDataPath(), "dataPath");
        Logger logger = Objects.requireNonNull(plugin.getLogger(), "logger");
        int maxSize = settings.pool().maxSize();
        if (settings.asyncThreads() > maxSize) {
            throw new IllegalArgumentException("asyncThreads is " + settings.asyncThreads() + ", above the pool's"
                + " maxSize of " + maxSize + "; each database thread needs a connection, so raise maxSize or lower"
                + " asyncThreads");
        }
        Migrator migrator = new Migrator(settings.dialect(), settings.tablePrefix(), settings.versionTableFor(name),
            clock, logger, migrations, plugin::getResource, baseline, Migrator.LOCK_WAIT);
        Workers workers = workers(name, settings.asyncThreads(), settings.dialect() == Dialect.SQLITE);
        CompletableFuture<Storage> started = new CompletableFuture<>();
        workers.writes().execute(() -> {
            List<HikariDataSource> pools = new ArrayList<>();
            try {
                requireHikari(Storage.class, logger);
                requireDriver(settings.dialect(), Storage.class, logger);
                Storage storage = switch (settings.dialect()) {
                    case SQLITE -> sqlite(settings, dataFolder, name, logger, errors, workers, pools, clock);
                    case MYSQL, MARIADB, POSTGRES -> server(settings, name, logger, errors, workers, pools, clock);
                };
                Migrator.Backup backup = settings.dialect() == Dialect.SQLITE
                    ? new SqliteBackup(dataFolder.resolve("backups"), version, settings.backupKeep(), clock, logger)
                    : null;
                try (Connection connection = storage.writer.getConnection()) {
                    migrator.migrate(connection, backup);
                }
                settle(workers.reads(), workers.writes(), () -> {
                    if (!started.complete(storage)) {
                        try {
                            closeAll(pools);
                        } catch (Throwable unclosed) {
                            storage.log(Level.WARNING, "Could not close the connection pools of a start whose"
                                + " future was already completed", unclosed);
                        } finally {
                            workers.shutdown();
                        }
                    }
                });
            } catch (Throwable failure) {
                try {
                    closeAll(pools);
                } catch (Throwable unclosed) {
                    failure.addSuppressed(unclosed);
                }
                settle(workers.reads(), workers.writes(), () -> started.completeExceptionally(failure));
                workers.shutdown();
            }
        });
        return started;
    }

    /**
     * Storage over a data source the caller owns, for tests that control the connections.
     *
     * <p>Work runs on {@code source}, one transaction at a time, on a single database thread named
     * {@code <name>-db-1}, where the name is the plugin's {@link Plugin#getName()}, that this storage owns. Reads and
     * writes both take their connection from {@code source}, each piece of work a connection of its own, so on SQLite
     * a read is not made read-only and a write waits for no {@link StorageSettings.Pool#connectionTimeout()}. No
     * migration runs and no connection pool is built. {@link #close} stops the thread and leaves {@code source}
     * open: work that outlives the drain closes only the connection it took from {@code source}, when it ends.
     *
     * <p>A plugin pairs it with {@code FaultyDataSource} from taffy-test to make statements and commits fail on
     * demand. The wiring lives in the plugin's own tests: they bring the schema up to date with {@link #start},
     * close that storage, and open this one over a {@code FaultyDataSource} on the same database. Its connections
     * carry only the properties the test passes, so a SQLite test that should behave as {@link #start} does passes
     * {@code journal_mode=WAL}, {@code synchronous=FULL}, {@code busy_timeout=5000}, {@code foreign_keys=true} and
     * {@code transaction_mode=IMMEDIATE}.
     *
     * @param plugin the owner plugin: {@link Plugin#getName()} names the database thread and must not be blank,
     *     and {@link Plugin#getLogger()} receives the warnings
     * @param errors receives the failures storage catches and handles, as for {@link #start}
     * @param dialect the database {@code source} connects to
     * @param source where every connection comes from
     * @param prefix the table prefix of {@link #table}, possibly empty: up to 32 lowercase letters, digits and
     *     underscores starting with a letter or underscore
     * @param clock the clock of {@link Tx#now()}
     * @return the storage, ready for work
     * @throws NullPointerException if an argument is {@code null}, or the plugin's name or logger is {@code null}
     * @throws IllegalArgumentException if the plugin's name is blank or the prefix is out of its range
     */
    public static Storage over(Plugin plugin, ErrorReports errors, Dialect dialect, DataSource source, String prefix,
                               Clock clock) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(errors, "errors");
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(source, "source");
        StorageSettings.requirePrefix(prefix);
        Objects.requireNonNull(clock, "clock");
        String name = requireName(plugin.getName());
        Logger logger = Objects.requireNonNull(plugin.getLogger(), "logger");
        return new Storage(dialect, prefix, logger, errors, workers(name, 1, false), source, source, List.of(),
            clock);
    }

    /**
     * The database this storage runs on.
     *
     * @return the dialect
     */
    public Dialect dialect() {
        return dialect;
    }

    /**
     * The full name of a table: the table prefix followed by the name.
     *
     * @param name the table name without the prefix
     * @return the prefixed name, to concatenate into SQL
     */
    public String table(String name) {
        return prefix + name;
    }

    /**
     * The way onto the database threads, for a task that uses no connection, such as completing a future on a
     * database thread. A task runs on any of the database threads, {@link StorageSettings#asyncThreads()} of them
     * for storage from {@link #start} and one for storage from {@link #over}, in no order relative to the database
     * work queued before it; on SQLite storage from {@link #start} its tasks run on the read threads. Once
     * {@link #close} has begun it refuses new tasks with {@link RejectedExecutionException}.
     *
     * @return the executor of the database threads
     */
    public Executor executor() {
        return executor;
    }

    /**
     * Runs work that may write, in one transaction on a database thread. On SQLite storage from {@link #start} it
     * runs on the one writer thread, in the order writes were submitted, through the single writer connection: a
     * write queued behind a long write waits in that queue, not for a connection, and reads keep running on the
     * other database threads. A write handed on from a read thread while {@link #close} drains is the exception:
     * it runs on that read thread. Storage from {@link #over} takes the connection from its source.
     *
     * <p>On SQLite storage from {@link #start}, the returned future completes on one of the read threads, so a
     * dependent stage added without an executor never holds up the writer thread. That completion waits its turn on
     * the read threads: while every read thread is held by work or a stage that waits for a write, the future of
     * that write does not complete. With more than one read thread, the futures of two writes may complete in
     * another order than the writes, which still commit in the order they were submitted. The work itself must not
     * wait for another write: that write queues behind it on the one writer thread and never runs.
     *
     * <p>On MySQL, MariaDB and PostgreSQL, writes run at the same time on up to
     * {@link StorageSettings#asyncThreads()} threads, each in a transaction on a connection of its own, in no order
     * relative to each other. Work that reads a row and then writes a value computed from what it read is correct
     * on SQLite, where writes run one at a time, and loses an update on a server, where another write may change
     * the row between the two statements. Such work either writes with a single conditional or relative
     * {@code UPDATE}, such as {@code SET balance = balance + ?} or {@code WHERE id = ? AND state = ?}, or selects
     * the row with {@link Dialect#forUpdate()}, which holds the row until the transaction ends.
     *
     * <p>On MySQL and MariaDB a transaction reads from the snapshot its first select took
     * ({@code REPEATABLE READ}), so a select without {@link Dialect#forUpdate()} that runs again inside one piece
     * of work sees the same rows however often it runs, whatever other transactions committed meanwhile. A loop
     * that reads, computes and writes with a condition until its write counts a row therefore runs one piece of
     * work per attempt, or reads with {@link Dialect#forUpdate()}, which reads the newest committed row.
     *
     * <p>The transaction commits when the work returns and rolls back when it throws. A commit the database refuses
     * because it rolled the transaction back, which is an {@link SQLTransactionRollbackException} or an
     * {@link SQLException} whose SQLState, or that of a cause, starts with {@code 40}, completes the future with that
     * exception: nothing was written. Any other failure of the commit statement itself, and one with SQLState
     * {@code 40003} (statement completion unknown), completes the future with a {@link CommitUnknownException}.
     *
     * @param <T> the type of the result
     * @param work the work
     * @return a future that completes with the result once the transaction has committed and its
     *     {@link Tx#afterCommit} actions have run, or completes exceptionally with what the work or the database
     *     threw, or with {@link RejectedExecutionException} when storage is closed
     * @throws NullPointerException if the work is {@code null}
     */
    public <T> CompletableFuture<T> async(TxWork<T> work) {
        return submit(writer, writes, work, 1);
    }

    /**
     * Runs work that only reads, in one transaction on a database thread. On SQLite storage from {@link #start} it
     * runs on the read threads and goes through the read-only connections, which refuse a write and run beside the
     * writer; on a server it shares the writer's pool. Storage from {@link #over} takes the connection from its source
     * and does not make it read-only.
     *
     * <p>The returned future completes on the database thread that ran the work, where a stage chained onto it
     * without an executor then runs and holds that thread: a wait in it for other database work never ends while no
     * other read thread is free, as the class description states.
     *
     * @param <T> the type of the result
     * @param work the work
     * @return a future that completes as the one of {@link #async} does
     * @throws NullPointerException if the work is {@code null}
     */
    public <T> CompletableFuture<T> read(TxWork<T> work) {
        return submit(reader, executor, work, 1);
    }

    /**
     * Runs work that may write as {@link #async} does, and runs it again when the database rolled its transaction
     * back to break a deadlock or a serialization conflict, up to 4 attempts in all. On SQLite storage from
     * {@link #start}, the returned future completes on one of the read threads, so a dependent stage added without
     * an executor never holds up the writer thread. The work must not wait for another write, as for {@link #async}.
     *
     * <p>A transaction counts as rolled back by the database when the work, a statement or the commit threw an
     * {@link SQLException} that is, or has as a cause, an {@link SQLTransactionRollbackException} or an exception
     * whose SQLState starts with {@code 40}. A commit that fails so, such as the commit of a PostgreSQL
     * {@code SERIALIZABLE} transaction that fails with SQLState {@code 40001}, is therefore run again. Every attempt
     * has a connection and a {@link Tx} of its own, so the {@link Tx#afterCommit} actions of an attempt that rolled
     * back never run. A {@link CommitUnknownException} is never retried, whatever its SQLState, because the
     * transaction may have been written, and a commit that fails with SQLState {@code 40003} (statement completion
     * unknown) is one.
     *
     * <p>The work may therefore run more than once. Each run must be safe to repeat: an effect outside the database
     * belongs in {@link Tx#afterCommit}, which runs only for the attempt that committed.
     *
     * <p>Before attempt {@code n + 1} the database thread sleeps a random 5n to 20n milliseconds. The sleep holds
     * that database thread, so other work queued on the database threads waits longer. On SQLite the pause holds
     * every write queued behind it. When the thread is interrupted during the sleep, the future completes
     * exceptionally with an {@link SQLException} and the thread's interrupt flag is set again.
     *
     * @param <T> the type of the result
     * @param work the work, which may run more than once
     * @return a future that completes as the one of {@link #async} does, with the failure of the last attempt when
     *     no attempt committed
     * @throws NullPointerException if the work is {@code null}
     */
    public <T> CompletableFuture<T> asyncRetrying(TxWork<T> work) {
        return submit(writer, writes, work, ROLLBACK_ATTEMPTS);
    }

    /**
     * Runs work that only reads as {@link #read} does, and runs it again when the database rolled its transaction
     * back, under the same rules and with the same pause as {@link #asyncRetrying}.
     *
     * @param <T> the type of the result
     * @param work the work, which may run more than once
     * @return a future that completes as the one of {@link #asyncRetrying} does
     * @throws NullPointerException if the work is {@code null}
     */
    public <T> CompletableFuture<T> readRetrying(TxWork<T> work) {
        return submit(reader, executor, work, ROLLBACK_ATTEMPTS);
    }

    /**
     * Stops the database threads and closes the connection pools, waiting on the calling thread for the work
     * already queued to finish.
     *
     * <p>New work is refused from the moment this method begins, except work that queued or running work submits
     * from a database thread, such as a stage chained onto the future of work. That work still runs within the
     * drain, on the submitting thread, after the work that submitted it has ended, in the order it was submitted.
     * On SQLite storage from {@link #start}, a write handed on from a read thread so runs on that read thread,
     * beside the writes still draining on the writer thread: it can commit before writes submitted earlier, and it
     * waits for the writer connection, failing with an {@link SQLException} when that wait passes
     * {@link StorageSettings.Pool#connectionTimeout()}.
     *
     * <p>From the moment this method begins, the read threads also refuse the completion of a write, so on SQLite
     * storage from {@link #start} the future of a write that ends during the drain completes on the thread that ran
     * it, the writer thread or, for a write handed on from a read thread, that read thread, and a stage chained onto
     * it without an executor runs there. Such a stage delays the work still draining on that thread, and a write it
     * submits runs only after it ends, so it must not wait for that write.
     *
     * <p>When the drain runs out, the database threads are interrupted. Work still queued fails with
     * {@link RejectedExecutionException}, as does work still waiting for a connection, and any other task queued on
     * the database threads, such as one from {@link #executor()} or the completion of a write, runs on the closing
     * thread. Work still running sees the interrupt, so a wait
     * inside it may end early with an {@link InterruptedException}. On SQLite such work keeps its connection until
     * it ends. On MySQL, MariaDB and PostgreSQL the pool aborts the connections in use, so such work fails with an
     * {@link SQLException}, or with a {@link CommitUnknownException} when the abort meets its commit. A warning
     * then names the number of queued pieces of work that did not run.
     *
     * <p>{@code close} does not wait for work still running after the interrupt: it returns once the pools are
     * closed, and that work ends afterwards on its database thread, a daemon thread, where its future then
     * completes. Work that catches the {@link InterruptedException} should end rather than wait again, because the
     * plugin is disabling.
     *
     * <p>Work that must be written before the plugin disables, such as the writes its pending settlements queue,
     * is queued before this method is called and is covered by the drain: a result of {@code false} means that
     * some of it may not have run.
     *
     * <p>It must not be called from a database thread, such as in a stage chained onto the future of work: the
     * drain would wait on the calling thread itself, so it runs out every time.
     *
     * @param drain how long to wait for the queued work; a negative drain waits no time, and a drain of
     *     {@link Long#MAX_VALUE} nanoseconds or more, such as {@code ChronoUnit.FOREVER.getDuration()}, waits with
     *     no limit
     * @return {@code true} if every queued piece of work finished within the drain
     * @throws NullPointerException if the drain is {@code null}
     */
    public boolean close(Duration drain) {
        Objects.requireNonNull(drain, "drain");
        executor.shutdown();
        writes.shutdown();
        long budget = drain.isNegative() ? 0
            : drain.compareTo(Duration.ofNanos(Long.MAX_VALUE)) >= 0 ? Long.MAX_VALUE : drain.toNanos();
        long begun = System.nanoTime();
        boolean drained;
        try {
            drained = writes.awaitTermination(budget, TimeUnit.NANOSECONDS)
                && executor.awaitTermination(budget - (System.nanoTime() - begun), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            drained = false;
        }
        if (!drained) {
            stopping = true;
            List<Runnable> unrun = new ArrayList<>(writes.shutdownNow());
            unrun.addAll(executor.shutdownNow());
            int refused = 0;
            for (Runnable queued : unrun) {
                if (queued instanceof Job job) {
                    job.result.completeExceptionally(
                        new RejectedExecutionException("storage closed before this work ran"));
                    refused++;
                    continue;
                }
                // Any other task is a hand-off onto these threads, such as a future's completion, and uses no
                // connection, so it runs on the closing thread.
                try {
                    queued.run();
                } catch (Throwable broken) {
                    handled("A task queued on the database threads failed as they closed", broken);
                }
            }
            log(Level.WARNING, "Storage closed before its database work finished within "
                + TimeUnit.NANOSECONDS.toMillis(budget) + " ms; " + refused
                + (refused == 1 ? " queued piece" : " queued pieces") + " of work did not run.", null);
        }
        poolsClosing = true;
        closeAll(pools);
        return drained;
    }

    // Called before any HikariCP type is touched: a missing HikariCP then fails the future of start instead of
    // throwing NoClassDefFoundError on a database thread.
    static void requireHikari(Class<?> caller, Logger logger) throws StorageException {
        if (!Classes.present(HIKARI_CLASS, caller, logger)) {
            throw new StorageException("HikariCP is not available");
        }
    }

    static void requireDriver(Dialect dialect, Class<?> caller, Logger logger) throws StorageException {
        if (!Classes.present(dialect.driverClassName(), caller, logger)) {
            throw new StorageException("the " + dialect.id() + " driver is not available");
        }
    }

    static String serverUrl(Dialect dialect, StorageSettings.Server server) {
        String address = server.host() + ":" + server.port() + "/" + server.database();
        StorageSettings.SslMode ssl = server.sslMode();
        // useAffectedRows makes an UPDATE count only the rows it changed, so a conditional update must change a
        // column, and an insert that meets an existing key counts as 0. socketTimeout ends a call on a dead
        // connection. Without TLS, which PREFER may end in, MySQL 8's caching_sha2_password needs the server key,
        // which allowPublicKeyRetrieval lets the driver fetch.
        Duration socketTimeout = server.socketTimeout();
        String publicKey = ssl == StorageSettings.SslMode.DISABLE || ssl == StorageSettings.SslMode.PREFER
            ? "&allowPublicKeyRetrieval=true" : "";
        return switch (dialect) {
            case MYSQL -> {
                String mode = switch (ssl) {
                    case DISABLE -> "DISABLED";
                    case PREFER -> "PREFERRED";
                    case TRUST -> "REQUIRED";
                    case VERIFY_FULL -> "VERIFY_IDENTITY";
                };
                yield "jdbc:mysql://" + address + "?sslMode=" + mode + "&useAffectedRows=true&socketTimeout="
                    + socketTimeout.toMillis() + publicKey;
            }
            case MARIADB -> {
                // The MariaDB driver has no mode that encrypts without a certificate check only when the server
                // offers TLS: its allow mode checks the certificate and the host name.
                String mode = switch (ssl) {
                    case DISABLE, PREFER -> "disable";
                    case TRUST -> "trust";
                    case VERIFY_FULL -> "verify-full";
                };
                yield "jdbc:mariadb://" + address + "?sslMode=" + mode + "&useAffectedRows=true&socketTimeout="
                    + socketTimeout.toMillis() + publicKey;
            }
            case POSTGRES -> {
                String mode = switch (ssl) {
                    case DISABLE -> "disable";
                    case PREFER -> "prefer";
                    case TRUST -> "require";
                    case VERIFY_FULL -> "verify-full";
                };
                // pgjdbc reads socketTimeout in seconds. Its default socket factory checks a certificate only
                // against a root.crt file in the user's home; DefaultJavaSSLFactory checks it against the JVM trust
                // store, as the other drivers do, and verify-full still checks the host name.
                String factory = ssl == StorageSettings.SslMode.VERIFY_FULL
                    ? "&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory" : "";
                yield "jdbc:postgresql://" + address + "?sslmode=" + mode + factory + "&socketTimeout="
                    + socketTimeout.toSeconds();
            }
            case SQLITE -> throw new IllegalArgumentException("sqlite connects to a file, not a server");
        };
    }

    // Runs its body, then the work handed on from this thread while storage closes, one after another on this
    // stack frame, so a chain of such work neither nests inside the transaction that submitted it nor deepens the
    // stack.
    private final class Job implements Runnable {

        private final CompletableFuture<?> result;
        private final Runnable body;

        Job(CompletableFuture<?> result, Runnable body) {
            this.result = result;
            this.body = body;
        }

        @Override
        public void run() {
            ArrayDeque<Job> queue = new ArrayDeque<>();
            handedOn.set(queue);
            try {
                for (Job next = this; next != null; next = queue.poll()) {
                    next.body.run();
                }
            } finally {
                handedOn.remove();
            }
        }
    }

    private <T> CompletableFuture<T> submit(DataSource source, ExecutorService service, TxWork<T> work,
                                            int attempts) {
        Objects.requireNonNull(work, "work");
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable body = () -> {
            Attempt<T> outcome = attempt(source, work);
            for (int attempt = 1; attempt < attempts && outcome.failure() instanceof SQLException failure
                && rolledBack(failure); attempt++) {
                try {
                    pauseBeforeRetry(attempt);
                } catch (SQLException interrupted) {
                    interrupted.addSuppressed(failure);
                    outcome = new Attempt<>(null, interrupted, List.of());
                    break;
                }
                outcome = attempt(source, work);
            }
            try {
                for (Runnable action : outcome.actions()) {
                    try {
                        action.run();
                    } catch (Throwable broken) {
                        handled("An action after a database commit failed", broken);
                    }
                }
            } finally {
                Attempt<T> settled = outcome;
                settle(executor, service, () -> {
                    if (settled.failure() == null) {
                        result.complete(settled.value());
                    } else {
                        result.completeExceptionally(settled.failure());
                    }
                });
            }
        };
        Job job = new Job(result, body);
        try {
            service.execute(job);
        } catch (RejectedExecutionException closed) {
            // Work submitted from a database thread during the shutdown drain runs on that thread, so the drain
            // covers it; anything else submitted after close began is refused.
            if (threads.contains(Thread.currentThread()) && !poolsClosing) {
                ArrayDeque<Job> queue = handedOn.get();
                if (queue == null) {
                    job.run();
                } else {
                    queue.add(job);
                }
            } else {
                result.completeExceptionally(closed);
            }
        }
        return result;
    }

    // Completes a future whose work ran on an executor other than the read threads from a read thread instead, so a
    // dependent stage added without an executor runs there. When the work ran on the read threads, or the read
    // threads refuse the completion because storage is closing, the thread that ran the work completes it.
    private static void settle(ExecutorService reads, ExecutorService ran, Runnable completion) {
        if (ran == reads) {
            completion.run();
            return;
        }
        try {
            reads.execute(completion);
        } catch (RejectedExecutionException closing) {
            completion.run();
        }
    }

    private record Attempt<T>(@Nullable T value, @Nullable Throwable failure, List<Runnable> actions) {
    }

    private <T> Attempt<T> attempt(DataSource source, TxWork<T> work) {
        Connection connection;
        try {
            connection = source.getConnection();
        } catch (Throwable unavailable) {
            // Once the drain has run out, a wait for a connection ends with the interrupt or the closed pool, and
            // the work has not run.
            Throwable failure = stopping
                ? new RejectedExecutionException("storage closed before this work ran", unavailable)
                : unavailable;
            return new Attempt<>(null, failure, List.of());
        }
        T value = null;
        Throwable failure = null;
        boolean committed = false;
        List<Runnable> actions = List.of();
        try (connection) {
            try {
                connection.setAutoCommit(false);
                Tx tx = new Tx(connection, dialect, prefix, clock.instant());
                actions = tx.committedActions();
                try {
                    value = work.run(tx);
                } catch (Throwable thrown) {
                    rollback(connection, thrown);
                    throw thrown;
                } finally {
                    tx.close();
                }
                try {
                    connection.commit();
                    committed = true;
                } catch (SQLException refused) {
                    SQLException outcome = rolledBack(refused) && !completionUnknown(refused) ? refused
                        : new CommitUnknownException(refused);
                    rollback(connection, outcome);
                    throw outcome;
                }
            } finally {
                if (poolsClosing && !pools.isEmpty()) {
                    closeBehindPool(connection);
                }
            }
        } catch (Throwable thrown) {
            if (!committed) {
                failure = thrown;
            }
        }
        return committed ? new Attempt<>(value, null, actions) : new Attempt<>(null, failure, List.of());
    }

    /**
     * Whether the database rolled a transaction back for a deadlock or a serialization failure. SQLState class 40
     * covers both ({@code 40001} on MySQL and MariaDB, {@code 40P01} on PostgreSQL), and a driver may wrap it, so
     * the causes are checked too. A {@link CommitUnknownException} copies the SQLState of the failed commit, so it
     * is recognised by its type first and never counts as rolled back; a failed commit that counts as rolled back
     * is never wrapped in one. A cause chain that loops ends at the first
     * exception seen twice.
     */
    static boolean rolledBack(SQLException ex) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable error = ex; error != null && seen.add(error); error = error.getCause()) {
            if (error instanceof CommitUnknownException) {
                return false;
            }
            if (error instanceof SQLTransactionRollbackException) {
                return true;
            }
            if (error instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.startsWith("40")) {
                    return true;
                }
            }
        }
        return false;
    }

    // SQLState 40003 is "statement completion unknown": the commit may have been written.
    private static boolean completionUnknown(SQLException ex) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable error = ex; error != null && seen.add(error); error = error.getCause()) {
            if (error instanceof SQLException sql && "40003".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static void pauseBeforeRetry(int attempt) throws SQLException {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(5L * attempt, 20L * attempt + 1));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted while waiting to restart a rolled back transaction", interrupted);
        }
    }

    private void handled(String message, Throwable failure) {
        log(Level.WARNING, message, failure);
        errors.report(ERROR_SOURCE, failure);
    }

    private void log(Level level, String message, @Nullable Throwable failure) {
        try {
            logger.log(level, message, failure);
        } catch (Throwable broken) {
            // A log handler that throws must not stop the actions or tasks that follow.
        }
    }

    private static Storage sqlite(StorageSettings settings, Path dataFolder, String name, Logger logger,
                                  ErrorReports errors, Workers workers, List<HikariDataSource> pools, Clock clock)
        throws IOException {
        Path file = dataFolder.resolve(settings.sqliteFile()).toAbsolutePath().normalize();
        Files.createDirectories(file.getParent());
        String url = "jdbc:sqlite:" + file;
        Properties pragmas = new Properties();
        pragmas.setProperty("journal_mode", "WAL");
        pragmas.setProperty("synchronous", "FULL");
        pragmas.setProperty("busy_timeout", "5000");
        pragmas.setProperty("foreign_keys", "true");
        // BEGIN IMMEDIATE takes the write lock as the transaction begins and waits for it up to the busy timeout. A
        // deferred transaction that read before another process committed fails at once when it then writes.
        Properties writing = new Properties();
        writing.putAll(pragmas);
        writing.setProperty("transaction_mode", "IMMEDIATE");
        StorageSettings.Pool pool = settings.pool();
        HikariDataSource writer = pool(name + "-writer", Dialect.SQLITE.driverClassName(), url, writing, 1, 1,
            pool.connectionTimeout(), null, null, null);
        pools.add(writer);
        HikariDataSource reader = pool(name + "-reader", Dialect.SQLITE.driverClassName(), url, pragmas,
            pool.maxSize(), pool.minIdle(), pool.connectionTimeout(), null, null, "PRAGMA query_only = ON");
        pools.add(reader);
        return new Storage(Dialect.SQLITE, "", logger, errors, workers, writer, reader, List.copyOf(pools),
            clock);
    }

    private static Storage server(StorageSettings settings, String name, Logger logger, ErrorReports errors,
                                  Workers workers, List<HikariDataSource> pools, Clock clock) {
        Dialect dialect = settings.dialect();
        StorageSettings.Server server = Objects.requireNonNull(settings.server());
        StorageSettings.Pool pool = settings.pool();
        Properties properties = new Properties();
        properties.putAll(server.properties());
        HikariDataSource shared = pool(name, dialect.driverClassName(), serverUrl(dialect, server), properties,
            pool.maxSize(), pool.minIdle(), pool.connectionTimeout(), server.user(), server.password(), null);
        pools.add(shared);
        return new Storage(dialect, server.tablePrefix(), logger, errors, workers, shared, shared,
            List.copyOf(pools), clock);
    }

    private static HikariDataSource pool(String name, String driver, String url, Properties properties, int maxSize,
                                         int minIdle, Duration timeout, @Nullable String user,
                                         @Nullable String password, @Nullable String initSql) {
        HikariConfig config = new HikariConfig();
        if (initSql != null) {
            config.setConnectionInitSql(initSql);
        }
        config.setPoolName(name);
        config.setDriverClassName(driver);
        config.setJdbcUrl(url);
        config.setDataSourceProperties(properties);
        config.setMaximumPoolSize(maxSize);
        config.setMinimumIdle(Math.min(minIdle, maxSize));
        config.setConnectionTimeout(timeout.toMillis());
        if (user != null) {
            config.setUsername(user);
            config.setPassword(password);
        }
        return new HikariDataSource(config);
    }

    // A loop, unlike a method reference, resolves HikariDataSource only when there is a pool to close.
    private static void closeAll(List<HikariDataSource> pools) {
        for (HikariDataSource pool : pools) {
            pool.close();
        }
    }

    // Hikari closes a pool by aborting the connections in use and dropping them, and the SQLite driver's abort does
    // nothing, so work that outlives the drain closes the connection behind the pool itself.
    private static void closeBehindPool(Connection connection) {
        try {
            connection.unwrap(Connection.class).close();
        } catch (SQLException unclosable) {
            // A connection that cannot be closed here stays open until the server stops.
        }
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static String requireName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name is blank; it names the database threads and pools");
        }
        return name;
    }

    // writes is the reads executor itself unless writes have a thread of their own.
    private record Workers(ExecutorService reads, ExecutorService writes, Set<Thread> threads) {

        void shutdown() {
            reads.shutdown();
            writes.shutdown();
        }
    }

    private static Workers workers(String name, int count, boolean ownWriter) {
        AtomicInteger counter = new AtomicInteger();
        Set<Thread> threads = ConcurrentHashMap.newKeySet();
        ExecutorService reads = Executors.newFixedThreadPool(count,
            task -> thread(task, name + "-db-" + counter.incrementAndGet(), threads));
        ExecutorService writes = ownWriter
            ? Executors.newSingleThreadExecutor(task -> thread(task, name + "-db-writer", threads))
            : reads;
        return new Workers(reads, writes, threads);
    }

    private static Thread thread(Runnable task, String name, Set<Thread> threads) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        threads.add(thread);
        return thread;
    }
}
