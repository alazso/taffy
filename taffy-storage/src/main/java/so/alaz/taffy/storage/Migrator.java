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
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Pattern;

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
 * <p>A version table in another layout stops the migration before anything changes.
 *
 * <p>On MySQL, MariaDB and PostgreSQL the version table is read and the migrations run while the connection holds
 * a lock keyed by the database (on PostgreSQL the schema) and the version table, with no socket timeout, so starts
 * that share a database migrate one at a time. On SQLite a migration runs with foreign keys off, as SQLite's table
 * rebuild needs, when the connection enforces them, and fails when {@code PRAGMA foreign_key_check} then finds a
 * row that breaks one.
 *
 * <p>With a {@link Baseline} and no version table yet, the database is first taken over from the old version table,
 * as {@link Baseline} describes, after every script is read and before the first pending migration runs.
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

    private static final String BASELINE_SUFFIX = "_baseline";

    private static final String TAKEOVER_SUFFIX = "_takeover";

    // At most 18 ASCII digits, so the value always fits a long.
    private static final Pattern WHOLE = Pattern.compile("[0-9]{1,18}");

    private static final long LOCK_WAIT_SECONDS = Duration.ofDays(1).toSeconds();

    private record Applied(String checksum, int statementsDone, boolean complete) {
    }

    private record Pending(Migration migration, String checksum, List<String> statements, int from) {
    }

    /** The old version table, the free name it is renamed to, and the highest version it records. */
    private record Takeover(String source, String renameTo, int version) {
    }

    /**
     * The migration lock of one version table in one database: {@code name} is the lock name on MySQL and MariaDB,
     * and {@code key} the advisory lock key on PostgreSQL, where {@code database} is the schema.
     */
    private record Lock(String database, String name, long key) {
    }

    private final Dialect dialect;
    private final String prefix;
    private final Clock clock;
    private final Logger logger;
    private final List<Migration> migrations;
    private final Function<String, @Nullable InputStream> resources;
    private final @Nullable Baseline baseline;

    /**
     * A migrator for one database, with no baseline.
     *
     * @throws IllegalArgumentException if the versions of {@code migrations} are not strictly increasing
     */
    Migrator(Dialect dialect, String prefix, Clock clock, Logger logger, List<Migration> migrations,
             Function<String, @Nullable InputStream> resources) {
        this(dialect, prefix, clock, logger, migrations, resources, null);
    }

    /**
     * A migrator for one database that takes over the version table of {@code baseline} when it is not
     * {@code null}.
     *
     * @throws IllegalArgumentException if the versions of {@code migrations} are not strictly increasing
     */
    Migrator(Dialect dialect, String prefix, Clock clock, Logger logger, List<Migration> migrations,
             Function<String, @Nullable InputStream> resources, @Nullable Baseline baseline) {
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
        this.baseline = baseline;
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
        Lock taken = null;
        try {
            Lock named = lockOf(connection, versionTable);
            lock(connection, versionTable, named);
            taken = named;
            migrateLocked(connection, backup);
        } catch (Throwable failure) {
            failed = failure;
            throw failure;
        } finally {
            Throwable pending = failed;
            Lock held = taken;
            if (held != null) {
                cleanUp(pending, () -> unlock(connection, held));
            }
            cleanUp(pending, () -> connection.setNetworkTimeout(Runnable::run, timeout));
        }
    }

    private void migrateLocked(Connection connection, @Nullable Backup backup) throws SQLException, StorageException {
        String versionTable = prefix + VERSION_TABLE;
        List<String> tables = tables(connection);
        boolean existing = tables.contains(versionTable);
        if (existing && !layoutMatches(connection, versionTable)) {
            throw new StorageException("the database holds a table named " + versionTable + " that storage did not"
                + " create, so it cannot record migrations there; rename or remove that table, or on MySQL, MariaDB"
                + " and PostgreSQL set another table prefix");
        }
        Map<Integer, Applied> applied = existing ? applied(connection, versionTable) : Map.of();
        @Nullable Takeover takeover = existing || baseline == null ? null : takeover(connection, tables, baseline);
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
        if (takeover != null && takeover.version() > known) {
            throw new StorageException("the table " + takeover.source() + " records schema version "
                + takeover.version() + ", but this version of the plugin knows schema versions up to " + known
                + "; install the plugin version that set up the database, or restore a backup of the database");
        }
        if (takeover != null && takeover.version() > 0
            && migrations.stream().noneMatch(migration -> migration.version() == takeover.version())) {
            throw new StorageException("the table " + takeover.source() + " records schema version "
                + takeover.version() + ", but this version of the plugin has no migration with that version; install"
                + " the plugin version that set up the database, or restore a backup of the database");
        }
        List<Pending> pending = new ArrayList<>();
        List<Pending> baselined = new ArrayList<>();
        for (Migration migration : migrations) {
            String path = migration.resource(dialect);
            String script = decode(path, read(path));
            String checksum = checksum(script);
            if (takeover != null && migration.version() <= takeover.version()) {
                List<String> statements = split(path, script);
                baselined.add(new Pending(migration, checksum, statements, statements.size()));
                continue;
            }
            Applied done = applied.get(migration.version());
            if (done != null && !done.checksum().equals(checksum)) {
                throw changed(migration, versionTable, done, checksum);
            }
            if (done == null || !done.complete()) {
                pending.add(new Pending(migration, checksum, split(path, script),
                    done == null ? 0 : done.statementsDone()));
            }
        }
        if (pending.isEmpty() && takeover == null) {
            return;
        }
        if (!tables.isEmpty() && backup != null) {
            backup.run(connection);
        }
        @Nullable String createVersionTable = versionTableDdl(versionTable);
        if (takeover != null) {
            takeOver(connection, versionTable, takeover, baselined);
            createVersionTable = null;
        }
        for (Pending migration : pending) {
            apply(connection, versionTable, createVersionTable, migration);
            createVersionTable = null;
            logger.info("Applied database migration " + migration.migration().id() + ".");
        }
    }

    private @Nullable Takeover takeover(Connection connection, List<String> tables, Baseline from)
        throws SQLException, StorageException {
        String old = from.table();
        @Nullable String source = tables.contains(old) ? old
            : tables.stream().filter(table -> sameTable(table, old)).findFirst().orElse(null);
        if (source == null) {
            return null;
        }
        String renamed = old + BASELINE_SUFFIX;
        String free = renamed;
        for (int suffix = 2; taken(tables, free); suffix++) {
            free = renamed + suffix;
        }
        return new Takeover(source, free, highest(connection, source));
    }

    private boolean taken(List<String> tables, String name) {
        return tables.stream().anyMatch(table -> table.equals(name) || sameTable(table, name));
    }

    /**
     * Whether {@code listed}, a name from the table list, names the same table as {@code name}, a lowercase ASCII
     * name. SQLite compares table names without regard to ASCII case and lists them as they were declared.
     */
    private boolean sameTable(String listed, String name) {
        return dialect == Dialect.SQLITE && listed.chars().allMatch(c -> c < 0x80) && listed.equalsIgnoreCase(name);
    }

    private int highest(Connection connection, String table) throws SQLException, StorageException {
        int highest = 0;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT version FROM " + table)) {
            while (result.next()) {
                String value = result.getString(1);
                if (value != null) {
                    highest = Math.max(highest, version(table, value));
                }
            }
        } catch (SQLException refused) {
            if (missingColumn(refused)) {
                throw new StorageException("the table " + table + " has no version column, so storage cannot read"
                    + " the schema version from it", refused);
            }
            throw refused;
        }
        return highest;
    }

    private static int version(String table, String value) throws StorageException {
        String digits = value.strip();
        if (WHOLE.matcher(digits).matches() && Long.parseLong(digits) <= Integer.MAX_VALUE) {
            return Integer.parseInt(digits);
        }
        throw new StorageException("the table " + table + " holds the version \"" + value + "\", which is not"
            + " a whole number from 0 to " + Integer.MAX_VALUE + ", so storage cannot read the schema version from"
            + " it");
    }

    // Creating the version table, recording the versions and renaming the old table take effect together: in one
    // transaction where DDL is transactional, and otherwise through a staging table that one RENAME TABLE moves into
    // place with the old table. Every step before that rename is rerunnable.
    private void takeOver(Connection connection, String versionTable, Takeover takeover, List<Pending> baselined)
        throws SQLException {
        long now = clock.millis();
        String renameTo = takeover.renameTo();
        if (dialect.transactionalDdl()) {
            Throwable failed = null;
            try {
                connection.setAutoCommit(false);
                execute(connection, versionTableDdl(versionTable));
                for (Pending step : baselined) {
                    record(connection, versionTable, step.migration(), step.checksum(), step.from(), now);
                }
                execute(connection, "ALTER TABLE " + takeover.source() + " RENAME TO " + renameTo);
                connection.commit();
            } catch (Throwable failure) {
                failed = failure;
                cleanUp(failure, connection::rollback);
                throw failure;
            } finally {
                Throwable rolledBack = failed;
                cleanUp(rolledBack, () -> connection.setAutoCommit(true));
            }
        } else {
            String staging = versionTable + TAKEOVER_SUFFIX;
            connection.setAutoCommit(true);
            execute(connection, versionTableDdl(staging));
            execute(connection, "DELETE FROM " + staging);
            for (Pending step : baselined) {
                record(connection, staging, step.migration(), step.checksum(), step.from(), now);
            }
            execute(connection, "RENAME TABLE " + staging + " TO " + versionTable + ", " + takeover.source()
                + " TO " + renameTo);
        }
        logger.info("Took over schema version " + takeover.version() + " from " + takeover.source()
            + ", now renamed to " + renameTo + ".");
    }

    static String checksum(String script) {
        return HexFormat.of().formatHex(sha256(script));
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

    private Lock lockOf(Connection connection, String versionTable) throws SQLException, StorageException {
        if (dialect == Dialect.POSTGRES) {
            String schema = current(connection, "SELECT current_schema()");
            if (schema == null) {
                throw new StorageException("the connection has no current schema, so the migration lock for "
                    + versionTable + " cannot be named; set a search_path");
            }
            return new Lock(schema, "", lockKey(schema, versionTable));
        }
        String database = current(connection, "SELECT DATABASE()");
        if (database == null) {
            throw new StorageException("the connection has no current database, so the migration lock for "
                + versionTable + " cannot be named; name the database in the connection settings");
        }
        return new Lock(database, lockName(database, versionTable), 0);
    }

    private static @Nullable String current(Connection connection, String query) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(query);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getString(1) : null;
        }
    }

    // Every Taffy version must derive the same name for the same database and version table. The database is
    // lowercased, so two spellings of one database on a case-insensitive server name one lock. The name is
    // 46 characters, within MySQL's limit of 64.
    static String lockName(String database, String versionTable) {
        byte[] digest = sha256(database.toLowerCase(Locale.ROOT) + "." + versionTable);
        return "taffy_" + HexFormat.of().formatHex(digest).substring(0, 40);
    }

    // Every Taffy version must derive the same key for the same schema and version table: the first 8 bytes of the
    // SHA-256 of the schema and the version table name, joined by a dot.
    static long lockKey(String schema, String versionTable) {
        return ByteBuffer.wrap(sha256(schema + "." + versionTable)).getLong();
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("the JVM has no SHA-256", missing);
        }
    }

    private void lock(Connection connection, String versionTable, Lock lock) throws SQLException, StorageException {
        if (dialect == Dialect.POSTGRES) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
                statement.setLong(1, lock.key());
                statement.execute();
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            statement.setString(1, lock.name());
            statement.setLong(2, LOCK_WAIT_SECONDS);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new StorageException("another start has held the migration lock of " + versionTable
                        + " in database " + lock.database() + " for a day; restart once the other server has"
                        + " started");
                }
            }
        }
    }

    private void unlock(Connection connection, Lock lock) throws SQLException {
        boolean postgres = dialect == Dialect.POSTGRES;
        try (PreparedStatement statement = connection.prepareStatement(
            postgres ? "SELECT pg_advisory_unlock(?)" : "SELECT RELEASE_LOCK(?)")) {
            if (postgres) {
                statement.setLong(1, lock.key());
            } else {
                statement.setString(1, lock.name());
            }
            statement.execute();
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
        try {
            probe(connection, table);
            return true;
        } catch (SQLException otherLayout) {
            return false;
        }
    }

    // Only a missing column counts as another layout; any other refusal, such as a denied read, a damaged file or
    // a lost connection, is rethrown.
    private boolean layoutMatches(Connection connection, String table) throws SQLException {
        try {
            probe(connection, table);
            return true;
        } catch (SQLException refused) {
            if (missingColumn(refused)) {
                return false;
            }
            throw refused;
        }
    }

    // SQLState 42S22 is MySQL's and MariaDB's unknown column and 42703 PostgreSQL's undefined column; SQLite
    // reports a missing column only in its message.
    private boolean missingColumn(SQLException refused) {
        String state = refused.getSQLState();
        if ("42S22".equals(state) || "42703".equals(state)) {
            return true;
        }
        String message = refused.getMessage();
        return dialect == Dialect.SQLITE && message != null && message.contains("no such column");
    }

    private static void probe(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeQuery("SELECT version, checksum, statements_done, applied_at FROM " + table
                + " WHERE 1 = 0").close();
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
