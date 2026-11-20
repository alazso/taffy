package so.alaz.taffy.gui;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Drops a second accepted click on the same button within the window, and any click within the window of a menu
 * opening. Only accepted clicks move the window. The window is read on every check. Time is read in nanoseconds from
 * a source that never steps back, such as {@link System#nanoTime()}, and only the difference of two readings is used.
 */
final class Debounce {

    private final Supplier<Duration> window;
    private final LongSupplier nanos;
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>> last = new ConcurrentHashMap<>();

    Debounce(Supplier<Duration> window, LongSupplier nanos) {
        this.window = window;
        this.nanos = nanos;
    }

    /**
     * Tells whether the player's last accepted click on the button is at least the window ago, and remembers this
     * click when it is.
     */
    boolean pass(UUID player, String button) {
        long now = nanos.getAsLong();
        ConcurrentHashMap<String, Long> buttons = last.computeIfAbsent(player, id -> new ConcurrentHashMap<>());
        Long previous = buttons.get(button);
        if (previous != null && now - previous < window.get().toNanos()) {
            return false;
        }
        buttons.put(button, now);
        return true;
    }

    /** Tells whether the window has passed since a menu opened at the reading {@code since} of the time source. */
    boolean settled(long since) {
        return nanos.getAsLong() - since >= window.get().toNanos();
    }

    void forget(UUID player) {
        last.remove(player);
    }
}
