package so.alaz.taffy.hooks;

import org.bukkit.plugin.Plugin;
import so.alaz.taffy.platform.ErrorReports;

import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Tells which players joined from Minecraft Bedrock Edition, through Floodgate or Geyser. A plugin that uses
 * {@code taffy-gui} passes {@code provider::bedrock} to {@code new Prompts(...)} as its {@code BedrockPlayers}.
 *
 * <p>A failure inside the Bedrock plugin is logged as a warning and reported, not thrown, except an {@link Error}
 * other than a {@link LinkageError}, which propagates. The provider asks the Bedrock plugin on the calling thread
 * and keeps nothing about a player.
 */
public interface BedrockProvider {

    /**
     * Detects Floodgate, then Geyser, and builds a provider on the first that can be read. The plugin that calls this
     * lists {@code floodgate} and {@code Geyser-Spigot} in its {@code paper-plugin.yml} under
     * {@code dependencies.server} with {@code load: BEFORE} and {@code required: false}, so each is enabled first
     * when it is installed. Each of the two is read only when it is installed, enabled and its API classes are
     * visible, and it is {@link HookState#FAILED} when a method the provider calls is missing. One that is not
     * visible or lacks the API is warned about; one that could not be hooked is warned about and reported to
     * {@code errors} under {@code hook.floodgate} or {@code hook.geyser-spigot}. Geyser is looked at only when
     * Floodgate did not turn on.
     *
     * <p>The result is Floodgate's when Floodgate turned on, and otherwise Geyser's when Geyser turned on. When
     * neither turned on it carries no provider and the status of Floodgate, or the status of Geyser when Floodgate
     * is not installed and Geyser is. The plugin name of a status is {@code floodgate} or {@code Geyser-Spigot}. A
     * failure inside a later check is logged as a warning, at most once a minute with its stack the first time, and
     * reported under the same source.
     *
     * @param owner the plugin that asks: its server's plugin manager is searched and its logger receives every
     *     warning
     * @param errors receives a failed detection and failures inside Floodgate and Geyser calls
     * @param switchedOn whether the owner switched Bedrock detection on; {@code false} gives Floodgate's
     *     {@link HookState#SWITCHED_OFF} and looks at nothing
     * @return the detection result, with a provider when detection found Floodgate or Geyser {@link HookState#ON};
     *     its status is read when asked
     * @throws NullPointerException if {@code owner} or {@code errors} is {@code null}
     */
    static Detected<BedrockProvider> detect(Plugin owner, ErrorReports errors, boolean switchedOn) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(errors, "errors");
        Logger logger = owner.getLogger();
        return PluginBedrock.detect(switchedOn, new PluginPresence(owner.getServer().getPluginManager(), logger),
            plugin -> FloodgateBedrock.create(plugin, logger, errors),
            plugin -> GeyserBedrock.create(plugin, logger, errors), logger, errors);
    }

    /**
     * Tells whether a player joined from Minecraft Bedrock Edition.
     *
     * @param player the player's unique id
     * @return {@code true} for a Bedrock player; {@code false} for any other player, a player who is not online,
     *     while the Bedrock plugin is disabled, and when the check fails. Only a failed check is logged and reported
     * @throws NullPointerException if {@code player} is {@code null}
     */
    boolean bedrock(UUID player);
}
