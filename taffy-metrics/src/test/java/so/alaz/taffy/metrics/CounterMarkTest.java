package so.alaz.taffy.metrics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class CounterMarkTest {

    private final AtomicLong total = new AtomicLong();
    private final CounterMark mark = new CounterMark(total::get);

    @Test
    void takeDeltaReportsTheIncreaseSinceTheLastRead() {
        total.set(5);
        assertThat(mark.takeDelta()).isEqualTo(5);
        total.set(8);
        assertThat(mark.takeDelta()).isEqualTo(3);
        assertThat(mark.takeDelta()).isZero();
    }

    @Test
    void peekDeltaAdvancesOnlyOnCommit() {
        total.set(5);
        assertThat(mark.peekDelta()).isEqualTo(5);
        assertThat(mark.peekDelta()).isEqualTo(5);
        mark.commit();
        total.set(7);
        assertThat(mark.peekDelta()).isEqualTo(2);
    }

    @Test
    void uncommittedReadKeepsTheCountForTheNextReport() {
        total.set(5);
        mark.peekDelta();
        total.set(9);
        assertThat(mark.peekDelta()).isEqualTo(9);
        mark.commit();
        assertThat(mark.peekDelta()).isZero();
    }

    @Test
    void readOnAnotherThreadIsNotCommittedByThisThread() throws InterruptedException {
        total.set(5);
        mark.peekDelta();
        total.set(9);
        Thread errorReport = new Thread(mark::peekDelta);
        errorReport.start();
        errorReport.join();
        mark.commit();
        assertThat(mark.peekDelta()).isEqualTo(4);
    }

    @Test
    void commitWithoutAReadChangesNothing() {
        total.set(6);
        mark.commit();
        assertThat(mark.peekDelta()).isEqualTo(6);
    }

    @Test
    void aTotalThatGoesDownReportsZeroOnceAndBothPathsThenCountFromTheLowerTotal() {
        AtomicLong bStatsTotal = new AtomicLong();
        CounterMark bStats = new CounterMark(bStatsTotal::get);
        List<Long> fastStatsReports = new ArrayList<>();
        List<Long> bStatsReports = new ArrayList<>();

        for (long value : new long[] {100, 0, 30, 60, 90, 120}) {
            total.set(value);
            bStatsTotal.set(value);
            fastStatsReports.add(mark.peekDelta());
            mark.commit();
            bStatsReports.add(bStats.takeDelta());
        }

        assertThat(fastStatsReports).containsExactly(100L, 0L, 30L, 30L, 30L, 30L);
        assertThat(bStatsReports).isEqualTo(fastStatsReports);
    }

    @Test
    void anUncommittedReadAfterADropKeepsTheMarkUntilACommit() {
        total.set(100);
        mark.peekDelta();
        mark.commit();
        total.set(10);

        assertThat(mark.peekDelta()).isZero();
        assertThat(mark.peekDelta()).isZero();
        mark.commit();
        total.set(25);

        assertThat(mark.peekDelta()).isEqualTo(15);
    }

    @Test
    void commitNeverMovesTheMarkBackwards() throws InterruptedException {
        total.set(5);
        mark.peekDelta();
        total.set(10);
        Thread other = new Thread(() -> {
            mark.peekDelta();
            mark.commit();
        });
        other.start();
        other.join();
        mark.commit();
        assertThat(mark.peekDelta()).isZero();
    }
}
