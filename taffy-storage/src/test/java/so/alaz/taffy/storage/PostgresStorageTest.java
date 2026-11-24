package so.alaz.taffy.storage;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import so.alaz.taffy.test.FaultyDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
final class PostgresStorageTest extends StorageContract {

    @Container
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(DatabaseImages.POSTGRES);

    private static final String NAME = "postgres-probe";

    private Storage storage;

    @Override
    Storage storage() {
        if (storage == null) {
            storage = start("contract_", List.of(), Map.of()).join();
        }
        return storage;
    }

    @Override
    String name() {
        return NAME;
    }

    @Override
    Dialect dialect() {
        return Dialect.POSTGRES;
    }

    @Override
    CompletableFuture<Storage> starting(String prefix, List<Migration> migrations, Map<String, String> scripts,
                                        @Nullable Baseline baseline) {
        return start(server(prefix), migrations, scripts, baseline);
    }

    @Override
    CompletableFuture<Storage> startingAs(String plugin, String prefix, @Nullable String versionTable,
                                          List<Migration> migrations, Map<String, String> scripts,
                                          @Nullable Baseline baseline) {
        StorageSettings settings = StorageSettings.server(Dialect.POSTGRES, server(prefix));
        return TestPlugins.start(TestPlugins.plugin(plugin, Path.of("unused"), Logger.getAnonymousLogger(),
            ProbeSchema.source(scripts)), versionTable == null ? settings : settings.versionTable(versionTable),
            migrations, baseline, CLOCK);
    }

    @Override
    String createProbe(String table) {
        return "CREATE TABLE " + table + " (id UUID NOT NULL PRIMARY KEY, state VARCHAR(48) NOT NULL, amount BIGINT,"
            + " at BIGINT, doc JSONB, data BYTEA, wide NUMERIC)";
    }

