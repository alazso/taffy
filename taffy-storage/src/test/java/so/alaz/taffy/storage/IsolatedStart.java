package so.alaz.taffy.storage;

import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ErrorReports;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Starts SQLite storage from whatever class loader loaded this class. It uses only JDK types and Taffy's main
 * classes, so a class loader that holds those and leaves a runtime library out can load and run it.
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
        CompletableFuture<Storage> started = Storage.start(settings, folder, name, "9.9.9", List.of(), path -> null,
            logger, new ErrorReports(), Clock.systemUTC());
        try {
            started.get(10, TimeUnit.SECONDS).close(Duration.ofSeconds(5));
            return null;
        } catch (ExecutionException failed) {
            return failed.getCause();
        }
    }
}
