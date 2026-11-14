package so.alaz.taffy.test;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class CapturedLogTest {

    private final CapturedLog log = new CapturedLog();

    @Test
    void aNewLogHasNoRecords() {
        assertThat(log.records).isEmpty();
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void recordsKeepTheLevelTheMessageAndTheThrowable() {
        IllegalStateException failure = new IllegalStateException("boom");

        log.logger.log(Level.SEVERE, "Could not save", failure);
        log.logger.warning("No throwable here");

        assertThat(log.records).hasSize(2);
        LogRecord first = log.records.get(0);
        assertThat(first.getLevel()).isEqualTo(Level.SEVERE);
        assertThat(first.getMessage()).isEqualTo("Could not save");
        assertThat(first.getThrown()).isSameAs(failure);
        assertThat(log.records.get(1).getThrown()).isNull();
    }

    @Test
    void linesRenderTheLevelThenTheMessageInOrder() {
        log.logger.warning("first");
        log.logger.info("second");
        log.logger.log(Level.SEVERE, "third", new IllegalStateException("boom"));

        assertThat(log.lines()).containsExactly("WARNING first", "INFO second", "SEVERE third");
    }

    @Test
    void aLineHoldsTheMessageWithNoParameterFilledIn() {
        log.logger.log(Level.INFO, "Loaded {0} files", 3);

        assertThat(log.lines()).containsExactly("INFO Loaded {0} files");
        assertThat(log.records.get(0).getParameters()).containsExactly(3);
    }

    @Test
    void linesAreACopyThatLaterRecordsDoNotChange() {
        log.logger.info("first");
        List<String> before = log.lines();

        log.logger.info("second");

        assertThat(before).containsExactly("INFO first");
        assertThat(log.lines()).containsExactly("INFO first", "INFO second");
    }

    @Test
    void aRecordBelowTheLoggersLevelIsNotCaptured() {
        log.logger.setLevel(Level.WARNING);

        log.logger.info("too quiet");
        log.logger.warning("loud enough");

        assertThat(log.lines()).containsExactly("WARNING loud enough");
    }

    @Test
    void nothingReachesTheParentsHandlers() {
        Logger parent = log.logger.getParent();
        List<LogRecord> seenByParent = new ArrayList<>();
        Handler probe = new Handler() {
            @Override
            public void publish(LogRecord record) {
                seenByParent.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        parent.addHandler(probe);
        try {
            log.logger.severe("kept to itself");
        } finally {
            parent.removeHandler(probe);
        }

        assertThat(log.lines()).containsExactly("SEVERE kept to itself");
        assertThat(seenByParent).isEmpty();
        assertThat(log.logger.getUseParentHandlers()).isFalse();
    }

    @Test
    void twoLogsDoNotShareRecords() {
        CapturedLog other = new CapturedLog();

        log.logger.info("mine");

        assertThat(other.records).isEmpty();
        assertThat(other.logger).isNotSameAs(log.logger);
    }

    @Test
    void theHandlerAcceptsFlushAndClose() {
        log.logger.info("before");

        for (Handler handler : log.logger.getHandlers()) {
            handler.flush();
            handler.close();
        }
        log.logger.info("after");

        assertThat(log.lines()).containsExactly("INFO before", "INFO after");
    }
}
