package so.alaz.taffy.hooks;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The placeholders a {@link PlaceholderExpansionBuilder} registered. A plugin keeps it from enable and calls
 * {@link #unregister()} first in {@code onDisable}, since PlaceholderAPI keeps an expansion registered after the
 * plugin that registered it disables.
 *
 * <p>{@link #statuses()} may be called from any thread. {@link #unregister()} runs on the server thread, since
 * PlaceholderAPI fires a synchronous event when it unregisters.
 */
public final class PlaceholderRegistration {

    private final Detected<Runnable> placeholderApiDetected;
    private final Detected<Runnable> miniPlaceholdersDetected;
    private final Logger logger;
    private @Nullable Runnable placeholderApi;
    private @Nullable Runnable miniPlaceholders;

    PlaceholderRegistration(Detected<Runnable> placeholderApi, Detected<Runnable> miniPlaceholders, Logger logger) {
        this.placeholderApiDetected = placeholderApi;
        this.miniPlaceholdersDetected = miniPlaceholders;
        this.placeholderApi = placeholderApi.provider();
        this.miniPlaceholders = miniPlaceholders.provider();
        this.logger = logger;
    }

    /**
     * The state of each backend now, read when called. A backend the placeholders were registered with is
     * {@link HookState#ON}, and reads {@link HookState#NOT_ENABLED} while it is not enabled. One that refused them
     * or threw is {@link HookState#FAILED} with the reason. Any other gives the state that stopped it, or
     * {@link HookState#SWITCHED_OFF}. {@link #unregister()} does not change the statuses.
     *
     * @return the status of PlaceholderAPI, then that of MiniPlaceholders
     */
    public List<HookStatus> statuses() {
        return List.of(placeholderApiDetected.status(), miniPlaceholdersDetected.status());
    }

    /**
     * Unregisters the placeholders from PlaceholderAPI and MiniPlaceholders. An expansion that has since replaced
     * them under the same identifier stays registered. A failure is logged as a warning. A second call does nothing.
     */
    public synchronized void unregister() {
        run("PlaceholderAPI", placeholderApi);
        run("MiniPlaceholders", miniPlaceholders);
        placeholderApi = null;
        miniPlaceholders = null;
    }

    private void run(String backend, @Nullable Runnable unregister) {
        if (unregister == null) {
            return;
        }
        try {
            unregister.run();
        } catch (Exception | LinkageError failure) {
            logger.log(Level.WARNING, backend + " could not unregister the placeholders (" + failure + ").", failure);
        }
    }
}
