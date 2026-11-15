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
                + " amount BIGINT, at BIGINT, doc TEXT, data BLOB)");
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
                found.uuid("missing")));

        assertThat(rows).singleElement().satisfies(values -> assertThat(values).containsOnlyNulls());
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
