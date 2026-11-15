package so.alaz.taffy.storage;

import com.zaxxer.hikari.HikariDataSource;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import so.alaz.taffy.platform.ErrorReports;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class MissingLibrariesTest {

    @TempDir
    private Path folder;

    @Test
    void withoutHikariCpTheStartFailsWithASentenceAndStopsItsThreads() throws Exception {
        Throwable failure = startWith(List.of(location("org.sqlite.JDBC")), "no-hikari");

        assertThat(failure).isNotNull().hasMessage("HikariCP is not available").hasNoSuppressedExceptions();
        assertThat(failure.getClass().getName()).isEqualTo(StorageException.class.getName());
        SqliteStartTest.awaitNoThreadNamed("no-hikari-db-");
        assertThat(folder.resolve("data.db")).doesNotExist();
    }

    @Test
    void withoutTheDriverTheStartFailsWithASentenceAndStopsItsThreads() throws Exception {
        Throwable failure = startWith(List.of(location(HikariDataSource.class.getName()),
            location("org.slf4j.Logger")), "no-driver");

        assertThat(failure).isNotNull().hasMessage("the sqlite driver is not available").hasNoSuppressedExceptions();
        assertThat(failure.getClass().getName()).isEqualTo(StorageException.class.getName());
        SqliteStartTest.awaitNoThreadNamed("no-driver-db-");
        assertThat(folder.resolve("data.db")).doesNotExist();
    }

    @Test
    void withBothLibrariesTheIsolatedStartSucceeds() throws Exception {
        Throwable failure = startWith(List.of(location("org.sqlite.JDBC"), location(HikariDataSource.class.getName()),
            location("org.slf4j.Logger")), "isolated");

        assertThat(failure).isNull();
        assertThat(folder.resolve("data.db")).exists();
        SqliteStartTest.awaitNoThreadNamed("isolated-db-");
    }

    private @Nullable Throwable startWith(List<URL> libraries, String name) throws Exception {
        List<URL> urls = new ArrayList<>(List.of(location(Storage.class.getName()),
            location(ErrorReports.class.getName()), location(IsolatedStart.class.getName())));
        urls.addAll(libraries);
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new),
            ClassLoader.getPlatformClassLoader())) {
            Class<?> start = Class.forName(IsolatedStart.class.getName(), true, loader);
            assertThat(start).isNotSameAs(IsolatedStart.class);
            Method failure = start.getDeclaredMethod("failure", Path.class, String.class);
            failure.setAccessible(true);
            return (Throwable) failure.invoke(null, folder, name);
        }
    }

    private static URL location(String className) throws ClassNotFoundException {
        return Class.forName(className, false, MissingLibrariesTest.class.getClassLoader())
            .getProtectionDomain().getCodeSource().getLocation();
    }
}
