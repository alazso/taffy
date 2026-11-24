package so.alaz.taffy.hooks;

import java.util.List;

// The owner's reasons for a Vault economy that is not there. On Folia the classic Vault API comes from VaultUnlocked,
// whose plugin is named Vault, and the economy must support Folia.
final class MissingEconomy {

    private MissingEconomy() {
    }

    static String line(boolean folia, boolean vaultPluginPresent) {
        if (folia) {
            return vaultPluginPresent
                ? "VaultUnlocked is installed, but no economy plugin that supports Folia has registered with it."
                + " Install one."
                : "VaultUnlocked and an economy plugin that supports Folia are needed. Install both.";
        }
        return vaultPluginPresent
            ? "Vault is installed, but no economy plugin has registered with it. Install one, such as EssentialsX."
            : "Vault and an economy plugin, such as EssentialsX, are needed. Install both.";
    }

    // Vault holds the economy API, but the plugin that looks it up does not list Vault in its paper-plugin.yml.
    static String notVisible(boolean folia) {
        return (folia ? "VaultUnlocked" : "Vault") + " is installed, but this plugin cannot see it, because its"
            + " paper-plugin.yml does not list Vault under dependencies.server.";
    }

    // The installed Vault plugin does not hold the economy API in its own class loader.
    static String apiMissing(boolean folia) {
        String vault = folia ? "VaultUnlocked" : "Vault";
        return vault + " is installed, but it lacks the economy API that is read. Install a version of " + vault
            + " that holds it.";
    }

    // No economy to read through the VaultUnlocked economy API.
    static String unlocked(boolean folia, boolean vaultPluginPresent) {
        String economy = folia ? "economy plugin that supports VaultUnlocked and Folia"
            : "economy plugin that supports VaultUnlocked";
        return vaultPluginPresent
            ? "VaultUnlocked is installed, but no " + economy + " has registered with it. Install one."
            : "VaultUnlocked and an " + economy + " are needed. Install both.";
    }

    static String unlockedNotVisible() {
        return "VaultUnlocked is installed, but this plugin cannot see it, because its paper-plugin.yml does not list"
            + " Vault, the name of the VaultUnlocked plugin, under dependencies.server.";
    }

    // The installed plugin named Vault holds no VaultUnlocked economy API in its own class loader.
    static String unlockedApiMissing() {
        return "The installed Vault lacks the VaultUnlocked economy API that is read. Install VaultUnlocked in its"
            + " place.";
    }

    // The backend is there and has no currency of that id.
    static String noCurrency(String backend, String currency, List<String> known) {
        return backend + " has no currency named " + currency + ". "
            + (known.isEmpty() ? "It lists no currency." : "It has " + String.join(", ", known) + ".");
    }
}
