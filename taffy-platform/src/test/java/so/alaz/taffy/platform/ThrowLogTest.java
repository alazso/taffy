package so.alaz.taffy.platform;

import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.CapturedLog;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

final class ThrowLogTest {

    private final CapturedLog log = new CapturedLog();
    private final AtomicLong now = new AtomicLong();
    private final ThrowLog throwLog = new ThrowLog(log.logger, now::get);

    @Test
    void theFirstWarningCarriesTheStackAndRepeatsWaitAMinuteAndCountWhatWasLeftOut() {
        IllegalStateException failure = new IllegalStateException("broken");

        throwLog.warn("a", "A failed.", failure);
        throwLog.warn("a", "A failed.", failure);
        throwLog.warn("a", "A failed.", failure);
        throwLog.warn("b", "B failed.", failure);
        now.set(Duration.ofSeconds(59).toNanos());
        throwLog.warn("a", "A failed.", failure);
        now.set(Duration.ofSeconds(60).toNanos());
        throwLog.warn("a", "A failed.", failure);
        throwLog.warn("a", "A failed.", failure);
        now.set(Duration.ofSeconds(120).toNanos());
        throwLog.warn("b", "B failed.", failure);

        assertThat(log.lines()).containsExactly("WARNING A failed.", "WARNING B failed.",
            "WARNING A failed. 3 more since the last warning were not logged.", "WARNING B failed.");
        assertThat(log.records.get(0).getThrown()).isSameAs(failure);
        assertThat(log.records.get(1).getThrown()).isSameAs(failure);
        assertThat(log.records.get(2).getThrown()).isNull();
        assertThat(log.records.get(3).getThrown()).isNull();
    }

    @Test
    void withTheSystemClockARepeatWithinTheWindowIsNotLogged() {
        ThrowLog systemClock = new ThrowLog(log.logger);
        IllegalStateException failure = new IllegalStateException("broken");

        systemClock.warn("a", "A failed.", failure);
        systemClock.warn("a", "A failed.", failure);
        systemClock.warn("b", "B failed.", null);

        assertThat(ThrowLog.WINDOW).isEqualTo(Duration.ofMinutes(1));
        assertThat(log.lines()).containsExactly("WARNING A failed.", "WARNING B failed.");
        assertThat(log.records.get(0).getThrown()).isSameAs(failure);
        assertThat(log.records.get(1).getThrown()).isNull();
    }
}
