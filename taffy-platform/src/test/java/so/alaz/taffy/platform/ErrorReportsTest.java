package so.alaz.taffy.platform;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

final class ErrorReportsTest {

    private record Report(String source, Throwable error) {
    }

    private final ErrorReports errors = new ErrorReports();
    private final List<Report> received = new ArrayList<>();

    private void collect(String source, Throwable error) {
        received.add(new Report(source, error));
    }

    @Test
    void aReportBeforeInstallIsDroppedAndNeverDeliveredLater() {
        errors.report("early", new IllegalStateException("too soon"));

        errors.install(this::collect);

        assertThat(received).isEmpty();
    }

    @Test
    void afterInstallTheTargetReceivesTheSameSourceAndThrowable() {
        IllegalStateException first = new IllegalStateException("first");
        Error second = new StackOverflowError("second");
        errors.install(this::collect);

        errors.report("storage.save", first);
        errors.report("hook.economy", second);

        assertThat(received)
            .containsExactly(new Report("storage.save", first), new Report("hook.economy", second));
        assertThat(received.get(0).error()).isSameAs(first);
        assertThat(received.get(1).error()).isSameAs(second);
    }

    @Test
    void afterClearNothingArrives() {
        errors.install(this::collect);
        errors.report("before", new IllegalStateException("kept"));

        errors.clear();
        errors.report("after", new IllegalStateException("dropped"));

        assertThat(received).extracting(Report::source).containsExactly("before");
    }

    @Test
    void installingNullStopsReportsLikeClear() {
        errors.install(this::collect);

        errors.install(null);
        errors.report("after", new IllegalStateException("dropped"));

        assertThat(received).isEmpty();
    }

    @Test
    void aSecondInstallReplacesTheFirstTarget() {
        List<String> replacement = new ArrayList<>();
        errors.install(this::collect);

        errors.install((source, error) -> replacement.add(source));
        errors.report("shutdown", new IllegalStateException("late"));

        assertThat(received).isEmpty();
        assertThat(replacement).containsExactly("shutdown");
    }

    @Test
    void aTargetThatThrowsDoesNotPropagateAndStaysInstalled() {
        List<String> attempts = new ArrayList<>();
        errors.install((source, error) -> {
            attempts.add(source);
            throw new IllegalStateException("the reporter is broken");
        });

        assertThatCode(() -> errors.report("first", new IllegalStateException("handled")))
            .doesNotThrowAnyException();
        errors.report("second", new IllegalStateException("handled"));

        assertThat(attempts).containsExactly("first", "second");
    }

    @Test
    void aTargetThatThrowsAnErrorDoesNotPropagateAndStaysInstalled() {
        List<String> attempts = new ArrayList<>();
        errors.install((source, error) -> {
            attempts.add(source);
            throw new NoClassDefFoundError("gone/Reporter");
        });

        assertThatCode(() -> errors.report("first", new IllegalStateException("handled")))
            .doesNotThrowAnyException();
        errors.report("second", new IllegalStateException("handled"));

        assertThat(attempts).containsExactly("first", "second");
    }
}
