package so.alaz.taffy.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class StrictYamlTest {

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
            .hasMessage("menus/main.yml is not saved as UTF-8")
            .hasCauseInstanceOf(CharacterCodingException.class);
    }

    @Test
    void aTagPaperCannotBuildStopsTheLoadAsInvalidYaml() throws Exception {
        Files.writeString(configFile(), "config-version: 1\nprefix: !Custom value\n");

        assertThatThrownBy(() -> StrictYaml.read(configFile(), "config.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessageStartingWith("config.yml is not valid YAML: ")
            .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void invalidYamlStopsTheLoadWithTheReason() throws Exception {
        String owner = "config-version: 1\nmodules: [\n";
        Files.writeString(configFile(), owner);

        assertThatThrownBy(() -> StrictYaml.read(configFile(), "config.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessageStartingWith("config.yml is not valid YAML: ")
            .hasCauseInstanceOf(InvalidConfigurationException.class);
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
            .hasCauseInstanceOf(InvalidConfigurationException.class)
            .satisfies(failure -> assertThat(failure)
                .hasMessage("config.yml is not valid YAML: " + failure.getCause().getMessage()));
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
            .hasMessageStartingWith("config.yml is not valid YAML: ")
            .hasCauseInstanceOf(InvalidConfigurationException.class);
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
    void reportsAMissingFile() {
        Path absent = folder.resolve("absent.yml");

        assertThatThrownBy(() -> StrictYaml.read(absent, "absent.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("cannot read " + absent + ": NoSuchFileException " + absent)
            .hasCauseInstanceOf(NoSuchFileException.class);
    }

    @Test
    void anUnreadableFileStopsTheLoad() throws Exception {
        Files.createDirectory(configFile());

        assertThatThrownBy(() -> StrictYaml.read(configFile(), "config.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessageStartingWith("cannot read " + configFile() + ": ")
            .hasCauseInstanceOf(IOException.class);
    }

    private Path configFile() {
        return folder.resolve("config.yml");
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
