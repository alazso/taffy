package so.alaz.taffy.hooks;

import org.bukkit.plugin.Plugin;
import org.geysermc.floodgate.api.FloodgateApi;
import so.alaz.taffy.platform.ErrorReports;

import java.util.UUID;
import java.util.logging.Logger;

// Loaded only after Floodgate was found installed, enabled and with its API present. FloodgateApi.getInstance gives
// null until Floodgate has set its API, which reads as no Bedrock player and is not reported.
final class FloodgateBedrock {

    private FloodgateBedrock() {
    }

    static BedrockProvider create(Plugin plugin, Logger logger, ErrorReports errors) {
        ApiLink.staticMethod(FloodgateApi.class, "getInstance", FloodgateApi.class);
        ApiLink.instanceMethod(FloodgateApi.class, "isFloodgatePlayer", boolean.class, UUID.class);
        return new PluginBedrock(plugin, PluginBedrock.FLOODGATE, player -> {
            FloodgateApi api = FloodgateApi.getInstance();
            return api != null && api.isFloodgatePlayer(player);
        }, logger, errors);
    }
}
