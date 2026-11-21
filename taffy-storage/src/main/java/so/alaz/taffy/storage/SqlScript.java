package so.alaz.taffy.storage;

import java.util.ArrayList;
import java.util.List;

final class SqlScript {

    private SqlScript() {
    }

    /**
     * Splits a migration script into the statements a driver runs one at a time.
     *
     * <p>A statement ends at a semicolon that ends its line, before any {@code --} comment, and the semicolon is
     * not part of the statement. Lines that are blank or hold only a {@code --} comment are dropped, as is a
     * {@code --} comment at the end of a line. The lines of one statement are joined with {@code \n}. A final
     * statement without a semicolon is kept. A semicolon outside a quoted string that does not end its line,
     * such as a second statement or a block comment on the same line, is refused, because a driver that runs only
     * the first of two statements would skip the second.
     *
     * <p>Only single-quoted strings are recognised, so a semicolon or {@code --} inside one is kept. A quote
     * inside a string is written as two quotes. A block comment outside a quoted string is kept in its statement,
     * over as many lines as it has, and nothing inside it is read: no semicolon, quote, {@code --} or {@code $$}.
     * What the splitter would read wrongly is refused: a line that ends inside a quoted string, a block comment
     * that never closes or holds another block comment, dollar quoting ({@code $$}) outside a quoted string and
     * outside a block comment, and on MySQL and MariaDB a quote that directly follows a backslash, which those two
     * read as part of the string. Double-quoted or backquoted identifiers that hold a semicolon, a quote or
     * {@code --}, a PostgreSQL escape string ({@code E'...'}) that holds {@code \'}, and a body of statements
     * between {@code BEGIN} and {@code END}, as in a trigger or a procedure, are not supported and not recognised.
     *
     * @param script the script text, with any line separator
     * @param dialect the database the script runs on
     * @return the statements in script order, each stripped of surrounding whitespace
     * @throws IllegalArgumentException if a semicolon outside a quoted string does not end its line, or the script
     *     holds one of the refused constructs, with a message that names the line
     */
    static List<String> statements(String script, Dialect dialect) {
        boolean backslashEscapes = dialect == Dialect.MYSQL || dialect == Dialect.MARIADB;
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String[] lines = script.split("\\R", -1);
        // The line on which the block comment that is still open started, or 0 outside a block comment.
        int commentFrom = 0;
        for (int number = 1; number <= lines.length; number++) {
            String line = lines[number - 1];
            String trimmed = line.strip();
            if (commentFrom == 0 && (trimmed.isEmpty() || trimmed.startsWith("--"))) {
                continue;
            }
            boolean quoted = false;
            int cut = line.length();
            int end = -1;
            for (int index = 0; index < line.length(); index++) {
                char character = line.charAt(index);
                char next = index + 1 < line.length() ? line.charAt(index + 1) : '\0';
                if (commentFrom != 0) {
                    if (character == '*' && next == '/') {
                        commentFrom = 0;
                        index++;
                    } else if (character == '/' && next == '*') {
                        throw refused(number, "holds a block comment inside a block comment, which PostgreSQL nests"
                            + " and the other databases do not", line);
                    }
                } else if (end >= 0) {
                    if (character == '-' && next == '-') {
                        cut = index;
                        break;
                    }
                    if (!Character.isWhitespace(character)) {
                        throw new IllegalArgumentException("a statement must end at the end of its line: "
                            + trimmed);
                    }
                } else if (character == '\'') {
                    if (backslashEscapes && index > 0 && line.charAt(index - 1) == '\\') {
                        throw refused(number, "has a quote directly after a backslash, which MySQL and MariaDB read"
                            + " as part of the string", line);
                    }
                    quoted = !quoted;
                } else if (quoted) {
                    continue;
                } else if (character == '-' && next == '-') {
                    cut = index;
                    break;
                } else if (character == '/' && next == '*') {
                    commentFrom = number;
                    index++;
                } else if (character == '$' && next == '$') {
                    throw refused(number, "holds dollar quoting ($$), which a migration script cannot hold", line);
                } else if (character == ';') {
                    end = index;
                }
            }
            if (quoted) {
                throw refused(number, "ends inside a quoted string, which must end on the line it starts", line);
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            if (end >= 0) {
                current.append(line, 0, end);
                add(statements, current);
            } else {
                current.append(line.substring(0, cut).stripTrailing());
            }
        }
        if (commentFrom != 0) {
            throw refused(commentFrom, "starts a block comment that never closes", lines[commentFrom - 1]);
        }
        add(statements, current);
        return statements;
    }

    private static IllegalArgumentException refused(int number, String reason, String line) {
        return new IllegalArgumentException("line " + number + " " + reason + ": " + line.strip());
    }

    private static void add(List<String> statements, StringBuilder current) {
        String statement = current.toString().strip();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
        current.setLength(0);
    }
}
