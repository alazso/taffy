package so.alaz.taffy.storage;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import so.alaz.taffy.test.FaultyDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private static final String OTHER_DATABASE = "taffy_other";

    private Storage storage;

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
            + " amount BIGINT, at BIGINT, doc JSON, data LONGBLOB, wide DECIMAL(65, 4)) ENGINE = InnoDB"
            + " DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin";
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
    void theMigrationLockIsKeyedByTheDatabase() throws Exception {
        String prefix = "keyed_";
        String versionTable = prefix + "schema_version";
        Migration first = new Migration(1, "alpha");
        Migration second = new Migration(2, "beta");
        Map<String, String> scripts = Map.of(
            first.resource(dialect()), "CREATE TABLE IF NOT EXISTS ${prefix}alpha (id INT);\n",
            second.resource(dialect()), "CREATE TABLE IF NOT EXISTS ${prefix}beta (id INT);\n");
        StorageSettings.Server server = server(prefix);
        try (Connection root = DriverManager.getConnection(Storage.serverUrl(dialect(), server), "root",
                 server.password());
             Statement admin = root.createStatement()) {
            admin.execute("CREATE DATABASE " + OTHER_DATABASE);
            try {
                admin.execute("GRANT ALL PRIVILEGES ON " + OTHER_DATABASE + ".* TO '" + server.user() + "'@'%'");
                try (Connection holder = connect()) {
                    assertThat(namedLock(holder, "GET_LOCK", Migrator.lockName(server.database(), versionTable)))
                        .isEqualTo(1);
                    startingOn(OTHER_DATABASE, prefix, List.of(first), scripts).orTimeout(30, TimeUnit.SECONDS)
                        .join().close(Duration.ofSeconds(5));

                    String other = Migrator.lockName(OTHER_DATABASE, versionTable);
                    assertThat(namedLock(holder, "GET_LOCK", other)).isEqualTo(1);
                    CompletableFuture<Storage> waiting = startingOn(OTHER_DATABASE, prefix, List.of(first, second),
                        scripts);
                    assertThatThrownBy(() -> waiting.get(2, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
                    assertThat(namedLock(holder, "RELEASE_LOCK", other)).isEqualTo(1);
                    waiting.orTimeout(30, TimeUnit.SECONDS).join().close(Duration.ofSeconds(5));
                }
                assertThat(ProbeSchema.versions(root, OTHER_DATABASE + "." + prefix)).containsExactly(1, 2);
            } finally {
                admin.execute("DROP DATABASE IF EXISTS " + OTHER_DATABASE);
            }
        }
    }

    @Test
    void aTakeoverInterruptedBeforeItsRenameIsCompletedByTheNextStart() throws Exception {
        String prefix = "halted_";
        String legacy = prefix + "legacy_version";
        String staging = prefix + "schema_version_takeover";
        Baseline baseline = Baseline.versionTable(legacy);
        Map<String, String> scripts = ProbeSchema.scripts(dialect());
        StorageSettings.Server server = server(prefix);
        Properties credentials = new Properties();
        credentials.setProperty("user", server.user());
        credentials.setProperty("password", server.password());
        FaultyDataSource faulty = new FaultyDataSource(Storage.serverUrl(dialect(), server), credentials);
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try {
                migrator(prefix, ProbeSchema.MIGRATIONS.subList(0, 2), scripts).migrate(connection, null);
                statement.execute("DROP TABLE " + prefix + "schema_version");
                statement.execute("CREATE TABLE " + legacy + " (version INT NOT NULL)");
                statement.execute("INSERT INTO " + legacy + " VALUES (2)");
                faulty.faults().failMatching("RENAME TABLE", 1);

                try (Connection failing = faulty.getConnection()) {
                    assertThatThrownBy(() -> new Migrator(dialect(), prefix, EARLIER_CLOCK,
                        Logger.getAnonymousLogger(), ProbeSchema.MIGRATIONS, ProbeSchema.source(scripts), baseline)
                        .migrate(failing, null))
                        .isInstanceOf(SQLException.class).hasMessage("injected failure of RENAME TABLE");
                }

                assertThat(ProbeSchema.tables(connection, dialect(), prefix))
                    .containsExactly(prefix + "alpha", prefix + "beta", legacy, staging);
                assertThat(ProbeSchema.ids(connection, staging, "version")).containsExactly(1, 2);
                statement.execute("INSERT INTO " + staging + " (version, name, checksum, statements_done, applied_at)"
                    + " VALUES (4, 'delta', '" + "0".repeat(64) + "', 1, 1)");

                starting(prefix, ProbeSchema.MIGRATIONS, scripts, baseline).join().close(Duration.ofSeconds(5));

                assertThat(ProbeSchema.tables(connection, dialect(), prefix)).containsExactly(prefix + "alpha",
                    prefix + "beta", prefix + "gamma", legacy + "_baseline", prefix + "schema_version");
                assertThat(ProbeSchema.versions(connection, prefix)).containsExactly(1, 2, 3);
                for (Migration migration : ProbeSchema.MIGRATIONS) {
                    assertThat(ProbeSchema.appliedAt(connection, prefix, migration.version()))
                        .isEqualTo(NOW.toEpochMilli());
                    assertThat(checksum(connection, prefix, migration.version()))
                        .isEqualTo(Migrator.checksum(scripts.get(migration.resource(dialect()))));
                }
                assertThat(ProbeSchema.ids(connection, prefix + "alpha", "id")).containsExactly(1);
            } finally {
                ProbeSchema.drop(connection, dialect(), prefix);
            }
        }
    }

    @Test
    void aDataStatementWhoseProgressRecordFailsIsRolledBackAndRunsOnceOnTheNextStart() throws Exception {
        String prefix = "atomic_";
        Migration scale = new Migration(4, "scale");
        Map<String, String> scripts = ProbeSchema.scripts(dialect());
        scripts.put(scale.resource(dialect()), "UPDATE ${prefix}alpha SET id = id * 10;\n");
        List<Migration> migrations = Stream.concat(ProbeSchema.MIGRATIONS.stream(), Stream.of(scale)).toList();
        StorageSettings.Server server = server(prefix);
        Properties credentials = new Properties();
        credentials.setProperty("user", server.user());
        credentials.setProperty("password", server.password());
        FaultyDataSource faulty = new FaultyDataSource(Storage.serverUrl(dialect(), server), credentials);
        String record = "INSERT INTO " + prefix + "schema_version";
        try (Connection connection = connect()) {
            try {
                migrator(prefix, ProbeSchema.MIGRATIONS, scripts).migrate(connection, null);
                assertThat(ProbeSchema.ids(connection, prefix + "alpha", "id")).containsExactly(1);
                faulty.faults().failMatching(record, 1);

                try (Connection failing = faulty.getConnection()) {
                    assertThatThrownBy(() -> new Migrator(dialect(), prefix, CLOCK, Logger.getAnonymousLogger(),
                        migrations, ProbeSchema.source(scripts)).migrate(failing, null))
                        .isInstanceOf(SQLException.class).hasMessage("injected failure of " + record);
                    assertThat(failing.getAutoCommit()).isTrue();
                }

                assertThat(ProbeSchema.ids(connection, prefix + "alpha", "id")).containsExactly(1);
                assertThat(ProbeSchema.versions(connection, prefix)).containsExactly(1, 2, 3);

                migrator(prefix, migrations, scripts).migrate(connection, null);
                migrator(prefix, migrations, scripts).migrate(connection, null);

                assertThat(ProbeSchema.ids(connection, prefix + "alpha", "id")).containsExactly(10);
                assertThat(ProbeSchema.statementsDone(connection, prefix, scale.version())).isEqualTo(1);
                assertThat(ProbeSchema.appliedAt(connection, prefix, scale.version())).isEqualTo(NOW.toEpochMilli());
            } finally {
                ProbeSchema.drop(connection, dialect(), prefix);
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
            shop.resource(dialect()), "CREATE TABLE IF NOT EXISTS ${prefix}shop (id INT);\n");
        Map<String, String> bankScripts = Map.of(
            bank.resource(dialect()), "CREATE TABLE IF NOT EXISTS ${prefix}bank (id INT);\n",
            more.resource(dialect()), "CREATE TABLE IF NOT EXISTS ${prefix}more (id INT);\n");
        String shopLock = Migrator.lockName(server(prefix).database(), shopTable);
        try (Connection connection = connect(); Connection holder = connect()) {
            try {
                startingAs("Shop-Plugin", prefix, List.of(shop), shopScripts).join().close(Duration.ofSeconds(5));
                startingAs("Bank-Plugin", prefix, List.of(bank), bankScripts).join().close(Duration.ofSeconds(5));

                assertThat(ProbeSchema.tables(connection, dialect(), prefix))
                    .containsExactly(prefix + "bank", bankTable, prefix + "shop", shopTable);
                assertThat(names(connection, shopTable)).containsExactly("shop");
                assertThat(names(connection, bankTable)).containsExactly("bank");

                assertThat(namedLock(holder, "GET_LOCK", shopLock)).isEqualTo(1);
                startingAs("Bank-Plugin", prefix, List.of(bank, more), bankScripts).orTimeout(30, TimeUnit.SECONDS)
                    .join().close(Duration.ofSeconds(5));
                assertThat(names(connection, bankTable)).containsExactly("bank", "more");
                CompletableFuture<Storage> waiting = startingAs("Shop-Plugin", prefix, List.of(shop), shopScripts);
                assertThatThrownBy(() -> waiting.get(2, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
                assertThat(namedLock(holder, "RELEASE_LOCK", shopLock)).isEqualTo(1);
                waiting.orTimeout(30, TimeUnit.SECONDS).join().close(Duration.ofSeconds(5));

                assertThat(names(connection, shopTable)).containsExactly("shop");
                assertThat(ProbeSchema.tables(connection, dialect(), prefix)).containsExactly(prefix + "bank",
                    bankTable, prefix + "more", prefix + "shop", shopTable);
            } finally {
                ProbeSchema.drop(connection, dialect(), prefix);
            }
        }
    }

    @Test
    void aVersionTableOfAnEarlierReleaseIsCopiedIntoThePluginsOwnVersionTable() throws Exception {
        String prefix = "earlier_";
        String earlier = prefix + "schema_version";
        String own = prefix + name().replace('-', '_') + "_schema_version";
        Map<String, String> scripts = ProbeSchema.scripts(dialect());
        try (Connection connection = connect()) {
            try {
                new Migrator(dialect(), prefix, EARLIER_CLOCK, Logger.getAnonymousLogger(),
                    ProbeSchema.MIGRATIONS.subList(0, 2), ProbeSchema.source(scripts)).migrate(connection, null);

                startingAs(name(), prefix, ProbeSchema.MIGRATIONS, scripts).join().close(Duration.ofSeconds(5));
                startingAs(name(), prefix, ProbeSchema.MIGRATIONS, scripts).join().close(Duration.ofSeconds(5));

                assertThat(ProbeSchema.tables(connection, dialect(), prefix)).containsExactlyInAnyOrder(
                    prefix + "alpha", prefix + "beta", prefix + "gamma", earlier, own);
                assertThat(ProbeSchema.ids(connection, own, "version")).containsExactly(1, 2, 3);
                assertThat(ProbeSchema.ids(connection, earlier, "version")).containsExactly(1, 2);
                assertThat(appliedAt(connection, own)).containsExactly(EARLIER.toEpochMilli(),
                    EARLIER.toEpochMilli(), NOW.toEpochMilli());
                assertThat(ProbeSchema.ids(connection, prefix + "alpha", "id")).containsExactly(1);
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

    @Override
    CompletableFuture<Storage> starting(String prefix, List<Migration> migrations, Map<String, String> scripts,
                                        @Nullable Baseline baseline) {
        return startingOn(server(prefix).database(), prefix, migrations, scripts, baseline);
    }

    private CompletableFuture<Storage> startingOn(String database, String prefix, List<Migration> migrations,
                                                  Map<String, String> scripts) {
        return startingOn(database, prefix, migrations, scripts, null);
    }

    private CompletableFuture<Storage> startingOn(String database, String prefix, List<Migration> migrations,
                                                  Map<String, String> scripts, @Nullable Baseline baseline) {
        StorageSettings.Server base = server(prefix);
        StorageSettings.Server server = StorageSettings.Server.of(base.host(), base.port(), database, base.user(),
            base.password(), base.sslMode()).tablePrefix(prefix);
        StorageSettings settings = StorageSettings.server(dialect(), server)
            .versionTable(prefix + "schema_version");
        return TestPlugins.start(TestPlugins.plugin(name(), Path.of("unused"), Logger.getAnonymousLogger(),
            ProbeSchema.source(scripts)), settings, migrations, baseline, CLOCK);
    }

    /** Starts storage for the plugin named {@code plugin}, which records its migrations in the default table. */
    private CompletableFuture<Storage> startingAs(String plugin, String prefix, List<Migration> migrations,
                                                  Map<String, String> scripts) {
        return TestPlugins.start(TestPlugins.plugin(plugin, Path.of("unused"), Logger.getAnonymousLogger(),
            ProbeSchema.source(scripts)), StorageSettings.server(dialect(), server(prefix)), migrations, null, CLOCK);
    }

    private static List<String> names(Connection connection, String versionTable) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT name FROM " + versionTable + " ORDER BY version")) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return names;
    }

    private static List<Long> appliedAt(Connection connection, String versionTable) throws SQLException {
        List<Long> times = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT applied_at FROM " + versionTable + " ORDER BY version")) {
            while (rows.next()) {
                times.add(rows.getLong(1));
            }
        }
        return times;
    }

    private Migrator migrator(String prefix, List<Migration> migrations, Map<String, String> scripts) {
        return new Migrator(dialect(), prefix, CLOCK, Logger.getAnonymousLogger(), migrations,
            ProbeSchema.source(scripts));
    }

    @Override
    Connection connect() throws SQLException {
        StorageSettings.Server server = server("");
        return DriverManager.getConnection(Storage.serverUrl(dialect(), server), server.user(), server.password());
    }

    private static int namedLock(Connection connection, String function, String name) throws SQLException {
        String sql = "SELECT " + function + (function.equals("GET_LOCK") ? "(?, 0)" : "(?)");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
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
