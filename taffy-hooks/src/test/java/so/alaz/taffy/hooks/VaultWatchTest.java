package so.alaz.taffy.hooks;

import io.papermc.paper.plugin.configuration.PluginMeta;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Server;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.platform.Scheduler;
import so.alaz.taffy.test.CapturedLog;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

final class VaultWatchTest {

    private final Plugin owner = mock(Plugin.class);
    private final Server server = mock(Server.class);
    private final ServicesManager services = mock(ServicesManager.class);
    private final PluginManager plugins = mock(PluginManager.class);
    private final Scheduler scheduler = mock(Scheduler.class);
    private final Economy economy = mock(Economy.class);
    private final CapturedLog log = new CapturedLog();
    private final ErrorReports errors = new ErrorReports();
    private final List<String> reported = new ArrayList<>();

    @BeforeEach
    void server() {
        when(owner.getServer()).thenReturn(server);
        when(owner.getLogger()).thenReturn(log.logger);
        when(owner.getName()).thenReturn("Shop");
        when(owner.isEnabled()).thenReturn(true);
        when(server.getServicesManager()).thenReturn(services);
        when(server.getPluginManager()).thenReturn(plugins);
        when(scheduler.global(any())).thenReturn(true);
        errors.install((source, error) -> reported.add(source + " " + error.getMessage()));
        Plugin vault = mock(Plugin.class);
        PluginMeta meta = mock(PluginMeta.class);
        when(meta.getVersion()).thenReturn("1.7.3");
        when(vault.getPluginMeta()).thenReturn(meta);
        when(plugins.getPlugin("Vault")).thenReturn(vault);
    }

    private VaultOptions options() {
        return VaultOptions.of(owner, errors, false);
    }

    @SuppressWarnings("unchecked")
    private void registered() {
        RegisteredServiceProvider<Economy> registration = mock(RegisteredServiceProvider.class);
        when(registration.getProvider()).thenReturn(economy);
        when(registration.getPlugin()).thenReturn(mock(Plugin.class));
        doReturn(registration).when(services).getRegistration(Economy.class);
    }

    private EconomyWatcher watcher() {
        ArgumentCaptor<Listener> listener = ArgumentCaptor.forClass(Listener.class);
        verify(plugins).registerEvents(listener.capture(), eq(owner));
        return (EconomyWatcher) listener.getValue();
    }

