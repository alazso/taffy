package so.alaz.taffy.gui;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class MenuSettingsTest {

    @Test
    void theDefaultsAreTheShippedValues() {
        assertThat(MenuSettings.DEFAULTS).isEqualTo(new MenuSettings(45, Material.GRAY_STAINED_GLASS_PANE,
            Duration.ofMillis(250), Material.CLOCK));
        assertThat(MenuSettings.DEFAULTS.pageSize()).isEqualTo(45);
        assertThat(MenuSettings.DEFAULTS.filler()).isEqualTo(Material.GRAY_STAINED_GLASS_PANE);
        assertThat(MenuSettings.DEFAULTS.clickDebounce()).isEqualTo(Duration.ofMillis(250));
        assertThat(MenuSettings.DEFAULTS.loadingItem()).isEqualTo(Material.CLOCK);
    }

    @Test
    void theBoundsOfEachRangeAreAccepted() {
        assertThat(new MenuSettings(1, Material.CLOCK, Duration.ofMillis(50), Material.CLOCK).pageSize())
            .isEqualTo(1);
        assertThat(new MenuSettings(45, Material.CLOCK, Duration.ofSeconds(2), Material.CLOCK).clickDebounce())
            .isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void aPageSizeOrDebounceOutsideItsRangeIsRefused() {
        assertThatThrownBy(() -> settings(0, Duration.ofMillis(250))).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("page size is 1 to 45, not 0");
        assertThatThrownBy(() -> settings(46, Duration.ofMillis(250))).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("page size is 1 to 45, not 46");
        assertThatThrownBy(() -> settings(45, Duration.ofMillis(49))).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("click debounce is 50ms to 2s, not 49ms");
        assertThatThrownBy(() -> settings(45, Duration.ofMillis(2001))).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("click debounce is 50ms to 2s, not 2001ms");
    }

    @Test
    void everyPartIsRequired() {
        assertThatThrownBy(() -> new MenuSettings(45, Material.CLOCK, null, Material.CLOCK))
            .isInstanceOf(NullPointerException.class).hasMessage("clickDebounce");
        assertThatThrownBy(() -> new MenuSettings(45, Material.CLOCK, Duration.ofMillis(250), null))
            .isInstanceOf(NullPointerException.class).hasMessage("loadingItem");
        assertThatThrownBy(() -> new MenuSettings(45, null, Duration.ofMillis(250), Material.CLOCK))
            .isInstanceOf(NullPointerException.class).hasMessage("filler");
    }

    private static MenuSettings settings(int pageSize, Duration debounce) {
        return new MenuSettings(pageSize, Material.GRAY_STAINED_GLASS_PANE, debounce, Material.CLOCK);
    }
}
