package so.alaz.taffy.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.Classes;
import so.alaz.taffy.platform.ErrorReports;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A plugin's database: work runs in transactions on database threads, and the schema is brought up to date by
 * the plugin's {@link Migration}s when storage starts.
 *
 * <p>Storage owns its database threads. {@link #start} creates a fixed pool of
 * {@link StorageSettings#asyncThreads()} daemon threads named {@code <name>-db-1}, {@code <name>-db-2} and so on,
 * and {@link #close} stops them, so a plugin passes it no executor. Each piece of work runs in a transaction of its
 * own on one of those threads, with a connection of its own. On SQLite, writes go through a pool of one connection
 * named {@code <name>-writer} and reads through a pool of read-only connections named {@code <name>-reader}; on a
 * server both go through one pool named {@code <name>}.
 *
 * <p>Every SQLite connection is opened with {@code journal_mode=WAL}, {@code synchronous=FULL},
 * {@code busy_timeout=5000} and {@code foreign_keys=true}, so foreign keys are enforced, and the read connections
 * also run {@code PRAGMA query_only = ON}. A SQLite transaction begins deferred: work that reads and then writes,
 * while another process holds the file's write lock, fails at once with {@code SQLITE_BUSY} instead of waiting for
 * the busy timeout, and {@link #asyncRetrying} does not retry it.
 *
 * <p>MySQL and MariaDB connections are opened with {@code useAffectedRows=true}, so an {@code UPDATE} counts only
 * the rows whose values it changed (see {@link Tx#update}). Every server connection has a socket timeout of 30
 * seconds during normal work: a statement that waits longer for the server, a lock wait included, fails and its
 * connection is closed. Migrations run without that timeout.
 *
 * <p>The future of a piece of work completes on the database thread after its transaction has ended and its
 * connection is back in the pool, so code chained onto the future never holds the connection or reaches an open
 * transaction.
 *
 * <p>Thread-safe: every method may be called from any thread, except that {@link #close} must not be called from a
 * database thread.
 */
public final class Storage {

    private static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(30);

    private static final String ERROR_SOURCE = "storage";

    private static final int ROLLBACK_ATTEMPTS = 4;

    private static final String HIKARI_CLASS = "com.zaxxer.hikari.HikariDataSource";

    private final Dialect dialect;
    private final String prefix;
    private final Logger logger;
    private final ErrorReports errors;
    private final ExecutorService executor;
    private final DataSource writer;
    private final DataSource reader;
    private final List<HikariDataSource> pools;
    private final Clock clock;
    private final Set<Thread> threads;
    private final ThreadLocal<@Nullable ArrayDeque<Job>> handedOn = new ThreadLocal<>();
    private volatile boolean stopping;
    private volatile boolean poolsClosing;

    private Storage(Dialect dialect, String prefix, Logger logger, ErrorReports errors,
                    Workers executor, DataSource writer, DataSource reader, List<HikariDataSource> pools,
                    Clock clock) {
        this.dialect = dialect;
        this.prefix = prefix;
        this.logger = logger;
        this.errors = errors;
        this.executor = executor.service();
        this.threads = executor.threads();
        this.writer = writer;
        this.reader = reader;
        this.pools = pools;
        this.clock = clock;
    }

    /**
     * Opens storage and brings its schema up to date, on one of its new database threads.
     *
     * <p>HikariCP and the JDBC driver of the dialect must be loadable from the class loader that loaded this
     * class; both are looked up before any connection pool is built. On SQLite the database is the file
     * {@link StorageSettings#sqliteFile()} under {@code dataFolder}, whose folders are created. A SQLite database
     * that already holds a table and has a pending migration is first copied into {@code dataFolder/backups}, which
     * keeps the newest {@link StorageSettings#backupKeep()} copies. Every pending script is read and split into
     * statements before that copy and before the first migration runs.
     *
     * <p>When the start fails, the pools it built and the threads it created are closed. They are also closed when
     * the returned future was already completed when the start finished, for example by {@code cancel},
     * {@code orTimeout} or {@code completeOnTimeout}: the storage built then reaches no one, so a plugin that gives
     * up on a start has nothing to close.
     *
     * @param settings what to connect to, the pool and the number of database threads
     * @param dataFolder the plugin's data folder, which holds the SQLite file and its backups
     * @param name the name the database threads and the connection pools carry, such as the plugin's name;
     *     not blank
     * @param pluginVersion the plugin's version, written into the file name of a SQLite backup
     * @param migrations the plugin's migrations, with strictly increasing versions
     * @param scripts opens the migration script at a resource path such as
     *     {@code db/migration/sqlite/V1__accounts.sql}, or returns {@code null} when there is none; a plugin passes
     *     {@code plugin::getResource}. Storage closes each stream it opens
     * @param logger receives the lines for applied migrations and backups, and the warnings
     * @param errors receives the failures storage catches and handles: an action after a commit that throws, and
     *     a task handed to the database threads that throws while storage closes
     * @param clock the clock of {@link Tx#now()} and of the times recorded for migrations and backups
     * @return a future that completes with the started storage, or completes exceptionally: with a
     *     {@link StorageException} when HikariCP or the driver is not available, a migration script is missing,
     *     unreadable, not valid UTF-8, cannot be split into statements or changed after it ran, a SQLite migration
     *     leaves a broken foreign key, or the database was set up by a newer version of the plugin; with an
     *     {@link SQLException} when the database refuses a statement, whose message names the migration and the
     *     statement when the statement belongs to a migration script; or with what the connection pool throws when
     *     it cannot connect
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the name is blank or the versions of {@code migrations} are not strictly
     *     increasing
     */
    public static CompletableFuture<Storage> start(StorageSettings settings, Path dataFolder, String name,
                                                   String pluginVersion, List<Migration> migrations,
                                                   Function<String, @Nullable InputStream> scripts, Logger logger,
                                                   ErrorReports errors, Clock clock) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(dataFolder, "dataFolder");
        requireName(name);
        Objects.requireNonNull(pluginVersion, "pluginVersion");
        Objects.requireNonNull(migrations, "migrations");
        Objects.requireNonNull(scripts, "scripts");
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(errors, "errors");
        Objects.requireNonNull(clock, "clock");
        Migrator migrator = new Migrator(settings.dialect(), settings.tablePrefix(), clock, logger, migrations,
            scripts);
        Workers workers = workers(name, settings.asyncThreads());
        ExecutorService executor = workers.service();
        CompletableFuture<Storage> started = new CompletableFuture<>();
        executor.execute(() -> {
            List<HikariDataSource> pools = new ArrayList<>();
            try {
                requireHikari(Storage.class, logger);
                requireDriver(settings.dialect(), Storage.class, logger);
                Storage storage = switch (settings.dialect()) {
                    case SQLITE -> sqlite(settings, dataFolder, name, logger, errors, workers, pools, clock);
                    case MYSQL, MARIADB, POSTGRES -> server(settings, name, logger, errors, workers, pools, clock);
                };
                Migrator.Backup backup = settings.dialect() == Dialect.SQLITE
                    ? new SqliteBackup(dataFolder.resolve("backups"), pluginVersion, settings.backupKeep(), clock,
                        logger)
                    : null;
                try (Connection connection = storage.writer.getConnection()) {
                    migrator.migrate(connection, backup);
                }
                if (!started.complete(storage)) {
                    closeAll(pools);
                    executor.shutdown();
                }
            } catch (Throwable failure) {
                try {
                    closeAll(pools);
                } catch (Throwable unclosed) {
                    failure.addSuppressed(unclosed);
                }
                executor.shutdown();
                started.completeExceptionally(failure);
            }
        });
        return started;
    }

    /**
     * Storage over a data source the caller owns, for tests that control the connections.
     *
     * <p>Work runs on {@code source}, one transaction at a time, on a single database thread named
     * {@code <name>-db-1} that this storage owns. Reads and writes both take their connection from
     * {@code source}, each piece of work a connection of its own, so on SQLite a read is not made read-only and
     * a write waits for no {@link StorageSettings.Pool#connectionTimeout()}. No migration runs and no connection
     * pool is built. {@link #close} stops the thread and leaves {@code source} open: work that outlives the drain
     * closes only the connection it took from {@code source}, when it ends.
     *
     * <p>A plugin pairs it with {@code FaultyDataSource} from taffy-test to make statements and commits fail on
     * demand. The wiring lives in the plugin's own tests: they bring the schema up to date with {@link #start},
     * close that storage, and open this one over a {@code FaultyDataSource} on the same database. Its connections
     * carry only the properties the test passes, so a SQLite test that should behave as {@link #start} does passes
     * {@code journal_mode=WAL}, {@code synchronous=FULL}, {@code busy_timeout=5000} and {@code foreign_keys=true}.
     *
     * @param dialect the database {@code source} connects to
     * @param source where every connection comes from
     * @param prefix the table prefix of {@link #table}, possibly empty: up to 32 lowercase letters, digits and
     *     underscores starting with a letter or underscore
     * @param name the name the database thread carries; not blank
     * @param logger receives the warnings
     * @param errors receives the failures storage catches and handles, as for {@link #start}
     * @param clock the clock of {@link Tx#now()}
     * @return the storage, ready for work
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the name is blank or the prefix is out of its range
     */
    public static Storage over(Dialect dialect, DataSource source, String prefix, String name, Logger logger,
                               ErrorReports errors, Clock clock) {
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(source, "source");
        requireName(name);
        StorageSettings.requirePrefix(prefix);
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(errors, "errors");
        Objects.requireNonNull(clock, "clock");
        return new Storage(dialect, prefix, logger, errors, workers(name, 1), source, source, List.of(),
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
     * work queued before it. Once {@link #close} has begun it refuses new tasks with
     * {@link RejectedExecutionException}.
     *
     * @return the executor of the database threads
     */
    public Executor executor() {
        return executor;
    }

    /**
     * Runs work that may write, in one transaction on a database thread. On SQLite storage from {@link #start} it
     * goes through the single writer connection, so writes run one at a time, and a write that waits longer than
     * {@link StorageSettings.Pool#connectionTimeout()} for that connection fails with an {@link SQLException}.
     * A write waits for that connection on a database thread, so with more than one database thread, writes queued
     * behind a long write wait on the connection, and count against the timeout, instead of waiting in the queue.
     * Storage from {@link #over} takes the connection from its source.
     *
     * <p>The transaction commits when the work returns and rolls back when it throws. A failure of the commit
     * statement itself completes the future with a {@link CommitUnknownException}.
     *
     * @param <T> the type of the result
     * @param work the work
     * @return a future that completes with the result once the transaction has committed and its
     *     {@link Tx#afterCommit} actions have run, or completes exceptionally with what the work or the database
     *     threw, or with {@link RejectedExecutionException} when storage is closed
     * @throws NullPointerException if the work is {@code null}
     */
    public <T> CompletableFuture<T> async(TxWork<T> work) {
        return submit(writer, work, 1);
    }

    /**
     * Runs work that only reads, in one transaction on a database thread. On SQLite storage from {@link #start} it
     * goes through the read-only connections, which refuse a write and run beside the writer; on a server it shares
     * the writer's pool. Storage from {@link #over} takes the connection from its source and does not make it
     * read-only.
     *
     * @param <T> the type of the result
     * @param work the work
     * @return a future that completes as the one of {@link #async} does
     * @throws NullPointerException if the work is {@code null}
     */
    public <T> CompletableFuture<T> read(TxWork<T> work) {
        return submit(reader, work, 1);
    }

    /**
     * Runs work that may write as {@link #async} does, and runs it again when the database rolled its transaction
     * back to break a deadlock or a serialization conflict, up to 4 attempts in all.
     *
     * <p>A transaction counts as rolled back by the database when the work or the database threw an
     * {@link SQLException} that is, or has as a cause, an {@link SQLTransactionRollbackException} or an exception
     * whose SQLState starts with {@code 40}. Every attempt has a connection and a {@link Tx} of its own, so the
     * {@link Tx#afterCommit} actions of an attempt that rolled back never run. A {@link CommitUnknownException} is
     * never retried, whatever its SQLState, because the transaction may have been written.
     *
     * <p>The work may therefore run more than once. Each run must be safe to repeat: an effect outside the database
     * belongs in {@link Tx#afterCommit}, which runs only for the attempt that committed.
     *
     * <p>Before attempt {@code n + 1} the database thread sleeps a random 5n to 20n milliseconds. The sleep holds
     * that database thread, so other work queued on the database threads waits longer. When the thread is
     * interrupted during the sleep, the future completes exceptionally with an {@link SQLException} and the
     * thread's interrupt flag is set again.
     *
     * @param <T> the type of the result
     * @param work the work, which may run more than once
     * @return a future that completes as the one of {@link #async} does, with the failure of the last attempt when
     *     no attempt committed
     * @throws NullPointerException if the work is {@code null}
     */
    public <T> CompletableFuture<T> asyncRetrying(TxWork<T> work) {
        return submit(writer, work, ROLLBACK_ATTEMPTS);
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
        return submit(reader, work, ROLLBACK_ATTEMPTS);
    }

    /**
     * Stops the database threads and closes the connection pools, waiting on the calling thread for the work
     * already queued to finish.
     *
     * <p>New work is refused from the moment this method begins, except work that queued or running work submits
     * from a database thread, such as a stage chained onto the future of work. That work still runs within the
     * drain, on the submitting thread, after the work that submitted it has ended, in the order it was submitted.
     *
     * <p>When the drain runs out, the database threads are interrupted. Work still queued fails with
     * {@link RejectedExecutionException}, as does work still waiting for a connection, and any other task queued
     * through {@link #executor()} runs on the closing thread. Work still running sees the interrupt, so a wait
     * inside it may end early with an {@link InterruptedException}. On SQLite such work keeps its connection until
     * it ends. On MySQL, MariaDB and PostgreSQL the pool aborts the connections in use, so such work fails with an
     * {@link SQLException}, or with a {@link CommitUnknownException} when the abort meets its commit. A warning
     * then names the number of queued pieces of work that did not run.
     *
     * <p>Work that must be written before the plugin disables, such as the writes its pending settlements queue,
     * is queued before this method is called and is covered by the drain: a result of {@code false} means that
     * some of it may not have run.
     *
     * <p>It must not be called from a database thread, such as in a stage chained onto the future of work: the
     * drain would wait on the calling thread itself, so it runs out every time.
     *
     * @param drain how long to wait for the queued work
     * @return {@code true} if every queued piece of work finished within the drain
     * @throws NullPointerException if the drain is {@code null}
     */
    public boolean close(Duration drain) {
        Objects.requireNonNull(drain, "drain");
        executor.shutdown();
        boolean drained;
        try {
            drained = executor.awaitTermination(drain.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            drained = false;
        }
        if (!drained) {
            stopping = true;
            int refused = 0;
            for (Runnable queued : executor.shutdownNow()) {
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
            log(Level.WARNING, "Storage closed before its database work finished within " + drain.toMillis()
                + " ms; " + refused + (refused == 1 ? " queued piece" : " queued pieces") + " of work did not run.",
                null);
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
        // connection. Without TLS, MySQL 8's caching_sha2_password needs the server key, which
        // allowPublicKeyRetrieval lets the driver fetch.
        String publicKey = ssl == StorageSettings.SslMode.DISABLE ? "&allowPublicKeyRetrieval=true" : "";
        return switch (dialect) {
            case MYSQL -> {
                String mode = switch (ssl) {
                    case DISABLE -> "DISABLED";
                    case TRUST -> "REQUIRED";
                    case VERIFY_FULL -> "VERIFY_IDENTITY";
                };
                yield "jdbc:mysql://" + address + "?sslMode=" + mode + "&useAffectedRows=true&socketTimeout="
                    + SOCKET_TIMEOUT.toMillis() + publicKey;
            }
            case MARIADB -> {
                String mode = switch (ssl) {
                    case DISABLE -> "disable";
                    case TRUST -> "trust";
                    case VERIFY_FULL -> "verify-full";
                };
                yield "jdbc:mariadb://" + address + "?sslMode=" + mode + "&useAffectedRows=true&socketTimeout="
                    + SOCKET_TIMEOUT.toMillis() + publicKey;
            }
            case POSTGRES -> {
                String mode = switch (ssl) {
                    case DISABLE -> "disable";
                    case TRUST -> "require";
                    case VERIFY_FULL -> "verify-full";
                };
                // pgjdbc reads socketTimeout in seconds. Its default socket factory checks a certificate only
                // against a root.crt file in the user's home; DefaultJavaSSLFactory checks it against the JVM trust
                // store, as the other drivers do, and verify-full still checks the host name.
                String factory = ssl == StorageSettings.SslMode.VERIFY_FULL
                    ? "&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory" : "";
                yield "jdbc:postgresql://" + address + "?sslmode=" + mode + factory + "&socketTimeout="
                    + SOCKET_TIMEOUT.toSeconds();
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

    private <T> CompletableFuture<T> submit(DataSource source, TxWork<T> work, int attempts) {
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
                if (outcome.failure() == null) {
                    result.complete(outcome.value());
                } else {
                    result.completeExceptionally(outcome.failure());
                }
            }
        };
        Job job = new Job(result, body);
        try {
            executor.execute(job);
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
                } catch (SQLException unknown) {
                    CommitUnknownException outcome = new CommitUnknownException(unknown);
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
     * is recognised by its type first and never counts as rolled back. A cause chain that loops ends at the first
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
                                  ErrorReports errors, Workers executor, List<HikariDataSource> pools, Clock clock)
        throws IOException {
        Path file = dataFolder.resolve(settings.sqliteFile()).toAbsolutePath().normalize();
        Files.createDirectories(file.getParent());
        String url = "jdbc:sqlite:" + file;
        Properties pragmas = new Properties();
        pragmas.setProperty("journal_mode", "WAL");
        pragmas.setProperty("synchronous", "FULL");
        pragmas.setProperty("busy_timeout", "5000");
        pragmas.setProperty("foreign_keys", "true");
        StorageSettings.Pool pool = settings.pool();
        HikariDataSource writer = pool(name + "-writer", Dialect.SQLITE.driverClassName(), url, pragmas, 1, 1,
            pool.connectionTimeout(), null, null, null);
        pools.add(writer);
        HikariDataSource reader = pool(name + "-reader", Dialect.SQLITE.driverClassName(), url, pragmas,
            pool.maxSize(), pool.minIdle(), pool.connectionTimeout(), null, null, "PRAGMA query_only = ON");
        pools.add(reader);
        return new Storage(Dialect.SQLITE, "", logger, errors, executor, writer, reader, List.copyOf(pools),
            clock);
    }

    private static Storage server(StorageSettings settings, String name, Logger logger, ErrorReports errors,
                                  Workers executor, List<HikariDataSource> pools, Clock clock) {
        Dialect dialect = settings.dialect();
        StorageSettings.Server server = Objects.requireNonNull(settings.server());
        StorageSettings.Pool pool = settings.pool();
        HikariDataSource shared = pool(name, dialect.driverClassName(), serverUrl(dialect, server), new Properties(),
            pool.maxSize(), pool.minIdle(), pool.connectionTimeout(), server.user(), server.password(), null);
        pools.add(shared);
        return new Storage(dialect, server.tablePrefix(), logger, errors, executor, shared, shared,
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

    private static void requireName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name is blank; it names the database threads and pools");
        }
    }

    private record Workers(ExecutorService service, Set<Thread> threads) {
    }

    private static Workers workers(String name, int count) {
        AtomicInteger counter = new AtomicInteger();
        Set<Thread> threads = ConcurrentHashMap.newKeySet();
        ExecutorService service = Executors.newFixedThreadPool(count, task -> {
            Thread thread = new Thread(task, name + "-db-" + counter.incrementAndGet());
            thread.setDaemon(true);
            threads.add(thread);
            return thread;
        });
        return new Workers(service, threads);
    }
}
