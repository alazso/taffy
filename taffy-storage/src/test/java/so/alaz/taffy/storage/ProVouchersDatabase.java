package so.alaz.taffy.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The database ProVouchers 1.6.0 leaves, and the scripts a build of it on Taffy storage ships for the same schema.
 *
 * <p>{@link #create} runs the statements the migration runner of 1.6.0 ran from an empty database to version 5, in
 * their order and with their text, which is the same on SQLite, MySQL, MariaDB and PostgreSQL: the version table
 * {@code provouchers_schema_version} holds one row that the runner updated after each migration, and every table
 * carries the literal {@code provouchers_} in its name, with no table prefix. It then adds rows a running server
 * wrote.
 */
final class ProVouchersDatabase {

    /** The version table of the migration runner of 1.6.0. */
    static final String VERSION_TABLE = "provouchers_schema_version";

    /** The tables that hold rows at version 5. */
    static final List<String> TABLES = List.of("provouchers_code_uses", "provouchers_cooldowns",
        "provouchers_stash", "provouchers_use_totals", "provouchers_used_vouchers");

    /** The migrations of the build on Taffy storage, one for each version the runner of 1.6.0 applied. */
    static final List<Migration> MIGRATIONS = List.of(new Migration(1, "initial"), new Migration(2, "cooldowns"),
        new Migration(3, "used_vouchers"), new Migration(4, "stash"), new Migration(5, "use_totals"));

    private static final String REDEEMED_STAMPS = """
        CREATE TABLE IF NOT EXISTS provouchers_redeemed_stamps (
            batch_id VARCHAR(36) NOT NULL,
            nonce VARCHAR(36) NOT NULL,
            player_uuid VARCHAR(36) NOT NULL,
            redeemed_at BIGINT NOT NULL,
            PRIMARY KEY (batch_id, nonce)
        )""";

    private static final String CODE_USES = """
        CREATE TABLE IF NOT EXISTS provouchers_code_uses (
            code VARCHAR(64) NOT NULL,
            player_uuid VARCHAR(36) NOT NULL,
            uses INTEGER NOT NULL,
            updated_at BIGINT NOT NULL,
            PRIMARY KEY (code, player_uuid)
        )""";

    private static final String COOLDOWNS = """
        CREATE TABLE IF NOT EXISTS provouchers_cooldowns (
            player_uuid VARCHAR(36) NOT NULL,
            voucher_id VARCHAR(64) NOT NULL,
            expires_at BIGINT NOT NULL,
            PRIMARY KEY (player_uuid, voucher_id)
        )""";

    private static final String USED_VOUCHERS = """
        CREATE TABLE IF NOT EXISTS provouchers_used_vouchers (
            uid VARCHAR(36) NOT NULL,
            player_uuid VARCHAR(36) NOT NULL,
            redeemed_at BIGINT NOT NULL,
            PRIMARY KEY (uid)
        )""";

    private static final String STASH = """
        CREATE TABLE IF NOT EXISTS provouchers_stash (
            id VARCHAR(36) NOT NULL,
            player_uuid VARCHAR(36) NOT NULL,
            voucher_id VARCHAR(64) NOT NULL,
            amount INTEGER NOT NULL,
            arg VARCHAR(255),
            source VARCHAR(16) NOT NULL,
            created_at BIGINT NOT NULL,
            expires_at BIGINT,
            PRIMARY KEY (id)
        )""";

    private static final String USE_TOTALS = """
        CREATE TABLE IF NOT EXISTS provouchers_use_totals (
            code VARCHAR(64) NOT NULL,
            uses BIGINT NOT NULL,
            PRIMARY KEY (code)
        )""";

    // The statements of the migrations of 1.6.0, version 1 first.
    private static final List<List<String>> RAN = List.of(
        List.of(REDEEMED_STAMPS, CODE_USES),
        List.of(COOLDOWNS),
        List.of(USED_VOUCHERS, "DROP TABLE IF EXISTS provouchers_redeemed_stamps"),
        List.of(STASH, "CREATE INDEX idx_provouchers_stash_player ON provouchers_stash (player_uuid)",
            "DROP TABLE IF EXISTS provouchers_offline_gives"),
        List.of(USE_TOTALS, "DELETE FROM provouchers_use_totals",
            "INSERT INTO provouchers_use_totals (code, uses) "
                + "SELECT code, SUM(uses) FROM provouchers_code_uses GROUP BY code"));

    private static final String STEVE = "8667ba71-b85a-4004-af54-457a9734eed7";
    private static final String ALEX = "ec561538-f3fd-461d-aff5-086b22154bce";

    // The total of voucher:gold has no row in provouchers_code_uses, so a migration 5 that ran again would drop it.
    private static final List<String> WRITTEN = List.of(
        "INSERT INTO provouchers_code_uses (code, player_uuid, uses, updated_at) VALUES"
            + " ('WELCOME', '" + STEVE + "', 2, 1790000000000)",
        "INSERT INTO provouchers_code_uses (code, player_uuid, uses, updated_at) VALUES"
            + " ('WELCOME', '" + ALEX + "', 1, 1790000001000)",
        "INSERT INTO provouchers_cooldowns (player_uuid, voucher_id, expires_at) VALUES"
            + " ('" + STEVE + "', 'gold', 1790000600000)",
        "INSERT INTO provouchers_used_vouchers (uid, player_uuid, redeemed_at) VALUES"
            + " ('0b0e3d53-6a0c-4a5e-9d0f-6f3a3c1f2a11', '" + ALEX + "', 1790000002000)",
        "INSERT INTO provouchers_stash (id, player_uuid, voucher_id, amount, arg, source, created_at, expires_at)"
            + " VALUES ('5f0c7a0e-0d3a-4c57-8a55-0c1d7f1e9b02', '" + STEVE + "', 'gold', 3, NULL, 'COMMAND',"
            + " 1790000003000, NULL)",
        "INSERT INTO provouchers_stash (id, player_uuid, voucher_id, amount, arg, source, created_at, expires_at)"
            + " VALUES ('a4b1b6f4-7a52-4b0f-b0b3-3f6f2f0c7d03', '" + ALEX + "', 'rank', 1, 'vip', 'API',"
            + " 1790000004000, 1790086404000)",
        "INSERT INTO provouchers_use_totals (code, uses) VALUES ('WELCOME', 3)",
        "INSERT INTO provouchers_use_totals (code, uses) VALUES ('voucher:gold', 2)");

    private ProVouchersDatabase() {
    }

    /** Creates the database of 1.6.0 on a connection in autocommit mode. */
    static void create(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + VERSION_TABLE + " (version INTEGER NOT NULL)");
            statement.execute("INSERT INTO " + VERSION_TABLE + " (version) VALUES (0)");
            for (int version = 1; version <= RAN.size(); version++) {
                for (String ran : RAN.get(version - 1)) {
                    statement.execute(ran);
                }
                statement.execute("UPDATE " + VERSION_TABLE + " SET version = " + version);
            }
            for (String written : WRITTEN) {
                statement.execute(written);
            }
        }
    }

    /**
     * The scripts of the build on Taffy storage for a dialect, keyed by resource path: script N holds the statements
     * version N of 1.6.0 ran, each ended by a semicolon, so the tables keep their literal names.
     */
    static Map<String, String> scripts(Dialect dialect) {
        Map<String, String> scripts = new HashMap<>();
        for (Migration migration : MIGRATIONS) {
            scripts.put(migration.resource(dialect), script(migration.version()));
        }
        return scripts;
    }

    /** The text of script {@code version}. */
    static String script(int version) {
        StringBuilder script = new StringBuilder();
        for (String ran : RAN.get(version - 1)) {
            script.append(ran).append(";\n");
        }
        return script.toString();
    }

    /** The number of statements of script {@code version}. */
    static int statements(int version) {
        return RAN.get(version - 1).size();
    }
}
