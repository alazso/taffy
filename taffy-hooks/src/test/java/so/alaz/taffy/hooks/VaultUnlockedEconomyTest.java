package so.alaz.taffy.hooks;

import io.papermc.paper.plugin.configuration.PluginMeta;
import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.platform.ThreadTag;
import so.alaz.taffy.test.CapturedLog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

final class VaultUnlockedEconomyTest extends EconomyProviderContract {

    private static final String CALLER = "Shop";
    private static final String WORLD = "lobby";
    private static final String GEMS = "gems";

    private final Server server = mock(Server.class);
    private final ServicesManager services = mock(ServicesManager.class);
    private final PluginManager plugins = mock(PluginManager.class);
    private final Economy economy = mock(Economy.class);
    private final Plugin registrant = mock(Plugin.class);
    private final Plugin vault = mock(Plugin.class);
    private final Plugin owner = mock(Plugin.class);
    private final CapturedLog log = new CapturedLog();
    private final List<String> reported = new ArrayList<>();
    private final ErrorReports errors = new ErrorReports();
    private final List<String> calls = new ArrayList<>();
    private BigDecimal held = BigDecimal.ZERO;
    private EconomyResponse next;
    private BigDecimal fee = BigDecimal.ZERO;

    @BeforeEach
    void server() {
        World first = mock(World.class);
        when(first.getName()).thenReturn(WORLD);
        World second = mock(World.class);
        when(second.getName()).thenReturn("nether");
        when(server.getWorlds()).thenReturn(List.of(first, second));
        when(server.isGlobalTickThread()).thenReturn(true);
        when(server.getServicesManager()).thenReturn(services);
        when(server.getPluginManager()).thenReturn(plugins);
        when(registrant.isEnabled()).thenReturn(true);
        when(registrant.getName()).thenReturn("TheNewEconomy");
        PluginMeta meta = mock(PluginMeta.class);
        when(meta.getVersion()).thenReturn("2.20.3");
        when(vault.getPluginMeta()).thenReturn(meta);
        when(owner.getServer()).thenReturn(server);
        when(owner.getLogger()).thenReturn(log.logger);
        when(owner.getName()).thenReturn(CALLER);
        errors.install((source, error) -> reported.add(source + " " + error.getMessage()));

        when(economy.getName()).thenReturn("TNE");
        when(economy.hasCurrency(GEMS)).thenReturn(true);
        when(economy.currencies()).thenReturn(List.of("dollars", GEMS));
        when(economy.getDefaultCurrency(CALLER)).thenReturn("dollars");
        when(economy.fractionalDigits(CALLER, GEMS)).thenReturn(2);
        when(economy.balance(CALLER, PLAYER, WORLD, GEMS)).thenAnswer(call -> {
            calls.add("balance");
            return held;
        });
        when(economy.withdraw(eq(CALLER), eq(PLAYER), eq(WORLD), eq(GEMS), any(BigDecimal.class)))
            .thenAnswer(call -> move("withdraw", call.getArgument(4, BigDecimal.class).negate()));
        when(economy.deposit(eq(CALLER), eq(PLAYER), eq(WORLD), eq(GEMS), any(BigDecimal.class)))
            .thenAnswer(call -> move("deposit", call.getArgument(4, BigDecimal.class)));
        when(economy.set(eq(CALLER), eq(PLAYER), eq(WORLD), eq(GEMS), any(BigDecimal.class)))
            .thenAnswer(call -> {
                BigDecimal wanted = call.getArgument(4, BigDecimal.class);
                return move("set", wanted.subtract(held));
            });
    }

    private EconomyResponse move(String call, BigDecimal change) {
        calls.add(call + " " + change.abs().toPlainString());
        if (next != null) {
            EconomyResponse answer = next;
            next = null;
            return answer;
        }
        held = held.add(change).subtract(fee);
        fee = BigDecimal.ZERO;
        return new EconomyResponse(change.abs(), held, EconomyResponse.ResponseType.SUCCESS, "");
    }

    private static final VaultUnlockedEconomy.Links ALL = new VaultUnlockedEconomy.Links(true, true);

    private VaultUnlockedEconomy economy(Integer scale) {
        return new VaultUnlockedEconomy(server, economy, registrant, CALLER, GEMS, scale, ALL, log.logger);
    }

