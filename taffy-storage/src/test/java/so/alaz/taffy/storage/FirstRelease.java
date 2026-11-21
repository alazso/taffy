package so.alaz.taffy.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The database a plugin on Taffy 0.1.0 left after its two migrations ran, and the scripts of that plugin's next
 * build, which adds a third migration.
 *
 * <p>The version table is {@code <prefix>schema_version} in the layout 0.1.0 created, and each row holds what 0.1.0
 * recorded: the SHA-256 of the script's exact bytes, the number of statements and the time of the run. The checksums
 * were taken from a run of the 0.1.0 migrator over these scripts on SQLite, with line feeds, with carriage return and
 * line feed, and with a byte order mark, which SQLite skips at the start of a statement. Nothing here is computed by
 * the migrator under test.
 */
final class FirstRelease {

    /** The bytes a build ships a script with. */
    enum Bytes {
        LF, CRLF, MARK, MARK_CRLF;

        String of(String script) {
            String ended = this == CRLF || this == MARK_CRLF ? script.replace("\n", "\r\n") : script;
            return this == MARK || this == MARK_CRLF ? '\uFEFF' + ended : ended;
        }
    }

    static final Migration ACCOUNTS = new Migration(1, "accounts");
    static final Migration LEDGER = new Migration(2, "ledger");
    static final Migration NOTES = new Migration(3, "notes");

    /** The migrations of the build that ran on 0.1.0. */
    static final List<Migration> RECORDED = List.of(ACCOUNTS, LEDGER);

    /** The migrations of the next build. */
    static final List<Migration> CURRENT = List.of(ACCOUNTS, LEDGER, NOTES);

    /** The {@code applied_at} of the recorded rows: 2026-06-01T12:00:00Z. */
    static final long APPLIED_AT = 1_780_315_200_000L;

    private static final String ACCOUNTS_SCRIPT = """
        -- Accounts of the players.
        CREATE TABLE IF NOT EXISTS ${prefix}accounts (
            id INTEGER NOT NULL PRIMARY KEY,
            name VARCHAR(32) NOT NULL
        );
        """;

    private static final String LEDGER_SCRIPT = """
        CREATE TABLE IF NOT EXISTS ${prefix}ledger (
            id INTEGER NOT NULL PRIMARY KEY,
            account INTEGER NOT NULL,
            note VARCHAR(64) NOT NULL
        );
        INSERT INTO ${prefix}accounts (id, name) VALUES (1, 'seed');
        """;

    private static final String NOTES_SCRIPT = """
        CREATE TABLE IF NOT EXISTS ${prefix}notes (
            id INTEGER NOT NULL PRIMARY KEY
        );
        """;

    // The statements 0.1.0 sent for the two scripts: no comment line, no semicolon.
    private static final List<String> RAN = List.of(
        """
            CREATE TABLE IF NOT EXISTS ${prefix}accounts (
                id INTEGER NOT NULL PRIMARY KEY,
                name VARCHAR(32) NOT NULL
            )""",
        """
            CREATE TABLE IF NOT EXISTS ${prefix}ledger (
                id INTEGER NOT NULL PRIMARY KEY,
                account INTEGER NOT NULL,
                note VARCHAR(64) NOT NULL
            )""",
        "INSERT INTO ${prefix}accounts (id, name) VALUES (1, 'seed')");

    private static final String PORTABLE_VERSION_TABLE = "CREATE TABLE IF NOT EXISTS %s"
        + " (version INTEGER NOT NULL PRIMARY KEY, name VARCHAR(128) NOT NULL, checksum CHAR(64) NOT NULL,"
        + " statements_done INTEGER NOT NULL, applied_at BIGINT)";

    private static final String INNODB_VERSION_TABLE = "CREATE TABLE IF NOT EXISTS %s"
        + " (version INT NOT NULL PRIMARY KEY, name VARCHAR(128) NOT NULL, checksum CHAR(64) NOT NULL,"
        + " statements_done INT NOT NULL, applied_at BIGINT NULL)"
        + " ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin";

