package so.alaz.taffy.comficonf;

import java.nio.file.Path;
import java.util.concurrent.locks.Lock;

/**
 * A plugin's registration of one settings resource with ComfiConf. Declared without any {@code com.comficonf} type,
 * so classes loaded before the bridge check may hold it while the ComfiConf bridge is absent.
 *
 * <p>{@link ComfiConfRegistration#register} gives one; the plugin's own integration class returns it from the
 * supplier it passes to {@link ComfiConfConnection#connect}.
 *
 * <p>Implementations are thread-safe: {@link ComfiConfConnection} calls every method from whichever thread calls it.
 * The link {@link ComfiConfRegistration#register} returns is.
 */
public interface ComfiConfLink {

    /** Id of the registered configuration resource, {@code namespace:name}. */
    String resourceId();

    /** Whether the resource is still registered with the bridge. */
    boolean isRegistered();

    /**
     * Tells the bridge that the resource changed outside ComfiConf, so open editors load the newer values. Does
     * nothing once the resource is no longer registered.
     */
    void notifyChanged();

    /** Unregisters the resource. Calling it more than once has no further effect. */
    void close();

    /**
     * The lock a ComfiConf apply of {@code file} holds from its read of the file to the move of the new content over
     * the file, and a read of the file holds while it reads. The same path gives the same lock on every call.
     *
     * @param file the settings file
     * @return the lock, never {@code null}
     */
    Lock lockFor(Path file);
}