    @Override
    EconomyProvider provider(int scale) {
        return economy(scale);
    }

    @Override
    boolean setsBalance() {
        return true;
    }

    @Override
    Bank bank() {
        return new Bank() {
            @Override
            public void hold(String balance) {
                held = new BigDecimal(balance);
            }

            @Override
            public BigDecimal held() {
                return held;
            }

            @Override
            public void refuseNext(String message) {
                next = new EconomyResponse(BigDecimal.ZERO, held, EconomyResponse.ResponseType.FAILURE, message);
            }

            @Override
            public void unsupportedNext() {
                next = new EconomyResponse(BigDecimal.ZERO, held, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "");
            }

            @Override
            public void feeNext(String amount) {
                fee = new BigDecimal(amount);
            }

            @Override
            public void onThread(boolean on) {
                when(server.isGlobalTickThread()).thenReturn(on);
            }
        };
    }

    private void register() {
        when(plugins.getPlugin("Vault")).thenReturn(vault);
        when(services.getRegistration(Economy.class)).thenReturn(
            new RegisteredServiceProvider<>(Economy.class, economy, ServicePriority.Normal, registrant));
    }

    @Test
    void everyCallNamesTheOwnerTheFirstWorldAndTheCurrencyAndAnAmountReachesTheEconomyAsABigDecimal() {
        VaultUnlockedEconomy provider = economy(2);
        held = new BigDecimal("100");
        BigDecimal large = new BigDecimal("12345678901234567890.12");

        assertThat(provider.deposit(PLAYER, large)).isEqualTo(EconomyResult.ok());
        assertThat(provider.withdraw(PLAYER, new BigDecimal("0.105"))).isEqualTo(EconomyResult.ok());
        assertThat(provider.balance(PLAYER)).isEqualTo(new BigDecimal("12345678901234567990.02"));

        verify(economy).deposit(CALLER, PLAYER, WORLD, GEMS, large);
        verify(economy).withdraw(CALLER, PLAYER, WORLD, GEMS, new BigDecimal("0.10"));
        assertThat(calls).containsExactly("deposit 12345678901234567890.12", "balance", "withdraw 0.10", "balance",
            "balance");
        assertThat(provider.thread(PLAYER)).isSameAs(ThreadTag.GLOBAL);
        assertThat(provider.thread(UUID.randomUUID())).isSameAs(ThreadTag.GLOBAL);
    }

    @Test
    void aBalanceTheEconomyKeepsFinerThanTheScaleIsComparedUnrounded() {
        VaultUnlockedEconomy provider = economy(2);
        held = new BigDecimal("9.999");

        assertThat(provider.balance(PLAYER)).isEqualTo(new BigDecimal("10.00"));
        assertThat(provider.has(PLAYER, BigDecimal.TEN)).isFalse();
        assertThat(provider.withdraw(PLAYER, BigDecimal.TEN))
            .isEqualTo(EconomyResult.of(EconomyResult.Status.INSUFFICIENT));
        assertThat(held).isEqualByComparingTo("9.999");
    }

    @Test
    void aMoveForACurrencyTheEconomyNoLongerHasFailsBeforeAnythingMoves() {
        VaultUnlockedEconomy provider = economy(2);
        held = BigDecimal.TEN;
        when(economy.hasCurrency(GEMS)).thenReturn(false);
        EconomyResult gone = EconomyResult.failed("the economy TNE has no currency named gems");

        assertThat(provider.withdraw(PLAYER, BigDecimal.ONE)).isEqualTo(gone);
        assertThat(provider.deposit(PLAYER, BigDecimal.ONE)).isEqualTo(gone);
        assertThat(provider.setBalance(PLAYER, BigDecimal.ONE)).isEqualTo(gone);

        assertThat(calls).isEmpty();
        assertThat(held).isEqualByComparingTo("10");
        assertThat(provider.balance(PLAYER)).isEqualTo(new BigDecimal("10.00"));
    }

