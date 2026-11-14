package so.alaz.taffy.platform;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Plays a sound from its config form, {@code "key [volume] [pitch]"}. The key is read in lower
 * case, since Minecraft keys are lower case. A volume or pitch that is absent or not a number is
 * {@code 1}.
 */
public final class Sounds {

    private Sounds() {
    }

    /**
     * Parses and plays {@code spec} for {@code player}; a {@code null} or blank spec is a no-op. Keeps no
     * state: the sound is sent on the calling thread through {@code Player.playSound}.
     *
     * @throws net.kyori.adventure.key.InvalidKeyException if the key does not parse once lower-cased;
     *     {@link #isValid(String)} tells beforehand
     */
    public static void play(Player player, @Nullable String spec) {
        if (spec == null) {
            return;
        }
        String[] parts = spec.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return;
        }
        float volume = parts.length > 1 ? parseFloat(parts[1]) : 1f;
        float pitch = parts.length > 2 ? parseFloat(parts[2]) : 1f;
        player.playSound(Sound.sound(Key.key(key(parts[0])), Sound.Source.MASTER, volume, pitch));
    }

    /**
     * Whether {@code spec} can be played: it is {@code null} or blank (a no-op), or its key parses once
     * lower-cased. Volume and pitch are not checked, since a token that is not a number falls back to {@code 1}.
     */
    public static boolean isValid(@Nullable String spec) {
        if (spec == null) {
            return true;
        }
        String[] parts = spec.trim().split("\\s+");
        return parts.length == 0 || parts[0].isEmpty() || Key.parseable(key(parts[0]));
    }

    private static String key(String token) {
        return token.toLowerCase(Locale.ROOT);
    }

    private static float parseFloat(String value) {
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException ex) {
            return 1f;
        }
    }
}
