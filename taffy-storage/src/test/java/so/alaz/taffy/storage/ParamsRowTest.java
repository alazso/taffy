package so.alaz.taffy.storage;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ParamsRowTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:15:30.123Z");

    @TempDir
    Path folder;

    private Connection connection;

    @BeforeEach
    void openProbeTable() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("probe.db"));
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE probe (id BLOB NOT NULL PRIMARY KEY, state VARCHAR(16),"
                + " amount BIGINT, at BIGINT, doc TEXT, data BLOB, wide TEXT)");
        }
    }

    @AfterEach
    void closeConnection() throws SQLException {
        connection.close();
    }

    @Test
    void everyBoundTypeRoundTripsExactly() throws SQLException {
        UUID id = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("922337203685477.5807");
        Instant at = Instant.ofEpochMilli(1_798_000_000_123L);
        byte[] data = {0, 1, 2, (byte) 0xFF};

        insert(id, "A", amount, at, "{\"a\":1}", data);
        List<Object[]> rows = query("SELECT * FROM probe WHERE id = ?", params -> params.uuid(id),
            found -> new Object[] {found.uuid("id"), found.text("state"), found.money("amount"), found.time("at"),
                found.json("doc"), found.bytes("data")});

        assertThat(rows).hasSize(1);
        Object[] row = rows.getFirst();
        assertThat(row[0]).isEqualTo(id);
        assertThat(row[1]).isEqualTo("A");
        assertThat((BigDecimal) row[2]).isEqualByComparingTo(amount).hasScaleOf(MoneyColumn.SCALE);
        assertThat(row[3]).isEqualTo(at);
        assertThat(row[4]).isEqualTo("{\"a\":1}");
        assertThat((byte[]) row[5]).containsExactly(data);
    }

    @Test
    void nullsRoundTripAsNull() throws SQLException {
        UUID id = UUID.randomUUID();
        insert(id, null, null, null, null, null);

        List<List<Object>> rows = query("SELECT id, state, amount, at, doc, data, NULL AS missing"
                + " FROM probe WHERE id = ?", params -> params.uuid(id),
            found -> Arrays.<Object>asList(found.text("state"), found.money("amount"), found.time("at"),
                found.json("doc"), found.bytes("data"), found.integer("amount"), found.text("missing"),
                found.uuid("missing"), found.decimal("missing")));

        assertThat(rows).singleElement().satisfies(values -> assertThat(values).containsOnlyNulls());
    }

    @Test
    void anInstantWhoseMillisecondsDoNotFitALongIsRefused() throws SQLException {
        Instant last = Instant.ofEpochMilli(Long.MAX_VALUE);
        Instant first = Instant.ofEpochMilli(Long.MIN_VALUE);
        insert(UUID.randomUUID(), "last", null, last, null, null);
        insert(UUID.randomUUID(), "first", null, first, null, null);

        assertThat(query("SELECT at FROM probe WHERE state = 'last'", params -> { }, found -> found.time("at")))
            .containsExactly(last);
        assertThat(query("SELECT at FROM probe WHERE state = 'first'", params -> { }, found -> found.time("at")))
            .containsExactly(first);
        for (Instant sentinel : List.of(Instant.MAX, Instant.MIN, last.plusMillis(1), first.minusMillis(1))) {
            assertThatThrownBy(() -> insert(UUID.randomUUID(), "never", null, sentinel, null, null))
                .isInstanceOf(ArithmeticException.class);
        }
        assertThat(query("SELECT state FROM probe WHERE state = 'never'", params -> { },
            found -> found.text("state"))).isEmpty();
    }

    @Test
    void aNullBalanceBindsNull() throws SQLException {
        UUID id = UUID.randomUUID();

        update("INSERT INTO probe (id, state, amount) VALUES (?, ?, ?)",
            params -> params.uuid(id).text("A").moneyOrNull(null));

        assertThat(query("SELECT amount FROM probe", params -> { }, row -> row.money("amount")))
            .containsExactly((BigDecimal) null);
    }

    @Test
    void moneySortsInNumericOrder() throws SQLException {
        for (String amount : List.of("10", "9.5", "100", "0", "0.0001")) {
            insert(UUID.randomUUID(), "A", new BigDecimal(amount), NOW, null, null);
        }

        List<BigDecimal> sorted = query("SELECT amount FROM probe ORDER BY amount", params -> { },
            row -> row.money("amount"));

        assertThat(sorted).extracting(BigDecimal::toPlainString)
            .containsExactly("0.0000", "0.0001", "9.5000", "10.0000", "100.0000");
    }

    @Test
    void aNegativeAmountIsRefusedAndANegativeBalanceIsKept() throws SQLException {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> insert(UUID.randomUUID(), "A", new BigDecimal("-1"), NOW, null, null))
            .isInstanceOf(MoneyOutOfRangeException.class)
            .hasMessage("amount -1 is outside 0 to 922337203685477.5807");
        update("INSERT INTO probe (id, state, amount) VALUES (?, ?, ?)",
            params -> params.uuid(id).text("A").moneyOrNull(new BigDecimal("-5.5")));

        assertThat(query("SELECT amount FROM probe", params -> { }, row -> row.money("amount")))
            .singleElement().satisfies(amount -> assertThat(amount).isEqualByComparingTo("-5.5"));
    }

    @Test
    void anOverCapBalanceStoresNullAndAnOverCapAmountIsRefused() throws SQLException {
        UUID id = UUID.randomUUID();
        BigDecimal over = new BigDecimal("1e20");

        update("INSERT INTO probe (id, state, amount) VALUES (?, ?, ?)",
            params -> params.uuid(id).text("A").moneyOrNull(over));
        List<BigDecimal> stored = query("SELECT amount FROM probe", params -> { }, row -> row.money("amount"));

        assertThat(stored).containsExactly((BigDecimal) null);
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "A", over, NOW, null, null))
            .isInstanceOf(MoneyOutOfRangeException.class);
        assertThat(query("SELECT COUNT(*) AS n FROM probe", params -> { }, row -> row.integer("n")))
            .containsExactly(1L);
    }

    @Test
    void anOverCapSignedBalanceIsRefusedAndOneInsideTheCapIsKept() throws SQLException {
        UUID id = UUID.randomUUID();

        for (String over : List.of("1e20", "-1e20")) {
            assertThatThrownBy(() -> update("INSERT INTO probe (id, state, amount) VALUES (?, ?, ?)",
                params -> params.uuid(UUID.randomUUID()).text("A").signedMoney(new BigDecimal(over))))
                .isInstanceOf(MoneyOutOfRangeException.class)
                .hasMessageEndingWith("is outside -922337203685477.5807 to 922337203685477.5807");
        }
        update("INSERT INTO probe (id, state, amount) VALUES (?, ?, ?)",
            params -> params.uuid(id).text("A").signedMoney(new BigDecimal("-5.5")));

        assertThat(query("SELECT amount FROM probe", params -> { }, row -> row.money("amount")))
            .singleElement().satisfies(amount -> assertThat(amount).isEqualByComparingTo("-5.5"));
    }

    @Test
    void aNullSignedBalanceBindsNull() throws SQLException {
        update("INSERT INTO probe (id, state, amount) VALUES (?, ?, ?)",
            params -> params.uuid(UUID.randomUUID()).text("A").signedMoney(null));

        assertThat(query("SELECT amount FROM probe", params -> { }, row -> row.money("amount")))
            .containsExactly((BigDecimal) null);
    }

    @Test
    void aDecimalBeyondTheMoneyCapRoundTripsWithItsScale() throws SQLException {
        List<BigDecimal> values = List.of(new BigDecimal("123456789012345678901234567890.123456789"),
            new BigDecimal("-1000000000000000.00"), new BigDecimal("1E+30"), BigDecimal.ZERO);
        for (BigDecimal value : values) {
            update("INSERT INTO probe (id, state, wide) VALUES (?, ?, ?)",
                params -> params.uuid(UUID.randomUUID()).text(value.toString()).decimal(value));
        }
        update("INSERT INTO probe (id, state, wide) VALUES (?, ?, ?)",
            params -> params.uuid(UUID.randomUUID()).text(null).decimal(null));

        List<Object[]> rows = query("SELECT state, wide FROM probe", params -> { },
            row -> new Object[] {row.text("state"), row.decimal("wide")});

        assertThat(rows).hasSize(5).allSatisfy(row -> assertThat(row[1])
            .isEqualTo(row[0] == null ? null : new BigDecimal((String) row[0])));
        assertThat(query("SELECT typeof(wide) AS kind FROM probe WHERE wide IS NOT NULL", params -> { },
            row -> row.text("kind"))).containsOnly("text");
    }

    @Test
    void aDecimalSortsAsTextOnSqlite() throws SQLException {
        for (String value : List.of("10", "9", "100")) {
            update("INSERT INTO probe (id, state, wide) VALUES (?, ?, ?)",
                params -> params.uuid(UUID.randomUUID()).text("A").decimal(new BigDecimal(value)));
        }

        List<BigDecimal> sorted = query("SELECT wide FROM probe ORDER BY wide", params -> { },
            row -> row.decimal("wide"));

        assertThat(sorted).extracting(BigDecimal::toPlainString).containsExactly("10", "100", "9");
    }

    @Test
    void textThatIsNotADecimalNumberIsRefusedOnRead() throws SQLException {
        update("INSERT INTO probe (id, state) VALUES (?, ?)", params -> params.uuid(UUID.randomUUID()).text("lots"));

        assertThatThrownBy(() -> query("SELECT state FROM probe", params -> { }, row -> row.decimal("state")))
            .isInstanceOf(SQLException.class)
            .hasMessage("state holds text that is not a decimal number")
            .hasCauseInstanceOf(NumberFormatException.class);
    }

    @Test
    void intsAndBooleansAreStoredAsWholeNumbersAndNullsReadAsNull() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE flags (id INTEGER PRIMARY KEY, enabled BOOLEAN, level INTEGER)");
        }
        String insert = "INSERT INTO flags (id, enabled, level) VALUES (?, ?, ?)";
        update(insert, params -> params.intValue(1).bool(true).intValue(Integer.MIN_VALUE));
        update(insert, params -> params.intValue(2).bool(Boolean.FALSE).intValue(Integer.valueOf(Integer.MAX_VALUE)));
        update(insert, params -> params.intValue(3).bool((Boolean) null).intValue((Integer) null));

        List<List<Object>> rows = query("SELECT * FROM flags ORDER BY id", params -> { },
            row -> Arrays.<Object>asList(row.intValue("id"), row.bool("enabled"), row.intValue("level"),
                row.integer("enabled")));

        assertThat(rows).containsExactly(
            Arrays.<Object>asList(1, true, Integer.MIN_VALUE, 1L),
            Arrays.<Object>asList(2, false, Integer.MAX_VALUE, 0L),
            Arrays.<Object>asList(3, null, null, null));
    }

    @Test
    void aNumberOutsideAnIntAndOneThatIsNeitherZeroNorOneAreRefused() throws SQLException {
        UUID id = UUID.randomUUID();
        update("INSERT INTO probe (id, amount, at) VALUES (?, ?, ?)",
            params -> params.uuid(id).integer(Integer.MIN_VALUE - 1L).integer(2));

        assertThatThrownBy(() -> query("SELECT amount FROM probe", params -> { }, row -> row.intValue("amount")))
            .isInstanceOf(SQLException.class)
            .hasMessage("amount holds -2147483649, which does not fit an int");
        assertThatThrownBy(() -> query("SELECT at FROM probe", params -> { }, row -> row.bool("at")))
            .isInstanceOf(SQLException.class)
            .hasMessage("at holds 2, which is neither 0 nor 1");
    }

    private void insert(UUID id, @Nullable String state, @Nullable BigDecimal amount, @Nullable Instant at,
        @Nullable String doc, byte @Nullable [] data) throws SQLException {
        update("INSERT INTO probe (id, state, amount, at, doc, data) VALUES (?, ?, ?, ?, ?, ?)",
            params -> params.uuid(id).text(state).money(amount).time(at).json(doc).bytes(data));
    }

    private void update(String sql, Binder binder) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(new Params(statement, Dialect.SQLITE));
            statement.executeUpdate();
        }
    }

    private <T> List<T> query(String sql, Binder binder, Mapper<T> mapper) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(new Params(statement, Dialect.SQLITE));
            try (ResultSet result = statement.executeQuery()) {
                Row row = new Row(result, Dialect.SQLITE);
                List<T> found = new ArrayList<>();
                while (result.next()) {
                    found.add(mapper.map(row));
                }
                return found;
            }
        }
    }

    @FunctionalInterface
    private interface Binder {
        void bind(Params params) throws SQLException;
    }

    @FunctionalInterface
    private interface Mapper<T> {
        @Nullable T map(Row row) throws SQLException;
    }
}
