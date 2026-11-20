package so.alaz.taffy.storage;

import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Three probe migrations for a server database, with the queries the container suites read the result with. The
 * MySQL and MariaDB scripts are the same text and keep every statement rerunnable, with the index inline.
 */
final class ProbeSchema {

    static final List<Migration> MIGRATIONS =
        List.of(new Migration(1, "alpha"), new Migration(2, "beta"), new Migration(3, "gamma"));

    static final List<String> TABLES = List.of("alpha", "beta", "gamma", "schema_version");

    private static final String INNODB = " ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin";

    private static final List<String> DUPLICATE_KEY_SCRIPTS = List.of(
        """
            CREATE TABLE IF NOT EXISTS ${prefix}alpha (
                id INT NOT NULL PRIMARY KEY,
                label VARCHAR(32) NOT NULL
            )%s;
            """.formatted(INNODB),
        """
            CREATE TABLE IF NOT EXISTS ${prefix}beta (
                id INT NOT NULL PRIMARY KEY,
                alpha INT NOT NULL,
                INDEX ${prefix}beta_alpha (alpha)
            )%s;
            INSERT IGNORE INTO ${prefix}alpha (id, label) VALUES (1, 'seed');
            """.formatted(INNODB),
        """
            CREATE TABLE IF NOT EXISTS ${prefix}gamma (
                id INT NOT NULL PRIMARY KEY
            )%s;
            """.formatted(INNODB));

    private static final List<String> POSTGRES_SCRIPTS = List.of(
        """
            CREATE TABLE IF NOT EXISTS ${prefix}alpha (
                id INTEGER NOT NULL PRIMARY KEY,
                label VARCHAR(32) NOT NULL
            );
            """,
        """
            CREATE TABLE IF NOT EXISTS ${prefix}beta (
                id INTEGER NOT NULL PRIMARY KEY,
                alpha INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS ${prefix}beta_alpha ON ${prefix}beta (alpha);
            INSERT INTO ${prefix}alpha (id, label) VALUES (1, 'seed') ON CONFLICT (id) DO NOTHING;
            """,
        """
            CREATE TABLE IF NOT EXISTS ${prefix}gamma (
                id INTEGER NOT NULL PRIMARY KEY
            );
            """);

    private ProbeSchema() {
    }

    /** The probe scripts for a dialect, keyed by resource path, in a map the caller may change. */
    static Map<String, String> scripts(Dialect dialect) {
        List<String> texts = switch (dialect) {
            case MYSQL, MARIADB -> DUPLICATE_KEY_SCRIPTS;
            case POSTGRES -> POSTGRES_SCRIPTS;
            case SQLITE -> throw new IllegalArgumentException("the probe schema is for server databases");
        };
        Map<String, String> scripts = new HashMap<>();
        for (int index = 0; index < MIGRATIONS.size(); index++) {
            scripts.put(MIGRATIONS.get(index).resource(dialect), texts.get(index));
        }
        return scripts;
    }

    static Function<String, @Nullable InputStream> source(Map<String, String> scripts) {
        return path -> scripts.containsKey(path)
            ? new ByteArrayInputStream(scripts.get(path).getBytes(StandardCharsets.UTF_8)) : null;
    }

    static List<String> prefixed(String prefix, List<String> tables) {
        return tables.stream().map(table -> prefix + table).sorted().toList();
    }

    /** The tables of the current database or schema whose names start with the prefix, sorted. */
    static List<String> tables(Connection connection, Dialect dialect, String prefix) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(dialect.listTables())) {
            while (result.next()) {
                String table = result.getString(1);
                if (table.startsWith(prefix)) {
                    tables.add(table);
                }
            }
        }
        return tables.stream().sorted().toList();
    }

    static void drop(Connection connection, Dialect dialect, String prefix) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String table : tables(connection, dialect, prefix)) {
                statement.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }

    /** The {@code applied_at} of a recorded version, or {@code null} while it is incomplete. */
    static @Nullable Long appliedAt(Connection connection, String prefix, int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT applied_at FROM " + prefix + "schema_version WHERE version = ?")) {
            statement.setInt(1, version);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("a row for version %d", version).isTrue();
                long value = result.getLong(1);
                return result.wasNull() ? null : value;
            }
        }
    }

    static int statementsDone(Connection connection, String prefix, int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT statements_done FROM " + prefix + "schema_version WHERE version = ?")) {
            statement.setInt(1, version);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("a row for version %d", version).isTrue();
                return result.getInt(1);
            }
        }
    }

    /**
     * Every row of a table, sorted, each as its column values joined by {@code |}, with {@code null} for a column
     * that holds none.
     */
    static List<String> rows(Connection connection, String table) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM " + table)) {
            int columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                List<String> values = new ArrayList<>();
                for (int column = 1; column <= columns; column++) {
                    values.add(String.valueOf(result.getString(column)));
                }
                rows.add(String.join("|", values));
            }
        }
        return rows.stream().sorted().toList();
    }

    static List<Integer> versions(Connection connection, String prefix) throws SQLException {
        return ids(connection, prefix + "schema_version", "version");
    }

    static List<Integer> ids(Connection connection, String table, String column) throws SQLException {
        List<Integer> ids = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT " + column + " FROM " + table + " ORDER BY " + column)) {
            while (rows.next()) {
                ids.add(rows.getInt(1));
            }
        }
        return ids;
    }
}
