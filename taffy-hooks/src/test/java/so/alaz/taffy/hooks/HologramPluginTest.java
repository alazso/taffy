package so.alaz.taffy.hooks;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

final class HologramPluginTest {

    @Test
    void eachPluginHasItsInstalledNameAndKey() {
        assertThat(HologramPlugin.DECENTHOLOGRAMS.plugin()).isEqualTo("DecentHolograms");
        assertThat(HologramPlugin.FANCYHOLOGRAMS.plugin()).isEqualTo("FancyHolograms");
        assertThat(HologramPlugin.HOLOGRAMS.plugin()).isEqualTo("Holograms");
        assertThat(HologramPlugin.HOLOGRAMLIB.plugin()).isEqualTo("HologramLib");
        for (HologramPlugin plugin : HologramPlugin.values()) {
            assertThat(plugin.key()).isEqualTo(plugin.name().toLowerCase(Locale.ROOT));
        }
    }

    @Test
    void everyPluginFillsPlaceholdersForEachViewer() {
        assertThat(HologramPlugin.values()).allMatch(HologramPlugin::placeholdersPerViewer);
    }

    @Test
    void aKeyFindsItsPluginIgnoringCaseAndSpaces() {
        assertThat(HologramPlugin.byKey("fancyholograms")).isEqualTo(HologramPlugin.FANCYHOLOGRAMS);
        assertThat(HologramPlugin.byKey(" DecentHolograms ")).isEqualTo(HologramPlugin.DECENTHOLOGRAMS);
        assertThat(HologramPlugin.byKey("HOLOGRAMLIB")).isEqualTo(HologramPlugin.HOLOGRAMLIB);
        assertThat(HologramPlugin.byKey("holograms")).isEqualTo(HologramPlugin.HOLOGRAMS);
        assertThat(HologramPlugin.byKey("cmi")).isNull();
        assertThat(HologramPlugin.byKey(" ")).isNull();
        assertThat(HologramPlugin.byKey(null)).isNull();
    }
}
