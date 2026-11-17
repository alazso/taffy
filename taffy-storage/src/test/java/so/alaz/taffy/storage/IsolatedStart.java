package so.alaz.taffy.storage;

import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ErrorReports;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Starts SQLite storage from whatever class loader loaded this class. It uses only JDK types, the Paper API and
 * Taffy's main classes, so a class loader that holds those and leaves a runtime library out can load and run it. Its
 * plugin is a JDK proxy, because no mocking library is on such a class loader.
 */
final class IsolatedStart {

    private IsolatedStart() {
    }

    /**
     * Starts storage in {@code folder} and closes it again.
     *
     * @return what the future of the start failed with, or {@code null} when it started
     */
    static @Nullable Throwable failure(Path folder, String name) throws Exception {
        StorageSettings settings = new StorageSettings(Dialect.SQLITE, "data.db", null,
            new StorageSettings.Pool(1, 0, Duration.ofSeconds(5)), 1, 0);
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        PluginMeta meta = proxy(PluginMeta.class, Map.of("getVersion", "9.9.9"));
        Plugin plugin = proxy(Plugin.class, Map.of("getName", name, "getPluginMeta", meta, "getDataPath", folder,
            "getLogger", logger));
        CompletableFuture<Storage> started = Storage.start(plugin, new ErrorReports(), settings, List.of(),
            Clock.systemUTC());
        try {
            started.get(10, TimeUnit.SECONDS).close(Duration.ofSeconds(5));
            return null;
        } catch (ExecutionException failed) {
            return failed.getCause();
        }
    }

    // Answers each method named in the map with its value and refuses every other method.
    private static <T> T proxy(Class<T> type, Map<String, Object> answers) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> {
            Object answer = answers.get(method.getName());
            if (answer == null) {
                throw new UnsupportedOperationException(method.getName());
            }
            return answer;
        }));
    }
}
