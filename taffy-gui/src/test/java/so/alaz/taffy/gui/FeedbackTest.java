package so.alaz.taffy.gui;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

final class FeedbackTest {

    private static final Sound CLICK = Sound.sound(Key.key("ui.button.click"), Sound.Source.MASTER, 0.4f, 1.0f);
    private static final Sound SUCCESS = Sound.sound(Key.key("entity.experience_orb.pickup"), Sound.Source.MASTER,
        0.6f, 1.2f);
    private static final Sound FAIL = Sound.sound(Key.key("entity.villager.no"), Sound.Source.MASTER, 0.6f, 1.0f);

    private final Player player = mock(Player.class);

    @Test
    void eachCuePlaysItsSoundFromThePlayer() {
        Feedback feedback = new Feedback(() -> Map.of(Cue.CLICK, CLICK, Cue.SUCCESS, SUCCESS, Cue.FAIL, FAIL));

        feedback.play(player, Cue.CLICK);
        feedback.play(player, Cue.SUCCESS);
        feedback.play(player, Cue.FAIL);

        verify(player).playSound(CLICK, Sound.Emitter.self());
        verify(player).playSound(SUCCESS, Sound.Emitter.self());
        verify(player).playSound(FAIL, Sound.Emitter.self());
        verifyNoMoreInteractions(player);
    }

    @Test
    void aMissingCuePlaysNothingAndAnEmptyMapSilencesEverything() {
        new Feedback(() -> Map.of(Cue.FAIL, FAIL)).play(player, Cue.CLICK);
        Feedback quiet = new Feedback(Map::of);
        for (Cue cue : Cue.values()) {
            quiet.play(player, cue);
        }

        verify(player, never()).playSound(any(Sound.class), any(Sound.Emitter.class));
    }

    @Test
    void theMapIsReadOnEveryPlay() {
        Map<Cue, Sound> sounds = new HashMap<>();
        Feedback feedback = new Feedback(() -> Map.copyOf(sounds));
        feedback.play(player, Cue.CLICK);
        verify(player, never()).playSound(any(Sound.class), any(Sound.Emitter.class));

        sounds.put(Cue.CLICK, CLICK);
        feedback.play(player, Cue.CLICK);

        verify(player).playSound(CLICK, Sound.Emitter.self());
        sounds.clear();
        feedback.play(player, Cue.CLICK);
        verifyNoMoreInteractions(player);
    }

    @Test
    void theSoundsAreRequired() {
        assertThatThrownBy(() -> new Feedback(null)).isInstanceOf(NullPointerException.class).hasMessage("sounds");
    }

    @Test
    void eachCueNamesItsKey() {
        assertThat(Cue.values()).extracting(Cue::key)
            .containsExactly("click", "success", "fail");
    }
}