    private Runnable firstTick() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).global(task.capture());
        return task.getValue();
    }

    // The events are mocked: constructing one asks Bukkit which thread it is on, and no server runs here.
    private static ServiceRegisterEvent arrived() {
        ServiceRegisterEvent event = mock(ServiceRegisterEvent.class);
        doReturn(service()).when(event).getProvider();
        return event;
    }

    private static ServiceUnregisterEvent left() {
        ServiceUnregisterEvent event = mock(ServiceUnregisterEvent.class);
        doReturn(service()).when(event).getProvider();
        return event;
    }

    private static RegisteredServiceProvider<?> service() {
        RegisteredServiceProvider<?> provider = mock(RegisteredServiceProvider.class);
        doReturn(Economy.class).when(provider).getService();
        return provider;
    }

    @Test
    void theWatchLooksUpAtOnceRegistersTheWatcherForTheOwnerAndLooksUpAgainOnTheFirstTickAndOnEachEvent() {
        VaultWatch watch = EconomyProvider.watchVault(options(), scheduler);

        assertThat(watch.current().status().state()).isEqualTo(HookState.NOT_REGISTERED);
        assertThat(watcher()).isNotNull();

        registered();
        firstTick().run();
        Detected<EconomyProvider> found = watch.current();

        assertThat(found.status()).isEqualTo(new HookStatus("Vault", HookState.ON, "1.7.3", null));
        assertThat(found.provider()).isInstanceOf(VaultEconomy.class);

        doReturn(null).when(services).getRegistration(Economy.class);
        watcher().onUnregister(left());
        assertThat(watch.current().status().state()).isEqualTo(HookState.NOT_REGISTERED);

        registered();
        watcher().onRegister(arrived());
        assertThat(watch.current().status().state()).isEqualTo(HookState.ON);
        assertThat(watch.current()).isNotSameAs(found);
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aStatusThatStaysIsWarnedAboutAndReportedOnceAndAgainOnlyAfterItChanged() {
        IllegalStateException broken = new IllegalStateException("services unavailable");
        when(services.getRegistration(Economy.class)).thenThrow(broken);

        VaultWatch watch = EconomyProvider.watchVault(options(), scheduler);
        firstTick().run();
        watcher().onRegister(arrived());
        watcher().onUnregister(left());

        assertThat(watch.current().status().state()).isEqualTo(HookState.FAILED);
        assertThat(log.lines()).containsExactly("WARNING Vault 1.7.3 could not be hooked"
            + " (java.lang.IllegalStateException: services unavailable), so it stays off.");
        assertThat(reported).containsExactly("hook.vault services unavailable");

        registered();
        watcher().onRegister(arrived());
        doThrow(broken).when(services).getRegistration(Economy.class);
        watcher().onUnregister(left());

        assertThat(watch.current().status().state()).isEqualTo(HookState.FAILED);
        assertThat(log.lines()).hasSize(2);
        assertThat(reported).hasSize(2);
    }

    @Test
    void anEconomyLeavingWhileTheServerStopsKeepsTheResult() {
        registered();
        VaultWatch watch = EconomyProvider.watchVault(options(), scheduler);
        Detected<EconomyProvider> found = watch.current();
        when(server.isStopping()).thenReturn(true);
        doReturn(null).when(services).getRegistration(Economy.class);

        watcher().onUnregister(left());

        assertThat(watch.current()).isSameAs(found);
    }

    @Test
    void aSwitchedOffWatchRegistersNothingSchedulesNothingAndReadsNothingFromTheServer() {
        VaultWatch watch = EconomyProvider.watchVault(options().switchedOn(false), scheduler);

        assertThat(watch.current().status()).isEqualTo(new HookStatus("Vault", HookState.SWITCHED_OFF, null, null));
        verifyNoInteractions(server, plugins, services, scheduler);
        watch.close();
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aClosedWatchLooksUpNoMoreAndKeepsItsLastResult() {
        VaultWatch watch = EconomyProvider.watchVault(options(), scheduler);
        EconomyWatcher watcher = watcher();
        Detected<EconomyProvider> last = watch.current();

        watch.close();
        watch.close();
        registered();
        firstTick().run();
        watcher.onRegister(arrived());

        assertThat(watch.current()).isSameAs(last);
        verify(services).getRegistration(Economy.class);
    }

    @Test
    void aFirstTickLookupTheSchedulerRefusesIsWarnedAbout() {
        when(scheduler.global(any())).thenReturn(false);

        VaultWatch watch = EconomyProvider.watchVault(options(), scheduler);

        assertThat(watch.current().status().state()).isEqualTo(HookState.NOT_REGISTERED);
        assertThat(log.lines()).containsExactly("WARNING The economy is not looked up again on the first tick, because"
            + " this plugin is not enabled.");
        verify(plugins).registerEvents(any(EconomyWatcher.class), any(Plugin.class));
    }

    @Test
    void aSwitchedOnWatchForAnOwnerThatIsNotEnabledIsRefusedBeforeAnythingIsLookedUpOrRegistered() {
        when(owner.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> EconomyProvider.watchVault(options(), scheduler))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("the plugin Shop is not enabled: call watchVault in onEnable or later, and not in or after"
                + " onDisable");

        verifyNoInteractions(server, plugins, services, scheduler);
        assertThat(log.lines()).isEmpty();
        assertThat(EconomyProvider.watchVault(options().switchedOn(false), scheduler).current().status().state())
            .isEqualTo(HookState.SWITCHED_OFF);
    }

    @Test
    void missingOptionsOrAMissingSchedulerAreRefusedBeforeAnythingIsRegistered() {
        assertThatThrownBy(() -> EconomyProvider.watchVault(null, scheduler))
            .isInstanceOf(NullPointerException.class).hasMessage("options");
        assertThatThrownBy(() -> EconomyProvider.watchVault(options(), null))
            .isInstanceOf(NullPointerException.class).hasMessage("scheduler");
        verify(plugins, never()).registerEvents(any(Listener.class), any(Plugin.class));
    }
}
