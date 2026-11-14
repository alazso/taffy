package so.alaz.taffy.platform;

import java.util.logging.Logger;

/** Runs the steps of a plugin's disable so that one failing step does not stop the ones after it. */
public final class Shutdown {

    /** One shutdown step, which may fail with any exception. */
    @FunctionalInterface
    public interface ShutdownStep {
        void run() throws Exception;
    }

    private Shutdown() {
    }

    /**
     * Runs a shutdown step, logging (not propagating) any failure so later steps still run.
     *
     * <p>A failure of any kind, an {@link Error} included, is written to {@code logger} as one warning that
     * reads "Failed to {@code what} during shutdown" followed by the failure, and is reported to
     * {@code errors} under the source {@code shutdown}. When the failure is an {@link InterruptedException}
     * the calling thread's interrupt flag is set again.
     *
     * @param what the step as a phrase that completes "Failed to", such as {@code close storage}
     */
    public static void closeQuietly(String what, ShutdownStep step, Logger logger, ErrorReports errors) {
        try {
            step.run();
        } catch (Throwable t) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.warning("Failed to " + what + " during shutdown: " + t);
            errors.report("shutdown", t);
        }
    }
}
