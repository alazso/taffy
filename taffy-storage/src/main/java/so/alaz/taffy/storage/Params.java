package so.alaz.taffy.storage;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

/**
 * Binds the values of a statement's {@code ?} placeholders in order, the first call binding the first
 * placeholder. Every binder that takes an object binds SQL {@code NULL} for {@code null}, and every binder
 * returns this instance so calls chain.
 *
 * <p>An instance belongs to one statement; it is not thread-safe.
 */
public final class Params {

    private final PreparedStatement statement;
    private final Dialect dialect;
    private int index;

    Params(PreparedStatement statement, Dialect dialect) {
        this.statement = statement;
        this.dialect = dialect;
    }

    /**
     * Binds a UUID: as its 16 bytes, most significant first, on SQLite, MySQL and MariaDB, and as a native
     * {@code uuid} value on PostgreSQL.
     *
     * @param value the UUID, or {@code null}
     * @return this instance
     * @throws SQLException if the driver refuses the value
     */
    public Params uuid(@Nullable UUID value) throws SQLException {
        dialect.bindUuid(statement, ++index, value);
        return this;
    }

    /**
     * Binds a string.
     *
     * @param value the string, or {@code null}
     * @return this instance
     * @throws SQLException if the driver refuses the value
     */
    public Params text(@Nullable String value) throws SQLException {
        if (value == null) {
            statement.setNull(++index, Types.VARCHAR);
        } else {
            statement.setString(++index, value);
        }
        return this;
    }

    /**
     * Binds a whole number.
     *
     * @param value the number
     * @return this instance
     * @throws SQLException if the driver refuses the value
     */
    public Params integer(long value) throws SQLException {
        statement.setLong(++index, value);
        return this;
    }

    /**
     * Binds a whole number, or {@code NULL} typed as {@code BIGINT}.
     *
     * @param value the number, or {@code null}
     * @return this instance
     * @throws SQLException if the driver refuses the value
     */
    public Params integer(@Nullable Long value) throws SQLException {
        return value == null ? nullBigint() : integer(value.longValue());
    }

    /**
     * Binds an amount of money as a whole number at {@value MoneyColumn#SCALE} decimal places, encoded by
     * {@link MoneyColumn#toStored}. An amount whose magnitude is below {@code 0.0001} is bound as 0 whatever its
     * sign.
     *
     * @param value the amount, between 0 and {@link MoneyColumn#MAX}, or {@code null}
     * @return this instance
     * @throws MoneyOutOfRangeException if the amount is {@code -0.0001} or less, or above {@link MoneyColumn#MAX}
     * @throws SQLException if the driver refuses the value
     */
    public Params money(@Nullable BigDecimal value) throws SQLException {
        return value == null ? nullBigint() : integer(MoneyColumn.toStored(value));
    }

    /**
     * Binds a signed amount of money as a whole number at {@value MoneyColumn#SCALE} decimal places, encoded by
     * {@link MoneyColumn#toStoredOrNull}. An amount whose magnitude is below {@code 0.0001} is bound as 0 whatever
     * its sign, and one whose magnitude is above {@link MoneyColumn#MAX} is bound as {@code NULL}.
     *
     * @param value the amount, which may be negative, or {@code null}
     * @return this instance
     * @throws SQLException if the driver refuses the value
     */
    public Params moneyOrNull(@Nullable BigDecimal value) throws SQLException {
        return integer(value == null ? null : MoneyColumn.toStoredOrNull(value));
    }

    /**
     * Binds an instant as its milliseconds since the epoch, in a {@code BIGINT}. Precision below a millisecond is
     * dropped.
     *
     * @param value the instant, or {@code null}
     * @return this instance
     * @throws SQLException if the driver refuses the value
     */
    public Params time(@Nullable Instant value) throws SQLException {
        return value == null ? nullBigint() : integer(value.toEpochMilli());
    }

    /**
     * Binds a JSON document: as {@link Types#OTHER} on PostgreSQL, so it fits a {@code json} or {@code jsonb}
     * column, and as a string on the other databases.
     *
     * @param value the JSON text, or {@code null}
     * @return this instance
     * @throws SQLException if the driver refuses the value
     */
    public Params json(@Nullable String value) throws SQLException {
        dialect.bindJson(statement, ++index, value);
        return this;
    }

    /**
     * Binds a byte array.
     *
     * @param value the bytes, or {@code null}
     * @return this instance
     * @throws SQLException if the driver refuses the value
     */
    public Params bytes(byte @Nullable [] value) throws SQLException {
        if (value == null) {
            statement.setNull(++index, Types.VARBINARY);
        } else {
            statement.setBytes(++index, value);
        }
        return this;
    }

    private Params nullBigint() throws SQLException {
        statement.setNull(++index, Types.BIGINT);
        return this;
    }
}
