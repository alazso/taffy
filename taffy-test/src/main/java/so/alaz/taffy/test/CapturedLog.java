package so.alaz.taffy.test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * A logger that keeps its records: each line as {@code "LEVEL message"}, and the records themselves for their
 * throwables.
 *
 * <p>The wording of Taffy's own log lines is not part of its API and can change in any release.
 *
 * <p>{@link #logger} and {@link #records} are public fields and are read directly. The logger is anonymous and
 * passes nothing to its parent's handlers, so a captured record is not printed. A record below the logger's level
 * is not captured: the level is the root logger's, {@code INFO} unless changed.
 */
public final class CapturedLog {

    /** The logger to pass to the code under test. */
    public final Logger logger = Logger.getAnonymousLogger();

    /** Every record the logger published, in order. Safe to read while other threads log. */
    public final List<LogRecord> records = new CopyOnWriteArrayList<>();

    /** A logger with no records yet. */
    public CapturedLog() {
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    /**
     * The records so far as lines, in order: the level name, a space, then the message as it was passed to the
     * logger, with no parameter filled in. The list is a copy and does not change.
     */
    public List<String> lines() {
        return records.stream().map(record -> record.getLevel() + " " + record.getMessage()).toList();
    }
}