    @Test
    void whileNoWorldIsLoadedAMoveFailsAndABalanceReadThrows() {
        VaultUnlockedEconomy provider = economy(2);
        held = BigDecimal.TEN;
        when(server.getWorlds()).thenReturn(List.of());

        assertThat(provider.withdraw(PLAYER, BigDecimal.ONE)).isEqualTo(EconomyResult.failed("no world is loaded yet"));
        assertThat(provider.deposit(PLAYER, BigDecimal.ONE)).isEqualTo(EconomyResult.failed("no world is loaded yet"));
        assertThat(provider.setBalance(PLAYER, BigDecimal.ONE))
            .isEqualTo(EconomyResult.failed("no world is loaded yet"));
        assertThatThrownBy(() -> provider.balance(PLAYER)).isInstanceOf(IllegalStateException.class)
            .hasMessageStartingWith("no world is loaded yet");
        assertThatThrownBy(() -> provider.has(PLAYER, BigDecimal.ONE)).isInstanceOf(IllegalStateException.class);
        assertThat(calls).isEmpty();
    }

    @Test
    void whileTheRegisteringPluginIsDisabledAMoveFailsBeforeTheAmountIsCheckedAndNothingIsCalled() {
        VaultUnlockedEconomy provider = economy(2);
        when(registrant.isEnabled()).thenReturn(false);
        EconomyResult disabled = EconomyResult.failed("the economy plugin TheNewEconomy is disabled");

        assertThat(provider.withdraw(PLAYER, new BigDecimal("-1"))).isEqualTo(disabled);
        assertThat(provider.deposit(PLAYER, BigDecimal.ZERO)).isEqualTo(disabled);
        assertThat(provider.setBalance(PLAYER, new BigDecimal("-1"))).isEqualTo(disabled);
        verifyNoInteractions(economy);
    }

    @Test
    void theScaleIsTheCurrencysFractionalDigitsCappedOrTwoWhenItReportsNoneAndAFinerOneIsWarnedAbout() {
        when(economy.fractionalDigits(CALLER, GEMS)).thenReturn(6);
        assertThat(economy(null).scale()).isEqualTo(EconomyProvider.MAX_SCALE);
        when(economy.fractionalDigits(CALLER, GEMS)).thenReturn(-1);
        assertThat(economy(null).scale()).isEqualTo(2);
        when(economy.fractionalDigits(CALLER, GEMS)).thenReturn(0);
        assertThat(economy(null).scale()).isZero();
        assertThat(log.lines()).isEmpty();

        VaultUnlockedEconomy finer = economy(3);
        assertThat(finer.scale()).isEqualTo(3);
        assertThat(finer.scale()).isEqualTo(3);
        assertThat(log.lines()).containsExactly("WARNING scale 3 is finer than the 0 decimal places TNE keeps for"
            + " gems, so the economy may round amounts sent to it.");
        assertThat(finer.probe())
            .isEqualTo("TNE through VaultUnlocked, currency gems, 3 decimal places (the economy reports 0)");
    }

    @Test
    void onlyTheDefaultCurrencyHasNamesAndNoCurrencyHasASymbol() {
        when(economy.defaultCurrencyNameSingular(CALLER)).thenReturn("Dollar");
        when(economy.defaultCurrencyNamePlural(CALLER)).thenReturn("Dollars");
        VaultUnlockedEconomy gems = economy(2);
        VaultUnlockedEconomy dollars =
            new VaultUnlockedEconomy(server, economy, registrant, CALLER, "dollars", 2, ALL, log.logger);
        when(server.isGlobalTickThread()).thenReturn(false);

        assertThat(gems.currencyNameSingular()).isNull();
        assertThat(gems.currencyNamePlural()).isNull();
        assertThat(dollars.currencyNameSingular()).isEqualTo("Dollar");
        assertThat(dollars.currencyNamePlural()).isEqualTo("Dollars");
        assertThat(dollars.currencySymbol()).isNull();

        when(economy.defaultCurrencyNameSingular(CALLER)).thenReturn(" ");
        when(economy.defaultCurrencyNamePlural(CALLER)).thenThrow(new IllegalStateException("no locale"));
        assertThat(dollars.currencyNameSingular()).isNull();
        assertThat(dollars.currencyNamePlural()).isNull();
        assertThat(dollars.currencyNamePlural()).isNull();
        assertThat(log.lines()).containsExactly("WARNING The economy threw in defaultCurrencyNamePlural, so it is read"
            + " as having none (java.lang.IllegalStateException: no locale).");
    }

