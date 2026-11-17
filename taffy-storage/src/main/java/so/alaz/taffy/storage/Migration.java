package so.alaz.taffy.storage;

import java.util.Objects;

/**
 * One step of a plugin's database layout: a version number and a name that together locate its script.
 *
 * <p>The script for a dialect is the resource {@code db/migration/<dialect id>/V<version>__<name>.sql}, for
 * example {@code db/migration/mariadb/V2__accounts.sql}, where the dialect id is {@link Dialect#id()}. A plugin
 * lists its migrations with strictly increasing versions and ships one script per migration for every dialect
 * it offers. The table {@code <prefix>schema_version} that records the applied migrations is created by storage,
 * so no script needs to create it; a script that creates it with {@code CREATE TABLE IF NOT EXISTS} still runs.
 * A table of that name in another layout stops the start before anything changes.
 *
 * <p>A script is read as UTF-8, and one that is not valid UTF-8 stops the start. It is split into statements by
 * the rules of a migration script: a statement ends at a semicolon that ends its line, and a line holds at most
 * one statement. Block comments, dollar quoting and a body of statements between {@code BEGIN} and {@code END},
 * as in a trigger or a procedure, are not supported. Before a script runs, every
 * {@code ${prefix}} in it is replaced by the table prefix. The SHA-256 checksum of the script is taken before that
 * replacement, over its exact bytes, and stored with the version. A script that differs from the one that ran,
 * even in its line endings, stops the start, so a plugin pins its scripts to LF line endings, for example with
 * {@code *.sql text eol=lf} in its {@code .gitattributes}.
 *
 * <p>On SQLite and PostgreSQL a migration runs in one transaction. On MySQL and MariaDB DDL commits on its own,
 * so progress is recorded after each statement and a start interrupted between a statement and that record runs
 * the statement again: every statement of a MySQL or MariaDB script must succeed when it runs a second time.
 * A resumed migration runs on a new connection, so no statement may depend on session state an earlier statement
 * set, such as a user variable, a prepared statement or {@code SET FOREIGN_KEY_CHECKS}. MariaDB has
 * {@code CREATE INDEX IF NOT EXISTS} and {@code ADD COLUMN IF NOT EXISTS}; MySQL has neither, so on MySQL an index
 * belongs inline in its {@code CREATE TABLE IF NOT EXISTS}.
 *
 * <p>On SQLite a migration runs with foreign keys off, so the table rebuild SQLite documents for changes that
 * {@code ALTER TABLE} cannot make neither cascades nor deletes child rows; a {@code PRAGMA foreign_keys} line in a
 * script has no effect. Before the migration commits, {@code PRAGMA foreign_key_check} runs, and a row that breaks
 * a foreign key rolls the migration back and stops the start.
 *
 * <p>On MySQL, MariaDB and PostgreSQL, starts that share a database migrate one at a time: a start holds a
 * database lock while it reads the applied migrations and runs the pending ones, and waits for that lock while
 * another start holds it. Starts on another database of the same server, or in another PostgreSQL schema, do not
 * wait for it. A statement of a migration has no socket timeout.
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
