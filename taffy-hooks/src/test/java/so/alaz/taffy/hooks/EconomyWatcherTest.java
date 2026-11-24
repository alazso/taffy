package so.alaz.taffy.hooks;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

final class EconomyWatcherTest {

    private final AtomicInteger resolves = new AtomicInteger();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final EconomyWatcher watcher = new EconomyWatcher(resolves::incrementAndGet, stopping::get);

    @Test
    void anEconomyArrivingOrLeavingResolvesTheEconomyAgain() {
        watcher.onRegister(registered(Economy.class));
        watcher.onUnregister(unregistered(Economy.class));

        assertThat(resolves).hasValue(2);
        assertThat(EconomyWatcher.ECONOMY).isEqualTo(Economy.class.getName());
    }

    @Test
    void anEconomyLeavingWhileTheServerStopsChangesNothing() {
        stopping.set(true);

        watcher.onUnregister(unregistered(Economy.class));

        assertThat(resolves).hasValue(0);
    }

    @Test
    void anyOtherServiceIsIgnored() {
        watcher.onRegister(registered(Runnable.class));
        watcher.onUnregister(unregistered(Runnable.class));

        assertThat(resolves).hasValue(0);
    }

    @Test
    void aVaultUnlockedWatcherHearsTheVaultUnlockedEconomyAndTheConstructorsWatcherDoesNot() {
        Class<?> unlocked = net.milkbowl.vault2.economy.Economy.class;
        AtomicInteger late = new AtomicInteger();
        EconomyWatcher unlockedWatcher = EconomyWatcher.vaultUnlocked(late::incrementAndGet, stopping::get);

        watcher.onRegister(registered(unlocked));
        watcher.onUnregister(unregistered(unlocked));
        assertThat(resolves).hasValue(0);

        unlockedWatcher.onRegister(registered(Economy.class));
        unlockedWatcher.onRegister(registered(Runnable.class));
        assertThat(late).hasValue(0);
        unlockedWatcher.onRegister(registered(unlocked));
        unlockedWatcher.onUnregister(unregistered(unlocked));
        assertThat(late).hasValue(2);

        stopping.set(true);
        unlockedWatcher.onUnregister(unregistered(unlocked));
        assertThat(late).hasValue(2);
        assertThat(EconomyWatcher.UNLOCKED_ECONOMY).isEqualTo(unlocked.getName());
        assertThatThrownBy(() -> EconomyWatcher.vaultUnlocked(null, stopping::get))
            .isInstanceOf(NullPointerException.class).hasMessage("resolve");
        assertThatThrownBy(() -> EconomyWatcher.vaultUnlocked(late::incrementAndGet, null))
            .isInstanceOf(NullPointerException.class).hasMessage("stopping");
    }

    @Test
    void aMissingResolveOrStoppingCheckIsRefused() {
        assertThatThrownBy(() -> new EconomyWatcher(null, stopping::get))
            .isInstanceOf(NullPointerException.class).hasMessage("resolve");
        assertThatThrownBy(() -> new EconomyWatcher(resolves::incrementAndGet, null))
            .isInstanceOf(NullPointerException.class).hasMessage("stopping");
    }

    // The events are mocked: constructing one asks Bukkit which thread it is on, and no server runs here.
    private static ServiceRegisterEvent registered(Class<?> service) {
        ServiceRegisterEvent event = mock(ServiceRegisterEvent.class);
        doReturn(provider(service)).when(event).getProvider();
        return event;
    }

    private static ServiceUnregisterEvent unregistered(Class<?> service) {
        ServiceUnregisterEvent event = mock(ServiceUnregisterEvent.class);
        doReturn(provider(service)).when(event).getProvider();
        return event;
    }

    private static RegisteredServiceProvider<?> provider(Class<?> service) {
        RegisteredServiceProvider<?> provider = mock(RegisteredServiceProvider.class);
        doReturn(service).when(provider).getService();
        return provider;
    }
}