    @Test
    void aFormattedAmountIsRoundedHalfEvenToTheScaleAndNamesTheCurrency() {
        VaultUnlockedEconomy provider = economy(2);
        when(economy.format(eq(CALLER), any(BigDecimal.class), eq(GEMS)))
            .thenAnswer(call -> call.getArgument(1, BigDecimal.class).toPlainString() + " gems");
        when(server.isGlobalTickThread()).thenReturn(false);

        assertThat(provider.format(new BigDecimal("1250.505"))).isEqualTo("1250.50 gems");
        assertThat(provider.format(new BigDecimal("-3"))).isEqualTo("-3.00 gems");
        assertThat(provider.format(new BigDecimal("1E+400"))).startsWith("1000").endsWith(".00 gems");

        when(economy.format(anyString(), any(BigDecimal.class), anyString())).thenReturn(" ", (String) null);
        assertThat(provider.format(BigDecimal.ONE)).isNull();
        assertThat(provider.format(BigDecimal.ONE)).isNull();
    }

    @Test
    void aFormattedAmountIsNullAndLoggedWhenTheEconomyThrowsWhileTheScaleIsWorkedOut() {
        VaultUnlockedEconomy provider = economy(null);
        when(economy.fractionalDigits(CALLER, GEMS)).thenThrow(new IllegalStateException("currencies not loaded"));

        assertThat(provider.format(BigDecimal.ONE)).isNull();

        assertThat(log.lines()).containsExactly("WARNING The economy threw in format, so it is read as having none"
            + " (java.lang.IllegalStateException: currencies not loaded).");
        verify(economy, never()).format(anyString(), any(BigDecimal.class), anyString());
    }

