package so.alaz.taffy.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import so.alaz.taffy.platform.ErrorReports;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
    String createProbe(String table) {
        return "CREATE TABLE " + table + " (id UUID NOT NULL PRIMARY KEY, state VARCHAR(16) NOT NULL, amount BIGINT,"
            + " at BIGINT, doc JSONB, data BYTEA)";
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

    private static CompletableFuture<Storage> start(String prefix, List<Migration> migrations,
                                                    Map<String, String> scripts) {
        StorageSettings settings = new StorageSettings(Dialect.POSTGRES, "data.db", server(prefix),
            new StorageSettings.Pool(4, 1, Duration.ofSeconds(10)), 2, 5);
        return Storage.start(settings, Path.of("unused"), NAME, "9.9.9", migrations, ProbeSchema.source(scripts),
            Logger.getAnonymousLogger(), new ErrorReports(), CLOCK);
    }

    private static StorageSettings.Server server(String prefix) {
        return new StorageSettings.Server(DATABASE.getHost(), DATABASE.getFirstMappedPort(),
            DATABASE.getDatabaseName(), DATABASE.getUsername(), DATABASE.getPassword(),
            StorageSettings.SslMode.DISABLE, prefix);
    }

    private static Connection connect() throws SQLException {
        StorageSettings.Server server = server("");
        return DriverManager.getConnection(Storage.serverUrl(Dialect.POSTGRES, server), server.user(),
            server.password());
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
