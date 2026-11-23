package so.alaz.taffy.hooks;

import io.papermc.paper.plugin.configuration.PluginMeta;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.platform.Scheduler;
import so.alaz.taffy.platform.ThreadTag;
import so.alaz.taffy.test.CapturedLog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

final class EconomyLookupTest {

    private static final UUID PLAYER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    private final Plugin owner = mock(Plugin.class);
    private final Server server = mock(Server.class);
    private final ServicesManager services = mock(ServicesManager.class);
    private final PluginManager plugins = mock(PluginManager.class);
    private final Economy economy = mock(Economy.class);
    private final Plugin registrant = mock(Plugin.class);
    private final CapturedLog log = new CapturedLog();
    private final ErrorReports errors = new ErrorReports();
    private final List<String> reported = new ArrayList<>();

    @BeforeEach
    void server() {
        when(owner.getServer()).thenReturn(server);
        when(owner.getLogger()).thenReturn(log.logger);
        when(server.getServicesManager()).thenReturn(services);
        when(server.getPluginManager()).thenReturn(plugins);
        when(server.isGlobalTickThread()).thenReturn(true);
        errors.install((source, error) -> reported.add(source + " " + error.getMessage()));
        when(registrant.isEnabled()).thenReturn(true);
        when(registrant.getName()).thenReturn("Essentials");
    }

    private static Plugin vaultPlugin(String version) {
        Plugin vault = mock(Plugin.class);
        PluginMeta meta = mock(PluginMeta.class);
        when(meta.getVersion()).thenReturn(version);
        when(vault.getPluginMeta()).thenReturn(meta);
        return vault;
    }

    private void installVault(String version) {
        Plugin vault = vaultPlugin(version);
        when(plugins.getPlugin("Vault")).thenReturn(vault);
    }

    private Detected<EconomyProvider> lookUp(boolean folia, Integer scale) {
        return EconomyLookup.vault(VaultOptions.of(owner, errors, folia).scale(scale));
    }

    @SuppressWarnings("unchecked")
    private void registered() {
        RegisteredServiceProvider<Economy> registration = mock(RegisteredServiceProvider.class);
        when(registration.getProvider()).thenReturn(economy);
        when(registration.getPlugin()).thenReturn(registrant);
        when(services.getRegistration(Economy.class)).thenReturn(registration);
    }

