package so.alaz.taffy.platform;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.function.Predicate;

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
     * A key that parses may still name no sound, such as the Bukkit enum form {@code ENTITY_PLAYER_LEVELUP}, and
     * then plays nothing: {@link #isKnown(String)} tells.
     */
    public static boolean isValid(@Nullable String spec) {
        if (spec == null) {
            return true;
        }
        String[] parts = spec.trim().split("\\s+");
        return parts.length == 0 || parts[0].isEmpty() || Key.parseable(key(parts[0]));
    }

    /**
     * Whether {@code spec} names a sound the server knows, for a warning while config loads. It is {@code true}
     * for a {@code null} or blank spec (a no-op) and for a key outside the {@code minecraft} namespace, which
     * only a resource pack can define and the server cannot check. A {@code minecraft} key is looked up in the
     * server's sound registry, once lower-cased. It is {@code false} for a key that does not parse.
     *
     * <p>A sound a resource pack adds under the {@code minecraft} namespace is not in the registry and reads as
     * unknown, though a client with the pack plays it. Reads the registry of the running server, so it cannot
     * be called without one.
     */
    public static boolean isKnown(@Nullable String spec) {
        return isKnown(spec,
            key -> RegistryAccess.registryAccess().getRegistry(RegistryKey.SOUND_EVENT).get(key) != null);
    }

    /** {@link #isKnown(String)} with {@code registered} standing for the server's sound registry. */
    static boolean isKnown(@Nullable String spec, Predicate<Key> registered) {
        if (spec == null) {
            return true;
        }
        String[] parts = spec.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return true;
        }
        String name = key(parts[0]);
        if (!Key.parseable(name)) {
            return false;
        }
        Key key = Key.key(name);
        return !key.namespace().equals(Key.MINECRAFT_NAMESPACE) || registered.test(key);
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
