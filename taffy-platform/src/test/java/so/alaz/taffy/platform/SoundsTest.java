package so.alaz.taffy.platform;

import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import so.alaz.taffy.test.JvmDefaults;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SoundsTest {

    @Test
    void blankSpecIsAValidNoOp() {
        assertThat(Sounds.isValid("")).isTrue();
        assertThat(Sounds.isValid("   ")).isTrue();
    }

    @Test
    void aNullSpecIsReadAsBlank() {
        Player player = mock(Player.class);

        assertThat(Sounds.isValid(null)).isTrue();
        Sounds.play(player, null);

        verifyNoInteractions(player);
    }

    @Test
    void keyIsCheckedInLowerCase() {
        assertThat(Sounds.isValid("minecraft:entity.player.levelup")).isTrue();
        assertThat(Sounds.isValid("MINECRAFT:ENTITY.PLAYER.LEVELUP 1 1")).isTrue();
        assertThat(Sounds.isValid("entity.player.levelup")).isTrue();   // the namespace defaults to minecraft
    }

    @Test
    void keyIsLowerCasedTheSameUnderATurkishDefaultLocale() throws Exception {
        Player player = mock(Player.class);

        JvmDefaults.withLocale(JvmDefaults.TURKISH, () -> {
            assertThat(Sounds.isValid("MINECRAFT:ENTITY.ITEM.PICKUP")).isTrue();
            Sounds.play(player, "ENTITY.ITEM.PICKUP");
        });

        assertThat(played(player).name()).isEqualTo(Key.key("minecraft", "entity.item.pickup"));
    }

    @Test
    void unparseableKeyIsInvalid() {
        assertThat(Sounds.isValid("bad$key")).isFalse();
        assertThat(Sounds.isValid("minecraft:a:b 1 1")).isFalse();
    }

    @Test
    void volumeAndPitchAreNotChecked() {
        assertThat(Sounds.isValid("minecraft:block.note_block.pling loud high")).isTrue();
    }

    @Test
    void playingABlankSpecSendsNothing() {
        Player player = mock(Player.class);

        Sounds.play(player, "");
        Sounds.play(player, "   ");

        verifyNoInteractions(player);
    }

    @Test
    void playSendsTheLowerCasedKeyWithTheParsedVolumeAndPitch() {
        Player player = mock(Player.class);

        Sounds.play(player, "  MINECRAFT:ENTITY.PLAYER.LEVELUP   0.5 2 ");

        Sound sound = played(player);
        assertThat(sound.name()).isEqualTo(Key.key("minecraft", "entity.player.levelup"));
        assertThat(sound.source()).isEqualTo(Sound.Source.MASTER);
        assertThat(sound.volume()).isEqualTo(0.5f);
        assertThat(sound.pitch()).isEqualTo(2f);
    }

    @Test
    void aVolumeOrPitchThatIsMissingOrNotANumberIsOne() {
        Player words = mock(Player.class);
        Player keyOnly = mock(Player.class);
        Player volumeOnly = mock(Player.class);

        Sounds.play(words, "block.note_block.pling loud high");
        Sounds.play(keyOnly, "block.note_block.pling");
        Sounds.play(volumeOnly, "block.note_block.pling 0.25");

        assertThat(played(words).volume()).isEqualTo(1f);
        assertThat(played(words).pitch()).isEqualTo(1f);
        assertThat(played(words).name()).isEqualTo(Key.key("minecraft", "block.note_block.pling"));
        assertThat(played(keyOnly).volume()).isEqualTo(1f);
        assertThat(played(keyOnly).pitch()).isEqualTo(1f);
        assertThat(played(volumeOnly).volume()).isEqualTo(0.25f);
        assertThat(played(volumeOnly).pitch()).isEqualTo(1f);
    }

    @Test
    void playingAnUnparseableKeyThrowsAndSendsNothing() {
        Player player = mock(Player.class);

        assertThatThrownBy(() -> Sounds.play(player, "bad$key 1 1")).isInstanceOf(InvalidKeyException.class);
        assertThatThrownBy(() -> Sounds.play(player, "minecraft:a:b")).isInstanceOf(InvalidKeyException.class);

        verifyNoInteractions(player);
    }

    private static Sound played(Player player) {
        ArgumentCaptor<Sound> sound = ArgumentCaptor.forClass(Sound.class);
        verify(player).playSound(sound.capture());
        return sound.getValue();
    }
}