    @Test
    void aRegisteredEconomyIsOnAndNothingInItIsCalled() {
        registered();
        installVault("1.7.3");

        Detected<EconomyProvider> found = lookUp(false, 3);

        assertThat(found.status()).isEqualTo(new HookStatus("Vault", HookState.ON, "1.7.3", null));
        EconomyProvider provider = found.provider();
        assertThat(provider).isInstanceOf(VaultEconomy.class);
        verifyNoInteractions(economy);
        when(economy.fractionalDigits()).thenReturn(4);
        assertThat(provider.scale()).isEqualTo(3);
        assertThat(log.lines()).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    void anEconomyRegisteredWithoutAVaultPluginIsOnWithAnUnknownVersion() {
        registered();

        Detected<EconomyProvider> found = lookUp(true, null);

        assertThat(found.status()).isEqualTo(new HookStatus("Vault", HookState.ON, "unknown", null));
        assertThat(found.provider()).isInstanceOf(VaultEconomy.class);
    }

    @Test
    void theStatusDoesNotFollowVaultsEnabledState() {
        registered();
        Plugin vault = vaultPlugin("1.7.3");
        when(vault.isEnabled()).thenReturn(false);
        when(plugins.getPlugin("Vault")).thenReturn(vault);
        when(plugins.isPluginEnabled("Vault")).thenReturn(false);

        Detected<EconomyProvider> found = lookUp(false, null);

        assertThat(found.status().state()).isEqualTo(HookState.ON);
        assertThat(found.bound()).isNull();
        assertThat(found.provider()).isNotNull();
    }

    @Test
    void theStatusDoesNotFollowTheEnabledStateOfThePluginThatRegisteredTheEconomy() {
        registered();
        installVault("1.7.3");
        Detected<EconomyProvider> found = lookUp(false, null);
        HookStatus on = new HookStatus("Vault", HookState.ON, "1.7.3", null);
        EconomyProvider provider = found.provider();

        when(registrant.isEnabled()).thenReturn(false);

        assertThat(found.status()).isEqualTo(on);
        assertThat(found.bound()).isNull();
        assertThat(found.provider()).isSameAs(provider);
        assertThat(provider.withdraw(PLAYER, BigDecimal.ONE))
            .isEqualTo(EconomyResult.failed("the economy plugin Essentials is disabled"));
        assertThat(provider.deposit(PLAYER, BigDecimal.ONE))
            .isEqualTo(EconomyResult.failed("the economy plugin Essentials is disabled"));
        verifyNoInteractions(economy);
        assertThat(found.status()).isEqualTo(on);
        assertThat(log.lines()).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    void theEntryPointOnTheProviderInterfaceLooksUpTheSameWay() {
        registered();
        installVault("1.7.3");

        Detected<EconomyProvider> found = EconomyProvider.vault(owner, errors, true, false, null);

        assertThat(found.status()).isEqualTo(new HookStatus("Vault", HookState.ON, "1.7.3", null));
        assertThat(found.provider()).isInstanceOf(VaultEconomy.class);
        assertThat(EconomyProvider.vault(owner, errors, false, false, null).status().state())
            .isEqualTo(HookState.SWITCHED_OFF);
    }

    @Test
    void theEntryPointThatTakesOptionsLooksUpAsTheOneThatTakesEachValue() {
        registered();
        installVault("1.7.3");
        when(economy.fractionalDigits()).thenReturn(2);
        VaultOptions options = VaultOptions.of(owner, errors, false);

        Detected<EconomyProvider> found = EconomyProvider.vault(options);

        assertThat(found.status()).isEqualTo(EconomyProvider.vault(owner, errors, true, false, null).status());
        assertThat(found.provider()).isInstanceOf(VaultEconomy.class);
        assertThat(found.provider().thread(PLAYER)).isSameAs(ThreadTag.GLOBAL);
        assertThat(EconomyProvider.vault(owner, errors, true, false, null).provider().thread(PLAYER))
            .isSameAs(ThreadTag.GLOBAL);
        assertThat(EconomyProvider.vault(options.scale(3)).provider().scale()).isEqualTo(3);
        assertThat(EconomyProvider.vault(options.switchedOn(false)).status().state())
            .isEqualTo(HookState.SWITCHED_OFF);
        assertThat(EconomyProvider.vault(VaultOptions.of(owner, errors, true)).status())
            .isEqualTo(EconomyProvider.vault(owner, errors, true, true, null).status());
        assertThatThrownBy(() -> EconomyProvider.vault(null))
            .isInstanceOf(NullPointerException.class).hasMessage("options");
    }

    @Test
    void theEntityRuleOfTheOptionsReachesTheProvider() {
        registered();
        installVault("1.7.3");
        Player online = mock(Player.class);
        when(server.getPlayer(PLAYER)).thenReturn(online);

        EconomyProvider provider = EconomyProvider.vault(
            VaultOptions.of(owner, errors, true).threadRule(VaultOptions.ThreadRule.ENTITY)).provider();

        assertThat(provider.thread(PLAYER)).isEqualTo(ThreadTag.entity(PLAYER));
        when(server.getPlayer(PLAYER)).thenReturn(null);
        assertThat(provider.thread(PLAYER)).isSameAs(ThreadTag.GLOBAL);
        verifyNoInteractions(economy);
    }

    @Test
    void theNativeFundsCheckOfTheOptionsReachesTheProvider() {
        registered();
        installVault("1.7.3");
        when(economy.fractionalDigits()).thenReturn(2);
        OfflinePlayer account = mock(OfflinePlayer.class);
        when(server.getOfflinePlayer(PLAYER)).thenReturn(account);
        when(economy.getBalance(account)).thenReturn(5.0);
        when(economy.has(account, 20.0)).thenReturn(true);
        VaultOptions options = VaultOptions.of(owner, errors, false);

        EconomyProvider emulated = EconomyProvider.vault(options).provider();
        EconomyProvider natively =
            EconomyProvider.vault(options.fundsCheck(VaultOptions.FundsCheck.NATIVE)).provider();

        assertThat(emulated.has(PLAYER, new BigDecimal("20"))).isFalse();
        assertThat(EconomyProvider.vault(owner, errors, true, false, null).provider().has(PLAYER, new BigDecimal("20")))
            .isFalse();
        verify(economy, never()).has(any(OfflinePlayer.class), anyDouble());
        assertThat(natively.has(PLAYER, new BigDecimal("20"))).isTrue();
    }

    @Test
    @SuppressWarnings("deprecation")
    void theDeprecatedEntryPointReadsTheFoliaFlagFromTheSchedulerMode() {
        installVault("1.7.3");

        assertThat(EconomyProvider.vault(owner, errors, true, Scheduler.Mode.FOLIA, null).status())
            .isEqualTo(EconomyProvider.vault(owner, errors, true, true, null).status());
        assertThat(EconomyProvider.vault(owner, errors, true, Scheduler.Mode.PAPER, null).status())
            .isEqualTo(EconomyProvider.vault(owner, errors, true, false, null).status());
        assertThatThrownBy(() -> EconomyProvider.vault(owner, errors, true, (Scheduler.Mode) null, null))
            .isInstanceOf(NullPointerException.class).hasMessage("mode");
    }

    @Test
    void noRegistrationOnPaperAsksForAnEconomyOrForVaultAndAnEconomy() {
        installVault("1.7.3");
        Detected<EconomyProvider> unregistered = lookUp(false, null);

        assertThat(unregistered.status()).isEqualTo(new HookStatus("Vault", HookState.NOT_REGISTERED, "1.7.3",
            MissingEconomy.line(false, true)));
        assertThat(unregistered.status().describe()).isEqualTo("Vault 1.7.3 has nothing registered with it");
        assertThat(unregistered.provider()).isNull();

        when(plugins.getPlugin("Vault")).thenReturn(null);
        assertThat(lookUp(false, null).status()).isEqualTo(
            new HookStatus("Vault", HookState.NOT_INSTALLED, null, MissingEconomy.line(false, false)));
        assertThat(reported).isEmpty();
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void noRegistrationOnFoliaAsksForAFoliaEconomyOrForVaultUnlocked() {
        installVault("2.10.0");
        assertThat(lookUp(true, null).status()).isEqualTo(
            new HookStatus("Vault", HookState.NOT_REGISTERED, "2.10.0", MissingEconomy.line(true, true)));

        when(plugins.getPlugin("Vault")).thenReturn(null);
        assertThat(lookUp(true, null).status()).isEqualTo(
            new HookStatus("Vault", HookState.NOT_INSTALLED, null, MissingEconomy.line(true, false)));
    }

    @Test
    void aLookupThatThrowsIsLoggedReportedUnderHookVaultAndFailed() {
        IllegalStateException broken = new IllegalStateException("services unavailable");
        when(services.getRegistration(Economy.class)).thenThrow(broken);
        installVault("1.7.3");

        Detected<EconomyProvider> failed = lookUp(false, null);

        assertThat(failed.status()).isEqualTo(new HookStatus("Vault", HookState.FAILED, "1.7.3",
            "The economy could not be read (java.lang.IllegalStateException: services unavailable)."));
        assertThat(failed.provider()).isNull();
        assertThat(reported).containsExactly("hook.vault services unavailable");
        assertThat(log.lines()).containsExactly("WARNING Vault 1.7.3 could not be hooked"
            + " (java.lang.IllegalStateException: services unavailable), so it stays off.");
        assertThat(log.records.getFirst().getThrown()).isSameAs(broken);
    }

    @Test
    void aLookupGivenTheResultBeforeItWarnsAndReportsOnlyWhenTheStatusChanged() {
        when(services.getRegistration(Economy.class)).thenThrow(new IllegalStateException("services unavailable"));
        installVault("1.7.3");
        VaultOptions options = VaultOptions.of(owner, errors, false);

        Detected<EconomyProvider> first = EconomyProvider.vault(options, null);
        Detected<EconomyProvider> second = EconomyProvider.vault(options, first);
        Detected<EconomyProvider> third = EconomyProvider.vault(options, second);

        assertThat(second.status()).isEqualTo(first.status());
        assertThat(third.status()).isEqualTo(new HookStatus("Vault", HookState.FAILED, "1.7.3",
            "The economy could not be read (java.lang.IllegalStateException: services unavailable)."));
        assertThat(third.provider()).isNull();
        assertThat(log.lines()).containsExactly("WARNING Vault 1.7.3 could not be hooked"
            + " (java.lang.IllegalStateException: services unavailable), so it stays off.");
        assertThat(reported).containsExactly("hook.vault services unavailable");

        doThrow(new IllegalStateException("registry closed")).when(services).getRegistration(Economy.class);
        Detected<EconomyProvider> changed = EconomyProvider.vault(options, third);

        assertThat(changed.status().reason())
            .isEqualTo("The economy could not be read (java.lang.IllegalStateException: registry closed).");
        assertThat(log.lines()).hasSize(2).last().isEqualTo("WARNING Vault 1.7.3 could not be hooked"
            + " (java.lang.IllegalStateException: registry closed), so it stays off.");
        assertThat(reported).containsExactly("hook.vault services unavailable", "hook.vault registry closed");
    }

    @Test
    void aStatusThatCameBackAfterAnotherIsWarnedAboutAgain() {
        IllegalStateException broken = new IllegalStateException("services unavailable");
        when(services.getRegistration(Economy.class)).thenThrow(broken);
        installVault("1.7.3");
        VaultOptions options = VaultOptions.of(owner, errors, false);
        Detected<EconomyProvider> failed = EconomyProvider.vault(options, null);

        doReturn(null).when(services).getRegistration(Economy.class);
        Detected<EconomyProvider> missing = EconomyProvider.vault(options, failed);
        doThrow(broken).when(services).getRegistration(Economy.class);
        Detected<EconomyProvider> again = EconomyProvider.vault(options, missing);

        assertThat(missing.status().state()).isEqualTo(HookState.NOT_REGISTERED);
        assertThat(again.status()).isEqualTo(failed.status());
        assertThat(log.lines()).hasSize(2);
        assertThat(reported).hasSize(2);
    }

    @Test
    void theLookupsWithoutAResultBeforeThemWarnAndReportEveryTime() {
        when(services.getRegistration(Economy.class)).thenThrow(new IllegalStateException("services unavailable"));
        installVault("1.7.3");
        VaultOptions options = VaultOptions.of(owner, errors, false);

        EconomyProvider.vault(options);
        EconomyProvider.vault(owner, errors, true, false, null);
        EconomyProvider.vault(options, null);

        assertThat(log.lines()).hasSize(3);
        assertThat(reported).hasSize(3);
        assertThatThrownBy(() -> EconomyProvider.vault(null, lookUp(false, null)))
            .isInstanceOf(NullPointerException.class).hasMessage("options");
    }

    @Test
    void aLinkageErrorIsReportedAndAnyOtherErrorPropagates() {
        when(services.getRegistration(Economy.class)).thenThrow(new NoClassDefFoundError("net/milkbowl/vault/Gone"));

        assertThat(lookUp(false, null).status()).isEqualTo(new HookStatus("Vault", HookState.FAILED,
            "unknown", "The economy could not be read (java.lang.NoClassDefFoundError: net/milkbowl/vault/Gone)."));
        assertThat(reported).containsExactly("hook.vault net/milkbowl/vault/Gone");
        assertThat(log.lines()).containsExactly("WARNING Vault unknown could not be hooked"
            + " (java.lang.NoClassDefFoundError: net/milkbowl/vault/Gone), so it stays off.");

        doThrow(new AssertionError("not a linkage error")).when(services).getRegistration(Economy.class);
        assertThatThrownBy(() -> lookUp(false, null))
            .isInstanceOf(AssertionError.class).hasMessage("not a linkage error");
        assertThat(reported).hasSize(1);
        assertThat(log.lines()).hasSize(1);
    }

    @Test
    void aSwitchedOffEconomyReadsNothingFromTheServer() {
        Detected<EconomyProvider> off =
            EconomyLookup.vault(VaultOptions.of(owner, errors, false).switchedOn(false).scale(2));

        assertThat(off.status()).isEqualTo(new HookStatus("Vault", HookState.SWITCHED_OFF, null, null));
        assertThat(off.provider()).isNull();
        verifyNoInteractions(server, services, plugins);
        assertThat(log.lines()).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    void aScaleOutsideZeroToFourIsRefusedBeforeTheSwitchAndBeforeAnythingIsRead() {
        assertThatThrownBy(() -> lookUp(false, -1))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("a scale runs from 0 to 4, not -1");
        assertThatThrownBy(() -> lookUp(false, 5))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("a scale runs from 0 to 4, not 5");
        assertThatThrownBy(() -> EconomyProvider.vault(owner, errors, false, false, 5))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("a scale runs from 0 to 4, not 5");
        verifyNoInteractions(server, services, plugins);
    }

    @Test
    void aMissingOwnerOrErrorSinkIsRefused() {
        assertThatThrownBy(() -> EconomyProvider.vault(null, errors, true, false, null))
            .isInstanceOf(NullPointerException.class).hasMessage("owner");
        assertThatThrownBy(() -> EconomyProvider.vault(owner, null, true, false, null))
            .isInstanceOf(NullPointerException.class).hasMessage("errors");
    }

    @Test
    void theScalesAtBothEndsAreAccepted() {
        registered();
        when(economy.fractionalDigits()).thenReturn(4);

        Detected<EconomyProvider> finest = lookUp(false, 4);
        Detected<EconomyProvider> whole = lookUp(false, 0);

        assertThat(finest.provider().scale()).isEqualTo(4);
        assertThat(whole.provider().scale()).isZero();
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void theLookupChecksTheSameClassTheWatcherMatches() {
        when(plugins.getPlugin("Vault")).thenReturn(null);

        lookUp(false, 0);

        assertThat(EconomyWatcher.ECONOMY).isEqualTo(Economy.class.getName());
        verify(services).getRegistration(Economy.class);
    }
}
