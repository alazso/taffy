package so.alaz.taffy.storage;

import java.util.Objects;

/**
 * One step of a plugin's database layout: a version number and a name that together locate its script.
 *
 * <p>The script for a dialect is the resource {@code db/migration/<dialect id>/V<version>__<name>.sql}, for
 * example {@code db/migration/mariadb/V2__accounts.sql}, where the dialect id is {@link Dialect#id()}. A plugin
 * lists its migrations with strictly increasing versions and ships one script per migration for every dialect
 * it offers. The version table that records the applied migrations, whose name
 * {@link StorageSettings#versionTable(String)} describes, is created by storage, so no script needs to create it; a
 * script that creates it with {@code CREATE TABLE IF NOT EXISTS} still runs. A table of that name in another layout
 * stops the start before anything changes.
 *
 * <p>A script is read as UTF-8, and one that is not valid UTF-8 stops the start. It is split into statements by
 * the rules of a migration script: a statement ends at a semicolon that ends its line, and a line holds at most
 * one statement. A block comment outside a quoted string is kept and sent with its statement, and nothing inside
 * it is read. A script that is still to run stops the start, before any migration runs, when it holds what those
 * rules cannot read: a single-quoted string that does not end on the line it starts, a block comment that never
 * closes or holds another block comment, dollar quoting ({@code $$}) outside a quoted string and outside a block
 * comment, or on MySQL and MariaDB a quote directly after a backslash. A body of statements between {@code BEGIN}
 * and {@code END}, as in a trigger or a procedure, and a PostgreSQL escape string ({@code E'...'}) that holds
 * {@code \'} are not supported. Before a script runs, every {@code ${prefix}} in it is replaced by the table
 * prefix.
 *
 * <p>The SHA-256 checksum of the script is taken before that replacement and stored with the version, and a
 * script that differs from the one that ran stops the start. The checksum is taken over the text without a leading
 * byte order mark and with every line ending as a line feed, so a build that checks the scripts out with other
 * line endings starts on the same databases. A row written by 0.1.0 to 0.3.2 holds the checksum of the script's
 * exact bytes: it is accepted when the script has the same text, whatever line endings or byte order mark either
 * build shipped it with, and for a script that shipped with line feeds and no byte order mark the two checksums are
 * the same. A script that ran on any database is never edited: its checksum would no longer match there.
 *
 * <p>On SQLite and PostgreSQL a migration runs in one transaction. On MySQL and MariaDB DDL commits on its own,
 * so progress is recorded after each statement, in one transaction with that statement: a statement that changes
 * rows of a transactional table commits together with its record and never runs twice. A DDL statement commits
 * before its record, and a start interrupted between the two runs that statement again, so every DDL statement of
 * a MySQL or MariaDB script must succeed when it runs a second time, as must a statement that changes rows of a
 * table that is not transactional. A resumed migration runs on a new connection, so no statement may depend on
 * session state an earlier statement set, such as a user variable, a prepared statement or
 * {@code SET FOREIGN_KEY_CHECKS}. MariaDB has {@code CREATE INDEX IF NOT EXISTS} and
 * {@code ADD COLUMN IF NOT EXISTS}; MySQL has neither, so on MySQL an index belongs inline in its
 * {@code CREATE TABLE IF NOT EXISTS}.
 *
 * <p>On SQLite a migration runs with foreign keys off, so the table rebuild SQLite documents for changes that
 * {@code ALTER TABLE} cannot make neither cascades nor deletes child rows; a {@code PRAGMA foreign_keys} line in a
 * script has no effect. {@code PRAGMA foreign_key_check} runs before the migration and again before it commits,
 * and a row that breaks a foreign key only in the second check rolls the migration back and stops the start. Rows
 * the first check already finds are named in one warning per start and do not stop the migration. The check gives
 * the rows of a {@code WITHOUT ROWID} table no row id, so those are counted per table, parent and foreign key, and
 * a higher count in the second check rolls the migration back.
 *
 * <p>On MySQL, MariaDB and PostgreSQL, starts that share a database and a version table migrate one at a time: a
 * start holds a database lock while it reads the applied migrations and runs the pending ones, and waits for that
 * lock while another start holds it, for up to a day on each of the three, after which the start fails with a
 * {@link StorageException}. Starts on another database of the same server, in another PostgreSQL schema, or of
 * another plugin with a version table of its own do not wait for it. A statement of a migration has no socket
 * timeout.
 *
 * @param version the version number, 1 or more
 * @param name the name, the part of the file name after {@code V<version>__}: not blank, at most 128 characters
 */
public record Migration(int version, String name) {

    private static final int MAX_NAME_LENGTH = 128;

    /**
     * Checks the version and the name.
     *
     * @throws NullPointerException if the name is {@code null}
     * @throws IllegalArgumentException if the version is below 1, or the name is blank or longer than 128
     *     characters
     */
    public Migration {
        Objects.requireNonNull(name, "name");
        if (version < 1) {
            throw new IllegalArgumentException("a migration version must be 1 or more, not " + version);
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("migration " + version + " needs a name");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("migration " + version + " has a name of " + name.length()
                + " characters; expected at most " + MAX_NAME_LENGTH);
        }
    }

    String id() {
        return "V" + version + "__" + name;
    }

    String resource(Dialect dialect) {
        return "db/migration/" + dialect.id() + "/" + id() + ".sql";
    }
}
