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
     * such as a second statement on the same line, is refused, because a driver that runs only the first of two
     * statements would skip the second.
     *
     * <p>Only single-quoted strings are recognised, so a semicolon or {@code --} inside one is kept. A quote
     * inside a string is written as two quotes. What the splitter would read wrongly is refused: a line that ends
     * inside a quoted string, the start of a block comment or dollar quoting ({@code $$}) outside a quoted string,
     * and a quote that directly follows a backslash, which MySQL and MariaDB read as part of the string and the
     * other databases as its end. Double-quoted or backquoted identifiers that hold a semicolon, a quote or
     * {@code --}, and a body of statements between {@code BEGIN} and {@code END}, as in a trigger or a procedure,
     * are not supported and not recognised.
     *
     * @param script the script text, with any line separator
     * @return the statements in script order, each stripped of surrounding whitespace
     * @throws IllegalArgumentException if a semicolon outside a quoted string does not end its line, or the script
     *     holds one of the refused constructs, with a message that names the line
     */
    static List<String> statements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String[] lines = script.split("\\R", -1);
        for (int number = 1; number <= lines.length; number++) {
            String line = lines[number - 1];
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            String code = code(line, number);
            int end = statementEnd(code, line);
            if (!current.isEmpty()) {
                current.append('\n');
            }
            if (end >= 0) {
                current.append(code, 0, end);
                add(statements, current);
            } else {
                current.append(code.stripTrailing());
            }
        }
        add(statements, current);
        return statements;
    }

    // The line without its -- comment.
    private static String code(String line, int number) {
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            char next = index + 1 < line.length() ? line.charAt(index + 1) : '\0';
            if (character == '\'') {
                if (index > 0 && line.charAt(index - 1) == '\\') {
                    throw refused(number, "has a quote directly after a backslash, which MySQL and MariaDB read as"
                        + " part of the string and the other databases as its end", line);
                }
                quoted = !quoted;
            } else if (!quoted && character == '-' && next == '-') {
                return line.substring(0, index);
            } else if (!quoted && character == '/' && next == '*') {
                throw refused(number, "holds a block comment, which a migration script cannot hold", line);
            } else if (!quoted && character == '$' && next == '$') {
                throw refused(number, "holds dollar quoting ($$), which a migration script cannot hold", line);
            }
        }
        if (quoted) {
            throw refused(number, "ends inside a quoted string, which must end on the line it starts", line);
        }
        return line;
    }

    private static IllegalArgumentException refused(int number, String reason, String line) {
        return new IllegalArgumentException("line " + number + " " + reason + ": " + line.strip());
    }

    private static int statementEnd(String code, String line) {
        boolean quoted = false;
        int end = -1;
        for (int index = 0; index < code.length(); index++) {
            char character = code.charAt(index);
            if (character == '\'') {
                quoted = !quoted;
            } else if (!quoted && character == ';') {
                if (!code.substring(index + 1).isBlank()) {
                    throw new IllegalArgumentException("a statement must end at the end of its line: " + line.strip());
                }
                end = index;
            }
        }
        return end;
    }

    private static void add(List<String> statements, StringBuilder current) {
        String statement = current.toString().strip();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
        current.setLength(0);
    }
}
