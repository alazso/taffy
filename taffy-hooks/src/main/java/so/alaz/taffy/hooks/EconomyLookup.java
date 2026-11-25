package so.alaz.taffy.hooks;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.Classes;
import so.alaz.taffy.platform.ErrorReports;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

// Reads the Vault plugin and the economy registration once and calls nothing in the economy. Vault's enabled state is
// never checked, and the status is not bound to it. The status's plugin is always Vault, on Folia too.
final class EconomyLookup {

    private static final String VAULT = "Vault";

    private static final String UNLOCKED = "VaultUnlocked";

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
        ClassLookup own = ClassLookup.of(EconomyWatcher.ECONOMY, EconomyLookup.class.getClassLoader());
        if (!own.present()) {
            if (vault == null) {
                return off(HookState.NOT_INSTALLED, null, MissingEconomy.line(folia, false));
            }
            ClassLookup theirs = own.failure() != null ? own
                : ClassLookup.of(EconomyWatcher.ECONOMY, vault.getClass().getClassLoader());
            Throwable failure = theirs.failure();
            if (failure != null) {
                String shown = version == null ? "unknown" : version;
                HookStatus failed = new HookStatus(VAULT, HookState.FAILED, shown, Detection.lookupFailed(failure));
                return new Detected<>(failed.equals(before) ? failed
                    : Detection.lookupFailed(VAULT, shown, failure, logger, errors), null);
            }
            if (theirs.present()) {
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

    // One currency of the economy registered with VaultUnlocked. The VaultUnlocked plugin is named Vault, and the
    // status's plugin is VaultUnlocked. Unlike the Vault lookup, this one asks the economy whether it has the
    // currency.
    static Detected<EconomyProvider> vaultUnlocked(Plugin owner, ErrorReports errors, boolean switchedOn,
                                                   boolean folia, String currency, @Nullable Integer scale) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(errors, "errors");
        Objects.requireNonNull(currency, "currency");
        if (currency.isBlank()) {
            throw new IllegalArgumentException("a currency id is not blank");
        }
        if (scale != null && (scale < 0 || scale > EconomyProvider.MAX_SCALE)) {
            throw new IllegalArgumentException(
                "a scale runs from 0 to " + EconomyProvider.MAX_SCALE + ", not " + scale);
        }
        if (!switchedOn) {
            return unlocked(HookState.SWITCHED_OFF, null, null);
        }
        Server server = owner.getServer();
        Logger logger = owner.getLogger();
        Plugin vault = server.getPluginManager().getPlugin(VAULT);
        String version = vault == null ? null : vault.getPluginMeta().getVersion();
        ClassLookup own = ClassLookup.of(EconomyWatcher.UNLOCKED_ECONOMY, EconomyLookup.class.getClassLoader());
        if (!own.present()) {
            if (vault == null) {
                return unlocked(HookState.NOT_INSTALLED, null, MissingEconomy.unlocked(folia, false));
            }
            ClassLookup theirs = own.failure() != null ? own
                : ClassLookup.of(EconomyWatcher.UNLOCKED_ECONOMY, vault.getClass().getClassLoader());
            Throwable failure = theirs.failure();
            if (failure != null) {
                String shown = version == null ? "unknown" : version;
                return new Detected<>(Detection.lookupFailed(UNLOCKED, shown, failure, logger, errors), null);
            }
            if (theirs.present()) {
                logger.warning(UNLOCKED + " " + version + " is not visible to this plugin, so it stays off: this"
                    + " plugin's paper-plugin.yml does not list Vault, the name of the VaultUnlocked plugin, under"
                    + " dependencies.server.");
                return unlocked(HookState.NOT_VISIBLE, version, MissingEconomy.unlockedNotVisible());
            }
            logger.warning("The installed Vault " + version + " lacks the VaultUnlocked economy API, so " + UNLOCKED
                + " stays off.");
            return unlocked(HookState.API_MISSING, version, MissingEconomy.unlockedApiMissing());
        }
        String shown = version == null ? "unknown" : version;
        VaultUnlockedEconomy.Found found;
        try {
            found = VaultUnlockedEconomy.lookup(server, owner.getName(), currency, scale, logger);
        } catch (RuntimeException | LinkageError failure) {
            Detection.failed(UNLOCKED, shown, failure, logger, errors);
            return unlocked(HookState.FAILED, shown, "The economy could not be read (" + failure + ").");
        }
        if (found == null) {
            return vault == null
                ? unlocked(HookState.NOT_INSTALLED, null, MissingEconomy.unlocked(folia, false))
                : unlocked(HookState.NOT_REGISTERED, version, MissingEconomy.unlocked(folia, true));
        }
        EconomyProvider provider = found.provider();
        if (provider == null) {
            return unlocked(HookState.NOT_REGISTERED, shown,
                MissingEconomy.noCurrency(found.economy(), currency, found.currencies()));
        }
        return new Detected<>(new HookStatus(UNLOCKED, HookState.ON, shown, null), provider);
    }

    // An empty list when the VaultUnlocked economy API is not visible or no economy is registered, and when the
    // economy throws, which is logged and reported.
    static List<EconomyCurrency> vaultUnlockedCurrencies(Plugin owner, ErrorReports errors) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(errors, "errors");
        Logger logger = owner.getLogger();
        if (!Classes.present(EconomyWatcher.UNLOCKED_ECONOMY, EconomyLookup.class, logger)) {
            return List.of();
        }
        try {
            return VaultUnlockedEconomy.currencies(owner.getServer(), owner.getName());
        } catch (RuntimeException | LinkageError failure) {
            logger.log(Level.WARNING, "The currencies of the " + UNLOCKED + " economy could not be listed ("
                + failure + ").", failure);
            errors.report(Detection.source(UNLOCKED), failure);
            return List.of();
        }
    }

    private static Detected<EconomyProvider> unlocked(HookState state, @Nullable String version,
                                                      @Nullable String reason) {
        return new Detected<>(new HookStatus(UNLOCKED, state, version, reason), null);
    }
}
