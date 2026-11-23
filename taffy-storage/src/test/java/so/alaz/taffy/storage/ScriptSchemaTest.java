package so.alaz.taffy.storage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

final class ScriptSchemaTest {

    @Test
    void aCreateTableGivesItsColumnsAndNoneOfItsConstraints() {
        ScriptSchema schema = schema(Dialect.POSTGRES, """
            CREATE TABLE IF NOT EXISTS public."Accounts" (
                player UUID NOT NULL,
                "Balance" NUMERIC(20, 4) NOT NULL DEFAULT 0 CHECK ("Balance" >= 0),
                note VARCHAR(32) DEFAULT 'a, (b)',
                key TEXT,
                tags INTEGER[],
                CONSTRAINT accounts_pk PRIMARY KEY (player),
                UNIQUE (note, key),
                FOREIGN KEY (player) REFERENCES players (id),
                CHECK (note <> '')
            )""");

        assertThat(schema.tables()).containsExactly("accounts");
        assertThat(schema.missingFrom(Map.of("accounts", Set.of()))).containsExactly("accounts.player",
            "accounts.balance", "accounts.note", "accounts.key", "accounts.tags");
        assertThat(schema.missingFrom(Map.of("accounts", Set.of("player", "balance", "note", "key", "tags", "more"))))
            .isEmpty();
        assertThat(schema.missingFrom(Map.of())).containsExactly("accounts");
    }

    @Test
    void mysqlIndexDefinitionsAndBackquotedNamesAreRead() {
        ScriptSchema schema = schema(Dialect.MYSQL, """
            CREATE TABLE `stash` (
                `id` INT NOT NULL /* the row, not the key */,
                `key` VARCHAR(8),
                amount DECIMAL(10, 2),
                PRIMARY KEY (`id`),
                KEY by_amount (amount),
                INDEX (amount),
                FULLTEXT KEY words (`key`)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4""",
            "ALTER TABLE stash ADD COLUMN note TEXT, ADD INDEX by_note (note(8)), CHANGE amount total DECIMAL(12, 2),"
                + " DROP INDEX by_amount, ADD (flag TINYINT(1), extra INT), RENAME INDEX words TO terms",
            "RENAME TABLE stash TO hoard, other TO another");

        assertThat(schema.tables()).containsExactly("hoard", "another");
        assertThat(schema.missingFrom(Map.of("hoard", Set.of(), "another", Set.of()))).containsExactly("hoard.id",
            "hoard.key", "hoard.note", "hoard.total", "hoard.flag", "hoard.extra");
    }

    @Test
    void alterTableAddsDropsAndRenamesColumnsAndTables() {
        ScriptSchema schema = schema(Dialect.POSTGRES,
            "CREATE TABLE a (id INTEGER, old_name TEXT, gone TEXT)",
            "ALTER TABLE IF EXISTS ONLY a ADD COLUMN IF NOT EXISTS added TEXT",
            "ALTER TABLE a ADD CONSTRAINT a_pk PRIMARY KEY (id)",
            "ALTER TABLE a DROP COLUMN IF EXISTS gone CASCADE",
            "ALTER TABLE a DROP CONSTRAINT a_pk",
            "ALTER TABLE a RENAME COLUMN old_name TO new_name",
            "ALTER TABLE a RENAME added TO extra",
            "ALTER TABLE a ALTER COLUMN extra DROP NOT NULL",
            "ALTER TABLE a RENAME TO b",
            "ALTER TABLE untracked ADD seen BOOLEAN",
            "CREATE INDEX b_extra ON b (extra)",
            "INSERT INTO b (id) VALUES (1)");

        assertThat(schema.missingFrom(Map.of("b", Set.of(), "untracked", Set.of())))
            .containsExactly("b.id", "b.new_name", "b.extra", "untracked.seen");
    }

    @Test
    void theSqliteTableRebuildLeavesTheNewLayoutUnderTheOldName() {
        ScriptSchema schema = schema(Dialect.SQLITE,
            "CREATE TABLE accounts (id INTEGER PRIMARY KEY, [legacy name] TEXT, key TEXT)",
            "CREATE TABLE accounts_new (id INTEGER PRIMARY KEY, name TEXT NOT NULL)",
            "INSERT INTO accounts_new SELECT id, [legacy name] FROM accounts",
            "DROP TABLE accounts",
            "ALTER TABLE accounts_new RENAME TO accounts",
            "CREATE TEMP TABLE scratch (id INTEGER)",
            "CREATE TABLE copy AS SELECT * FROM accounts",
            "CREATE TABLE IF NOT EXISTS accounts (other INTEGER)",
            "DROP TABLE IF EXISTS never_there");

        assertThat(schema.tables()).containsExactly("accounts", "copy");
        assertThat(schema.missingFrom(Map.of("accounts", Set.of("id"), "copy", Set.of())))
            .containsExactly("accounts.name");
    }

    @Test
    void anExclusionConstraintOnPostgresIsNotAColumnAndAColumnNamedExcludeIs() {
        ScriptSchema schema = schema(Dialect.POSTGRES,
            "CREATE TABLE t (id INT, during tsrange, exclude TEXT, EXCLUDE USING gist (during WITH &&))",
            "ALTER TABLE t ADD EXCLUDE (id WITH =), ADD period INT");

        assertThat(schema.missingFrom(Map.of("t", Set.of())))
            .containsExactly("t.id", "t.during", "t.exclude", "t.period");
    }

    @Test
    void aPeriodOnMariaDbIsNotAColumnAndAColumnNamedPeriodIs() {
        ScriptSchema schema = schema(Dialect.MARIADB,
            "CREATE TABLE t (id INT, a DATE, b DATE, period INT, PERIOD FOR p (a, b))",
            "ALTER TABLE t ADD PERIOD FOR q (a, b)");

        assertThat(schema.missingFrom(Map.of("t", Set.of()))).containsExactly("t.id", "t.a", "t.b", "t.period");
    }

    @Test
    void anAddedPartitionOnMysqlAndMariaDbIsNotAColumn() {
        for (Dialect dialect : List.of(Dialect.MYSQL, Dialect.MARIADB)) {
            ScriptSchema schema = schema(dialect,
                "CREATE TABLE t (id INT) PARTITION BY RANGE (id) (PARTITION p0 VALUES LESS THAN (10))",
                "ALTER TABLE t ADD PARTITION (PARTITION p1 VALUES LESS THAN (20))",
                "ALTER TABLE t DROP PARTITION p0");

            assertThat(schema.missingFrom(Map.of("t", Set.of("id")))).isEmpty();
        }
    }

    @Test
    void aHashCommentOnMysqlAndMariaDbIsSkipped() {
        for (Dialect dialect : List.of(Dialect.MYSQL, Dialect.MARIADB)) {
            ScriptSchema schema = schema(dialect, """
                CREATE TABLE t (
                    id INT, # the key, primary
                    name TEXT # it's the last column
                )""");

            assertThat(schema.missingFrom(Map.of("t", Set.of()))).containsExactly("t.id", "t.name");
        }
    }

    private static ScriptSchema schema(Dialect dialect, String... statements) {
        ScriptSchema schema = new ScriptSchema(dialect);
        List.of(statements).forEach(schema::apply);
        return schema;
    }
}
