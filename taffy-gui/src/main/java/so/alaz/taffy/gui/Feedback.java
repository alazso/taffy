package so.alaz.taffy.gui;

import net.kyori.adventure.sound.Sound;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Menu sounds. Each cue plays the sound the plugin maps it to, from the player who hears it. A cue missing from the
 * map plays nothing, so an empty map switches sounds off. {@link Menus} builds it from {@link Menus.Parts#sounds()}.
 */
final class Feedback {

    private final Supplier<Map<Cue, Sound>> sounds;

    /**
     * Creates the sounds.
     *
     * @param sounds the sound of each cue, read on every play so a reload takes effect at the next sound
     * @throws NullPointerException if {@code sounds} is {@code null}
     */
    Feedback(Supplier<Map<Cue, Sound>> sounds) {
        this.sounds = Objects.requireNonNull(sounds, "sounds");
    }

    /**
     * Plays the cue's sound to the player. Call on the player's entity thread.
     *
     * @param player the player who hears the sound
     * @param cue the moment to mark
     */
    void play(Player player, Cue cue) {
        Sound sound = sounds.get().get(cue);
        if (sound == null) {
            return;
        }
        player.playSound(sound, Sound.Emitter.self());
    }
}