    // The checksums 0.1.0 recorded for the accounts script and the ledger script, by the bytes of the scripts.
    private static final Map<Bytes, List<String>> CHECKSUMS = Map.of(
        Bytes.LF, List.of("a61a8ed2f65599d40e1cd362b77f7903d31d49cef21967c21a115ca3cee22bae",
            "22d38e8e018338cecdcaef39a3b20030dda93b987fa3bdca0c7e5b1acfd84f2e"),
        Bytes.CRLF, List.of("ddea7573bbd829eae93376d799c5c6f2fae09dade252567f643c5c6890198252",
            "c0de6e10d2fb423bf2dbe3a5ae3f5f2054b70ad49287d76539fa7dbd36375360"),
        Bytes.MARK, List.of("54b401fae816089d4af56a8db6e4f130493ec7cbfcdd19f8156b128f78762aec",
            "b2b4e9b786a5f71133039c59bc54df3f7946f1a2ce6e385c24868ea4dae2c945"));

    private FirstRelease() {
    }

    /**
     * Creates what 0.1.0 left under {@code prefix} after it ran the two scripts shipped as {@code shipped}: the
     * version table with its two rows, the two tables, the row the ledger script inserted, and an account and a
     * ledger entry the running plugin wrote.
     */
    static void record(Connection connection, Dialect dialect, String prefix, Bytes shipped) throws SQLException {
        String versionTable = prefix + "schema_version";
        String layout = dialect == Dialect.MYSQL || dialect == Dialect.MARIADB
            ? INNODB_VERSION_TABLE : PORTABLE_VERSION_TABLE;
        try (Statement statement = connection.createStatement()) {
            statement.execute(layout.formatted(versionTable));
            for (String ran : RAN) {
                statement.execute(ran.replace("${prefix}", prefix));
            }
            for (String row : rows(shipped)) {
                String[] values = row.split("\\|");
                statement.execute("INSERT INTO " + versionTable
                    + " (version, name, checksum, statements_done, applied_at) VALUES (" + values[0] + ", '"
                    + values[1] + "', '" + values[2] + "', " + values[3] + ", " + values[4] + ")");
            }
            statement.execute("INSERT INTO " + prefix + "accounts (id, name) VALUES (2, 'steve')");
            statement.execute("INSERT INTO " + prefix + "ledger (id, account, note) VALUES (10, 2, 'first sale')");
        }
    }

    /**
     * The two rows 0.1.0 recorded for scripts shipped as {@code shipped}, each as
     * {@code version|name|checksum|statements_done|applied_at}, the form {@link ProbeSchema#rows} reads a row in.
     */
    static List<String> rows(Bytes shipped) {
        List<String> checksums = CHECKSUMS.get(shipped);
        if (checksums == null) {
            throw new IllegalArgumentException("no run of 0.1.0 was recorded for " + shipped);
        }
        return List.of("1|accounts|" + checksums.get(0) + "|1|" + APPLIED_AT,
            "2|ledger|" + checksums.get(1) + "|2|" + APPLIED_AT);
    }

    /** The row a build records for the notes script, applied at {@code appliedAt}, in the form of {@link #rows}. */
    static String notesRow(long appliedAt) {
        return "3|notes|" + sha256(NOTES_SCRIPT) + "|1|" + appliedAt;
    }

    /** The three scripts of the next build for a dialect, shipped as {@code shipped}, keyed by resource path. */
    static Map<String, String> scripts(Dialect dialect, Bytes shipped) {
        Map<String, String> scripts = new HashMap<>();
        scripts.put(ACCOUNTS.resource(dialect), shipped.of(ACCOUNTS_SCRIPT));
        scripts.put(LEDGER.resource(dialect), shipped.of(LEDGER_SCRIPT));
        scripts.put(NOTES.resource(dialect), shipped.of(NOTES_SCRIPT));
        return scripts;
    }

    /** The tables this fixture and the next build create under {@code prefix}, without the version tables. */
    static List<String> tables(String prefix) {
        return List.of(prefix + "accounts", prefix + "ledger", prefix + "notes");
    }

    /** The lowercase hexadecimal SHA-256 of the UTF-8 bytes of {@code text}. */
    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("the JVM has no SHA-256", missing);
        }
    }
}
