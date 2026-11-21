package so.alaz.taffy.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class SqlScriptTest {

    @Test
    void splitsAtSemicolonsThatEndALineAndDropsComments() {
        String script = """
            -- first table
            CREATE TABLE a (
                id INT, -- a trailing note
                note VARCHAR(10) DEFAULT 'x;y -- not a comment'
            );

            CREATE INDEX a_id ON a (id); -- index on id\r
            -- last
            """;

        assertThat(statements(script)).containsExactly(
            "CREATE TABLE a (\n    id INT,\n    note VARCHAR(10) DEFAULT 'x;y -- not a comment'\n)",
            "CREATE INDEX a_id ON a (id)");
    }

    @Test
    void aFinalStatementWithoutASemicolonIsKept() {
        assertThat(statements("SELECT 1;\nSELECT 2")).containsExactly("SELECT 1", "SELECT 2");
        assertThat(statements("\n-- only comments\n")).isEmpty();
    }

    @Test
    void aSingleHyphenIsCodeNotAComment() {
        assertThat(statements("SELECT 1 - 2;\nSELECT 3 -\n4;\n")).containsExactly("SELECT 1 - 2", "SELECT 3 -\n4");
    }

    @Test
    void twoStatementsOnOneLineAreRefused() {
        assertThatThrownBy(() -> statements("CREATE TABLE a (id INT); CREATE TABLE b (id INT);\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a statement must end at the end of its line: CREATE TABLE a (id INT); CREATE TABLE b (id INT);");
        assertThatThrownBy(() -> statements("SELECT 1;;\n")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLineThatEndsInsideAQuotedStringIsRefusedWithItsLineNumber() {
        assertThatThrownBy(() -> statements("SELECT 1;\n\nINSERT INTO a VALUES ('first\nsecond');\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("line 3 ends inside a quoted string, which must end on the line it starts:"
                + " INSERT INTO a VALUES ('first");
        assertThat(statements("INSERT INTO a VALUES ('it''s', '');\n"))
            .containsExactly("INSERT INTO a VALUES ('it''s', '')");
    }

    @Test
    void aBlockCommentOutsideAQuotedStringIsKeptInItsStatement() {
        assertThat(statements("/* licence header */\nCREATE TABLE t (id INTEGER);"))
            .containsExactly("/* licence header */\nCREATE TABLE t (id INTEGER)");
        assertThat(statements("CREATE TABLE t (id INTEGER /* key */);"))
            .containsExactly("CREATE TABLE t (id INTEGER /* key */)");
        assertThat(SqlScript.statements("/*!40101 SET NAMES utf8mb4 */;", Dialect.MYSQL))
            .containsExactly("/*!40101 SET NAMES utf8mb4 */");
        assertThat(statements("INSERT INTO a VALUES ('/* kept */'); -- a /* note */\n"))
            .containsExactly("INSERT INTO a VALUES ('/* kept */')");
        assertThat(statements("SELECT 6 / 2 * 3;\n")).containsExactly("SELECT 6 / 2 * 3");
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void nothingInsideABlockCommentIsRead(Dialect dialect) {
        assertThat(SqlScript.statements("/*\nit's; here -- x\n\n$$ \\' -- end */\nSELECT 1;\nSELECT 2;", dialect))
            .containsExactly("/*\nit's; here -- x\n\n$$ \\' -- end */\nSELECT 1", "SELECT 2");
    }

    @Test
    void aBlockCommentAfterTheEndOfAStatementIsRefused() {
        assertThatThrownBy(() -> statements("-- head\nCREATE TABLE a (id INT); /* one; two */\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a statement must end at the end of its line: CREATE TABLE a (id INT); /* one; two */");
    }

    @Test
    void aBlockCommentThatNeverClosesOrHoldsAnotherIsRefused() {
        assertThatThrownBy(() -> statements("SELECT 0;\n/* open\nSELECT 1;\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("line 2 starts a block comment that never closes: /* open");
        assertThatThrownBy(() -> statements("/* a /* b */ c */\nSELECT 1;\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("line 1 holds a block comment inside a block comment, which PostgreSQL nests and the other"
                + " databases do not: /* a /* b */ c */");
        assertThat(statements("SELECT 1 /*/ still open */;\n")).containsExactly("SELECT 1 /*/ still open */");
    }

    @Test
    void dollarQuotingOutsideAQuotedStringIsRefused() {
        assertThatThrownBy(() -> statements("CREATE FUNCTION f() RETURNS int AS $$\nSELECT 1;\n$$ LANGUAGE sql;\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("line 1 holds dollar quoting ($$), which a migration script cannot hold:"
                + " CREATE FUNCTION f() RETURNS int AS $$");
        assertThat(statements("INSERT INTO a VALUES ('$$'); -- costs $$\nSELECT $1;\n"))
            .containsExactly("INSERT INTO a VALUES ('$$')", "SELECT $1");
    }

    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = {"MYSQL", "MARIADB"})
    void aQuoteDirectlyAfterABackslashIsRefusedOnMySqlAndMariaDb(Dialect dialect) {
        assertThatThrownBy(() -> SqlScript.statements("INSERT INTO a VALUES ('it\\'s; here');\n", dialect))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("line 1 has a quote directly after a backslash, which MySQL and MariaDB read as part of the"
                + " string: INSERT INTO a VALUES ('it\\'s; here');");
        assertThatThrownBy(() -> SqlScript.statements("SELECT 'C:\\';\n", dialect))
            .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("line 1 has a quote directly");
        assertThatThrownBy(() -> SqlScript.statements(
            "UPDATE t SET a = 1 WHERE b LIKE '%\\_%' ESCAPE '\\';\n", dialect))
            .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("line 1 has a quote directly");
        assertThat(SqlScript.statements("INSERT INTO a VALUES ('a\\b');\n", dialect))
            .containsExactly("INSERT INTO a VALUES ('a\\b')");
    }

    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = {"SQLITE", "POSTGRES"})
    void aQuoteDirectlyAfterABackslashEndsTheStringOnSqliteAndPostgres(Dialect dialect) {
        assertThat(SqlScript.statements("UPDATE t SET a = 1 WHERE b LIKE '%\\_%' ESCAPE '\\';\n", dialect))
            .containsExactly("UPDATE t SET a = 1 WHERE b LIKE '%\\_%' ESCAPE '\\'");
        assertThat(SqlScript.statements("SELECT 'C:\\';\n", dialect)).containsExactly("SELECT 'C:\\'");
    }

    private static List<String> statements(String script) {
        return SqlScript.statements(script, Dialect.SQLITE);
    }
}
