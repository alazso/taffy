package so.alaz.taffy.platform;

import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.RaceRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

final class ErrorReportsTest {

    private record Report(String source, Throwable error) {
    }

    private final ErrorReports errors = new ErrorReports();
    private final List<Report> received = new ArrayList<>();

    private void collect(String source, Throwable error) {
        received.add(new Report(source, error));
    }

    @Test
    void reportsBeforeTheFirstInstallAreHeldAndDeliveredInOrderOnInstall() {
        IllegalStateException first = new IllegalStateException("config");
        IllegalStateException second = new IllegalStateException("hook");
        errors.report("config.load", first);
        errors.report("hook.economy", second);

        assertThat(received).isEmpty();
        errors.install(this::collect);

        assertThat(received)
            .containsExactly(new Report("config.load", first), new Report("hook.economy", second));
        assertThat(received.get(0).error()).isSameAs(first);
        assertThat(received.get(1).error()).isSameAs(second);
    }

    @Test
    void heldReportsAreDeliveredOnTheInstallingThreadBeforeInstallReturns() {
        List<Thread> threads = new ArrayList<>();
        errors.report("early", new IllegalStateException("held"));

        errors.install((source, error) -> threads.add(Thread.currentThread()));

        assertThat(threads).containsExactly(Thread.currentThread());
    }

    @Test
    void onlyTheFirstReportOfEachSourceIsHeld() {
        IllegalStateException kept = new IllegalStateException("first");
        errors.report("storage.save", kept);
        errors.report("hook.economy", new IllegalStateException("other source"));
        errors.report("storage.save", new IllegalStateException("second"));

        errors.install(this::collect);

        assertThat(received).extracting(Report::source).containsExactly("storage.save", "hook.economy");
        assertThat(received.get(0).error()).isSameAs(kept);
    }

    @Test
    void atMostSixteenReportsAreHeld() {
        for (int index = 0; index < 20; index++) {
            errors.report("source." + index, new IllegalStateException("report " + index));
        }

        errors.install(this::collect);

        assertThat(received).extracting(Report::source)
            .containsExactlyElementsOf(IntStream.range(0, 16).mapToObj(index -> "source." + index).toList());
    }

    @Test
    void aHeldSourceIsStillDroppedOnceSixteenAreHeld() {
        IllegalStateException kept = new IllegalStateException("first");
        errors.report("source.0", kept);
        for (int index = 1; index < 16; index++) {
            errors.report("source." + index, new IllegalStateException("report " + index));
        }
        errors.report("source.0", new IllegalStateException("repeat"));

        errors.install(this::collect);

        assertThat(received).hasSize(16);
        assertThat(received.get(0).error()).isSameAs(kept);
    }

    @Test
    void clearBeforeTheFirstInstallDiscardsTheHeldReports() {
        errors.report("early", new IllegalStateException("held"));

        errors.clear();
        errors.report("after.clear", new IllegalStateException("dropped"));
        errors.install(this::collect);

        assertThat(received).isEmpty();
    }

    @Test
    void anInstallAfterAClearReceivesTheReportsMadeAfterIt() {
        errors.clear();
        errors.report("while.cleared", new IllegalStateException("dropped"));

        errors.install(this::collect);
        errors.report("after.install", new IllegalStateException("kept"));

        assertThat(received).extracting(Report::source).containsExactly("after.install");
    }

    @Test
    void installingNullBeforeTheFirstInstallDiscardsTheHeldReports() {
        errors.report("early", new IllegalStateException("held"));

        errors.install(null);
        errors.report("after.null", new IllegalStateException("dropped"));
        errors.install(this::collect);

        assertThat(received).isEmpty();
    }

    @Test
    void afterTheFirstInstallAReportWithNoTargetIsDroppedAndNeverDeliveredLater() {
        errors.install(this::collect);
        errors.clear();

        errors.report("late", new IllegalStateException("no target"));
        errors.install(this::collect);

        assertThat(received).isEmpty();
    }

    @Test
    void aSecondInstallDeliversNothingHeld() {
        List<String> first = new ArrayList<>();
        errors.report("early", new IllegalStateException("held"));
        errors.install((source, error) -> first.add(source));

        errors.install(this::collect);

        assertThat(first).containsExactly("early");
        assertThat(received).isEmpty();
    }

    @Test
    void aHeldReportWhoseTargetThrowsDoesNotStopTheRest() {
        List<String> attempts = new ArrayList<>();
        errors.report("first", new IllegalStateException("held"));
        errors.report("second", new IllegalStateException("held"));
        errors.report("third", new IllegalStateException("held"));

        assertThatCode(() -> errors.install((source, error) -> {
            attempts.add(source);
            if (source.equals("first")) {
                throw new IllegalStateException("the reporter is broken");
            }
            throw new NoClassDefFoundError("gone/Reporter");
        })).doesNotThrowAnyException();

        assertThat(attempts).containsExactly("first", "second", "third");
    }

