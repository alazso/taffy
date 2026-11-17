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
 * <p>When storage's version table {@code <prefix>schema_version} does not exist, the start takes the database over
 * before any migration runs, on MySQL, MariaDB and PostgreSQL while it holds the migration lock:
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
 * {@code <prefix>schema_version_takeover} and renaming it and the old table in one statement. A start interrupted
 * at any point leaves either no version table of storage's, and the next start takes the database over again, or a
 * complete one.
 *
 * <p>Once storage's version table exists the baseline is ignored, so it may stay in the plugin's start for good.
 *
 * <p>Storage does not check that the database matches the scripts up to N: they are recorded as applied on the
 * plugin's word, and a database that differs from them stays different. An old table whose name is storage's own
 * version table is not a baseline: storage refuses a version table in another layout and stops the start. An old
 * table under any other name is not noticed by storage, so a plugin whose database may hold one passes it here.
 */
public final class Baseline {

    private static final Pattern TABLE = Pattern.compile("[a-z_][a-z0-9_]{0,31}");

    private final String table;

    private Baseline(String table) {
        this.table = table;
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
        return new Baseline(table);
    }

    String table() {
        return table;
    }
}
