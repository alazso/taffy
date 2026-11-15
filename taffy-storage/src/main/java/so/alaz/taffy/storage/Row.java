package so.alaz.taffy.storage;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/**
 * Reads the columns of the current row of a query result by column name, decoding each value the way
 * {@link Params} binds it. Every reader returns {@code null} when the column holds SQL {@code NULL}.
 *
 * <p>An instance reads the row its result is positioned on; it is not thread-safe.
 */
public final class Row {

    private final ResultSet result;
    private final Dialect dialect;

    Row(ResultSet result, Dialect dialect) {
        this.result = result;
        this.dialect = dialect;
    }

    /**
     * Reads a UUID: from 16 bytes, most significant first, on SQLite, MySQL and MariaDB, and from a native
     * {@code uuid} value on PostgreSQL.
     *
     * @param column the column name
     * @return the UUID, or {@code null}
     * @throws SQLException if the column cannot be read or does not hold exactly 16 bytes
     */
    public @Nullable UUID uuid(String column) throws SQLException {
        return dialect.readUuid(result, column);
    }

    /**
     * Reads a string.
     *
     * @param column the column name
     * @return the string, or {@code null}
     * @throws SQLException if the column cannot be read
     */
    public @Nullable String text(String column) throws SQLException {
        return result.getString(column);
    }

    /**
     * Reads a whole number.
     *
     * @param column the column name
     * @return the number, or {@code null} if the column holds SQL {@code NULL}
     * @throws SQLException if the column cannot be read as a number
     */
    public @Nullable Long integer(String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    /**
     * Reads an amount of money stored as a whole number at {@value MoneyColumn#SCALE} decimal places, decoded by
     * {@link MoneyColumn#fromStored}.
     *
     * @param column the column name
     * @return the amount at {@value MoneyColumn#SCALE} decimal places, or {@code null}
     * @throws SQLException if the column cannot be read as a number
     */
    public @Nullable BigDecimal money(String column) throws SQLException {
        Long stored = integer(column);
        return stored == null ? null : MoneyColumn.fromStored(stored);
    }

    /**
     * Reads an instant stored as milliseconds since the epoch.
     *
     * @param column the column name
     * @return the instant, or {@code null}
     * @throws SQLException if the column cannot be read as a number
     */
    public @Nullable Instant time(String column) throws SQLException {
        Long millis = integer(column);
        return millis == null ? null : Instant.ofEpochMilli(millis);
    }

    /**
     * Reads a JSON document as text. A MySQL {@code JSON} column and a PostgreSQL {@code jsonb} column return a
     * normalized document, which may differ from the stored text in whitespace, key order and duplicate keys.
     * MariaDB, SQLite and a PostgreSQL {@code json} column return the text as it was stored.
     *
     * @param column the column name
     * @return the JSON text, or {@code null}
     * @throws SQLException if the column cannot be read
     */
    public @Nullable String json(String column) throws SQLException {
        return result.getString(column);
    }

    /**
     * Reads a byte array.
     *
     * @param column the column name
     * @return the bytes, or {@code null}
     * @throws SQLException if the column cannot be read
     */
    public byte @Nullable [] bytes(String column) throws SQLException {
        return result.getBytes(column);
    }
}
