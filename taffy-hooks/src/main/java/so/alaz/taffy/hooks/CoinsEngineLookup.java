package so.alaz.taffy.hooks;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ErrorReports;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

// Detects ExcellentEconomy, the plugin CoinsEngine was renamed to, and names no class of it: CoinsEngineEconomy is
// named only inside the factory, which runs after every presence check passed.
final class CoinsEngineLookup {

    static final String PLUGIN = "ExcellentEconomy";

    // The plugin name of the releases before the rename, whose API is not read.
    static final String OLD_PLUGIN = "CoinsEngine";

    static final String API = "su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI";

    static final String NIGHTCORE = "nightcore";

    // A supertype of ExcellentCurrency, which the nightcore plugin holds.
    static final String NIGHTCORE_PROBE = "su.nightexpress.nightcore.util.placeholder.PlaceholderResolvable";

    private CoinsEngineLookup() {
    }

    // What the factory found: the provider, or the ids ExcellentEconomy has when it lacks the one asked for.
    record Found(@Nullable EconomyProvider provider, List<String> currencies) {
    }

    static Detected<EconomyProvider> detect(Plugin owner, ErrorReports errors, boolean switchedOn, String currency) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(errors, "errors");
        Objects.requireNonNull(currency, "currency");
        if (currency.isBlank()) {
            throw new IllegalArgumentException("a currency id is not blank");
        }
        if (!switchedOn) {
            return new Detected<>(new HookStatus(PLUGIN, HookState.SWITCHED_OFF, null, null), null);
        }
        Logger logger = owner.getLogger();
        Server server = owner.getServer();
        return detect(new PluginPresence(server.getPluginManager()), owner, currency, errors);
    }

    static Detected<EconomyProvider> detect(Presence presence, Plugin owner, String currency, ErrorReports errors) {
        Logger logger = owner.getLogger();
        Server server = owner.getServer();
        Detected<Found> detected = Detection.run(PLUGIN, API, new Detection.Through(NIGHTCORE, NIGHTCORE_PROBE), true,
            presence, plugin -> CoinsEngineEconomy.find(owner, plugin, server, currency, logger), logger, errors);
        HookStatus status = detected.status();
        Found found = detected.provider();
        if (found == null) {
            if (status.state() == HookState.NOT_INSTALLED && presence.installed(OLD_PLUGIN)) {
                return new Detected<>(new HookStatus(PLUGIN, HookState.NOT_INSTALLED, null, renamed()), null);
            }
            return new Detected<>(status, null);
        }
        EconomyProvider provider = found.provider();
        if (provider == null) {
            return new Detected<>(new HookStatus(PLUGIN, HookState.NOT_REGISTERED, status.version(),
                MissingEconomy.noCurrency(PLUGIN, currency, found.currencies())), null);
        }
        return new Detected<>(new HookStatus(PLUGIN, HookState.ON, status.version(), null), provider,
            detected.bound(), () -> presence.plugin(PLUGIN));
    }

    // An empty list when ExcellentEconomy is not installed, not enabled or not visible, when a lookup of its classes
    // throws, which is logged, and when it throws, which is logged and reported.
    static List<EconomyCurrency> currencies(Plugin owner, ErrorReports errors) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(errors, "errors");
        Logger logger = owner.getLogger();
        Server server = owner.getServer();
        return currencies(new PluginPresence(server.getPluginManager()), server, logger, errors);
    }

    static List<EconomyCurrency> currencies(Presence presence, Server server, Logger logger, ErrorReports errors) {
        if (!presence.installed(PLUGIN) || !presence.enabled(PLUGIN)) {
            return List.of();
        }
        for (String probe : List.of(API, NIGHTCORE_PROBE)) {
            ClassLookup lookup = presence.classLookup(probe);
            Throwable failure = lookup.failure();
            if (failure != null) {
                logger.warning("The currencies of " + PLUGIN + " could not be listed. "
                    + Detection.lookupFailed(failure));
                return List.of();
            }
            if (!lookup.present()) {
                return List.of();
            }
        }
        try {
            return CoinsEngineEconomy.currencies(server);
        } catch (RuntimeException | LinkageError failure) {
            logger.log(Level.WARNING, "The currencies of " + PLUGIN + " could not be listed (" + failure + ").",
                failure);
            errors.report(Detection.source(PLUGIN), failure);
            return List.of();
        }
    }

    static String renamed() {
        return OLD_PLUGIN + " is installed under its old name, whose API is not read. Install " + PLUGIN
            + ", the name " + OLD_PLUGIN + " has now.";
    }
}
