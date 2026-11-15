package so.alaz.taffy.storage;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Brings a database to the newest schema version of a list of {@link Migration}s and records each applied one in
 * {@code <prefix>schema_version}, which it creates with {@code CREATE TABLE IF NOT EXISTS} before applying the
 * first pending migration. On SQLite and PostgreSQL that statement runs in the first migration's transaction, so a
 * first migration that fails leaves no version table behind.
 *
 * <p>Every pending script is read, checked as UTF-8 and split into statements before the backup and before the
 * first migration runs.
 *
 * <p>On MySQL and MariaDB DDL commits on its own, so a migration's progress is recorded after each statement in
 * {@code statements_done} and a later start resumes after the last recorded statement. A start interrupted between
 * a statement and its record runs that statement again, so every statement of a MySQL or MariaDB script must be
 * rerunnable. MariaDB has {@code CREATE INDEX IF NOT EXISTS} and {@code ADD COLUMN IF NOT EXISTS}; MySQL has
 * neither, so on MySQL an index belongs inline in its {@code CREATE TABLE IF NOT EXISTS}. An incomplete row keeps
 * the checksum of the script that started it, and a resumed migration whose script changed stops the start.
 *
 * <p>On MySQL, MariaDB and PostgreSQL the version table is read and the migrations run while the connection holds
 * a database lock named after the version table, with no socket timeout, so starts that share a database migrate
 * one at a time. On SQLite a migration runs with foreign keys off, as SQLite's table rebuild needs, when the
 * connection enforces them, and fails when {@code PRAGMA foreign_key_check} then finds a row that breaks one.
 */
final class Migrator {

    @FunctionalInterface
    interface Backup {
        void run(Connection connection) throws SQLException, StorageException;
    }

    @FunctionalInterface
    private interface Step {
        void run() throws SQLException;
    }

    static final String PREFIX_TOKEN = "${prefix}";

    private static final String VERSION_TABLE = "schema_version";

    private static final long LOCK_WAIT_SECONDS = Duration.ofDays(1).toSeconds();

    private record Applied(String checksum, int statementsDone, boolean complete) {
    }

    private record Pending(Migration migration, String checksum, List<String> statements, int from) {
    }

    private final Dialect dialect;
    private final String prefix;
    private final Clock clock;
    private final Logger logger;
    private final List<Migration> migrations;
    private final Function<String, @Nullable InputStream> resources;

