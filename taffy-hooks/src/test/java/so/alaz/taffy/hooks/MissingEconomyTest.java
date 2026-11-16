package so.alaz.taffy.hooks;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class MissingEconomyTest {

    @Test
    void onPaperTheLineNamesVaultAndWhatIsMissing() {
        assertThat(MissingEconomy.line(false, false))
            .isEqualTo("Vault and an economy plugin, such as EssentialsX, are needed. Install both.");
        assertThat(MissingEconomy.line(false, true)).isEqualTo("Vault is installed, but no economy plugin has"
            + " registered with it. Install one, such as EssentialsX.");
    }

    @Test
    void onFoliaTheLineNamesVaultUnlockedAndAFoliaEconomyNotVault() {
        assertThat(MissingEconomy.line(true, false))
            .isEqualTo("VaultUnlocked and an economy plugin that supports Folia are needed. Install both.");
        assertThat(MissingEconomy.line(true, true)).isEqualTo("VaultUnlocked is installed, but no economy plugin"
            + " that supports Folia has registered with it. Install one.");
    }

    @Test
    void aVaultThePluginCannotSeeNamesTheMissingEntry() {
        assertThat(MissingEconomy.notVisible(false)).isEqualTo("Vault is installed, but this plugin cannot see it,"
            + " because its paper-plugin.yml does not list Vault under dependencies.server.");
        assertThat(MissingEconomy.notVisible(true)).isEqualTo("VaultUnlocked is installed, but this plugin cannot see"
            + " it, because its paper-plugin.yml does not list Vault under dependencies.server.");
    }

    @Test
    void aVaultWithoutTheEconomyApiAsksForAVersionThatHoldsIt() {
        assertThat(MissingEconomy.apiMissing(false)).isEqualTo("Vault is installed, but it lacks the economy API that"
            + " is read. Install a version of Vault that holds it.");
        assertThat(MissingEconomy.apiMissing(true)).isEqualTo("VaultUnlocked is installed, but it lacks the economy"
            + " API that is read. Install a version of VaultUnlocked that holds it.");
    }
}