    @Test
    void aBalanceSetThatLandsOnAnotherBalanceThrowsAndOneThatCannotBeReadBackStaysOk() {
        VaultUnlockedEconomy provider = economy(2);
        held = new BigDecimal("100.00");
        fee = BigDecimal.ONE;

        assertThatThrownBy(() -> provider.setBalance(PLAYER, new BigDecimal("50")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("the economy TNE answered the balance set with a success and holds 49.00, not 50.00");
        assertThat(held).isEqualByComparingTo("49");
        assertThat(calls).containsExactly("set 50.00", "balance");

        when(economy.balance(CALLER, PLAYER, WORLD, GEMS)).thenThrow(new IllegalStateException("database closed"));
        assertThat(provider.setBalance(PLAYER, new BigDecimal("20"))).isEqualTo(EconomyResult.ok());
        assertThat(held).isEqualByComparingTo("20");
        assertThat(log.lines()).containsExactly("WARNING The balance could not be read after a balance set that went"
            + " through, so it was not checked against the amount (java.lang.IllegalStateException: database"
            + " closed).");
    }

    @Test
    void aVaultUnlockedWithoutSetAnswersUnsupportedAndOneWithoutCurrencyDigitsAsksForTheDigitsOfTheEconomy() {
        VaultUnlockedEconomy old = new VaultUnlockedEconomy(server, economy, registrant, CALLER, GEMS, null,
            new VaultUnlockedEconomy.Links(false, false), log.logger);
        held = BigDecimal.TEN;
        when(economy.fractionalDigits(CALLER)).thenReturn(3);

        assertThat(old.setBalance(PLAYER, new BigDecimal("-1")))
            .isEqualTo(EconomyResult.of(EconomyResult.Status.UNSUPPORTED));
        assertThat(old.scale()).isEqualTo(3);
        assertThat(old.probe()).isEqualTo("TNE through VaultUnlocked, currency gems, 3 decimal places");
        assertThat(old.withdraw(PLAYER, new BigDecimal("2.5005"))).isEqualTo(EconomyResult.ok());

        assertThat(held).isEqualByComparingTo("7.500");
        verify(economy, never()).set(anyString(), any(UUID.class), anyString(), anyString(), any(BigDecimal.class));
        verify(economy, never()).fractionalDigits(anyString(), anyString());
    }

    @Test
    void aReceiptOfTheVaultUnlockedEconomyHasNoTransactionId() {
        VaultUnlockedEconomy provider = economy(2);
        held = BigDecimal.TEN;

        assertThat(provider.withdrawReceipt(PLAYER, BigDecimal.ONE, () -> true))
            .isEqualTo(new EconomyReceipt(EconomyResult.ok(), null));
        assertThat(provider.depositReceipt(PLAYER, BigDecimal.ONE))
            .isEqualTo(new EconomyReceipt(EconomyResult.ok(), null));
    }

    @Test
    void aMissingResponseThrowsAndABlankRefusalHasAMessage() {
        VaultUnlockedEconomy provider = economy(2);
        held = BigDecimal.TEN;
        next = new EconomyResponse(BigDecimal.ZERO, held, EconomyResponse.ResponseType.FAILURE, " ");

        assertThat(provider.deposit(PLAYER, BigDecimal.ONE)).isEqualTo(EconomyResult.failed("the economy refused it"));

        when(economy.deposit(eq(CALLER), eq(PLAYER), eq(WORLD), eq(GEMS), any(BigDecimal.class))).thenReturn(null);
        assertThatThrownBy(() -> provider.deposit(PLAYER, BigDecimal.ONE)).isInstanceOf(IllegalStateException.class)
            .hasMessage("the economy returned no response");
        when(economy.balance(CALLER, PLAYER, WORLD, GEMS)).thenReturn(null);
        assertThatThrownBy(() -> provider.balance(PLAYER)).isInstanceOf(IllegalStateException.class)
            .hasMessage("the economy reported no balance");
    }

    @Test
    void aLookupFindsTheCurrencyReadsNoBalanceAndItsStatusNamesVaultUnlocked() {
        register();
        held = new BigDecimal("7");

        Detected<EconomyProvider> found = EconomyProvider.vaultUnlocked(owner, errors, true, false, GEMS, null);

        assertThat(found.status()).isEqualTo(new HookStatus("VaultUnlocked", HookState.ON, "2.20.3", null));
        assertThat(found.provider()).isInstanceOf(VaultUnlockedEconomy.class);
        assertThat(found.bound()).isNull();
        assertThat(calls).isEmpty();
        verify(economy, never()).fractionalDigits(anyString(), anyString());
        assertThat(found.provider().balance(PLAYER)).isEqualTo(new BigDecimal("7.00"));
        assertThat(log.lines()).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    void aLookupForACurrencyTheEconomyLacksIsNotRegisteredAndNamesTheCurrenciesItHas() {
        register();

        Detected<EconomyProvider> found = EconomyProvider.vaultUnlocked(owner, errors, true, false, "tokens", 2);

        assertThat(found.provider()).isNull();
        assertThat(found.status()).isEqualTo(new HookStatus("VaultUnlocked", HookState.NOT_REGISTERED, "2.20.3",
            "TNE has no currency named tokens. It has dollars, gems."));
        assertThat(found.status().line()).isEqualTo("VaultUnlocked 2.20.3 has nothing registered with it. TNE has no"
            + " currency named tokens. It has dollars, gems.");
        assertThat(log.lines()).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    void aLookupWithNoEconomyRegisteredAsksForOneAndWithNoVaultPluginForBoth() {
        when(plugins.getPlugin("Vault")).thenReturn(vault);

        assertThat(EconomyProvider.vaultUnlocked(owner, errors, true, false, GEMS, null).status())
            .isEqualTo(new HookStatus("VaultUnlocked", HookState.NOT_REGISTERED, "2.20.3", "VaultUnlocked is"
                + " installed, but no economy plugin that supports VaultUnlocked has registered with it. Install"
                + " one."));
        assertThat(EconomyProvider.vaultUnlocked(owner, errors, true, true, GEMS, null).status().reason())
            .isEqualTo("VaultUnlocked is installed, but no economy plugin that supports VaultUnlocked and Folia has"
                + " registered with it. Install one.");

        when(plugins.getPlugin("Vault")).thenReturn(null);
        assertThat(EconomyProvider.vaultUnlocked(owner, errors, true, false, GEMS, null).status())
            .isEqualTo(new HookStatus("VaultUnlocked", HookState.NOT_INSTALLED, null, "VaultUnlocked and an economy"
                + " plugin that supports VaultUnlocked are needed. Install both."));
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aLookupThatThrowsIsFailedLoggedAndReportedUnderHookVaultunlocked() {
        register();
        when(economy.hasCurrency(GEMS)).thenThrow(new IllegalStateException("currencies not loaded"));

        Detected<EconomyProvider> found = EconomyProvider.vaultUnlocked(owner, errors, true, false, GEMS, null);

        assertThat(found.provider()).isNull();
        assertThat(found.status()).isEqualTo(new HookStatus("VaultUnlocked", HookState.FAILED, "2.20.3",
            "The economy could not be read (java.lang.IllegalStateException: currencies not loaded)."));
        assertThat(reported).containsExactly("hook.vaultunlocked currencies not loaded");
        assertThat(log.lines()).containsExactly("WARNING VaultUnlocked 2.20.3 could not be hooked"
            + " (java.lang.IllegalStateException: currencies not loaded), so it stays off.");
    }

    @Test
    void aLookupThatIsSwitchedOffReadsNothingAndABlankCurrencyOrAScaleOutOfRangeIsRefused() {
        Detected<EconomyProvider> off = EconomyProvider.vaultUnlocked(owner, errors, false, false, GEMS, null);

        assertThat(off.status()).isEqualTo(new HookStatus("VaultUnlocked", HookState.SWITCHED_OFF, null, null));
        verifyNoInteractions(server, services, plugins);
        assertThatThrownBy(() -> EconomyProvider.vaultUnlocked(owner, errors, false, false, " ", null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("a currency id is not blank");
        assertThatThrownBy(() -> EconomyProvider.vaultUnlocked(owner, errors, false, false, GEMS, 5))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("a scale runs from 0 to 4, not 5");
        assertThatThrownBy(() -> EconomyProvider.vaultUnlocked(null, errors, true, false, GEMS, null))
            .isInstanceOf(NullPointerException.class).hasMessage("owner");
        assertThatThrownBy(() -> EconomyProvider.vaultUnlocked(owner, null, true, false, GEMS, null))
            .isInstanceOf(NullPointerException.class).hasMessage("errors");
        assertThatThrownBy(() -> EconomyProvider.vaultUnlocked(owner, errors, true, false, null, null))
            .isInstanceOf(NullPointerException.class).hasMessage("currency");
    }

    @Test
    void theCurrenciesAreListedByIdAndOnlyTheDefaultOneHasAName() {
        assertThat(EconomyProvider.vaultUnlockedCurrencies(owner, errors)).isEmpty();
        register();
        when(economy.defaultCurrencyNameSingular(CALLER)).thenReturn("Dollar");

        List<EconomyCurrency> listed = EconomyProvider.vaultUnlockedCurrencies(owner, errors);

        assertThat(listed).containsExactly(new EconomyCurrency("dollars", "Dollar", null),
            new EconomyCurrency(GEMS, null, null));
        assertThatThrownBy(() -> listed.add(new EconomyCurrency("x", null, null)))
            .isInstanceOf(UnsupportedOperationException.class);

        when(economy.currencies()).thenThrow(new IllegalStateException("currencies not loaded"));
        assertThat(EconomyProvider.vaultUnlockedCurrencies(owner, errors)).isEmpty();
        assertThat(reported).containsExactly("hook.vaultunlocked currencies not loaded");
        assertThat(log.lines()).containsExactly("WARNING The currencies of the VaultUnlocked economy could not be"
            + " listed (java.lang.IllegalStateException: currencies not loaded).");
    }

    @Test
    void theVaultLookupReadsOnlyTheVaultApiAndFindsNoEconomyWhereOnlyAVaultUnlockedOneIsRegistered() {
        register();

        Detected<EconomyProvider> classic = EconomyProvider.vault(owner, errors, true, false, null);

        assertThat(classic.status().state()).isEqualTo(HookState.NOT_REGISTERED);
        assertThat(classic.status().plugin()).isEqualTo("Vault");
        verify(services).getRegistration(net.milkbowl.vault.economy.Economy.class);
        verify(services, never()).getRegistration(Economy.class);
        verifyNoInteractions(economy);
    }
}
