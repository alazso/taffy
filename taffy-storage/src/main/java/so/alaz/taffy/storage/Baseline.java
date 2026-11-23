package so.alaz.taffy.storage;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A version table written by the plugin's own migration runner, which storage takes over on a database that has no
 * version table of storage's yet. It is passed to
 * {@link Storage#start(org.bukkit.plugin.Plugin, so.alaz.taffy.platform.ErrorReports, StorageSettings, java.util.List,
 * Baseline, java.time.Clock)}.
 *
 * <p>The old table holds a column named {@code version} with one row, or one row per applied step, each holding a
 * whole number from 0 to 2147483647 in ASCII digits, or {@code NULL}, which is skipped. Its other columns are not
 * read. The highest version in it, N, is the version the database is at; a table with no version in it is at 0.
 *
 * <p>When storage's version table, which {@link StorageSettings#versionTable(String)} names, does not exist, and
 * neither does a version table 0.1.0 to 0.3.2 wrote for this plugin, the start takes the database over before any
 * migration runs, on MySQL, MariaDB and PostgreSQL while it holds the migration lock:
 * <ol>
 *   <li>It reads N from the old table. When the old table does not exist the database is treated as a new one and
 *       every migration runs; a table named {@code <table>_baseline} is not read. On SQLite, which compares table
 *       names without regard to case, the old table is found in whatever case its name was declared; on MySQL,
 *       MariaDB and PostgreSQL its name must be stored in the lowercase given here.</li>
 *   <li>It stops the start when N is higher than the highest version of the plugin's migrations, or when N is 1 or
 *       more and no migration has version N, and reads every migration script, checking each, before it changes
 *       anything.</li>
 *   <li>On SQLite it copies the database file into the backups folder, as a start does before a migration.</li>
 *   <li>It creates storage's version table and records every migration up to N as applied, with the checksum of its
 *       current script, without running it.</li>
 *   <li>It renames the old table to {@code <table>_baseline}, or, when that name is taken, to the first free name of
 *       {@code <table>_baseline2}, {@code <table>_baseline3} and so on. The old table is renamed, never dropped.</li>
 *   <li>It runs the migrations above N as a start does.</li>
 * </ol>
 *
 * <p>Creating the version table, recording the versions and renaming the old table take effect together: on SQLite
 * and PostgreSQL in one transaction, and on MySQL and MariaDB by emptying and filling
 * {@code <version table>_takeover} and renaming it and the old table in one statement. A start interrupted
 * at any point leaves either no version table of storage's, and the next start takes the database over again, or a
 * complete one.
 *
 * <p>Once storage's version table exists the baseline is ignored, so it may stay in the plugin's start for good.
 *
 * <p>Unless {@link #checkSchema()} asks for it, storage does not check that the database matches the scripts up to
 * N: they are recorded as applied on the plugin's word, and a database that differs from them stays different.
 *
 * <p>Unless {@link #takeOverSameName()} asks for it, an old table whose name is storage's own version table is not
 * a baseline: storage refuses a version table in another layout and stops the start. An old table under any other
 * name is not noticed by storage, so a plugin whose database may hold one passes it here.
 *
 * <p>Instances are immutable: {@link #takeOverSameName()} and {@link #checkSchema()} each return a new baseline.
 */
public final class Baseline {

    private static final Pattern TABLE = Pattern.compile("[a-z_][a-z0-9_]{0,31}");

    private final String table;
    private final boolean sameName;
    private final boolean schemaChecked;

    private Baseline(String table, boolean sameName, boolean schemaChecked) {
        this.table = table;
        this.sameName = sameName;
        this.schemaChecked = schemaChecked;
    }

    /**
     * The version table of the plugin's own migration runner.
     *
     * @param table the full name of the old version table, with no table prefix added: 1 to 32 lowercase letters,
     *     digits and underscores, starting with a letter or underscore. It is written into SQL unquoted, so it must
     *     not be a word the database reserves, such as {@code order} or {@code user}
     * @return the baseline
     * @throws NullPointerException if the table is {@code null}
     * @throws IllegalArgumentException if the table name is out of its range
     */
    public static Baseline versionTable(String table) {
        Objects.requireNonNull(table, "table");
        if (!TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("the version table is \"" + table + "\"; use 1 to 32 lowercase"
                + " letters, digits and underscores, starting with a letter or underscore");
        }
        return new Baseline(table, false, false);
    }

    /**
     * A baseline that also takes the old table over when it has the name of storage's own version table, such as
     * an old table named {@code schema_version} on SQLite, or {@code <prefix>schema_version} on a server whose
     * version table {@link StorageSettings#versionTable(String)} gives that name.
     *
     * <p>When a table of the version table's name exists in another layout than storage's and this baseline names
     * it, the start reads N from it and takes the database over as the class description states, with one
     * difference in order: the old table is renamed to {@code <table>_baseline} first, and storage's version table
     * is then created under the name that became free. On SQLite and PostgreSQL both happen in one transaction with
     * the recording of the versions. On MySQL and MariaDB one statement renames the old table and
     * {@code <version table>_takeover} into place. The old table is renamed, never dropped or rewritten, and a
     * takeover that fails leaves the old table under its name with its rows, so the next start takes over again.
     * On SQLite the old table is found in whatever case its name was declared.
     *
     * <p>A table of the version table's name in storage's layout is storage's version table, and the baseline is
     * ignored. When a version table 0.1.0 to 0.3.2 wrote for this plugin also exists, the start stops as it does
     * without this call.
     *
     * @return a baseline that takes over an old table named as storage's version table
     */
    public Baseline takeOverSameName() {
        return new Baseline(table, true, schemaChecked);
    }

    /**
     * A baseline that compares the database with the scripts it records as applied before it changes anything, and
     * stops the start when the database lacks a table or a column they create.
     *
     * <p>The expected tables and columns are read from the statements of the migration scripts up to N, in order,
     * after {@code ${prefix}} is replaced: {@code CREATE TABLE} with its column list, {@code ALTER TABLE} with
     * {@code ADD}, {@code DROP} and {@code RENAME} of a column, {@code CHANGE} of a column on MySQL and MariaDB,
     * {@code ALTER TABLE ... RENAME TO}, {@code RENAME TABLE} and {@code DROP TABLE}. A temporary table and every
     * other statement, such as {@code CREATE INDEX}, {@code INSERT} or {@code CREATE VIEW}, are not read. A table
     * created without a column list, as with {@code CREATE TABLE ... AS SELECT}, is expected to exist with any
     * columns.
     *
     * <p>In a column list, and after {@code ADD}, the first word of an element names a column unless it starts a
     * constraint ({@code CONSTRAINT}, {@code PRIMARY}, {@code UNIQUE}, {@code FOREIGN}, {@code CHECK},
     * {@code EXCLUDE} on PostgreSQL), an index or a partition on MySQL and MariaDB ({@code KEY}, {@code INDEX},
     * {@code FULLTEXT}, {@code SPATIAL}, {@code PARTITION}) or a {@code PERIOD FOR} on MariaDB. Any other table
     * element is read as a column of that name, and the start stops for that column, which the database does not
     * have. Line comments that start with {@code --} and block comments are skipped, as are line comments that
     * start with {@code #} on MySQL and MariaDB.
     *
     * <p>Names are compared without regard to case and without their quotes. Column types, constraints, indexes
     * and rows are not compared, and tables and columns the scripts do not create are allowed, so the database
     * may hold more than the scripts describe. With N at 0 no script is recorded as applied and nothing is
     * compared.
     *
     * <p>A mismatch completes the future of the start exceptionally with a {@link StorageException} that names the
     * missing tables and columns, before the SQLite backup is taken and with the old version table left as it was.
     *
     * @return a baseline that checks the tables and columns of the database
     */
    public Baseline checkSchema() {
        return new Baseline(table, sameName, true);
    }

    String table() {
        return table;
    }

    boolean sameName() {
        return sameName;
    }

    boolean schemaChecked() {
        return schemaChecked;
    }
}
