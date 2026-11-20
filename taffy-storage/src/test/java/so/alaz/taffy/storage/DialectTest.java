package so.alaz.taffy.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

final class DialectTest {

    private static final List<String> COLUMNS = List.of("owner_id", "slot", "seq", "amount");
    private static final List<String> KEY = List.of("owner_id", "slot", "seq");
    private static final UUID ID = UUID.fromString("01234567-89ab-cdef-fedc-ba9876543210");
    private static final byte[] ID_BYTES = {
        0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xab, (byte) 0xcd, (byte) 0xef,
        (byte) 0xfe, (byte) 0xdc, (byte) 0xba, (byte) 0x98, 0x76, 0x54, 0x32, 0x10
    };

    @Test
    void insertOrIgnoreFollowsEachDatabase() {
        assertThat(Dialect.SQLITE.insertOrIgnore("items", COLUMNS, KEY)).isEqualTo(
            "INSERT INTO items (owner_id, slot, seq, amount) VALUES (?, ?, ?, ?) ON CONFLICT (owner_id, slot, seq) DO NOTHING");
        assertThat(Dialect.POSTGRES.insertOrIgnore("items", COLUMNS, KEY))
            .isEqualTo(Dialect.SQLITE.insertOrIgnore("items", COLUMNS, KEY));
        assertThat(Dialect.MARIADB.insertOrIgnore("p_items", COLUMNS, KEY)).isEqualTo(
            "INSERT INTO p_items (owner_id, slot, seq, amount) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE owner_id = owner_id");
        assertThat(Dialect.MYSQL.insertOrIgnore("p_items", COLUMNS, KEY))
            .isEqualTo(Dialect.MARIADB.insertOrIgnore("p_items", COLUMNS, KEY));
    }

    @Test
    void upsertUpdatesTheNamedColumns() {
        List<String> columns = List.of("version", "name", "applied_at");

        assertThat(Dialect.SQLITE.upsert("v", columns, List.of("version"), List.of("name", "applied_at"))).isEqualTo(
            "INSERT INTO v (version, name, applied_at) VALUES (?, ?, ?) ON CONFLICT (version) DO UPDATE SET"
                + " name = excluded.name, applied_at = excluded.applied_at");
        assertThat(Dialect.POSTGRES.upsert("v", columns, List.of("version"), List.of("name", "applied_at")))
            .isEqualTo(Dialect.SQLITE.upsert("v", columns, List.of("version"), List.of("name", "applied_at")));
        assertThat(Dialect.MARIADB.upsert("v", columns, List.of("version"), List.of("name", "applied_at"))).isEqualTo(
            "INSERT INTO v (version, name, applied_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE"
                + " name = VALUES(name), applied_at = VALUES(applied_at)");
        assertThat(Dialect.MYSQL.upsert("v", columns, List.of("version"), List.of("name", "applied_at")))
            .isEqualTo(Dialect.MARIADB.upsert("v", columns, List.of("version"), List.of("name", "applied_at")));
    }

    @Test
    void lockingFollowsEachDatabase() {
        assertThat(Dialect.SQLITE.forUpdate()).isEmpty();
        assertThat(Dialect.MYSQL.forUpdate()).isEqualTo(" FOR UPDATE");
        assertThat(Dialect.MARIADB.forUpdate()).isEqualTo(" FOR UPDATE");
        assertThat(Dialect.POSTGRES.forUpdate()).isEqualTo(" FOR UPDATE");
    }

    @Test
    void onlyMysqlAndMariadbRunDdlOutsideATransaction() {
        assertThat(Dialect.SQLITE.transactionalDdl()).isTrue();
        assertThat(Dialect.MYSQL.transactionalDdl()).isFalse();
        assertThat(Dialect.MARIADB.transactionalDdl()).isFalse();
        assertThat(Dialect.POSTGRES.transactionalDdl()).isTrue();
    }

    @Test
    void eachDialectListsItsOwnTables() {
        assertThat(Dialect.SQLITE.listTables()).contains("sqlite_master");
        assertThat(Dialect.MYSQL.listTables()).isEqualTo(Dialect.MARIADB.listTables());
        assertThat(Dialect.MARIADB.listTables()).contains("DATABASE()");
        assertThat(Dialect.POSTGRES.listTables()).contains("current_schema()");
        assertThat(List.of(Dialect.values())).extracting(Dialect::id)
            .containsExactly("sqlite", "mysql", "mariadb", "postgres");
    }

    @Test
    void eachDialectNamesItsDriverClass() {
        assertThat(List.of(Dialect.values())).extracting(Dialect::driverClassName).containsExactly(
            "org.sqlite.JDBC", "com.mysql.cj.jdbc.Driver", "org.mariadb.jdbc.Driver", "org.postgresql.Driver");
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void driverClassLoads(Dialect dialect) {
        assertThatCode(() -> Class.forName(dialect.driverClassName())).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = {"SQLITE", "MYSQL", "MARIADB"})
    void binaryDialectsBindAUuidAsSixteenBytes(Dialect dialect) throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);

        dialect.bindUuid(statement, 3, ID);

