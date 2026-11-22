package so.alaz.taffy.hooks;

import org.bukkit.plugin.Plugin;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.platform.ThrowLog;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;

// Names no Floodgate or Geyser class: the check comes from FloodgateBedrock or GeyserBedrock, which load only after
// their plugin was found installed, enabled and with its API present.
final class PluginBedrock implements BedrockProvider {

    static final String FLOODGATE = "floodgate";
    static final String FLOODGATE_PROBE = "org.geysermc.floodgate.api.FloodgateApi";
    static final String GEYSER = "Geyser-Spigot";
    static final String GEYSER_PROBE = "org.geysermc.geyser.api.GeyserApi";

    private final Plugin bound;
    private final String name;
    private final String source;
    private final Predicate<UUID> check;
    private final ThrowLog throwLog;
    private final ErrorReports errors;

    PluginBedrock(Plugin bound, String name, Predicate<UUID> check, Logger logger, ErrorReports errors) {
        this.bound = bound;
        this.name = name;
        this.source = Detection.source(name);
        this.check = check;
        this.throwLog = new ThrowLog(logger);
        this.errors = errors;
    }

    static Detected<BedrockProvider> detect(boolean switchedOn, Presence presence,
                                            Function<Plugin, BedrockProvider> floodgate,
                                            Function<Plugin, BedrockProvider> geyser, Logger logger,
                                            ErrorReports errors) {
        Detected<BedrockProvider> first = Detection.run(FLOODGATE, FLOODGATE_PROBE, switchedOn, presence, floodgate,
            logger, errors);
        if (!switchedOn || first.provider() != null) {
            return first;
        }
        Detected<BedrockProvider> second = Detection.run(GEYSER, GEYSER_PROBE, true, presence, geyser, logger,
            errors);
        if (second.provider() != null) {
            return second;
        }
        boolean onlyGeyser = first.status().state() == HookState.NOT_INSTALLED
            && second.status().state() != HookState.NOT_INSTALLED;
        return onlyGeyser ? second : first;
    }

    @Override
    public boolean bedrock(UUID player) {
        Objects.requireNonNull(player, "player");
        if (!bound.isEnabled()) {
            return false;
        }
        try {
            return check.test(player);
        } catch (Exception | LinkageError ex) {
            throwLog.warn("bedrock", name + " could not tell whether a player joined from Bedrock (" + ex + ").", ex);
            errors.report(source, ex);
            return false;
        }
    }
}
