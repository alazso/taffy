package so.alaz.taffy.storage;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A supported database: the SQL that differs between databases, the JDBC driver it connects with, and how it
 * stores UUIDs, JSON and decimal numbers.
 *
 * <p>The SQL builders take table and column names as given and concatenate them into the statement, so they
 * must come from code, never from a player or a server owner. Every value is a {@code ?} placeholder.
 */
public enum Dialect {
    /** SQLite. DDL runs inside a transaction; UUIDs are stored as 16 bytes. */
    SQLITE("sqlite", true),
    /**
     * MySQL. It generates the same SQL as {@link #MARIADB}: DDL runs outside a transaction, conflicts are
     * handled with {@code ON DUPLICATE KEY UPDATE}, and UUIDs are stored as 16 bytes. {@link #upsert} uses
     * {@code VALUES(column)}, which MySQL 8.0.20 and later deprecate but still accept.
     */
    MYSQL("mysql", false),
    /** MariaDB. DDL runs outside a transaction; UUIDs are stored as 16 bytes. */
    MARIADB("mariadb", false),
    /** PostgreSQL. DDL runs inside a transaction; UUIDs and JSON are bound as native values. */
    POSTGRES("postgres", true);

    private final String id;
    private final boolean transactionalDdl;

    Dialect(String id, boolean transactionalDdl) {
        this.id = id;
        this.transactionalDdl = transactionalDdl;
    }

    /**
     * Returns the lowercase identifier of this database: {@code sqlite}, {@code mysql}, {@code mariadb} or
     * {@code postgres}. It also names the folder that holds this database's migration scripts.
     *
     * @return the identifier
     */
    public String id() {
        return id;
    }

    /**
     * Returns whether DDL statements take part in a transaction, so that a failed migration rolls back
     * completely.
     *
     * @return {@code true} for SQLite and PostgreSQL, {@code false} for MySQL and MariaDB
     */
    public boolean transactionalDdl() {
        return transactionalDdl;
    }

    /**
     * Returns the class name of the JDBC driver for this database. The pool names it explicitly because Paper's
     * library loader puts the drivers on the plugin class loader, where {@link java.sql.DriverManager} cannot
     * find them by URL.
     *
     * @return the fully qualified driver class name
     */
    public String driverClassName() {
        return switch (this) {
            case SQLITE -> "org.sqlite.JDBC";
            case MYSQL -> "com.mysql.cj.jdbc.Driver";
            case MARIADB -> "org.mariadb.jdbc.Driver";
            case POSTGRES -> "org.postgresql.Driver";
        };
    }

    /**
     * Builds an insert of one row that does nothing when the row conflicts with an existing one, so the update
     * count is 0 instead of an error.
     *
     * <p>On MySQL and MariaDB the statement skips a conflict on any unique key of the table, so {@code key} must
     * be the table's only unique key. There the update count of a conflicting row is 0 only on a connection opened
     * with {@code useAffectedRows=true}; with the drivers' default it is 1, the same as a fresh insert.
     *
     * @param table the table name, with any prefix
     * @param columns the inserted columns, one placeholder each, in order
     * @param key the columns of the unique key a conflict is detected on; must not be empty
     * @return the statement text
     */
    public String insertOrIgnore(String table, List<String> columns, List<String> key) {
        String insert = insert(table, columns);
        return switch (this) {
            case MYSQL, MARIADB -> insert + " ON DUPLICATE KEY UPDATE " + key.getFirst() + " = " + key.getFirst();
            case SQLITE, POSTGRES -> insert + " ON CONFLICT (" + String.join(", ", key) + ") DO NOTHING";
        };
    }

    /**
     * Builds an insert of one row that, when the row conflicts with an existing one, overwrites the named
     * columns of the existing row with the inserted values.
     *
     * <p>On MySQL and MariaDB the statement uses {@code ON DUPLICATE KEY UPDATE column = VALUES(column)}, which
     * reacts to a conflict on any unique key of the table. MySQL 8.0.20 and later deprecate {@code VALUES(column)}
     * in this position but still accept it.
     *
     * <p>Run through {@link Tx#update} on MySQL or MariaDB storage, it counts 1 for an inserted row, 2 for an
     * existing row it changed and 0 for an existing row it left as it was. On SQLite and PostgreSQL it counts 1
     * either way.
     *
     * @param table the table name, with any prefix
     * @param columns the inserted columns, one placeholder each, in order
     * @param key the columns of the unique key a conflict is detected on; MySQL and MariaDB ignore it
     * @param updated the columns overwritten on a conflict; must not be empty
     * @return the statement text
     */
    public String upsert(String table, List<String> columns, List<String> key, List<String> updated) {
        String insert = insert(table, columns);
        return switch (this) {
            case MYSQL, MARIADB -> insert + " ON DUPLICATE KEY UPDATE "
                + updated.stream().map(column -> column + " = VALUES(" + column + ")").collect(Collectors.joining(", "));
            case SQLITE, POSTGRES -> insert + " ON CONFLICT (" + String.join(", ", key) + ") DO UPDATE SET "
                + updated.stream().map(column -> column + " = excluded." + column).collect(Collectors.joining(", "));
        };
    }

    /**
     * Returns the clause appended to a select to lock the rows it reads until the transaction ends.
     *
     * @return {@code " FOR UPDATE"}, or an empty string on SQLite, which has no such clause
     */
    public String forUpdate() {
        return this == SQLITE ? "" : " FOR UPDATE";
    }

    /**
     * Returns a query whose single column holds the name of each table in the current database or schema.
     *
     * @return the query text
     */
    public String listTables() {
        return switch (this) {
            case SQLITE -> "SELECT name FROM sqlite_master WHERE type = 'table'";
            case MYSQL, MARIADB -> "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()";
            case POSTGRES -> "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema()";
        };
    }

    void bindUuid(PreparedStatement statement, int index, @Nullable UUID id) throws SQLException {
        if (this == POSTGRES) {
            statement.setObject(index, id, Types.OTHER);
        } else if (id == null) {
            statement.setNull(index, Types.BINARY);
        } else {
            statement.setBytes(index, ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array());
        }
    }

    @Nullable UUID readUuid(ResultSet row, String column) throws SQLException {
        if (this == POSTGRES) {
            return row.getObject(column, UUID.class);
        }
        byte[] bytes = row.getBytes(column);
        if (bytes == null) {
            return null;
        }
        if (bytes.length != 16) {
            throw new SQLException(column + " holds " + bytes.length + " bytes, not a 16 byte UUID");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    void bindJson(PreparedStatement statement, int index, @Nullable String json) throws SQLException {
        if (this == POSTGRES) {
            statement.setObject(index, json, Types.OTHER);
        } else if (json == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, json);
        }
    }

    void bindDecimal(PreparedStatement statement, int index, @Nullable BigDecimal value) throws SQLException {
        if (this == SQLITE) {
            if (value == null) {
                statement.setNull(index, Types.VARCHAR);
            } else {
                statement.setString(index, value.toString());
            }
        } else if (value == null) {
            statement.setNull(index, Types.NUMERIC);
        } else {
            statement.setBigDecimal(index, value);
        }
    }

    @Nullable BigDecimal readDecimal(ResultSet row, String column) throws SQLException {
        if (this != SQLITE) {
            return row.getBigDecimal(column);
        }
        String text = row.getString(column);
        if (text == null) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException failure) {
            throw new SQLException(column + " holds text that is not a decimal number", failure);
        }
    }

    private static String insert(String table, List<String> columns) {
        return "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES ("
            + columns.stream().map(column -> "?").collect(Collectors.joining(", ")) + ")";
    }
}
