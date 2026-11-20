package so.alaz.taffy.storage;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import so.alaz.taffy.test.CapturedLog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class MigratorTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:15:30.123Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final Migration V1 = new Migration(1, "schema_version");
    private static final Migration V2 = new Migration(2, "probe");

    private static final String V1_SCRIPT = """
        CREATE TABLE IF NOT EXISTS ${prefix}schema_version (
            version INTEGER NOT NULL PRIMARY KEY,
            name VARCHAR(128) NOT NULL,
            checksum CHAR(64) NOT NULL,
            statements_done INTEGER NOT NULL,
            applied_at BIGINT
        );
        """;

    private static final String SQLITE_VERSION_TABLE = "CREATE TABLE IF NOT EXISTS p_schema_version"
        + " (version INTEGER NOT NULL PRIMARY KEY, name VARCHAR(128) NOT NULL, checksum CHAR(64) NOT NULL,"
        + " statements_done INTEGER NOT NULL, applied_at BIGINT)";

    private static final String LAYOUT_PROBE =
        "SELECT version, checksum, statements_done, applied_at FROM p_schema_version WHERE 1 = 0";

    private static final String FOREIGN_TABLE = "the database holds a table named p_schema_version that storage did"
        + " not create, so it cannot record migrations there; rename or remove that table, or on MySQL, MariaDB and"
        + " PostgreSQL set another table prefix";

    private static final String MARIADB_VERSION_TABLE = "CREATE TABLE IF NOT EXISTS p_schema_version"
        + " (version INT NOT NULL PRIMARY KEY, name VARCHAR(128) NOT NULL, checksum CHAR(64) NOT NULL,"
        + " statements_done INT NOT NULL, applied_at BIGINT NULL)"
        + " ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin";

    @TempDir
    private Path folder;

    private final CapturedLog log = new CapturedLog();
    private final Map<String, String> scripts = new HashMap<>();
    private final AtomicInteger backups = new AtomicInteger();
    private Connection connection;

    @BeforeEach
    void open() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("data.db"));
        scripts.put(V1.resource(Dialect.SQLITE), V1_SCRIPT);
    }

    @AfterEach
    void closeConnection() throws Exception {
        connection.close();
    }

    @Test
    void aFirstMigrationThatCreatesTheVersionTableIsRecordedAndCreatesNothingElse() throws Exception {
        migrator("", List.of(V1)).migrate(connection, this::backup);

        assertThat(tables()).containsExactly("schema_version");
        assertThat(row(1)).containsExactly("schema_version", Migrator.checksum(V1_SCRIPT), 1L, NOW.toEpochMilli());
        assertThat(backups).hasValue(0);
    }

    @Test
    void theVersionTableExistsAfterARunWhoseScriptsNeverCreateIt() throws Exception {
        Migration probe = new Migration(1, "probe");
        String script = "CREATE TABLE ${prefix}probe (id INTEGER);\n";
        scripts.put(probe.resource(Dialect.SQLITE), script);

        migrator("p_", List.of(probe)).migrate(connection, this::backup);

        assertThat(tables()).containsExactly("p_probe", "p_schema_version");
        assertThat(row("p_schema_version", 1)).containsExactly("probe", Migrator.checksum(script), 1L,
            NOW.toEpochMilli());
        assertThat(log.lines()).containsExactly("INFO Applied database migration V1__probe.");
    }

    @Test
    void aSecondRunChangesNothingAndTakesNoBackup() throws Exception {
        migrator("", List.of(V1)).migrate(connection, this::backup);
        log.records.clear();

        migrator("", List.of(V1)).migrate(connection, this::backup);

        assertThat(log.lines()).isEmpty();
        assertThat(backups).hasValue(0);
    }

    @Test
    void aNewMigrationOnAnExistingDatabaseIsBackedUpFirst() throws Exception {
        migrator("", List.of(V1)).migrate(connection, this::backup);
        log.records.clear();
        scripts.put(V2.resource(Dialect.SQLITE), "CREATE TABLE ${prefix}probe (id INTEGER);\n");

        migrator("", List.of(V1, V2)).migrate(connection, this::backup);

        assertThat(backups).hasValue(1);
        assertThat(tables()).contains("probe");
        assertThat(log.lines()).containsExactly("INFO Applied database migration V2__probe.");
    }

    @Test
    void aChangedMigrationStopsTheStartAndNamesTheFix() throws Exception {
        migrator("", List.of(V1)).migrate(connection, this::backup);
        scripts.put(V1.resource(Dialect.SQLITE), V1_SCRIPT + "\n-- edited\n");

        assertThatThrownBy(() -> migrator("", List.of(V1)).migrate(connection, this::backup))
            .isInstanceOf(StorageException.class)
            .hasMessage("migration V1__schema_version changed after it ran on this database (stored checksum "
                + Migrator.checksum(V1_SCRIPT) + ", now " + Migrator.checksum(V1_SCRIPT + "\n-- edited\n")
                + "); install the plugin version that applied it again, or restore a backup of the database");
    }

    @Test
    void aDatabaseAheadOfThisVersionStopsTheStart() throws Exception {
        migrator("", List.of(V1)).migrate(connection, this::backup);
        execute("INSERT INTO schema_version VALUES (7, 'future', 'x', 1, 1)");

        assertThatThrownBy(() -> migrator("", List.of(V1)).migrate(connection, this::backup))
            .isInstanceOf(StorageException.class)
            .hasMessage("the database is at schema version 7, set up by a newer version of the plugin; this version"
                + " knows schema versions up to 1; install the newer version again, or restore a backup of the"
                + " database");
    }

    @Test
    void aFailedMigrationLeavesNoTraceOnATransactionalDatabase() throws Exception {
        migrator("", List.of(V1)).migrate(connection, this::backup);
        scripts.put(V2.resource(Dialect.SQLITE), "CREATE TABLE ${prefix}probe (id INTEGER);\nNOT VALID SQL;\n");

        assertThatThrownBy(() -> migrator("", List.of(V1, V2)).migrate(connection, this::backup))
            .isInstanceOf(SQLException.class)
            .hasMessageStartingWith("migration V2__probe failed at statement 2 of 2; it was rolled back: ")
            .hasMessageContaining("NOT")
            .cause().isInstanceOf(SQLException.class);

        assertThat(tables()).doesNotContain("probe");
        assertThat(row(2)).isEmpty();
        assertThat(connection.getAutoCommit()).isTrue();
    }

    @Test
    void thePrefixIsSubstitutedAfterTheChecksumAndAnotherPrefixIsReported() throws Exception {
        migrator("a_", List.of(V1)).migrate(connection, this::backup);
        log.records.clear();

        migrator("b_", List.of(V1)).migrate(connection, this::backup);

        assertThat(tables()).contains("a_schema_version", "b_schema_version");
        assertThat(checksum("a_schema_version")).isEqualTo(checksum("b_schema_version"));
        assertThat(log.lines()).first().asString().isEqualTo("WARNING The database holds tables under the"
            + " prefix \"a_\", but storage uses \"b_\", so the plugin starts with empty tables. Set the table"
            + " prefix to \"a_\" and restart to use that data.");
    }

    @Test
    void anotherPluginsVersionTableRaisesNoWarning() throws Exception {
        execute("CREATE TABLE schema_version (installed_rank INT, version VARCHAR(50), description VARCHAR(200),"
            + " checksum INT, success BOOLEAN)");
        execute("CREATE TABLE plan_schema_version (id INT)");

        migrator("p_", List.of(V1)).migrate(connection, this::backup);

        assertThat(log.lines()).containsExactly("INFO Applied database migration V1__schema_version.");
    }

    @Test
    void aScriptWithTwoStatementsOnALineStopsTheStartBeforeTheBackupAndAnyMigration() throws Exception {
        Migration v3 = new Migration(3, "third");
        migrator("", List.of(V1)).migrate(connection, this::backup);
        scripts.put(V2.resource(Dialect.SQLITE), "CREATE TABLE ${prefix}fine (id INTEGER);\n");
        scripts.put(v3.resource(Dialect.SQLITE), "CREATE TABLE a (id INTEGER); CREATE TABLE b (id INTEGER);\n");

        assertThatThrownBy(() -> migrator("", List.of(V1, V2, v3)).migrate(connection, this::backup))
            .isInstanceOf(StorageException.class)
            .hasMessage("db/migration/sqlite/V3__third.sql cannot be split into statements: a statement must end at"
                + " the end of its line: CREATE TABLE a (id INTEGER); CREATE TABLE b (id INTEGER);")
            .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThat(row(2)).isEmpty();
        assertThat(tables()).doesNotContain("fine");
        assertThat(backups).hasValue(0);
    }

    @Test
    void aMissingResourceStopsTheStartBeforeAnyTableIsCreated() throws Exception {
        scripts.clear();

        assertThatThrownBy(() -> migrator("", List.of(V1)).migrate(connection, this::backup))
            .isInstanceOf(StorageException.class)
            .hasMessage("the plugin has no db/migration/sqlite/V1__schema_version.sql");
        assertThat(tables()).isEmpty();
    }

    @Test
    void aScriptThatIsNotUtf8StopsTheStart() throws Exception {
        byte[] latin1 = "CREATE TABLE t (label TEXT DEFAULT 'café');\n".getBytes(StandardCharsets.ISO_8859_1);
        Migrator migrator = new Migrator(Dialect.SQLITE, "", CLOCK, log.logger, List.of(V1),
            path -> new ByteArrayInputStream(latin1));

        assertThatThrownBy(() -> migrator.migrate(connection, this::backup))
            .isInstanceOf(StorageException.class)
            .hasMessage("db/migration/sqlite/V1__schema_version.sql is not valid UTF-8")
            .hasCauseInstanceOf(CharacterCodingException.class);
        assertThat(tables()).isEmpty();
    }

    @Test
    void anUncheckedReadFailureOfTheScriptsFunctionStopsTheStart() {
        UncheckedIOException broken = new UncheckedIOException(new IOException("disk gone"));
        Migrator migrator = new Migrator(Dialect.SQLITE, "", CLOCK, log.logger, List.of(V1), path -> {
            throw broken;
        });

        assertThatThrownBy(() -> migrator.migrate(connection, this::backup))
            .isInstanceOf(StorageException.class)
            .hasMessage("cannot read db/migration/sqlite/V1__schema_version.sql")
            .hasCause(broken);
    }

    @Test
    void aDatabaseWithTablesButNoVersionTableIsBackedUpFirst() throws Exception {
        execute("CREATE TABLE owner_data (id INTEGER)");

        migrator("", List.of(V1)).migrate(connection, this::backup);

        assertThat(backups).hasValue(1);
    }

    @Test
    void anExistingDatabaseMigratesWithoutABackupWhenThereIsNone() throws Exception {
        migrator("", List.of(V1)).migrate(connection, null);
        scripts.put(V2.resource(Dialect.SQLITE), "CREATE TABLE ${prefix}probe (id INTEGER);\n");

        migrator("", List.of(V1, V2)).migrate(connection, null);

        assertThat(tables()).contains("probe");
        assertThat(row(2)).hasSize(4);
    }

    @Test
    void aSqliteTableRebuildKeepsTheChildRowsOfARebuiltParent() throws Exception {
        try (Connection enforcing = foreignKeyConnection()) {
            Migration rebuild = new Migration(2, "rebuild");
            scripts.put(V1.resource(Dialect.SQLITE), """
                CREATE TABLE parent (id INTEGER PRIMARY KEY);
                CREATE TABLE child (id INTEGER PRIMARY KEY, parent INTEGER REFERENCES parent (id) ON DELETE CASCADE);
                INSERT INTO parent VALUES (1);
                INSERT INTO child VALUES (10, 1);
                """);
            scripts.put(rebuild.resource(Dialect.SQLITE), """
                PRAGMA foreign_keys = OFF;
                CREATE TABLE parent_new (id INTEGER PRIMARY KEY, note TEXT NOT NULL DEFAULT '');
                INSERT INTO parent_new (id) SELECT id FROM parent;
                DROP TABLE parent;
                ALTER TABLE parent_new RENAME TO parent;
                """);

            migrator("", List.of(V1, rebuild)).migrate(enforcing, this::backup);

            assertThat(count(enforcing, "child")).isEqualTo(1);
            assertThat(foreignKeys(enforcing)).isEqualTo(1);
        }
    }

    @Test
    void aSqliteMigrationThatBreaksAForeignKeyIsRolledBack() throws Exception {
        try (Connection enforcing = foreignKeyConnection()) {
            Migration orphan = new Migration(2, "orphan");
            scripts.put(V1.resource(Dialect.SQLITE), """
                CREATE TABLE parent (id INTEGER PRIMARY KEY);
                CREATE TABLE child (id INTEGER PRIMARY KEY, parent INTEGER REFERENCES parent (id));
                INSERT INTO parent VALUES (1);
                INSERT INTO child VALUES (10, 1);
                """);
            scripts.put(orphan.resource(Dialect.SQLITE), "DELETE FROM parent;\n");
            migrator("", List.of(V1)).migrate(enforcing, this::backup);

            assertThatThrownBy(() -> migrator("", List.of(V1, orphan)).migrate(enforcing, this::backup))
                .isInstanceOf(StorageException.class)
                .hasMessage("migration V2__orphan leaves a row of child whose foreign key refers to a missing row of"
                    + " parent; it was rolled back");

            assertThat(count(enforcing, "parent")).isEqualTo(1);
            assertThat(foreignKeys(enforcing)).isEqualTo(1);
            assertThat(enforcing.getAutoCommit()).isTrue();
        }
    }

    @Test
    void aBrokenConnectionKeepsTheStatementFailureAndAddsTheCleanUpFailures() throws Exception {
        Migration probe = new Migration(1, "probe");
        scripts.put(probe.resource(Dialect.POSTGRES), "CREATE TABLE ${prefix}a (id INT);\n");
        Connection server = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(server.createStatement()).thenReturn(statement);
        when(server.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        when(statement.executeQuery(Dialect.POSTGRES.listTables())).thenReturn(mock(ResultSet.class));
        grantLock(server);
        nameDatabase(server, "public");
        SQLException timedOut = new SQLException("Read timed out", "08006");
        SQLException closed = new SQLException("This connection has been closed.", "08003");
        when(statement.execute("CREATE TABLE p_a (id INT)")).thenThrow(timedOut);
        doThrow(closed).when(server).rollback();
        doThrow(closed).when(server).setAutoCommit(true);

        assertThatThrownBy(() -> migrator(Dialect.POSTGRES, "p_", List.of(probe)).migrate(server, null))
            .isInstanceOf(SQLException.class)
            .hasMessage("migration V1__probe failed at statement 1 of 1; it was rolled back: Read timed out")
            .hasCause(timedOut)
            .satisfies(failure -> {
                assertThat(((SQLException) failure).getSQLState()).isEqualTo("08006");
                assertThat(failure.getSuppressed()).containsExactly(closed, closed);
            });
    }

    @Test
    void aMariaDbStatementFailureNamesWhatIsAlreadyApplied() throws Exception {
        Migration probe = new Migration(1, "probe");
        scripts.put(probe.resource(Dialect.MARIADB), "CREATE TABLE IF NOT EXISTS ${prefix}a (id INT);\n"
            + "ALTER TABLE ${prefix}a ADD COLUMN note INT;\n");
        Connection server = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(server.createStatement()).thenReturn(statement);
        when(server.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        grantLock(server);
        when(statement.executeQuery(Dialect.MARIADB.listTables())).thenReturn(mock(ResultSet.class));
        when(statement.execute("ALTER TABLE p_a ADD COLUMN note INT"))
            .thenThrow(new SQLException("Duplicate column name 'note'", "42S21", 1060));

        assertThatThrownBy(() -> migrator(Dialect.MARIADB, "p_", List.of(probe)).migrate(server, null))
            .isInstanceOf(SQLException.class)
            .hasMessage("migration V1__probe failed at statement 2 of 2; 1 statement of it applied, and the next start"
                + " resumes at statement 2: Duplicate column name 'note'")
            .satisfies(failure -> assertThat(((SQLException) failure).getErrorCode()).isEqualTo(1060));
    }

    @Test
    void aChangedScriptOfAnIncompleteMariaDbMigrationSaysHowToRecover() throws Exception {
        Migration probe = new Migration(3, "probe");
        scripts.put(probe.resource(Dialect.MARIADB), "CREATE TABLE IF NOT EXISTS ${prefix}a (id INT);\n");
        Connection server = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet tables = mock(ResultSet.class);
        ResultSet applied = mock(ResultSet.class);
        when(server.createStatement()).thenReturn(statement);
        when(server.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        grantLock(server);
        when(statement.executeQuery(Dialect.MARIADB.listTables())).thenReturn(tables);
        when(tables.next()).thenReturn(true, false);
        when(tables.getString(1)).thenReturn("p_schema_version");
        when(statement.executeQuery(LAYOUT_PROBE)).thenReturn(mock(ResultSet.class));
        when(statement.executeQuery("SELECT version, checksum, statements_done, applied_at FROM p_schema_version"))
            .thenReturn(applied);
        when(applied.next()).thenReturn(true, false);
        when(applied.getInt("version")).thenReturn(3);
        when(applied.getString("checksum")).thenReturn("earlier");
        when(applied.getInt("statements_done")).thenReturn(1);
        when(applied.wasNull()).thenReturn(true);

        assertThatThrownBy(() -> migrator(Dialect.MARIADB, "p_", List.of(probe)).migrate(server, null))
            .isInstanceOf(StorageException.class)
            .hasMessage("migration V3__probe stopped after 1 statement on an earlier start and has changed since"
                + " (stored checksum earlier, now " + Migrator.checksum(scripts.get(probe.resource(Dialect.MARIADB)))
                + "); undo what it applied and delete version 3 from p_schema_version, or install the plugin"
                + " version that started it again and fix what made it fail");
    }

    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = {"MYSQL", "MARIADB", "POSTGRES"})
    void aServerMigratesUnderALockAndWithoutASocketTimeout(Dialect dialect) throws Exception {
        Migration probe = new Migration(1, "probe");
        scripts.put(probe.resource(dialect), "CREATE TABLE ${prefix}a (id INT);\n");
        Connection server = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement lock = mock(PreparedStatement.class);
        PreparedStatement unlock = mock(PreparedStatement.class);
        when(server.getNetworkTimeout()).thenReturn(30_000);
        when(server.createStatement()).thenReturn(statement);
        when(server.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        when(statement.executeQuery(dialect.listTables())).thenReturn(mock(ResultSet.class));
        boolean postgres = dialect == Dialect.POSTGRES;
        grantLock(server, lock);
        if (postgres) {
            nameDatabase(server, "public");
            when(server.prepareStatement("SELECT pg_advisory_unlock(?)")).thenReturn(unlock);
        } else {
            when(server.prepareStatement("SELECT RELEASE_LOCK(?)")).thenReturn(unlock);
        }

        migrator(dialect, "p_", List.of(probe)).migrate(server, null);

        InOrder order = inOrder(server, statement, lock, unlock);
        order.verify(server).setNetworkTimeout(any(), eq(0));
        if (postgres) {
            order.verify(lock).setLong(1, Migrator.lockKey("public", "p_schema_version"));
        } else {
            order.verify(lock).setString(1, Migrator.lockName("shop", "p_schema_version"));
            order.verify(lock).setLong(2, 86_400L);
        }
        order.verify(lock).executeQuery();
        order.verify(statement).executeQuery(dialect.listTables());
        order.verify(statement).execute("CREATE TABLE p_a (id INT)");
        if (postgres) {
            order.verify(unlock).setLong(1, Migrator.lockKey("public", "p_schema_version"));
        } else {
            order.verify(unlock).setString(1, Migrator.lockName("shop", "p_schema_version"));
        }
        order.verify(unlock).execute();
        order.verify(server).setNetworkTimeout(any(), eq(30_000));
    }

    @Test
    void aLockHeldElsewhereStopsTheStartWithoutReleasingIt() throws Exception {
        Connection server = mock(Connection.class);
        PreparedStatement lock = mock(PreparedStatement.class);
        ResultSet refused = mock(ResultSet.class);
        when(server.getNetworkTimeout()).thenReturn(30_000);
        when(server.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        nameDatabase(server, "Shop");
        when(server.prepareStatement("SELECT GET_LOCK(?, ?)")).thenReturn(lock);
        when(lock.executeQuery()).thenReturn(refused);
        when(refused.next()).thenReturn(true);
        when(refused.getInt(1)).thenReturn(0);

        assertThatThrownBy(() -> migrator(Dialect.MYSQL, "p_", List.of(V1)).migrate(server, null))
            .isInstanceOf(StorageException.class)
            .hasMessage("another start has held the migration lock of p_schema_version in database Shop for a day;"
                + " restart once the other server has started");
        verify(lock).setString(1, Migrator.lockName("shop", "p_schema_version"));
        verify(server, never()).prepareStatement("SELECT RELEASE_LOCK(?)");
        verify(server, never()).createStatement();
        verify(server).setNetworkTimeout(any(), eq(30_000));
    }

    @Test
    void theMySqlLockNameIsKeyedByTheDatabaseAndTheVersionTable() {
        String longest = Migrator.lockName("d".repeat(64), "p".repeat(32) + "schema_version");

        assertThat(longest).hasSize(46).matches("taffy_[0-9a-f]{40}");
        assertThat(Migrator.lockName("shop", "p_schema_version"))
            .isEqualTo("taffy_9c93f249db2fafee53a9887960daa1aab62294ce")
            .isEqualTo(Migrator.lockName("shop", "p_schema_version"))
            .isEqualTo(Migrator.lockName("Shop", "p_schema_version"))
            .isNotEqualTo(Migrator.lockName("other", "p_schema_version"))
            .isNotEqualTo(Migrator.lockName("shop", "q_schema_version"));
        assertThat(Migrator.lockName("SHOPI", "p_schema_version"))
            .isEqualTo(Migrator.lockName("shopi", "p_schema_version"));
    }

    @Test
    void thePostgresLockKeyIsKeyedByTheSchemaAndTheVersionTable() throws Exception {
        assertThat(Migrator.lockKey("public", "p_schema_version")).isEqualTo(3636661416060457810L);
        assertThat(Migrator.lockKey("tenant_a", "p_schema_version"))
            .isNotEqualTo(Migrator.lockKey("tenant_b", "p_schema_version"))
            .isNotEqualTo(Migrator.lockKey("tenant_a", "q_schema_version"));

        assertThat(postgresLockKey("tenant_a")).isEqualTo(Migrator.lockKey("tenant_a", "p_schema_version"));
        assertThat(postgresLockKey("tenant_b")).isEqualTo(Migrator.lockKey("tenant_b", "p_schema_version"));
    }

    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = {"MYSQL", "MARIADB"})
    void aConnectionWithoutADatabaseStopsTheStartBeforeTheLock(Dialect dialect) throws Exception {
        Connection server = mock(Connection.class);
        when(server.getNetworkTimeout()).thenReturn(30_000);
        when(server.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        nameDatabase(server, null);

        assertThatThrownBy(() -> migrator(dialect, "p_", List.of(V1)).migrate(server, null))
            .isInstanceOf(StorageException.class)
            .hasMessage("the connection has no current database, so the migration lock for p_schema_version cannot"
                + " be named; name the database in the connection settings");
        verify(server, never()).prepareStatement("SELECT GET_LOCK(?, ?)");
        verify(server, never()).prepareStatement("SELECT RELEASE_LOCK(?)");
        verify(server, never()).createStatement();
        verify(server).setNetworkTimeout(any(), eq(30_000));
    }

    @Test
    void aConnectionWithoutASchemaStopsTheStartBeforeTheLock() throws Exception {
        Connection server = mock(Connection.class);
        PreparedStatement current = mock(PreparedStatement.class);
        when(server.getNetworkTimeout()).thenReturn(30_000);
        when(server.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        when(server.prepareStatement("SELECT current_schema()")).thenReturn(current);
        when(current.executeQuery()).thenReturn(mock(ResultSet.class));

        assertThatThrownBy(() -> migrator(Dialect.POSTGRES, "p_", List.of(V1)).migrate(server, null))
            .isInstanceOf(StorageException.class)
            .hasMessage("the connection has no current schema, so the migration lock for p_schema_version cannot be"
                + " named; set a search_path");
        verify(server, never()).prepareStatement("SELECT pg_try_advisory_lock(?)");
        verify(server, never()).prepareStatement("SELECT pg_advisory_unlock(?)");
        verify(server, never()).createStatement();
        verify(server).setNetworkTimeout(any(), eq(30_000));
    }

    @Test
    void aVersionTableInAnotherLayoutStopsTheStartBeforeTheBackupAndChangesNothing() throws Exception {
        execute("CREATE TABLE p_schema_version (version INTEGER NOT NULL)");
        execute("INSERT INTO p_schema_version VALUES (1)");
        scripts.put(V2.resource(Dialect.SQLITE), "CREATE TABLE ${prefix}probe (id INTEGER);\n");

        assertThatThrownBy(() -> migrator("p_", List.of(V1, V2)).migrate(connection, this::backup))
            .isInstanceOf(StorageException.class)
            .hasMessage(FOREIGN_TABLE);

        assertThat(backups).hasValue(0);
        assertThat(tables()).containsExactly("p_schema_version");
        assertThat(columns("p_schema_version")).containsExactly("version");
        assertThat(count(connection, "p_schema_version")).isEqualTo(1);
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aServerVersionTableInAnotherLayoutStopsTheStartUnderTheLockAndReleasesIt() throws Exception {
        Connection server = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet tables = mock(ResultSet.class);
        PreparedStatement unlock = mock(PreparedStatement.class);
        when(server.createStatement()).thenReturn(statement);
        when(server.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        grantLock(server);
        when(server.prepareStatement("SELECT RELEASE_LOCK(?)")).thenReturn(unlock);
        when(statement.executeQuery(Dialect.MYSQL.listTables())).thenReturn(tables);
        when(tables.next()).thenReturn(true, false);
        when(tables.getString(1)).thenReturn("p_schema_version");
        when(statement.executeQuery(LAYOUT_PROBE))
            .thenThrow(new SQLException("Unknown column 'checksum' in 'field list'", "42S22", 1054));

        assertThatThrownBy(() -> migrator(Dialect.MYSQL, "p_", List.of(V1)).migrate(server, this::backup))
            .isInstanceOf(StorageException.class)
            .hasMessage(FOREIGN_TABLE);

        verify(statement, never()).executeQuery(
            "SELECT version, checksum, statements_done, applied_at FROM p_schema_version");
        verify(statement, never()).execute(anyString());
        verify(unlock).setString(1, Migrator.lockName("shop", "p_schema_version"));
        verify(unlock).execute();
        assertThat(backups).hasValue(0);
    }

    @Test
    void aVersionTableThatCannotBeProbedFailsTheStartWithTheDatabaseErrorAndReleasesTheLock() throws Exception {
        Connection server = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet tables = mock(ResultSet.class);
        PreparedStatement unlock = mock(PreparedStatement.class);
        when(server.createStatement()).thenReturn(statement);
        when(server.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        grantLock(server);
        when(server.prepareStatement("SELECT RELEASE_LOCK(?)")).thenReturn(unlock);
        when(statement.executeQuery(Dialect.MYSQL.listTables())).thenReturn(tables);
        when(tables.next()).thenReturn(true, false);
        when(tables.getString(1)).thenReturn("p_schema_version");
        SQLException lost = new SQLException("Communications link failure", "08S01");
        when(statement.executeQuery(LAYOUT_PROBE)).thenThrow(lost);

        assertThatThrownBy(() -> migrator(Dialect.MYSQL, "p_", List.of(V1)).migrate(server, this::backup))
            .isSameAs(lost);

        verify(statement, never()).execute(anyString());
        verify(unlock).execute();
        assertThat(backups).hasValue(0);
    }

    @Test
    void aSqliteVersionTableThatCannotBeProbedFailsTheStartWithTheDatabaseError() throws Exception {
        Connection sqlite = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet tables = mock(ResultSet.class);
        when(sqlite.createStatement()).thenReturn(statement);
        when(statement.executeQuery(Dialect.SQLITE.listTables())).thenReturn(tables);
        when(tables.next()).thenReturn(true, false);
        when(tables.getString(1)).thenReturn("p_schema_version");
        SQLException corrupt = new SQLException("[SQLITE_CORRUPT] The database disk image is malformed", null, 11);
        when(statement.executeQuery(LAYOUT_PROBE)).thenThrow(corrupt);

        assertThatThrownBy(() -> migrator("p_", List.of(V1)).migrate(sqlite, this::backup)).isSameAs(corrupt);

        verify(statement, never()).execute(anyString());
        assertThat(backups).hasValue(0);
    }

    @Test
    void anEmptyMigrationListCreatesNoVersionTable() throws Exception {
        migrator("p_", List.of()).migrate(connection, this::backup);

        assertThat(tables()).isEmpty();
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aFailedFirstMigrationLeavesNoVersionTableSoTheNextStartTakesNoBackup() throws Exception {
        Migration probe = new Migration(1, "probe");
        scripts.put(probe.resource(Dialect.SQLITE), "CREATE TABLE ${prefix}probe (id INTEGER);\nNOT VALID SQL;\n");

        assertThatThrownBy(() -> migrator("p_", List.of(probe)).migrate(connection, this::backup))
            .isInstanceOf(SQLException.class);

        assertThat(tables()).isEmpty();
        assertThat(connection.getAutoCommit()).isTrue();
        scripts.put(probe.resource(Dialect.SQLITE), "CREATE TABLE ${prefix}probe (id INTEGER);\n");

        migrator("p_", List.of(probe)).migrate(connection, this::backup);

        assertThat(tables()).containsExactly("p_probe", "p_schema_version");
        assertThat(backups).hasValue(0);
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void theVersionTableIsCreatedOnceInsideTheFirstPendingMigration(Dialect dialect) throws Exception {
        Migration first = new Migration(1, "probe_a");
        Migration second = new Migration(2, "probe_b");
        scripts.put(first.resource(dialect), "CREATE TABLE ${prefix}a (id INT);\n");
        scripts.put(second.resource(dialect), "CREATE TABLE ${prefix}b (id INT);\n");
        Connection server = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(server.createStatement()).thenReturn(statement);
        when(server.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        grantLock(server);
        when(statement.executeQuery(dialect.listTables())).thenReturn(mock(ResultSet.class));
        when(statement.executeQuery("PRAGMA foreign_keys")).thenReturn(mock(ResultSet.class));

        migrator(dialect, "p_", List.of(first, second)).migrate(server, null);

        String ddl = dialect.transactionalDdl() ? SQLITE_VERSION_TABLE : MARIADB_VERSION_TABLE;
        InOrder order = inOrder(server, statement);
        if (dialect.transactionalDdl()) {
            order.verify(server).setAutoCommit(false);
        }
        order.verify(statement).execute(ddl);
        order.verify(statement).execute("CREATE TABLE p_a (id INT)");
        order.verify(statement).execute("CREATE TABLE p_b (id INT)");
        verify(statement, times(1)).execute(ddl);
        verify(statement, times(3)).execute(anyString());
        verify(server, dialect == Dialect.SQLITE ? never() : atLeastOnce()).setNetworkTimeout(any(), eq(0));
    }

    @Test
    void anUnreadableResourceStopsTheStart() {
        IOException broken = new IOException("broken jar entry");
        Function<String, InputStream> resources = path -> new InputStream() {
            @Override
            public int read() throws IOException {
                throw broken;
            }
        };
        Migrator migrator = new Migrator(Dialect.SQLITE, "", CLOCK, log.logger, List.of(V1), resources);

        assertThatThrownBy(() -> migrator.migrate(connection, this::backup))
            .isInstanceOf(StorageException.class)
            .hasMessage("cannot read db/migration/sqlite/V1__schema_version.sql")
            .hasCause(broken);
    }

    @Test
    void checksumsAreLowercaseSha256() {
        assertThat(Migrator.checksum("abc"))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void aMigrationIsNamedAndLocatedByItsVersionAndName() {
        Migration migration = new Migration(12, "add_owner");

        assertThat(migration.id()).isEqualTo("V12__add_owner");
        assertThat(migration.resource(Dialect.SQLITE)).isEqualTo("db/migration/sqlite/V12__add_owner.sql");
        assertThat(migration.resource(Dialect.MYSQL)).isEqualTo("db/migration/mysql/V12__add_owner.sql");
        assertThat(migration.resource(Dialect.MARIADB)).isEqualTo("db/migration/mariadb/V12__add_owner.sql");
        assertThat(migration.resource(Dialect.POSTGRES)).isEqualTo("db/migration/postgres/V12__add_owner.sql");
    }

    @Test
    void aMigrationNeedsAPositiveVersionAndAName() {
        assertThatThrownBy(() -> new Migration(0, "probe"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a migration version must be 1 or more, not 0");
        assertThatThrownBy(() -> new Migration(-3, "probe"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a migration version must be 1 or more, not -3");
        assertThatThrownBy(() -> new Migration(1, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("name");
        assertThatThrownBy(() -> new Migration(1, " \t"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("migration 1 needs a name");
    }

    @Test
    void aMigrationNameFitsTheNameColumn() {
        String longest = "n".repeat(128);

        assertThat(new Migration(3, longest).name()).isEqualTo(longest);
        assertThatThrownBy(() -> new Migration(3, longest + "n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("migration 3 has a name of 129 characters; expected at most 128");
    }

    @Test
    void versionsOutOfOrderAreRefused() {
        assertThatThrownBy(() -> migrator("", List.of(V2, V1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("migration versions must be strictly increasing, but V1__schema_version follows V2__probe");
        assertThatThrownBy(() -> migrator("", List.of(V1, new Migration(1, "again"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("migration versions must be strictly increasing, but V1__again follows V1__schema_version");
    }

    @Test
    void theMigrationListIsCopiedWhenTheMigratorIsBuilt() throws Exception {
        List<Migration> migrations = new ArrayList<>(List.of(V1));
        Migrator migrator = migrator("", migrations);
        migrations.add(V2);

        migrator.migrate(connection, this::backup);

        assertThat(tables()).containsExactly("schema_version");
        assertThat(log.lines()).containsExactly("INFO Applied database migration V1__schema_version.");
    }

    @Test
    void mariaDbCheckpointsEachStatementAndResumesAfterTheLastRecordedOne() throws Exception {
        Migration probe = new Migration(1, "probe");
        String script = "CREATE TABLE IF NOT EXISTS ${prefix}a (id INT);\n"
            + "CREATE TABLE IF NOT EXISTS ${prefix}b (id INT);\n"
            + "CREATE TABLE IF NOT EXISTS ${prefix}c (id INT);\n";
        scripts.put(probe.resource(Dialect.MARIADB), script);
        Connection server = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement record = mock(PreparedStatement.class);
        ResultSet tables = mock(ResultSet.class);
        ResultSet applied = mock(ResultSet.class);
        when(server.createStatement()).thenReturn(statement);
        when(server.prepareStatement(anyString())).thenReturn(record);
        grantLock(server);
        when(statement.executeQuery(Dialect.MARIADB.listTables())).thenReturn(tables);
        when(tables.next()).thenReturn(true, false);
        when(tables.getString(1)).thenReturn("p_schema_version");
        when(statement.executeQuery(LAYOUT_PROBE)).thenReturn(mock(ResultSet.class));
        when(statement.executeQuery("SELECT version, checksum, statements_done, applied_at FROM p_schema_version"))
            .thenReturn(applied);
        when(applied.next()).thenReturn(true, false);
        when(applied.getInt("version")).thenReturn(1);
        when(applied.getString("checksum")).thenReturn(Migrator.checksum(script));
        when(applied.getInt("statements_done")).thenReturn(1);
        when(applied.wasNull()).thenReturn(true);

        migrator(Dialect.MARIADB, "p_", List.of(probe)).migrate(server, this::backup);

        InOrder order = inOrder(server, statement, record);
        order.verify(statement).execute(MARIADB_VERSION_TABLE);
        order.verify(server).setAutoCommit(false);
        order.verify(statement).execute("CREATE TABLE IF NOT EXISTS p_b (id INT)");
        order.verify(record).setLong(4, 2L);
        order.verify(record).setNull(5, Types.BIGINT);
        order.verify(record).executeUpdate();
        order.verify(server).commit();
        order.verify(server).setAutoCommit(true);
        order.verify(server).setAutoCommit(false);
        order.verify(statement).execute("CREATE TABLE IF NOT EXISTS p_c (id INT)");
        order.verify(record).setLong(4, 3L);
        order.verify(record).setNull(5, Types.BIGINT);
        order.verify(record).executeUpdate();
        order.verify(server).commit();
        order.verify(server).setAutoCommit(true);
        order.verify(record).setLong(4, 3L);
        order.verify(record).setLong(5, NOW.toEpochMilli());
        order.verify(record).executeUpdate();
        verify(statement, never()).execute("CREATE TABLE IF NOT EXISTS p_a (id INT)");
        verify(server, times(3)).prepareStatement(Dialect.MARIADB.upsert("p_schema_version",
            List.of("version", "name", "checksum", "statements_done", "applied_at"), List.of("version"),
            List.of("name", "checksum", "statements_done", "applied_at")));
        verify(server, times(2)).setAutoCommit(false);
        verify(server, times(2)).commit();
        verify(server, never()).rollback();
        assertThat(backups).hasValue(1);
        assertThat(log.lines()).containsExactly("INFO Applied database migration V1__probe.");
    }

    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = {"MARIADB", "MYSQL"})
    void mariaDbAndMySqlCreateTheVersionTableWithInnoDbAndBinaryCollation(Dialect dialect) throws Exception {
        assertThat(createdVersionTable(dialect)).isEqualTo(MARIADB_VERSION_TABLE);
    }

    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = {"SQLITE", "POSTGRES"})
    void sqliteAndPostgresCreateTheVersionTableWithPortableTypes(Dialect dialect) throws Exception {
        assertThat(createdVersionTable(dialect)).isEqualTo(SQLITE_VERSION_TABLE);
    }

    private String createdVersionTable(Dialect dialect) throws Exception {
        Migration probe = new Migration(1, "probe");
        scripts.put(probe.resource(dialect), "CREATE TABLE ${prefix}probe (id INT);\n");
        Connection server = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet tables = mock(ResultSet.class);
        when(server.createStatement()).thenReturn(statement);
        when(server.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        grantLock(server);
        when(statement.executeQuery(dialect.listTables())).thenReturn(tables);
        when(statement.executeQuery("PRAGMA foreign_keys")).thenReturn(mock(ResultSet.class));

        migrator(dialect, "p_", List.of(probe)).migrate(server, null);

        ArgumentCaptor<String> executed = ArgumentCaptor.forClass(String.class);
        verify(statement, times(2)).execute(executed.capture());
        assertThat(executed.getAllValues().get(1)).isEqualTo("CREATE TABLE p_probe (id INT)");
        verify(server, times(1)).setAutoCommit(false);
        verify(server, times(1)).commit();
        return executed.getAllValues().getFirst();
    }

    @Test
    void aPendingScriptWithABlockCommentStopsTheStartBeforeTheBackupAndNamesTheFileAndLine() throws Exception {
        migrator("", List.of(V1)).migrate(connection, this::backup);
        scripts.put(V2.resource(Dialect.SQLITE), "CREATE TABLE ${prefix}fine (id INTEGER);\n/* a; b */\n");

        assertThatThrownBy(() -> migrator("", List.of(V1, V2)).migrate(connection, this::backup))
            .isInstanceOf(StorageException.class)
            .hasMessage("db/migration/sqlite/V2__probe.sql cannot be split into statements: line 2 holds a block"
                + " comment, which a migration script cannot hold: /* a; b */")
            .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThat(tables()).doesNotContain("fine");
        assertThat(backups).hasValue(0);
    }

    @Test
    void anAppliedScriptThatHoldsARefusedConstructDoesNotStopTheStart() throws Exception {
        String applied = "/* written for 0.3 */\nCREATE TABLE ${prefix}old (id INTEGER);\n";
        scripts.put(V1.resource(Dialect.SQLITE), applied);
        scripts.put(V2.resource(Dialect.SQLITE), "CREATE TABLE ${prefix}probe (id INTEGER);\n");
        execute(SQLITE_VERSION_TABLE.replace("p_schema_version", "schema_version"));
        execute("INSERT INTO schema_version VALUES (1, 'schema_version', '" + Migrator.rawChecksum(applied)
            + "', 1, 5)");

        migrator("", List.of(V1, V2)).migrate(connection, this::backup);

        assertThat(tables()).containsExactly("probe", "schema_version");
        assertThat(log.lines()).containsExactly("INFO Applied database migration V2__probe.");
    }

    @Test
    void theChecksumIgnoresLineEndingsAndALeadingByteOrderMark() {
        String script = "CREATE TABLE a (id INTEGER);\n-- note\nCREATE TABLE b (id INTEGER);\n";

        assertThat(Migrator.checksum(script.replace("\n", "\r\n"))).isEqualTo(Migrator.checksum(script));
        assertThat(Migrator.checksum(script.replace("\n", "\r"))).isEqualTo(Migrator.checksum(script));
        assertThat(Migrator.checksum("﻿" + script)).isEqualTo(Migrator.checksum(script));
        assertThat(Migrator.checksum("﻿" + script.replace("\n", "\r\n"))).isEqualTo(Migrator.checksum(script));
        assertThat(Migrator.checksum(script)).isEqualTo(Migrator.rawChecksum(script));
        assertThat(Migrator.rawChecksum(script.replace("\n", "\r\n"))).isNotEqualTo(Migrator.rawChecksum(script));
        assertThat(Migrator.rawChecksum("﻿" + script)).isNotEqualTo(Migrator.rawChecksum(script));
        assertThat(Migrator.checksum(script.replace("a (", "a  ("))).isNotEqualTo(Migrator.checksum(script));
        assertThat(Migrator.checksum(script + "﻿")).isNotEqualTo(Migrator.checksum(script));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\r"})
    void aBuildWithOtherLineEndingsOrAByteOrderMarkStartsOnADatabaseThatRanTheLfScript(String ending)
        throws Exception {
        migrator("", List.of(V1)).migrate(connection, this::backup);
        List<Object> recorded = row(1);
        log.records.clear();

        scripts.put(V1.resource(Dialect.SQLITE), V1_SCRIPT.replace("\n", ending));
        migrator("", List.of(V1)).migrate(connection, this::backup);
        scripts.put(V1.resource(Dialect.SQLITE), "﻿" + V1_SCRIPT.replace("\n", ending));
        migrator("", List.of(V1)).migrate(connection, this::backup);

        assertThat(log.lines()).isEmpty();
        assertThat(row(1)).isEqualTo(recorded);
        assertThat(backups).hasValue(0);
    }

    @Test
    void aRowThatHoldsTheChecksumOfTheExactBytesIsAcceptedAndKept() throws Exception {
        Migration probe = new Migration(1, "probe");
        String crlf = "CREATE TABLE ${prefix}probe (id INTEGER);\r\n";
        scripts.put(probe.resource(Dialect.SQLITE), crlf);
        scripts.put(V2.resource(Dialect.SQLITE), "CREATE TABLE ${prefix}later (id INTEGER);\r\n");
        execute("CREATE TABLE probe (id INTEGER)");
        execute(SQLITE_VERSION_TABLE.replace("p_schema_version", "schema_version"));
        execute("INSERT INTO schema_version VALUES (1, 'probe', '" + Migrator.rawChecksum(crlf) + "', 1, 5)");

        migrator("", List.of(probe, V2)).migrate(connection, this::backup);

        assertThat(Migrator.rawChecksum(crlf)).isNotEqualTo(Migrator.checksum(crlf));
        assertThat(row(1)).containsExactly("probe", Migrator.rawChecksum(crlf), 1L, 5L);
        assertThat(row(2)).containsExactly("probe", Migrator.checksum("CREATE TABLE ${prefix}later (id INTEGER);\n"),
            1L, NOW.toEpochMilli());
        assertThat(log.lines()).containsExactly("INFO Applied database migration V2__probe.");
    }

    @Test
    void aRowWithTheChecksumOfOtherBytesThanTheScriptsStillStopsTheStart() throws Exception {
        Migration probe = new Migration(1, "probe");
        String lf = "CREATE TABLE ${prefix}probe (id INTEGER);\n";
        String crlf = lf.replace("\n", "\r\n");
        scripts.put(probe.resource(Dialect.SQLITE), lf.replace("probe (", "probe  ("));
        execute(SQLITE_VERSION_TABLE.replace("p_schema_version", "schema_version"));
        execute("INSERT INTO schema_version VALUES (1, 'probe', '" + Migrator.rawChecksum(crlf) + "', 1, 5)");

        assertThatThrownBy(() -> migrator("", List.of(probe)).migrate(connection, this::backup))
            .isInstanceOf(StorageException.class)
            .hasMessageStartingWith("migration V1__probe changed after it ran on this database (stored checksum "
                + Migrator.rawChecksum(crlf) + ", now " + Migrator.checksum(lf.replace("probe (", "probe  (")) + ")");
    }

    @Test
    void aScriptWithCrlfAndAByteOrderMarkRunsAndIsRecordedWithTheNormalizedChecksum() throws Exception {
        Migration probe = new Migration(1, "probe");
        String lf = "CREATE TABLE ${prefix}probe (\n    id INTEGER\n);\nINSERT INTO ${prefix}probe VALUES (7);\n";
        String shipped = "﻿" + lf.replace("\n", "\r\n");
        scripts.put(probe.resource(Dialect.SQLITE), shipped);

        migrator("", List.of(probe)).migrate(connection, this::backup);

        assertThat(row(1)).containsExactly("probe", Migrator.checksum(lf), 2L, NOW.toEpochMilli());
        assertThat(Migrator.checksum(lf)).isNotEqualTo(Migrator.rawChecksum(shipped));
        assertThat(count(connection, "probe")).isEqualTo(1);
    }

    @Test
    void aScriptThatOnlySelectsIsAppliedAndRecorded() throws Exception {
        Migration noop = new Migration(2, "noop");
        scripts.put(noop.resource(Dialect.SQLITE), "SELECT 1;\n");

        migrator("", List.of(V1, noop)).migrate(connection, this::backup);
        migrator("", List.of(V1, noop)).migrate(connection, this::backup);

        assertThat(tables()).containsExactly("schema_version");
        assertThat(row(2)).containsExactly("noop", Migrator.checksum("SELECT 1;\n"), 1L, NOW.toEpochMilli());
        assertThat(log.lines()).containsExactly("INFO Applied database migration V1__schema_version.",
            "INFO Applied database migration V2__noop.");
    }

    @Test
    void aMigrationMayHaveAnotherShapeOnEachDialect() throws Exception {
        Migration shaped = new Migration(1, "shaped");
        String sqlite = """
            CREATE TABLE ${prefix}shaped_new (id INTEGER PRIMARY KEY, note TEXT);
            CREATE TABLE ${prefix}shaped (id INTEGER PRIMARY KEY);
            DROP TABLE ${prefix}shaped_new;
            """;
        scripts.put(shaped.resource(Dialect.SQLITE), sqlite);
        scripts.put(shaped.resource(Dialect.MARIADB), "SELECT 1;\n");
        Connection server = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement record = mock(PreparedStatement.class);
        when(server.createStatement()).thenReturn(statement);
        when(server.prepareStatement(anyString())).thenReturn(record);
        grantLock(server);
        when(statement.executeQuery(Dialect.MARIADB.listTables())).thenReturn(mock(ResultSet.class));

        migrator("", List.of(shaped)).migrate(connection, this::backup);
        migrator(Dialect.MARIADB, "p_", List.of(shaped)).migrate(server, null);

        assertThat(tables()).containsExactly("schema_version", "shaped");
        assertThat(row(1)).containsExactly("shaped", Migrator.checksum(sqlite), 3L, NOW.toEpochMilli());
        verify(statement).execute("SELECT 1");
        verify(record, atLeastOnce()).setString(3, Migrator.checksum("SELECT 1;\n"));
        verify(record, times(2)).setLong(4, 1L);
        verify(record, never()).setLong(4, 3L);
    }

    @Test
    void aSqliteMigrationRunsOverRowsThatBrokeAForeignKeyBeforeItAndIsBlamedOnlyForNewOnes() throws Exception {
        try (Connection enforcing = foreignKeyConnection(); Statement statement = enforcing.createStatement()) {
            Migration note = new Migration(2, "note");
            Migration orphan = new Migration(3, "orphan");
            scripts.put(V1.resource(Dialect.SQLITE), """
                CREATE TABLE parent (id INTEGER PRIMARY KEY);
                CREATE TABLE child (id INTEGER PRIMARY KEY, parent INTEGER REFERENCES parent (id));
                INSERT INTO parent VALUES (1);
                INSERT INTO child VALUES (10, 1);
                """);
            scripts.put(note.resource(Dialect.SQLITE), "ALTER TABLE parent ADD COLUMN note TEXT;\n");
            scripts.put(orphan.resource(Dialect.SQLITE), "DELETE FROM parent;\n");
            migrator("", List.of(V1)).migrate(enforcing, this::backup);
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.execute("INSERT INTO child VALUES (11, 99)");
            statement.execute("PRAGMA foreign_keys = ON");
            log.records.clear();

            migrator("", List.of(V1, note)).migrate(enforcing, this::backup);

            assertThat(log.lines()).containsExactly("WARNING Before migration V2__note ran, the database already held"
                + " rows whose foreign key refers to a missing row, in child. The migration runs and those rows stay"
                + " as they are.", "INFO Applied database migration V2__note.");
            assertThat(count(enforcing, "child")).isEqualTo(2);
            assertThat(foreignKeys(enforcing)).isEqualTo(1);

            assertThatThrownBy(() -> migrator("", List.of(V1, note, orphan)).migrate(enforcing, this::backup))
                .isInstanceOf(StorageException.class)
                .hasMessage("migration V3__orphan leaves a row of child whose foreign key refers to a missing row of"
                    + " parent; it was rolled back");
            assertThat(count(enforcing, "parent")).isEqualTo(1);
            assertThat(foreignKeys(enforcing)).isEqualTo(1);
        }
    }

    @Test
    void oneWarningPerStartNamesTheRowsThatBrokeAForeignKeyBeforeTheFirstPendingMigration() throws Exception {
        try (Connection enforcing = foreignKeyConnection(); Statement statement = enforcing.createStatement()) {
            Migration second = new Migration(2, "second");
            Migration third = new Migration(3, "third");
            scripts.put(V1.resource(Dialect.SQLITE), """
                CREATE TABLE parent (id INTEGER PRIMARY KEY);
                CREATE TABLE child (id INTEGER PRIMARY KEY, parent INTEGER REFERENCES parent (id));
                """);
            scripts.put(second.resource(Dialect.SQLITE), "CREATE TABLE second (id INTEGER);\n");
            scripts.put(third.resource(Dialect.SQLITE), "CREATE TABLE third (id INTEGER);\n");
            migrator("", List.of(V1)).migrate(enforcing, this::backup);
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.execute("INSERT INTO child VALUES (11, 99)");
            statement.execute("PRAGMA foreign_keys = ON");
            log.records.clear();

            migrator("", List.of(V1, second, third)).migrate(enforcing, this::backup);

            assertThat(log.lines()).hasSize(3).filteredOn(line -> line.startsWith("WARNING")).hasSize(1);
        }
    }

    @Test
    void aMariaDbStatementAndItsProgressRecordRollBackTogether() throws Exception {
        Migration probe = new Migration(1, "probe");
        scripts.put(probe.resource(Dialect.MARIADB), "CREATE TABLE IF NOT EXISTS ${prefix}a (id INT);\n"
            + "UPDATE ${prefix}a SET id = id * 10;\n");
        Connection server = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement record = mock(PreparedStatement.class);
        when(server.createStatement()).thenReturn(statement);
        when(server.prepareStatement(anyString())).thenReturn(record);
        grantLock(server);
        when(statement.executeQuery(Dialect.MARIADB.listTables())).thenReturn(mock(ResultSet.class));
        SQLException lost = new SQLException("Connection reset", "08S01");
        when(record.executeUpdate()).thenReturn(1).thenThrow(lost);

        assertThatThrownBy(() -> migrator(Dialect.MARIADB, "p_", List.of(probe)).migrate(server, null))
            .isSameAs(lost);

        InOrder order = inOrder(server, statement, record);
        order.verify(statement).execute(MARIADB_VERSION_TABLE);
        order.verify(server).setAutoCommit(false);
        order.verify(statement).execute("CREATE TABLE IF NOT EXISTS p_a (id INT)");
        order.verify(record).executeUpdate();
        order.verify(server).commit();
        order.verify(server).setAutoCommit(true);
        order.verify(server).setAutoCommit(false);
        order.verify(statement).execute("UPDATE p_a SET id = id * 10");
        order.verify(record).executeUpdate();
        order.verify(server).rollback();
        order.verify(server).setAutoCommit(true);
        verify(server, times(1)).commit();
        verify(record, times(2)).executeUpdate();
    }

    @Test
    void aPostgresLockHeldElsewhereStopsTheStartOnceTheWaitHasPassedWithoutReleasingIt() throws Exception {
        Connection server = mock(Connection.class);
        PreparedStatement lock = mock(PreparedStatement.class);
        ResultSet refused = mock(ResultSet.class);
        when(server.getNetworkTimeout()).thenReturn(30_000);
        when(server.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        nameDatabase(server, "public");
        when(server.prepareStatement("SELECT pg_try_advisory_lock(?)")).thenReturn(lock);
        when(lock.executeQuery()).thenReturn(refused);
        when(refused.next()).thenReturn(true);
        when(refused.getBoolean(1)).thenReturn(false);
        Migrator waiting = migrator(Dialect.POSTGRES, "p_", "p_schema_version", List.of(V1), Duration.ofMillis(600));

        assertThatThrownBy(() -> waiting.migrate(server, null))
            .isInstanceOf(StorageException.class)
            .hasMessage("another start has held the migration lock of p_schema_version in database public for"
                + " 600ms; restart once the other server has started");
        verify(lock).setLong(1, Migrator.lockKey("public", "p_schema_version"));
        verify(lock, atLeast(2)).executeQuery();
        verify(server, never()).prepareStatement("SELECT pg_advisory_lock(?)");
        verify(server, never()).prepareStatement("SELECT pg_advisory_unlock(?)");
        verify(server, never()).createStatement();
        verify(server).setNetworkTimeout(any(), eq(30_000));
    }

    @Test
    void aPostgresLockFreedWhileTheStartWaitsIsTakenAndReleased() throws Exception {
        Connection server = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement lock = mock(PreparedStatement.class);
        PreparedStatement unlock = mock(PreparedStatement.class);
        ResultSet answer = mock(ResultSet.class);
        when(server.createStatement()).thenReturn(statement);
        when(server.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        nameDatabase(server, "public");
        when(server.prepareStatement("SELECT pg_try_advisory_lock(?)")).thenReturn(lock);
        when(server.prepareStatement("SELECT pg_advisory_unlock(?)")).thenReturn(unlock);
        when(lock.executeQuery()).thenReturn(answer);
        when(answer.next()).thenReturn(true);
        when(answer.getBoolean(1)).thenReturn(false, false, true);
        when(statement.executeQuery(Dialect.POSTGRES.listTables())).thenReturn(mock(ResultSet.class));

        migrator(Dialect.POSTGRES, "p_", List.of()).migrate(server, null);

        verify(lock, times(3)).executeQuery();
        verify(unlock).setLong(1, Migrator.lockKey("public", "p_schema_version"));
        verify(unlock).execute();
    }

    @Test
    void aMySqlLockIsAskedForWithTheWaitInSecondsAndTheMessageNamesAShorterWait() throws Exception {
        Connection server = mock(Connection.class);
        PreparedStatement lock = mock(PreparedStatement.class);
        ResultSet refused = mock(ResultSet.class);
        when(server.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        nameDatabase(server, "shop");
        when(server.prepareStatement("SELECT GET_LOCK(?, ?)")).thenReturn(lock);
        when(lock.executeQuery()).thenReturn(refused);
        when(refused.next()).thenReturn(true);
        when(refused.getInt(1)).thenReturn(0);
        Migrator waiting = migrator(Dialect.MYSQL, "p_", "p_schema_version", List.of(V1), Duration.ofSeconds(2));

        assertThatThrownBy(() -> waiting.migrate(server, null))
            .isInstanceOf(StorageException.class)
            .hasMessage("another start has held the migration lock of p_schema_version in database shop for 2s;"
                + " restart once the other server has started");
        verify(lock).setLong(2, 2L);
    }

    @Test
    void theVersionTableCarriesTheNameItIsGiven() throws Exception {
        Migration probe = new Migration(1, "probe");
        String script = "CREATE TABLE ${prefix}probe (id INTEGER);\n";
        scripts.put(probe.resource(Dialect.SQLITE), script);

        named("p_", "shop_versions", List.of(probe)).migrate(connection, this::backup);
        named("p_", "shop_versions", List.of(probe)).migrate(connection, this::backup);

        assertThat(tables()).containsExactly("p_probe", "shop_versions");
        assertThat(row("shop_versions", 1)).containsExactly("probe", Migrator.checksum(script), 1L,
            NOW.toEpochMilli());
        assertThat(log.lines()).containsExactly("INFO Applied database migration V1__probe.");
    }

    @Test
    void anEarlierVersionTableWithThisPluginsRowsIsCopiedOnceAndStaysAsItWas() throws Exception {
        Migration one = new Migration(1, "one");
        Migration two = new Migration(2, "two");
        scripts.put(one.resource(Dialect.SQLITE), "CREATE TABLE ${prefix}one (id INTEGER);\n");
        scripts.put(two.resource(Dialect.SQLITE), "CREATE TABLE ${prefix}two (id INTEGER);\n");
        migrator("p_", List.of(one)).migrate(connection, this::backup);
        List<Object> earlier = row("p_schema_version", 1);
        log.records.clear();

        named("p_", "p_shop_schema_version", List.of(one, two)).migrate(connection, this::backup);
        named("p_", "p_shop_schema_version", List.of(one, two)).migrate(connection, this::backup);

        assertThat(tables()).containsExactly("p_one", "p_schema_version", "p_shop_schema_version", "p_two");
        assertThat(row("p_shop_schema_version", 1)).isEqualTo(earlier).hasSize(4);
        assertThat(row("p_shop_schema_version", 2)).hasSize(4);
        assertThat(row("p_schema_version", 1)).isEqualTo(earlier);
        assertThat(row("p_schema_version", 2)).isEmpty();
        assertThat(log.lines()).containsExactly("INFO Copied the migrations p_schema_version records into"
            + " p_shop_schema_version, which records them from now on. p_schema_version stays as it was and is no"
            + " longer read.", "INFO Applied database migration V2__two.");
        assertThat(backups).hasValue(1);
    }

    @Test
    void anEarlierVersionTableIsCopiedWithoutABackupWhenNoMigrationIsPending() throws Exception {
        Migration one = new Migration(1, "one");
        scripts.put(one.resource(Dialect.SQLITE), "CREATE TABLE ${prefix}one (id INTEGER);\n");
        migrator("p_", List.of(one)).migrate(connection, this::backup);
        log.records.clear();

        named("p_", "p_shop_schema_version", List.of(one)).migrate(connection, this::backup);

        assertThat(row("p_shop_schema_version", 1)).isEqualTo(row("p_schema_version", 1)).hasSize(4);
        assertThat(log.lines()).hasSize(1).first().asString().startsWith("INFO Copied the migrations");
        assertThat(backups).hasValue(0);
    }

    @Test
    void twoPluginsUnderOnePrefixKeepAHistoryEachAndLeaveAnotherPluginsEarlierTableAlone() throws Exception {
        Migration shop = new Migration(1, "shop");
        Migration bank = new Migration(1, "bank");
        Migration bankTwo = new Migration(2, "more");
        scripts.put(shop.resource(Dialect.SQLITE), "CREATE TABLE ${prefix}shop (id INTEGER);\n");
        scripts.put(bank.resource(Dialect.SQLITE), "CREATE TABLE ${prefix}bank (id INTEGER);\n");
        scripts.put(bankTwo.resource(Dialect.SQLITE), "CREATE TABLE ${prefix}more (id INTEGER);\n");
        migrator("p_", List.of(bank)).migrate(connection, this::backup);
        log.records.clear();

        named("p_", "p_shop_schema_version", List.of(shop)).migrate(connection, this::backup);
        named("p_", "p_bank_schema_version", List.of(bank, bankTwo)).migrate(connection, this::backup);
        named("p_", "p_shop_schema_version", List.of(shop)).migrate(connection, this::backup);

        assertThat(tables()).containsExactly("p_bank", "p_bank_schema_version", "p_more", "p_schema_version",
            "p_shop", "p_shop_schema_version");
        assertThat(row("p_shop_schema_version", 1)).first().isEqualTo("shop");
        assertThat(row("p_shop_schema_version", 2)).isEmpty();
        assertThat(row("p_bank_schema_version", 1)).first().isEqualTo("bank");
        assertThat(row("p_bank_schema_version", 2)).first().isEqualTo("more");
        assertThat(row("p_schema_version", 1)).first().isEqualTo("bank");
        assertThat(log.lines()).containsExactly("INFO Applied database migration V1__shop.",
            "INFO Copied the migrations p_schema_version records into p_bank_schema_version, which records them from"
                + " now on. p_schema_version stays as it was and is no longer read.",
            "INFO Applied database migration V2__more.");
    }

    @Test
    void anEarlierVersionTableWhoseScriptChangedStopsTheStartBeforeItIsCopied() throws Exception {
        Migration one = new Migration(1, "one");
        Migration two = new Migration(2, "two");
        scripts.put(one.resource(Dialect.SQLITE), "CREATE TABLE ${prefix}one (id INTEGER);\n");
        scripts.put(two.resource(Dialect.SQLITE), "CREATE TABLE ${prefix}two (id INTEGER);\n");
        migrator("p_", List.of(one, two)).migrate(connection, this::backup);
        scripts.put(two.resource(Dialect.SQLITE), "CREATE TABLE ${prefix}two (id INTEGER, note TEXT);\n");

        assertThatThrownBy(() -> named("p_", "p_shop_schema_version", List.of(one, two))
            .migrate(connection, this::backup))
            .isInstanceOf(StorageException.class)
            .hasMessageStartingWith("migration V2__two changed after it ran on this database (stored checksum ");
        assertThat(tables()).doesNotContain("p_shop_schema_version");
        assertThat(backups).hasValue(0);
    }

    @Test
    void anEarlierVersionTableAheadOfThisVersionStopsTheStartBeforeItIsCopied() throws Exception {
        Migration one = new Migration(1, "one");
        Migration two = new Migration(2, "two");
        scripts.put(one.resource(Dialect.SQLITE), "CREATE TABLE ${prefix}one (id INTEGER);\n");
        scripts.put(two.resource(Dialect.SQLITE), "CREATE TABLE ${prefix}two (id INTEGER);\n");
        migrator("p_", List.of(one, two)).migrate(connection, this::backup);

        assertThatThrownBy(() -> named("p_", "p_shop_schema_version", List.of(one))
            .migrate(connection, this::backup))
            .isInstanceOf(StorageException.class)
            .hasMessageStartingWith("the database is at schema version 2, set up by a newer version of the plugin");
        assertThat(tables()).doesNotContain("p_shop_schema_version");
    }

    @Test
    void theOtherPrefixWarningNamesOnlyAVersionTableThatHoldsThisPluginsChecksums() throws Exception {
        Migration shop = new Migration(1, "shop");
        Migration bank = new Migration(1, "bank");
        scripts.put(shop.resource(Dialect.SQLITE), "CREATE TABLE ${prefix}shop (id INTEGER);\n");
        scripts.put(bank.resource(Dialect.SQLITE), "CREATE TABLE ${prefix}bank (id INTEGER);\n");
        migrator("a_", List.of(bank)).migrate(connection, this::backup);
        named("a_", "a_bank_schema_version", List.of(bank)).migrate(connection, this::backup);
        log.records.clear();

        named("b_", "b_shop_schema_version", List.of(shop)).migrate(connection, this::backup);
        named("c_", "c_shop_schema_version", List.of(shop)).migrate(connection, this::backup);

        assertThat(log.lines()).containsExactly("INFO Applied database migration V1__shop.",
            "WARNING The database holds tables under the prefix \"b_\", but storage uses \"c_\", so the plugin"
                + " starts with empty tables. Set the table prefix to \"b_\" and restart to use that data.",
            "INFO Applied database migration V1__shop.");
    }

    @Test
    void aVersionTableOfThisPluginAndItsEarlierOneUnderAnotherPrefixRaiseOneWarning() throws Exception {
        Migration shop = new Migration(1, "shop");
        scripts.put(shop.resource(Dialect.SQLITE), "CREATE TABLE ${prefix}shop (id INTEGER);\n");
        migrator("a_", List.of(shop)).migrate(connection, this::backup);
        named("a_", "a_shop_schema_version", List.of(shop)).migrate(connection, this::backup);
        log.records.clear();

        named("b_", "b_shop_schema_version", List.of(shop)).migrate(connection, this::backup);

        assertThat(log.lines()).containsExactly(
            "WARNING The database holds tables under the prefix \"a_\", but storage uses \"b_\", so the plugin"
                + " starts with empty tables. Set the table prefix to \"a_\" and restart to use that data.",
            "INFO Applied database migration V1__shop.");
    }

    @Test
    void aServerStartHoldsTheLockOfTheEarlierVersionTableTooWhileItReadsIt() throws Exception {
        Migration probe = new Migration(1, "probe");
        scripts.put(probe.resource(Dialect.MYSQL), "CREATE TABLE IF NOT EXISTS ${prefix}a (id INT);\n");
        Connection server = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement lock = mock(PreparedStatement.class);
        PreparedStatement unlock = mock(PreparedStatement.class);
        when(server.createStatement()).thenReturn(statement);
        when(server.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        grantLock(server, lock);
        when(server.prepareStatement("SELECT RELEASE_LOCK(?)")).thenReturn(unlock);
        when(statement.executeQuery(Dialect.MYSQL.listTables())).thenAnswer(call -> {
            ResultSet tables = mock(ResultSet.class);
            when(tables.next()).thenReturn(true, false);
            when(tables.getString(1)).thenReturn("p_schema_version");
            return tables;
        });
        when(statement.executeQuery(LAYOUT_PROBE)).thenReturn(mock(ResultSet.class));
        when(statement.executeQuery("SELECT version, checksum, statements_done, applied_at FROM p_schema_version"))
            .thenReturn(mock(ResultSet.class));
        String ours = Migrator.lockName("shop", "p_shop_schema_version");
        String earlier = Migrator.lockName("shop", "p_schema_version");

        migrator(Dialect.MYSQL, "p_", "p_shop_schema_version", List.of(probe), Migrator.LOCK_WAIT)
            .migrate(server, null);

        InOrder order = inOrder(statement, lock, unlock);
        order.verify(lock).setString(1, ours);
        order.verify(lock).executeQuery();
        order.verify(statement).executeQuery(Dialect.MYSQL.listTables());
        order.verify(lock).setString(1, earlier);
        order.verify(lock).executeQuery();
        order.verify(statement).executeQuery(Dialect.MYSQL.listTables());
        order.verify(statement).execute(MARIADB_VERSION_TABLE.replace("p_schema_version", "p_shop_schema_version"));
        order.verify(statement).execute("CREATE TABLE IF NOT EXISTS p_a (id INT)");
        order.verify(unlock).setString(1, earlier);
        order.verify(unlock).execute();
        order.verify(unlock).setString(1, ours);
        order.verify(unlock).execute();
    }

    private void backup(Connection ignored) {
        backups.incrementAndGet();
    }

    private static void grantLock(Connection server) throws SQLException {
        grantLock(server, mock(PreparedStatement.class));
    }

    // Grants the lock on the first try, whether it is taken with GET_LOCK or with pg_try_advisory_lock.
    private static void grantLock(Connection server, PreparedStatement lock) throws SQLException {
        nameDatabase(server, "shop");
        ResultSet granted = mock(ResultSet.class);
        when(server.prepareStatement("SELECT GET_LOCK(?, ?)")).thenReturn(lock);
        when(server.prepareStatement("SELECT pg_try_advisory_lock(?)")).thenReturn(lock);
        when(lock.executeQuery()).thenReturn(granted);
        when(granted.next()).thenReturn(true);
        when(granted.getInt(1)).thenReturn(1);
        when(granted.getBoolean(1)).thenReturn(true);
    }

    // Answers both SELECT DATABASE() and SELECT current_schema() with the name.
    private static void nameDatabase(Connection server, @Nullable String database) throws SQLException {
        for (String query : List.of("SELECT DATABASE()", "SELECT current_schema()")) {
            PreparedStatement current = mock(PreparedStatement.class);
            ResultSet result = mock(ResultSet.class);
            when(server.prepareStatement(query)).thenReturn(current);
            when(current.executeQuery()).thenReturn(result);
            when(result.next()).thenReturn(true);
            when(result.getString(1)).thenReturn(database);
        }
    }

    private Connection foreignKeyConnection() throws SQLException {
        Properties enforcing = new Properties();
        enforcing.setProperty("foreign_keys", "true");
        return DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("keys.db"), enforcing);
    }

    private static long count(Connection on, String table) throws SQLException {
        try (Statement statement = on.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static int foreignKeys(Connection on) throws SQLException {
        try (Statement statement = on.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA foreign_keys")) {
            result.next();
            return result.getInt(1);
        }
    }

    private long postgresLockKey(String schema) throws Exception {
        Connection server = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement lock = mock(PreparedStatement.class);
        when(server.createStatement()).thenReturn(statement);
        when(server.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        grantLock(server, lock);
        when(statement.executeQuery(Dialect.POSTGRES.listTables())).thenReturn(mock(ResultSet.class));
        nameDatabase(server, schema);

        migrator(Dialect.POSTGRES, "p_", List.of()).migrate(server, null);

        ArgumentCaptor<Long> key = ArgumentCaptor.forClass(Long.class);
        verify(lock).setLong(eq(1), key.capture());
        return key.getValue();
    }

    private List<String> columns(String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM " + table)) {
            for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                columns.add(result.getMetaData().getColumnName(column));
            }
        }
        return columns;
    }

    private Migrator migrator(String prefix, List<Migration> migrations) {
        return migrator(Dialect.SQLITE, prefix, migrations);
    }

    private Migrator migrator(Dialect dialect, String prefix, List<Migration> migrations) {
        return new Migrator(dialect, prefix, CLOCK, log.logger, migrations, this::resource);
    }

    private Migrator named(String prefix, String versionTable, List<Migration> migrations) {
        return migrator(Dialect.SQLITE, prefix, versionTable, migrations, Migrator.LOCK_WAIT);
    }

    private Migrator migrator(Dialect dialect, String prefix, String versionTable, List<Migration> migrations,
                              Duration lockWait) {
        return new Migrator(dialect, prefix, versionTable, CLOCK, log.logger, migrations, this::resource, null,
            lockWait);
    }

    private @Nullable InputStream resource(String path) {
        return scripts.containsKey(path)
            ? new ByteArrayInputStream(scripts.get(path).getBytes(StandardCharsets.UTF_8)) : null;
    }

    private List<String> tables() throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")) {
            while (result.next()) {
                tables.add(result.getString(1));
            }
        }
        return tables;
    }

    private List<Object> row(int version) throws SQLException {
        return row("schema_version", version);
    }

    private List<Object> row(String table, int version) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "SELECT name, checksum, statements_done, applied_at FROM " + table + " WHERE version = " + version)) {
            if (!result.next()) {
                return List.of();
            }
            return List.of(result.getString(1), result.getString(2), result.getLong(3), result.getLong(4));
        }
    }

    private String checksum(String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT checksum FROM " + table + " WHERE version = 1")) {
            result.next();
            return result.getString(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
