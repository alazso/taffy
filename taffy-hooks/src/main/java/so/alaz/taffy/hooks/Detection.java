package so.alaz.taffy.hooks;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ErrorReports;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

// The factory is the only place an adapter class is named, and it runs only after every presence check passed, so
// an adapter class never loads without its plugin and API. NOT_VISIBLE and API_MISSING log one warning and report
// nothing; FAILED, a class lookup that threw included, logs one warning and reports under hook.<plugin in lower case>.
final class Detection {

    // A class of another plugin that the integration is read through.
    record Through(String plugin, String probe) {
    }

    private Detection() {
    }

    static <T> Detected<T> run(String plugin, String probe, boolean switchedOn, Presence presence,
                               Function<Plugin, T> factory, Logger logger, ErrorReports errors) {
        return run(plugin, probe, null, switchedOn, presence, factory, logger, errors);
    }

    // A through class that is not visible stops detection as NOT_VISIBLE, after every check of the plugin passed.
    static <T> Detected<T> run(String plugin, String probe, @Nullable Through through, boolean switchedOn,
                               Presence presence, Function<Plugin, T> factory, Logger logger, ErrorReports errors) {
        if (!switchedOn) {
            return off(plugin, HookState.SWITCHED_OFF, null);
        }
        HookStatus stopped = check(plugin, probe, presence, logger, errors);
        if (stopped != null) {
            return new Detected<>(stopped, null);
        }
        String version = presence.version(plugin);
        ClassLookup throughLookup = through == null ? ClassLookup.PRESENT : presence.classLookup(through.probe());
        Throwable throughFailure = throughLookup.failure();
        if (throughFailure != null) {
            return new Detected<>(lookupFailed(plugin, version, throughFailure, logger, errors), null);
        }
        if (through != null && !throughLookup.present()) {
            logger.warning(plugin + " " + version + " stays off, because " + through.plugin() + ", which it is read"
                + " through, is not visible to this plugin: this plugin's paper-plugin.yml does not list "
                + through.plugin() + " under dependencies.server.");
            return new Detected<>(new HookStatus(plugin, HookState.NOT_VISIBLE, version,
                notVisibleThrough(plugin, through.plugin())), null);
        }
        Plugin bound = presence.plugin(plugin);
        if (bound == null) {
            return off(plugin, HookState.NOT_INSTALLED, null);
        }
        T provider;
        try {
            provider = Objects.requireNonNull(factory.apply(bound), "the factory returned null");
        } catch (Exception | LinkageError failure) {
            failed(plugin, version, failure, logger, errors);
            return off(plugin, HookState.FAILED, version);
        }
        return new Detected<>(new HookStatus(plugin, HookState.ON, version, null), provider, bound,
            () -> presence.plugin(plugin));
    }

    // The status of the first presence check that fails, or null when the plugin is installed, enabled and its probe
    // class is visible. A probe that is not visible is warned about, naming whether the plugin's own loader has it. A
    // lookup of the probe that threw gives FAILED, logged and reported.
    static @Nullable HookStatus check(String plugin, String probe, Presence presence, Logger logger,
                                      ErrorReports errors) {
        if (!presence.installed(plugin)) {
            return new HookStatus(plugin, HookState.NOT_INSTALLED, null, null);
        }
        if (!presence.enabled(plugin)) {
            return new HookStatus(plugin, HookState.NOT_ENABLED, null, null);
        }
        ClassLookup own = presence.classLookup(probe);
        if (own.present()) {
            return null;
        }
        String version = presence.version(plugin);
        Throwable ownFailure = own.failure();
        if (ownFailure != null) {
            return lookupFailed(plugin, version, ownFailure, logger, errors);
        }
        Plugin installed = presence.plugin(plugin);
        ClassLookup theirs = installed == null ? ClassLookup.ABSENT : presence.classLookupIn(installed, probe);
        Throwable theirFailure = theirs.failure();
        if (theirFailure != null) {
            return lookupFailed(plugin, version, theirFailure, logger, errors);
        }
        if (theirs.present()) {
            return notVisible(plugin, version, notVisible(plugin), logger);
        }
        return apiMissing(plugin, version, null, logger);
    }

    // The owner's reason for a FAILED status of a class lookup that threw.
    static String lookupFailed(Throwable failure) {
        return "A class lookup failed because another plugin on this server is broken (" + failure + "). Fix or"
            + " remove that plugin, then restart the server.";
    }

    // Logs the failed lookup with its stack and reports it under the integration's source.
    static HookStatus lookupFailed(String plugin, String version, Throwable failure, Logger logger,
                                   ErrorReports errors) {
        String reason = lookupFailed(failure);
        logger.log(Level.WARNING, plugin + " " + version + " could not be hooked, so it stays off. " + reason,
            failure);
        errors.report(source(plugin), failure);
        return new HookStatus(plugin, HookState.FAILED, version, reason);
    }

    // The owner's reason for a NOT_VISIBLE status.
    static String notVisible(String plugin) {
        return plugin + " is installed, but this plugin cannot see it, because its paper-plugin.yml does not list "
            + plugin + " under dependencies.server.";
    }

    // The owner's reason for a NOT_VISIBLE status of a plugin that is visible itself.
    static String notVisibleThrough(String plugin, String through) {
        return plugin + " is installed, but this plugin cannot see " + through + ", which " + plugin
            + " is read through, because its paper-plugin.yml does not list " + through
            + " under dependencies.server.";
    }

    static HookStatus notVisible(String plugin, String version, String reason, Logger logger) {
        logger.warning(plugin + " " + version + " is not visible to this plugin, so it stays off: this plugin's"
            + " paper-plugin.yml does not list " + plugin + " under dependencies.server.");
        return new HookStatus(plugin, HookState.NOT_VISIBLE, version, reason);
    }

    static HookStatus apiMissing(String plugin, String version, @Nullable String reason, Logger logger) {
        logger.warning(plugin + " " + version + " lacks the API that is read, so it stays off.");
        return new HookStatus(plugin, HookState.API_MISSING, version, reason);
    }

    // Logs the failure with its stack and reports it under the integration's source.
    static void failed(String plugin, String version, Throwable failure, Logger logger, ErrorReports errors) {
        logger.log(Level.WARNING, plugin + " " + version + " could not be hooked (" + failure + "), so it stays off.",
            failure);
        errors.report(source(plugin), failure);
    }

    static String source(String plugin) {
        return "hook." + plugin.toLowerCase(Locale.ROOT);
    }

    static String summary(String label, List<HookStatus> statuses) {
        return statuses.stream().map(HookStatus::describe).collect(Collectors.joining("; ", label + ": ", "."));
    }

    private static <T> Detected<T> off(String plugin, HookState state, @Nullable String version) {
        return new Detected<>(new HookStatus(plugin, state, version, null), null);
    }
}
