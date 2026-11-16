package so.alaz.taffy.hooks;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.platform.ThreadTag;
import so.alaz.taffy.test.CapturedLog;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

final class VaultEconomyTest {

    private static final UUID PLAYER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    private final Server server = mock(Server.class);
    private final ServicesManager services = mock(ServicesManager.class);
    private final Economy economy = mock(Economy.class);
    private final OfflinePlayer account = mock(OfflinePlayer.class);
    private final CapturedLog log = new CapturedLog();

    @BeforeEach
    void server() {
        when(server.getServicesManager()).thenReturn(services);
        when(server.getOfflinePlayer(PLAYER)).thenReturn(account);
        when(economy.fractionalDigits()).thenReturn(2);
        when(economy.getName()).thenReturn("EssentialsX Economy");
    }

    @Test
    @SuppressWarnings("unchecked")
    void lookingUpFindsTheRegisteredEconomyAndCallsNothingInIt() {
        assertThat(VaultEconomy.lookup(server, null, log.logger)).isNull();

        RegisteredServiceProvider<Economy> registration = mock(RegisteredServiceProvider.class);
        when(registration.getProvider()).thenReturn(economy);
        when(services.getRegistration(Economy.class)).thenReturn(registration);
        EconomyProvider found = VaultEconomy.lookup(server, null, log.logger);

        assertThat(found).isInstanceOf(VaultEconomy.class);
        assertThat(found.thread(PLAYER)).isSameAs(ThreadTag.GLOBAL);
        verifyNoInteractions(economy);
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
    void aDepositGoesToTheEconomyWhetherOrNotThePlayerIsOnline() {
        when(economy.depositPlayer(account, 2.5)).thenReturn(success());

        assertThat(economy(null).deposit(PLAYER, new BigDecimal("2.50"))).isEqualTo(EconomyResult.ok());

        verify(economy).depositPlayer(account, 2.5);
        verify(server, never()).getPlayer(any(UUID.class));
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

    private VaultEconomy economy(Integer scale) {
        return new VaultEconomy(server, economy, scale, log.logger);
    }

    private static EconomyResponse success() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.SUCCESS, null);
    }
}
