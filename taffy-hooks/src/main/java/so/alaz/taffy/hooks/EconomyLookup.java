package so.alaz.taffy.hooks;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.Classes;
import so.alaz.taffy.platform.ErrorReports;

import java.util.Objects;
import java.util.logging.Logger;

// Reads the Vault plugin and the economy registration once and calls nothing in the economy. Vault's enabled state is
// never checked, and the status is not bound to it. The status's plugin is always Vault, on Folia too.
final class EconomyLookup {

    private static final String VAULT = "Vault";

    private EconomyLookup() {
    }

    static Detected<EconomyProvider> vault(VaultOptions options) {
        return vault(options, null);
    }

    // A status equal to the one of previous is neither warned about nor reported.
    static Detected<EconomyProvider> vault(VaultOptions options, @Nullable Detected<EconomyProvider> previous) {
        Objects.requireNonNull(options, "options");
        if (!options.switchedOn()) {
            return off(HookState.SWITCHED_OFF, null, null);
        }
        Plugin owner = options.owner();
        ErrorReports errors = options.errors();
        boolean folia = options.folia();
        Server server = owner.getServer();
        Logger logger = owner.getLogger();
        Plugin vault = server.getPluginManager().getPlugin(VAULT);
        String version = vault == null ? null : vault.getPluginMeta().getVersion();
        HookStatus before = previous == null ? null : previous.status();
        if (!Classes.present(EconomyWatcher.ECONOMY, EconomyLookup.class, logger)) {
            if (vault == null) {
                return off(HookState.NOT_INSTALLED, null, MissingEconomy.line(folia, false));
            }
            if (Classes.present(EconomyWatcher.ECONOMY, vault.getClass(), logger)) {
                HookStatus hidden = new HookStatus(VAULT, HookState.NOT_VISIBLE, version,
                    MissingEconomy.notVisible(folia));
                return new Detected<>(hidden.equals(before) ? hidden
                    : Detection.notVisible(VAULT, version, MissingEconomy.notVisible(folia), logger), null);
            }
            HookStatus lacking = new HookStatus(VAULT, HookState.API_MISSING, version,
                MissingEconomy.apiMissing(folia));
            return new Detected<>(lacking.equals(before) ? lacking
                : Detection.apiMissing(VAULT, version, MissingEconomy.apiMissing(folia), logger), null);
        }
        String shown = version == null ? "unknown" : version;
        EconomyProvider provider;
        try {
            provider = VaultEconomy.lookup(server, options.scale(), logger, options.threadRule(),
                options.fundsCheck());
        } catch (RuntimeException | LinkageError failure) {
            Detected<EconomyProvider> failed = off(HookState.FAILED, shown,
                "The economy could not be read (" + failure + ").");
            if (!failed.status().equals(before)) {
                Detection.failed(VAULT, shown, failure, logger, errors);
            }
            return failed;
        }
        if (provider == null) {
            return vault == null
                ? off(HookState.NOT_INSTALLED, null, MissingEconomy.line(folia, false))
                : off(HookState.NOT_REGISTERED, version, MissingEconomy.line(folia, true));
        }
        return new Detected<>(new HookStatus(VAULT, HookState.ON, shown, null), provider);
    }

    private static Detected<EconomyProvider> off(HookState state, @Nullable String version, @Nullable String reason) {
        return new Detected<>(new HookStatus(VAULT, state, version, reason), null);
    }
}
