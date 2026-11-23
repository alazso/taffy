package so.alaz.taffy.storage;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.test.CapturedLog;
import so.alaz.taffy.test.FaultyDataSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class BaselineTest {

    private static final String NAME = "baseline-probe";

    private static final String LEGACY = "legacy_schema_version";

    private static final Baseline BASELINE = Baseline.versionTable(LEGACY);

    private static final List<Migration> MIGRATIONS =
        List.of(new Migration(1, "alpha"), new Migration(2, "beta"), new Migration(3, "gamma"));

    private static final Map<String, String> SCRIPTS = Map.of(
        "db/migration/sqlite/V1__alpha.sql", "CREATE TABLE alpha (id INTEGER PRIMARY KEY);\n",
        "db/migration/sqlite/V2__beta.sql", """
            CREATE TABLE beta (id INTEGER PRIMARY KEY);
            CREATE INDEX beta_id ON beta (id);
            """,
        "db/migration/sqlite/V3__gamma.sql", "CREATE TABLE gamma (id INTEGER PRIMARY KEY);\n");

    private static final Clock LATER = Clock.offset(StorageContract.CLOCK, Duration.ofDays(1));

    @TempDir
    private Path folder;

    private final CapturedLog log = new CapturedLog();

    @Test
    void aSingleRowVersionTableIsTakenOverAndTheLaterMigrationsRun() throws Exception {
        sql("CREATE TABLE " + LEGACY + " (version INTEGER NOT NULL)",
            "INSERT INTO " + LEGACY + " VALUES (2)",
            "CREATE TABLE alpha (id INTEGER PRIMARY KEY)",
            "INSERT INTO alpha VALUES (7)",
            "CREATE TABLE beta (id INTEGER PRIMARY KEY)");

        start(BASELINE, SCRIPTS, StorageContract.CLOCK);

        try (Connection connection = connect()) {
            assertThat(tables(connection)).containsExactly("alpha", "beta", "gamma", LEGACY + "_baseline",
                "schema_version");
            assertThat(ProbeSchema.versions(connection, "")).containsExactly(1, 2, 3);
            for (Migration migration : MIGRATIONS) {
                String script = SCRIPTS.get(migration.resource(Dialect.SQLITE));
                assertThat(StorageContract.checksum(connection, "", migration.version()))
                    .isEqualTo(Migrator.checksum(script));
                assertThat(ProbeSchema.statementsDone(connection, "", migration.version()))
                    .isEqualTo(SqlScript.statements(script, Dialect.SQLITE).size());
                assertThat(ProbeSchema.appliedAt(connection, "", migration.version()))
                    .isEqualTo(StorageContract.NOW.toEpochMilli());
            }
            assertThat(ProbeSchema.ids(connection, "alpha", "id")).containsExactly(7);
            assertThat(ProbeSchema.ids(connection, LEGACY + "_baseline", "version")).containsExactly(2);
        }
        assertThat(backups()).hasSize(1);
        assertThat(log.lines()).containsExactly(
            "INFO Copied the database to backups/data-9.9.9-20261102-101530.db before upgrading its layout.",
            "INFO Took over schema version 2 from " + LEGACY + ", now renamed to " + LEGACY + "_baseline.",
            "INFO Applied database migration V3__gamma.");
    }

    @Test
    void aMultiRowVersionTableAtTheNewestVersionRunsNoMigration() throws Exception {
        sql("CREATE TABLE " + LEGACY + " (installed_rank INTEGER PRIMARY KEY, version INTEGER, description TEXT)",
            "INSERT INTO " + LEGACY + " VALUES (1, NULL, 'created')",
            "INSERT INTO " + LEGACY + " VALUES (2, 1, 'first')",
            "INSERT INTO " + LEGACY + " VALUES (3, 3, 'third')",
            "INSERT INTO " + LEGACY + " VALUES (4, 2, 'second')",
            "CREATE TABLE alpha (id INTEGER PRIMARY KEY)",
            "CREATE TABLE beta (id INTEGER PRIMARY KEY)",
            "CREATE TABLE gamma (id INTEGER PRIMARY KEY)");

        start(BASELINE, SCRIPTS, StorageContract.CLOCK);

        try (Connection connection = connect()) {
            assertThat(tables(connection)).containsExactly("alpha", "beta", "gamma", LEGACY + "_baseline",
                "schema_version");
            assertThat(ProbeSchema.versions(connection, "")).containsExactly(1, 2, 3);
        }
        assertThat(log.lines()).filteredOn(line -> line.contains("Applied")).isEmpty();
        assertThat(log.lines()).contains(
            "INFO Took over schema version 3 from " + LEGACY + ", now renamed to " + LEGACY + "_baseline.");
    }

    @Test
    void aRenamedOldTableWithoutTheOldTableIsNotTakenOver() throws Exception {
        sql("CREATE TABLE " + LEGACY + "_baseline (version INTEGER NOT NULL)",
            "INSERT INTO " + LEGACY + "_baseline VALUES (2)");

        start(BASELINE, SCRIPTS, StorageContract.CLOCK);

        try (Connection connection = connect()) {
            assertThat(tables(connection)).containsExactly("alpha", "beta", "gamma", LEGACY + "_baseline",
                "schema_version");
            assertThat(ProbeSchema.versions(connection, "")).containsExactly(1, 2, 3);
            assertThat(ProbeSchema.ids(connection, LEGACY + "_baseline", "version")).containsExactly(2);
        }
        assertThat(log.lines()).filteredOn(line -> line.contains("Took over")).isEmpty();
        assertThat(log.lines()).contains("INFO Applied database migration V1__alpha.",
            "INFO Applied database migration V2__beta.", "INFO Applied database migration V3__gamma.");
    }

    @Test
    void aTakenNameMovesTheOldTableToTheFirstFreeNumberedName() throws Exception {
        sql("CREATE TABLE " + LEGACY + " (version INTEGER NOT NULL)",
            "INSERT INTO " + LEGACY + " VALUES (1)",
            "CREATE TABLE " + LEGACY + "_baseline (note TEXT)",
            "CREATE TABLE " + LEGACY + "_baseline2 (note TEXT)",
            "CREATE TABLE alpha (id INTEGER PRIMARY KEY)");

        start(BASELINE, SCRIPTS, StorageContract.CLOCK);

        try (Connection connection = connect()) {
            assertThat(tables(connection)).containsExactly("alpha", "beta", "gamma", LEGACY + "_baseline",
                LEGACY + "_baseline2", LEGACY + "_baseline3", "schema_version");
            assertThat(ProbeSchema.ids(connection, LEGACY + "_baseline3", "version")).containsExactly(1);
            assertThat(StorageContract.columns(connection, LEGACY + "_baseline")).containsExactly("note");
        }
    }

    @Test
    void anOldTableDeclaredInAnotherCaseIsTakenOverOnSqlite() throws Exception {
        String declared = "Legacy_Schema_Version";
        sql("CREATE TABLE " + declared + " (version INTEGER NOT NULL)",
            "INSERT INTO " + declared + " VALUES (2)",
            "CREATE TABLE LEGACY_SCHEMA_VERSION_BASELINE (note TEXT)",
            "CREATE TABLE alpha (id INTEGER PRIMARY KEY)",
            "CREATE TABLE beta (id INTEGER PRIMARY KEY)");

        start(BASELINE, SCRIPTS, StorageContract.CLOCK);

        try (Connection connection = connect()) {
            assertThat(tables(connection)).containsExactlyInAnyOrder("alpha", "beta", "gamma",
                "LEGACY_SCHEMA_VERSION_BASELINE", LEGACY + "_baseline2", "schema_version");
            assertThat(ProbeSchema.versions(connection, "")).containsExactly(1, 2, 3);
            assertThat(ProbeSchema.ids(connection, LEGACY + "_baseline2", "version")).containsExactly(2);
        }
        assertThat(log.lines()).containsExactly(
            "INFO Copied the database to backups/data-9.9.9-20261102-101530.db before upgrading its layout.",
            "INFO Took over schema version 2 from " + declared + ", now renamed to " + LEGACY + "_baseline2.",
            "INFO Applied database migration V3__gamma.");
    }

    @Test
    void aVersionAboveTheScriptsStopsTheStartBeforeAnyChange() throws Exception {
        sql("CREATE TABLE " + LEGACY + " (version INTEGER NOT NULL)",
            "INSERT INTO " + LEGACY + " VALUES (4)");

        assertThatThrownBy(() -> start(BASELINE, SCRIPTS, StorageContract.CLOCK))
            .isInstanceOf(CompletionException.class)
            .cause().isInstanceOf(StorageException.class)
            .hasMessage("the table " + LEGACY + " records schema version 4, but this version of the plugin knows"
                + " schema versions up to 3; install the plugin version that set up the database, or restore a"
                + " backup of the database");

        assertUnchanged();
    }

    @Test
    void aVersionWithNoMigrationStopsTheStartBeforeAnyChange() throws Exception {
        sql("CREATE TABLE " + LEGACY + " (version INTEGER NOT NULL)",
            "INSERT INTO " + LEGACY + " VALUES (3)");
        List<Migration> migrations =
            List.of(new Migration(1, "alpha"), new Migration(2, "beta"), new Migration(5, "epsilon"));
        Map<String, String> scripts = new HashMap<>(SCRIPTS);
        scripts.put("db/migration/sqlite/V5__epsilon.sql", "CREATE TABLE epsilon (id INTEGER PRIMARY KEY);\n");

        assertThatThrownBy(() -> start(BASELINE, scripts, StorageContract.CLOCK, migrations))
            .cause().isInstanceOf(StorageException.class)
            .hasMessage("the table " + LEGACY + " records schema version 3, but this version of the plugin has no"
                + " migration with that version; install the plugin version that set up the database, or restore a"
                + " backup of the database");

        assertUnchanged();
    }

    @Test
    void aMissingScriptStopsTheStartBeforeAnyChange() throws Exception {
        sql("CREATE TABLE " + LEGACY + " (version INTEGER NOT NULL)",
            "INSERT INTO " + LEGACY + " VALUES (2)");
        Map<String, String> scripts = new HashMap<>(SCRIPTS);
        scripts.remove("db/migration/sqlite/V2__beta.sql");

        assertThatThrownBy(() -> start(BASELINE, scripts, StorageContract.CLOCK))
            .cause().isInstanceOf(StorageException.class)
            .hasMessage("the plugin has no db/migration/sqlite/V2__beta.sql");

        assertUnchanged();
    }

    @Test
    void aScriptThatCannotBeSplitStopsTheStartBeforeAnyChange() throws Exception {
        sql("CREATE TABLE " + LEGACY + " (version INTEGER NOT NULL)",
            "INSERT INTO " + LEGACY + " VALUES (1)");
        Map<String, String> scripts = new HashMap<>(SCRIPTS);
        scripts.put("db/migration/sqlite/V1__alpha.sql", "CREATE TABLE alpha (id INTEGER PRIMARY KEY); SELECT 1;\n");

        assertThatThrownBy(() -> start(BASELINE, scripts, StorageContract.CLOCK))
            .cause().isInstanceOf(StorageException.class)
            .hasMessageStartingWith("db/migration/sqlite/V1__alpha.sql cannot be split into statements: ");

        assertUnchanged();
    }

    @Test
    void aSecondStartAfterTheTakeoverChangesNothing() throws Exception {
        sql("CREATE TABLE " + LEGACY + " (version INTEGER NOT NULL)",
            "INSERT INTO " + LEGACY + " VALUES (2)",
            "CREATE TABLE alpha (id INTEGER PRIMARY KEY)",
            "CREATE TABLE beta (id INTEGER PRIMARY KEY)");
        start(BASELINE, SCRIPTS, StorageContract.CLOCK);
        int lines = log.lines().size();

        start(BASELINE, SCRIPTS, LATER);

        try (Connection connection = connect()) {
            assertThat(tables(connection)).containsExactly("alpha", "beta", "gamma", LEGACY + "_baseline",
                "schema_version");
            for (Migration migration : MIGRATIONS) {
                assertThat(ProbeSchema.appliedAt(connection, "", migration.version()))
                    .isEqualTo(StorageContract.NOW.toEpochMilli());
            }
        }
        assertThat(backups()).hasSize(1);
        assertThat(log.lines()).hasSize(lines);
    }

    @Test
    void aBaselineIsIgnoredOnceTheVersionTableOfStorageExists() throws Exception {
        start(null, SCRIPTS, StorageContract.CLOCK);
        sql("CREATE TABLE " + LEGACY + " (version INTEGER NOT NULL)",
            "INSERT INTO " + LEGACY + " VALUES (9)");

        start(BASELINE, SCRIPTS, LATER);

        try (Connection connection = connect()) {
            assertThat(tables(connection)).containsExactly("alpha", "beta", "gamma", LEGACY, "schema_version");
            assertThat(ProbeSchema.ids(connection, LEGACY, "version")).containsExactly(9);
        }
    }

    @Test
    void withNoOldTableTheDatabaseIsNew() throws Exception {
        start(BASELINE, SCRIPTS, StorageContract.CLOCK);

        try (Connection connection = connect()) {
            assertThat(tables(connection)).containsExactly("alpha", "beta", "gamma", "schema_version");
        }
        assertThat(backups()).isEmpty();
        assertThat(log.lines()).containsExactly("INFO Applied database migration V1__alpha.",
            "INFO Applied database migration V2__beta.", "INFO Applied database migration V3__gamma.");
    }

    @Test
    void anOldTableWithNoVersionIsTakenOverAtVersionZero() throws Exception {
        sql("CREATE TABLE " + LEGACY + " (version INTEGER)");

        start(BASELINE, SCRIPTS, StorageContract.CLOCK);

        try (Connection connection = connect()) {
            assertThat(tables(connection)).containsExactly("alpha", "beta", "gamma", LEGACY + "_baseline",
                "schema_version");
            assertThat(ProbeSchema.versions(connection, "")).containsExactly(1, 2, 3);
        }
        assertThat(log.lines()).contains(
            "INFO Took over schema version 0 from " + LEGACY + ", now renamed to " + LEGACY + "_baseline.",
            "INFO Applied database migration V1__alpha.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.1", "-1", "two", "+1", "١", "", "2147483648", "99999999999",
        "9999999999999999999"})
    void aVersionThatIsNotAWholeNumberInRangeStopsTheStart(String version) throws Exception {
        sql("CREATE TABLE " + LEGACY + " (version TEXT NOT NULL)",
            "INSERT INTO " + LEGACY + " VALUES ('" + version + "')");

        assertThatThrownBy(() -> start(BASELINE, SCRIPTS, StorageContract.CLOCK))
            .cause().isInstanceOf(StorageException.class)
            .hasMessage("the table " + LEGACY + " holds the version \"" + version + "\", which is not a whole number"
                + " from 0 to 2147483647, so storage cannot read the schema version from it");

        assertUnchanged();
    }

    @Test
    void aVersionWithSurroundingSpacesIsRead() throws Exception {
        sql("CREATE TABLE " + LEGACY + " (version TEXT NOT NULL)",
            "INSERT INTO " + LEGACY + " VALUES (' 1 ')",
            "CREATE TABLE alpha (id INTEGER PRIMARY KEY)");

        start(BASELINE, SCRIPTS, StorageContract.CLOCK);

        try (Connection connection = connect()) {
            assertThat(ProbeSchema.versions(connection, "")).containsExactly(1, 2, 3);
        }
    }

    @Test
    void anOldTableWithNoVersionColumnStopsTheStart() throws Exception {
        sql("CREATE TABLE " + LEGACY + " (installed_rank INTEGER NOT NULL)",
            "INSERT INTO " + LEGACY + " VALUES (1)");

        assertThatThrownBy(() -> start(BASELINE, SCRIPTS, StorageContract.CLOCK))
            .cause().isInstanceOf(StorageException.class)
            .hasMessage("the table " + LEGACY + " has no version column, so storage cannot read the schema version"
                + " from it")
            .cause().isInstanceOf(SQLException.class);

        assertUnchanged();
    }

    @Test
    void aFailedRenameRollsTheWholeTakeoverBackAndTheNextStartCompletesIt() throws Exception {
        sql("CREATE TABLE " + LEGACY + " (version INTEGER NOT NULL)",
            "INSERT INTO " + LEGACY + " VALUES (2)",
            "CREATE TABLE alpha (id INTEGER PRIMARY KEY)",
            "CREATE TABLE beta (id INTEGER PRIMARY KEY)");
        FaultyDataSource faulty = new FaultyDataSource("jdbc:sqlite:" + folder.resolve("data.db"), new Properties());
        faulty.faults().failMatching("RENAME TO", 1);
        Migrator migrator = new Migrator(Dialect.SQLITE, "", StorageContract.CLOCK, log.logger, MIGRATIONS,
            BaselineTest::script, BASELINE);

        try (Connection failing = faulty.getConnection()) {
            assertThatThrownBy(() -> migrator.migrate(failing, null))
                .isInstanceOf(SQLException.class).hasMessage("injected failure of RENAME TO");
            assertThat(failing.getAutoCommit()).isTrue();
        }
        try (Connection connection = connect()) {
            assertThat(tables(connection)).containsExactly("alpha", "beta", LEGACY);

            migrator.migrate(connection, null);

            assertThat(tables(connection)).containsExactly("alpha", "beta", "gamma", LEGACY + "_baseline",
                "schema_version");
            assertThat(ProbeSchema.versions(connection, "")).containsExactly(1, 2, 3);
        }
    }

    @Test
    void aBaselineNamesAPlainLowercaseTable() {
        assertThat(Baseline.versionTable("_old_version_9").table()).isEqualTo("_old_version_9");
        assertThat(Baseline.versionTable("a".repeat(32)).table()).hasSize(32);
        assertThatThrownBy(() -> Baseline.versionTable(null))
            .isInstanceOf(NullPointerException.class).hasMessage("table");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Legacy", "9version", "old-version", "old version", "old;drop",
        "abcdefghijklmnopqrstuvwxyz0123456"})
    void aBaselineRefusesANameThatIsNotAPlainLowercaseTable(String table) {
        assertThatThrownBy(() -> Baseline.versionTable(table))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the version table is \"" + table + "\"; use 1 to 32 lowercase letters, digits and"
                + " underscores, starting with a letter or underscore");
    }

    @Test
    void anOldTableDeclaredInAnotherCaseOfTheVersionTablesNameIsTakenOverWhenTheBaselineAsksForIt() throws Exception {
        sql("CREATE TABLE Schema_Version (version INTEGER NOT NULL)",
            "INSERT INTO Schema_Version VALUES (2)",
            "CREATE TABLE alpha (id INTEGER PRIMARY KEY)",
            "INSERT INTO alpha VALUES (7)",
            "CREATE TABLE beta (id INTEGER PRIMARY KEY)");
        Baseline baseline = Baseline.versionTable("schema_version").takeOverSameName().checkSchema();

        start(baseline, SCRIPTS, StorageContract.CLOCK);
        start(baseline, SCRIPTS, LATER);

        try (Connection connection = connect()) {
            assertThat(tables(connection)).containsExactly("alpha", "beta", "gamma", "schema_version",
                "schema_version_baseline");
            assertThat(ProbeSchema.versions(connection, "")).containsExactly(1, 2, 3);
            assertThat(ProbeSchema.appliedAt(connection, "", 3)).isEqualTo(StorageContract.NOW.toEpochMilli());
            assertThat(ProbeSchema.ids(connection, "alpha", "id")).containsExactly(7);
            assertThat(ProbeSchema.ids(connection, "schema_version_baseline", "version")).containsExactly(2);
        }
        assertThat(backups()).hasSize(1);
        assertThat(log.lines()).containsExactly(
            "INFO Copied the database to backups/data-9.9.9-20261102-101530.db before upgrading its layout.",
            "INFO Took over schema version 2 from Schema_Version, now renamed to schema_version_baseline.",
            "INFO Applied database migration V3__gamma.");
    }

    @Test
    void aSameNameTakeoverOfATableWithNoVersionColumnStopsTheStartAndLeavesTheTable() throws Exception {
        sql("CREATE TABLE schema_version (applied INTEGER NOT NULL)", "INSERT INTO schema_version VALUES (2)");

        assertThatThrownBy(() -> start(Baseline.versionTable("schema_version").takeOverSameName(), SCRIPTS,
            StorageContract.CLOCK))
            .isInstanceOf(CompletionException.class)
            .cause().isInstanceOf(StorageException.class)
            .hasMessage("the table schema_version has no version column, so storage cannot read the schema version"
                + " from it");

        try (Connection connection = connect()) {
            assertThat(tables(connection)).containsExactly("schema_version");
            assertThat(ProbeSchema.ids(connection, "schema_version", "applied")).containsExactly(2);
        }
        assertThat(backups()).isEmpty();
    }

    @Test
    void aBaselineForAnotherTableDoesNotTakeOverATableNamedAsTheVersionTable() throws Exception {
        sql("CREATE TABLE schema_version (version INTEGER NOT NULL)", "INSERT INTO schema_version VALUES (2)");

        assertThatThrownBy(() -> start(BASELINE.takeOverSameName(), SCRIPTS, StorageContract.CLOCK))
            .isInstanceOf(CompletionException.class)
            .cause().isInstanceOf(StorageException.class)
            .hasMessageStartingWith("the database holds a table named schema_version that storage did not create");

        try (Connection connection = connect()) {
            assertThat(tables(connection)).containsExactly("schema_version");
        }
    }

    @Test
    void aSchemaCheckPassesADatabaseThatHoldsMoreThanTheScriptsCreate() throws Exception {
        sql("CREATE TABLE " + LEGACY + " (version INTEGER NOT NULL)",
            "INSERT INTO " + LEGACY + " VALUES (2)",
            "CREATE TABLE Alpha (ID INTEGER PRIMARY KEY, note TEXT)",
            "CREATE TABLE beta (id INTEGER PRIMARY KEY)",
            "CREATE TABLE owner_notes (id INTEGER)");

        start(BASELINE.checkSchema(), SCRIPTS, StorageContract.CLOCK);

        try (Connection connection = connect()) {
            assertThat(ProbeSchema.versions(connection, "")).containsExactly(1, 2, 3);
        }
    }

    @Test
    void aSchemaCheckNamesWhatTheDatabaseLacksBeforeTheBackupAndTheTakeover() throws Exception {
        sql("CREATE TABLE " + LEGACY + " (version INTEGER NOT NULL)",
            "INSERT INTO " + LEGACY + " VALUES (2)",
            "CREATE TABLE alpha (other INTEGER PRIMARY KEY)");

        assertThatThrownBy(() -> start(BASELINE.checkSchema(), SCRIPTS, StorageContract.CLOCK))
            .isInstanceOf(CompletionException.class)
            .cause().isInstanceOf(StorageException.class)
            .hasMessage("the table " + LEGACY + " records schema version 2, but the database lacks alpha.id, beta,"
                + " which the migrations up to that version create; restore a backup of the database, or install"
                + " the plugin version that set it up and let it finish its own upgrade");

        try (Connection connection = connect()) {
            assertThat(tables(connection)).containsExactly("alpha", LEGACY);
        }
        assertThat(backups()).isEmpty();
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void startRefusesANullBaseline() {
        assertThatThrownBy(() -> Storage.start(TestPlugins.plugin(NAME, folder, log.logger), new ErrorReports(),
            SqliteStartTest.settings(5), MIGRATIONS, (Baseline) null, StorageContract.CLOCK))
            .isInstanceOf(NullPointerException.class).hasMessage("baseline");
    }

    private void start(@Nullable Baseline baseline, Map<String, String> scripts, Clock clock) {
        start(baseline, scripts, clock, MIGRATIONS);
    }

    private void start(@Nullable Baseline baseline, Map<String, String> scripts, Clock clock,
                       List<Migration> migrations) {
        Storage storage = TestPlugins.start(TestPlugins.plugin(NAME, folder, log.logger, path -> source(scripts, path)),
            SqliteStartTest.settings(5), migrations, baseline, clock).join();
        assertThat(storage.close(Duration.ofSeconds(5))).isTrue();
    }

    private void assertUnchanged() throws Exception {
        try (Connection connection = connect()) {
            assertThat(tables(connection)).containsExactly(LEGACY);
        }
        assertThat(backups()).isEmpty();
        assertThat(log.lines()).isEmpty();
    }

    private void sql(String... statements) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("data.db"));
    }

    private List<Path> backups() throws Exception {
        Path backups = folder.resolve("backups");
        if (!Files.isDirectory(backups)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(backups)) {
            return files.toList();
        }
    }

    private static List<String> tables(Connection connection) throws SQLException {
        return ProbeSchema.tables(connection, Dialect.SQLITE, "");
    }

    private static @Nullable InputStream script(String path) {
        return source(SCRIPTS, path);
    }

    private static @Nullable InputStream source(Map<String, String> scripts, String path) {
        String script = scripts.get(path);
        return script == null ? null : new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8));
    }
}
