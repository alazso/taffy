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
// nothing; FAILED logs one warning and reports under hook.<plugin in lower case>.
final class Detection {

    private Detection() {
    }

    static <T> Detected<T> run(String plugin, String probe, boolean switchedOn, Presence presence,
                               Function<Plugin, T> factory, Logger logger, ErrorReports errors) {
        if (!switchedOn) {
            return off(plugin, HookState.SWITCHED_OFF, null);
        }
        HookStatus stopped = check(plugin, probe, presence, logger);
        if (stopped != null) {
            return new Detected<>(stopped, null);
        }
        String version = presence.version(plugin);
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
        return new Detected<>(new HookStatus(plugin, HookState.ON, version, null), provider, bound);
    }

    // The status of the first presence check that fails, or null when the plugin is installed, enabled and its probe
    // class is visible. A probe that is not visible is warned about, naming whether the plugin's own loader has it.
    static @Nullable HookStatus check(String plugin, String probe, Presence presence, Logger logger) {
        if (!presence.installed(plugin)) {
            return new HookStatus(plugin, HookState.NOT_INSTALLED, null, null);
        }
        if (!presence.enabled(plugin)) {
            return new HookStatus(plugin, HookState.NOT_ENABLED, null, null);
        }
        if (presence.classPresent(probe)) {
            return null;
        }
        String version = presence.version(plugin);
        Plugin installed = presence.plugin(plugin);
        if (installed != null && presence.classPresentIn(installed, probe)) {
            return notVisible(plugin, version, notVisible(plugin), logger);
        }
        return apiMissing(plugin, version, null, logger);
    }

    // The owner's reason for a NOT_VISIBLE status.
    static String notVisible(String plugin) {
        return plugin + " is installed, but this plugin cannot see it, because its paper-plugin.yml does not list "
            + plugin + " under dependencies.server.";
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
