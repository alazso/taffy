package so.alaz.taffy.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import so.alaz.taffy.platform.DiagnosticReport;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.test.CapturedLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

final class StorageDiagnosticsTest {

    private static final String COUNTS = "\\d+ active, \\d+ idle, 0 waiting, ";

    @TempDir
    private Path folder;

    private final CapturedLog log = new CapturedLog();

    private CompletableFuture<Storage> start(String file) {
        return Storage.start(TestPlugins.plugin("diag-probe", folder, log.logger), new ErrorReports(),
            StorageSettings.sqlite(file).pool(StorageSettings.Pool.of(3)).asyncThreads(2), List.of(),
            StorageContract.CLOCK);
    }

    private static List<String> plain(List<DiagnosticReport.Line> lines) {
        return lines.stream().map(DiagnosticReport.Line::plain).toList();
    }

    @Test
    void sqliteStorageNamesItsDatabaseItsStateAndBothPools() throws Exception {
        CompletableFuture<Storage> started = start("data.db");
        Storage storage = started.get(10, TimeUnit.SECONDS);
        try {
            List<String> lines = plain(storage.diagnostics());

            assertThat(lines).hasSize(4).startsWith("Database: SQLite", "State: open");
            assertThat(lines.get(2)).matches("Write pool: " + COUNTS + "1 max");
            assertThat(lines.get(3)).matches("Read pool: " + COUNTS + "3 max");
            assertThat(plain(Storage.diagnostics(started))).isEqualTo(lines);
            assertThat(String.join("\n", lines)).doesNotContain("data.db").doesNotContain("jdbc")
                .doesNotContain(folder.getFileName().toString());
            assertThatThrownBy(() -> storage.diagnostics().clear()).isInstanceOf(UnsupportedOperationException.class);
        } finally {
            assertThat(storage.close(Duration.ofSeconds(5))).isTrue();
        }
    }

    @Test
    void aRunningWriteCountsAsAnActiveConnectionAndTheReadDoesNotWaitForIt() throws Exception {
        Storage storage = start("data.db").get(10, TimeUnit.SECONDS);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<Void> write = storage.async(tx -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(plain(storage.diagnostics())).contains("Write pool: 1 active, 0 idle, 0 waiting, 1 max");

            release.countDown();
            write.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            assertThat(storage.close(Duration.ofSeconds(5))).isTrue();
        }
    }

    @Test
    void closedStorageReadsClosedWithClosedPools() throws Exception {
        CompletableFuture<Storage> started = start("data.db");
        Storage storage = started.get(10, TimeUnit.SECONDS);

        assertThat(storage.close(Duration.ofSeconds(5))).isTrue();

        assertThat(plain(storage.diagnostics())).containsExactly(
            "Database: SQLite", "State: closed", "Write pool: closed", "Read pool: closed");
        assertThat(plain(Storage.diagnostics(started))).contains("State: closed");
    }

    @Test
    void storageOverASourceHasNoPoolLineAndTakesNoConnection() {
        DataSource source = mock(DataSource.class);
        Storage storage = Storage.over(TestPlugins.plugin("over-diag", log.logger), new ErrorReports(),
            Dialect.POSTGRES, source, "p_", StorageContract.CLOCK);
        try {
            assertThat(plain(storage.diagnostics())).containsExactly("Database: PostgreSQL", "State: open");
            verifyNoInteractions(source);
        } finally {
            assertThat(storage.close(Duration.ofSeconds(5))).isTrue();
        }
        assertThat(plain(storage.diagnostics())).containsExactly("Database: PostgreSQL", "State: closed");
    }

    @Test
    void everyDialectHasItsName() {
        DataSource source = mock(DataSource.class);
        for (Dialect dialect : Dialect.values()) {
            Storage storage = Storage.over(TestPlugins.plugin("names-diag", log.logger), new ErrorReports(), dialect,
                source, "", StorageContract.CLOCK);
            String name = switch (dialect) {
                case SQLITE -> "SQLite";
                case MYSQL -> "MySQL";
                case MARIADB -> "MariaDB";
                case POSTGRES -> "PostgreSQL";
            };
            assertThat(plain(storage.diagnostics())).as(dialect.name()).startsWith("Database: " + name);
            assertThat(storage.close(Duration.ofSeconds(5))).isTrue();
        }
    }

    @Test
    void aStartThatHasNotCompletedReadsStartingWithoutWaiting() {
        CompletableFuture<Storage> pending = new CompletableFuture<>();

        assertThat(plain(Storage.diagnostics(pending))).containsExactly("State: starting");
        assertThat(pending).isNotDone();
    }

    @Test
    void aCancelledStartReadsCancelled() {
        CompletableFuture<Storage> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);

        assertThat(plain(Storage.diagnostics(cancelled))).containsExactly("State: start cancelled");
    }

    @Test
    void aFailedStartShowsItsMessageWithoutUserHostAddressOrUrl() {
        SQLException denied = new SQLException("Access denied for user 'mc_prod'@'10.0.0.5' (using password: YES)");
        SQLException unreachable = new SQLException(
            "Could not connect to jdbc:mysql://db.example.net:3306/shop?password=hunter2: Connect to 10.0.0.5:3306"
                + " timed out");

        assertThat(plain(Storage.diagnostics(CompletableFuture.failedFuture(denied)))).containsExactly(
            "State: failed to start: Access denied for user [user hidden]@[host hidden] (using password: YES)");
        assertThat(plain(Storage.diagnostics(CompletableFuture.failedFuture(
            new CompletionException(new ExecutionException(unreachable)))))).containsExactly(
            "State: failed to start: Could not connect to jdbc:[redacted] Connect to [address hidden]:3306"
                + " timed out");
    }

    @Test
    void aFailureWithoutAMessageShowsItsClassAndALongMessageIsCut() {
        assertThat(plain(Storage.diagnostics(CompletableFuture.failedFuture(new IllegalStateException()))))
            .containsExactly("State: failed to start: java.lang.IllegalStateException");
        assertThat(plain(Storage.diagnostics(CompletableFuture.failedFuture(new CompletionException(" ", null)))))
            .containsExactly("State: failed to start: java.util.concurrent.CompletionException");

        List<DiagnosticReport.Line> lines =
            Storage.diagnostics(CompletableFuture.failedFuture(new SQLException("x".repeat(5000))));
        assertThat(lines).singleElement().satisfies(line ->
            assertThat(line.value()).hasSize(DiagnosticReport.MAX_VALUE).endsWith("xxx..."));
    }

    @Test
    void aRealFailedStartHidesTheDataPath() throws IOException {
        Files.writeString(folder.resolve("blocker"), "not a folder");
        CompletableFuture<Storage> failed = start("blocker/data.db");
        assertThatThrownBy(() -> failed.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);

        List<String> lines = plain(Storage.diagnostics(failed));

        assertThat(lines).singleElement().asString().startsWith("State: failed to start: ")
            .contains("[path hidden]").doesNotContain(folder.getFileName().toString()).doesNotContain("blocker");
    }

    @Test
    void aNullFutureAndAFutureOfNullAreRefused() {
        assertThatThrownBy(() -> Storage.diagnostics(null)).isInstanceOf(NullPointerException.class)
            .hasMessage("start");
        assertThatThrownBy(() -> Storage.diagnostics(CompletableFuture.completedFuture(null)))
            .isInstanceOf(NullPointerException.class).hasMessage("storage");
    }
}
