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
}
