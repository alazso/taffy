package so.alaz.taffy.hooks;

import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.JvmDefaults;

import static org.assertj.core.api.Assertions.assertThat;

final class CustomItemPluginTest {

    @Test
    void thePluginsAreDeclaredInResolutionOrder() {
        assertThat(CustomItemPlugin.values()).containsExactly(CustomItemPlugin.ITEMSADDER, CustomItemPlugin.NEXO,
            CustomItemPlugin.ORAXEN, CustomItemPlugin.HEADDATABASE);
    }

    @Test
    void eachPluginHasItsNameKeyAndProbe() {
        assertThat(CustomItemPlugin.ITEMSADDER.plugin()).isEqualTo("ItemsAdder");
        assertThat(CustomItemPlugin.ITEMSADDER.key()).isEqualTo("itemsadder");
        assertThat(CustomItemPlugin.ITEMSADDER.probe()).isEqualTo("dev.lone.itemsadder.api.CustomStack");
        assertThat(CustomItemPlugin.NEXO.plugin()).isEqualTo("Nexo");
        assertThat(CustomItemPlugin.NEXO.key()).isEqualTo("nexo");
        assertThat(CustomItemPlugin.NEXO.probe()).isEqualTo("com.nexomc.nexo.api.NexoItems");
        assertThat(CustomItemPlugin.ORAXEN.plugin()).isEqualTo("Oraxen");
        assertThat(CustomItemPlugin.ORAXEN.key()).isEqualTo("oraxen");
        assertThat(CustomItemPlugin.ORAXEN.probe()).isEqualTo("io.th0rgal.oraxen.api.OraxenItems");
        assertThat(CustomItemPlugin.HEADDATABASE.plugin()).isEqualTo("HeadDatabase");
        assertThat(CustomItemPlugin.HEADDATABASE.key()).isEqualTo("headdatabase");
        assertThat(CustomItemPlugin.HEADDATABASE.probe()).isEqualTo("me.arcaniax.hdb.api.HeadDatabaseAPI");
    }

    @Test
    void everyKeyFindsItsPlugin() {
        for (CustomItemPlugin plugin : CustomItemPlugin.values()) {
            assertThat(CustomItemPlugin.byKey(plugin.key())).as(plugin.key()).isSameAs(plugin);
        }
    }

    @Test
    void theAliasesFindTheirPlugins() {
        assertThat(CustomItemPlugin.byKey("ia")).isSameAs(CustomItemPlugin.ITEMSADDER);
        assertThat(CustomItemPlugin.byKey("hdb")).isSameAs(CustomItemPlugin.HEADDATABASE);
    }

    @Test
    void upperCaseAndSurroundingSpaceAreIgnoredUnderTheTurkishLocale() throws Exception {
        JvmDefaults.withLocale(JvmDefaults.TURKISH, () -> {
            assertThat(CustomItemPlugin.byKey("ITEMSADDER")).isSameAs(CustomItemPlugin.ITEMSADDER);
            assertThat(CustomItemPlugin.byKey("IA")).isSameAs(CustomItemPlugin.ITEMSADDER);
            assertThat(CustomItemPlugin.byKey(" Nexo ")).isSameAs(CustomItemPlugin.NEXO);
            assertThat(CustomItemPlugin.byKey("HDB")).isSameAs(CustomItemPlugin.HEADDATABASE);
        });
    }

    @Test
    void anUnknownBlankOrMissingHintFindsNothing() {
        assertThat(CustomItemPlugin.byKey("other")).isNull();
        assertThat(CustomItemPlugin.byKey("nexo:")).isNull();
        assertThat(CustomItemPlugin.byKey("   ")).isNull();
        assertThat(CustomItemPlugin.byKey("")).isNull();
        assertThat(CustomItemPlugin.byKey(null)).isNull();
    }
}
