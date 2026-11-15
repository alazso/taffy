package so.alaz.taffy.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.test.CapturedLog;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class SqliteStartTest {

    private static final String NAME = "start-probe";

    private static final String VERSION_TABLE = "CREATE TABLE schema_version (version INTEGER NOT NULL PRIMARY KEY,"
        + " name VARCHAR(128) NOT NULL, checksum CHAR(64) NOT NULL, statements_done INTEGER NOT NULL,"
        + " applied_at BIGINT)";

    private static final String COPY = "data-9.9.9-20261102-101530.db";

    private static final List<Migration> MIGRATIONS =
        List.of(new Migration(1, "probe_a"), new Migration(2, "probe_b"), new Migration(3, "probe_c"));

    private static final Map<String, String> SCRIPTS = Map.of(
        "db/migration/sqlite/V1__probe_a.sql", "CREATE TABLE probe_a (id INTEGER PRIMARY KEY);\n",
        "db/migration/sqlite/V2__probe_b.sql", """
            CREATE TABLE probe_b (id INTEGER PRIMARY KEY, owner TEXT, tag TEXT);
            CREATE INDEX probe_b_owner ON probe_b (owner);
            CREATE INDEX probe_b_tag ON probe_b (tag);
            """,
        "db/migration/sqlite/V3__probe_c.sql", "CREATE TABLE probe_c (id INTEGER PRIMARY KEY);\n");

    @TempDir
    private Path folder;

    private final CapturedLog log = new CapturedLog();
    private Storage storage;

    @AfterEach
    void close() {
        if (storage != null) {
            storage.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void workChainedFromATransactionStillRunningWhenCloseBeginsCommitsInTheDrain() throws Exception {
        storage = start(settings(0));
        storage.async(tx -> {
            tx.execute("CREATE TABLE probe (id INTEGER PRIMARY KEY)");
            return null;
        }).join();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> chained = storage.async(tx -> {
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return null;
        }).thenCompose(ignored -> storage.async(tx -> tx.update("INSERT INTO probe (id) VALUES (1)", Tx.Binder.NONE)));
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        Storage closing = storage;
        AtomicBoolean drained = new AtomicBoolean();
        Thread closer = new Thread(() -> drained.set(closing.close(Duration.ofSeconds(10))));
        closer.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (closer.getState() != Thread.State.TIMED_WAITING) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.onSpinWait();
        }

        release.countDown();
        closer.join(10_000);

        assertThat(chained.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(drained.get()).isTrue();
        storage = start(settings(0));
        assertThat(storage.read(tx -> tx.queryOne("SELECT id FROM probe", Tx.Binder.NONE, row -> row.integer("id")))
            .join()).contains(1L);
    }

    @Test
    void aFreshDatabaseGetsEveryMigrationAndNoBackup() {
        storage = start(settings(5));

        long applied = storage.read(tx -> tx.queryOne(
            "SELECT applied_at FROM schema_version WHERE version = 1 AND statements_done = 1",
            Tx.Binder.NONE, row -> row.integer("applied_at")).orElseThrow()).join();

        assertThat(applied).isEqualTo(StorageContract.NOW.toEpochMilli());
        assertThat(Files.exists(folder.resolve("backups"))).isFalse();
        assertThat(Files.exists(folder.resolve("data.db"))).isTrue();
        assertThat(log.lines()).containsExactly("INFO Applied database migration V1__probe_a.",
            "INFO Applied database migration V2__probe_b.", "INFO Applied database migration V3__probe_c.");
    }

    @Test
    void aLaterMigrationAppliesToADatabaseAtTheFirstVersion() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("data.db"))) {
            new Migrator(Dialect.SQLITE, "", StorageContract.CLOCK, new CapturedLog().logger,
                List.of(MIGRATIONS.getFirst()), SqliteStartTest::script).migrate(connection, null);
        }

        storage = start(settings(5));

        List<String> indexes = storage.read(tx -> tx.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'probe_b' AND sql IS NOT NULL"
                + " ORDER BY name", Tx.Binder.NONE, row -> row.text("name"))).join();
        assertThat(indexes).containsExactly("probe_b_owner", "probe_b_tag");
        assertThat(log.lines()).containsExactly(
            "INFO Copied the database to backups/" + COPY + " before upgrading its layout.",
            "INFO Applied database migration V2__probe_b.", "INFO Applied database migration V3__probe_c.");
    }

    @ParameterizedTest
    @MethodSource("earlierVersions")
    void aDatabaseAtAnEarlierVersionGetsOnlyTheLaterMigrations(int version) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("data.db"))) {
            new Migrator(Dialect.SQLITE, "", StorageContract.CLOCK, new CapturedLog().logger,
                MIGRATIONS.subList(0, version), SqliteStartTest::script).migrate(connection, null);
        }

        storage = start(settings(5));

        List<String> applied = MIGRATIONS.subList(version, MIGRATIONS.size()).stream()
            .map(migration -> "INFO Applied database migration " + migration.id() + ".").toList();
        List<String> lines = log.lines();
        assertThat(lines.getFirst())
            .isEqualTo("INFO Copied the database to backups/" + COPY + " before upgrading its layout.");
        assertThat(lines.subList(1, lines.size())).containsExactlyElementsOf(applied);
        assertThat(storage.read(tx -> tx.query("SELECT name FROM sqlite_master WHERE type = 'table'"
            + " AND name NOT LIKE 'sqlite_%' ORDER BY name", Tx.Binder.NONE, row -> row.text("name"))).join())
            .containsExactly("probe_a", "probe_b", "probe_c", "schema_version");
    }

    static IntStream earlierVersions() {
        return IntStream.range(1, MIGRATIONS.size());
    }

    @Test
    void aRestartAppliesNothing() {
        start(settings(5)).close(Duration.ofSeconds(5));
        log.records.clear();

        storage = start(settings(5));

        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aFileInASubfolderGetsItsFolder() {
        StorageSettings nested = new StorageSettings(Dialect.SQLITE, "data/store.db", null, settings(5).pool(), 1, 5);

        storage = start(nested);

        assertThat(Files.exists(folder.resolve("data").resolve("store.db"))).isTrue();
    }

    @Test
    void anExistingDatabaseIsCopiedBeforeAMigrationKeepingTheConfiguredNumberOfCopies() throws Exception {
        Path backups = Files.createDirectories(folder.resolve("backups"));
        Path old = Files.writeString(backups.resolve("data-0.0.1-20260101-000000.db"), "old");
        Files.setLastModifiedTime(old, FileTime.fromMillis(86_400_000L));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("data.db"));
             Statement statement = connection.createStatement()) {
            statement.execute(VERSION_TABLE);
            statement.execute("CREATE TABLE owner_table (id INTEGER)");
        }

        storage = start(settings(1));

        List<String> kept;
        try (Stream<Path> files = Files.list(backups)) {
            kept = files.map(path -> path.getFileName().toString()).sorted().toList();
        }
        assertThat(kept).containsExactly(COPY);
        try (Connection copy = DriverManager.getConnection("jdbc:sqlite:" + backups.resolve(COPY));
             Statement statement = copy.createStatement()) {
            assertThat(statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name = 'owner_table'")
                .getInt(1)).isEqualTo(1);
        }
        assertThat(log.lines()).containsExactly(
            "INFO Copied the database to backups/" + COPY + " before upgrading its layout.",
            "INFO Applied database migration V1__probe_a.",
            "INFO Applied database migration V2__probe_b.", "INFO Applied database migration V3__probe_c.");
    }

    @Test
    void workQueuedWhenTheDrainRunsOutFailsInsteadOfHanging() throws Exception {
        StorageSettings single = new StorageSettings(Dialect.SQLITE, "data.db", null, settings(5).pool(), 1, 5);
        Storage closing = start(single);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> running = closing.async(tx -> {
            inside.countDown();
            awaitThroughInterrupts(release);
            return 1;
        });
        CompletableFuture<Integer> queued = closing.async(tx -> 2);
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(closing.close(Duration.ofMillis(200))).isFalse();
        release.countDown();

        assertThatThrownBy(() -> queued.get(2, TimeUnit.SECONDS))
            .hasCauseInstanceOf(RejectedExecutionException.class)
            .hasRootCauseMessage("storage closed before this work ran");
        assertThat(running.get(2, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(log.lines()).last().isEqualTo("WARNING Storage closed before its database work finished within"
            + " 200 ms; 1 queued piece of work did not run.");
        awaitReleased(folder.resolve("data.db"));
    }

    @Test
    void aHandOffQueuedWhenTheDrainRunsOutStillRuns() throws Exception {
        StorageSettings single = new StorageSettings(Dialect.SQLITE, "data.db", null, settings(5).pool(), 1, 5);
        Storage closing = start(single);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> running = closing.async(tx -> {
            inside.countDown();
            awaitThroughInterrupts(release);
            return 1;
        });
        AtomicBoolean ran = new AtomicBoolean();
        closing.executor().execute(() -> ran.set(true));
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(closing.close(Duration.ofMillis(200))).isFalse();
        release.countDown();

        assertThat(ran).isTrue();
        assertThat(running.get(2, TimeUnit.SECONDS)).isEqualTo(1);
        awaitReleased(folder.resolve("data.db"));
    }

    @Test
    void aWriteStillWaitingForTheWriterWhenTheDrainRunsOutFailsAsRefused() throws Exception {
        Storage closing = start(settings(0));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> holder = new AtomicReference<>();
        CompletableFuture<Integer> holding = closing.async(tx -> {
            holder.set(Thread.currentThread().getName());
            inside.countDown();
            awaitThroughInterrupts(release);
            return 1;
        });
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Integer> waiting = closing.async(tx -> 2);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.getName().startsWith(NAME + "-db-")
            && !thread.getName().equals(holder.get()) && thread.getState() == Thread.State.TIMED_WAITING)) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(10);
        }

        assertThat(closing.close(Duration.ofMillis(200))).isFalse();
        release.countDown();

        assertThatThrownBy(() -> waiting.get(5, TimeUnit.SECONDS))
            .hasCauseInstanceOf(RejectedExecutionException.class)
            .cause().hasMessage("storage closed before this work ran");
        assertThat(holding.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        awaitReleased(folder.resolve("data.db"));
    }

    @Test
    void aWriteSubmittedInsideAWriteDuringTheDrainCommitsAfterIt() throws Exception {
        storage = start(settings(0));
        Storage running = storage;
        running.async(tx -> {
            tx.execute("CREATE TABLE probe (id INTEGER PRIMARY KEY)");
            return null;
        }).join();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<CompletableFuture<Integer>> inner = new AtomicReference<>();
        CompletableFuture<Integer> outer = running.async(tx -> {
            inside.countDown();
            awaitThroughInterrupts(release);
            tx.update("INSERT INTO probe (id) VALUES (1)", Tx.Binder.NONE);
            inner.set(running.async(nested -> nested.update("INSERT INTO probe (id) VALUES (2)", Tx.Binder.NONE)));
            return 1;
        });
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        AtomicBoolean drained = new AtomicBoolean();
        Thread closer = closeInTheBackground(running, Duration.ofSeconds(20), drained);

        release.countDown();
        closer.join(20_000);

        assertThat(outer.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(inner.get().get(10, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(drained).isTrue();
        storage = start(settings(0));
        assertThat(storage.read(tx -> tx.query("SELECT id FROM probe ORDER BY id", Tx.Binder.NONE,
            row -> row.integer("id"))).join()).containsExactly(1L, 2L);
    }

    @Test
    void aStartGivenUpOnClosesWhatItBuiltOnceItFinishes() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Storage> started = Storage.start(settings(5), folder, "given-up", "9.9.9", MIGRATIONS,
            path -> {
                reading.countDown();
                awaitThroughInterrupts(release);
                return script(path);
            }, log.logger, new ErrorReports(), StorageContract.CLOCK);
        assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(started.cancel(true)).isTrue();
        release.countDown();

        awaitNoThreadNamed("given-up-");
        awaitReleased(folder.resolve("data.db"));
        assertThat(started).isCancelled();
    }

    @Test
    void anExistingFileWithTablesButNoVersionTableIsCopiedFirst() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("data.db"));
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE owner_table (id INTEGER)");
        }

        storage = start(settings(5));

        assertThat(folder.resolve("backups").resolve(COPY)).exists();
        assertThat(log.lines().getFirst())
            .isEqualTo("INFO Copied the database to backups/" + COPY + " before upgrading its layout.");
    }

    @Test
    void aMalformedScriptStopsTheStartBeforeTheBackupAndNamesTheScript() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("data.db"));
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE owner_table (id INTEGER)");
        }
        Function<String, InputStream> malformed = path -> path.endsWith("V3__probe_c.sql")
            ? new ByteArrayInputStream("CREATE TABLE a (id INT); CREATE TABLE b (id INT);\n"
                .getBytes(StandardCharsets.UTF_8))
            : script(path);

        assertThatThrownBy(() -> Storage.start(settings(5), folder, NAME, "9.9.9", MIGRATIONS, malformed, log.logger,
            new ErrorReports(), StorageContract.CLOCK).join())
            .isInstanceOf(CompletionException.class)
            .cause().isInstanceOf(StorageException.class)
            .hasMessage("db/migration/sqlite/V3__probe_c.sql cannot be split into statements: a statement must end at"
                + " the end of its line: CREATE TABLE a (id INT); CREATE TABLE b (id INT);");
        assertThat(folder.resolve("backups")).doesNotExist();
        assertThat(log.lines()).isEmpty();
    }

    // When the last connection to a database closes, SQLite removes its WAL file, so a WAL file that is gone shows
    // every connection was closed, on any system. On Windows an open file also cannot be renamed.
    private static void awaitReleased(Path file) throws Exception {
        Path wal = file.resolveSibling(file.getFileName() + "-wal");
        Path probe = file.resolveSibling(file.getFileName() + ".probe");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try {
                if (Files.exists(wal)) {
                    throw new FileSystemException(wal.toString(), null, "a connection is still open");
                }
                Files.move(file, probe);
                Files.move(probe, file);
                return;
            } catch (FileSystemException locked) {
                if (System.nanoTime() > deadline) {
                    throw locked;
                }
                Thread.sleep(50);
            }
        }
    }

    // Waits for the latch as work that ignores the interrupt does, and tells whether it was interrupted.
    static boolean awaitThroughInterrupts(CountDownLatch release) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (release.getCount() > 0 && System.nanoTime() < deadline) {
            try {
                release.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    // Starts closing storage on a new thread and returns once that thread waits in the drain.
    static Thread closeInTheBackground(Storage closing, Duration drain, AtomicBoolean drained) {
        Thread closer = new Thread(() -> drained.set(closing.close(drain)));
        closer.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (closer.getState() != Thread.State.TIMED_WAITING) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.onSpinWait();
        }
        return closer;
    }

    @Test
    void codeChainedOnTheResultNeverHoldsTheWriter() {
        storage = start(settings(5));
        storage.async(tx -> {
            tx.execute("CREATE TABLE chained (id INTEGER)");
            return null;
        }).join();
        AtomicReference<Throwable> leak = new AtomicReference<>();

        Tx leaked = storage.async(tx -> tx).thenApply(tx -> {
            try {
                tx.execute("INSERT INTO chained VALUES (42)");
            } catch (Throwable failure) {
                leak.set(failure);
            }
            return tx;
        }).join();

        assertThat(leak.get()).isInstanceOf(IllegalStateException.class).hasMessage("this transaction has ended");
        assertThat(leaked).isNotNull();
        assertThat(storage.async(tx -> tx.queryOne("SELECT COUNT(*) AS n FROM chained", Tx.Binder.NONE,
            row -> row.integer("n")).orElseThrow()).join()).isZero();
    }

    @Test
    void zeroKeepTakesNoCopy() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("data.db"));
             Statement statement = connection.createStatement()) {
            statement.execute(VERSION_TABLE);
        }

        storage = start(settings(0));

        assertThat(Files.exists(folder.resolve("backups"))).isFalse();
    }

    @Test
    void aDatabaseFromANewerVersionStopsTheStart() {
        Storage first = start(settings(5));
        int later = MIGRATIONS.size() + 1;
        first.async(tx -> tx.update("INSERT INTO schema_version VALUES (" + later + ", 'later', 'x', 1, 1)",
            Tx.Binder.NONE)).join();
        first.close(Duration.ofSeconds(5));

        assertThatThrownBy(() -> start(settings(5)))
            .isInstanceOf(CompletionException.class)
            .hasRootCauseInstanceOf(StorageException.class)
            .rootCause().hasMessageContaining("schema version " + later + ", set up by a newer version of the plugin");
    }

    @Test
    void aFailedStartClosesItsPoolsAndThreads() throws Exception {
        List<Migration> missing = new ArrayList<>(MIGRATIONS);
        missing.add(new Migration(4, "missing"));

        assertThatThrownBy(() -> Storage.start(settings(5), folder, "failed-start", "9.9.9", missing, SqliteStartTest::script,
            log.logger, new ErrorReports(), StorageContract.CLOCK).join())
            .isInstanceOf(CompletionException.class)
            .rootCause().isInstanceOf(StorageException.class)
            .hasMessage("the plugin has no db/migration/sqlite/V4__missing.sql");

        awaitReleased(folder.resolve("data.db"));
        awaitNoThreadNamed("failed-start-");
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void workAfterCloseIsRejected() {
        Storage closed = start(settings(5));
        assertThat(closed.close(Duration.ofSeconds(5))).isTrue();

        assertThatThrownBy(() -> closed.async(tx -> 1).join())
            .hasRootCauseInstanceOf(RejectedExecutionException.class);
        assertThatThrownBy(() -> closed.executor().execute(() -> {
        })).isInstanceOf(RejectedExecutionException.class);
    }

    static void awaitNoThreadNamed(String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (Thread.getAllStackTraces().keySet().stream()
            .anyMatch(thread -> thread.isAlive() && thread.getName().startsWith(prefix))) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    static StorageSettings settings(int keep) {
        return new StorageSettings(Dialect.SQLITE, "data.db", null,
            new StorageSettings.Pool(4, 1, Duration.ofSeconds(5)), 2, keep);
    }

    private static InputStream script(String path) {
        String script = SCRIPTS.get(path);
        return script == null ? null : new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8));
    }

    private Storage start(StorageSettings settings) {
        return Storage.start(settings, folder, NAME, "9.9.9", MIGRATIONS, SqliteStartTest::script, log.logger,
            new ErrorReports(), StorageContract.CLOCK).join();
    }
}
