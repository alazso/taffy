package so.alaz.taffy.hooks;

import org.bukkit.plugin.Plugin;
import so.alaz.taffy.platform.ErrorReports;

import java.util.UUID;
import java.util.logging.Logger;

// Reads FloodgateApi.getInstance().isFloodgatePlayer(UUID) through method handles, so the module compiles without
// the Floodgate API. getInstance gives null until Floodgate has set its API, which reads as no Bedrock player and is
// not reported.
final class FloodgateBedrock {

    private FloodgateBedrock() {
    }

    static BedrockProvider create(Plugin plugin, Logger logger, ErrorReports errors) {
        return create(FloodgateBedrock.class.getClassLoader(), plugin, logger, errors);
    }

    // FloodgateApi is looked up on apiLoader: the loader of the plugin that shades the module.
    static BedrockProvider create(ClassLoader apiLoader, Plugin plugin, Logger logger, ErrorReports errors) {
        Class<?> api = ApiLink.type(PluginBedrock.FLOODGATE_PROBE, apiLoader);
        return new PluginBedrock(plugin, PluginBedrock.FLOODGATE, PluginBedrock.check(
            ApiLink.staticMethod(api, "getInstance", api),
            ApiLink.instanceMethod(api, "isFloodgatePlayer", boolean.class, UUID.class)), logger, errors);
    }
}
