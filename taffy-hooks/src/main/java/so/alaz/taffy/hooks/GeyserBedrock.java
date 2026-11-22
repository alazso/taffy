package so.alaz.taffy.hooks;

import org.bukkit.plugin.Plugin;
import org.geysermc.geyser.api.GeyserApi;
import so.alaz.taffy.platform.ErrorReports;

import java.util.UUID;
import java.util.logging.Logger;

// Loaded only after Geyser-Spigot was found installed, enabled and with its API present. GeyserApi.api throws a
// RuntimeException until Geyser has registered its API. isBedrockPlayer is declared by GeyserApiBase, of base-api.
final class GeyserBedrock {

    private GeyserBedrock() {
    }

    static BedrockProvider create(Plugin plugin, Logger logger, ErrorReports errors) {
        ApiLink.staticMethod(GeyserApi.class, "api", GeyserApi.class);
        ApiLink.instanceMethod(GeyserApi.class, "isBedrockPlayer", boolean.class, UUID.class);
        return new PluginBedrock(plugin, PluginBedrock.GEYSER, player -> GeyserApi.api().isBedrockPlayer(player),
            logger, errors);
    }
}
