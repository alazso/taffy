package so.alaz.taffy.hooks;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

final class NpcPluginTest {

    @Test
    void eachPluginHasItsInstalledNameAndKey() {
        assertThat(NpcPlugin.CITIZENS.plugin()).isEqualTo("Citizens");
        assertThat(NpcPlugin.FANCYNPCS.plugin()).isEqualTo("FancyNpcs");
        assertThat(NpcPlugin.ZNPCSPLUS.plugin()).isEqualTo("ZNPCsPlus");
        for (NpcPlugin plugin : NpcPlugin.values()) {
            assertThat(plugin.key()).isEqualTo(plugin.name().toLowerCase(Locale.ROOT));
            assertThat(plugin.key()).isEqualTo(plugin.plugin().toLowerCase(Locale.ROOT));
        }
    }

    @Test
    void eachProbeIsAClassOfThePluginsApi() throws Exception {
        for (NpcPlugin plugin : NpcPlugin.values()) {
            assertThat(Class.forName(plugin.probe(), false, getClass().getClassLoader())).as(plugin.name()).isNotNull();
        }
    }

    @Test
    void aKeyFindsItsPluginIgnoringCaseAndSpaces() {
        assertThat(NpcPlugin.byKey("citizens")).isEqualTo(NpcPlugin.CITIZENS);
        assertThat(NpcPlugin.byKey(" FancyNpcs ")).isEqualTo(NpcPlugin.FANCYNPCS);
        assertThat(NpcPlugin.byKey("ZNPCSPLUS")).isEqualTo(NpcPlugin.ZNPCSPLUS);
        assertThat(NpcPlugin.byKey("znpcs")).isNull();
        assertThat(NpcPlugin.byKey(" ")).isNull();
        assertThat(NpcPlugin.byKey(null)).isNull();
    }
}
