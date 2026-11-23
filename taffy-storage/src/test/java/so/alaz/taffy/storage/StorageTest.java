package so.alaz.taffy.storage;

import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.test.CapturedLog;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

final class StorageTest {

    private static final StorageSettings.Pool POOL =
        StorageSettings.Pool.of(2).minIdle(0).connectionTimeout(Duration.ofMillis(500));

    @TempDir
    private Path folder;

    private final CapturedLog log = new CapturedLog();
    private final ErrorReports errors = new ErrorReports();
    private final List<Map.Entry<String, Throwable>> reported = new CopyOnWriteArrayList<>();
    private final DataSource source = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);

    @BeforeEach
    void wire() throws SQLException {
        errors.install((origin, error) -> reported.add(Map.entry(origin, error)));
        when(source.getConnection()).thenReturn(connection);
        when(connection.unwrap(Connection.class)).thenReturn(connection);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        MYSQL    | DISABLE     | jdbc:mysql://db.example:3307/app?sslMode=DISABLED&useAffectedRows=true&socketTimeout=30000&rewriteBatchedStatements=true&allowPublicKeyRetrieval=true
        MYSQL    | PREFER      | jdbc:mysql://db.example:3307/app?sslMode=PREFERRED&useAffectedRows=true&socketTimeout=30000&rewriteBatchedStatements=true&allowPublicKeyRetrieval=true
        MYSQL    | TRUST       | jdbc:mysql://db.example:3307/app?sslMode=REQUIRED&useAffectedRows=true&socketTimeout=30000&rewriteBatchedStatements=true
        MYSQL    | VERIFY_FULL | jdbc:mysql://db.example:3307/app?sslMode=VERIFY_IDENTITY&useAffectedRows=true&socketTimeout=30000&rewriteBatchedStatements=true
        MARIADB  | DISABLE     | jdbc:mariadb://db.example:3307/app?sslMode=disable&useAffectedRows=true&socketTimeout=30000&allowPublicKeyRetrieval=true
        MARIADB  | PREFER      | jdbc:mariadb://db.example:3307/app?sslMode=disable&useAffectedRows=true&socketTimeout=30000&allowPublicKeyRetrieval=true
        MARIADB  | TRUST       | jdbc:mariadb://db.example:3307/app?sslMode=trust&useAffectedRows=true&socketTimeout=30000
        MARIADB  | VERIFY_FULL | jdbc:mariadb://db.example:3307/app?sslMode=verify-full&useAffectedRows=true&socketTimeout=30000
        POSTGRES | DISABLE     | jdbc:postgresql://db.example:3307/app?sslmode=disable&socketTimeout=30
        POSTGRES | PREFER      | jdbc:postgresql://db.example:3307/app?sslmode=prefer&socketTimeout=30
        POSTGRES | TRUST       | jdbc:postgresql://db.example:3307/app?sslmode=require&socketTimeout=30
        POSTGRES | VERIFY_FULL | jdbc:postgresql://db.example:3307/app?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory&socketTimeout=30
        """)
    void aServerUrlCarriesTheDriversOwnSslModeAndTimeouts(Dialect dialect, StorageSettings.SslMode ssl, String url) {
        assertThat(Storage.serverUrl(dialect, server("db.example", ssl))).isEqualTo(url);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        MYSQL    | jdbc:mysql://db.example:3307/app?sslMode=REQUIRED&useAffectedRows=true&socketTimeout=90000&rewriteBatchedStatements=true
        MARIADB  | jdbc:mariadb://db.example:3307/app?sslMode=trust&useAffectedRows=true&socketTimeout=90000
        POSTGRES | jdbc:postgresql://db.example:3307/app?sslmode=require&socketTimeout=90
        """)
    void aServerUrlCarriesTheSocketTimeoutOfTheServerAndNoneOfItsDriverProperties(Dialect dialect, String url) {
        StorageSettings.Server server = server("db.example", StorageSettings.SslMode.TRUST)
            .socketTimeout(Duration.ofSeconds(90)).properties(Map.of("connectTimeout", "5000"));

        assertThat(Storage.serverUrl(dialect, server)).isEqualTo(url);
    }

    @Test
    void anIpv6HostKeepsItsBrackets() {
        assertThat(Storage.serverUrl(Dialect.POSTGRES, server("[::1]", StorageSettings.SslMode.TRUST)))
            .isEqualTo("jdbc:postgresql://[::1]:3307/app?sslmode=require&socketTimeout=30");
    }

    @Test
    void sqliteHasNoServerUrl() {
        assertThatThrownBy(() -> Storage.serverUrl(Dialect.SQLITE, server("db.example",
            StorageSettings.SslMode.DISABLE)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("sqlite connects to a file, not a server");
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void aDriverTheCallersLoaderCannotSeeStopsTheStartWithASentence(Dialect dialect) {
        assertThatThrownBy(() -> Storage.requireDriver(dialect, String.class, log.logger))
            .isInstanceOf(StorageException.class)
            .hasMessage("the " + dialect.id() + " driver is not available");
        assertThatCode(() -> Storage.requireDriver(dialect, Storage.class, log.logger)).doesNotThrowAnyException();
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void hikariCpTheCallersLoaderCannotSeeStopsTheStartWithASentence() {
        assertThatThrownBy(() -> Storage.requireHikari(String.class, log.logger))
            .isInstanceOf(StorageException.class)
            .hasMessage("HikariCP is not available");
        assertThatCode(() -> Storage.requireHikari(Storage.class, log.logger)).doesNotThrowAnyException();
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aMissingArgumentIsRefusedBeforeAnyThreadStarts() {
        Plugin plugin = TestPlugins.plugin("nulls", folder, log.logger);

        assertThatThrownBy(() -> Storage.start(null, errors, sqlite(), List.of(), StorageContract.CLOCK))
            .isInstanceOf(NullPointerException.class).hasMessage("plugin");
        assertThatThrownBy(() -> Storage.start(plugin, null, sqlite(), List.of(), StorageContract.CLOCK))
            .isInstanceOf(NullPointerException.class).hasMessage("errors");
        assertThatThrownBy(() -> Storage.start(plugin, errors, null, List.of(), StorageContract.CLOCK))
            .isInstanceOf(NullPointerException.class).hasMessage("settings");
        assertThatThrownBy(() -> Storage.start(plugin, errors, sqlite(), null, StorageContract.CLOCK))
            .isInstanceOf(NullPointerException.class).hasMessage("migrations");
        assertThatThrownBy(() -> Storage.start(plugin, errors, sqlite(), List.of(), null))
            .isInstanceOf(NullPointerException.class).hasMessage("clock");
        assertThatThrownBy(() -> Storage.over(null, errors, Dialect.POSTGRES, source, "p_", StorageContract.CLOCK))
            .isInstanceOf(NullPointerException.class).hasMessage("plugin");
        assertThatThrownBy(() -> Storage.over(plugin, null, Dialect.POSTGRES, source, "p_", StorageContract.CLOCK))
            .isInstanceOf(NullPointerException.class).hasMessage("errors");
        assertThatThrownBy(() -> Storage.over(plugin, errors, null, source, "p_", StorageContract.CLOCK))
            .isInstanceOf(NullPointerException.class).hasMessage("dialect");
        assertThatThrownBy(() -> Storage.over(plugin, errors, Dialect.POSTGRES, null, "p_", StorageContract.CLOCK))
            .isInstanceOf(NullPointerException.class).hasMessage("source");
        assertThatThrownBy(() -> Storage.over(plugin, errors, Dialect.POSTGRES, source, null, StorageContract.CLOCK))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Storage.over(plugin, errors, Dialect.POSTGRES, source, "p_", null))
            .isInstanceOf(NullPointerException.class).hasMessage("clock");
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(thread -> thread.getName().startsWith("nulls-db-"));
        assertThat(folder.resolve("data.db")).doesNotExist();
    }

    @Test
    void aPluginThatGivesNoVersionDataPathOrLoggerIsRefusedBeforeAnyThreadStarts() {
        Plugin noVersion = TestPlugins.plugin("absent", folder, log.logger);
        PluginMeta blankMeta = mock(PluginMeta.class);
        when(noVersion.getPluginMeta()).thenReturn(blankMeta);
        Plugin noDataPath = TestPlugins.plugin("absent", folder, log.logger);
        when(noDataPath.getDataPath()).thenReturn(null);
        Plugin noLogger = TestPlugins.plugin("absent", folder, log.logger);
        when(noLogger.getLogger()).thenReturn(null);

        assertThatThrownBy(() -> Storage.start(noVersion, errors, sqlite(), List.of(), StorageContract.CLOCK))
            .isInstanceOf(NullPointerException.class).hasMessage("version");
        assertThatThrownBy(() -> Storage.start(noDataPath, errors, sqlite(), List.of(), StorageContract.CLOCK))
            .isInstanceOf(NullPointerException.class).hasMessage("dataPath");
        assertThatThrownBy(() -> Storage.start(noLogger, errors, sqlite(), List.of(), StorageContract.CLOCK))
            .isInstanceOf(NullPointerException.class).hasMessage("logger");
        assertThatThrownBy(() -> Storage.over(noLogger, errors, Dialect.POSTGRES, source, "p_",
            StorageContract.CLOCK)).isInstanceOf(NullPointerException.class).hasMessage("logger");
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(thread -> thread.getName().startsWith("absent-db-"));
        assertThat(folder.resolve("data.db")).doesNotExist();
    }

    @Test
    void aMissingNameIsRefused() {
        Plugin plugin = TestPlugins.plugin("absent", folder, log.logger);
        when(plugin.getName()).thenReturn(null);

        assertThatThrownBy(() -> Storage.start(plugin, errors, sqlite(), List.of(), StorageContract.CLOCK))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("name");
        assertThatThrownBy(() -> Storage.over(plugin, errors, Dialect.POSTGRES, source, "p_", StorageContract.CLOCK))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("name");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void aBlankNameIsRefusedBeforeAnyThreadStarts(String name) {
        Plugin plugin = TestPlugins.plugin(name, folder, log.logger);

        assertThatThrownBy(() -> Storage.start(plugin, errors, sqlite(), List.of(), StorageContract.CLOCK))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("name is blank; it names the database threads and pools");
        assertThatThrownBy(() -> Storage.over(plugin, errors, Dialect.SQLITE, source, "", StorageContract.CLOCK))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("name is blank; it names the database threads and pools");
        assertThat(folder.resolve("data.db")).doesNotExist();
    }

    @Test
    void migrationsOutOfOrderAreRefusedBeforeAnyThreadStarts() {
        List<Migration> unordered = List.of(new Migration(2, "second"), new Migration(1, "first"));

        assertThatThrownBy(() -> Storage.start(TestPlugins.plugin("unordered", folder, log.logger), errors, sqlite(),
            unordered, StorageContract.CLOCK))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("migration versions must be strictly increasing, but V1__first follows V2__second");
        assertThat(Thread.getAllStackTraces().keySet())
            .noneMatch(thread -> thread.getName().startsWith("unordered-db-"));
    }

    @Test
    void storageOverASourceRefusesAPrefixThatIsNotSafeInSql() {
        assertThatThrownBy(() -> Storage.over(TestPlugins.plugin("over", log.logger), errors, Dialect.MARIADB, source,
            "p; DROP", StorageContract.CLOCK))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("tablePrefix is \"p; DROP\"");
    }

    @Test
    void storageOverASourceTakesItsThreadNameAndItsWarningsFromThePlugin() throws Exception {
        CapturedLog own = new CapturedLog();
        Storage storage = Storage.over(TestPlugins.plugin("owner-name", own.logger), errors, Dialect.POSTGRES,
            source, "p_", StorageContract.CLOCK);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> running = storage.async(tx -> {
            inside.countDown();
            SqliteStartTest.awaitThroughInterrupts(release);
            return Thread.currentThread().getName();
        });
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(storage.close(Duration.ofMillis(100))).isFalse();
        release.countDown();

        assertThat(running.get(10, TimeUnit.SECONDS)).isEqualTo("owner-name-db-1");
        assertThat(own.lines()).containsExactly("WARNING Storage closed before its database work finished within"
            + " 100 ms; 0 queued pieces of work did not run.");
        assertThat(log.records).isEmpty();
    }

    @Test
    void storageOverASourceRunsEachPieceOfWorkInItsOwnTransactionAndLeavesTheSourceOpen() throws Exception {
        Storage storage = over();

        String written = storage.async(tx -> Thread.currentThread().getName() + " " + tx.table("accounts")).join();
        String read = storage.read(tx -> tx.dialect().id() + " " + tx.now()).join();

        assertThat(written).isEqualTo("over-probe-db-1 p_accounts");
        assertThat(read).isEqualTo("postgres " + StorageContract.NOW);
        assertThat(storage.dialect()).isEqualTo(Dialect.POSTGRES);
        assertThat(storage.table("accounts")).isEqualTo("p_accounts");
        verify(connection, times(2)).setAutoCommit(false);
        verify(connection, times(2)).commit();
        verify(connection, times(2)).close();
        verify(connection, never()).rollback();
        assertThat(storage.close(Duration.ofSeconds(5))).isTrue();
        verify(source, times(2)).getConnection();
        verifyNoMoreInteractions(source);
        SqliteStartTest.awaitNoThreadNamed("over-probe-db-");
    }

    @Test
    void failedWorkRollsBackAndKeepsARefusedRollbackAsSuppressed() throws Exception {
        SQLException refused = new SQLException("rollback refused");
        doThrow(refused).when(connection).rollback();
        Storage storage = over();
        AtomicBoolean ran = new AtomicBoolean();

        CompletableFuture<Object> failed = storage.async(tx -> {
            tx.afterCommit(() -> ran.set(true));
            throw new SQLException("boom");
        });

        assertThatThrownBy(failed::join).isInstanceOf(CompletionException.class)
            .cause().isInstanceOf(SQLException.class).hasMessage("boom")
            .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(refused));
        verify(connection).rollback();
        verify(connection, never()).commit();
        assertThat(ran).isFalse();
        assertThat(reported).isEmpty();
        storage.close(Duration.ofSeconds(5));
    }

    @Test
    void aFailedCommitStatementHasAnUnknownOutcomeAndRunsNoAction() throws Exception {
        doThrow(new SQLException("connection lost", "08006", 17)).when(connection).commit();
        Storage storage = over();
        AtomicBoolean ran = new AtomicBoolean();

        CompletableFuture<Integer> unknown = storage.async(tx -> {
            tx.afterCommit(() -> ran.set(true));
            return 1;
        });

        assertThatThrownBy(unknown::join).isInstanceOf(CompletionException.class)
            .cause().isInstanceOf(CommitUnknownException.class)
            .satisfies(failure -> {
                assertThat(((SQLException) failure).getSQLState()).isEqualTo("08006");
                assertThat(((SQLException) failure).getErrorCode()).isEqualTo(17);
            });
        verify(connection).rollback();
        assertThat(ran).isFalse();
        storage.close(Duration.ofSeconds(5));
    }

    @Test
    void aThrowingActionAfterACommitIsLoggedAndReportedAndTheRestStillRun() {
        Storage storage = over();
        IllegalStateException broken = new IllegalStateException("broken action");
        List<String> ran = new CopyOnWriteArrayList<>();

        int value = storage.async(tx -> {
            tx.afterCommit(() -> {
                throw broken;
            });
            tx.afterCommit(() -> ran.add("second"));
            return 7;
        }).join();

        assertThat(value).isEqualTo(7);
        assertThat(ran).containsExactly("second");
        assertThat(log.records).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.WARNING);
            assertThat(record.getMessage()).isEqualTo("An action after a database commit failed");
            assertThat(record.getThrown()).isSameAs(broken);
        });
        assertThat(reported).containsExactly(Map.entry("storage", broken));
        storage.close(Duration.ofSeconds(5));
    }

    @Test
    void aLogHandlerThatThrowsStillLetsTheWorkComplete() throws Exception {
        Logger throwing = Logger.getAnonymousLogger();
        throwing.setUseParentHandlers(false);
        throwing.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                throw new IllegalStateException("handler broken");
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        Storage storage = Storage.over(TestPlugins.plugin("throwing-log", throwing), errors, Dialect.POSTGRES, source,
            "p_", StorageContract.CLOCK);

        List<String> ran = new CopyOnWriteArrayList<>();
        CompletableFuture<Integer> written = storage.async(tx -> {
            tx.afterCommit(() -> {
                throw new IllegalStateException("broken action");
            });
            tx.afterCommit(() -> ran.add("second"));
            return 7;
        });

        assertThat(written.get(10, TimeUnit.SECONDS)).isEqualTo(7);
        assertThat(ran).containsExactly("second");
        assertThat(storage.async(tx -> Thread.currentThread().getName()).get(10, TimeUnit.SECONDS))
            .isEqualTo("throwing-log-db-1");
        assertThat(storage.close(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void aNullDrainOrWorkIsRefusedBeforeAnythingChanges() throws Exception {
        Storage storage = over();

        assertThatThrownBy(() -> storage.close(null)).isInstanceOf(NullPointerException.class).hasMessage("drain");
        assertThatThrownBy(() -> storage.async(null)).isInstanceOf(NullPointerException.class).hasMessage("work");
        assertThatThrownBy(() -> storage.read(null)).isInstanceOf(NullPointerException.class).hasMessage("work");
        assertThatThrownBy(() -> storage.asyncRetrying(null)).isInstanceOf(NullPointerException.class)
            .hasMessage("work");
        assertThatThrownBy(() -> storage.readRetrying(null)).isInstanceOf(NullPointerException.class)
            .hasMessage("work");

        verify(source, never()).getConnection();
        assertThat(storage.async(tx -> 1).get(10, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(storage.close(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void aDrainTooLongToCountInNanosecondsWaitsWithNoLimit() throws Exception {
        Storage storage = over();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> running = storage.async(tx -> {
            inside.countDown();
            SqliteStartTest.awaitThroughInterrupts(release);
            return 1;
        });
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS).execute(release::countDown);

        assertThat(storage.close(ChronoUnit.FOREVER.getDuration())).isTrue();

        assertThat(running).isCompletedWithValue(1);
        SqliteStartTest.awaitNoThreadNamed("over-probe-db-");
    }

    @Test
    void aNegativeDrainWaitsNoTime() throws Exception {
        Storage storage = over();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> running = storage.async(tx -> {
            inside.countDown();
            SqliteStartTest.awaitThroughInterrupts(release);
            return 1;
        });
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(storage.close(Duration.ofSeconds(-5))).isFalse();
        release.countDown();

        assertThat(running.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(log.lines()).containsExactly("WARNING Storage closed before its database work finished within"
            + " 0 ms; 0 queued pieces of work did not run.");
    }

    @Test
    void aChainOfWorkHandedOnDuringTheDrainRunsOneStepAfterAnother() throws Exception {
        Storage storage = over();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        int steps = 10_000;
        CompletableFuture<Integer> chain = storage.async(tx -> {
            inside.countDown();
            SqliteStartTest.awaitThroughInterrupts(release);
            return 0;
        }).thenCompose(ignored -> step(storage, 1, steps));
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        AtomicBoolean drained = new AtomicBoolean();
        Thread closer = SqliteStartTest.closeInTheBackground(storage, Duration.ofSeconds(60), drained);

        release.countDown();
        closer.join(60_000);

        assertThat(chain.get(10, TimeUnit.SECONDS)).isEqualTo(steps);
        assertThat(drained).isTrue();
        verify(connection, times(steps + 1)).commit();
    }

    private static CompletableFuture<Integer> step(Storage storage, int step, int steps) {
        return storage.async(tx -> step)
            .thenCompose(done -> done == steps ? CompletableFuture.completedFuture(done) : step(storage, done + 1,
                steps));
    }

    @Test
    void aThrowingHandOffRunWhileClosingIsLoggedAndReportedAndTheNextStillRuns() throws Exception {
        Storage storage = over();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> running = storage.async(tx -> {
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return 1;
        });
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        IllegalStateException broken = new IllegalStateException("broken hand-off");
        AtomicBoolean next = new AtomicBoolean();
        storage.executor().execute(() -> {
            throw broken;
        });
        storage.executor().execute(() -> next.set(true));

        assertThat(storage.close(Duration.ofMillis(100))).isFalse();
        release.countDown();

        assertThat(next).isTrue();
        assertThat(running.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(log.records).hasSize(2);
        assertThat(log.records.getFirst()).satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.WARNING);
            assertThat(record.getMessage()).isEqualTo("A task queued on the database threads failed as they closed");
            assertThat(record.getThrown()).isSameAs(broken);
        });
        assertThat(log.lines().get(1)).isEqualTo("WARNING Storage closed before its database work finished within"
            + " 100 ms; 0 queued pieces of work did not run.");
        assertThat(reported).containsExactly(Map.entry("storage", broken));
    }

    @Test
    void workThatOutlivesTheDrainOfStorageOverASourceClosesOnlyTheConnectionItTook() throws Exception {
        Storage storage = over();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<CompletableFuture<Integer>> late = new AtomicReference<>();
        CompletableFuture<Integer> running = storage.async(tx -> {
            inside.countDown();
            interrupted.set(SqliteStartTest.awaitThroughInterrupts(release));
            late.set(storage.async(after -> 2));
            return 1;
        });
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(storage.close(Duration.ofMillis(100))).isFalse();
        release.countDown();

        assertThat(running.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(interrupted).isTrue();
        assertThatThrownBy(() -> late.get().get(10, TimeUnit.SECONDS))
            .hasCauseInstanceOf(RejectedExecutionException.class);
        verify(connection).commit();
        verify(connection).close();
        verify(connection, never()).unwrap(Connection.class);
    }

    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = {"MYSQL", "MARIADB", "POSTGRES"})
    void aServerThatRefusesTheConnectionFailsTheStartAndStopsItsThreads(Dialect dialect) throws Exception {
        StorageSettings.Server closed = StorageSettings.Server.of("127.0.0.1", 1, "app", "shop", "secret",
            StorageSettings.SslMode.DISABLE).tablePrefix("p_");
        StorageSettings settings = StorageSettings.server(dialect, closed).pool(POOL);
        String name = "refused-" + dialect.id();

        CompletableFuture<Storage> started = Storage.start(TestPlugins.plugin(name, folder, log.logger), errors,
            settings, List.of(new Migration(1, "probe")), StorageContract.CLOCK);

        assertThatThrownBy(() -> started.get(30, TimeUnit.SECONDS))
            .cause().isInstanceOf(RuntimeException.class).hasMessageContaining("Failed to initialize pool");
        SqliteStartTest.awaitNoThreadNamed(name + "-db-");
        assertThat(reported).isEmpty();
    }

    private Storage over() {
        return Storage.over(TestPlugins.plugin("over-probe", log.logger), errors, Dialect.POSTGRES, source, "p_",
            StorageContract.CLOCK);
    }

    private static StorageSettings sqlite() {
        return StorageSettings.sqlite("data.db").pool(POOL).asyncThreads(1).backupKeep(0);
    }

    private static StorageSettings.Server server(String host, StorageSettings.SslMode ssl) {
        return StorageSettings.Server.of(host, 3307, "app", "shop", "secret", ssl).tablePrefix("p_");
    }
}
