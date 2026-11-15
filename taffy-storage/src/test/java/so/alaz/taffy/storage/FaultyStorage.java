package so.alaz.taffy.storage;

import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.test.FaultyDataSource;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * A SQLite storage whose statements and commits fail on demand. {@link #kill()} makes every later database call
 * fail, as if the process had died; {@link #reopen()} is the next process on the same file.
 */
final class FaultyStorage implements AutoCloseable {

    private static final String NAME = "faulty";

    private final Path folder;
    private final Clock clock;
    private final FaultyDataSource source;
    private final Storage storage;

    private FaultyStorage(Path folder, Clock clock) {
        this.folder = folder;
        this.clock = clock;
        String url = "jdbc:sqlite:" + folder.resolve("data.db").toAbsolutePath();
        Properties pragmas = new Properties();
        pragmas.setProperty("journal_mode", "WAL");
        pragmas.setProperty("busy_timeout", "5000");
        pragmas.setProperty("foreign_keys", "true");
        this.source = new FaultyDataSource(url, pragmas);
        this.storage = Storage.over(Dialect.SQLITE, source, "", NAME, Logger.getAnonymousLogger(),
            new ErrorReports(), clock);
    }

    /** Migrates a new database in {@code folder} and opens it. */
    static FaultyStorage open(Path folder, Clock clock, List<Migration> migrations,
                              Function<String, @Nullable InputStream> scripts) {
        StorageSettings settings = new StorageSettings(Dialect.SQLITE, "data.db", null,
            new StorageSettings.Pool(4, 1, Duration.ofSeconds(5)), 1, 0);
        Storage migrated = Storage.start(settings, folder, NAME, "9.9.9", migrations, scripts,
            Logger.getAnonymousLogger(), new ErrorReports(), clock).join();
        migrated.close(Duration.ofSeconds(5));
        return new FaultyStorage(folder, clock);
    }

    FaultyStorage reopen() {
        return new FaultyStorage(folder, clock);
    }

    Storage storage() {
        return storage;
    }

    FaultyDataSource.Faults faults() {
        return source.faults();
    }

    /** Kills this process's storage: nothing it runs from now on reaches the file. */
    void kill() {
        source.faults().kill();
        storage.close(Duration.ZERO);
    }

    @Override
    public void close() {
        storage.close(Duration.ofSeconds(5));
    }
}
