package so.alaz.taffy.comficonf;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import so.alaz.taffy.config.ConfigLoadException;
import so.alaz.taffy.config.StrictYaml;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

final class YamlBytesTest {

    @TempDir
    private Path folder;

    @Test
    void readingAFileOfExactlyTheLimitGivesAllItsBytes() throws Exception {
        byte[] content = new byte[YamlBytes.MAX_FILE_BYTES];
        Arrays.fill(content, (byte) '#');
        Path file = Files.write(folder.resolve("config.yml"), content);

        assertThat(YamlBytes.read(file, "config.yml")).isEqualTo(content);
    }

    @Test
    void readingAFileOneByteOverTheLimitIsRefused() throws Exception {
        Path file = Files.write(folder.resolve("config.yml"), new byte[YamlBytes.MAX_FILE_BYTES + 1]);

        assertThatThrownBy(() -> YamlBytes.read(file, "menus/main.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("menus/main.yml is larger than 16 MiB")
            .hasNoCause();
    }

    @Test
    void theLimitAndItsMessageMatchThoseOfStrictYaml() throws Exception {
        byte[] notUtf8 = new byte[YamlBytes.MAX_FILE_BYTES];
        Arrays.fill(notUtf8, (byte) 0xFF);
        Path atLimit = Files.write(folder.resolve("at.yml"), notUtf8);
        Path over = Files.write(folder.resolve("over.yml"), new byte[YamlBytes.MAX_FILE_BYTES + 1]);

        assertThatThrownBy(() -> StrictYaml.read(atLimit, "config.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("config.yml is not saved as UTF-8");
        Throwable refused = catchThrowable(() -> StrictYaml.read(over, "config.yml"));
        assertThat(refused).isInstanceOf(ConfigLoadException.class);
        assertThatThrownBy(() -> YamlBytes.read(over, "config.yml")).hasMessage(refused.getMessage());
    }

    @Test
    void readingAMissingFileThrowsTheIoFailure() {
        assertThatThrownBy(() -> YamlBytes.read(folder.resolve("absent.yml"), "absent.yml"))
            .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void textWithoutLineBreaksDoesNotUseCarriageReturns() {
        assertThat(YamlBytes.usesCarriageReturns(bytes(""))).isFalse();
        assertThat(YamlBytes.usesCarriageReturns(bytes("rows: 6"))).isFalse();
    }

    @Test
    void textWithOnlyCrlfBreaksUsesCarriageReturns() {
        assertThat(YamlBytes.usesCarriageReturns(bytes("a: 1\r\nb: 2\r\n"))).isTrue();
    }

    @Test
    void textWithOnlyLfBreaksDoesNotUseCarriageReturns() {
        assertThat(YamlBytes.usesCarriageReturns(bytes("a: 1\nb: 2\n"))).isFalse();
    }

    @Test
    void anEqualNumberOfCrlfAndLfBreaksDoesNotUseCarriageReturns() {
        assertThat(YamlBytes.usesCarriageReturns(bytes("a: 1\r\nb: 2\n"))).isFalse();
    }

    @Test
    void mostlyCrlfBreaksUseCarriageReturns() {
        assertThat(YamlBytes.usesCarriageReturns(bytes("a: 1\r\nb: 2\r\nc: 3\n"))).isTrue();
    }

    @Test
    void aLoneCarriageReturnIsNotALineBreak() {
        assertThat(YamlBytes.usesCarriageReturns(bytes("a: 1\rb: 2\n"))).isFalse();
        assertThat(YamlBytes.usesCarriageReturns(bytes("a: 1\r"))).isFalse();
    }

    @Test
    void aLineFeedAtTheStartHasNoCarriageReturnBeforeIt() {
        assertThat(YamlBytes.usesCarriageReturns(bytes("\na: 1\r\n"))).isFalse();
        assertThat(YamlBytes.usesCarriageReturns(bytes("\na: 1\r\nb: 2\r\n"))).isTrue();
    }

    @Test
    void encodingLikeACrlfFileWritesCrlf() {
        byte[] encoded = YamlBytes.encodeLike("# note\na: 1\nb: 2\n", bytes("a: 0\r\nb: 0\r\n"));

        assertThat(new String(encoded, StandardCharsets.UTF_8)).isEqualTo("# note\r\na: 1\r\nb: 2\r\n");
    }

    @Test
    void encodingLikeACrlfFileDoesNotDoubleCarriageReturnsAlreadyPresent() {
        byte[] encoded = YamlBytes.encodeLike("a: 1\r\nb: 2\n", bytes("a: 0\r\n"));

        assertThat(new String(encoded, StandardCharsets.UTF_8)).isEqualTo("a: 1\r\nb: 2\r\n");
    }

    @Test
    void encodingLikeAnLfFileKeepsLf() {
        byte[] encoded = YamlBytes.encodeLike("# note\na: 1\nb: 2\n", bytes("a: 0\nb: 0\n"));

        assertThat(new String(encoded, StandardCharsets.UTF_8)).isEqualTo("# note\na: 1\nb: 2\n");
    }

    @Test
    void encodingLikeAFileWithAByteOrderMarkWritesTheMarkOnce() {
        byte[] original = bytes("﻿a: 0\r\n");

        assertThat(YamlBytes.encodeLike("a: 1\n", original)).isEqualTo(bytes("﻿a: 1\r\n"));
        assertThat(YamlBytes.encodeLike("﻿a: 1\n", original)).isEqualTo(bytes("﻿a: 1\r\n"));
    }

    @Test
    void encodingLikeAFileWithoutAByteOrderMarkWritesNone() {
        assertThat(YamlBytes.encodeLike("a: 1\n", bytes("a: 0\n"))).isEqualTo(bytes("a: 1\n"));
        assertThat(YamlBytes.encodeLike("a: 1\n", new byte[] {(byte) 0xEF, (byte) 0xBB})).isEqualTo(bytes("a: 1\n"));
        assertThat(YamlBytes.encodeLike("a: 1\n", new byte[] {(byte) 0xEF, (byte) 0xBB, 'a'}))
            .isEqualTo(bytes("a: 1\n"));
    }

    @Test
    void parsingAFileWithAByteOrderMarkReadsItsFirstKey() throws Exception {
        YamlConfiguration yaml = YamlBytes.parse(bytes("﻿rows: 6\n"), "config.yml");

        assertThat(yaml.getKeys(false)).containsExactly("rows");
    }

    @Test
    void encodingWritesUtf8() {
        assertThat(YamlBytes.encodeLike("title: 'Café'\n", bytes("")))
            .isEqualTo("title: 'Café'\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsingKeepsCommentsSoSavingWritesThemBack() throws Exception {
        String text = """
            # Header kept on purpose.

            # How many rows the menu has.
            rows: 6 # inline note
            title: Café
            """;

        YamlConfiguration yaml = YamlBytes.parse(bytes(text), "config.yml");

        assertThat(yaml.getInt("rows")).isEqualTo(6);
        assertThat(yaml.getString("title")).isEqualTo("Café");
        assertThat(yaml.getComments("rows")).containsExactly("How many rows the menu has.");
        assertThat(yaml.saveToString()).isEqualTo(text);
    }

    @Test
    void parsingACrlfFileReadsItsValues() throws Exception {
        YamlConfiguration yaml = YamlBytes.parse(bytes("# note\r\nrows: 6\r\n"), "config.yml");

        assertThat(yaml.getInt("rows")).isEqualTo(6);
        assertThat(yaml.getComments("rows")).containsExactly("note");
    }

    @Test
    void everyParseGivesANewConfiguration() throws Exception {
        byte[] content = bytes("rows: 6\n");
        YamlBytes.parse(content, "config.yml").set("rows", 3);

        assertThat(YamlBytes.parse(content, "config.yml").getInt("rows")).isEqualTo(6);
    }

    @Test
    void bytesThatAreNotUtf8AreRefusedWithTheSourceName() {
        byte[] content = "# café\nrows: 6\n".getBytes(StandardCharsets.ISO_8859_1);

        assertThatThrownBy(() -> YamlBytes.parse(content, "menus/main.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("menus/main.yml is not saved as UTF-8")
            .hasCauseInstanceOf(CharacterCodingException.class);
    }

    @Test
    void anUnpairedSurrogateIsRefusedRatherThanReplaced() {
        byte[] content = {'a', ':', ' ', (byte) 0xED, (byte) 0xA0, (byte) 0x80, '\n'};

        assertThatThrownBy(() -> YamlBytes.parse(content, "config.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("config.yml is not saved as UTF-8");
    }

    @Test
    void textTheParserRejectsIsNotValidYaml() {
        assertThatThrownBy(() -> YamlBytes.parse(bytes("rows: 6\nmodules: [\n"), "config.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("config.yml is not valid YAML: expected the node content, but found '<stream end>'"
                + " at line 3, column 1")
            .hasNoCause();
    }

    @Test
    void aTagPaperCannotBuildIsNotValidYaml() {
        assertThatThrownBy(() -> YamlBytes.parse(bytes("prefix: !Custom value\n"), "config.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessageStartingWith("config.yml is not valid YAML: ")
            .hasNoCause();
    }

    @Test
    void anAnchorUsedInsideItselfIsRefused() {
        assertThatThrownBy(() -> YamlBytes.parse(bytes("a: &a\n  b: *a\n"), "config.yml"))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessage("config.yml is not valid YAML: an anchor is used inside the value it names");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
