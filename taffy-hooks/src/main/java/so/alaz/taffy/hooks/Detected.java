package so.alaz.taffy.hooks;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * The result of detecting one integration: its status, and the integration itself when detection turned it on.
 *
 * <p>The status is read when asked. An integration that detection bound to its plugin reads
 * {@link HookState#NOT_ENABLED}, with no version and no reason, while that plugin is disabled, and the status found
 * at detection again once it is enabled. A status given to the public constructor is fixed, and so is the Vault
 * economy's, which changes only through a new lookup. {@link #provider()} stays set after the plugin disables; the
 * provider's own calls then give their empty answers.
 *
 * <p>Immutable, and safe to read from any thread.
 *
 * @param <T> the interface the integration is reached through
 */
public final class Detected<T> {

    private final HookStatus status;
    private final @Nullable T provider;
    private final @Nullable Plugin bound;

    /**
     * Creates a result with a fixed status, checking that a provider is given exactly when the state is on.
     *
     * @param status the state of the integration
     * @param provider the integration, non-null exactly when {@code status.state()} is {@link HookState#ON} or
     *     {@link HookState#LOADING}
     * @throws NullPointerException if {@code status} is {@code null}
     * @throws IllegalArgumentException if the state is {@link HookState#ON} or {@link HookState#LOADING} and
     *     {@code provider} is {@code null}, or the state is any other and {@code provider} is not {@code null}
     */
    public Detected(HookStatus status, @Nullable T provider) {
        this(status, provider, null);
    }

    // A non-null bound plugin makes status() read NOT_ENABLED while that plugin is disabled.
    Detected(HookStatus status, @Nullable T provider, @Nullable Plugin bound) {
        Objects.requireNonNull(status, "status");
        boolean on = status.state() == HookState.ON || status.state() == HookState.LOADING;
        if (on != (provider != null)) {
            throw new IllegalArgumentException(on ? "state " + status.state() + " needs a provider"
                : "state " + status.state() + " takes no provider");
        }
        if (bound != null && provider == null) {
            throw new IllegalArgumentException("a bound plugin needs a provider");
        }
        this.status = status;
        this.provider = provider;
        this.bound = bound;
    }

    /**
     * The state of the integration now.
     *
     * @return {@link HookState#NOT_ENABLED} with no version and no reason while the plugin the integration was bound
     *     to is not enabled, and otherwise the status found at detection
     */
    public HookStatus status() {
        if (bound != null && !bound.isEnabled()) {
            return new HookStatus(status.plugin(), HookState.NOT_ENABLED, null, null);
        }
        return status;
    }

    /**
     * The integration.
     *
     * @return the integration, non-null exactly when detection turned it on, and kept after its plugin disables
     */
    public @Nullable T provider() {
        return provider;
    }

    @Nullable Plugin bound() {
        return bound;
    }
}
