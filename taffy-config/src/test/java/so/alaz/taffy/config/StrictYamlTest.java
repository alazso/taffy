package so.alaz.taffy.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.parser.ParserException;
import org.yaml.snakeyaml.scanner.ScannerException;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class StrictYamlTest {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private static final String VOUCHER = """
        material: PAPER
        # the name a player sees
        name: "<gold>Gutschein f\u00fcr 5 \u20ac"
        lore:
          - "Einl\u00f6sen mit Rechtsklick"
          - plain
        uses: 3
        """;

    @TempDir
    private Path folder;

    @Test
    void aFileIsReadWithItsValues() throws Exception {
        Files.writeString(configFile(), "menu:\n  rows: 6\n  title: 'Café'\n");

        YamlConfiguration yaml = StrictYaml.read(configFile(), "config.yml");

        assertThat(yaml.getInt("menu.rows")).isEqualTo(6);
        assertThat(yaml.getString("menu.title")).isEqualTo("Café");
    }

    @Test
    void acceptsAnEmptyFile() throws Exception {
        Files.writeString(configFile(), "");

        assertThat(StrictYaml.read(configFile(), "config.yml").getKeys(true)).isEmpty();
    }

    @Test
    void aFileThatStartsWithAByteOrderMarkIsRead() throws Exception {
        Files.writeString(configFile(), "\uFEFFrows: 6\n");

        YamlConfiguration yaml = StrictYaml.read(configFile(), "config.yml");

        assertThat(yaml.getKeys(true)).containsExactly("rows");
        assertThat(yaml.getInt("rows")).isEqualTo(6);
    }

    @Test
    void commentsSurviveSaveToString() throws Exception {
        String text = """
            # Header kept on purpose.

            # How many rows the menu has.
            rows: 6 # inline note
            """;

        YamlConfiguration yaml = StrictYaml.parse(text, "config.yml");

        assertThat(yaml.options().getHeader()).containsExactly("Header kept on purpose.");
        assertThat(yaml.getComments("rows")).containsExactly("How many rows the menu has.");
        assertThat(yaml.getInlineComments("rows")).containsExactly("inline note");
        assertThat(yaml.saveToString()).isEqualTo(text);
    }

    @Test
    void aLongTextIsSavedOnOneLine() throws Exception {
        String message = "<gray>Welcome to the server, " + "a long word ".repeat(16) + "and: a colon, a #hash";
        assertThat(message).hasSizeGreaterThan(200);
        String text = "rows: 6\nmessage: \"" + message + "\"\n";

        YamlConfiguration yaml = StrictYaml.parse(text, "config.yml");
        yaml.set("rows", 3);
        String saved = yaml.saveToString();

        assertThat(saved.lines()).hasSize(2).last().asString().startsWith("message: ").endsWith("a #hash'");
        assertThat(StrictYaml.parse(saved, "config.yml").getString("message")).isEqualTo(message);
    }

    @Test
    void everyCallGivesANewConfiguration() throws Exception {
        YamlConfiguration first = StrictYaml.parse("rows: 6\n", "config.yml");
        first.set("rows", 3);

        assertThat(StrictYaml.parse("rows: 6\n", "config.yml").getInt("rows")).isEqualTo(6);
    }

    @Test
    void aFileNotSavedAsUtf8StopsTheLoadAndSaysSo() throws Exception {
        String owner = "# café\nconfig-version: 1\n";
        Files.write(configFile(), owner.getBytes(StandardCharsets.ISO_8859_1));

        assertThatThrownBy(() -> StrictYaml.read(configFile(), "menus/main.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("menus/main.yml is not saved as UTF-8: the first byte that is not UTF-8 is on line 1")
            .hasCauseInstanceOf(CharacterCodingException.class);
    }

    @Test
    void theMessageOfAFileNotSavedAsUtf8NamesTheLineOfTheFirstSuchByte() throws Exception {
        Files.write(configFile(), VOUCHER.getBytes(WINDOWS_1252));

        assertThatThrownBy(() -> StrictYaml.read(configFile(), "vouchers/gold.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("vouchers/gold.yml is not saved as UTF-8: the first byte that is not UTF-8 is on line 3");
    }

    @Test
    void aLenientReadLoadsAFileSavedInALegacyCodePageWithOneWarning() throws Exception {
        Files.write(configFile(), VOUCHER.getBytes(WINDOWS_1252));
        List<String> warnings = new ArrayList<>();

        YamlConfiguration yaml = StrictYaml.readLenient(configFile(), "vouchers/gold.yml", warnings::add);

        assertThat(yaml.getString("material")).isEqualTo("PAPER");
        assertThat(yaml.getString("name")).isEqualTo("<gold>Gutschein f\uFFFDr 5 \uFFFD");
        assertThat(yaml.getStringList("lore")).containsExactly("Einl\uFFFDsen mit Rechtsklick", "plain");
        assertThat(yaml.getInt("uses")).isEqualTo(3);
        assertThat(warnings).containsExactly("vouchers/gold.yml is not saved as UTF-8, so each byte that is not UTF-8"
            + " is read as the replacement character U+FFFD; the first is on line 3. Save the file as UTF-8.");
    }

    @Test
    void aLenientReadOfAUtf8FileGivesNoWarning() throws Exception {
        Files.writeString(configFile(), VOUCHER + "kept: \"\uFFFD\"\n");
        List<String> warnings = new ArrayList<>();

        YamlConfiguration yaml = StrictYaml.readLenient(configFile(), "vouchers/gold.yml", warnings::add);

        assertThat(yaml.getString("name")).isEqualTo("<gold>Gutschein f\u00fcr 5 \u20ac");
        assertThat(yaml.getString("kept")).isEqualTo("\uFFFD");
        assertThat(warnings).isEmpty();
    }

    @Test
    void aLenientReadStillRefusesInvalidYamlAndAMissingFile() throws Exception {
        Files.write(configFile(), "name: Caf\u00e9\nlore: [\n".getBytes(WINDOWS_1252));
        List<String> warnings = new ArrayList<>();

        assertThatThrownBy(() -> StrictYaml.readLenient(configFile(), "config.yml", warnings::add))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessageStartingWith("config.yml is not valid YAML: ");
        assertThat(warnings).hasSize(1);
        assertThatThrownBy(() -> StrictYaml.readLenient(folder.resolve("absent.yml"), "absent.yml", warnings::add))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("cannot read absent.yml: NoSuchFileException");
    }

    @Test
    void theLineOfTheFirstByteThatIsNotUtf8IsCountedAcrossALargeFile() {
        byte[] head = "# filler line of a comment\n".repeat(1000).getBytes(StandardCharsets.UTF_8);
        byte[] cutOff = Arrays.copyOf(head, head.length + 7);
        System.arraycopy("x: \"\u20ac".getBytes(StandardCharsets.UTF_8), 0, cutOff, head.length, 7);

        assertThat(StrictYaml.malformedLine(head)).isZero();
        assertThat(StrictYaml.malformedLine(Arrays.copyOf(cutOff, cutOff.length - 1))).isEqualTo(1001);
        assertThat(StrictYaml.malformedLine(cutOff)).isZero();
        assertThat(StrictYaml.malformedLine(new byte[0])).isZero();
    }

    @Test
    void aTextWithAnAsteriskOrAnAmpersandButNoAliasIsParsed() throws Exception {
        assertThat(StrictYaml.parse("motd: \"*** welcome ***\"\n", "config.yml").getString("motd"))
            .isEqualTo("*** welcome ***");
        assertThat(StrictYaml.parse("motd: Tom & Jerry\n", "config.yml").getString("motd")).isEqualTo("Tom & Jerry");
        assertThat(StrictYaml.parse("motd: \"&a*bold*\"\n", "config.yml").getString("motd")).isEqualTo("&a*bold*");
        assertThat(StrictYaml.parse("base: &b {rows: 6}\ncopy: *b\n", "config.yml").getInt("copy.rows"))
            .isEqualTo(6);
        assertThatThrownBy(() -> StrictYaml.parse("copy: *missing\n", "config.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessageStartingWith("config.yml is not valid YAML: ");
    }

    @Test
    void aTagPaperCannotBuildStopsTheLoadAsInvalidYaml() throws Exception {
        Files.writeString(configFile(), "config-version: 1\nprefix: !Custom value\n");

        assertThatThrownBy(() -> StrictYaml.read(configFile(), "config.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessageStartingWith("config.yml is not valid YAML: ")
            .hasNoCause();
    }

    @Test
    void invalidYamlStopsTheLoadWithTheReason() throws Exception {
        String owner = "config-version: 1\nmodules: [\n";
        Files.writeString(configFile(), owner);

        assertThatThrownBy(() -> StrictYaml.read(configFile(), "config.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("config.yml is not valid YAML: expected the node content, but found '<stream end>'"
                + " at line 3, column 1")
            .hasNoCause();
        assertThat(Files.readString(configFile())).isEqualTo(owner);
    }

    @Test
    void reportsAFileThatDoesNotParse() throws Exception {
        Files.writeString(configFile(), "menu:\n\trows: 6\n");

        assertThatThrownBy(() -> StrictYaml.read(configFile(), "menus/main.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessageStartingWith("menus/main.yml is not valid YAML: ");
    }

    @Test
    void aDocumentThatIsNotAMappingIsNotValid() {
        assertThatThrownBy(() -> StrictYaml.parse("- first\n- second\n", "config.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("config.yml is not valid YAML: Top level is not a Map.")
            .hasNoCause();
    }

    @Test
    void aMappingAnchorUsedInsideItselfIsNotValid() {
        assertThatThrownBy(() -> StrictYaml.parse("a: &a\n  b: *a\n", "config.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("config.yml is not valid YAML: an anchor is used inside the value it names");
    }

    @Test
    void aListAnchorUsedInsideItselfIsNotValid() {
        assertThatThrownBy(() -> StrictYaml.parse("a: &a [[*a]]\n", "config.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("config.yml is not valid YAML: an anchor is used inside the value it names");
    }

    @Test
    void aliasesThatRepeatTooManyValuesAreNotValid() {
        assertThatThrownBy(() -> StrictYaml.parse(nestedAliases(7, "{", "}"), "config.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("config.yml is not valid YAML: its aliases repeat more than 100000 values");
        assertThatThrownBy(() -> StrictYaml.parse(nestedAliases(9, "[", "]"), "config.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("config.yml is not valid YAML: its aliases repeat more than 100000 values");
    }

    @Test
    void aliasesBelowTheLimitAndMergedSectionsLoad() throws Exception {
        YamlConfiguration lists = StrictYaml.parse(nestedAliases(4, "[", "]"), "config.yml");
        YamlConfiguration merged = StrictYaml.parse("""
            base: &base {rows: 3, title: Shop}
            kit:
              <<: *base
              title: Kit
            same: *base
            """, "config.yml");

        assertThat(lists.getList("l4")).hasSize(9);
        assertThat(merged.getInt("kit.rows")).isEqualTo(3);
        assertThat(merged.getString("kit.title")).isEqualTo("Kit");
        assertThat(merged.getString("same.title")).isEqualTo("Shop");
    }

    @Test
    void aSecondDocumentIsNotValid() {
        assertThatThrownBy(() -> StrictYaml.parse("rows: 6\n---\nrows: 3\n", "config.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("config.yml is not valid YAML: but found another document at line 2, column 1")
            .hasNoCause();
    }

    @Test
    void aKeyWrittenTwiceKeepsTheLastOne() throws Exception {
        String text = """
            rows: 6
            rows: 3
            menu:
              title: first
            menu:
              size: 9
            """;

        YamlConfiguration yaml = StrictYaml.parse(text, "config.yml");

        assertThat(yaml.getKeys(true)).containsExactly("rows", "menu", "menu.size");
        assertThat(yaml.getInt("rows")).isEqualTo(3);
        assertThat(yaml.getInt("menu.size")).isEqualTo(9);
    }

    @Test
    void theFailureNamesTheSourceItWasGiven() {
        assertThatThrownBy(() -> StrictYaml.parse("modules: [\n", "the config.yml inside the plugin jar"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessageStartingWith("the config.yml inside the plugin jar is not valid YAML: ");
    }

    @Test
    void aParseFailureNamesTheLineAndColumnWithoutQuotingTheLine() throws Exception {
        Files.writeString(configFile(), "config-version: 1\npassword: hunter2: x\n");

        assertThatThrownBy(() -> StrictYaml.read(configFile(), "config.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("config.yml is not valid YAML: mapping values are not allowed here at line 2, column 18")
            .hasNoCause();
    }

    @Test
    void aMarkedFailureInTheCauseChainGivesItsProblemAndPosition() {
        Mark mark = new Mark("reader", 30, 4, 2, new int[0], 0);
        Throwable wrapped = new InvalidConfigurationException(new ScannerException("while scanning", null,
            "found a tab", mark));

        assertThat(StrictYaml.reason(wrapped)).isEqualTo("found a tab at line 5, column 3");
    }

    @Test
    void aMarkedFailureWithoutAProblemGivesItsContext() {
        Mark mark = new Mark("reader", 0, 0, 0, new int[0], 0);

        assertThat(StrictYaml.reason(new ParserException("while parsing a block", mark, null, null)))
            .isEqualTo("while parsing a block");
    }

    @Test
    void aMarkedFailureWithNeitherProblemNorContextGivesItsClassName() {
        Mark mark = new Mark("reader", 0, 1, 6, new int[0], 0);

        assertThat(StrictYaml.reason(new ParserException(null, null, null, mark)))
            .isEqualTo("ParserException at line 2, column 7");
        assertThat(StrictYaml.reason(new ScannerException(null, null, null, null))).isEqualTo("ScannerException");
    }

    @Test
    void aMarkedFailureWithoutAProblemMarkHasNoPosition() {
        assertThat(StrictYaml.reason(new ScannerException("while scanning", null, "found a tab", null)))
            .isEqualTo("found a tab");
    }

    @Test
    void aFailureWithoutAMarkedCauseGivesItsMessageOrClassName() {
        assertThat(StrictYaml.reason(new InvalidConfigurationException("Top level is not a Map.")))
            .isEqualTo("Top level is not a Map.");
        assertThat(StrictYaml.reason(new IllegalStateException())).isEqualTo("IllegalStateException");
    }

    @Test
    void aCauseChainWithACycleEndsTheSearch() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second", first);
        first.initCause(second);

        assertThat(StrictYaml.reason(first)).isEqualTo("first");
    }

    @Test
    void aFileOfExactlyTheLimitIsRead() throws Exception {
        Files.write(configFile(), document(StrictYaml.MAX_FILE_BYTES));

        YamlConfiguration yaml = StrictYaml.read(configFile(), "config.yml");

        assertThat(yaml.getInt("rows")).isEqualTo(6);
    }

    @Test
    void aFileOneByteOverTheLimitIsRefused() throws Exception {
        Files.write(configFile(), document(StrictYaml.MAX_FILE_BYTES + 1));

        assertThatThrownBy(() -> StrictYaml.read(configFile(), "menus/main.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("menus/main.yml is larger than 16 MiB")
            .hasNoCause();
    }

    @Test
    void reportsAMissingFile() {
        Path absent = folder.resolve("absent.yml");

        assertThatThrownBy(() -> StrictYaml.read(absent, "absent.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("cannot read absent.yml: NoSuchFileException")
            .hasCauseInstanceOf(NoSuchFileException.class);
    }

    @Test
    void anUnreadableFileStopsTheLoad() throws Exception {
        Files.createDirectory(configFile());

        assertThatThrownBy(() -> StrictYaml.read(configFile(), "config.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessageStartingWith("cannot read config.yml: ")
            .hasMessageNotContaining(folder.toString())
            .hasCauseInstanceOf(IOException.class);
    }

    private Path configFile() {
        return folder.resolve("config.yml");
    }

    /**
     * {@code rows: 6} then comment lines of at most 64 bytes, {@code size} bytes in all. Short lines keep the
     * parse fast: the parser takes minutes over a single comment line of 16 MiB.
     */
    private static byte[] document(int size) {
        byte[] content = new byte[size];
        byte[] head = "rows: 6\n".getBytes(StandardCharsets.UTF_8);
        Arrays.fill(content, (byte) 'x');
        System.arraycopy(head, 0, content, 0, head.length);
        for (int line = head.length; line < size; line += 64) {
            content[line] = '#';
            content[Math.min(line + 63, size - 1)] = '\n';
        }
        return content;
    }

    /**
     * Levels {@code l0} to {@code l<levels>}, each a collection of nine entries that are all an alias of the
     * level below, so the last level stands for nine to the power {@code levels} values.
     */
    private static String nestedAliases(int levels, String open, String close) {
        StringBuilder text = new StringBuilder();
        for (int level = 0; level <= levels; level++) {
            String value = level == 0 ? "x" : "*l" + (level - 1);
            List<String> entries = new ArrayList<>();
            for (int entry = 0; entry < 9; entry++) {
                entries.add(open.equals("{") ? "k" + entry + ": " + value : value);
            }
            text.append("l").append(level).append(": &l").append(level).append(' ').append(open)
                .append(String.join(", ", entries)).append(close).append('\n');
        }
        return text.toString();
    }
}
