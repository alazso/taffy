package so.alaz.taffy.hooks;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * The result of detecting one integration: its status, and the integration itself when detection turned it on.
 *
 * <p>The status is read when asked. An integration that detection bound to its plugin reads
 * {@link HookState#NOT_ENABLED}, with no version, while that plugin is disabled, and the status found at detection
 * again once it is enabled. A status given to the public constructor is fixed, and so is the Vault economy's, which
 * changes only through a new lookup. {@link #provider()} stays set after the plugin disables; the provider's own
 * calls then give their empty answers.
 *
 * <p>The integration stays bound to the plugin instance found at detection. While that instance is disabled and the
 * server holds another, enabled instance under the same name, as after a plugin manager loaded the plugin again, the
 * {@link HookState#NOT_ENABLED} status carries a reason that says so, and the integration stays off until the owner
 * detects again.
 *
 * <p>Immutable, and safe to read from any thread.
 *
 * @param <T> the interface the integration is reached through
 */
public final class Detected<T> {

    private final HookStatus status;
    private final @Nullable T provider;
    private final @Nullable Plugin bound;
    private final Supplier<@Nullable Plugin> current;

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
        this(status, provider, null, () -> null);
    }

    // A non-null bound plugin makes status() read NOT_ENABLED while that plugin is disabled. current gives the
    // plugin the server holds now under the bound plugin's name, or null when it holds none.
    Detected(HookStatus status, @Nullable T provider, @Nullable Plugin bound, Supplier<@Nullable Plugin> current) {
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
        this.current = Objects.requireNonNull(current, "current");
    }

    /**
     * The state of the integration now.
     *
     * @return {@link HookState#NOT_ENABLED} with no version while the plugin the integration was bound to is not
     *     enabled, with a reason only when the server holds another, enabled instance of that plugin, and otherwise
     *     the status found at detection
     */
    public HookStatus status() {
        if (bound != null && !bound.isEnabled()) {
            return notEnabled();
        }
        return status;
    }

    // The status of a bound integration whose plugin is disabled.
    HookStatus notEnabled() {
        Plugin now = current.get();
        boolean replaced = now != null && now != bound && now.isEnabled();
        return new HookStatus(status.plugin(), HookState.NOT_ENABLED, null,
            replaced ? replaced(status.plugin()) : null);
    }

    // The owner's reason for a NOT_ENABLED status whose plugin runs as another instance than the bound one.
    static String replaced(String plugin) {
        return plugin + " was loaded again as a new plugin instance after this plugin detected it, and this plugin"
            + " still holds the old one. Restart the server to use " + plugin + " again, or run this plugin's reload"
            + " command if it detects " + plugin + " again.";
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
