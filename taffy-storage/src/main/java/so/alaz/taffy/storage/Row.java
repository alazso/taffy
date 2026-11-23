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
     * Reads a whole number that fits an {@code int}, such as one {@link Params#intValue(int)} bound.
     *
     * @param column the column name
     * @return the number, or {@code null} if the column holds SQL {@code NULL}
     * @throws SQLException if the column cannot be read as a number, on SQLite holds text, a number with a
     *     fraction or bytes, or holds a number below {@link Integer#MIN_VALUE} or above {@link Integer#MAX_VALUE}
     */
    public @Nullable Integer intValue(String column) throws SQLException {
        Long whole = whole(column);
        if (whole == null) {
            return null;
        }
        long value = whole;
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new SQLException(column + " holds " + value + ", which does not fit an int");
        }
        return (int) value;
    }

    /**
     * Reads a boolean bound by {@link Params#bool(boolean)}. On SQLite, MySQL and MariaDB the column holds a whole
     * number: 1 reads as {@code true} and 0 as {@code false}. On PostgreSQL the column is read as the driver reads
     * a boolean, which covers a {@code boolean} column and a whole-number column that holds 0 or 1.
     *
     * @param column the column name
     * @return the boolean, or {@code null} if the column holds SQL {@code NULL}
     * @throws SQLException if the column cannot be read, on SQLite, MySQL and MariaDB holds a number other than 0
     *     and 1, on SQLite holds text, a number with a fraction or bytes, or on PostgreSQL holds a value the driver
     *     does not read as a boolean
     */
    public @Nullable Boolean bool(String column) throws SQLException {
        if (dialect == Dialect.POSTGRES) {
            boolean value = result.getBoolean(column);
            return result.wasNull() ? null : value;
        }
        Long whole = whole(column);
        if (whole == null) {
            return null;
        }
        long value = whole;
        if (value != 0 && value != 1) {
            throw new SQLException(column + " holds " + value + ", which is neither 0 nor 1");
        }
        return value == 1;
    }

    // The SQLite driver reads text that is not a number as 0 and cuts the fraction of a REAL value, and the MySQL
    // driver throws NumberFormatException for text such as "true".
    private @Nullable Long whole(String column) throws SQLException {
        if (dialect == Dialect.SQLITE) {
            Object value = result.getObject(column);
            if (value == null) {
                return null;
            }
            if (value instanceof Integer || value instanceof Long) {
                return ((Number) value).longValue();
            }
            throw new SQLException(column + " holds a value that is not a whole number");
        }
        try {
            long value = result.getLong(column);
            return result.wasNull() ? null : value;
        } catch (NumberFormatException notANumber) {
            throw new SQLException(column + " holds text that is not a whole number", notANumber);
        }
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
     * Reads a decimal number bound by {@link Params#decimal}: a {@code DECIMAL} or {@code NUMERIC} value on MySQL,
     * MariaDB and PostgreSQL, and text on SQLite. The scale of the result is the scale the column holds, which
     * may differ from the scale of the bound value, so compare with {@link BigDecimal#compareTo}.
     *
     * @param column the column name
     * @return the number, or {@code null}
     * @throws SQLException if the column cannot be read, or on SQLite holds text that is not a decimal number
     */
    public @Nullable BigDecimal decimal(String column) throws SQLException {
        return dialect.readDecimal(result, column);
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
