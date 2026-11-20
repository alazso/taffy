package so.alaz.taffy.gui;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class MenuSettingsTest {

    @Test
    void theDefaultsAreTheShippedValues() {
        assertThat(MenuSettings.DEFAULTS.pageSize()).isEqualTo(45);
        assertThat(MenuSettings.DEFAULTS.filler()).isEqualTo(Material.GRAY_STAINED_GLASS_PANE);
        assertThat(MenuSettings.DEFAULTS.clickDebounce()).isEqualTo(Duration.ofMillis(250));
        assertThat(MenuSettings.DEFAULTS.loadingItem()).isEqualTo(Material.CLOCK);
    }

    @Test
    void theBoundsOfEachRangeAreAccepted() {
        assertThat(MenuSettings.DEFAULTS.pageSize(1).pageSize()).isEqualTo(1);
        assertThat(MenuSettings.DEFAULTS.pageSize(45).pageSize()).isEqualTo(45);
        assertThat(MenuSettings.DEFAULTS.clickDebounce(Duration.ofMillis(50)).clickDebounce())
            .isEqualTo(Duration.ofMillis(50));
        assertThat(MenuSettings.DEFAULTS.clickDebounce(Duration.ofSeconds(2)).clickDebounce())
            .isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void aPageSizeOrDebounceOutsideItsRangeIsRefused() {
        assertThatThrownBy(() -> MenuSettings.DEFAULTS.pageSize(0)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("page size is 1 to 45, not 0");
        assertThatThrownBy(() -> MenuSettings.DEFAULTS.pageSize(46)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("page size is 1 to 45, not 46");
        assertThatThrownBy(() -> MenuSettings.DEFAULTS.clickDebounce(Duration.ofMillis(49)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("click debounce is 50ms to 2s, not 49ms");
        assertThatThrownBy(() -> MenuSettings.DEFAULTS.clickDebounce(Duration.ofMillis(2001)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("click debounce is 50ms to 2s, not 2001ms");
    }

    @Test
    void everyPartIsRequired() {
        assertThatThrownBy(() -> MenuSettings.DEFAULTS.clickDebounce(null))
            .isInstanceOf(NullPointerException.class).hasMessage("clickDebounce");
        assertThatThrownBy(() -> MenuSettings.DEFAULTS.loadingItem(null))
            .isInstanceOf(NullPointerException.class).hasMessage("loadingItem");
        assertThatThrownBy(() -> MenuSettings.DEFAULTS.filler(null))
            .isInstanceOf(NullPointerException.class).hasMessage("filler");
    }

    @Test
    void eachMethodReplacesOnlyItsOwnValueAndLeavesTheSettingsItWasCalledOn() {
        MenuSettings sized = MenuSettings.DEFAULTS.pageSize(28);
        MenuSettings filled = sized.filler(Material.BLACK_STAINED_GLASS_PANE);
        MenuSettings slowed = filled.clickDebounce(Duration.ofMillis(500));
        MenuSettings loading = slowed.loadingItem(Material.HOPPER);

        assertThat(MenuSettings.DEFAULTS.pageSize()).isEqualTo(45);
        assertThat(sized.filler()).isEqualTo(Material.GRAY_STAINED_GLASS_PANE);
        assertThat(filled.clickDebounce()).isEqualTo(Duration.ofMillis(250));
        assertThat(slowed.loadingItem()).isEqualTo(Material.CLOCK);
        assertThat(loading.pageSize()).isEqualTo(28);
        assertThat(loading.filler()).isEqualTo(Material.BLACK_STAINED_GLASS_PANE);
        assertThat(loading.clickDebounce()).isEqualTo(Duration.ofMillis(500));
        assertThat(loading.loadingItem()).isEqualTo(Material.HOPPER);
    }

    @Test
    void settingsAreValues() {
        MenuSettings settings = MenuSettings.DEFAULTS.pageSize(28).loadingItem(Material.HOPPER);
        MenuSettings same = MenuSettings.DEFAULTS.loadingItem(Material.HOPPER).pageSize(28);

        assertThat(settings).isEqualTo(same).hasSameHashCodeAs(same).isNotEqualTo(MenuSettings.DEFAULTS);
        assertThat(settings).hasToString("MenuSettings[pageSize=28, filler=GRAY_STAINED_GLASS_PANE, "
            + "clickDebounce=PT0.25S, loadingItem=HOPPER]");
    }

    @Test
    void noConstructorIsPublic() {
        assertThat(MenuSettings.class.isRecord()).isFalse();
        assertThat(Arrays.stream(MenuSettings.class.getDeclaredConstructors()).map(c -> c.getModifiers()))
            .allMatch(Modifier::isPrivate);
    }
}
