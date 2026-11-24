package so.alaz.taffy.hooks;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ParameterizedClass
@EnumSource(VaultOptions.ThreadRule.class)
final class VaultEconomyTest extends EconomyProviderContract {

    private static final UUID OFFLINE = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");

    private final Server server = mock(Server.class);
    private final ServicesManager services = mock(ServicesManager.class);
    private final Economy economy = mock(Economy.class);
    private final OfflinePlayer account = mock(OfflinePlayer.class);
    private final Plugin registrant = mock(Plugin.class);
    private final Player online = mock(Player.class);
    private final FakeEconomy held = new FakeEconomy("DoubleBank", 4);
    private final CapturedLog log = new CapturedLog();
    private final VaultOptions.ThreadRule rule;

    VaultEconomyTest(VaultOptions.ThreadRule rule) {
        this.rule = rule;
    }

    @BeforeEach
    void server() {
        when(server.getServicesManager()).thenReturn(services);
        when(server.getOfflinePlayer(PLAYER)).thenReturn(account);
        when(account.getUniqueId()).thenReturn(PLAYER);
        onThread(true);
        when(economy.fractionalDigits()).thenReturn(2);
        when(economy.getName()).thenReturn("EssentialsX Economy");
        when(registrant.isEnabled()).thenReturn(true);
        when(registrant.getName()).thenReturn("Essentials");
    }

    // Under the entity rule PLAYER is online and the calling thread owns or does not own the player. Under the global
    // rule the calling thread is or is not the global tick thread.
    private void onThread(boolean on) {
        if (rule == VaultOptions.ThreadRule.ENTITY) {
            when(server.getPlayer(PLAYER)).thenReturn(online);
            when(server.isOwnedByCurrentRegion(online)).thenReturn(on);
        } else {
            when(server.isGlobalTickThread()).thenReturn(on);
        }
    }

    @Override
    EconomyProvider provider(int scale) {
        return new VaultEconomy(server, held, registrant, scale, log.logger, rule, VaultOptions.FundsCheck.EMULATED);
    }

    @Override
    Bank bank() {
        return new Bank() {
            @Override
            public void hold(String balance) {
                held.set(PLAYER, Double.parseDouble(balance));
            }

            @Override
            public BigDecimal held() {
                return VaultEconomy.amount(held.stored(PLAYER), EconomyProvider.MAX_SCALE, held.stored(PLAYER));
            }

            @Override
            public void refuseNext(String message) {
                held.answerNext(EconomyResponse.ResponseType.FAILURE, message);
            }

            @Override
            public void unsupportedNext() {
                held.answerNext(EconomyResponse.ResponseType.NOT_IMPLEMENTED, "not implemented");
            }

            @Override
            public void feeNext(String fee) {
                held.feeNext(Double.parseDouble(fee));
            }

            @Override
            public void onThread(boolean on) {
                VaultEconomyTest.this.onThread(on);
            }
        };
    }

    @Override
    boolean setsBalance() {
        return false;
    }

    @Test
    void settingABalanceIsUnsupportedOnceTheThreadIsCheckedAndCallsNothingInTheEconomy() {
        VaultEconomy provider = economy(2);

        assertThat(provider.setBalance(PLAYER, new BigDecimal("25")))
            .isEqualTo(EconomyResult.of(EconomyResult.Status.UNSUPPORTED));
        assertThat(provider.setBalance(PLAYER, new BigDecimal("-1")).status())
            .isEqualTo(EconomyResult.Status.UNSUPPORTED);
        when(registrant.isEnabled()).thenReturn(false);
        assertThat(provider.setBalance(PLAYER, BigDecimal.ONE).status()).isEqualTo(EconomyResult.Status.UNSUPPORTED);

        onThread(false);
        assertThatThrownBy(() -> provider.setBalance(PLAYER, BigDecimal.ONE))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(economy, registrant);
    }

