package so.alaz.taffy.hooks;

import org.bukkit.plugin.Plugin;
import so.alaz.taffy.platform.ErrorReports;

import java.util.UUID;
import java.util.logging.Logger;

// Reads GeyserApi.api().isBedrockPlayer(UUID) through method handles, so the module compiles without the Geyser
// API. api throws a RuntimeException until Geyser has registered its API. isBedrockPlayer is declared by
// org.geysermc.api.GeyserApiBase, which GeyserApi extends, and is resolved on GeyserApi.
final class GeyserBedrock {

    private GeyserBedrock() {
    }

    static BedrockProvider create(Plugin plugin, Logger logger, ErrorReports errors) {
        return create(GeyserBedrock.class.getClassLoader(), plugin, logger, errors);
    }

    // GeyserApi is looked up on apiLoader: the loader of the plugin that shades the module.
    static BedrockProvider create(ClassLoader apiLoader, Plugin plugin, Logger logger, ErrorReports errors) {
        Class<?> api = ApiLink.type(PluginBedrock.GEYSER_PROBE, apiLoader);
        return new PluginBedrock(plugin, PluginBedrock.GEYSER, PluginBedrock.check(
            ApiLink.staticMethod(api, "api", api),
            ApiLink.instanceMethod(api, "isBedrockPlayer", boolean.class, UUID.class)), logger, errors);
    }
}
