package so.alaz.taffy.metrics;

import dev.faststats.Config;
import dev.faststats.ErrorTrackerService;
import dev.faststats.FastStatsContext;
import dev.faststats.SdkInfo;
import dev.faststats.SimpleContext;

import java.util.Optional;

/**
 * A FastStats context that was made ready, with what it submits. FastStats attaches every service a context was
 * built with, whatever the server owner allows, and holds each send back while submission is not active or the
 * service's config key is off. {@link #metrics()} and {@link #errorTrackerService()} give a service only while
 * FastStats sends for it.
 */
final class FastStatsRun {

    /** The system property FastStats sets on a server's first run, in which no context starts submission. */
    static final String FIRST_RUN = "faststats.first-run";

    final FastStatsContext context;
    private volatile boolean submitting;

    /**
     * Wraps a context whose {@code ready()} succeeded.
     *
     * @param context the context
     * @param submitting whether {@code ready()} started submission, which it does not on a server's first run
     */
    FastStatsRun(FastStatsContext context, boolean submitting) {
        this.context = context;
        this.submitting = submitting;
    }

    /** Whether submission was started and {@link #stopSubmission()} has not been called. */
    boolean submitting() {
        return submitting;
    }

    Config getConfig() {
        return context.getConfig();
    }

    SdkInfo getSdkInfo() {
        return context.getSdkInfo();
    }

    /** The metrics service, while submission is on and the server owner left metrics on. */
    Optional<dev.faststats.Metrics> metrics() {
        return submitting && context.getConfig().submitMetrics() ? context.metrics() : Optional.empty();
    }

    /** The error service, while submission is on and the server owner left error tracking on. */
    Optional<ErrorTrackerService> errorTrackerService() {
        return submitting && context.getConfig().errorTracking() ? context.errorTrackerService() : Optional.empty();
    }

    /** Turns submission off, so that {@link #shutdown()} sends nothing. */
    void stopSubmission() {
        submitting = false;
        stopSubmission(context);
    }

    /** Sends the last errors and metrics, unless submission is off, and stops the context. */
    void shutdown() {
        context.shutdown();
    }

    /**
     * Turns submission off on {@code context} and cancels its scheduled sends. FastStats 0.33.0 skips every send of
     * a context that is not submitting; its {@code shutdown()} otherwise makes a final send.
     */
    static void stopSubmission(FastStatsContext context) {
        ((SimpleContext) context).stopSubmission();
    }
}
