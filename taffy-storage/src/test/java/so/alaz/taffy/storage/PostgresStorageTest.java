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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    String createProbe(String table) {
        return "CREATE TABLE " + table + " (id UUID NOT NULL PRIMARY KEY, state VARCHAR(16) NOT NULL, amount BIGINT,"
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
                StorageSettings.Server other = new StorageSettings.Server(base.host(), base.port(), base.database(),
                    role, password, base.sslMode(), prefix);

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
        StorageSettings settings = new StorageSettings(Dialect.POSTGRES, "data.db", server,
            new StorageSettings.Pool(4, 1, Duration.ofSeconds(10)), 2, 5);
        return TestPlugins.start(TestPlugins.plugin(NAME, Path.of("unused"), Logger.getAnonymousLogger(),
            ProbeSchema.source(scripts)), settings, migrations, baseline, CLOCK);
    }

    private static StorageSettings.Server server(String prefix) {
        return new StorageSettings.Server(DATABASE.getHost(), DATABASE.getFirstMappedPort(),
            DATABASE.getDatabaseName(), DATABASE.getUsername(), DATABASE.getPassword(),
            StorageSettings.SslMode.DISABLE, prefix);
    }

    @Override
    Connection connect() throws SQLException {
        StorageSettings.Server server = server("");
        return DriverManager.getConnection(Storage.serverUrl(Dialect.POSTGRES, server), server.user(),
            server.password());
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