    @Test
    void aReportRacingTheFirstInstallIsDeliveredExactlyOnce() throws Exception {
        for (int round = 0; round < 200; round++) {
            ErrorReports racing = new ErrorReports();
            Queue<String> delivered = new ConcurrentLinkedQueue<>();
            int reporters = 8;

            RaceRunner.race(reporters + 1, contender -> {
                if (contender == reporters) {
                    racing.install((source, error) -> delivered.add(source));
                } else {
                    racing.report("source." + contender, new IllegalStateException("raced"));
                }
                return contender;
            });

            assertThat(delivered).containsExactlyInAnyOrderElementsOf(
                IntStream.range(0, reporters).mapToObj(index -> "source." + index).toList());
        }
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

    @Test
    void anUnhandledReportReachesTheOneHandlerOfASingleInstall() {
        IllegalStateException escaped = new IllegalStateException("escaped");
        errors.install(this::collect);

        errors.reportUnhandled("scheduler.async", escaped);

        assertThat(received).containsExactly(new Report("scheduler.async", escaped));
    }

    @Test
    void anInstallOfTwoHandlersSendsEachReportToTheOneThatTakesIt() {
        List<Report> unhandled = new ArrayList<>();
        IllegalStateException caught = new IllegalStateException("caught");
        IllegalStateException escaped = new IllegalStateException("escaped");
        errors.install(this::collect, (source, error) -> unhandled.add(new Report(source, error)));

        errors.report("storage.save", caught);
        errors.reportUnhandled("scheduler.async", escaped);

        assertThat(received).containsExactly(new Report("storage.save", caught));
        assertThat(unhandled).containsExactly(new Report("scheduler.async", escaped));
    }

    @Test
    void heldReportsAreDeliveredToTheHandlerThatTakesEachInTheOrderTheyWereMade() {
        List<String> order = new ArrayList<>();
        errors.reportUnhandled("scheduler.global", new IllegalStateException("escaped"));
        errors.report("config.load", new IllegalStateException("caught"));
        errors.reportUnhandled("scheduler.global", new IllegalStateException("second of its source"));
        errors.report("scheduler.global", new IllegalStateException("third of its source"));

        errors.install((source, error) -> order.add("handled " + source),
            (source, error) -> order.add("unhandled " + source));

        assertThat(order).containsExactly("unhandled scheduler.global", "handled config.load");
    }

    @Test
    void anInstallOfTwoHandlersReplacesTheTargetBeforeItAndASingleInstallReplacesBoth() {
        List<String> order = new ArrayList<>();
        errors.install(this::collect);
        errors.install((source, error) -> order.add("handled " + source),
            (source, error) -> order.add("unhandled " + source));
        errors.report("first", new IllegalStateException("caught"));
        errors.reportUnhandled("first", new IllegalStateException("escaped"));

        errors.install(this::collect);
        errors.report("second", new IllegalStateException("caught"));
        errors.reportUnhandled("second", new IllegalStateException("escaped"));

        assertThat(order).containsExactly("handled first", "unhandled first");
        assertThat(received).extracting(Report::source).containsExactly("second", "second");
    }

    @Test
    void anInstallOfTwoHandlersRefusesANullHandlerAndLeavesTheTargetInPlace() {
        errors.install(this::collect);

        assertThatNullPointerException().isThrownBy(() -> errors.install(null, this::collect))
            .withMessage("handle");
        assertThatNullPointerException().isThrownBy(() -> errors.install(this::collect, null))
            .withMessage("unhandled");
        errors.reportUnhandled("scheduler.async", new IllegalStateException("escaped"));

        assertThat(received).extracting(Report::source).containsExactly("scheduler.async");
    }

    @Test
    void anUnhandledReportIsDroppedAfterClearAndItsHandlerMayThrow() {
        List<String> attempts = new ArrayList<>();
        errors.install(this::collect, (source, error) -> {
            attempts.add(source);
            throw new NoClassDefFoundError("gone/Reporter");
        });

        assertThatCode(() -> errors.reportUnhandled("first", new IllegalStateException("escaped")))
            .doesNotThrowAnyException();
        errors.clear();
        errors.reportUnhandled("second", new IllegalStateException("escaped"));

        assertThat(attempts).containsExactly("first");
        assertThat(received).isEmpty();
    }
}