    /**
     * A migrator for one database.
     *
     * @throws IllegalArgumentException if the versions of {@code migrations} are not strictly increasing
     */
    Migrator(Dialect dialect, String prefix, Clock clock, Logger logger, List<Migration> migrations,
             Function<String, @Nullable InputStream> resources) {
        List<Migration> copy = List.copyOf(migrations);
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index).version() <= copy.get(index - 1).version()) {
                throw new IllegalArgumentException("migration versions must be strictly increasing, but "
                    + copy.get(index).id() + " follows " + copy.get(index - 1).id());
            }
        }
        this.dialect = dialect;
        this.prefix = prefix;
        this.clock = clock;
        this.logger = logger;
        this.migrations = copy;
        this.resources = resources;
    }

    void migrate(Connection connection, @Nullable Backup backup) throws SQLException, StorageException {
        if (dialect == Dialect.SQLITE) {
            migrateLocked(connection, backup);
            return;
        }
        String versionTable = prefix + VERSION_TABLE;
        int timeout = connection.getNetworkTimeout();
        connection.setNetworkTimeout(Runnable::run, 0);
        Throwable failed = null;
        boolean locked = false;
        try {
            lock(connection, versionTable);
            locked = true;
            migrateLocked(connection, backup);
        } catch (Throwable failure) {
            failed = failure;
            throw failure;
        } finally {
            Throwable pending = failed;
            if (locked) {
                cleanUp(pending, () -> unlock(connection, versionTable));
            }
            cleanUp(pending, () -> connection.setNetworkTimeout(Runnable::run, timeout));
        }
    }

    private void migrateLocked(Connection connection, @Nullable Backup backup) throws SQLException, StorageException {
        String versionTable = prefix + VERSION_TABLE;
        List<String> tables = tables(connection);
        boolean existing = tables.contains(versionTable);
        Map<Integer, Applied> applied = existing ? applied(connection, versionTable) : Map.of();
        if (!existing) {
            warnAboutOtherPrefixes(connection, tables);
        }
        int known = migrations.stream().mapToInt(Migration::version).max().orElse(0);
        int newest = applied.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (newest > known) {
            throw new StorageException("the database is at schema version " + newest
                + ", set up by a newer version of the plugin; this version knows schema versions up to " + known
                + "; install the newer version again, or restore a backup of the database");
        }
        List<Pending> pending = new ArrayList<>();
        for (Migration migration : migrations) {
            String path = migration.resource(dialect);
            String script = decode(path, read(path));
            String checksum = checksum(script);
            Applied done = applied.get(migration.version());
            if (done != null && !done.checksum().equals(checksum)) {
                throw changed(migration, versionTable, done, checksum);
            }
            if (done == null || !done.complete()) {
                pending.add(new Pending(migration, checksum, split(path, script),
                    done == null ? 0 : done.statementsDone()));
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        if (!tables.isEmpty() && backup != null) {
            backup.run(connection);
        }
        @Nullable String createVersionTable = versionTableDdl(versionTable);
        for (Pending migration : pending) {
            apply(connection, versionTable, createVersionTable, migration);
            createVersionTable = null;
            logger.info("Applied database migration " + migration.migration().id() + ".");
        }
    }

    static String checksum(String script) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(script.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("the JVM has no SHA-256", missing);
        }
    }

    private String versionTableDdl(String versionTable) {
        return switch (dialect) {
            case SQLITE, POSTGRES -> "CREATE TABLE IF NOT EXISTS " + versionTable
                + " (version INTEGER NOT NULL PRIMARY KEY, name VARCHAR(128) NOT NULL, checksum CHAR(64) NOT NULL,"
                + " statements_done INTEGER NOT NULL, applied_at BIGINT)";
            case MYSQL, MARIADB -> "CREATE TABLE IF NOT EXISTS " + versionTable
                + " (version INT NOT NULL PRIMARY KEY, name VARCHAR(128) NOT NULL, checksum CHAR(64) NOT NULL,"
                + " statements_done INT NOT NULL, applied_at BIGINT NULL)"
                + " ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin";
        };
    }

    private void apply(Connection connection, String versionTable, @Nullable String createVersionTable,
                       Pending pending) throws SQLException, StorageException {
        Migration migration = pending.migration();
        List<String> statements = pending.statements();
        if (dialect.transactionalDdl()) {
            // PRAGMA foreign_keys has no effect inside a transaction, so it is switched before the transaction.
            boolean foreignKeys = dialect == Dialect.SQLITE && foreignKeysOn(connection);
            if (foreignKeys) {
                execute(connection, "PRAGMA foreign_keys = OFF");
            }
            Throwable failed = null;
            try {
                connection.setAutoCommit(false);
                if (createVersionTable != null) {
                    execute(connection, createVersionTable);
                }
                for (int index = 0; index < statements.size(); index++) {
                    run(connection, migration, statements, index, "; it was rolled back");
                }
                if (foreignKeys) {
                    checkForeignKeys(connection, migration);
                }
                record(connection, versionTable, migration, pending.checksum(), statements.size(), clock.millis());
                connection.commit();
            } catch (Throwable failure) {
                failed = failure;
                cleanUp(failure, connection::rollback);
                throw failure;
            } finally {
                Throwable rolledBack = failed;
                cleanUp(rolledBack, () -> connection.setAutoCommit(true));
                if (foreignKeys) {
                    cleanUp(rolledBack, () -> execute(connection, "PRAGMA foreign_keys = ON"));
                }
            }
            return;
        }
        connection.setAutoCommit(true);
        if (createVersionTable != null) {
            execute(connection, createVersionTable);
        }
        for (int index = pending.from(); index < statements.size(); index++) {
            String progress = index == 0 ? "" : "; " + statements(index) + " of it applied, and the next start"
                + " resumes at statement " + (index + 1);
            run(connection, migration, statements, index, progress);
            record(connection, versionTable, migration, pending.checksum(), index + 1, null);
        }
        record(connection, versionTable, migration, pending.checksum(), statements.size(), clock.millis());
    }

    private void run(Connection connection, Migration migration, List<String> statements, int index,
                     String progress) throws SQLException {
        try {
            execute(connection, statements.get(index).replace(PREFIX_TOKEN, prefix));
        } catch (SQLException refused) {
            throw new SQLException("migration " + migration.id() + " failed at statement " + (index + 1) + " of "
                + statements.size() + progress + ": " + refused.getMessage(), refused.getSQLState(),
                refused.getErrorCode(), refused);
        }
    }

    private static boolean foreignKeysOn(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA foreign_keys")) {
            return result.next() && result.getInt(1) == 1;
        }
    }

    private static void checkForeignKeys(Connection connection, Migration migration)
        throws SQLException, StorageException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (result.next()) {
                throw new StorageException("migration " + migration.id() + " leaves a row of " + result.getString(1)
                    + " whose foreign key refers to a missing row of " + result.getString(3)
                    + "; it was rolled back");
            }
        }
    }

    // Runs a step that follows a failure, adding the step's own failure to that failure instead of replacing it.
    private static void cleanUp(@Nullable Throwable pending, Step step) throws SQLException {
        try {
            step.run();
        } catch (SQLException failure) {
            if (pending == null) {
                throw failure;
            }
            pending.addSuppressed(failure);
        }
    }

    private void lock(Connection connection, String versionTable) throws SQLException, StorageException {
        if (dialect == Dialect.POSTGRES) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
                statement.setLong(1, lockKey(versionTable));
                statement.execute();
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            statement.setString(1, versionTable);
            statement.setLong(2, LOCK_WAIT_SECONDS);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new StorageException("another start has held the migration lock of " + versionTable
                        + " for a day; restart once the other server has started");
                }
            }
        }
    }

    private void unlock(Connection connection, String versionTable) throws SQLException {
        boolean postgres = dialect == Dialect.POSTGRES;
        try (PreparedStatement statement = connection.prepareStatement(
            postgres ? "SELECT pg_advisory_unlock(?)" : "SELECT RELEASE_LOCK(?)")) {
            if (postgres) {
                statement.setLong(1, lockKey(versionTable));
            } else {
                statement.setString(1, versionTable);
            }
            statement.execute();
        }
    }

    // A PostgreSQL advisory lock is keyed by a number: the first 8 bytes of the SHA-256 of the version table name.
    private static long lockKey(String versionTable) {
        try {
            return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
                .digest(versionTable.getBytes(StandardCharsets.UTF_8))).getLong();
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("the JVM has no SHA-256", missing);
        }
    }

    private void record(Connection connection, String versionTable, Migration migration, String checksum,
                        int statementsDone, @Nullable Long appliedAt) throws SQLException {
        String sql = dialect.upsert(versionTable,
            List.of("version", "name", "checksum", "statements_done", "applied_at"),
            List.of("version"),
            List.of("name", "checksum", "statements_done", "applied_at"));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            Params params = new Params(statement, dialect);
            params.integer(migration.version()).text(migration.name()).text(checksum).integer(statementsDone)
                .integer(appliedAt);
            statement.executeUpdate();
        }
    }

    private List<String> tables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(dialect.listTables())) {
            while (result.next()) {
                tables.add(result.getString(1));
            }
        }
        return tables;
    }

    private static Map<Integer, Applied> applied(Connection connection, String versionTable) throws SQLException {
        Map<Integer, Applied> applied = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "SELECT version, checksum, statements_done, applied_at FROM " + versionTable)) {
            while (result.next()) {
                result.getLong("applied_at");
                boolean complete = !result.wasNull();
                applied.put(result.getInt("version"),
                    new Applied(result.getString("checksum"), result.getInt("statements_done"), complete));
            }
        }
        return applied;
    }

    // Runs only when the table list holds no version table of this prefix.
    private void warnAboutOtherPrefixes(Connection connection, List<String> tables) {
        for (String table : tables) {
            if (table.endsWith(VERSION_TABLE) && ours(connection, table)) {
                String other = table.substring(0, table.length() - VERSION_TABLE.length());
                logger.warning("The database holds tables under the prefix \"" + other
                    + "\", but storage uses \"" + prefix + "\", so the plugin starts with empty tables. Set"
                    + " the table prefix to \"" + other + "\" and restart to use that data.");
            }
        }
    }

    private static boolean ours(Connection connection, String table) {
        try (Statement statement = connection.createStatement()) {
            statement.executeQuery("SELECT version, checksum, statements_done, applied_at FROM " + table + " WHERE 1 = 0")
                .close();
            return true;
        } catch (SQLException otherLayout) {
            return false;
        }
    }

    private byte[] read(String path) throws StorageException {
        try (InputStream in = resources.apply(path)) {
            if (in == null) {
                throw new StorageException("the plugin has no " + path);
            }
            return in.readAllBytes();
        } catch (IOException | UncheckedIOException failure) {
            throw new StorageException("cannot read " + path, failure);
        }
    }

    private static String decode(String path, byte[] script) throws StorageException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(script))
                .toString();
        } catch (CharacterCodingException invalid) {
            throw new StorageException(path + " is not valid UTF-8", invalid);
        }
    }

    private static List<String> split(String path, String script) throws StorageException {
        try {
            return SqlScript.statements(script);
        } catch (IllegalArgumentException malformed) {
            throw new StorageException(path + " cannot be split into statements: " + malformed.getMessage(),
                malformed);
        }
    }

    private static StorageException changed(Migration migration, String versionTable, Applied done,
                                            String checksum) {
        String checksums = " (stored checksum " + done.checksum() + ", now " + checksum + ")";
        if (done.complete()) {
            return new StorageException("migration " + migration.id() + " changed after it ran on this database"
                + checksums + "; install the plugin version that applied it again, or restore a backup of the"
                + " database");
        }
        return new StorageException("migration " + migration.id() + " stopped after "
            + statements(done.statementsDone()) + " on an earlier start and has changed since" + checksums
            + "; undo what it applied and"
            + " delete version " + migration.version() + " from " + versionTable + ", or install the plugin"
            + " version that started it again and fix what made it fail");
    }

    private static String statements(int count) {
        return count == 1 ? "1 statement" : count + " statements";
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