    @AfterEach
    void close() {
        assertThat(storage.close(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void theMigrationsFromEmptyCreateOnlyPrefixedTables() throws Exception {
        String prefix = "fresh_";
        try (Connection connection = connect()) {
            try {
                start(prefix, ProbeSchema.MIGRATIONS, ProbeSchema.scripts(Dialect.POSTGRES)).join()
                    .close(Duration.ofSeconds(5));

                assertThat(ProbeSchema.tables(connection, Dialect.POSTGRES, prefix))
                    .containsExactlyElementsOf(ProbeSchema.prefixed(prefix, ProbeSchema.TABLES));
                for (Migration migration : ProbeSchema.MIGRATIONS) {
                    assertThat(ProbeSchema.appliedAt(connection, prefix, migration.version()))
                        .isEqualTo(NOW.toEpochMilli());
                }
                assertThat(indexes(connection, prefix + "beta")).containsExactlyInAnyOrder(prefix + "beta_pkey",
                    prefix + "beta_alpha");
                assertThat(ProbeSchema.ids(connection, prefix + "alpha", "id")).containsExactly(1);
            } finally {
                ProbeSchema.drop(connection, Dialect.POSTGRES, prefix);
            }
        }
    }

    @Test
    void aFailingMigrationLeavesNoTraceAndRunsWholeOnTheNextStart() throws Exception {
        String prefix = "broken_";
        Map<String, String> scripts = ProbeSchema.scripts(Dialect.POSTGRES);
        String second = ProbeSchema.MIGRATIONS.get(1).resource(Dialect.POSTGRES);
        String working = scripts.get(second);
        scripts.put(second, """
            CREATE TABLE ${prefix}beta (id INTEGER NOT NULL PRIMARY KEY);
            INSERT INTO ${prefix}missing VALUES (1);
            """);
        try (Connection connection = connect()) {
            try {
                assertThatThrownBy(() -> start(prefix, ProbeSchema.MIGRATIONS, scripts).join())
                    .cause().isInstanceOf(SQLException.class);

                assertThat(ProbeSchema.tables(connection, Dialect.POSTGRES, prefix))
                    .containsExactly(prefix + "alpha", prefix + "schema_version");
                assertThat(ProbeSchema.versions(connection, prefix)).containsExactly(1);

                scripts.put(second, working);
                start(prefix, ProbeSchema.MIGRATIONS, scripts).join().close(Duration.ofSeconds(5));

                assertThat(ProbeSchema.tables(connection, Dialect.POSTGRES, prefix))
                    .containsExactlyElementsOf(ProbeSchema.prefixed(prefix, ProbeSchema.TABLES));
                assertThat(ProbeSchema.versions(connection, prefix)).containsExactly(1, 2, 3);
                assertThat(ProbeSchema.statementsDone(connection, prefix, 2)).isEqualTo(3);
            } finally {
                ProbeSchema.drop(connection, Dialect.POSTGRES, prefix);
            }
        }
    }

    @Test
    void aFailingFirstMigrationLeavesNoVersionTable() throws Exception {
        String prefix = "first_";
        Map<String, String> scripts = ProbeSchema.scripts(Dialect.POSTGRES);
        scripts.put(ProbeSchema.MIGRATIONS.getFirst().resource(Dialect.POSTGRES), """
            CREATE TABLE ${prefix}alpha (id INTEGER NOT NULL PRIMARY KEY);
            INSERT INTO ${prefix}missing VALUES (1);
            """);
        try (Connection connection = connect()) {
            try {
                assertThatThrownBy(() -> start(prefix, ProbeSchema.MIGRATIONS, scripts).join())
                    .cause().isInstanceOf(SQLException.class);

                assertThat(ProbeSchema.tables(connection, Dialect.POSTGRES, prefix)).isEmpty();
            } finally {
                ProbeSchema.drop(connection, Dialect.POSTGRES, prefix);
            }
        }
    }

    @Test
    void twoStartsSharingADatabaseApplyAMigrationOnce() throws Exception {
        String prefix = "shared_";
        Migration slow = new Migration(1, "slow");
        Map<String, String> scripts = Map.of(slow.resource(Dialect.POSTGRES), """
            SELECT pg_sleep(2);
            CREATE TABLE ${prefix}slow (id INTEGER);
            INSERT INTO ${prefix}slow VALUES (1);
            """);
        try (Connection connection = connect()) {
            try {
                CompletableFuture<Storage> first = start(prefix, List.of(slow), scripts);
                CompletableFuture<Storage> second = start(prefix, List.of(slow), scripts);
                first.join().close(Duration.ofSeconds(5));
                second.join().close(Duration.ofSeconds(5));

                assertThat(ProbeSchema.versions(connection, prefix)).containsExactly(1);
                assertThat(ProbeSchema.ids(connection, prefix + "slow", "id")).containsExactly(1);
            } finally {
                ProbeSchema.drop(connection, Dialect.POSTGRES, prefix);
            }
        }
    }

    @Test
    void theMigrationLockIsKeyedByTheSchema() throws Exception {
        String prefix = "keyed_";
        String role = "taffy_other";
        String password = UUID.randomUUID().toString();
        Migration first = new Migration(1, "alpha");
        Migration second = new Migration(2, "beta");
        Map<String, String> scripts = Map.of(
            first.resource(Dialect.POSTGRES), "CREATE TABLE ${prefix}alpha (id INTEGER);\n",
            second.resource(Dialect.POSTGRES), "CREATE TABLE ${prefix}beta (id INTEGER);\n");
        long publicKey = Migrator.lockKey("public", prefix + "schema_version");
        long otherKey = Migrator.lockKey(role, prefix + "schema_version");
        try (Connection holder = connect(); Statement admin = holder.createStatement()) {
            admin.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + password + "'");
            admin.execute("CREATE SCHEMA " + role + " AUTHORIZATION " + role);
            try {
                assertThat(advisory(holder, "pg_try_advisory_lock", publicKey)).isTrue();
                StorageSettings.Server base = server(prefix);
                StorageSettings.Server other = StorageSettings.Server.of(base.host(), base.port(), base.database(),
                    role, password, base.sslMode()).tablePrefix(prefix);

                start(other, List.of(first), scripts).orTimeout(30, TimeUnit.SECONDS).join()
                    .close(Duration.ofSeconds(5));

                assertThat(advisory(holder, "pg_try_advisory_lock", otherKey)).isTrue();
                CompletableFuture<Storage> waiting = start(other, List.of(first, second), scripts);
                assertThatThrownBy(() -> waiting.get(2, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
                assertThat(advisory(holder, "pg_advisory_unlock", otherKey)).isTrue();
                waiting.orTimeout(30, TimeUnit.SECONDS).join().close(Duration.ofSeconds(5));

                assertThat(schemaTables(holder, role)).containsExactly(prefix + "alpha", prefix + "beta",
                    prefix + "schema_version");
            } finally {
                admin.execute("SELECT pg_advisory_unlock_all()");
                admin.execute("DROP SCHEMA IF EXISTS " + role + " CASCADE");
                admin.execute("DROP ROLE IF EXISTS " + role);
            }
        }
    }

    @Test
    void aVersionTableInAnotherLayoutLeavesTheConnectionUsableAndTheLockFree() throws Exception {
        String prefix = "alien_";
        String versionTable = prefix + "schema_version";
        Migrator migrator = new Migrator(Dialect.POSTGRES, prefix, CLOCK, Logger.getAnonymousLogger(),
            ProbeSchema.MIGRATIONS, ProbeSchema.source(ProbeSchema.scripts(Dialect.POSTGRES)));
        try (Connection connection = connect(); Connection other = connect();
             Statement statement = connection.createStatement()) {
            try {
                statement.execute("CREATE TABLE " + versionTable + " (version INTEGER NOT NULL)");
                statement.execute("INSERT INTO " + versionTable + " VALUES (7)");

                assertThatThrownBy(() -> migrator.migrate(connection, null))
                    .isInstanceOf(StorageException.class)
                    .hasMessageStartingWith("the database holds a table named " + versionTable + " that storage");

                assertThat(connection.getAutoCommit()).isTrue();
                assertThat(ProbeSchema.ids(connection, versionTable, "version")).containsExactly(7);
                long key = Migrator.lockKey("public", versionTable);
                assertThat(advisory(other, "pg_try_advisory_lock", key)).isTrue();
                assertThat(advisory(other, "pg_advisory_unlock", key)).isTrue();
            } finally {
                statement.execute("DROP TABLE IF EXISTS " + versionTable);
            }
        }
    }

    @Test
    void aFailedTakeoverRenameRollsTheTakeoverBackAndTheNextStartCompletesIt() throws Exception {
        String prefix = "rolled_";
        String legacy = prefix + "legacy_version";
        Baseline baseline = Baseline.versionTable(legacy);
        Map<String, String> scripts = ProbeSchema.scripts(Dialect.POSTGRES);
        StorageSettings.Server server = server(prefix);
        Properties credentials = new Properties();
        credentials.setProperty("user", server.user());
        credentials.setProperty("password", server.password());
        FaultyDataSource faulty = new FaultyDataSource(Storage.serverUrl(Dialect.POSTGRES, server), credentials);
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try {
                new Migrator(Dialect.POSTGRES, prefix, CLOCK, Logger.getAnonymousLogger(),
                    ProbeSchema.MIGRATIONS.subList(0, 2), ProbeSchema.source(scripts)).migrate(connection, null);
                statement.execute("DROP TABLE " + prefix + "schema_version");
                statement.execute("CREATE TABLE " + legacy + " (version INTEGER NOT NULL)");
                statement.execute("INSERT INTO " + legacy + " VALUES (2)");
                faulty.faults().failMatching("RENAME TO", 1);

                try (Connection failing = faulty.getConnection()) {
                    assertThatThrownBy(() -> new Migrator(Dialect.POSTGRES, prefix, CLOCK,
                        Logger.getAnonymousLogger(), ProbeSchema.MIGRATIONS, ProbeSchema.source(scripts), baseline)
                        .migrate(failing, null))
                        .isInstanceOf(SQLException.class).hasMessage("injected failure of RENAME TO");
                    assertThat(failing.getAutoCommit()).isTrue();
                    assertThat(ProbeSchema.ids(failing, legacy, "version")).containsExactly(2);
                }

                assertThat(ProbeSchema.tables(connection, Dialect.POSTGRES, prefix))
                    .containsExactly(prefix + "alpha", prefix + "beta", legacy);

                start(server, ProbeSchema.MIGRATIONS, scripts, baseline).join().close(Duration.ofSeconds(5));

                assertThat(ProbeSchema.tables(connection, Dialect.POSTGRES, prefix)).containsExactly(prefix + "alpha",
                    prefix + "beta", prefix + "gamma", legacy + "_baseline", prefix + "schema_version");
                assertThat(ProbeSchema.versions(connection, prefix)).containsExactly(1, 2, 3);
                assertThat(ProbeSchema.ids(connection, legacy + "_baseline", "version")).containsExactly(2);
            } finally {
                ProbeSchema.drop(connection, Dialect.POSTGRES, prefix);
            }
        }
    }

    @Test
    void aSameNameTakeoverThatFailsAfterItsRenameIsRolledBackAndTheNextStartCompletesIt() throws Exception {
        String prefix = "adopted_";
        String versionTable = prefix + "schema_version";
        Baseline baseline = Baseline.versionTable(versionTable).takeOverSameName();
        Map<String, String> scripts = ProbeSchema.scripts(Dialect.POSTGRES);
        StorageSettings.Server server = server(prefix);
        Properties credentials = new Properties();
        credentials.setProperty("user", server.user());
        credentials.setProperty("password", server.password());
        FaultyDataSource faulty = new FaultyDataSource(Storage.serverUrl(Dialect.POSTGRES, server), credentials);
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try {
                new Migrator(Dialect.POSTGRES, prefix, CLOCK, Logger.getAnonymousLogger(),
                    ProbeSchema.MIGRATIONS.subList(0, 2), ProbeSchema.source(scripts)).migrate(connection, null);
                statement.execute("DROP TABLE " + versionTable);
                statement.execute("CREATE TABLE " + versionTable + " (version INTEGER NOT NULL PRIMARY KEY)");
                statement.execute("INSERT INTO " + versionTable + " VALUES (2)");
                faulty.faults().failMatching("CREATE TABLE IF NOT EXISTS " + versionTable, 1);

                try (Connection failing = faulty.getConnection()) {
                    assertThatThrownBy(() -> new Migrator(Dialect.POSTGRES, prefix, CLOCK,
                        Logger.getAnonymousLogger(), ProbeSchema.MIGRATIONS, ProbeSchema.source(scripts), baseline)
                        .migrate(failing, null))
                        .isInstanceOf(SQLException.class)
                        .hasMessage("injected failure of CREATE TABLE IF NOT EXISTS " + versionTable);
                    assertThat(failing.getAutoCommit()).isTrue();
                }

                assertThat(ProbeSchema.tables(connection, Dialect.POSTGRES, prefix))
                    .containsExactly(prefix + "alpha", prefix + "beta", versionTable);
                assertThat(columns(connection, versionTable)).containsExactly("version");
                assertThat(ProbeSchema.ids(connection, versionTable, "version")).containsExactly(2);

                start(server, ProbeSchema.MIGRATIONS, scripts, baseline).join().close(Duration.ofSeconds(5));
                start(server, ProbeSchema.MIGRATIONS, scripts, baseline).join().close(Duration.ofSeconds(5));

                assertThat(ProbeSchema.tables(connection, Dialect.POSTGRES, prefix)).containsExactly(prefix + "alpha",
                    prefix + "beta", prefix + "gamma", versionTable, versionTable + "_baseline");
                assertThat(ProbeSchema.versions(connection, prefix)).containsExactly(1, 2, 3);
                assertThat(ProbeSchema.ids(connection, versionTable + "_baseline", "version")).containsExactly(2);
            } finally {
                ProbeSchema.drop(connection, Dialect.POSTGRES, prefix);
            }
        }
    }

    @Test
    void aSerializableCommitThatFailsWith40001IsRetriedAndBothTransactionsCommitOnce() throws Exception {
        String table = "skew_duty";
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table
                + " (doctor VARCHAR(16) NOT NULL PRIMARY KEY, on_call BOOLEAN NOT NULL)");
            try {
                statement.execute("INSERT INTO " + table + " VALUES ('alice', TRUE), ('bob', TRUE)");
                CyclicBarrier bothWrote = new CyclicBarrier(2);
                AtomicInteger attempts = new AtomicInteger();
                AtomicInteger committed = new AtomicInteger();

                CompletableFuture<Long> alice = leaving(table, "alice", bothWrote, attempts, committed);
                CompletableFuture<Long> bob = leaving(table, "bob", bothWrote, attempts, committed);

                assertThat(List.of(alice.get(30, TimeUnit.SECONDS), bob.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(2L, 1L);
                assertThat(attempts).hasValue(3);
                assertThat(committed).hasValue(2);
                try (ResultSet onCall = statement.executeQuery(
                    "SELECT COUNT(*) FROM " + table + " WHERE on_call")) {
                    assertThat(onCall.next()).isTrue();
                    assertThat(onCall.getInt(1)).isEqualTo(1);
                }
            } finally {
                statement.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }

    // A doctor goes off call when two are on call. The first attempt waits, after its write, until the other
    // transaction has written too, so both read two doctors on call and the commit of the second fails with 40001.
    private CompletableFuture<Long> leaving(String table, String doctor, CyclicBarrier bothWrote,
                                            AtomicInteger attempts, AtomicInteger committed) {
        AtomicInteger own = new AtomicInteger();
        return storage().asyncRetrying(tx -> {
            attempts.incrementAndGet();
            tx.afterCommit(committed::incrementAndGet);
            tx.execute("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE");
            long onCall = tx.queryOne("SELECT COUNT(*) AS n FROM " + table + " WHERE on_call", Tx.Binder.NONE,
                row -> row.integer("n")).orElseThrow();
            if (onCall >= 2) {
                tx.update("UPDATE " + table + " SET on_call = FALSE WHERE doctor = ?",
                    params -> params.text(doctor));
            }
            if (own.incrementAndGet() == 1) {
                try {
                    bothWrote.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException | BrokenBarrierException | TimeoutException unmet) {
                    throw new IllegalStateException("the other transaction did not write", unmet);
                }
            }
            return onCall;
        });
    }

    @Test
    void aMigrationLockHeldByAnotherConnectionStopsTheStartOnceTheWaitHasPassed() throws Exception {
        String prefix = "held_";
        String versionTable = prefix + "schema_version";
        long key = Migrator.lockKey("public", versionTable);
        Migrator migrator = new Migrator(Dialect.POSTGRES, prefix, versionTable, CLOCK, Logger.getAnonymousLogger(),
            ProbeSchema.MIGRATIONS, ProbeSchema.source(ProbeSchema.scripts(Dialect.POSTGRES)), null,
            Duration.ofSeconds(2));
        try (Connection holder = connect(); Connection connection = connect();
             Statement holding = holder.createStatement()) {
            try {
                assertThat(advisory(holder, "pg_try_advisory_lock", key)).isTrue();
                long begun = System.nanoTime();

                assertThatThrownBy(() -> migrator.migrate(connection, null))
                    .isInstanceOf(StorageException.class)
                    .hasMessage("another start has held the migration lock of " + versionTable
                        + " in database public for 2s; restart once the other server has started");

                assertThat(Duration.ofNanos(System.nanoTime() - begun))
                    .isBetween(Duration.ofSeconds(2), Duration.ofSeconds(20));
                assertThat(connection.getAutoCommit()).isTrue();
                assertThat(ProbeSchema.tables(connection, Dialect.POSTGRES, prefix)).isEmpty();
                assertThat(advisory(holder, "pg_advisory_unlock", key)).isTrue();

                migrator.migrate(connection, null);

                assertThat(ProbeSchema.versions(connection, prefix)).containsExactly(1, 2, 3);
                assertThat(advisory(holder, "pg_try_advisory_lock", key)).isTrue();
            } finally {
                holding.execute("SELECT pg_advisory_unlock_all()");
                ProbeSchema.drop(connection, Dialect.POSTGRES, prefix);
            }
        }
    }

    @Test
    void twoPluginsUnderOnePrefixKeepAHistoryAndAMigrationLockEach() throws Exception {
        String prefix = "duo_";
        String shopTable = prefix + "shop_plugin_schema_version";
        String bankTable = prefix + "bank_plugin_schema_version";
        Migration shop = new Migration(1, "shop");
        Migration bank = new Migration(1, "bank");
        Migration more = new Migration(2, "more");
        Map<String, String> shopScripts = Map.of(
            shop.resource(Dialect.POSTGRES), "CREATE TABLE ${prefix}shop (id INTEGER);\n");
        Map<String, String> bankScripts = Map.of(
            bank.resource(Dialect.POSTGRES), "CREATE TABLE ${prefix}bank (id INTEGER);\n",
            more.resource(Dialect.POSTGRES), "CREATE TABLE ${prefix}more (id INTEGER);\n");
        long shopKey = Migrator.lockKey("public", shopTable);
        try (Connection connection = connect(); Connection holder = connect();
             Statement holding = holder.createStatement()) {
            try {
                startAs("Shop-Plugin", prefix, List.of(shop), shopScripts).join().close(Duration.ofSeconds(5));
                startAs("Bank-Plugin", prefix, List.of(bank), bankScripts).join().close(Duration.ofSeconds(5));

                assertThat(ProbeSchema.tables(connection, Dialect.POSTGRES, prefix))
                    .containsExactly(prefix + "bank", bankTable, prefix + "shop", shopTable);
                assertThat(ProbeSchema.ids(connection, shopTable, "version")).containsExactly(1);
                assertThat(ProbeSchema.ids(connection, bankTable, "version")).containsExactly(1);

                assertThat(advisory(holder, "pg_try_advisory_lock", shopKey)).isTrue();
                startAs("Bank-Plugin", prefix, List.of(bank, more), bankScripts).orTimeout(30, TimeUnit.SECONDS)
                    .join().close(Duration.ofSeconds(5));
                assertThat(ProbeSchema.ids(connection, bankTable, "version")).containsExactly(1, 2);
                CompletableFuture<Storage> waiting = startAs("Shop-Plugin", prefix, List.of(shop), shopScripts);
                assertThatThrownBy(() -> waiting.get(2, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
                assertThat(advisory(holder, "pg_advisory_unlock", shopKey)).isTrue();
                waiting.orTimeout(30, TimeUnit.SECONDS).join().close(Duration.ofSeconds(5));

                assertThat(ProbeSchema.ids(connection, shopTable, "version")).containsExactly(1);
                assertThat(ProbeSchema.tables(connection, Dialect.POSTGRES, prefix)).containsExactly(prefix + "bank",
                    bankTable, prefix + "more", prefix + "shop", shopTable);
            } finally {
                holding.execute("SELECT pg_advisory_unlock_all()");
                ProbeSchema.drop(connection, Dialect.POSTGRES, prefix);
            }
        }
    }

    @Test
    void aVersionTableOfAnEarlierReleaseIsCopiedIntoThePluginsOwnVersionTable() throws Exception {
        String prefix = "earlier_";
        String earlier = prefix + "schema_version";
        String own = prefix + "postgres_probe_schema_version";
        Map<String, String> scripts = ProbeSchema.scripts(Dialect.POSTGRES);
        try (Connection connection = connect()) {
            try {
                new Migrator(Dialect.POSTGRES, prefix, CLOCK, Logger.getAnonymousLogger(),
                    ProbeSchema.MIGRATIONS.subList(0, 2), ProbeSchema.source(scripts)).migrate(connection, null);

                startAs(NAME, prefix, ProbeSchema.MIGRATIONS, scripts).join().close(Duration.ofSeconds(5));
                startAs(NAME, prefix, ProbeSchema.MIGRATIONS, scripts).join().close(Duration.ofSeconds(5));

                assertThat(ProbeSchema.tables(connection, Dialect.POSTGRES, prefix)).containsExactlyInAnyOrder(
                    prefix + "alpha", prefix + "beta", prefix + "gamma", earlier, own);
                assertThat(ProbeSchema.ids(connection, own, "version")).containsExactly(1, 2, 3);
                assertThat(ProbeSchema.ids(connection, earlier, "version")).containsExactly(1, 2);
                assertThat(ProbeSchema.ids(connection, prefix + "alpha", "id")).containsExactly(1);
            } finally {
                ProbeSchema.drop(connection, Dialect.POSTGRES, prefix);
            }
        }
    }

    @Test
    void twoTransactionsThatLockTwoRowsInOppositeOrderBothCommitOnceAfterTheLoserIsRetried() throws Exception {
        String prefix = "deadlock_";
        Storage threaded = start(StorageSettings.server(Dialect.POSTGRES, server(prefix)).asyncThreads(2));
        try (Connection connection = connect()) {
            try {
                DeadlockScenario.run(threaded, connection, prefix + "rows");
            } finally {
                assertThat(threaded.close(Duration.ofSeconds(5))).isTrue();
                ProbeSchema.drop(connection, Dialect.POSTGRES, prefix);
            }
        }
    }

    @Test
    void aStatementThatOutlastsTheSocketTimeoutOfTheServerFailsAndOneWithinItRuns() throws Exception {
        String prefix = "socket_";
        Storage quick = start(StorageSettings.server(Dialect.POSTGRES,
            server(prefix).socketTimeout(Duration.ofSeconds(1))));
        Storage patient = start(StorageSettings.server(Dialect.POSTGRES,
            server(prefix).socketTimeout(Duration.ofSeconds(20))));
        try (Connection connection = connect()) {
            try {
                long begun = System.nanoTime();

                assertThatThrownBy(() -> quick.async(tx -> {
                    tx.execute("SELECT pg_sleep(6)");
                    return null;
                }).get(30, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).cause().isInstanceOf(SQLException.class);

                assertThat(Duration.ofNanos(System.nanoTime() - begun)).isLessThan(Duration.ofSeconds(6));
                assertThat(quick.read(tx -> tx.queryOne("SELECT 1 AS n", Tx.Binder.NONE, row -> row.integer("n"))
                    .orElseThrow()).get(30, TimeUnit.SECONDS)).isEqualTo(1L);
                assertThat(patient.async(tx -> {
                    tx.execute("SELECT pg_sleep(3)");
                    return "slept";
                }).get(30, TimeUnit.SECONDS)).isEqualTo("slept");
            } finally {
                quick.close(Duration.ofSeconds(5));
                patient.close(Duration.ofSeconds(5));
                ProbeSchema.drop(connection, Dialect.POSTGRES, prefix);
            }
        }
    }

    @Test
    void theDriverPropertiesOfTheServerReachEveryConnection() throws Exception {
        String prefix = "props_";
        Storage plain = start(StorageSettings.server(Dialect.POSTGRES, server(prefix)));
        Storage tuned = start(StorageSettings.server(Dialect.POSTGRES,
            server(prefix).properties(Map.of("ApplicationName", "taffy-probe"))).asyncThreads(2));
        TxWork<String> application = tx -> tx.queryOne("SELECT current_setting('application_name') AS app",
            Tx.Binder.NONE, row -> row.text("app")).orElseThrow();
        try (Connection connection = connect()) {
            try {
                assertThat(plain.read(application).get(30, TimeUnit.SECONDS)).isNotEqualTo("taffy-probe");
                assertThat(tuned.read(application).get(30, TimeUnit.SECONDS)).isEqualTo("taffy-probe");
                assertThat(tuned.async(application).get(30, TimeUnit.SECONDS)).isEqualTo("taffy-probe");
            } finally {
                plain.close(Duration.ofSeconds(5));
                tuned.close(Duration.ofSeconds(5));
                ProbeSchema.drop(connection, Dialect.POSTGRES, prefix);
            }
        }
    }

    /** Starts storage with no migrations, recording them in {@code <prefix>schema_version}. */
    private static Storage start(StorageSettings settings) {
        return TestPlugins.start(TestPlugins.plugin(NAME, Path.of("unused"), Logger.getAnonymousLogger()),
            settings.versionTable(settings.tablePrefix() + "schema_version"), List.of(), null, CLOCK).join();
    }

    /** Starts storage for the plugin named {@code plugin}, which records its migrations in the default table. */
    private static CompletableFuture<Storage> startAs(String plugin, String prefix, List<Migration> migrations,
                                                      Map<String, String> scripts) {
        return TestPlugins.start(TestPlugins.plugin(plugin, Path.of("unused"), Logger.getAnonymousLogger(),
            ProbeSchema.source(scripts)), StorageSettings.server(Dialect.POSTGRES, server(prefix)), migrations, null,
            CLOCK);
    }

    private static CompletableFuture<Storage> start(String prefix, List<Migration> migrations,
                                                    Map<String, String> scripts) {
        return start(server(prefix), migrations, scripts);
    }

    private static CompletableFuture<Storage> start(StorageSettings.Server server, List<Migration> migrations,
                                                    Map<String, String> scripts) {
        return start(server, migrations, scripts, null);
    }

    private static CompletableFuture<Storage> start(StorageSettings.Server server, List<Migration> migrations,
                                                    Map<String, String> scripts, @Nullable Baseline baseline) {
        StorageSettings settings = StorageSettings.server(Dialect.POSTGRES, server)
            .versionTable(server.tablePrefix() + "schema_version");
        return TestPlugins.start(TestPlugins.plugin(NAME, Path.of("unused"), Logger.getAnonymousLogger(),
            ProbeSchema.source(scripts)), settings, migrations, baseline, CLOCK);
    }

    private static StorageSettings.Server server(String prefix) {
        return StorageSettings.Server.of(DATABASE.getHost(), DATABASE.getFirstMappedPort(),
            DATABASE.getDatabaseName(), DATABASE.getUsername(), DATABASE.getPassword(),
            StorageSettings.SslMode.DISABLE).tablePrefix(prefix);
    }

    @Override
    Connection connect() throws SQLException {
        StorageSettings.Server server = server("");
        return DriverManager.getConnection(Storage.serverUrl(Dialect.POSTGRES, server), server.user(),
            server.password());
    }

    @Test
    void anInsertNamesTheKeyColumnInTheLetterCaseTheCatalogHolds() {
        String table = keyed();
        String quoted = storage().table("quoted_keyed");
        String sql = "INSERT INTO " + table + " (label) VALUES (?)";
        String intoQuoted = "INSERT INTO " + quoted + " (label) VALUES (?)";
        storage().async(tx -> {
            tx.execute("DROP TABLE IF EXISTS " + quoted);
            tx.execute("CREATE TABLE " + quoted + " (label VARCHAR(16), \"Id\" BIGSERIAL PRIMARY KEY)");
            return null;
        }).join();

        long key = storage().async(tx -> tx.insert(intoQuoted, "Id", params -> params.text("quoted"))).join();

        assertThat(storage().read(tx -> tx.queryOne("SELECT label FROM " + quoted + " WHERE \"Id\" = ?",
            params -> params.integer(key), row -> row.text("label"))).join()).contains("quoted");
        assertThatThrownBy(() -> storage().async(tx -> tx.insert(intoQuoted, "id", params -> params.text("lower")))
            .join()).cause().isInstanceOf(SQLException.class).hasMessageContaining("column \"id\" does not exist");
        assertThatThrownBy(() -> storage().async(tx -> tx.insert(sql, "ID", params -> params.text("upper")))
            .join()).cause().isInstanceOf(SQLException.class).hasMessageContaining("column \"ID\" does not exist");
        assertThat(keyedLabels(table)).isEmpty();
    }

    @Test
    void anInsertRefusesAKeyColumnThatHoldsNoWholeNumberThatFitsALong() {
        String table = storage().table("odd_keyed");
        String sql = "INSERT INTO " + table + " (label) VALUES ('odd')";
        storage().async(tx -> {
            tx.execute("DROP TABLE IF EXISTS " + table);
            tx.execute("CREATE TABLE " + table + " (label VARCHAR(16), id UUID NOT NULL DEFAULT gen_random_uuid(),"
                + " big NUMERIC(30) NOT NULL DEFAULT 123456789012345678901234567890,"
                + " fits NUMERIC(30) NOT NULL DEFAULT 42, absent BIGINT)");
            return null;
        }).join();

        assertThat(storage().async(tx -> tx.insert(sql, "fits", Tx.Binder.NONE)).join()).isEqualTo(42L);
        assertThatThrownBy(() -> storage().async(tx -> tx.insert(sql, "id", Tx.Binder.NONE)).join())
            .cause().isInstanceOf(SQLException.class).hasMessage(
                "the generated key is a java.util.UUID that is not a whole number that fits a long: " + sql);
        assertThatThrownBy(() -> storage().async(tx -> tx.insert(sql, "big", Tx.Binder.NONE)).join())
            .cause().isInstanceOf(SQLException.class).hasMessage(
                "the generated key is a java.math.BigDecimal that is not a whole number that fits a long: " + sql);
        assertThatThrownBy(() -> storage().async(tx -> tx.insert(sql, "absent", Tx.Binder.NONE)).join())
            .cause().isInstanceOf(SQLException.class).hasMessage("the statement generated no key: " + sql);
        assertThat(storage().read(tx -> tx.query("SELECT fits FROM " + table, Tx.Binder.NONE,
            row -> row.integer("fits"))).join()).containsExactly(42L);
    }

    @Test
    void anInsertRefusesAReturningClauseOfItsOwnThatIsNotTheKeyColumn() {
        String table = storage().table("returning_keyed");
        String insert = "INSERT INTO " + table + " (label) VALUES (?) RETURNING ";
        storage().async(tx -> {
            tx.execute("DROP TABLE IF EXISTS " + table);
            tx.execute("CREATE TABLE " + table + " (label VARCHAR(16), other BIGINT NOT NULL DEFAULT 777,"
                + " id BIGSERIAL PRIMARY KEY)");
            return null;
        }).join();

        long key = storage().async(tx -> tx.insert(insert + "id", "id", params -> params.text("same"))).join();

        assertThat(storage().read(tx -> tx.queryOne("SELECT label FROM " + table + " WHERE id = ?",
            params -> params.integer(key), row -> row.text("label"))).join()).contains("same");
        for (String returning : List.of("other", "other, id", "id, other", "*")) {
            String sql = insert + returning;
            assertThatThrownBy(() -> storage().async(tx -> tx.insert(sql, "id", params -> params.text("own")))
                .join()).cause().isInstanceOf(SQLException.class).hasMessage(
                    "the statement has a RETURNING clause of its own, and insert returns the key column id: " + sql);
        }
        assertThat(storage().read(tx -> tx.query("SELECT label FROM " + table, Tx.Binder.NONE,
            row -> row.text("label"))).join()).containsExactly("same");
    }

    @Test
    void anInsertOnConflictDoUpdateThatFindsTheRowReturnsTheKeyOfThatRow() {
        String table = keyed();
        String upsert = "INSERT INTO " + table + " (label) VALUES ('taken') ON CONFLICT (label) DO UPDATE SET"
            + " label = 'taken'";

        long inserted = storage().async(tx -> tx.insert(upsert, "id", Tx.Binder.NONE)).join();
        long updated = storage().async(tx -> tx.insert(upsert, "id", Tx.Binder.NONE)).join();

        assertThat(updated).isEqualTo(inserted);
        assertThat(keyedLabels(table)).containsExactly("taken");
    }

    private static boolean advisory(Connection connection, String function, long key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + function + "(?)")) {
            statement.setLong(1, key);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static List<String> schemaTables(Connection connection, String schema) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT table_name FROM"
            + " information_schema.tables WHERE table_schema = ? ORDER BY table_name")) {
            statement.setString(1, schema);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    tables.add(rows.getString(1));
                }
            }
        }
        return tables;
    }

    private static List<String> indexes(Connection connection, String table) throws SQLException {
        List<String> indexes = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT indexname FROM pg_indexes"
                 + " WHERE schemaname = current_schema() AND tablename = '" + table + "'")) {
            while (rows.next()) {
                indexes.add(rows.getString(1));
            }
        }
        return indexes;
    }
}
