package so.alaz.taffy.storage;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import so.alaz.taffy.test.RaceRunner;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

abstract class StorageContract {

    static final Instant NOW = Instant.parse("2026-11-02T10:15:30.123Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final List<String> COLUMNS = List.of("id", "state", "amount", "at", "doc", "data");

    abstract Storage storage();

    abstract String name();

    abstract String createProbe(String table);

    abstract Dialect dialect();

    /**
     * Starts another storage on the database of {@link #storage()}, with the prefix where the dialect has one, taking
     * over the old version table of {@code baseline} when it is not {@code null}.
     */
    abstract CompletableFuture<Storage> starting(String prefix, List<Migration> migrations,
                                                 Map<String, String> scripts, @Nullable Baseline baseline);

    /** Starts another storage on the database of {@link #storage()}, with no baseline. */
    CompletableFuture<Storage> starting(String prefix, List<Migration> migrations, Map<String, String> scripts) {
        return starting(prefix, migrations, scripts, null);
    }

    /**
     * Starts another storage on the database of {@link #storage()} for the plugin named {@code plugin}, with the
     * prefix where the dialect has one. The version table is {@code versionTable}, or has its default name when
     * {@code versionTable} is {@code null}.
     */
    abstract CompletableFuture<Storage> startingAs(String plugin, String prefix, @Nullable String versionTable,
                                                   List<Migration> migrations, Map<String, String> scripts,
                                                   @Nullable Baseline baseline);

    /** The default name of the version table of the plugin {@link #name()} under {@code prefix}. */
    String defaultVersionTable(String prefix) {
        return dialect() == Dialect.SQLITE ? "schema_version" : prefix + name().replace('-', '_') + "_schema_version";
    }

    /** A connection of its own to the database of {@link #storage()}. */
    abstract Connection connect() throws SQLException;

    /** Checks that no start of this test wrote a backup of the database. */
    void assertNoBackup() {
    }

    String probe() {
        return storage().table("probe");
    }

    @BeforeEach
    void freshProbeTable() {
        storage().async(tx -> {
            tx.execute("DROP TABLE IF EXISTS " + probe());
            tx.execute(createProbe(probe()));
            return null;
        }).join();
    }

    @Test
    void everyBoundTypeRoundTripsExactly() {
        UUID id = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("922337203685477.5807");
        Instant at = Instant.ofEpochMilli(1_798_000_000_123L);
        byte[] data = {0, 1, 2, (byte) 0xFF};

        insert(id, "A", amount, at, "{\"a\":1}", data);
        Object[] row = storage().read(tx -> tx.queryOne("SELECT * FROM " + probe() + " WHERE id = ?",
            params -> params.uuid(id),
            found -> new Object[] {found.uuid("id"), found.text("state"), found.money("amount"), found.time("at"),
                found.json("doc"), found.bytes("data")}).orElseThrow()).join();

        assertThat(row[0]).isEqualTo(id);
        assertThat(row[1]).isEqualTo("A");
        assertThat((BigDecimal) row[2]).isEqualByComparingTo(amount);
        assertThat(row[3]).isEqualTo(at);
        assertThat(row[4].toString().replace(" ", "")).isEqualTo("{\"a\":1}");
        assertThat((byte[]) row[5]).containsExactly(data);
    }

    @Test
    void nullsRoundTripAsNull() {
        UUID id = UUID.randomUUID();
        insert(id, "A", null, null, null, null);

        List<Object> values = storage().read(tx -> tx.queryOne("SELECT * FROM " + probe() + " WHERE id = ?",
            params -> params.uuid(id),
            found -> java.util.Arrays.<Object>asList(found.money("amount"), found.time("at"), found.json("doc"),
                found.bytes("data"), found.integer("amount"))).orElseThrow()).join();

        assertThat(values).containsOnlyNulls();
    }

    @Test
    void moneySortsInNumericOrder() {
        for (String amount : List.of("10", "9.5", "100", "0", "0.0001")) {
            insert(UUID.randomUUID(), "A", new BigDecimal(amount), NOW, null, null);
        }

        List<BigDecimal> sorted = storage().read(tx -> tx.query(
            "SELECT amount FROM " + probe() + " ORDER BY amount", Tx.Binder.NONE, row -> row.money("amount"))).join();

        assertThat(sorted).extracting(BigDecimal::toPlainString)
            .containsExactly("0.0000", "0.0001", "9.5000", "10.0000", "100.0000");
    }

    @Test
    void aNegativeAmountIsRefusedAndANegativeBalanceIsKept() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> insert(UUID.randomUUID(), "A", new BigDecimal("-1"), NOW, null, null))
            .hasRootCauseInstanceOf(MoneyOutOfRangeException.class);
        storage().async(tx -> tx.update("INSERT INTO " + probe() + " (id, state, amount) VALUES (?, ?, ?)",
            params -> params.uuid(id).text("A").moneyOrNull(new BigDecimal("-5.5")))).join();

