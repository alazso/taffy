package so.alaz.taffy.storage;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The tables and columns a list of migration statements leaves, read from the statements {@link Baseline#checkSchema}
 * names. Names are held in lowercase and without their quotes.
 *
 * <p>An instance is built on one thread; it is not thread-safe.
 */
final class ScriptSchema {

    private static final Set<String> TABLE_MODIFIERS =
        Set.of("OR", "REPLACE", "UNLOGGED", "GLOBAL", "LOCAL", "TEMP", "TEMPORARY");

    private static final Set<String> CONSTRAINTS = Set.of("CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK");

    // MySQL and MariaDB reserve these words, so only there do they start an index definition and never name a column.
    private static final Set<String> INDEXES = Set.of("KEY", "INDEX", "FULLTEXT", "SPATIAL");

    private static final Set<String> DROPPED_OTHERS = Set.of("CONSTRAINT", "PRIMARY", "FOREIGN", "CHECK", "PARTITION");

    private record Token(String text, boolean word, boolean quoted) {

        boolean is(String keyword) {
            return word && !quoted && text.equalsIgnoreCase(keyword);
        }

        boolean in(Set<String> keywords) {
            return word && !quoted && keywords.contains(text.toUpperCase(Locale.ROOT));
        }

        boolean symbol(String symbol) {
            return !word && text.equals(symbol);
        }
    }

    private final Dialect dialect;
    private final Map<String, Set<String>> tables = new LinkedHashMap<>();

    ScriptSchema(Dialect dialect) {
        this.dialect = dialect;
    }

    /** Applies one statement, without its final semicolon and with the table prefix already in place. */
    void apply(String statement) {
        List<Token> tokens = tokens(statement);
        if (tokens.size() < 3) {
            return;
        }
        if (tokens.getFirst().is("CREATE")) {
            create(tokens);
        } else if (tokens.getFirst().is("ALTER") && tokens.get(1).is("TABLE")) {
            alter(tokens);
        } else if (tokens.getFirst().is("DROP") && tokens.get(1).is("TABLE")) {
            drop(tokens);
        } else if (tokens.getFirst().is("RENAME") && tokens.get(1).is("TABLE")) {
            renameTables(tokens);
        }
    }

    /**
     * What a database lacks of these tables and columns: a missing table as its name, and a missing column of a
     * table that exists as {@code table.column}, in the order the statements created them.
     *
     * @param actual the columns of each table of the database, both in lowercase
     */
    List<String> missingFrom(Map<String, Set<String>> actual) {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Set<String>> table : tables.entrySet()) {
            Set<String> columns = actual.get(table.getKey());
            if (columns == null) {
                missing.add(table.getKey());
                continue;
            }
            for (String column : table.getValue()) {
                if (!columns.contains(column)) {
                    missing.add(table.getKey() + "." + column);
                }
            }
        }
        return missing;
    }

    /** The tables the statements leave, in lowercase. */
    Set<String> tables() {
        return tables.keySet();
    }

    private void create(List<Token> tokens) {
        int at = 1;
        boolean temporary = false;
        boolean replace = false;
        while (at < tokens.size() && tokens.get(at).in(TABLE_MODIFIERS)) {
            temporary |= tokens.get(at).is("TEMP") || tokens.get(at).is("TEMPORARY");
            replace |= tokens.get(at).is("REPLACE");
            at++;
        }
        if (at >= tokens.size() || !tokens.get(at).is("TABLE") || temporary) {
            return;
        }
        at++;
        boolean ifNotExists = false;
        if (at + 2 < tokens.size() && tokens.get(at).is("IF") && tokens.get(at + 1).is("NOT")
            && tokens.get(at + 2).is("EXISTS")) {
            ifNotExists = true;
            at += 3;
        }
        int[] end = new int[1];
        String table = name(tokens, at, end);
        if (table == null || tables.containsKey(table) && ifNotExists && !replace) {
            return;
        }
        Set<String> columns = new LinkedHashSet<>();
        at = end[0];
        if (at < tokens.size() && tokens.get(at).symbol("(")) {
            for (List<Token> item : items(tokens, at + 1, true)) {
                Token first = item.getFirst();
                if (first.word() && !first.is("LIKE") && !startsOther(item, 0)) {
                    columns.add(key(first));
                }
            }
        }
        tables.put(table, columns);
    }

    private void alter(List<Token> tokens) {
        int at = 2;
        if (at + 1 < tokens.size() && tokens.get(at).is("IF") && tokens.get(at + 1).is("EXISTS")) {
            at += 2;
        }
        if (at < tokens.size() && tokens.get(at).is("ONLY")) {
            at++;
        }
        int[] end = new int[1];
        String table = name(tokens, at, end);
        if (table == null) {
            return;
        }
        for (List<Token> action : items(tokens, end[0], false)) {
            table = act(table, action);
        }
    }

    // Applies one action of an ALTER TABLE and gives the name the table has after it.
    private String act(String table, List<Token> action) {
        Token verb = action.getFirst();
        int at = 1;
        if (verb.is("ADD")) {
            boolean column = at < action.size() && action.get(at).is("COLUMN");
            at += column ? 1 : 0;
            if (at + 2 < action.size() && action.get(at).is("IF") && action.get(at + 1).is("NOT")
                && action.get(at + 2).is("EXISTS")) {
                at += 3;
            }
            if (at >= action.size()) {
                return table;
            }
            Set<String> columns = tables.computeIfAbsent(table, absent -> new LinkedHashSet<>());
            if (action.get(at).symbol("(")) {
                for (List<Token> item : items(action, at + 1, true)) {
                    if (item.getFirst().word()) {
                        columns.add(key(item.getFirst()));
                    }
                }
            } else if (action.get(at).word() && (column || !startsOther(action, at))) {
                columns.add(key(action.get(at)));
            }
        } else if (verb.is("DROP")) {
            boolean column = at < action.size() && action.get(at).is("COLUMN");
            at += column ? 1 : 0;
            if (at + 1 < action.size() && action.get(at).is("IF") && action.get(at + 1).is("EXISTS")) {
                at += 2;
            }
            Set<String> columns = tables.get(table);
            if (columns != null && at < action.size() && action.get(at).word()
                && (column || !action.get(at).in(DROPPED_OTHERS) && !startsIndex(action.get(at)))) {
                columns.remove(key(action.get(at)));
            }
        } else if (verb.is("CHANGE")) {
            at += at < action.size() && action.get(at).is("COLUMN") ? 1 : 0;
            if (at + 1 < action.size() && action.get(at).word() && action.get(at + 1).word()) {
                renameColumn(table, action.get(at), action.get(at + 1));
            }
        } else if (verb.is("RENAME")) {
            return rename(table, action);
        }
        return table;
    }

    private String rename(String table, List<Token> action) {
        if (action.size() < 2) {
            return table;
        }
        Token second = action.get(1);
        if (second.is("CONSTRAINT") || startsIndex(second)) {
            return table;
        }
        if (second.is("COLUMN")) {
            if (action.size() >= 5 && action.get(3).is("TO")) {
                renameColumn(table, action.get(2), action.get(4));
            }
            return table;
        }
        if (action.size() >= 4 && action.get(2).is("TO") && !second.is("TO") && !second.is("AS")) {
            renameColumn(table, second, action.get(3));
            return table;
        }
        int[] end = new int[1];
        String renamed = name(action, second.is("TO") || second.is("AS") ? 2 : 1, end);
        return renamed == null ? table : renameTable(table, renamed);
    }

    private void drop(List<Token> tokens) {
        int at = 2;
        if (at + 1 < tokens.size() && tokens.get(at).is("IF") && tokens.get(at + 1).is("EXISTS")) {
            at += 2;
        }
        for (List<Token> item : items(tokens, at, false)) {
            String table = name(item, 0, new int[1]);
            if (table != null) {
                tables.remove(table);
            }
        }
    }

    private void renameTables(List<Token> tokens) {
        for (List<Token> item : items(tokens, 2, false)) {
            int[] end = new int[1];
            String from = name(item, 0, end);
            if (from != null && end[0] < item.size() && item.get(end[0]).is("TO")) {
                String to = name(item, end[0] + 1, new int[1]);
                if (to != null) {
                    renameTable(from, to);
                }
            }
        }
    }

    private String renameTable(String from, String to) {
        Set<String> columns = tables.remove(from);
        tables.put(to, columns == null ? new LinkedHashSet<>() : columns);
        return to;
    }

    private void renameColumn(String table, Token from, Token to) {
        Set<String> columns = tables.computeIfAbsent(table, absent -> new LinkedHashSet<>());
        columns.remove(key(from));
        columns.add(key(to));
    }

    // Whether the tokens from the index on start a table element other than a column. EXCLUDE on PostgreSQL and
    // PERIOD on MariaDB may name a column, so they count only with the word or parenthesis their element needs.
    // MySQL and MariaDB reserve PARTITION.
    private boolean startsOther(List<Token> tokens, int at) {
        Token first = tokens.get(at);
        if (first.in(CONSTRAINTS) || startsIndex(first)) {
            return true;
        }
        if (at + 1 >= tokens.size()) {
            return false;
        }
        Token next = tokens.get(at + 1);
        return switch (dialect) {
            case POSTGRES -> first.is("EXCLUDE") && (next.is("USING") || next.symbol("("));
            case MARIADB -> first.is("PARTITION") || first.is("PERIOD") && next.is("FOR");
            case MYSQL -> first.is("PARTITION");
            case SQLITE -> false;
        };
    }

    private boolean startsIndex(Token token) {
        return (dialect == Dialect.MYSQL || dialect == Dialect.MARIADB) && token.in(INDEXES);
    }

    private static String key(Token token) {
        return token.text().toLowerCase(Locale.ROOT);
    }

    // A table name at the index, possibly qualified by a schema: the last part in lowercase, or null when no name
    // stands there. end[0] is the index after the name.
    private static @Nullable String name(List<Token> tokens, int at, int[] end) {
        if (at >= tokens.size() || !tokens.get(at).word()) {
            return null;
        }
        while (at + 2 < tokens.size() && tokens.get(at + 1).symbol(".") && tokens.get(at + 2).word()) {
            at += 2;
        }
        end[0] = at + 1;
        return key(tokens.get(at));
    }

    // The non-empty runs of tokens between commas outside parentheses, from the index on. A list that is itself in
    // parentheses ends at the parenthesis that closes it.
    private static List<List<Token>> items(List<Token> tokens, int from, boolean parenthesized) {
        List<List<Token>> items = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int depth = 0;
        for (int at = from; at < tokens.size(); at++) {
            Token token = tokens.get(at);
            if (token.symbol("(")) {
                depth++;
            } else if (token.symbol(")")) {
                if (depth == 0 && parenthesized) {
                    break;
                }
                depth--;
            } else if (token.symbol(",") && depth == 0) {
                if (!current.isEmpty()) {
                    items.add(current);
                }
                current = new ArrayList<>();
                continue;
            }
            current.add(token);
        }
        if (!current.isEmpty()) {
            items.add(current);
        }
        return items;
    }

    // Words, quoted identifiers without their quotes, and single symbols. String literals and block comments are
    // dropped, as is a comment from # to the end of its line on MySQL and MariaDB. A bracketed identifier is read
    // on SQLite only, since PostgreSQL writes array types with brackets.
    private List<Token> tokens(String statement) {
        List<Token> tokens = new ArrayList<>();
        int length = statement.length();
        int at = 0;
        while (at < length) {
            char character = statement.charAt(at);
            if (Character.isWhitespace(character)) {
                at++;
            } else if (character == '/' && at + 1 < length && statement.charAt(at + 1) == '*') {
                int close = statement.indexOf("*/", at + 2);
                at = close < 0 ? length : close + 2;
            } else if (character == '#' && (dialect == Dialect.MYSQL || dialect == Dialect.MARIADB)) {
                int end = statement.indexOf('\n', at);
                at = end < 0 ? length : end + 1;
            } else if (character == '\'') {
                at = closing(statement, at, '\'') + 1;
            } else if (character == '"' || character == '`' || character == '[' && dialect == Dialect.SQLITE) {
                char quote = character == '[' ? ']' : character;
                int close = closing(statement, at, quote);
                String text = statement.substring(at + 1, Math.min(close, length));
                tokens.add(new Token(text.replace("" + quote + quote, "" + quote), true, true));
                at = close + 1;
            } else if (Character.isLetterOrDigit(character) || character == '_' || character == '$') {
                int start = at;
                while (at < length && (Character.isLetterOrDigit(statement.charAt(at))
                    || statement.charAt(at) == '_' || statement.charAt(at) == '$')) {
                    at++;
                }
                tokens.add(new Token(statement.substring(start, at), true, false));
            } else {
                tokens.add(new Token(String.valueOf(character), false, false));
                at++;
            }
        }
        return tokens;
    }

    // The index of the quote that closes the one at the index, where a doubled quote stands for the quote itself,
    // or the length of the statement when none closes it.
    private static int closing(String statement, int open, char quote) {
        int at = open + 1;
        while (at < statement.length()) {
            if (statement.charAt(at) == quote) {
                if (at + 1 < statement.length() && statement.charAt(at + 1) == quote && quote != ']') {
                    at += 2;
                    continue;
                }
                return at;
            }
            at++;
        }
        return statement.length();
    }
}
