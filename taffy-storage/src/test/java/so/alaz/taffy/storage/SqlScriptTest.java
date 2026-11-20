package so.alaz.taffy.storage;

import org.junit.jupiter.api.Test;

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

        assertThat(SqlScript.statements(script)).containsExactly(
            "CREATE TABLE a (\n    id INT,\n    note VARCHAR(10) DEFAULT 'x;y -- not a comment'\n)",
            "CREATE INDEX a_id ON a (id)");
    }

    @Test
    void aFinalStatementWithoutASemicolonIsKept() {
        assertThat(SqlScript.statements("SELECT 1;\nSELECT 2")).containsExactly("SELECT 1", "SELECT 2");
        assertThat(SqlScript.statements("\n-- only comments\n")).isEmpty();
    }

    @Test
    void aSingleHyphenIsCodeNotAComment() {
        assertThat(SqlScript.statements("SELECT 1 - 2;\nSELECT 3 -\n4;\n")).containsExactly("SELECT 1 - 2",
            "SELECT 3 -\n4");
    }

    @Test
    void twoStatementsOnOneLineAreRefused() {
        assertThatThrownBy(() -> SqlScript.statements("CREATE TABLE a (id INT); CREATE TABLE b (id INT);\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a statement must end at the end of its line: CREATE TABLE a (id INT); CREATE TABLE b (id INT);");
        assertThatThrownBy(() -> SqlScript.statements("SELECT 1;;\n"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLineThatEndsInsideAQuotedStringIsRefusedWithItsLineNumber() {
        assertThatThrownBy(() -> SqlScript.statements("SELECT 1;\n\nINSERT INTO a VALUES ('first\nsecond');\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("line 3 ends inside a quoted string, which must end on the line it starts:"
                + " INSERT INTO a VALUES ('first");
        assertThat(SqlScript.statements("INSERT INTO a VALUES ('it''s', '');\n"))
            .containsExactly("INSERT INTO a VALUES ('it''s', '')");
    }

    @Test
    void aBlockCommentOutsideAQuotedStringIsRefused() {
        assertThatThrownBy(() -> SqlScript.statements("-- head\nCREATE TABLE a (id INT); /* one; two */\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("line 2 holds a block comment, which a migration script cannot hold:"
                + " CREATE TABLE a (id INT); /* one; two */");
        assertThatThrownBy(() -> SqlScript.statements("/*\nCREATE TABLE a (id INT);\n*/\n"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("line 1 holds a block comment");
        assertThat(SqlScript.statements("INSERT INTO a VALUES ('/* kept */'); -- a /* note */\n"))
            .containsExactly("INSERT INTO a VALUES ('/* kept */')");
        assertThat(SqlScript.statements("SELECT 6 / 2 * 3;\n")).containsExactly("SELECT 6 / 2 * 3");
    }

    @Test
    void dollarQuotingOutsideAQuotedStringIsRefused() {
        assertThatThrownBy(() -> SqlScript.statements(
            "CREATE FUNCTION f() RETURNS int AS $$\nSELECT 1;\n$$ LANGUAGE sql;\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("line 1 holds dollar quoting ($$), which a migration script cannot hold:"
                + " CREATE FUNCTION f() RETURNS int AS $$");
        assertThat(SqlScript.statements("INSERT INTO a VALUES ('$$'); -- costs $$\nSELECT $1;\n"))
            .containsExactly("INSERT INTO a VALUES ('$$')", "SELECT $1");
    }

    @Test
    void aQuoteDirectlyAfterABackslashIsRefused() {
        assertThatThrownBy(() -> SqlScript.statements("INSERT INTO a VALUES ('it\\'s; here');\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("line 1 has a quote directly after a backslash, which MySQL and MariaDB read as part of the"
                + " string and the other databases as its end: INSERT INTO a VALUES ('it\\'s; here');");
        assertThatThrownBy(() -> SqlScript.statements("SELECT 'C:\\';\n"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("line 1 has a quote directly");
        assertThat(SqlScript.statements("INSERT INTO a VALUES ('a\\b');\n"))
            .containsExactly("INSERT INTO a VALUES ('a\\b')");
    }
}
