package so.alaz.taffy.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import so.alaz.taffy.platform.ErrorReports;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The contract plus the migration cases of a database whose DDL commits on its own, MySQL and MariaDB, where a
 * migration records a checkpoint after each statement.
 */
abstract class CheckpointedStorageContract extends StorageContract {

    private static final Instant EARLIER = NOW.minus(Duration.ofDays(30));
    private static final Clock EARLIER_CLOCK = Clock.fixed(EARLIER, ZoneOffset.UTC);
    private static final String CONTRACT_PREFIX = "contract_";
    private static final Migration RESUME = new Migration(4, "resume");

    private Storage storage;

    abstract Dialect dialect();

    abstract StorageSettings.Server server(String prefix);

    @Override
    Storage storage() {
        if (storage == null) {
            storage = start(CONTRACT_PREFIX, List.of(), Map.of());
        }
        return storage;
    }

    @Override
    String createProbe(String table) {
        return "CREATE TABLE " + table + " (id BINARY(16) NOT NULL PRIMARY KEY, state VARCHAR(16) NOT NULL,"
            + " amount BIGINT, at BIGINT, doc JSON, data LONGBLOB) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4"
            + " COLLATE = utf8mb4_bin";
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
                start(prefix, ProbeSchema.MIGRATIONS, ProbeSchema.scripts(dialect())).close(Duration.ofSeconds(5));

                assertThat(ProbeSchema.tables(connection, dialect(), prefix))
                    .containsExactlyElementsOf(ProbeSchema.prefixed(prefix, ProbeSchema.TABLES));
                for (Migration migration : ProbeSchema.MIGRATIONS) {
                    assertThat(ProbeSchema.appliedAt(connection, prefix, migration.version()))
                        .isEqualTo(NOW.toEpochMilli());
                }
                assertThat(indexes(connection, prefix + "beta")).containsExactlyInAnyOrder("PRIMARY",
                    prefix + "beta_alpha");
                assertThat(ProbeSchema.ids(connection, prefix + "alpha", "id")).containsExactly(1);
            } finally {
                ProbeSchema.drop(connection, dialect(), prefix);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("earlierVersions")
    void aDatabaseAtAnEarlierVersionGetsOnlyTheLaterMigrations(int version) throws Exception {
        String prefix = "from" + version + "_";
        try (Connection connection = connect()) {
            try {
                new Migrator(dialect(), prefix, EARLIER_CLOCK, Logger.getAnonymousLogger(),
                    ProbeSchema.MIGRATIONS.subList(0, version),
                    ProbeSchema.source(ProbeSchema.scripts(dialect()))).migrate(connection, null);
                start(prefix, ProbeSchema.MIGRATIONS, ProbeSchema.scripts(dialect())).close(Duration.ofSeconds(5));

                for (Migration migration : ProbeSchema.MIGRATIONS) {
                    long expected = migration.version() <= version ? EARLIER.toEpochMilli() : NOW.toEpochMilli();
                    assertThat(ProbeSchema.appliedAt(connection, prefix, migration.version()))
                        .as("applied_at of %s", migration.id()).isEqualTo(expected);
                }
                assertThat(ProbeSchema.tables(connection, dialect(), prefix))
                    .containsExactlyElementsOf(ProbeSchema.prefixed(prefix, ProbeSchema.TABLES));
                assertThat(ProbeSchema.ids(connection, prefix + "alpha", "id")).containsExactly(1);
            } finally {
                ProbeSchema.drop(connection, dialect(), prefix);
            }
        }
    }

    static IntStream earlierVersions() {
        return IntStream.range(1, ProbeSchema.MIGRATIONS.size());
    }

    @Test
    void aMigrationResumesAfterItsLastCheckpointedStatement() throws Exception {
        String prefix = "resume_";
        Map<String, String> scripts = ProbeSchema.scripts(dialect());
        scripts.put(RESUME.resource(dialect()), """
            CREATE TABLE IF NOT EXISTS ${prefix}log (id INT);
            INSERT INTO ${prefix}log VALUES (1);
            INSERT INTO ${prefix}later VALUES (2);
            """);
        List<Migration> migrations = Stream.concat(ProbeSchema.MIGRATIONS.stream(), Stream.of(RESUME)).toList();
        int version = RESUME.version();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try {
                assertThatThrownBy(() -> migrator(prefix, migrations, scripts).migrate(connection, null))
                    .isInstanceOf(SQLException.class);
                assertThat(ProbeSchema.statementsDone(connection, prefix, version)).isEqualTo(2);
                assertThat(ProbeSchema.appliedAt(connection, prefix, version)).isNull();
                assertThat(ProbeSchema.appliedAt(connection, prefix, 3)).isEqualTo(NOW.toEpochMilli());

                statement.execute("CREATE TABLE " + prefix + "later (id INT)");
                migrator(prefix, migrations, scripts).migrate(connection, null);

                assertThat(ProbeSchema.statementsDone(connection, prefix, version)).isEqualTo(3);
                assertThat(ProbeSchema.appliedAt(connection, prefix, version)).isEqualTo(NOW.toEpochMilli());
                assertThat(ProbeSchema.ids(connection, prefix + "log", "id")).containsExactly(1);
                assertThat(ProbeSchema.ids(connection, prefix + "later", "id")).containsExactly(2);
            } finally {
                ProbeSchema.drop(connection, dialect(), prefix);
            }
        }
    }

