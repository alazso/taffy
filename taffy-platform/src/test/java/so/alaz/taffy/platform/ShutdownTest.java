package so.alaz.taffy.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.CapturedLog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class ShutdownTest {

    private record Report(String source, Throwable error) {
    }

    private final CapturedLog log = new CapturedLog();
    private final ErrorReports errors = new ErrorReports();
    private final List<Report> reports = new ArrayList<>();

    ShutdownTest() {
        errors.install((source, error) -> reports.add(new Report(source, error)));
    }

    @AfterEach
    void clearTheInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void aStepThatSucceedsRunsOnceAndLogsAndReportsNothing() {
        List<String> ran = new ArrayList<>();

        Shutdown.closeQuietly("close storage", () -> ran.add("storage"), log.logger, errors);

        assertThat(ran).containsExactly("storage");
        assertThat(log.records).isEmpty();
        assertThat(reports).isEmpty();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void aCheckedExceptionGivesOneWarningAndOneReportWithTheSourceShutdown() {
        IOException failure = new IOException("disk full");

        Shutdown.closeQuietly("close storage", () -> {
            throw failure;
        }, log.logger, errors);

        assertThat(log.lines()).containsExactly("WARNING Failed to close storage during shutdown");
        assertThat(log.records).singleElement().satisfies(record -> assertThat(record.getThrown()).isSameAs(failure));
        assertThat(reports).containsExactly(new Report("shutdown", failure));
        assertThat(reports.get(0).error()).isSameAs(failure);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void anInterruptedExceptionLeavesTheInterruptFlagSet() {
        InterruptedException failure = new InterruptedException("woken");

        Shutdown.closeQuietly("settle pending work", () -> {
            throw failure;
        }, log.logger, errors);

        assertThat(Thread.interrupted()).isTrue();
        assertThat(log.lines()).containsExactly("WARNING Failed to settle pending work during shutdown");
        assertThat(log.records.getFirst().getThrown()).isSameAs(failure);
        assertThat(reports).containsExactly(new Report("shutdown", failure));
    }

    @Test
    void anErrorIsLoggedAndReportedAndNotPropagated() {
        NoClassDefFoundError failure = new NoClassDefFoundError("gone/Missing");

        Shutdown.closeQuietly("stop metrics", () -> {
            throw failure;
        }, log.logger, errors);

        assertThat(log.lines()).containsExactly("WARNING Failed to stop metrics during shutdown");
        assertThat(log.records.getFirst().getThrown()).isSameAs(failure);
        assertThat(reports).containsExactly(new Report("shutdown", failure));
    }

    @Test
    void aSecondStepStillRunsAfterTheFirstFails() {
        List<String> ran = new ArrayList<>();

        Shutdown.closeQuietly("close open menus", () -> {
            ran.add("menus");
            throw new IllegalStateException("already closed");
        }, log.logger, errors);
        Shutdown.closeQuietly("close storage", () -> ran.add("storage"), log.logger, errors);

        assertThat(ran).containsExactly("menus", "storage");
        assertThat(log.lines()).containsExactly("WARNING Failed to close open menus during shutdown");
        assertThat(log.records.getFirst().getThrown()).isInstanceOf(IllegalStateException.class)
            .hasMessage("already closed");
        assertThat(reports).extracting(Report::source).containsExactly("shutdown");
    }

    @Test
    void aFailureIsStillLoggedWhenNoReportTargetIsInstalled() {
        ErrorReports inert = new ErrorReports();

        Shutdown.closeQuietly("close the webhook client", () -> {
            throw new IOException("closed");
        }, log.logger, inert);

        assertThat(log.lines()).containsExactly("WARNING Failed to close the webhook client during shutdown");
        assertThat(log.records.getFirst().getThrown()).isInstanceOf(IOException.class).hasMessage("closed");
        assertThat(reports).isEmpty();
    }

    @Test
    void aReportTargetThatThrowsDoesNotStopTheNextStep() {
        List<String> ran = new ArrayList<>();
        errors.install((source, error) -> {
            throw new IllegalStateException("the reporter is broken");
        });

        Shutdown.closeQuietly("close open menus", () -> {
            throw new IOException("closed");
        }, log.logger, errors);
        Shutdown.closeQuietly("close storage", () -> ran.add("storage"), log.logger, errors);

        assertThat(ran).containsExactly("storage");
        assertThat(log.lines()).containsExactly("WARNING Failed to close open menus during shutdown");
    }

    @Test
    void aReportTargetThatThrowsAnErrorDoesNotStopTheNextStep() {
        List<String> ran = new ArrayList<>();
        errors.install((source, error) -> {
            throw new NoClassDefFoundError("gone/Reporter");
        });

        Shutdown.closeQuietly("close open menus", () -> {
            throw new IOException("closed");
        }, log.logger, errors);
        Shutdown.closeQuietly("close storage", () -> ran.add("storage"), log.logger, errors);

        assertThat(ran).containsExactly("storage");
        assertThat(log.lines()).containsExactly("WARNING Failed to close open menus during shutdown");
    }
}
