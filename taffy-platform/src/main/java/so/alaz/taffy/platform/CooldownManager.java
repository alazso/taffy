package so.alaz.taffy.platform;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Tracks keyed cooldowns against the system clock. A key is any object compared by
 * {@code equals}/{@code hashCode}, typically a record of a player UUID and what is on cooldown.
 *
 * <p>A length is read in whole milliseconds, so a positive length below one millisecond counts as zero. A
 * length that reaches past the end of the clock's range lasts until that end.
 *
 * <p>Thread-safe: each call is atomic. A check followed by a start is two calls, so two threads can both
 * pass the check for one key.
 */
public final class CooldownManager {

    private final LongSupplier millisClock;
    private final ConcurrentHashMap<Object, Long> expiries = new ConcurrentHashMap<>();

    public CooldownManager() {
        this(System::currentTimeMillis);
    }

    CooldownManager(LongSupplier millisClock) {
        this.millisClock = millisClock;
    }

    /**
     * Starts (or restarts) a cooldown for the key lasting {@code length}. A length of zero or less clears the
     * key's cooldown.
     */
    public void set(Object key, Duration length) {
        long millis = millis(length);
        if (millis <= 0) {
            expiries.remove(key);
        } else {
            expiries.put(key, expiryAfter(millis));
        }
    }

    /**
     * Extends the key's cooldown to last at least {@code length} from now, keeping the later expiry, so it
     * never shortens a longer cooldown already running. A length of zero or less changes nothing.
     */
    public void extend(Object key, Duration length) {
        long millis = millis(length);
        if (millis > 0) {
            expiries.merge(key, expiryAfter(millis), Math::max);
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

    private static long millis(Duration length) {
        try {
            return length.toMillis();
        } catch (ArithmeticException beyondLong) {
            return length.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    private long expiryAfter(long millis) {
        long now = millisClock.getAsLong();
        long expiry = now + millis;
        // The sum wraps to a value below now when it passes Long.MAX_VALUE.
        return expiry < now ? Long.MAX_VALUE : expiry;
    }

    private long remainingMillis(Object key) {
        Long expiry = expiries.get(key);
        if (expiry == null) {
            return 0;
        }
        long remaining = expiry - millisClock.getAsLong();
        if (remaining <= 0) {
            // Conditional on the value read, so a cooldown another thread set meanwhile is kept.
            expiries.remove(key, expiry);
            return 0;
        }
        return remaining;
    }
}
