package so.alaz.taffy.platform;

import org.bukkit.Color;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.JvmDefaults;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ColorsTest {

    private static final List<String> NAMES = List.of(
        "AQUA", "BLACK", "BLUE", "FUCHSIA", "GRAY", "GREEN", "LIME", "MAROON", "NAVY",
        "OLIVE", "ORANGE", "PURPLE", "RED", "SILVER", "TEAL", "WHITE", "YELLOW");

    @Test
    void everyNamedColorIsTheConstantOfThatName() throws ReflectiveOperationException {
        assertThat(NAMES).hasSize(17);
        for (String name : NAMES) {
            assertThat(Colors.parse(name)).as(name).isEqualTo(Color.class.getField(name).get(null));
        }
    }

    @Test
    void aNameIsReadInAnyCaseAndWithSpacesAround() {
        assertThat(Colors.parse("white")).isEqualTo(Color.WHITE);
        assertThat(Colors.parse("  Red ")).isEqualTo(Color.RED);
        assertThat(Colors.parse("\tfuChSia\n")).isEqualTo(Color.FUCHSIA);
    }

    @Test
    void sixHexDigitsAfterAHashAreRedGreenBlue() {
        assertThat(Colors.parse("#FF8800")).isEqualTo(Color.fromRGB(0xFF8800));
        assertThat(Colors.parse(" #ff8800 ")).isEqualTo(Color.fromRGB(255, 136, 0));
        assertThat(Colors.parse("#000000")).isEqualTo(Color.BLACK);
    }

    @Test
    void aHexValueWithABadDigitOrAnotherLengthIsRejected() {
        assertRejected("#GGGGGG");
        assertRejected("#FFF");
        assertRejected("#FF88001");
        assertRejected("FF8800");
    }

    @Test
    void aSignedHexValueIsRejected() {
        assertRejected("#+1F2E3");
        assertRejected("#-1F2E3");
        assertRejected("#-00001");
    }

    @Test
    void aChannelWithASignIsRejected() {
        assertRejected("+5,+5,+5");
        assertRejected("1,2,+3");
        assertRejected("-0,0,0");
    }

    @Test
    void aNameIsReadTheSameUnderATurkishDefaultLocale() throws Exception {
        JvmDefaults.withLocale(JvmDefaults.TURKISH, () -> {
            assertThat(Colors.parse("lime")).isEqualTo(Color.LIME);
            assertThat(Colors.parse("white")).isEqualTo(Color.WHITE);
            assertThat(Colors.parse("Fuchsia")).isEqualTo(Color.FUCHSIA);
        });
    }

    @Test
    void threeChannelsAreReadWithSpacesAroundThem() {
        assertThat(Colors.parse("255,100,0")).isEqualTo(Color.fromRGB(255, 100, 0));
        assertThat(Colors.parse(" 122 , 33,  57 ")).isEqualTo(Color.fromRGB(122, 33, 57));
        assertThat(Colors.parse("0,0,0")).isEqualTo(Color.BLACK);
        assertThat(Colors.parse("255,255,255")).isEqualTo(Color.WHITE);
    }

    @Test
    void aChannelAbove255IsRejected() {
        assertRejected("256,0,0");
        assertRejected("0,0,1000");
    }

    @Test
    void aChannelBelowZeroIsRejected() {
        assertRejected("-1,0,0");
        assertRejected("0,-20,0");
    }

    @Test
    void aChannelThatIsNotAWholeNumberIsRejected() {
        assertRejected("1,x,3");
        assertRejected("1,,3");
        assertRejected("1.5,2,3");
    }

    @Test
    void twoOrFourChannelsAreRejected() {
        assertRejected("1,2");
        assertRejected("1,2,3,4");
    }

    @Test
    void anythingElseIsRejected() {
        assertRejected("CHARTREUSE");
        assertRejected("");
        assertRejected("dark red");
    }

    @Test
    void theErrorQuotesTheInputAsItWasGiven() {
        assertThatThrownBy(() -> Colors.parse("  nope "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("invalid color '  nope ': expected a named color, \"#RRGGBB\" hex in quotes or r,g,b");
    }

    @Test
    void nullIsRejectedWithTheSameErrorAsAnyOtherValue() {
        assertThatThrownBy(() -> Colors.parse(null))
            .isExactlyInstanceOf(IllegalArgumentException.class)
            .hasMessage("invalid color 'null': expected " + Colors.EXPECTED);
    }

    private static void assertRejected(String raw) {
        assertThatThrownBy(() -> Colors.parse(raw))
            .as(raw)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("invalid color '" + raw + "': expected " + Colors.EXPECTED);
    }
}
