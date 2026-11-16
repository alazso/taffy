package so.alaz.taffy.gui;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Drops a second accepted click on the same button within the window, and any click within the window of a menu
 * opening. Only accepted clicks move the window. The window is read on every check.
 */
final class Debounce {

    private final Supplier<Duration> window;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Instant>> last = new ConcurrentHashMap<>();

    Debounce(Supplier<Duration> window, Clock clock) {
        this.window = window;
        this.clock = clock;
    }

    /**
     * Tells whether the player's last accepted click on the button is at least the window ago, and remembers this
     * click when it is.
     */
    boolean pass(UUID player, String button) {
        Instant now = clock.instant();
        ConcurrentHashMap<String, Instant> buttons = last.computeIfAbsent(player, id -> new ConcurrentHashMap<>());
        Instant previous = buttons.get(button);
        if (previous != null && now.isBefore(previous.plus(window.get()))) {
            return false;
        }
        buttons.put(button, now);
        return true;
    }

    /** Tells whether the window has passed since a menu opened at {@code since}. */
    boolean settled(Instant since) {
        return !clock.instant().isBefore(since.plus(window.get()));
    }

    void forget(UUID player) {
        last.remove(player);
    }
}