    @Test
    void twoStartsSharingADatabaseApplyAMigrationOnce() throws Exception {
        String prefix = "shared_";
        Migration slow = new Migration(1, "slow");
        Map<String, String> scripts = Map.of(slow.resource(dialect()), """
            SELECT SLEEP(2);
            CREATE TABLE ${prefix}slow (id INT);
            INSERT INTO ${prefix}slow VALUES (1);
            """);
        try (Connection connection = connect()) {
            try {
                CompletableFuture<Storage> first = starting(prefix, List.of(slow), scripts);
                CompletableFuture<Storage> second = starting(prefix, List.of(slow), scripts);
                first.join().close(Duration.ofSeconds(5));
                second.join().close(Duration.ofSeconds(5));

                assertThat(ProbeSchema.versions(connection, prefix)).containsExactly(1);
                assertThat(ProbeSchema.ids(connection, prefix + "slow", "id")).containsExactly(1);
            } finally {
                ProbeSchema.drop(connection, dialect(), prefix);
            }
        }
    }

    @Test
    void anUpdateThatChangesNoColumnCountsNoRows() {
        UUID id = UUID.randomUUID();
        insert(id, "A", null, null, null, null);

        int unchanged = storage().async(tx -> tx.update("UPDATE " + probe() + " SET state = ? WHERE id = ?",
            params -> params.text("A").uuid(id))).join();
        int changed = storage().async(tx -> tx.update("UPDATE " + probe() + " SET state = ? WHERE id = ?",
            params -> params.text("B").uuid(id))).join();

        assertThat(unchanged).isZero();
        assertThat(changed).isEqualTo(1);
    }

    private Storage start(String prefix, List<Migration> migrations, Map<String, String> scripts) {
        return starting(prefix, migrations, scripts).join();
    }

    private CompletableFuture<Storage> starting(String prefix, List<Migration> migrations,
                                                Map<String, String> scripts) {
        StorageSettings settings = new StorageSettings(dialect(), "data.db", server(prefix),
            new StorageSettings.Pool(4, 1, Duration.ofSeconds(10)), 2, 5);
        return Storage.start(settings, Path.of("unused"), name(), "9.9.9", migrations, ProbeSchema.source(scripts),
            Logger.getAnonymousLogger(), new ErrorReports(), CLOCK);
    }

    private Migrator migrator(String prefix, List<Migration> migrations, Map<String, String> scripts) {
        return new Migrator(dialect(), prefix, CLOCK, Logger.getAnonymousLogger(), migrations,
            ProbeSchema.source(scripts));
    }

    private Connection connect() throws SQLException {
        StorageSettings.Server server = server("");
        return DriverManager.getConnection(Storage.serverUrl(dialect(), server), server.user(), server.password());
    }

    private static List<String> indexes(Connection connection, String table) throws SQLException {
        List<String> indexes = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT DISTINCT index_name FROM information_schema.statistics"
                 + " WHERE table_schema = DATABASE() AND table_name = '" + table + "'")) {
            while (rows.next()) {
                indexes.add(rows.getString(1));
            }
        }
        return indexes;
    }
}
