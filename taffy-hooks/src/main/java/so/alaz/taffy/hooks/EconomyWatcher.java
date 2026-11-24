package so.alaz.taffy.hooks;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Looks the Vault economy up again when one registers or leaves. The service is matched by class name, so this
 * listener loads no Vault class. An economy leaving while the server stops is ignored, since plugins disable one by
 * one and the economy is not missing. A watcher made by the constructor hears the economy of the Vault API, and one
 * made by {@link #vaultUnlocked(Runnable, BooleanSupplier)} the economy of the VaultUnlocked economy API.
 *
 * <p>The plugin registers this listener itself while the economy is switched on, and also looks the economy up once
 * on the first tick, after every plugin has enabled. {@code resolve} runs on the thread that fires the event.
 */
public final class EconomyWatcher implements Listener {

    static final String ECONOMY = "net.milkbowl.vault.economy.Economy";

    static final String UNLOCKED_ECONOMY = "net.milkbowl.vault2.economy.Economy";

    private final String service;
    private final Runnable resolve;
    private final BooleanSupplier stopping;

    /**
     * Creates a watcher.
     *
     * @param resolve looks the economy up again through {@link EconomyProvider#vault} and keeps the new
     *     {@link Detected} in a {@code volatile} field
     * @param stopping whether the server is stopping
     * @throws NullPointerException if {@code resolve} or {@code stopping} is {@code null}
     */
    public EconomyWatcher(Runnable resolve, BooleanSupplier stopping) {
        this(ECONOMY, resolve, stopping);
    }

    private EconomyWatcher(String service, Runnable resolve, BooleanSupplier stopping) {
        this.service = service;
        this.resolve = Objects.requireNonNull(resolve, "resolve");
        this.stopping = Objects.requireNonNull(stopping, "stopping");
    }

    /**
     * Creates a watcher of the economy registered with the VaultUnlocked economy API, which
     * {@link EconomyProvider#vaultUnlocked} reads. An economy plugin registers that service apart from the one of
     * the Vault API, so a watcher made by the constructor does not hear it, and this one does not hear a Vault
     * economy. The service is matched by class name, so the watcher loads no VaultUnlocked class.
     *
     * @param resolve looks the economy up again through {@link EconomyProvider#vaultUnlocked} and keeps the new
     *     {@link Detected} in a {@code volatile} field
     * @param stopping whether the server is stopping
     * @return the watcher, for the plugin to register as a listener
     * @throws NullPointerException if {@code resolve} or {@code stopping} is {@code null}
     * @since 0.4.13
     */
    public static EconomyWatcher vaultUnlocked(Runnable resolve, BooleanSupplier stopping) {
        return new EconomyWatcher(UNLOCKED_ECONOMY, resolve, stopping);
    }

    /**
     * Runs {@code resolve} when the registered service is the economy this watcher was made for.
     *
     * @param event the registration
     */
    @EventHandler
    public void onRegister(ServiceRegisterEvent event) {
        if (isEconomy(event.getProvider())) {
            resolve.run();
        }
    }

    /**
     * Runs {@code resolve} when the unregistered service is the economy this watcher was made for and the server is
     * not stopping.
     *
     * @param event the unregistration
     */
    @EventHandler
    public void onUnregister(ServiceUnregisterEvent event) {
        if (!stopping.getAsBoolean() && isEconomy(event.getProvider())) {
            resolve.run();
        }
    }

    private boolean isEconomy(RegisteredServiceProvider<?> provider) {
        return provider.getService().getName().equals(service);
    }
}