        assertThat(storage().read(tx -> tx.queryOne("SELECT amount FROM " + probe(), Tx.Binder.NONE,
            row -> row.money("amount")).orElseThrow()).join()).isEqualByComparingTo("-5.5");
    }

    @Test
    void anOverCapBalanceStoresNullAndAnOverCapAmountIsRefused() {
        UUID id = UUID.randomUUID();
        BigDecimal over = new BigDecimal("1e20");

        storage().async(tx -> tx.update(
            "INSERT INTO " + probe() + " (id, state, amount) VALUES (?, ?, ?)",
            params -> params.uuid(id).text("A").moneyOrNull(over))).join();
        List<BigDecimal> stored = storage().read(tx -> tx.query("SELECT amount FROM " + probe(), Tx.Binder.NONE,
            row -> row.money("amount"))).join();

        assertThat(stored).containsExactly((BigDecimal) null);
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "A", over, NOW, null, null))
            .hasRootCauseInstanceOf(MoneyOutOfRangeException.class);
    }

    @Test
    void anOverCapSignedBalanceIsRefusedAndOneInsideTheCapIsKept() {
        UUID id = UUID.randomUUID();
        String sql = "INSERT INTO " + probe() + " (id, state, amount) VALUES (?, ?, ?)";

        assertThatThrownBy(() -> storage().async(tx -> tx.update(sql,
            params -> params.uuid(UUID.randomUUID()).text("A").signedMoney(new BigDecimal("-1e20")))).join())
            .hasRootCauseInstanceOf(MoneyOutOfRangeException.class);
        storage().async(tx -> tx.update(sql,
            params -> params.uuid(id).text("A").signedMoney(new BigDecimal("-5.5")))).join();

        assertThat(storage().read(tx -> tx.query("SELECT amount FROM " + probe(), Tx.Binder.NONE,
            row -> row.money("amount"))).join())
            .singleElement().satisfies(amount -> assertThat(amount).isEqualByComparingTo("-5.5"));
    }

    @Test
    void aDecimalBeyondTheMoneyCapRoundTrips() {
        String sql = "INSERT INTO " + probe() + " (id, state, wide) VALUES (?, ?, ?)";
        List<String> values = List.of("123456789012345678901234567890.1234", "-1000000000000000.5", "0");
        for (int index = 0; index < values.size(); index++) {
            String label = Integer.toString(index);
            BigDecimal value = new BigDecimal(values.get(index));
            storage().async(tx -> tx.update(sql,
                params -> params.uuid(UUID.randomUUID()).text(label).decimal(value))).join();
        }
        UUID empty = UUID.randomUUID();
        storage().async(tx -> tx.update(sql, params -> params.uuid(empty).text("A").decimal(null))).join();

        List<Object[]> stored = storage().read(tx -> tx.query(
            "SELECT state, wide FROM " + probe() + " WHERE id <> ?", params -> params.uuid(empty),
            row -> new Object[] {row.text("state"), row.decimal("wide")})).join();
        List<BigDecimal> missing = storage().read(tx -> tx.query("SELECT wide FROM " + probe() + " WHERE id = ?",
            params -> params.uuid(empty), row -> row.decimal("wide"))).join();

        assertThat(stored).hasSize(3)
            .allSatisfy(row -> assertThat((BigDecimal) row[1])
                .isEqualByComparingTo(values.get(Integer.parseInt((String) row[0]))));
        assertThat(missing).containsExactly((BigDecimal) null);
    }

    @Test
    void insertOrIgnoreCountsANewRowOnceAndNeverThrows() {
        UUID id = UUID.randomUUID();
        String sql = storage().dialect().insertOrIgnore(probe(), List.of("id", "state"), List.of("id"));

        int first = storage().async(tx -> tx.update(sql, params -> params.uuid(id).text("A"))).join();
        int second = storage().async(tx -> tx.update(sql, params -> params.uuid(id).text("B"))).join();

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(state(id)).isEqualTo("A");
    }

    @Test
    void aConditionalUpdateHasExactlyOneWinner() {
        UUID id = UUID.randomUUID();
        insert(id, "ACTIVE", BigDecimal.ONE, NOW, null, null);
        String claim = "UPDATE " + probe() + " SET state = ? WHERE id = ? AND state = ?";

        int winner = storage().async(tx -> tx.update(claim, params -> params.text("PENDING").uuid(id).text("ACTIVE")))
            .join();
        int loser = storage().async(tx -> tx.update(claim, params -> params.text("PENDING").uuid(id).text("ACTIVE")))
            .join();

        assertThat(winner).isEqualTo(1);
        assertThat(loser).isZero();
        assertThat(state(id)).isEqualTo("PENDING");
    }

    @Test
    void aRacedConditionalUpdateHasExactlyOneWinner() throws Exception {
        UUID id = UUID.randomUUID();
        insert(id, "ACTIVE", BigDecimal.ONE, NOW, null, null);
        String claim = "UPDATE " + probe() + " SET state = ? WHERE id = ? AND state = ?";

        List<Integer> changed = RaceRunner.race(16, contender -> storage()
            .async(tx -> tx.update(claim, params -> params.text("PENDING").uuid(id).text("ACTIVE"))).join());

        assertThat(changed).filteredOn(count -> count == 1).hasSize(1);
        assertThat(changed).filteredOn(count -> count == 0).hasSize(15);
        assertThat(state(id)).isEqualTo("PENDING");
    }

    @Test
    void upsertInsertsThenUpdates() {
        UUID id = UUID.randomUUID();
        String sql = storage().dialect().upsert(probe(), List.of("id", "state"), List.of("id"), List.of("state"));

        storage().async(tx -> tx.update(sql, params -> params.uuid(id).text("A"))).join();
        storage().async(tx -> tx.update(sql, params -> params.uuid(id).text("B"))).join();

        assertThat(state(id)).isEqualTo("B");
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void aFailedTransactionLeavesNothingBehind() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> storage().async(tx -> {
            tx.update("INSERT INTO " + probe() + " (id, state) VALUES (?, ?)", params -> params.uuid(id).text("A"));
            throw new SQLException("boom");
        }).join()).isInstanceOf(CompletionException.class).hasRootCauseMessage("boom");

        assertThat(count()).isZero();
    }

    @Test
    void workRunsOnADatabaseThreadWithTheApplicationClock() {
        String thread = storage().async(tx -> Thread.currentThread().getName()).join();
        Instant now = storage().async(Tx::now).join();

        assertThat(thread).startsWith(name() + "-db-");
        assertThat(now).isEqualTo(NOW);
    }

    @Test
    void aTransactionCannotBeUsedFromAnotherThreadOrAfterItEnds() {
        AtomicReference<Throwable> crossThread = new AtomicReference<>();
        Tx leaked = storage().async(tx -> {
            Thread other = new Thread(() -> {
                try {
                    tx.execute("SELECT 1");
                } catch (Throwable failure) {
                    crossThread.set(failure);
                }
            });
            other.start();
            try {
                other.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return tx;
        }).join();

        assertThat(crossThread.get()).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("only on the database thread");
        assertThatThrownBy(() -> leaked.execute("SELECT 1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("this transaction has ended");
    }

    @Test
    void aVersionTableInAnotherLayoutStopsTheStartAndStaysAsItWas() throws Exception {
        String prefix = "alien_";
        String versionTable = (dialect() == Dialect.SQLITE ? "" : prefix) + "schema_version";
        Migration first = new Migration(1, "alpha");
        Map<String, String> scripts = Map.of(first.resource(dialect()), "CREATE TABLE ${prefix}alpha (id INTEGER);\n");
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try {
                statement.execute("CREATE TABLE " + versionTable + " (version INTEGER NOT NULL)");
                statement.execute("INSERT INTO " + versionTable + " VALUES (7)");
                List<String> before = ProbeSchema.tables(connection, dialect(), "");

                assertThatThrownBy(() -> starting(prefix, List.of(first), scripts).join())
                    .isInstanceOf(CompletionException.class)
                    .cause().isInstanceOf(StorageException.class)
                    .hasMessage("the database holds a table named " + versionTable + " that storage did not create,"
                        + " so it cannot record migrations there; rename or remove that table, or on MySQL, MariaDB"
                        + " and PostgreSQL set another table prefix");

                assertThat(ProbeSchema.tables(connection, dialect(), "")).isEqualTo(before);
                assertThat(columns(connection, versionTable)).containsExactly("version");
                assertThat(ProbeSchema.ids(connection, versionTable, "version")).containsExactly(7);
                assertNoBackup();
            } finally {
                statement.execute("DROP TABLE IF EXISTS " + versionTable);
            }
        }
    }

    @Test
    void aMigrationThatOnlySelectsAndOneShapedForTheDialectAreAppliedAndRecorded() throws Exception {
        String prefix = dialect() == Dialect.SQLITE ? "" : "shape_";
        Migration noop = new Migration(1, "noop");
        Migration shaped = new Migration(2, "shaped");
        String shape = switch (dialect()) {
            case SQLITE -> """
                CREATE TABLE ${prefix}shaped_new (id INTEGER PRIMARY KEY, note TEXT);
                ALTER TABLE ${prefix}shaped_new RENAME TO ${prefix}shaped;
                """;
            case MYSQL, MARIADB -> """
                CREATE TABLE IF NOT EXISTS ${prefix}shaped (id INT NOT NULL PRIMARY KEY, note TEXT) ENGINE = InnoDB;
                """;
            case POSTGRES -> """
                CREATE TABLE ${prefix}shaped (id INTEGER NOT NULL PRIMARY KEY);
                ALTER TABLE ${prefix}shaped ADD COLUMN note TEXT;
                SELECT 1;
                """;
        };
        int statements = switch (dialect()) {
            case SQLITE -> 2;
            case MYSQL, MARIADB -> 1;
            case POSTGRES -> 3;
        };
        Map<String, String> scripts = Map.of(noop.resource(dialect()), "SELECT 1;\n",
            shaped.resource(dialect()), shape);
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try {
                starting(prefix, List.of(noop, shaped), scripts).join().close(Duration.ofSeconds(5));
                starting(prefix, List.of(noop, shaped), scripts).join().close(Duration.ofSeconds(5));

                assertThat(ProbeSchema.versions(connection, prefix)).containsExactly(1, 2);
                assertThat(checksum(connection, prefix, 1)).isEqualTo(Migrator.checksum("SELECT 1;\n"));
                assertThat(ProbeSchema.statementsDone(connection, prefix, 1)).isEqualTo(1);
                assertThat(ProbeSchema.appliedAt(connection, prefix, 1)).isEqualTo(NOW.toEpochMilli());
                assertThat(checksum(connection, prefix, 2)).isEqualTo(Migrator.checksum(shape));
                assertThat(ProbeSchema.statementsDone(connection, prefix, 2)).isEqualTo(statements);
                assertThat(columns(connection, prefix + "shaped")).containsExactly("id", "note");
            } finally {
                for (String table : List.of(prefix + "shaped", prefix + "shaped_new", prefix + "schema_version")) {
                    statement.execute("DROP TABLE IF EXISTS " + table);
                }
            }
        }
    }

    @Test
    void aBaselineTakesOverAMultiRowVersionTableOnce() throws Exception {
        String prefix = dialect() == Dialect.SQLITE ? "" : "taken_";
        String legacy = "legacy_schema_version";
        Baseline baseline = Baseline.versionTable(legacy);
        List<Migration> migrations =
            List.of(new Migration(1, "alpha"), new Migration(2, "beta"), new Migration(3, "gamma"));
        Map<String, String> scripts = Map.of(
            migrations.get(0).resource(dialect()), "CREATE TABLE ${prefix}alpha (id INTEGER);\n",
            migrations.get(1).resource(dialect()), "CREATE TABLE ${prefix}beta (id INTEGER);\n",
            migrations.get(2).resource(dialect()), "CREATE TABLE ${prefix}gamma (id INTEGER);\n");
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try {
                statement.execute("CREATE TABLE " + legacy + " (version INTEGER NOT NULL, description VARCHAR(32))");
                statement.execute("INSERT INTO " + legacy + " VALUES (1, 'first')");
                statement.execute("INSERT INTO " + legacy + " VALUES (2, 'second')");
                statement.execute("CREATE TABLE " + prefix + "alpha (id INTEGER)");
                statement.execute("INSERT INTO " + prefix + "alpha VALUES (7)");
                statement.execute("CREATE TABLE " + prefix + "beta (id INTEGER)");

                starting(prefix, migrations, scripts, baseline).join().close(Duration.ofSeconds(5));
                starting(prefix, migrations, scripts, baseline).join().close(Duration.ofSeconds(5));

                assertThat(ProbeSchema.tables(connection, dialect(), ""))
                    .contains(legacy + "_baseline", prefix + "gamma", prefix + "schema_version")
                    .doesNotContain(legacy, legacy + "_baseline2", prefix + "schema_version_takeover");
                assertThat(ProbeSchema.versions(connection, prefix)).containsExactly(1, 2, 3);
                for (Migration migration : migrations) {
                    assertThat(checksum(connection, prefix, migration.version())).as("checksum of %s", migration.id())
                        .isEqualTo(Migrator.checksum(scripts.get(migration.resource(dialect()))));
                    assertThat(ProbeSchema.appliedAt(connection, prefix, migration.version())).isEqualTo(
                        NOW.toEpochMilli());
                }
                assertThat(ProbeSchema.ids(connection, legacy + "_baseline", "version")).containsExactly(1, 2);
                assertThat(ProbeSchema.ids(connection, prefix + "alpha", "id")).containsExactly(7);
            } finally {
                for (String table : List.of(legacy, legacy + "_baseline", prefix + "alpha", prefix + "beta",
                    prefix + "gamma", prefix + "schema_version", prefix + "schema_version_takeover")) {
                    statement.execute("DROP TABLE IF EXISTS " + table);
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(FirstRelease.Bytes.class)
    void aDatabaseTheFirstReleaseRecordedStartsOnABuildWithLfCrlfOrByteOrderMarkScripts(FirstRelease.Bytes build)
        throws Exception {
        String prefix = dialect() == Dialect.SQLITE ? "" : "first_";
        String earlier = prefix + "schema_version";
        String own = defaultVersionTable(prefix);
        Map<String, String> scripts = FirstRelease.scripts(dialect(), build);
        List<String> recorded = FirstRelease.rows(FirstRelease.Bytes.LF);
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try {
                FirstRelease.record(connection, dialect(), prefix, FirstRelease.Bytes.LF);

                startingAs(name(), prefix, null, FirstRelease.CURRENT, scripts, null).join()
                    .close(Duration.ofSeconds(5));
                startingAs(name(), prefix, null, FirstRelease.CURRENT, scripts, null).join()
                    .close(Duration.ofSeconds(5));

                assertThat(ProbeSchema.rows(connection, own)).containsExactly(recorded.get(0), recorded.get(1),
                    FirstRelease.notesRow(NOW.toEpochMilli()));
                assertThat(ProbeSchema.rows(connection, earlier)).startsWith(recorded.get(0), recorded.get(1))
                    .hasSize(own.equals(earlier) ? 3 : 2);
                assertThat(ProbeSchema.tables(connection, dialect(), prefix))
                    .containsAll(FirstRelease.tables(prefix)).doesNotContain(own + "_takeover");
                assertThat(ProbeSchema.rows(connection, prefix + "accounts")).containsExactly("1|seed", "2|steve");
                assertThat(ProbeSchema.rows(connection, prefix + "ledger")).containsExactly("10|2|first sale");
                assertThat(ProbeSchema.rows(connection, prefix + "notes")).isEmpty();
            } finally {
                dropFirstRelease(statement, prefix);
            }
        }
    }

    @Test
    void aDatabaseTheFirstReleaseRecordedFromCrlfScriptsStartsOnABuildThatShipsThoseBytes() throws Exception {
        startsOn(FirstRelease.Bytes.CRLF, FirstRelease.Bytes.CRLF);
    }

    @Test
    void aDatabaseTheFirstReleaseRecordedFromCrlfScriptsStartsOnABuildThatShipsLineFeeds() throws Exception {
        startsOn(FirstRelease.Bytes.CRLF, FirstRelease.Bytes.LF);
    }

    @Test
    void aDatabaseTheFirstReleaseRecordedFromCrlfScriptsIsCopiedByABuildThatShipsLineFeeds() throws Exception {
        String prefix = dialect() == Dialect.SQLITE ? "" : "crlf_";
        String own = defaultVersionTable(prefix);
        Map<String, String> scripts = FirstRelease.scripts(dialect(), FirstRelease.Bytes.LF);
        List<String> recorded = FirstRelease.rows(FirstRelease.Bytes.CRLF);
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try {
                FirstRelease.record(connection, dialect(), prefix, FirstRelease.Bytes.CRLF);

                startingAs(name(), prefix, null, FirstRelease.CURRENT, scripts, null).join()
                    .close(Duration.ofSeconds(5));

                assertThat(ProbeSchema.rows(connection, own)).containsExactly(recorded.get(0), recorded.get(1),
                    FirstRelease.notesRow(NOW.toEpochMilli()));
                assertThat(ProbeSchema.rows(connection, prefix + "accounts")).containsExactly("1|seed", "2|steve");
            } finally {
                dropFirstRelease(statement, prefix);
            }
        }
    }

    @Test
    void anEarlierVersionTableWhoseEveryScriptChangedRefusesTheStartAndRunsNothing() throws Exception {
        String prefix = dialect() == Dialect.SQLITE ? "" : "edited_";
        String earlier = prefix + "schema_version";
        String own = defaultVersionTable(prefix);
        Map<String, String> scripts = new HashMap<>(FirstRelease.scripts(dialect(), FirstRelease.Bytes.LF));
        scripts.replaceAll((path, script) -> script + "-- reviewed\n");
        List<String> recorded = FirstRelease.rows(FirstRelease.Bytes.LF);
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try {
                FirstRelease.record(connection, dialect(), prefix, FirstRelease.Bytes.LF);

                CompletableFuture<Storage> start = startingAs(name(), prefix, null, FirstRelease.CURRENT, scripts,
                    null);

                assertThatThrownBy(start::join).cause().isInstanceOf(StorageException.class)
                    .hasMessageContaining("changed after it ran on this database")
                    .hasMessageStartingWith(own.equals(earlier) ? "migration V1__accounts changed"
                        : "the table " + earlier + " records migration V1__accounts with another checksum");
                assertThat(ProbeSchema.rows(connection, earlier)).containsExactlyElementsOf(recorded);
                assertThat(ProbeSchema.rows(connection, prefix + "accounts")).containsExactly("1|seed", "2|steve");
                assertThat(ProbeSchema.tables(connection, dialect(), prefix)).doesNotContain(prefix + "notes")
                    .filteredOn(table -> table.endsWith("schema_version")).containsExactly(earlier);
            } finally {
                dropFirstRelease(statement, prefix);
            }
        }
    }

    /**
     * Starts the next build, which names the version table as 0.1.0 did and ships its scripts as {@code shipped}, on
     * a database 0.1.0 recorded from scripts shipped as {@code from}.
     */
    void startsOn(FirstRelease.Bytes from, FirstRelease.Bytes shipped) throws Exception {
        String prefix = dialect() == Dialect.SQLITE ? "" : "kept_";
        String versionTable = prefix + "schema_version";
        Map<String, String> scripts = FirstRelease.scripts(dialect(), shipped);
        List<String> recorded = FirstRelease.rows(from);
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try {
                FirstRelease.record(connection, dialect(), prefix, from);

                starting(prefix, FirstRelease.CURRENT, scripts).join().close(Duration.ofSeconds(5));
                starting(prefix, FirstRelease.CURRENT, scripts).join().close(Duration.ofSeconds(5));

                assertThat(recorded).doesNotContainAnyElementsOf(FirstRelease.rows(FirstRelease.Bytes.LF));
                assertThat(ProbeSchema.rows(connection, versionTable)).containsExactly(recorded.get(0),
                    recorded.get(1), FirstRelease.notesRow(NOW.toEpochMilli()));
                assertThat(ProbeSchema.tables(connection, dialect(), prefix)).containsAll(FirstRelease.tables(prefix));
                assertThat(ProbeSchema.rows(connection, prefix + "accounts")).containsExactly("1|seed", "2|steve");
                assertThat(ProbeSchema.rows(connection, prefix + "ledger")).containsExactly("10|2|first sale");
            } finally {
                dropFirstRelease(statement, prefix);
            }
        }
    }

    private void dropFirstRelease(Statement statement, String prefix) throws SQLException {
        List<String> tables = new ArrayList<>(FirstRelease.tables(prefix));
        tables.add(prefix + "schema_version");
        tables.add(defaultVersionTable(prefix));
        tables.add(defaultVersionTable(prefix) + "_takeover");
        for (String table : tables) {
            statement.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    @Test
    void aBaselineTakesOverTheDatabaseOfProVouchers() throws Exception {
        String old = ProVouchersDatabase.VERSION_TABLE;
        @Nullable String named = dialect() == Dialect.SQLITE ? null : "provouchers_migrations";
        String versionTable = named == null ? "schema_version" : named;
        Baseline baseline = Baseline.versionTable(old);
        List<Migration> migrations = ProVouchersDatabase.MIGRATIONS;
        Map<String, String> scripts = ProVouchersDatabase.scripts(dialect());
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try {
                ProVouchersDatabase.create(connection);
                assertThat(ProbeSchema.rows(connection, old)).containsExactly("5");
                Map<String, List<String>> before = new HashMap<>();
                for (String table : ProVouchersDatabase.TABLES) {
                    before.put(table, ProbeSchema.rows(connection, table));
                    assertThat(before.get(table)).as("rows of %s", table).isNotEmpty();
                }

                startingAs("ProVouchers", "", named, migrations, scripts, baseline).join()
                    .close(Duration.ofSeconds(5));
                startingAs("ProVouchers", "", named, migrations, scripts, baseline).join()
                    .close(Duration.ofSeconds(5));

                List<String> recorded = new ArrayList<>();
                for (Migration migration : migrations) {
                    int version = migration.version();
                    recorded.add(version + "|" + migration.name() + "|"
                        + FirstRelease.sha256(ProVouchersDatabase.script(version)) + "|"
                        + ProVouchersDatabase.statements(version) + "|" + NOW.toEpochMilli());
                }
                assertThat(ProbeSchema.rows(connection, versionTable)).containsExactlyElementsOf(recorded);
                assertThat(ProbeSchema.rows(connection, old + "_baseline")).containsExactly("5");
                List<String> tables = new ArrayList<>(ProVouchersDatabase.TABLES);
                tables.add(old + "_baseline");
                if (named != null) {
                    tables.add(named);
                }
                assertThat(ProbeSchema.tables(connection, dialect(), "provouchers_"))
                    .containsExactlyInAnyOrderElementsOf(tables);
                for (String table : ProVouchersDatabase.TABLES) {
                    assertThat(ProbeSchema.rows(connection, table)).as("rows of %s", table)
                        .isEqualTo(before.get(table));
                }
            } finally {
                ProbeSchema.drop(connection, dialect(), "provouchers_");
                statement.execute("DROP TABLE IF EXISTS " + versionTable);
            }
        }
    }

    @Test
    void aVersionTableNamedAsTheOldTableOfProVouchersStopsTheStartAndLeavesTheDatabaseAsItWas() throws Exception {
        String old = ProVouchersDatabase.VERSION_TABLE;
        @Nullable String named = dialect() == Dialect.SQLITE ? old : null;
        Baseline baseline = Baseline.versionTable(old);
        Map<String, String> scripts = ProVouchersDatabase.scripts(dialect());
        try (Connection connection = connect()) {
            try {
                ProVouchersDatabase.create(connection);
                List<String> tables = ProbeSchema.tables(connection, dialect(), "");
                Map<String, List<String>> before = new HashMap<>();
                for (String table : ProVouchersDatabase.TABLES) {
                    before.put(table, ProbeSchema.rows(connection, table));
                }

                assertThatThrownBy(() -> startingAs("ProVouchers", "", named, ProVouchersDatabase.MIGRATIONS, scripts,
                    baseline).join())
                    .isInstanceOf(CompletionException.class)
                    .cause().isInstanceOf(StorageException.class)
                    .hasMessage("the database holds a table named " + old + " that storage did not create, so it"
                        + " cannot record migrations there; rename or remove that table, or on MySQL, MariaDB and"
                        + " PostgreSQL set another table prefix");

                assertThat(ProbeSchema.tables(connection, dialect(), "")).isEqualTo(tables);
                assertThat(ProbeSchema.rows(connection, old)).containsExactly("5");
                for (String table : ProVouchersDatabase.TABLES) {
                    assertThat(ProbeSchema.rows(connection, table)).as("rows of %s", table)
                        .isEqualTo(before.get(table));
                }
                assertNoBackup();
            } finally {
                ProbeSchema.drop(connection, dialect(), "provouchers_");
            }
        }
    }

    @Test
    void aBaselineThatTakesOverTheSameNameAdoptsTheVersionTableOfProVouchers() throws Exception {
        String old = ProVouchersDatabase.VERSION_TABLE;
        @Nullable String named = dialect() == Dialect.SQLITE ? old : null;
        Baseline baseline = Baseline.versionTable(old).takeOverSameName().checkSchema();
        List<Migration> migrations = ProVouchersDatabase.MIGRATIONS;
        Map<String, String> scripts = ProVouchersDatabase.scripts(dialect());
        try (Connection connection = connect()) {
            try {
                ProVouchersDatabase.create(connection);
                Map<String, List<String>> before = new HashMap<>();
                for (String table : ProVouchersDatabase.TABLES) {
                    before.put(table, ProbeSchema.rows(connection, table));
                }

                startingAs("ProVouchers", "", named, migrations, scripts, baseline).join()
                    .close(Duration.ofSeconds(5));
                startingAs("ProVouchers", "", named, migrations, scripts, baseline).join()
                    .close(Duration.ofSeconds(5));

                List<String> recorded = new ArrayList<>();
                for (Migration migration : migrations) {
                    int version = migration.version();
                    recorded.add(version + "|" + migration.name() + "|"
                        + FirstRelease.sha256(ProVouchersDatabase.script(version)) + "|"
                        + ProVouchersDatabase.statements(version) + "|" + NOW.toEpochMilli());
                }
                assertThat(ProbeSchema.rows(connection, old)).containsExactlyElementsOf(recorded);
                assertThat(ProbeSchema.rows(connection, old + "_baseline")).containsExactly("5");
                List<String> tables = new ArrayList<>(ProVouchersDatabase.TABLES);
                tables.add(old);
                tables.add(old + "_baseline");
                assertThat(ProbeSchema.tables(connection, dialect(), "provouchers_"))
                    .containsExactlyInAnyOrderElementsOf(tables);
                for (String table : ProVouchersDatabase.TABLES) {
                    assertThat(ProbeSchema.rows(connection, table)).as("rows of %s", table)
                        .isEqualTo(before.get(table));
                }
            } finally {
                ProbeSchema.drop(connection, dialect(), "provouchers_");
            }
        }
    }

    @Test
    void aSameNameTakeoverOfAVersionNoMigrationHasLeavesTheDatabaseAsItWas() throws Exception {
        String old = ProVouchersDatabase.VERSION_TABLE;
        @Nullable String named = dialect() == Dialect.SQLITE ? old : null;
        Baseline baseline = Baseline.versionTable(old).takeOverSameName();
        Map<String, String> scripts = ProVouchersDatabase.scripts(dialect());
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try {
                ProVouchersDatabase.create(connection);
                statement.execute("UPDATE " + old + " SET version = 9");
                List<String> tables = ProbeSchema.tables(connection, dialect(), "");

                assertThatThrownBy(() -> startingAs("ProVouchers", "", named, ProVouchersDatabase.MIGRATIONS, scripts,
                    baseline).join())
                    .isInstanceOf(CompletionException.class)
                    .cause().isInstanceOf(StorageException.class)
                    .hasMessage("the table " + old + " records schema version 9, but this version of the plugin"
                        + " knows schema versions up to 5; install the plugin version that set up the database, or"
                        + " restore a backup of the database");

                assertThat(ProbeSchema.tables(connection, dialect(), "")).isEqualTo(tables);
                assertThat(columns(connection, old)).containsExactly("version");
                assertThat(ProbeSchema.rows(connection, old)).containsExactly("9");
                assertNoBackup();
            } finally {
                ProbeSchema.drop(connection, dialect(), "provouchers_");
            }
        }
    }

    @Test
    void aBaselineThatChecksTheSchemaStopsTheStartOnADatabaseBehindItsRecordedVersion() throws Exception {
        String old = ProVouchersDatabase.VERSION_TABLE;
        @Nullable String named = dialect() == Dialect.SQLITE ? null : "provouchers_migrations";
        Baseline baseline = Baseline.versionTable(old).checkSchema();
        Map<String, String> scripts = ProVouchersDatabase.scripts(dialect());
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try {
                ProVouchersDatabase.create(connection);
                statement.execute("DROP TABLE provouchers_use_totals");
                statement.execute("ALTER TABLE provouchers_stash DROP COLUMN arg");
                List<String> tables = ProbeSchema.tables(connection, dialect(), "");

                assertThatThrownBy(() -> startingAs("ProVouchers", "", named, ProVouchersDatabase.MIGRATIONS, scripts,
                    baseline).join())
                    .isInstanceOf(CompletionException.class)
                    .cause().isInstanceOf(StorageException.class)
                    .hasMessage("the table " + old + " records schema version 5, but the database lacks"
                        + " provouchers_stash.arg, provouchers_use_totals, which the migrations up to that version"
                        + " create; restore a backup of the database, or install the plugin version that set it up"
                        + " and let it finish its own upgrade");

                assertThat(ProbeSchema.tables(connection, dialect(), "")).isEqualTo(tables);
                assertThat(ProbeSchema.rows(connection, old)).containsExactly("5");
                assertNoBackup();
            } finally {
                ProbeSchema.drop(connection, dialect(), "provouchers_");
            }
        }
    }

    @Test
    void intsAndBooleansRoundTripAndNullsReadAsNull() {
        String flags = storage().table("flags");
        storage().async(tx -> {
            tx.execute("DROP TABLE IF EXISTS " + flags);
            tx.execute("CREATE TABLE " + flags + " (id INTEGER NOT NULL PRIMARY KEY, enabled BOOLEAN, level INTEGER,"
                + " big BIGINT)");
            return null;
        }).join();
        try {
            String insert = "INSERT INTO " + flags + " (id, enabled, level, big) VALUES (?, ?, ?, ?)";
            storage().async(tx -> {
                tx.update(insert, params -> params.intValue(1).bool(true).intValue(Integer.MIN_VALUE)
                    .integer(Integer.MAX_VALUE + 1L));
                tx.update(insert, params -> params.intValue(2).bool(Boolean.FALSE)
                    .intValue(Integer.valueOf(Integer.MAX_VALUE)).integer(7));
                tx.update(insert, params -> params.intValue(3).bool((Boolean) null).intValue((Integer) null)
                    .integer((Long) null));
                return null;
            }).join();

            List<List<Object>> rows = storage().read(tx -> tx.query(
                "SELECT id, enabled, level FROM " + flags + " ORDER BY id", Tx.Binder.NONE,
                row -> java.util.Arrays.<Object>asList(row.intValue("id"), row.bool("enabled"),
                    row.intValue("level")))).join();
            List<Integer> enabled = storage().read(tx -> tx.query(
                "SELECT id FROM " + flags + " WHERE enabled = ?", params -> params.bool(true),
                row -> row.intValue("id"))).join();

            assertThat(rows).containsExactly(
                java.util.Arrays.<Object>asList(1, true, Integer.MIN_VALUE),
                java.util.Arrays.<Object>asList(2, false, Integer.MAX_VALUE),
                java.util.Arrays.<Object>asList(3, null, null));
            assertThat(enabled).containsExactly(1);
            assertThatThrownBy(() -> storage().read(tx -> tx.queryOne(
                "SELECT big FROM " + flags + " WHERE id = 1", Tx.Binder.NONE, row -> row.intValue("big"))).join())
                .hasRootCauseInstanceOf(SQLException.class)
                .hasRootCauseMessage("big holds 2147483648, which does not fit an int");
        } finally {
            storage().async(tx -> {
                tx.execute("DROP TABLE IF EXISTS " + flags);
                return null;
            }).join();
        }
    }

    @Test
    void aBatchRunsTheStatementOncePerBinderInsideTheTransaction() {
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        String insert = "INSERT INTO " + probe() + " (id, state) VALUES (?, ?)";
        List<Tx.Binder> binders = new ArrayList<>();
        for (UUID id : ids) {
            binders.add(params -> params.uuid(id).text("B" + ids.indexOf(id)));
        }

        int[] counts = storage().async(tx -> tx.batch(insert, binders)).join();
        int[] none = storage().async(tx -> tx.batch(insert, List.of())).join();
        int[] changed = storage().async(tx -> tx.batch("UPDATE " + probe() + " SET state = ? WHERE id = ?",
            List.of(params -> params.text("C").uuid(ids.get(0)),
                params -> params.text("C").uuid(UUID.randomUUID())))).join();

        assertThat(counts).hasSize(3);
        assertThat(java.util.Arrays.stream(counts).boxed())
            .allMatch(count -> count == 1 || count == Statement.SUCCESS_NO_INFO);
        if (dialect() != Dialect.MYSQL) {
            assertThat(counts).containsExactly(1, 1, 1);
            assertThat(changed).containsExactly(1, 0);
        }
        assertThat(none).isEmpty();
        assertThat(changed).hasSize(2);
        assertThat(ids.stream().map(this::state)).containsExactly("C", "B1", "B2");
    }

    @Test
    void aBatchThatFailsMidwayThrowsAndTheWorkThatLetsItThroughWritesNothing() {
        UUID kept = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID before = UUID.randomUUID();
        insert(kept, "A", null, null, null, null);
        String insert = "INSERT INTO " + probe() + " (id, state) VALUES (?, ?)";

        assertThatThrownBy(() -> storage().async(tx -> {
            tx.update(insert, params -> params.uuid(before).text("A"));
            return tx.batch(insert, List.of(
                params -> params.uuid(first).text("B"),
                params -> params.uuid(kept).text("B"),
                params -> params.uuid(UUID.randomUUID()).text("B")));
        }).join()).isInstanceOf(CompletionException.class).cause().isInstanceOf(SQLException.class);

        assertThat(count()).isEqualTo(1);
        assertThat(state(kept)).isEqualTo("A");
    }

    @Test
    void aBackupIsTakenOnSqliteAndRefusedOnAServer() {
        if (dialect() == Dialect.SQLITE) {
            insert(UUID.randomUUID(), "A", null, null, null, null);

            assertThat(storage().backup("contract", 1).join()).isRegularFile()
                .hasFileName("manual-contract-20261102-101530.db");
            return;
        }
        assertThatThrownBy(() -> storage().backup("contract", 1).join())
            .isInstanceOf(CompletionException.class)
            .cause().isInstanceOf(StorageException.class)
            .hasMessage("a backup copies a SQLite database file, and this storage runs on " + dialect().id()
                + "; back the database up with the tools of the database server");
    }

    @Test
    void theStoreOfProVouchersPortedOntoTxKeepsItsClaimsCooldownsAndCaps() throws Exception {
        PortedVoucherStore store = new PortedVoucherStore(storage());
        UUID steve = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
        UUID alex = UUID.fromString("ec561538-f3fd-461d-aff5-086b22154bce");
        try (Connection connection = connect()) {
            try {
                ProVouchersDatabase.create(connection);

                assertThat(store.recordUse("uid-1", steve, 100L).join()).isTrue();
                assertThat(store.recordUse("uid-1", steve, 100L).join()).isTrue();
                assertThat(store.recordUse("uid-1", alex, 100L).join()).isFalse();
                assertThat(store.recordUseAndWait("uid-2", alex, 200L)).isTrue();

                store.setCooldown(steve, "gold", 1_790_000_600_000L).join();
                store.setCooldown(steve, "gold", 1_790_000_900_000L).join();
                store.setCooldown(steve, "rank", 5L).join();
                assertThat(store.activeCooldowns(steve, 1_790_000_000_000L).join())
                    .containsExactly(Map.entry("gold", 1_790_000_900_000L));

                assertThat(store.reserveUses("KIT", steve, 3, 2, 3).join()).isEqualTo(2);
                assertThat(store.reserveUses("KIT", alex, 3, 2, 3).join()).isEqualTo(1);
                assertThat(store.reserveUses("KIT", alex, 1, 2, 3).join()).isZero();
                assertThat(store.addUses("KIT", alex, 1, 2, 3).join()).isFalse();
                assertThat(ProbeSchema.rows(connection, "provouchers_use_totals")).contains("KIT|3");
                assertThat(uses(connection, "KIT", alex)).isEqualTo(1);

                assertThat(store.reserveUsesOptimistically("PASS", steve, 2, 5, 3).join()).isEqualTo(2);
                assertThat(store.reserveUsesOptimistically("PASS", alex, 2, 5, 3).join()).isEqualTo(1);
                assertThat(ProbeSchema.rows(connection, "provouchers_use_totals")).contains("PASS|3");
                assertThat(uses(connection, "PASS", steve)).isEqualTo(2);
            } finally {
                ProbeSchema.drop(connection, dialect(), "provouchers_");
            }
        }
    }

    private static int uses(Connection connection, String key, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT uses FROM provouchers_code_uses WHERE code = ? AND player_uuid = ?")) {
            statement.setString(1, key);
            statement.setString(2, player.toString());
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("a count of %s for %s", key, player).isTrue();
                return result.getInt(1);
            }
        }
    }

    @Test
    void queryOneRefusesSeveralRows() {
        insert(UUID.randomUUID(), "A", null, null, null, null);
        insert(UUID.randomUUID(), "A", null, null, null, null);

        assertThatThrownBy(() -> storage().read(tx -> tx.queryOne("SELECT id FROM " + probe(), Tx.Binder.NONE,
            row -> row.uuid("id"))).join()).hasRootCauseMessage(
            "expected at most one row, found 2: SELECT id FROM " + probe());
    }

    void insert(UUID id, String state, BigDecimal amount, Instant at, String doc, byte[] data) {
        String sql = "INSERT INTO " + probe() + " (" + String.join(", ", COLUMNS) + ") VALUES (?, ?, ?, ?, ?, ?)";
        storage().async(tx -> tx.update(sql,
            params -> params.uuid(id).text(state).money(amount).time(at).json(doc).bytes(data))).join();
    }

    String state(UUID id) {
        return storage().read(tx -> tx.queryOne("SELECT state FROM " + probe() + " WHERE id = ?",
            params -> params.uuid(id), row -> row.text("state")).orElseThrow()).join();
    }

    static List<String> columns(Connection connection, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM " + table)) {
            for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                columns.add(result.getMetaData().getColumnName(column));
            }
        }
        return columns;
    }

    static String checksum(Connection connection, String prefix, int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT checksum FROM " + prefix + "schema_version WHERE version = ?")) {
            statement.setInt(1, version);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("a row for version %d", version).isTrue();
                return result.getString(1);
            }
        }
    }

    long count() {
        return storage().read(tx -> tx.queryOne("SELECT COUNT(*) AS n FROM " + probe(), Tx.Binder.NONE,
            row -> row.integer("n")).orElseThrow()).join();
    }
}
