package so.alaz.taffy.platform;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks keyed cooldowns against the system clock. A key is any object compared by
 * {@code equals}/{@code hashCode}, typically a record of a player UUID and what is on cooldown. Thread-safe.
 */
public final class CooldownManager {

    private final ConcurrentHashMap<Object, Long> expiries = new ConcurrentHashMap<>();

    /**
     * Starts (or restarts) a cooldown for the key lasting the given milliseconds. A length of zero or less
     * clears the key's cooldown.
     */
    public void setMillis(Object key, long millis) {
        if (millis <= 0) {
            expiries.remove(key);
        } else {
            expiries.put(key, System.currentTimeMillis() + millis);
        }
    }

    /**
     * Extends the key's cooldown to last at least the given milliseconds, keeping the later expiry,
     * so it never shortens a longer cooldown already running.
     */
    public void extendMillis(Object key, long millis) {
        if (millis > 0) {
            expiries.merge(key, System.currentTimeMillis() + millis, Math::max);
        }
    }

    /** Whether the key has a cooldown that has not expired. */
    public boolean isOnCooldown(Object key) {
        return remainingMillis(key) > 0;
    }

    /** Time remaining for the key, or {@link Duration#ZERO} if not on cooldown. */
    public Duration remaining(Object key) {
        return Duration.ofMillis(remainingMillis(key));
    }

    private long remainingMillis(Object key) {
        Long expiry = expiries.get(key);
        if (expiry == null) {
            return 0;
        }
        long remaining = expiry - System.currentTimeMillis();
        if (remaining <= 0) {
            // Conditional on the value read, so a cooldown another thread set meanwhile is kept.
            expiries.remove(key, expiry);
            return 0;
        }
        return remaining;
    }
}
