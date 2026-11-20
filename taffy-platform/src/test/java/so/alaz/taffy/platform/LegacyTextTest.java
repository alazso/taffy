package so.alaz.taffy.platform;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyTextTest {

    @Test
    void convertsAmpersandCodes() {
        assertThat(LegacyText.toMiniMessage("&aHi &e{player}&7!"))
            .isEqualTo("<green>Hi <yellow>{player}<gray>!");
        assertThat(LegacyText.toMiniMessage("&lBold &rplain"))
            .isEqualTo("<bold>Bold <reset>plain");
    }

    @Test
    void convertsUpperCaseCodeLetters() {
        assertThat(LegacyText.toMiniMessage("&LBold &Cred")).isEqualTo("<bold>Bold <red><!bold>red");
    }

    @Test
    void aColourCodeClosesTheDecorationsOpenedBeforeIt() {
        assertThat(LegacyText.toMiniMessage("&c&lADMIN &7Steve")).isEqualTo("<red><bold>ADMIN <gray><!bold>Steve");
        assertThat(LegacyText.toMiniMessage("&o&n&m&l&ka&fb")).isEqualTo(
            "<italic><underlined><strikethrough><bold><obfuscated>a"
                + "<white><!obfuscated><!bold><!strikethrough><!underlined><!italic>b");
        assertThat(LegacyText.toMiniMessage("&la&#FF8800b&cc")).isEqualTo("<bold>a<#FF8800><!bold>b<red>c");
        assertThat(LegacyText.sectionToMiniMessage("§oa§x§F§F§8§8§0§0b")).isEqualTo("<italic>a<#FF8800><!italic>b");
    }

    @Test
    void aResetCodeLeavesNoDecorationToClose() {
        assertThat(LegacyText.toMiniMessage("&la&rb&cc")).isEqualTo("<bold>a<reset>b<red>c");
    }

    @Test
    void aDecorationOpenedByATagStaysOpenAfterAColourCode() {
        assertThat(LegacyText.toMiniMessage("<bold>a&cb")).isEqualTo("<bold>a<red>b");
    }

    @Test
    void turnsThePairedHexFormIntoARunOfColourTags() {
        assertThat(LegacyText.toMiniMessage("&x&f&f&8&8&0&0Hi"))
            .isEqualTo("&x<white><white><dark_gray><dark_gray><black><black>Hi");
    }

    @Test
    void convertsACodeInsideATagArgument() {
        assertThat(LegacyText.toMiniMessage("<click:open_url:'https://example.com/?a=1&b=2&ref=z'>go</click>"))
            .isEqualTo("<click:open_url:'https://example.com/?a=1<aqua>=2<reset>ef=z'>go</click>");
    }

    @Test
    void convertsSectionSignAndHex() {
        assertThat(LegacyText.toMiniMessage("§cRed")).isEqualTo("<red>Red");
        assertThat(LegacyText.toMiniMessage("&#FF8800Hi")).isEqualTo("<#FF8800>Hi");
    }

    @Test
    void convertsAHexCodeThatEndsTheInput() {
        assertThat(LegacyText.toMiniMessage("x&#FF8800")).isEqualTo("x<#FF8800>");
        assertThat(LegacyText.toMiniMessage("x§#ff8800")).isEqualTo("x<#ff8800>");
    }

    @Test
    void convertsThePairedHexFormWrittenWithSectionSigns() {
        assertThat(LegacyText.toMiniMessage("§x§F§F§8§8§0§0Hi &ayes")).isEqualTo("<#FF8800>Hi <green>yes");
        assertThat(LegacyText.toMiniMessage("§X§f§f§8§8§0§0")).isEqualTo("<#ff8800>");
        assertThat(LegacyText.sectionToMiniMessage("a§x§1§2§3§4§5§6b")).isEqualTo("a<#123456>b");
    }

    @Test
    void aPairedHexFormThatIsCutShortOrBrokenIsConvertedPairByPair() {
        String pairs = "§x<white><white><dark_gray><dark_gray><black>";

        assertThat(LegacyText.sectionToMiniMessage("§x§F§F§8§8§0")).isEqualTo(pairs);
        assertThat(LegacyText.sectionToMiniMessage("§x§F§F§8§8§0&0")).isEqualTo(pairs + "&0");
        assertThat(LegacyText.sectionToMiniMessage("§x§F§F§8§8§0§g")).isEqualTo(pairs + "§g");
    }

    @Test
    void theSectionOnlyConversionLeavesAmpersandsAsWritten() {
        assertThat(LegacyText.sectionToMiniMessage("§cRed &aamp §LBold")).isEqualTo("<red>Red &aamp <bold>Bold");
        assertThat(LegacyText.sectionToMiniMessage("https://example.com/?a=1&b=2&#FF8800"))
            .isEqualTo("https://example.com/?a=1&b=2&#FF8800");
        assertThat(LegacyText.sectionToMiniMessage("§#FF8800Hi")).isEqualTo("<#FF8800>Hi");
    }

    @Test
    void theSectionOnlyConversionReturnsAStringWithoutASectionSignAsItIs() {
        String input = "<red>plain &c";

        assertThat(LegacyText.sectionToMiniMessage(input)).isSameAs(input);
    }

    @Test
    void miniMessageParsesEveryResultOfTheSectionOnlyConversion() {
        MiniMessage mini = MiniMessage.miniMessage();
        for (char next = ' '; next <= '~'; next++) {
            String converted = LegacyText.sectionToMiniMessage("a§" + next + "b§");

            assertThatCode(() -> mini.deserialize(converted)).as(converted).doesNotThrowAnyException();
        }
        assertThatCode(() -> mini.deserialize(LegacyText.sectionToMiniMessage("§§c§x§a§§b")))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> mini.deserialize("a§cb")).isInstanceOf(ParsingException.class);
    }

    @Test
    void leavesAnAmpersandHashThatIsNotSixHexDigitsAlone() {
        assertThat(LegacyText.toMiniMessage("&#GG8800Hi")).isEqualTo("&#GG8800Hi");
        assertThat(LegacyText.toMiniMessage("&#FF88")).isEqualTo("&#FF88");
    }

    @Test
    void leavesMiniMessageAndPlainTextAlone() {
        assertThat(LegacyText.toMiniMessage("<gradient:#a:#b>Hi</gradient>"))
            .isEqualTo("<gradient:#a:#b>Hi</gradient>");
        assertThat(LegacyText.toMiniMessage("plain & simple")).isEqualTo("plain & simple");
        assertThat(LegacyText.toMiniMessage("trailing &")).isEqualTo("trailing &");
    }
}