        verify(statement).setBytes(3, ID_BYTES);
        verifyNoMoreInteractions(statement);
    }

    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = {"SQLITE", "MYSQL", "MARIADB"})
    void binaryDialectsBindANullUuidAsBinaryNull(Dialect dialect) throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);

        dialect.bindUuid(statement, 2, null);

        verify(statement).setNull(2, Types.BINARY);
        verifyNoMoreInteractions(statement);
    }

    @Test
    void postgresBindsAUuidAsANativeValue() throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);

        Dialect.POSTGRES.bindUuid(statement, 1, ID);
        Dialect.POSTGRES.bindUuid(statement, 2, null);

        verify(statement).setObject(1, ID, Types.OTHER);
        verify(statement).setObject(2, null, Types.OTHER);
        verifyNoMoreInteractions(statement);
    }

    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = {"SQLITE", "MYSQL", "MARIADB"})
    void binaryDialectsReadSixteenBytesAsAUuid(Dialect dialect) throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getBytes("owner")).thenReturn(ID_BYTES);

        assertThat(dialect.readUuid(row, "owner")).isEqualTo(ID);
    }

    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = {"SQLITE", "MYSQL", "MARIADB"})
    void binaryDialectsReadANullColumnAsNull(Dialect dialect) throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getBytes("owner")).thenReturn(null);

        assertThat(dialect.readUuid(row, "owner")).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = {"SQLITE", "MYSQL", "MARIADB"})
    void binaryDialectsRefuseAColumnThatIsNotSixteenBytes(Dialect dialect) throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getBytes("owner")).thenReturn(new byte[15]);

        assertThatThrownBy(() -> dialect.readUuid(row, "owner"))
            .isInstanceOf(SQLException.class)
            .hasMessage("owner holds 15 bytes, not a 16 byte UUID");
    }

    @Test
    void postgresReadsAUuidAsANativeValue() throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("owner", UUID.class)).thenReturn(ID);
        when(row.getObject("other", UUID.class)).thenReturn(null);

        assertThat(Dialect.POSTGRES.readUuid(row, "owner")).isEqualTo(ID);
        assertThat(Dialect.POSTGRES.readUuid(row, "other")).isNull();
        verify(row).getObject("owner", UUID.class);
        verify(row).getObject("other", UUID.class);
        verifyNoMoreInteractions(row);
    }

    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = {"SQLITE", "MYSQL", "MARIADB"})
    void textDialectsBindJsonAsAString(Dialect dialect) throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);

        dialect.bindJson(statement, 1, "{\"a\":1}");
        dialect.bindJson(statement, 2, null);

        verify(statement).setString(1, "{\"a\":1}");
        verify(statement).setNull(2, Types.VARCHAR);
        verifyNoMoreInteractions(statement);
    }

    @Test
    void postgresBindsJsonAsANativeValue() throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);

        Dialect.POSTGRES.bindJson(statement, 1, "{\"a\":1}");
        Dialect.POSTGRES.bindJson(statement, 2, null);

        verify(statement).setObject(1, "{\"a\":1}", Types.OTHER);
        verify(statement).setObject(2, null, Types.OTHER);
        verifyNoMoreInteractions(statement);
    }

    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = {"MYSQL", "MARIADB", "POSTGRES"})
    void serverDialectsBindADecimalAsANumericValue(Dialect dialect) throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);
        BigDecimal value = new BigDecimal("1000000000000000.5000");

        dialect.bindDecimal(statement, 1, value);
        dialect.bindDecimal(statement, 2, null);

        verify(statement).setBigDecimal(1, value);
        verify(statement).setNull(2, Types.NUMERIC);
        verifyNoMoreInteractions(statement);
    }

    @Test
    void sqliteBindsADecimalAsItsText() throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);

        Dialect.SQLITE.bindDecimal(statement, 1, new BigDecimal("1000000000000000.5000"));
        Dialect.SQLITE.bindDecimal(statement, 2, new BigDecimal("1e999999999"));
        Dialect.SQLITE.bindDecimal(statement, 3, null);

        verify(statement).setString(1, "1000000000000000.5000");
        verify(statement).setString(2, "1E+999999999");
        verify(statement).setNull(3, Types.VARCHAR);
        verifyNoMoreInteractions(statement);
    }

    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = {"MYSQL", "MARIADB", "POSTGRES"})
    void serverDialectsReadADecimalFromTheDriver(Dialect dialect) throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getBigDecimal("balance")).thenReturn(new BigDecimal("1000000000000000.5000"));

        assertThat(dialect.readDecimal(row, "balance")).isEqualTo(new BigDecimal("1000000000000000.5000"));
        assertThat(dialect.readDecimal(row, "missing")).isNull();
    }

    @Test
    void sqliteReadsADecimalFromItsText() throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("balance")).thenReturn("1000000000000000.5000");
        when(row.getString("label")).thenReturn("lots");

        assertThat(Dialect.SQLITE.readDecimal(row, "balance")).isEqualTo(new BigDecimal("1000000000000000.5000"));
        assertThat(Dialect.SQLITE.readDecimal(row, "missing")).isNull();
        assertThatThrownBy(() -> Dialect.SQLITE.readDecimal(row, "label"))
            .isInstanceOf(SQLException.class)
            .hasMessage("label holds text that is not a decimal number");
    }
}
