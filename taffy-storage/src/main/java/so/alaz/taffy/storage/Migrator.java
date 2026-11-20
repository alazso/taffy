package so.alaz.taffy.storage;

import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.Durations;

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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Brings a database to the newest schema version of a list of {@link Migration}s and records each applied one in
 * the version table, which it creates with {@code CREATE TABLE IF NOT EXISTS} before applying the first pending
 * migration. On SQLite and PostgreSQL that statement runs in the first migration's transaction, so a first
 * migration that fails leaves no version table behind.
 *
 * <p>Every script is read and checked as UTF-8, and every pending one is split into statements, before the backup
 * and before the first migration runs. The checksum recorded for a script is taken over its text without a leading
 * byte order mark and with every line ending as a line feed, and a row that holds the checksum of the script's exact
 * bytes, as 0.1.0 to 0.3.2 recorded it, is accepted as well.
 *
 * <p>On MySQL and MariaDB DDL commits on its own, so a migration's progress is recorded after each statement in
 * {@code statements_done} and a later start resumes after the last recorded statement. Each statement runs in one
 * transaction with its record, so a statement that changes rows of a transactional table and its record commit
 * together. A DDL statement commits before its record, and a start interrupted between the two runs that statement
 * again, so every DDL statement of a MySQL or MariaDB script must be rerunnable. MariaDB has
 * {@code CREATE INDEX IF NOT EXISTS} and {@code ADD COLUMN IF NOT EXISTS}; MySQL has neither, so on MySQL an index
 * belongs inline in its {@code CREATE TABLE IF NOT EXISTS}. An incomplete row keeps the checksum of the script that
 * started it, and a resumed migration whose script changed stops the start.
 *
 * <p>A version table in another layout stops the migration before anything changes.
 *
 * <p>On MySQL, MariaDB and PostgreSQL the version table is read and the migrations run while the connection holds
 * a lock keyed by the database (on PostgreSQL the schema) and the version table, with no socket timeout, so starts
 * that share a database and a version table migrate one at a time. A start waits up to a day for that lock. On
 * SQLite a migration runs with foreign keys off, as SQLite's table rebuild needs, when the connection enforces
 * them, and fails when {@code PRAGMA foreign_key_check} then finds a row that breaks one and that the same check
 * did not find before the migration.
 *
 * <p>When the version table does not exist and its name is not {@code <prefix>schema_version}, the name 0.1.0 to
 * 0.3.2 gave it, a table of that name in storage's layout that records at least one migration with the checksum of
 * one of these scripts is this plugin's earlier version table: its rows are copied into the new version table before
 * the first pending migration runs, and it stays as it was. On a server the start holds the lock of that earlier
 * table too while it reads and copies it.
 *
 * <p>With a {@link Baseline}, no version table and no earlier version table, the database is first taken over from
 * the old version table, as {@link Baseline} describes, after every script is read and before the first pending
 * migration runs.
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

    @FunctionalInterface
    private interface Work {
        void run() throws SQLException, StorageException;
    }

    static final String PREFIX_TOKEN = "${prefix}";

    static final Duration LOCK_WAIT = Duration.ofDays(1);

    private static final String BASELINE_SUFFIX = "_baseline";

    private static final String TAKEOVER_SUFFIX = "_takeover";

    private static final String COLUMNS = "version, name, checksum, statements_done, applied_at";

    // At most 18 ASCII digits, so the value always fits a long.
    private static final Pattern WHOLE = Pattern.compile("[0-9]{1,18}");

    private static final Duration LOCK_POLL = Duration.ofMillis(250);

    private record Applied(String checksum, int statementsDone, boolean complete) {
    }

    /**
     * A migration script: its text without a leading byte order mark, the checksum of its exact bytes and the
     * checksum of its text with every line ending as a line feed.
     */
    private record Script(Migration migration, String path, String text, String raw, String checksum) {

        boolean recordedAs(String stored) {
            return stored.equals(checksum) || stored.equals(raw);
        }
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
    private record Lock(String table, String database, String name, long key) {
    }

    private record Violation(String table, @Nullable Long rowid, String parent, int key) {
    }

    private final Dialect dialect;
    private final String prefix;
    private final String versionTable;
    private final Clock clock;
    private final Logger logger;
    private final List<Migration> migrations;
    private final Function<String, @Nullable InputStream> resources;
    private final @Nullable Baseline baseline;
    private final Duration lockWait;
    private boolean warnedOfBrokenKeys;

    /**
     * A migrator for one database whose version table is {@code <prefix>schema_version}, with no baseline.
     *
     * @throws IllegalArgumentException if the versions of {@code migrations} are not strictly increasing
     */
    Migrator(Dialect dialect, String prefix, Clock clock, Logger logger, List<Migration> migrations,
             Function<String, @Nullable InputStream> resources) {
        this(dialect, prefix, clock, logger, migrations, resources, null);
    }

    /**
     * A migrator for one database whose version table is {@code <prefix>schema_version}, which takes over the
     * version table of {@code baseline} when it is not {@code null}.
     *
     * @throws IllegalArgumentException if the versions of {@code migrations} are not strictly increasing
     */
    Migrator(Dialect dialect, String prefix, Clock clock, Logger logger, List<Migration> migrations,
             Function<String, @Nullable InputStream> resources, @Nullable Baseline baseline) {
        this(dialect, prefix, prefix + StorageSettings.LEGACY_VERSION_TABLE, clock, logger, migrations, resources,
            baseline, LOCK_WAIT);
    }

    /**
     * A migrator for one database that records migrations in {@code versionTable}, takes over the version table of
     * {@code baseline} when it is not {@code null}, and waits {@code lockWait} for the migration lock.
     *
     * @throws IllegalArgumentException if the versions of {@code migrations} are not strictly increasing
     */
    Migrator(Dialect dialect, String prefix, String versionTable, Clock clock, Logger logger,
             List<Migration> migrations, Function<String, @Nullable InputStream> resources,
             @Nullable Baseline baseline, Duration lockWait) {
        List<Migration> copy = List.copyOf(migrations);
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index).version() <= copy.get(index - 1).version()) {
                throw new IllegalArgumentException("migration versions must be strictly increasing, but "
                    + copy.get(index).id() + " follows " + copy.get(index - 1).id());
            }
        }
        this.dialect = dialect;
        this.prefix = prefix;
        this.versionTable = versionTable;
        this.clock = clock;
        this.logger = logger;
        this.migrations = copy;
        this.resources = resources;
        this.baseline = baseline;
        this.lockWait = lockWait;
    }

    void migrate(Connection connection, @Nullable Backup backup) throws SQLException, StorageException {
        if (dialect == Dialect.SQLITE) {
            migrateLocked(connection, backup, tables(connection));
            return;
        }
        int timeout = connection.getNetworkTimeout();
        connection.setNetworkTimeout(Runnable::run, 0);
        Throwable failed = null;
        List<Lock> taken = new ArrayList<>();
        try {
            String database = database(connection);
            taken.add(lock(connection, lockOf(database, versionTable)));
            List<String> tables = tables(connection);
            String earlier = prefix + StorageSettings.LEGACY_VERSION_TABLE;
            if (!earlier.equals(versionTable) && !tables.contains(versionTable) && tables.contains(earlier)) {
                taken.add(lock(connection, lockOf(database, earlier)));
                tables = tables(connection);
            }
            migrateLocked(connection, backup, tables);
        } catch (Throwable failure) {
            failed = failure;
            throw failure;
        } finally {
            Throwable pending = failed;
            for (Lock held : taken.reversed()) {
                cleanUp(pending, () -> unlock(connection, held));
            }
            cleanUp(pending, () -> connection.setNetworkTimeout(Runnable::run, timeout));
        }
    }

    private void migrateLocked(Connection connection, @Nullable Backup backup, List<String> tables)
        throws SQLException, StorageException {
        boolean existing = tables.contains(versionTable);
        if (existing && !layoutMatches(connection, versionTable)) {
            throw new StorageException("the database holds a table named " + versionTable + " that storage did not"
                + " create, so it cannot record migrations there; rename or remove that table, or on MySQL, MariaDB"
                + " and PostgreSQL set another table prefix");
        }
        List<Script> scripts = new ArrayList<>();
        for (Migration migration : migrations) {
            String path = migration.resource(dialect);
            String script = decode(path, read(path));
            scripts.add(new Script(migration, path, withoutMark(script), rawChecksum(script), checksum(script)));
        }
        @Nullable String earlier = existing ? null : earlierVersionTable(connection, tables, scripts);
        String recorded = earlier == null ? versionTable : earlier;
        Map<Integer, Applied> applied = existing || earlier != null ? applied(connection, recorded) : Map.of();
        @Nullable Takeover takeover = existing || earlier != null || baseline == null ? null
            : takeover(connection, tables, baseline);
        if (!existing && earlier == null) {
            warnAboutOtherPrefixes(connection, tables, scripts);
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
        for (Script script : scripts) {
            Migration migration = script.migration();
            if (takeover != null && migration.version() <= takeover.version()) {
                List<String> statements = split(script);
                baselined.add(new Pending(migration, script.checksum(), statements, statements.size()));
                continue;
            }
            Applied done = applied.get(migration.version());
            if (done != null && !script.recordedAs(done.checksum())) {
                throw changed(migration, recorded, done, script.checksum());
            }
            if (done == null || !done.complete()) {
                pending.add(new Pending(migration, script.checksum(), split(script),
                    done == null ? 0 : done.statementsDone()));
            }
        }
        if (pending.isEmpty() && takeover == null && earlier == null) {
            return;
        }
        if (!tables.isEmpty() && backup != null && (!pending.isEmpty() || takeover != null)) {
            backup.run(connection);
        }
        @Nullable String createVersionTable = versionTableDdl(versionTable);
        if (takeover != null) {
            takeOver(connection, takeover, baselined);
            createVersionTable = null;
        }
        if (earlier != null) {
            copy(connection, earlier);
            createVersionTable = null;
        }
        for (Pending migration : pending) {
            apply(connection, createVersionTable, migration);
            createVersionTable = null;
            logger.info("Applied database migration " + migration.migration().id() + ".");
        }
    }

    /**
     * The table {@code <prefix>schema_version} when it is this plugin's version table of 0.1.0 to 0.3.2: another
     * name than the version table, storage's layout, and at least one row whose checksum is the one of the script of
     * its version.
     */
    private @Nullable String earlierVersionTable(Connection connection, List<String> tables, List<Script> scripts)
        throws SQLException {
        String earlier = prefix + StorageSettings.LEGACY_VERSION_TABLE;
        if (earlier.equals(versionTable) || !tables.contains(earlier) || !layoutMatches(connection, earlier)) {
            return null;
        }
        return match(applied(connection, earlier), scripts).same() > 0 ? earlier : null;
    }

    /**
     * How the rows of a version table compare with the scripts: {@code same} rows hold the checksum of the script
     * of their version and {@code different} rows hold another one.
     */
    private record Match(int same, int different) {
    }

    private static Match match(Map<Integer, Applied> rows, List<Script> scripts) {
        int same = 0;
        int different = 0;
        for (Script script : scripts) {
            Applied row = rows.get(script.migration().version());
            if (row != null && script.recordedAs(row.checksum())) {
                same++;
            } else if (row != null) {
                different++;
            }
        }
        return new Match(same, different);
    }

    // The rows reach the version table together: in one transaction where DDL is transactional, and otherwise
    // through a staging table that one RENAME TABLE moves into place. Every step before that rename is rerunnable.
    private void copy(Connection connection, String earlier) throws SQLException, StorageException {
        String rows = " (" + COLUMNS + ") SELECT " + COLUMNS + " FROM " + earlier;
        if (dialect.transactionalDdl()) {
            transaction(connection, () -> {
                execute(connection, versionTableDdl(versionTable));
                execute(connection, "INSERT INTO " + versionTable + rows);
            });
        } else {
            String staging = versionTable + TAKEOVER_SUFFIX;
            connection.setAutoCommit(true);
            execute(connection, versionTableDdl(staging));
            execute(connection, "DELETE FROM " + staging);
            execute(connection, "INSERT INTO " + staging + rows);
            execute(connection, "RENAME TABLE " + staging + " TO " + versionTable);
        }
        logger.info("Copied the migrations " + earlier + " records into " + versionTable + ", which records them"
            + " from now on. " + earlier + " stays as it was and is no longer read.");
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
    private void takeOver(Connection connection, Takeover takeover, List<Pending> baselined)
        throws SQLException, StorageException {
        long now = clock.millis();
        String renameTo = takeover.renameTo();
        if (dialect.transactionalDdl()) {
            transaction(connection, () -> {
                execute(connection, versionTableDdl(versionTable));
                for (Pending step : baselined) {
                    record(connection, versionTable, step.migration(), step.checksum(), step.from(), now);
                }
                execute(connection, "ALTER TABLE " + takeover.source() + " RENAME TO " + renameTo);
            });
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

    /**
     * The checksum recorded for a script: the SHA-256 of its text without a leading byte order mark and with every
     * line ending as a line feed.
     */
    static String checksum(String script) {
        return rawChecksum(withoutMark(script).replace("\r\n", "\n").replace('\r', '\n'));
    }

    /** The checksum 0.1.0 to 0.3.2 recorded for a script: the SHA-256 of its exact bytes. */
    static String rawChecksum(String script) {
        return HexFormat.of().formatHex(sha256(script));
    }

    private static String withoutMark(String script) {
        return script.startsWith("﻿") ? script.substring(1) : script;
    }

    private String versionTableDdl(String table) {
        return switch (dialect) {
            case SQLITE, POSTGRES -> "CREATE TABLE IF NOT EXISTS " + table
                + " (version INTEGER NOT NULL PRIMARY KEY, name VARCHAR(128) NOT NULL, checksum CHAR(64) NOT NULL,"
                + " statements_done INTEGER NOT NULL, applied_at BIGINT)";
            case MYSQL, MARIADB -> "CREATE TABLE IF NOT EXISTS " + table
                + " (version INT NOT NULL PRIMARY KEY, name VARCHAR(128) NOT NULL, checksum CHAR(64) NOT NULL,"
                + " statements_done INT NOT NULL, applied_at BIGINT NULL)"
                + " ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin";
        };
    }

    private void apply(Connection connection, @Nullable String createVersionTable, Pending pending)
        throws SQLException, StorageException {
        Migration migration = pending.migration();
        List<String> statements = pending.statements();
        if (dialect.transactionalDdl()) {
            // PRAGMA foreign_keys has no effect inside a transaction, so it is switched before the transaction.
            boolean foreignKeys = dialect == Dialect.SQLITE && foreignKeysOn(connection);
            Set<Violation> broken = foreignKeys ? brokenKeys(connection, migration) : Set.of();
            if (foreignKeys) {
                execute(connection, "PRAGMA foreign_keys = OFF");
            }
            Throwable failed = null;
            try {
                transaction(connection, () -> {
                    if (createVersionTable != null) {
                        execute(connection, createVersionTable);
                    }
                    for (int index = 0; index < statements.size(); index++) {
                        run(connection, migration, statements, index, "; it was rolled back");
                    }
                    if (foreignKeys) {
                        checkForeignKeys(connection, migration, broken);
                    }
                    record(connection, versionTable, migration, pending.checksum(), statements.size(),
                        clock.millis());
                });
            } catch (Throwable failure) {
                failed = failure;
                throw failure;
            } finally {
                if (foreignKeys) {
                    cleanUp(failed, () -> execute(connection, "PRAGMA foreign_keys = ON"));
                }
            }
            return;
        }
        connection.setAutoCommit(true);
        if (createVersionTable != null) {
            execute(connection, createVersionTable);
        }
        for (int index = pending.from(); index < statements.size(); index++) {
            int statement = index;
            String progress = index == 0 ? "" : "; " + statements(index) + " of it applied, and the next start"
                + " resumes at statement " + (index + 1);
            transaction(connection, () -> {
                run(connection, migration, statements, statement, progress);
                record(connection, versionTable, migration, pending.checksum(), statement + 1, null);
            });
        }
        record(connection, versionTable, migration, pending.checksum(), statements.size(), clock.millis());
    }

    // Commits the work, or rolls it back when it throws, and leaves the connection in autocommit mode.
    private static void transaction(Connection connection, Work work) throws SQLException, StorageException {
        Throwable failed = null;
        try {
            connection.setAutoCommit(false);
            work.run();
            connection.commit();
        } catch (Throwable failure) {
            failed = failure;
            cleanUp(failure, connection::rollback);
            throw failure;
        } finally {
            Throwable rolledBack = failed;
            cleanUp(rolledBack, () -> connection.setAutoCommit(true));
        }
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

    // The rows that break a foreign key before the migration runs, which the check after it does not blame on it.
    private Set<Violation> brokenKeys(Connection connection, Migration migration) throws SQLException {
        Set<Violation> broken = violations(connection);
        if (!broken.isEmpty() && !warnedOfBrokenKeys) {
            warnedOfBrokenKeys = true;
            Set<String> tables = new TreeSet<>();
            for (Violation violation : broken) {
                tables.add(violation.table());
            }
            logger.warning("Before migration " + migration.id() + " ran, the database already held rows whose"
                + " foreign key refers to a missing row, in " + String.join(", ", tables) + ". The migration runs"
                + " and those rows stay as they are.");
        }
        return broken;
    }

    private static Set<Violation> violations(Connection connection) throws SQLException {
        Set<Violation> violations = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA foreign_key_check")) {
            while (result.next()) {
                long rowid = result.getLong(2);
                violations.add(new Violation(result.getString(1), result.wasNull() ? null : rowid,
                    result.getString(3), result.getInt(4)));
            }
        }
        return violations;
    }

    private static void checkForeignKeys(Connection connection, Migration migration, Set<Violation> before)
        throws SQLException, StorageException {
        for (Violation violation : violations(connection)) {
            if (!before.contains(violation)) {
                throw new StorageException("migration " + migration.id() + " leaves a row of " + violation.table()
                    + " whose foreign key refers to a missing row of " + violation.parent()
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

    // The current database on MySQL and MariaDB, and the current schema on PostgreSQL.
    private String database(Connection connection) throws SQLException, StorageException {
        if (dialect == Dialect.POSTGRES) {
            String schema = current(connection, "SELECT current_schema()");
            if (schema == null) {
                throw new StorageException("the connection has no current schema, so the migration lock for "
                    + versionTable + " cannot be named; set a search_path");
            }
            return schema;
        }
        String database = current(connection, "SELECT DATABASE()");
        if (database == null) {
            throw new StorageException("the connection has no current database, so the migration lock for "
                + versionTable + " cannot be named; name the database in the connection settings");
        }
        return database;
    }

    private Lock lockOf(String database, String table) {
        return dialect == Dialect.POSTGRES
            ? new Lock(table, database, "", lockKey(database, table))
            : new Lock(table, database, lockName(database, table), 0);
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

    private Lock lock(Connection connection, Lock lock) throws SQLException, StorageException {
        if (dialect == Dialect.POSTGRES) {
            // pg_advisory_lock waits with no limit, so the lock is tried until the wait has passed.
            long begun = System.nanoTime();
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                statement.setLong(1, lock.key());
                while (!granted(statement)) {
                    if (System.nanoTime() - begun >= lockWait.toNanos()) {
                        throw held(lock);
                    }
                    pause(lock);
                }
            }
            return lock;
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            statement.setString(1, lock.name());
            statement.setLong(2, lockWait.toSeconds());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw held(lock);
                }
            }
        }
        return lock;
    }

    private static boolean granted(PreparedStatement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery()) {
            return result.next() && result.getBoolean(1);
        }
    }

    private void pause(Lock lock) throws StorageException {
        try {
            Thread.sleep(LOCK_POLL);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new StorageException("interrupted while waiting for the migration lock of " + lock.table()
                + " in database " + lock.database(), interrupted);
        }
    }

    private StorageException held(Lock lock) {
        String waited = lockWait.equals(LOCK_WAIT) ? "a day" : Durations.format(lockWait);
        return new StorageException("another start has held the migration lock of " + lock.table()
            + " in database " + lock.database() + " for " + waited + "; restart once the other server has"
            + " started");
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

    private void record(Connection connection, String table, Migration migration, String checksum,
                        int statementsDone, @Nullable Long appliedAt) throws SQLException {
        String sql = dialect.upsert(table,
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

    private static Map<Integer, Applied> applied(Connection connection, String table) throws SQLException {
        Map<Integer, Applied> applied = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "SELECT version, checksum, statements_done, applied_at FROM " + table)) {
            while (result.next()) {
                result.getLong("applied_at");
                boolean complete = !result.wasNull();
                applied.put(result.getInt("version"),
                    new Applied(result.getString("checksum"), result.getInt("statements_done"), complete));
            }
        }
        return applied;
    }

    // Runs only when the database holds neither the version table nor this plugin's earlier one. A version table
    // counts as this plugin's when every row of a version these scripts have holds the checksum of that script, and
    // at least one row does.
    private void warnAboutOtherPrefixes(Connection connection, List<String> tables, List<Script> scripts) {
        Set<String> others = new LinkedHashSet<>();
        for (String table : tables) {
            @Nullable String other = otherPrefix(table);
            if (other != null && !other.equals(prefix) && ours(connection, table, scripts)) {
                others.add(other);
            }
        }
        for (String other : others) {
            logger.warning("The database holds tables under the prefix \"" + other
                + "\", but storage uses \"" + prefix + "\", so the plugin starts with empty tables. Set"
                + " the table prefix to \"" + other + "\" and restart to use that data.");
        }
    }

    // The prefix under which the table would be this plugin's version table, or its earlier one.
    private @Nullable String otherPrefix(String table) {
        if (versionTable.startsWith(prefix)) {
            String tail = versionTable.substring(prefix.length());
            if (!tail.isEmpty() && table.endsWith(tail)) {
                return table.substring(0, table.length() - tail.length());
            }
        }
        String earlier = StorageSettings.LEGACY_VERSION_TABLE;
        return table.endsWith(earlier) ? table.substring(0, table.length() - earlier.length()) : null;
    }

    private static boolean ours(Connection connection, String table, List<Script> scripts) {
        try {
            probe(connection, table);
            Match match = match(applied(connection, table), scripts);
            return match.same() > 0 && match.different() == 0;
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

    private static List<String> split(Script script) throws StorageException {
        try {
            return SqlScript.statements(script.text());
        } catch (IllegalArgumentException malformed) {
            throw new StorageException(script.path() + " cannot be split into statements: "
                + malformed.getMessage(), malformed);
        }
    }

    private static StorageException changed(Migration migration, String table, Applied done, String checksum) {
        String checksums = " (stored checksum " + done.checksum() + ", now " + checksum + ")";
        if (done.complete()) {
            return new StorageException("migration " + migration.id() + " changed after it ran on this database"
                + checksums + "; install the plugin version that applied it again, or restore a backup of the"
                + " database");
        }
        return new StorageException("migration " + migration.id() + " stopped after "
            + statements(done.statementsDone()) + " on an earlier start and has changed since" + checksums
            + "; undo what it applied and"
            + " delete version " + migration.version() + " from " + table + ", or install the plugin"
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
