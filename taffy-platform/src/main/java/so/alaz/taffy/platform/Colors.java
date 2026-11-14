package so.alaz.taffy.platform;

import org.bukkit.Color;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;

/**
 * Parses a color from config: one of the 17 named colors, a {@code #RRGGBB} hex value, or an
 * {@code r,g,b} triple.
 */
public final class Colors {

    /**
     * The accepted forms, in lower case and without a full stop, as they follow {@code expected} in the message
     * of {@link #parse}. The hex form is shown in quotes, since YAML reads an unquoted {@code #} as the start of a
     * comment.
     */
    public static final String EXPECTED = "a named color, \"#RRGGBB\" hex in quotes or r,g,b";

    private static final Map<String, Color> NAMED = Map.ofEntries(
        Map.entry("AQUA", Color.AQUA), Map.entry("BLACK", Color.BLACK),
        Map.entry("BLUE", Color.BLUE), Map.entry("FUCHSIA", Color.FUCHSIA),
        Map.entry("GRAY", Color.GRAY), Map.entry("GREEN", Color.GREEN),
        Map.entry("LIME", Color.LIME), Map.entry("MAROON", Color.MAROON),
        Map.entry("NAVY", Color.NAVY), Map.entry("OLIVE", Color.OLIVE),
        Map.entry("ORANGE", Color.ORANGE), Map.entry("PURPLE", Color.PURPLE),
        Map.entry("RED", Color.RED), Map.entry("SILVER", Color.SILVER),
        Map.entry("TEAL", Color.TEAL), Map.entry("WHITE", Color.WHITE),
        Map.entry("YELLOW", Color.YELLOW));

    private Colors() {
    }

    /**
     * Parses {@code raw} with the whitespace around it ignored. A name is read in any letter case. A hex
     * value is six hex digits after the {@code #}. A channel is a whole number from 0 to 255 without a sign.
     *
     * @throws IllegalArgumentException when {@code raw} is {@code null} or in none of the three forms, with the
     *     message {@code invalid color '<raw>': expected } followed by {@link #EXPECTED}, quoting {@code raw} as
     *     it was given
     */
    public static Color parse(@Nullable String raw) {
        if (raw == null) {
            throw invalid(null);
        }
        String value = raw.trim();
        Color named = NAMED.get(value.toUpperCase(Locale.ROOT));
        if (named != null) {
            return named;
        }
        if (value.startsWith("#") && value.length() == 7 && isHex(value, 1)) {
            return Color.fromRGB(Integer.parseInt(value.substring(1), 16));
        }
        if (value.contains(",")) {
            String[] parts = value.split("\\s*,\\s*");
            if (parts.length == 3) {
                try {
                    return Color.fromRGB(channel(parts[0]), channel(parts[1]), channel(parts[2]));
                } catch (NumberFormatException ignored) {
                    // falls through to the shared error
                }
            }
        }
        throw invalid(raw);
    }

    private static IllegalArgumentException invalid(@Nullable String raw) {
        return new IllegalArgumentException("invalid color '" + raw + "': expected " + EXPECTED);
    }

    private static boolean isHex(String value, int from) {
        for (int i = from; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static int channel(String value) {
        String digits = value.trim();
        // Integer.parseInt accepts a leading sign.
        if (digits.startsWith("+") || digits.startsWith("-")) {
            throw new NumberFormatException("signed channel: " + digits);
        }
        int channel = Integer.parseInt(digits);
        if (channel < 0 || channel > 255) {
            throw new NumberFormatException("channel out of range: " + channel);
        }
        return channel;
    }
}
