package so.alaz.taffy.platform;

import java.util.Map;

/**
 * Converts legacy {@code &} and {@code §} color and format codes, and the {@code &#RRGGBB} hex form,
 * into MiniMessage tags.
 *
 * <p>The input is scanned without regard to MiniMessage tags. A tag already present passes through
 * unchanged unless it holds {@code &} or {@code §} followed by a code character: the query string in
 * {@code <click:open_url:'https://example.com/?a=1&b=2'>} is converted too.
 *
 * <p>The paired hex form is read only when every character of it follows a section sign, as in
 * {@code §x§R§R§G§G§B§B}. The {@code &x&R&R&G&G&B&B} form is not supported: {@code &x} stays as text and
 * each pair after it is converted on its own, so that form comes out as a run of color tags.
 *
 * <p>A color code ends the format codes before it, as it does in legacy text: {@code &c&lADMIN &7Steve}
 * becomes {@code <red><bold>ADMIN <gray><!bold>Steve}. The color tag is followed by the negation of each
 * decoration that a format code earlier in the same call opened, and {@code &r} becomes {@code <reset>}. Only
 * format codes count: a decoration opened by a tag in the input, or in a string converted by another call,
 * stays open after a color code, unless a format code earlier in the same call opened the same decoration, in
 * which case the negation tag ends both.
 */
public final class LegacyText {

    private static final char SECTION = '§';

    /** The length of {@code §x§R§R§G§G§B§B}. */
    private static final int PAIRED_HEX_LENGTH = 14;

    private static final Map<Character, String> CODES = Map.ofEntries(
        Map.entry('0', "<black>"), Map.entry('1', "<dark_blue>"),
        Map.entry('2', "<dark_green>"), Map.entry('3', "<dark_aqua>"),
        Map.entry('4', "<dark_red>"), Map.entry('5', "<dark_purple>"),
        Map.entry('6', "<gold>"), Map.entry('7', "<gray>"),
        Map.entry('8', "<dark_gray>"), Map.entry('9', "<blue>"),
        Map.entry('a', "<green>"), Map.entry('b', "<aqua>"),
        Map.entry('c', "<red>"), Map.entry('d', "<light_purple>"),
        Map.entry('e', "<yellow>"), Map.entry('f', "<white>"),
        Map.entry('k', "<obfuscated>"), Map.entry('l', "<bold>"),
        Map.entry('m', "<strikethrough>"), Map.entry('n', "<underlined>"),
        Map.entry('o', "<italic>"), Map.entry('r', "<reset>"));

    /** The decoration of each format code from {@code k} to {@code o}, in that order. */
    private static final String[] DECORATIONS = {"obfuscated", "bold", "strikethrough", "underlined", "italic"};

    private LegacyText() {
    }

    /** Converts legacy color and format codes; a string without them is returned as-is. */
    public static String toMiniMessage(String input) {
        return convert(input, true);
    }

    /**
     * Converts only the codes written with a section sign, and leaves every {@code &} as written. The
     * result holds no section sign followed by a code character, which is the form MiniMessage refuses
     * to parse. A string without a section sign is returned as-is.
     */
    public static String sectionToMiniMessage(String input) {
        return input.indexOf(SECTION) < 0 ? input : convert(input, false);
    }

    private static String convert(String input, boolean ampersand) {
        StringBuilder out = new StringBuilder(input.length());
        int open = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if ((c == SECTION || (ampersand && c == '&')) && i + 1 < input.length()) {
                char next = Character.toLowerCase(input.charAt(i + 1));
                if (next == '#' && i + 7 < input.length() && isHex(input, i + 2, 6)) {
                    out.append('<').append(input, i + 1, i + 8).append('>');
                    open = closeDecorations(out, open);
                    i += 7;
                    continue;
                }
                if (c == SECTION && next == 'x' && isPairedHex(input, i)) {
                    out.append("<#");
                    for (int digit = i + 3; digit < i + PAIRED_HEX_LENGTH; digit += 2) {
                        out.append(input.charAt(digit));
                    }
                    out.append('>');
                    open = closeDecorations(out, open);
                    i += PAIRED_HEX_LENGTH - 1;
                    continue;
                }
                String tag = CODES.get(next);
                if (tag != null) {
                    out.append(tag);
                    if (next >= 'k' && next <= 'o') {
                        open |= 1 << (next - 'k');
                    } else if (next == 'r') {
                        open = 0;
                    } else {
                        open = closeDecorations(out, open);
                    }
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Appends the negation tag of each decoration whose bit is set in {@code open}, and returns the empty set.
     */
    private static int closeDecorations(StringBuilder out, int open) {
        for (int bit = 0; bit < DECORATIONS.length; bit++) {
            if ((open & 1 << bit) != 0) {
                out.append("<!").append(DECORATIONS[bit]).append('>');
            }
        }
        return 0;
    }

    /** Whether six section-sign and hex-digit pairs follow the {@code §x} at {@code from}. */
    private static boolean isPairedHex(String input, int from) {
        if (from + PAIRED_HEX_LENGTH > input.length()) {
            return false;
        }
        for (int i = from + 2; i < from + PAIRED_HEX_LENGTH; i += 2) {
            if (input.charAt(i) != SECTION || Character.digit(input.charAt(i + 1), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHex(String input, int from, int count) {
        for (int i = from; i < from + count; i++) {
            if (Character.digit(input.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