    @Test
    void theCurrencyNamesComeFromTheEconomyAndABlankOrMissingOneIsNull() {
        VaultEconomy provider = economy(2);
        when(economy.currencyNameSingular()).thenReturn("Dollar", " ", null);
        when(economy.currencyNamePlural()).thenReturn("Dollars", "", null);
        onThread(false);

        assertThat(provider.currencyNameSingular()).isEqualTo("Dollar");
        assertThat(provider.currencyNamePlural()).isEqualTo("Dollars");
        assertThat(provider.currencyNameSingular()).isNull();
        assertThat(provider.currencyNamePlural()).isNull();
        assertThat(provider.currencyNameSingular()).isNull();
        assertThat(provider.currencyNamePlural()).isNull();
        assertThat(provider.currencySymbol()).isNull();
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aFormattedAmountIsRoundedHalfEvenToTheScaleAndSentAsADouble() {
        VaultEconomy provider = economy(2);
        when(economy.format(anyDouble())).thenAnswer(call -> "$" + call.getArgument(0, Double.class));
        onThread(false);

        assertThat(provider.format(new BigDecimal("1250.505"))).isEqualTo("$1250.5");
        assertThat(provider.format(new BigDecimal("1250.515"))).isEqualTo("$1250.52");
        assertThat(provider.format(new BigDecimal("-3"))).isEqualTo("$-3.0");
        assertThat(provider.format(BigDecimal.ZERO)).isEqualTo("$0.0");
        verify(economy).format(1250.5);
        verify(economy).format(1250.52);

        when(economy.format(anyDouble())).thenReturn(" ", (String) null);
        assertThat(provider.format(BigDecimal.ONE)).isNull();
        assertThat(provider.format(BigDecimal.ONE)).isNull();
        assertThatThrownBy(() -> provider.format(new BigDecimal("1E+400")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("1E+400 is beyond the range of a double");
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aCurrencyNameOrAFormatThatThrowsIsNullAndLoggedOnceForEachCall() {
        VaultEconomy provider = economy(2);
        IllegalStateException closed = new IllegalStateException("the economy is closed");
        when(economy.currencyNameSingular()).thenThrow(closed);
        when(economy.currencyNamePlural()).thenThrow(new NoSuchMethodError("currencyNamePlural"));
        when(economy.format(anyDouble())).thenThrow(new UnsupportedOperationException("format"));

        assertThat(provider.currencyNameSingular()).isNull();
        assertThat(provider.currencyNameSingular()).isNull();
        assertThat(provider.currencyNamePlural()).isNull();
        assertThat(provider.format(BigDecimal.ONE)).isNull();
        assertThat(provider.format(BigDecimal.TEN)).isNull();

        assertThat(log.lines()).containsExactly(
            "WARNING The economy threw in currencyNameSingular, so it is read as having none ("
                + "java.lang.IllegalStateException: the economy is closed).",
            "WARNING The economy threw in currencyNamePlural, so it is read as having none ("
                + "java.lang.NoSuchMethodError: currencyNamePlural).",
            "WARNING The economy threw in format, so it is read as having none ("
                + "java.lang.UnsupportedOperationException: format).");
        assertThat(log.records.getFirst().getThrown()).isSameAs(closed);
    }

    @Test
    void aFormattedAmountIsNullAndLoggedWhenTheEconomyThrowsWhileTheScaleIsWorkedOut() {
        VaultEconomy provider = economy(null);
        when(economy.fractionalDigits()).thenThrow(new IllegalStateException("not loaded"));

        assertThat(provider.format(BigDecimal.ONE)).isNull();

        assertThat(log.lines()).containsExactly("WARNING The economy threw in format, so it is read as having none ("
            + "java.lang.IllegalStateException: not loaded).");
        verify(economy, never()).format(anyDouble());
    }

    @Test
    void aReceiptOfTheVaultEconomyHasNoTransactionId() {
        held.set(PLAYER, 10.0);
        EconomyProvider provider = provider(2);

        assertThat(provider.withdrawReceipt(PLAYER, new BigDecimal("4"), () -> true))
            .isEqualTo(new EconomyReceipt(EconomyResult.ok(), null));
        assertThat(provider.depositReceipt(PLAYER, BigDecimal.ONE))
            .isEqualTo(new EconomyReceipt(EconomyResult.ok(), null));
        assertThat(held.calls).containsExactly("getBalance", "withdrawPlayer 4.0", "getBalance",
            "depositPlayer 1.0");
    }

    @Test
    @SuppressWarnings("unchecked")
    void lookingUpFindsTheRegisteredEconomyAndCallsNothingInIt() {
        assertThat(VaultEconomy.lookup(server, null, log.logger, VaultOptions.ThreadRule.GLOBAL,
            VaultOptions.FundsCheck.EMULATED)).isNull();

        RegisteredServiceProvider<Economy> registration = mock(RegisteredServiceProvider.class);
        when(registration.getProvider()).thenReturn(economy);
        when(registration.getPlugin()).thenReturn(registrant);
        when(services.getRegistration(Economy.class)).thenReturn(registration);
        EconomyProvider found = VaultEconomy.lookup(server, null, log.logger, VaultOptions.ThreadRule.GLOBAL,
            VaultOptions.FundsCheck.EMULATED);

        assertThat(found).isInstanceOf(VaultEconomy.class);
        assertThat(found.thread(PLAYER)).isSameAs(ThreadTag.GLOBAL);
        verifyNoInteractions(economy);
        verifyNoInteractions(registrant);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aWithdrawOrDepositIsRefusedWithNoCallIntoTheEconomyWhileItsPluginIsDisabled() {
        FakeEconomy stored = new FakeEconomy("DoubleBank", 2);
        stored.set(PLAYER, 10.0);
        RegisteredServiceProvider<Economy> registration = mock(RegisteredServiceProvider.class);
        when(registration.getProvider()).thenReturn(stored);
        when(registration.getPlugin()).thenReturn(registrant);
        when(services.getRegistration(Economy.class)).thenReturn(registration);
        EconomyProvider provider =
            VaultEconomy.lookup(server, null, log.logger, rule, VaultOptions.FundsCheck.EMULATED);
        List<String> started = new ArrayList<>();

        when(registrant.isEnabled()).thenReturn(false);

        EconomyResult refused = EconomyResult.failed("the economy plugin Essentials is disabled");
        assertThat(provider.withdraw(PLAYER, BigDecimal.ONE)).isEqualTo(refused);
        assertThat(provider.withdraw(PLAYER, BigDecimal.ONE, () -> started.add("start"))).isEqualTo(refused);
        assertThat(provider.deposit(PLAYER, BigDecimal.ONE)).isEqualTo(refused);
        assertThat(provider.deposit(PLAYER, new BigDecimal("-1"))).isEqualTo(refused);
        assertThat(refused.moved()).isFalse();
        assertThat(started).isEmpty();
        assertThat(stored.calls).isEmpty();
        assertThat(stored.stored(PLAYER)).isEqualTo(10.0);
        assertThatThrownBy(() -> provider.withdraw(PLAYER, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> provider.deposit(null, BigDecimal.ONE)).isInstanceOf(NullPointerException.class);

        assertThat(provider.balance(PLAYER)).isEqualTo(new BigDecimal("10.00"));
        assertThat(provider.has(PLAYER, BigDecimal.TEN)).isTrue();
        assertThat(provider.has(PLAYER, new BigDecimal("10.01"))).isFalse();
        assertThat(stored.calls).containsExactly("getBalance", "getBalance", "getBalance");

        when(registrant.isEnabled()).thenReturn(true);
        assertThat(provider.withdraw(PLAYER, BigDecimal.ONE)).isEqualTo(EconomyResult.ok());
        assertThat(provider.deposit(PLAYER, new BigDecimal("2.50"))).isEqualTo(EconomyResult.ok());
        assertThat(stored.stored(PLAYER)).isEqualTo(11.5);
    }

    @Test
    void theEntityRuleNamesThePlayersThreadWhileOnlineAndTheGlobalThreadWhileNot() {
        VaultEconomy provider = new VaultEconomy(server, economy, registrant, null, log.logger,
            VaultOptions.ThreadRule.ENTITY, VaultOptions.FundsCheck.EMULATED);
        when(server.getPlayer(PLAYER)).thenReturn(online);

        assertThat(provider.thread(PLAYER)).isEqualTo(ThreadTag.entity(PLAYER));
        assertThat(provider.thread(OFFLINE)).isSameAs(ThreadTag.GLOBAL);
        when(server.getPlayer(PLAYER)).thenReturn(null);
        assertThat(provider.thread(PLAYER)).isSameAs(ThreadTag.GLOBAL);
        assertThatThrownBy(() -> provider.thread(null)).isInstanceOf(NullPointerException.class).hasMessage("player");
        verifyNoInteractions(economy);
    }

    @Test
    void theGlobalRuleNamesTheGlobalThreadAndAsksTheServerNothing() {
        VaultEconomy provider = new VaultEconomy(server, economy, registrant, null, log.logger,
            VaultOptions.ThreadRule.GLOBAL, VaultOptions.FundsCheck.EMULATED);
        when(server.isGlobalTickThread()).thenReturn(true);
        when(economy.getBalance(account)).thenReturn(10.0);

        assertThat(provider.thread(PLAYER)).isSameAs(ThreadTag.GLOBAL);
        assertThat(provider.balance(PLAYER)).isEqualTo(new BigDecimal("10.00"));
        verify(server, never()).getPlayer(any(UUID.class));
        verify(server, never()).isOwnedByCurrentRegion(any(Entity.class));
    }

    @Test
    void underTheGlobalRuleACallOffTheGlobalTickThreadThrowsBeforeAnythingElseIsRead() {
        VaultEconomy provider = new VaultEconomy(server, economy, registrant, null, log.logger,
            VaultOptions.ThreadRule.GLOBAL, VaultOptions.FundsCheck.EMULATED);
        when(server.isGlobalTickThread()).thenReturn(false);
        when(server.isOwnedByCurrentRegion(online)).thenReturn(true);
        String refusal = "an economy call for " + PLAYER + " runs on the thread thread(player) names, GLOBAL, not on "
            + Thread.currentThread().getName() + "; run it through Scheduler.dispatch(economy.thread(player), ...)";

        assertThatThrownBy(() -> provider.balance(PLAYER)).isInstanceOf(IllegalStateException.class)
            .hasMessage(refusal);
        assertThatThrownBy(() -> provider.has(PLAYER, BigDecimal.ONE)).isInstanceOf(IllegalStateException.class)
            .hasMessage(refusal);
        assertThatThrownBy(() -> provider.withdraw(PLAYER, new BigDecimal("-1")))
            .isInstanceOf(IllegalStateException.class).hasMessage(refusal);
        assertThatThrownBy(() -> provider.deposit(PLAYER, BigDecimal.ZERO))
            .isInstanceOf(IllegalStateException.class).hasMessage(refusal);
        verifyNoInteractions(economy, registrant);
        verify(server, never()).getOfflinePlayer(any(UUID.class));
    }

    @Test
    void underTheEntityRuleACallForAnOnlinePlayerRunsOnTheThreadThatOwnsThePlayer() {
        VaultEconomy provider = new VaultEconomy(server, economy, registrant, null, log.logger,
            VaultOptions.ThreadRule.ENTITY, VaultOptions.FundsCheck.EMULATED);
        when(server.getPlayer(PLAYER)).thenReturn(online);
        when(economy.getBalance(account)).thenReturn(10.0);
        when(economy.depositPlayer(account, 1.0)).thenReturn(success());

        when(server.isOwnedByCurrentRegion(online)).thenReturn(true);
        when(server.isGlobalTickThread()).thenReturn(false);
        assertThat(provider.balance(PLAYER)).isEqualTo(new BigDecimal("10.00"));
        assertThat(provider.has(PLAYER, BigDecimal.TEN)).isTrue();
        assertThat(provider.deposit(PLAYER, BigDecimal.ONE)).isEqualTo(EconomyResult.ok());

        when(server.isOwnedByCurrentRegion(online)).thenReturn(false);
        when(server.isGlobalTickThread()).thenReturn(true);
        assertThatThrownBy(() -> provider.deposit(PLAYER, BigDecimal.ONE)).isInstanceOf(IllegalStateException.class)
            .hasMessage("an economy call for " + PLAYER + " runs on the thread thread(player) names, ENTITY(" + PLAYER
                + "), not on " + Thread.currentThread().getName()
                + "; run it through Scheduler.dispatch(economy.thread(player), ...)");
        assertThatThrownBy(() -> provider.withdraw(PLAYER, BigDecimal.ONE))
            .isInstanceOf(IllegalStateException.class);
        verify(economy).depositPlayer(account, 1.0);
        verify(economy, never()).withdrawPlayer(any(OfflinePlayer.class), anyDouble());
    }

    @Test
    void underTheEntityRuleACallForAnOfflinePlayerRunsOnTheGlobalTickThread() {
        VaultEconomy provider = new VaultEconomy(server, economy, registrant, null, log.logger,
            VaultOptions.ThreadRule.ENTITY, VaultOptions.FundsCheck.EMULATED);
        OfflinePlayer away = mock(OfflinePlayer.class);
        when(server.getOfflinePlayer(OFFLINE)).thenReturn(away);
        when(economy.getBalance(away)).thenReturn(10.0, 9.0);
        when(economy.withdrawPlayer(away, 1.0)).thenReturn(success());

        when(server.isGlobalTickThread()).thenReturn(true);
        assertThat(provider.thread(OFFLINE)).isSameAs(ThreadTag.GLOBAL);
        assertThat(provider.withdraw(OFFLINE, BigDecimal.ONE)).isEqualTo(EconomyResult.ok());

        when(server.isGlobalTickThread()).thenReturn(false);
        assertThatThrownBy(() -> provider.withdraw(OFFLINE, BigDecimal.ONE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageStartingWith("an economy call for " + OFFLINE + " runs on the thread thread(player) names,"
                + " GLOBAL, not on ");
        verify(economy).withdrawPlayer(away, 1.0);
        verify(server, never()).isOwnedByCurrentRegion(any(Entity.class));
    }

    @Test
    void theThreadCheckComesAfterTheNullChecksAndBeforeTheDisabledPluginAndTheAmount() {
        VaultEconomy provider = economy(null);
        when(registrant.isEnabled()).thenReturn(false);
        onThread(false);

        assertThatThrownBy(() -> provider.withdraw(null, BigDecimal.ONE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> provider.has(PLAYER, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> provider.withdraw(PLAYER, BigDecimal.ONE))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> provider.deposit(PLAYER, new BigDecimal("-1")))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(economy, registrant);
    }

    @Test
    void aBalanceAndAPriceBothOfTenCentsPassTheCheck() {
        when(economy.getBalance(account)).thenReturn(0.1, 0.0);
        when(economy.withdrawPlayer(account, 0.1)).thenReturn(success());

        EconomyResult result = economy(null).withdraw(PLAYER, new BigDecimal("0.10"));

        assertThat(result).isEqualTo(EconomyResult.ok());
        verify(economy).withdrawPlayer(account, 0.1);
    }

    @Test
    void aShortBalanceIsInsufficientAndTheEconomyIsNotAskedToWithdraw() {
        when(economy.getBalance(account)).thenReturn(5.0);

        EconomyResult result = economy(null).withdraw(PLAYER, new BigDecimal("10.00"));

        assertThat(result.status()).isEqualTo(EconomyResult.Status.INSUFFICIENT);
        verify(economy, never()).withdrawPlayer(eq(account), anyDouble());
    }

    @Test
    void aStartThatDeclinesStopsTheWithdrawAfterTheCheck() {
        when(economy.getBalance(account)).thenReturn(10.0);

        EconomyResult result = economy(null).withdraw(PLAYER, BigDecimal.ONE, () -> false);

        assertThat(result.status()).isEqualTo(EconomyResult.Status.NOT_STARTED);
        verify(economy).getBalance(account);
        verify(economy, never()).withdrawPlayer(eq(account), anyDouble());
    }

    @Test
    void aBalanceBelowZeroAfterTheWithdrawIsOverdrawn() {
        when(economy.getBalance(account)).thenReturn(10.0, -1.0);
        when(economy.withdrawPlayer(account, 10.0)).thenReturn(success());

        assertThat(economy(null).withdraw(PLAYER, BigDecimal.TEN).status())
            .isEqualTo(EconomyResult.Status.OVERDRAWN);
    }

    @Test
    void aRefusedOrMissingResponseIsReportedAsTheEconomyGaveIt() {
        when(economy.getBalance(account)).thenReturn(10.0);
        when(economy.withdrawPlayer(account, 1.0)).thenReturn(
            new EconomyResponse(0, 10, EconomyResponse.ResponseType.FAILURE, "Account is locked"));
        assertThat(economy(null).withdraw(PLAYER, BigDecimal.ONE)).isEqualTo(EconomyResult.failed("Account is locked"));

        when(economy.withdrawPlayer(account, 1.0)).thenReturn(
            new EconomyResponse(0, 10, EconomyResponse.ResponseType.FAILURE, ""));
        assertThat(economy(null).withdraw(PLAYER, BigDecimal.ONE))
            .isEqualTo(EconomyResult.failed("the economy refused it"));

        when(economy.withdrawPlayer(account, 1.0)).thenReturn(
            new EconomyResponse(0, 10, EconomyResponse.ResponseType.FAILURE, null));
        assertThat(economy(null).withdraw(PLAYER, BigDecimal.ONE))
            .isEqualTo(EconomyResult.failed("the economy refused it"));

        when(economy.depositPlayer(account, 1.0)).thenReturn(
            new EconomyResponse(0, 10, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "no"));
        assertThat(economy(null).deposit(PLAYER, BigDecimal.ONE).status())
            .isEqualTo(EconomyResult.Status.UNSUPPORTED);

        when(economy.depositPlayer(account, 1.0)).thenReturn(null);
        assertThatThrownBy(() -> economy(null).deposit(PLAYER, BigDecimal.ONE))
            .isInstanceOf(IllegalStateException.class).hasMessage("the economy returned no response");
    }

    @Test
    void aDepositGoesToTheEconomyThroughTheOfflinePlayerOfTheServer() {
        when(economy.depositPlayer(account, 2.5)).thenReturn(success());

        assertThat(economy(null).deposit(PLAYER, new BigDecimal("2.50"))).isEqualTo(EconomyResult.ok());

        verify(economy).depositPlayer(account, 2.5);
        verify(server).getOfflinePlayer(PLAYER);
    }

    @Test
    void anAmountIsRoundedHalfEvenToTheScaleBeforeItReachesTheEconomy() {
        when(economy.depositPlayer(eq(account), anyDouble())).thenReturn(success());

        economy(null).deposit(PLAYER, new BigDecimal("2.345"));
        economy(null).deposit(PLAYER, new BigDecimal("2.355"));

        verify(economy).depositPlayer(account, 2.34);
        verify(economy).depositPlayer(account, 2.36);
    }

    @Test
    void aNegativeAmountOrOneThatRoundsToZeroIsRefusedBeforeTheEconomyIsCalled() {
        VaultEconomy provider = economy(null);

        assertThatThrownBy(() -> provider.deposit(PLAYER, new BigDecimal("-1")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("an amount is positive, not -1");
        assertThatThrownBy(() -> provider.withdraw(PLAYER, new BigDecimal("-0.01")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.deposit(PLAYER, new BigDecimal("0.004")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("0.004 rounds to zero at 2 decimal places");
        assertThatThrownBy(() -> provider.withdraw(PLAYER, new BigDecimal("0.005")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("0.005 rounds to zero at 2 decimal places");
        assertThatThrownBy(() -> provider.deposit(PLAYER, BigDecimal.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
        verify(economy, never()).getBalance(any(OfflinePlayer.class));
        verify(economy, never()).withdrawPlayer(any(OfflinePlayer.class), anyDouble());
        verify(economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
    }

    @Test
    void hasComparesTheUnroundedBalanceWithTheRoundedAmount() {
        when(economy.getBalance(account)).thenReturn(0.995);
        VaultEconomy provider = economy(null);

        assertThat(provider.balance(PLAYER)).isEqualTo(new BigDecimal("1.00"));
        assertThat(provider.has(PLAYER, new BigDecimal("0.99"))).isTrue();
        assertThat(provider.has(PLAYER, new BigDecimal("0.994"))).isTrue();
        assertThat(provider.has(PLAYER, new BigDecimal("1.00"))).isFalse();
        assertThat(provider.has(PLAYER, new BigDecimal("0.995"))).isFalse();
        verify(economy, never()).has(any(OfflinePlayer.class), anyDouble());
    }

    @Test
    void aBalanceThatRoundsUpToTheAmountDoesNotCoverIt() {
        when(economy.getBalance(account)).thenReturn(99.5, 99.5);
        VaultEconomy whole = economy(0);

        assertThat(whole.has(PLAYER, new BigDecimal("100"))).isFalse();
        assertThat(whole.withdraw(PLAYER, new BigDecimal("100")).status())
            .isEqualTo(EconomyResult.Status.INSUFFICIENT);
        verify(economy, never()).withdrawPlayer(eq(account), anyDouble());
    }

    @Test
    void aBalanceJustUnderZeroAfterTheWithdrawIsOverdrawn() {
        when(economy.getBalance(account)).thenReturn(10.0, -0.005);
        when(economy.withdrawPlayer(account, 10.0)).thenReturn(success());

        assertThat(economy(null).withdraw(PLAYER, new BigDecimal("10")))
            .isEqualTo(EconomyResult.of(EconomyResult.Status.OVERDRAWN));
    }

    @Test
    void anEconomyWithoutTheOfflinePlayerBalanceFailsTheWithdrawBeforeAnyMoneyMoves() {
        when(economy.getBalance(account)).thenThrow(new AbstractMethodError("getBalance"));

        assertThat(economy(null).withdraw(PLAYER, BigDecimal.ONE))
            .isEqualTo(EconomyResult.failed("the balance could not be read: getBalance"));
        verify(economy, never()).withdrawPlayer(eq(account), anyDouble());
    }

    @Test
    void aBalanceLeftByADoubleSubtractionCoversTheAmountItDisplaysAs() {
        FakeEconomy stored = new FakeEconomy("DoubleBank", 2);
        VaultEconomy provider = emulated(stored);

        stored.set(PLAYER, 1.0);
        assertThat(provider.withdraw(PLAYER, new BigDecimal("0.90"))).isEqualTo(EconomyResult.ok());
        assertThat(stored.stored(PLAYER)).isEqualTo(0.09999999999999998);
        assertThat(provider.balance(PLAYER)).isEqualTo(new BigDecimal("0.10"));
        assertThat(provider.has(PLAYER, new BigDecimal("0.10"))).isTrue();
        assertThat(provider.withdraw(PLAYER, new BigDecimal("0.10"))).isEqualTo(EconomyResult.ok());
        assertThat(stored.stored(PLAYER)).isNegative();
        assertThat(provider.balance(PLAYER)).isEqualTo(new BigDecimal("0.00"));

        stored.set(PLAYER, 0.3);
        assertThat(provider.withdraw(PLAYER, new BigDecimal("0.10"))).isEqualTo(EconomyResult.ok());
        assertThat(stored.stored(PLAYER)).isEqualTo(0.19999999999999998);
        assertThat(provider.has(PLAYER, new BigDecimal("0.20"))).isTrue();
        assertThat(provider.withdraw(PLAYER, new BigDecimal("0.20"))).isEqualTo(EconomyResult.ok());
    }

    @Test
    void aBalanceThatIsReallyShortStaysShortAndOneReallyBelowZeroStaysOverdrawn() {
        FakeEconomy stored = new FakeEconomy("DoubleBank", 4);
        VaultEconomy provider = emulated(stored);

        stored.set(PLAYER, 0.0999);
        assertThat(provider.has(PLAYER, new BigDecimal("0.10"))).isFalse();
        assertThat(provider.withdraw(PLAYER, new BigDecimal("0.10")))
            .isEqualTo(EconomyResult.of(EconomyResult.Status.INSUFFICIENT));
        assertThat(stored.calls).doesNotContain("withdrawPlayer 0.1");

        stored.set(PLAYER, 0.09999999);
        assertThat(provider.has(PLAYER, new BigDecimal("0.10"))).isFalse();

        stored.set(PLAYER, 0.99995);
        assertThat(provider.has(PLAYER, new BigDecimal("1.0000"))).isFalse();

        stored.set(PLAYER, 0.09999);
        assertThat(provider.withdraw(PLAYER, new BigDecimal("0.0999"))).isEqualTo(EconomyResult.ok());
        stored.set(PLAYER, 0.0999);
        assertThat(provider.withdraw(PLAYER, new BigDecimal("0.1")).status())
            .isEqualTo(EconomyResult.Status.INSUFFICIENT);
        stored.set(PLAYER, 0.09995);
        assertThat(provider.withdraw(PLAYER, new BigDecimal("0.1")).status())
            .isEqualTo(EconomyResult.Status.INSUFFICIENT);
        stored.set(PLAYER, 0.1);
        stored.calls.clear();
        assertThat(provider.withdraw(PLAYER, new BigDecimal("0.1001")).status())
            .isEqualTo(EconomyResult.Status.INSUFFICIENT);
        assertThat(stored.calls).containsExactly("getBalance");
    }

    @Test
    void aBalanceIsReadAtTheGuardScale() {
        assertThat(VaultEconomy.GUARD_SCALE).isEqualTo(EconomyProvider.MAX_SCALE + 4);
        assertThat(VaultEconomy.amount(0.09999999999999998)).isEqualByComparingTo("0.1");
        assertThat(VaultEconomy.amount(0.19999999999999998)).isEqualByComparingTo("0.2");
        assertThat(VaultEconomy.amount(1.0 - 0.9 - 0.1)).isEqualByComparingTo("0");
        assertThat(VaultEconomy.amount(0.1 + 0.2)).isEqualByComparingTo("0.3");
        assertThat(VaultEconomy.amount(0.0999)).isEqualByComparingTo("0.0999");
        assertThat(VaultEconomy.amount(0.995)).isEqualByComparingTo("0.995");
        assertThat(VaultEconomy.amount(99.5)).isEqualByComparingTo("99.5");
        assertThat(VaultEconomy.amount(-0.005)).isEqualByComparingTo("-0.005");
        assertThat(VaultEconomy.amount(-0.00000001)).isEqualByComparingTo("-0.00000001");
        assertThat(VaultEconomy.amount(1234567890123.45)).isEqualByComparingTo("1234567890123.45");
    }

    @Test
    void aBalanceWithinTheNoiseOfAnAmountAtTheScaleReadsAsThatAmount() {
        assertThat(VaultEconomy.NOISE_ULPS).isEqualTo(16);
        double left = 1000000456.18 - 28.10;
        assertThat(VaultEconomy.amount(left)).isEqualByComparingTo("1000000428.0799999");
        assertThat(VaultEconomy.amount(left, 2, left)).isEqualTo(new BigDecimal("1000000428.08000000"));
        assertThat(read(0.09999999999999998, 2)).isEqualByComparingTo("0.1");
        assertThat(read(0.0999, 2)).isEqualByComparingTo("0.0999");
        assertThat(read(0.09999999, 2)).isEqualByComparingTo("0.09999999");
        assertThat(read(0.99995, 4)).isEqualByComparingTo("0.99995");
        assertThat(read(1234567890123.456, 2)).isEqualByComparingTo("1234567890123.456");
        assertThat(read(123456789012.3456, 2)).isEqualByComparingTo("123456789012.3456");
        assertThat(read(-0.00000001, 2)).isEqualByComparingTo("-0.00000001");
        assertThat(read(0.0, 2)).isEqualByComparingTo("0");
        assertThatThrownBy(() -> read(Double.NaN, 2)).isInstanceOf(IllegalStateException.class);

        double emptied = left - 1000000428.08;
        assertThat(emptied).isNegative();
        assertThat(read(emptied, 2)).isNegative();
        assertThat(VaultEconomy.amount(emptied, 2, left)).isEqualByComparingTo("0");
        assertThat(VaultEconomy.amount(-0.01, 2, left)).isEqualByComparingTo("-0.01");
        assertThat(VaultEconomy.amount(-0.001, 2, left)).isEqualByComparingTo("-0.001");
    }

    private static BigDecimal read(double value, int scale) {
        return VaultEconomy.amount(value, scale, value);
    }

    @Test
    void aLargeBalanceLeftByADoubleSubtractionCoversTheAmountItDisplaysAs() {
        FakeEconomy stored = new FakeEconomy("DoubleBank", 2);
        VaultEconomy provider = emulated(stored);

        stored.set(PLAYER, 1000000456.18);
        assertThat(provider.withdraw(PLAYER, new BigDecimal("28.10"))).isEqualTo(EconomyResult.ok());
        assertThat(stored.stored(PLAYER)).isLessThan(1000000428.08);
        assertThat(provider.balance(PLAYER)).isEqualTo(new BigDecimal("1000000428.08"));
        assertThat(provider.has(PLAYER, new BigDecimal("1000000428.08"))).isTrue();
        assertThat(provider.has(PLAYER, new BigDecimal("1000000428.09"))).isFalse();
        stored.calls.clear();
        assertThat(provider.withdraw(PLAYER, new BigDecimal("1000000428.08"))).isEqualTo(EconomyResult.ok());
        assertThat(stored.calls).contains("withdrawPlayer 1.00000042808E9");
        assertThat(stored.stored(PLAYER)).isNegative();
        assertThat(provider.balance(PLAYER)).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void aBalanceThatCannotBeReadAfterAWithdrawThatWentThroughLeavesTheResultOkAndIsLogged() {
        IllegalStateException gone = new IllegalStateException("economy went away");
        when(economy.getBalance(account)).thenReturn(10.0).thenThrow(gone);
        when(economy.withdrawPlayer(account, 1.0)).thenReturn(success());
        VaultEconomy provider = economy(null);

        assertThat(provider.withdraw(PLAYER, BigDecimal.ONE)).isEqualTo(EconomyResult.ok());

        assertThat(log.lines()).containsExactly("WARNING The balance could not be read after a withdraw that went"
            + " through, so it was not checked for an overdraft (" + gone + ").");
        assertThat(log.records.getFirst().getThrown()).isSameAs(gone);

        doReturn(10.0).doThrow(new AbstractMethodError("getBalance")).when(economy).getBalance(account);
        assertThat(provider.withdraw(PLAYER, BigDecimal.ONE)).isEqualTo(EconomyResult.ok());
        assertThat(log.lines()).hasSize(1);
    }

    @Test
    void aNullPlayerIsRefusedBeforeTheServerIsAsked() {
        VaultEconomy provider = economy(null);

        assertThatThrownBy(() -> provider.balance(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> provider.has(null, BigDecimal.ONE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> provider.withdraw(null, BigDecimal.ONE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> provider.deposit(null, BigDecimal.ONE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> provider.withdraw(PLAYER, BigDecimal.ONE, null))
            .isInstanceOf(NullPointerException.class);
        verify(server, never()).getOfflinePlayer(any(UUID.class));
    }

    @Test
    void hasAcceptsAnAmountAWithdrawRefusesAndComparesItAsGiven() {
        when(economy.getBalance(account)).thenReturn(0.0, 0.0, -3.0, -3.0);
        VaultEconomy provider = economy(null);

        assertThat(provider.has(PLAYER, new BigDecimal("0.004"))).isTrue();
        assertThat(provider.has(PLAYER, new BigDecimal("-5"))).isTrue();
        assertThat(provider.has(PLAYER, new BigDecimal("-5"))).isTrue();
        assertThat(provider.has(PLAYER, new BigDecimal("-2"))).isFalse();
    }

    @Test
    void anAmountADoubleCannotCarryExactlyIsRefusedBeforeTheEconomyIsCalled() {
        when(economy.depositPlayer(eq(account), anyDouble())).thenReturn(success());
        VaultEconomy provider = economy(null);

        assertThatThrownBy(() -> provider.deposit(PLAYER, new BigDecimal("12345678901234567.89")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("12345678901234567.89 cannot reach the economy exactly as a double");
        assertThatThrownBy(() -> provider.withdraw(PLAYER, new BigDecimal("1E400")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("1E+400 cannot reach the economy exactly as a double");
        verify(economy, never()).getBalance(any(OfflinePlayer.class));
        verify(economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());

        assertThat(provider.deposit(PLAYER, new BigDecimal("1234567890123.45"))).isEqualTo(EconomyResult.ok());
        verify(economy).depositPlayer(account, 1234567890123.45);
    }

    @Test
    void theScaleComesFromTheOverrideOrTheEconomyOnFirstUseAndIsReadOnce() {
        assertThat(economy(null).scale()).isEqualTo(2);

        when(economy.fractionalDigits()).thenReturn(-1);
        assertThat(economy(null).scale()).isEqualTo(2);

        when(economy.fractionalDigits()).thenReturn(6);
        VaultEconomy fine = economy(null);
        assertThat(fine.scale()).isEqualTo(4);
        assertThat(fine.scale()).isEqualTo(4);

        when(economy.fractionalDigits()).thenReturn(2);
        assertThat(economy(4).scale()).isEqualTo(4);
        assertThat(log.lines()).containsExactly("WARNING scale 4 is finer than the 2 decimal places"
            + " EssentialsX Economy keeps, so the economy may round amounts sent to it.");
        assertThat(economy(0).scale()).isZero();

        when(economy.fractionalDigits()).thenReturn(-1);
        assertThat(economy(3).scale()).isEqualTo(3);
        assertThat(log.lines()).hasSize(1);
        verify(economy, times(6)).fractionalDigits();
    }

    @Test
    void aScaleEqualToTheEconomysOwnIsNotWarnedAbout() {
        assertThat(economy(2).scale()).isEqualTo(2);
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aBalanceIsRoundedAndAnUnreadableOneFailsBeforeAnyWithdraw() {
        when(economy.getBalance(account)).thenReturn(12.345);
        assertThat(economy(null).balance(PLAYER)).isEqualTo(new BigDecimal("12.34"));

        when(economy.getBalance(account)).thenReturn(Double.NaN);
        assertThat(economy(null).withdraw(PLAYER, BigDecimal.ONE).status())
            .isEqualTo(EconomyResult.Status.FAILED);
        verify(economy, never()).withdrawPlayer(eq(account), anyDouble());
        assertThatThrownBy(() -> economy(null).balance(PLAYER))
            .isInstanceOf(IllegalStateException.class).hasMessage("the economy reported a balance of NaN");

        when(economy.getBalance(account)).thenReturn(Double.POSITIVE_INFINITY);
        assertThatThrownBy(() -> economy(null).has(PLAYER, BigDecimal.ONE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theProbeNamesTheEconomyAndThrowsWhenItCannotBeRead() {
        assertThat(economy(null).probe()).isEqualTo("EssentialsX Economy through Vault, 2 decimal places");

        when(economy.fractionalDigits()).thenReturn(-1);
        assertThat(economy(null).probe())
            .isEqualTo("EssentialsX Economy through Vault, 2 decimal places (the economy reports -1)");
        when(economy.fractionalDigits()).thenReturn(6);
        assertThat(economy(null).probe())
            .isEqualTo("EssentialsX Economy through Vault, 4 decimal places (the economy reports 6)");

        when(economy.getName()).thenThrow(new IllegalStateException("economy disabled"));
        assertThatThrownBy(() -> economy(null).probe())
            .isInstanceOf(IllegalStateException.class).hasMessage("economy disabled");
    }

    @Test
    void underTheDefaultCheckAnOverdraftTheEconomyAllowsIsRefusedWithoutAskingIt() {
        FakeEconomy lender = new FakeEconomy("LoanBank", 2);
        lender.set(PLAYER, 5.0);
        lender.credit(50.0);
        VaultEconomy provider = emulated(lender);

        assertThat(provider.has(PLAYER, new BigDecimal("20"))).isFalse();
        assertThat(provider.withdraw(PLAYER, new BigDecimal("20")))
            .isEqualTo(EconomyResult.of(EconomyResult.Status.INSUFFICIENT));
        assertThat(lender.calls).containsExactly("getBalance", "getBalance");
        assertThat(lender.stored(PLAYER)).isEqualTo(5.0);
    }

    @Test
    void underTheNativeCheckHasAsksTheEconomyAndAWithdrawReachesItWithNoBalanceRead() {
        FakeEconomy lender = new FakeEconomy("LoanBank", 2);
        lender.set(PLAYER, 5.0);
        lender.credit(50.0);
        VaultEconomy provider = natively(lender);

        assertThat(provider.has(PLAYER, new BigDecimal("20"))).isTrue();
        assertThat(provider.has(PLAYER, new BigDecimal("55.004"))).isTrue();
        assertThat(provider.has(PLAYER, new BigDecimal("55.01"))).isFalse();
        assertThat(lender.calls).containsExactly("has 20.0", "has 55.0", "has 55.01");

        lender.calls.clear();
        EconomyResult result = provider.withdraw(PLAYER, new BigDecimal("20"));

        assertThat(result).isEqualTo(EconomyResult.ok());
        assertThat(result.moved()).isTrue();
        assertThat(lender.calls).containsExactly("withdrawPlayer 20.0");
        assertThat(lender.stored(PLAYER)).isEqualTo(-15.0);
        assertThat(provider.balance(PLAYER)).isEqualTo(new BigDecimal("-15.00"));
    }

    @Test
    void underTheNativeCheckAWithdrawTheEconomyRefusesIsFailedWithItsMessage() {
        FakeEconomy lender = new FakeEconomy("LoanBank", 2);
        lender.set(PLAYER, 5.0);
        lender.credit(50.0);
        VaultEconomy provider = natively(lender);

        EconomyResult refused = provider.withdraw(PLAYER, new BigDecimal("55.01"));

        assertThat(refused).isEqualTo(EconomyResult.failed("Insufficient funds"));
        assertThat(refused.moved()).isFalse();
        assertThat(lender.calls).containsExactly("withdrawPlayer 55.01");
        assertThat(lender.stored(PLAYER)).isEqualTo(5.0);

        lender.answerNext(EconomyResponse.ResponseType.FAILURE, " ");
        assertThat(provider.withdraw(PLAYER, BigDecimal.ONE)).isEqualTo(EconomyResult.failed("the economy refused it"));
        lender.answerNext(EconomyResponse.ResponseType.NOT_IMPLEMENTED, "no");
        assertThat(provider.withdraw(PLAYER, BigDecimal.ONE))
            .isEqualTo(EconomyResult.of(EconomyResult.Status.UNSUPPORTED));
        assertThat(lender.stored(PLAYER)).isEqualTo(5.0);
    }

    @Test
    void underTheNativeCheckStartRunsBeforeTheEconomyIsAskedAndADeclineCallsNothingInIt() {
        FakeEconomy lender = new FakeEconomy("LoanBank", 2);
        lender.set(PLAYER, 5.0);
        VaultEconomy provider = natively(lender);
        List<String> order = new ArrayList<>();

        assertThat(provider.withdraw(PLAYER, BigDecimal.ONE, () -> false))
            .isEqualTo(EconomyResult.of(EconomyResult.Status.NOT_STARTED));
        assertThat(lender.calls).isEmpty();

        assertThat(provider.withdraw(PLAYER, new BigDecimal("7.005"), () -> order.add("start " + lender.calls)))
            .isEqualTo(EconomyResult.ok());
        assertThat(order).containsExactly("start []");
        assertThat(lender.calls).containsExactly("withdrawPlayer 7.0");
        assertThat(lender.stored(PLAYER)).isEqualTo(-2.0);
    }

    @Test
    void underTheNativeCheckTheAmountTheThreadAndTheDisabledPluginAreCheckedAsUnderTheDefault() {
        FakeEconomy lender = new FakeEconomy("LoanBank", 2);
        lender.set(PLAYER, 5.0);
        VaultEconomy provider = natively(lender);

        assertThatThrownBy(() -> provider.withdraw(PLAYER, new BigDecimal("-1")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("an amount is positive, not -1");
        assertThatThrownBy(() -> provider.withdraw(PLAYER, new BigDecimal("0.004")))
            .isInstanceOf(IllegalArgumentException.class);
        when(registrant.isEnabled()).thenReturn(false);
        assertThat(provider.withdraw(PLAYER, BigDecimal.ONE))
            .isEqualTo(EconomyResult.failed("the economy plugin Essentials is disabled"));
        when(registrant.isEnabled()).thenReturn(true);
        onThread(false);
        assertThatThrownBy(() -> provider.has(PLAYER, BigDecimal.ONE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> provider.withdraw(PLAYER, BigDecimal.ONE)).isInstanceOf(IllegalStateException.class);
        assertThat(lender.calls).isEmpty();

        onThread(true);
        assertThat(provider.deposit(PLAYER, BigDecimal.ONE)).isEqualTo(EconomyResult.ok());
        assertThat(lender.stored(PLAYER)).isEqualTo(6.0);
    }

    private VaultEconomy economy(Integer scale) {
        return new VaultEconomy(server, economy, registrant, scale, log.logger, rule, VaultOptions.FundsCheck.EMULATED);
    }

    private VaultEconomy emulated(Economy over) {
        return new VaultEconomy(server, over, registrant, null, log.logger, rule, VaultOptions.FundsCheck.EMULATED);
    }

    private VaultEconomy natively(Economy over) {
        return new VaultEconomy(server, over, registrant, null, log.logger, rule, VaultOptions.FundsCheck.NATIVE);
    }

    private static EconomyResponse success() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.SUCCESS, null);
    }
}
