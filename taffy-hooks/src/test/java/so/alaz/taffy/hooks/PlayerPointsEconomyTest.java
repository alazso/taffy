package so.alaz.taffy.hooks;

import io.papermc.paper.plugin.configuration.PluginMeta;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.platform.ThreadTag;
import so.alaz.taffy.test.CapturedLog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// PlayerPoints holds whole points, so the contract tests that move or read a fraction are replaced here by ones in
// whole points, and the provider has the one scale whatever scale a contract test asks for.
final class PlayerPointsEconomyTest extends EconomyProviderContract {

    private final Server server = mock(Server.class);
    private final Plugin plugin = mock(Plugin.class);
    private final PluginMeta meta = mock(PluginMeta.class);
    private final PlayerPointsAPI api = mock(PlayerPointsAPI.class);
    private final List<String> calls = new ArrayList<>();
    private final CapturedLog log = new CapturedLog();
    private int held;
    private boolean refuse;

    @BeforeEach
    void playerPoints() {
        when(server.isPrimaryThread()).thenReturn(false);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getPluginMeta()).thenReturn(meta);
        when(meta.getVersion()).thenReturn("3.3.5");
        when(api.look(PLAYER)).thenAnswer(call -> look());
        when(api.take(eq(PLAYER), anyInt())).thenAnswer(call -> offset("take", -call.getArgument(1, Integer.class)));
        when(api.give(eq(PLAYER), anyInt())).thenAnswer(call -> offset("give", call.getArgument(1, Integer.class)));
        when(api.set(eq(PLAYER), anyInt())).thenAnswer(call -> store(call.getArgument(1, Integer.class)));
        when(api.getCurrencyNameSingular()).thenReturn("Point");
        when(api.getCurrencyNamePlural()).thenReturn("Points");
    }

    // As PlayerPoints does when a listener cancelled its change event, a refused set leaves the points as they were.
    private synchronized boolean store(int points) {
        calls.add("set " + points);
        if (refuse) {
            refuse = false;
            return false;
        }
        held = points;
        return true;
    }

    private synchronized int look() {
        calls.add("look");
        return held;
    }

    // As PlayerPoints does, refuses a change that leaves the balance below zero or wraps it past Integer.MAX_VALUE,
    // and moves the whole change or nothing.
    private synchronized boolean offset(String call, int change) {
        calls.add(call + " " + Math.abs(change));
        if (refuse) {
            refuse = false;
            return false;
        }
        if (held + change < 0) {
            return false;
        }
        held += change;
        return true;
    }

    @Override
    EconomyProvider provider(int scale) {
        return new PlayerPointsEconomy(server, plugin, api, log.logger, true, true);
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
                held = new BigDecimal(balance).intValueExact();
            }

            @Override
            public BigDecimal held() {
                return BigDecimal.valueOf(held);
            }

            @Override
            public void refuseNext(String message) {
                refuse = true;
            }

            @Override
            public void unsupportedNext() {
                throw new UnsupportedOperationException("PlayerPoints answers no call as unsupported");
            }

            @Override
            public void feeNext(String fee) {
                throw new UnsupportedOperationException("PlayerPoints takes no fee");
            }

            @Override
            public void onThread(boolean on) {
                when(server.isPrimaryThread()).thenReturn(!on);
            }
        };
    }

    @Test
    @Override
    void aBalanceIsRoundedHalfEvenToTheScale() {
        held = 12;

        assertThat(provider(2).balance(PLAYER)).isEqualTo(new BigDecimal("12"));
        assertThat(provider(2).scale()).isZero();
    }

    @Test
    @Override
    void anAmountIsRoundedHalfEvenToTheScaleBeforeItMoves() {
        EconomyProvider provider = provider(0);
        held = 10;
        List<String> started = new ArrayList<>();

        for (String fraction : List.of("0.5", "1.5", "2.5", "2.4", "2.999", "1.0001")) {
            BigDecimal amount = new BigDecimal(fraction);
            assertThatThrownBy(() -> provider.deposit(PLAYER, amount)).as(fraction)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(fraction + " cannot reach PlayerPoints exactly: an amount is a whole number of points up"
                    + " to 2147483647");
            assertThatThrownBy(() -> provider.withdraw(PLAYER, amount, () -> started.add(fraction))).as(fraction)
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot reach PlayerPoints");
        }
        assertThat(started).isEmpty();
        assertThat(held).isEqualTo(10);
        verifyNoInteractions(api);

        assertThat(provider.deposit(PLAYER, new BigDecimal("2.00"))).isEqualTo(EconomyResult.ok());
        assertThat(provider.withdraw(PLAYER, new BigDecimal("3.0"))).isEqualTo(EconomyResult.ok());
        assertThat(provider.withdraw(PLAYER, new BigDecimal("1E+0"))).isEqualTo(EconomyResult.ok());
        assertThat(held).isEqualTo(8);
        assertThat(calls).containsExactly("give 2", "look", "take 3", "look", "take 1");
    }

    @Test
    void anAmountBeyondTheLargestIntIsRefusedAndADepositThatWouldPassItFailsWithNoPointsMoved() {
        EconomyProvider provider = provider(0);
        held = 10;

        assertThatThrownBy(() -> provider.deposit(PLAYER, new BigDecimal("2147483648")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot reach PlayerPoints exactly");
        assertThatThrownBy(() -> provider.withdraw(PLAYER, new BigDecimal("1E+12")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot reach PlayerPoints exactly");
        verifyNoInteractions(api);

        assertThat(provider.deposit(PLAYER, BigDecimal.valueOf(Integer.MAX_VALUE)))
            .isEqualTo(EconomyResult.failed("PlayerPoints refused it"));
        assertThat(held).isEqualTo(10);

        held = 0;
        assertThat(provider.deposit(PLAYER, BigDecimal.valueOf(Integer.MAX_VALUE))).isEqualTo(EconomyResult.ok());
        assertThat(held).isEqualTo(Integer.MAX_VALUE);
        assertThat(provider.withdraw(PLAYER, BigDecimal.valueOf(Integer.MAX_VALUE))).isEqualTo(EconomyResult.ok());
        assertThat(held).isZero();
    }

    @Test
    @Override
    void hasComparesTheBalanceWithTheRoundedAmountAndAcceptsAnyAmount() {
        EconomyProvider provider = provider(0);
        held = 10;

        assertThat(provider.has(PLAYER, BigDecimal.TEN)).isTrue();
        assertThat(provider.has(PLAYER, new BigDecimal("10.4"))).isTrue();
        assertThat(provider.has(PLAYER, new BigDecimal("10.5"))).isTrue();
        assertThat(provider.has(PLAYER, new BigDecimal("10.6"))).isFalse();
        assertThat(provider.has(PLAYER, new BigDecimal("11"))).isFalse();
        assertThat(provider.has(PLAYER, new BigDecimal("0.4"))).isTrue();
        assertThat(provider.has(PLAYER, new BigDecimal("-5"))).isTrue();
        assertThat(provider.has(PLAYER, new BigDecimal("1E+12"))).isFalse();
        assertThat(held).isEqualTo(10);
        verify(api, never()).take(eq(PLAYER), anyInt());
        verify(api, never()).give(eq(PLAYER), anyInt());
    }

    @Test
    @Override
    void aWithdrawTakesTheAmountWhenTheBalanceCoversItAndRefusesWithInsufficientWhenItDoesNot() {
        EconomyProvider provider = provider(0);
        held = 10;
        List<String> started = new ArrayList<>();

        assertThat(provider.withdraw(PLAYER, new BigDecimal("11"), () -> started.add("short")))
            .isEqualTo(EconomyResult.of(EconomyResult.Status.INSUFFICIENT));
        assertThat(started).isEmpty();
        assertThat(held).isEqualTo(10);
        verify(api, never()).take(eq(PLAYER), anyInt());

        assertThat(provider.withdraw(PLAYER, new BigDecimal("4"), () -> started.add("covered")))
            .isEqualTo(EconomyResult.ok());
        assertThat(started).containsExactly("covered");
        assertThat(held).isEqualTo(6);

        assertThat(provider.withdraw(PLAYER, new BigDecimal("6"))).isEqualTo(EconomyResult.ok());
        assertThat(held).isZero();
        assertThat(provider.withdraw(PLAYER, BigDecimal.ONE).status()).isEqualTo(EconomyResult.Status.INSUFFICIENT);
    }

    @Test
    @Override
    void aDepositGivesTheAmountAndARefusedOneMovesNoMoney() {
        EconomyProvider provider = provider(0);
        held = 10;

        assertThat(provider.deposit(PLAYER, new BigDecimal("3"))).isEqualTo(EconomyResult.ok());
        assertThat(held).isEqualTo(13);

        refuse = true;
        assertThat(provider.deposit(PLAYER, BigDecimal.ONE)).isEqualTo(EconomyResult.failed("PlayerPoints refused it"));
        assertThat(held).isEqualTo(13);
    }

    @Test
    @Override
    void movedIsTrueForEveryStatusWhoseMoneyLeftOrReachedTheAccountAndFalseForTheRest() {
        EconomyProvider provider = provider(0);
        for (EconomyResult.Status status : List.of(EconomyResult.Status.OK, EconomyResult.Status.INSUFFICIENT,
            EconomyResult.Status.FAILED, EconomyResult.Status.NOT_STARTED)) {
            held = 10;
            EconomyResult result = switch (status) {
                case OK -> provider.withdraw(PLAYER, BigDecimal.ONE);
                case INSUFFICIENT -> provider.withdraw(PLAYER, new BigDecimal("11"));
                case FAILED -> {
                    refuse = true;
                    yield provider.withdraw(PLAYER, BigDecimal.ONE);
                }
                case NOT_STARTED -> provider.withdraw(PLAYER, BigDecimal.ONE, () -> false);
                default -> throw new AssertionError(status);
            };

            assertThat(result.status()).isEqualTo(status);
            assertThat(result.moved()).as(status.name()).isEqualTo(held != 10);
            assertThat(held != 10).as(status.name()).isEqualTo(status == EconomyResult.Status.OK);
        }
    }

    @Test
    @Override
    void theScaleTheThreadAndTheProbeAnswerOffTheThreadTheProviderNames() {
        EconomyProvider provider = provider(0);

        when(server.isPrimaryThread()).thenReturn(true);

        assertThat(provider.scale()).isZero();
        assertThat(provider.thread(PLAYER)).isSameAs(ThreadTag.ASYNC);
        assertThat(provider.thread(UUID.randomUUID())).isSameAs(ThreadTag.ASYNC);
        assertThat(provider.probe()).isEqualTo("PlayerPoints 3.3.5, whole points");
        verifyNoInteractions(api);
    }

    @Test
    void aCallOnATickThreadNamesTheAsyncThreadAndTheCallingThread() {
        when(server.isPrimaryThread()).thenReturn(true);

        assertThatThrownBy(() -> provider(0).balance(PLAYER)).isInstanceOf(IllegalStateException.class)
            .hasMessage("an economy call for " + PLAYER + " runs on the thread thread(player) names, ASYNC, not on "
                + Thread.currentThread().getName() + "; run it through Scheduler.dispatch(economy.thread(player),"
                + " ...)");
        assertThatThrownBy(() -> provider(0).deposit(PLAYER, new BigDecimal("0.5")))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(api, plugin);
    }

    @Test
    void aWithdrawThatPlayerPointsRefusesAfterTheBalanceCoveredItFailsAndNoPointsMove() {
        held = 10;
        refuse = true;
        List<String> started = new ArrayList<>();

        assertThat(provider(0).withdraw(PLAYER, BigDecimal.TEN, () -> started.add("start")))
            .isEqualTo(EconomyResult.failed("PlayerPoints refused it"));

        assertThat(started).containsExactly("start");
        assertThat(held).isEqualTo(10);
        assertThat(calls).containsExactly("look", "take 10");
    }

    @Test
    void aBalanceThatCannotBeReadFailsTheWithdrawBeforeItStarts() {
        doThrow(new IllegalStateException("the database is closed")).when(api).look(PLAYER);
        List<String> started = new ArrayList<>();

        assertThat(provider(0).withdraw(PLAYER, BigDecimal.ONE, () -> started.add("start")))
            .isEqualTo(EconomyResult.failed("the balance could not be read: the database is closed"));

        assertThat(started).isEmpty();
        verify(api, never()).take(eq(PLAYER), anyInt());
    }

    @Test
    void whilePlayerPointsIsDisabledAWithdrawAndADepositFailBeforeTheAmountIsCheckedAndTheProbeThrows() {
        EconomyProvider provider = provider(0);
        held = 10;
        when(plugin.isEnabled()).thenReturn(false);

        assertThat(provider.withdraw(PLAYER, new BigDecimal("0.5")))
            .isEqualTo(EconomyResult.failed("the economy plugin PlayerPoints is disabled"));
        assertThat(provider.deposit(PLAYER, new BigDecimal("-1")))
            .isEqualTo(EconomyResult.failed("the economy plugin PlayerPoints is disabled"));
        assertThatThrownBy(provider::probe).isInstanceOf(IllegalStateException.class)
            .hasMessage("PlayerPoints is disabled");
        verifyNoInteractions(api);
        assertThat(held).isEqualTo(10);
    }

    @Test
    void twoWithdrawsForOnePlayerRunOneAfterTheOtherFromTheBalanceReadToTheTake() throws Exception {
        EconomyProvider provider = provider(0);
        held = 10;
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch secondRead = new CountDownLatch(1);
        doAnswer(call -> {
            int read = look();
            if (started.getCount() == 0) {
                secondRead.countDown();
            }
            return read;
        }).when(api).look(PLAYER);

        CompletableFuture<EconomyResult> first = CompletableFuture.supplyAsync(() ->
            provider.withdraw(PLAYER, BigDecimal.TEN, () -> {
                started.countDown();
                try {
                    return !secondRead.await(300, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    throw new IllegalStateException(interrupted);
                }
            }));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<EconomyResult> second =
            CompletableFuture.supplyAsync(() -> provider.withdraw(PLAYER, BigDecimal.TEN));

        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(EconomyResult.ok());
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(EconomyResult.of(EconomyResult.Status.INSUFFICIENT));
        assertThat(held).isZero();
        assertThat(calls).containsExactly("look", "take 10", "look");
    }

    private Plugin owner(PluginManager plugins, CapturedLog log) {
        Plugin owner = mock(Plugin.class);
        when(owner.getServer()).thenReturn(server);
        when(owner.getLogger()).thenReturn(log.logger);
        when(server.getPluginManager()).thenReturn(plugins);
        return owner;
    }

    private static PluginManager installed(Plugin playerPoints) {
        PluginManager plugins = mock(PluginManager.class);
        when(plugins.getPlugin("PlayerPoints")).thenReturn(playerPoints);
        when(plugins.isPluginEnabled("PlayerPoints")).thenReturn(true);
        return plugins;
    }

    @Test
    void settingABalanceStoresWholePointsThroughSetAndRefusesAFractionAndAnAmountBeyondTheLargestInt() {
        EconomyProvider provider = provider(0);
        held = 10;

        assertThat(provider.setBalance(PLAYER, new BigDecimal("25.00"))).isEqualTo(EconomyResult.ok());
        assertThat(provider.setBalance(PLAYER, new BigDecimal("0.0"))).isEqualTo(EconomyResult.ok());
        assertThat(provider.setBalance(PLAYER, BigDecimal.valueOf(Integer.MAX_VALUE))).isEqualTo(EconomyResult.ok());
        assertThat(held).isEqualTo(Integer.MAX_VALUE);
        assertThat(calls).containsExactly("set 25", "set 0", "set 2147483647");

        for (String refused : List.of("2.5", "-1", "2147483648")) {
            assertThatThrownBy(() -> provider.setBalance(PLAYER, new BigDecimal(refused))).as(refused)
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(calls).hasSize(3);
        assertThat(held).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void aSetThatPlayerPointsRefusesFailsAndOneWhilePlayerPointsIsDisabledFailsBeforeTheAmountIsChecked() {
        EconomyProvider provider = provider(0);
        held = 10;
        refuse = true;

        assertThat(provider.setBalance(PLAYER, BigDecimal.ONE))
            .isEqualTo(EconomyResult.failed("PlayerPoints refused it"));
        assertThat(held).isEqualTo(10);

        when(plugin.isEnabled()).thenReturn(false);
        assertThat(provider.setBalance(PLAYER, new BigDecimal("-1")))
            .isEqualTo(EconomyResult.failed("the economy plugin PlayerPoints is disabled"));
        assertThat(calls).containsExactly("set 1");
        assertThat(held).isEqualTo(10);
    }

    @Test
    void aPlayerPointsWithoutSetAnswersUnsupportedAndOneWithoutTheNameMethodsHasNoNames() {
        EconomyProvider older = new PlayerPointsEconomy(server, plugin, api, log.logger, false, false);
        held = 10;

        assertThat(older.setBalance(PLAYER, new BigDecimal("2.5")))
            .isEqualTo(EconomyResult.of(EconomyResult.Status.UNSUPPORTED));
        assertThat(older.currencyNameSingular()).isNull();
        assertThat(older.currencyNamePlural()).isNull();
        assertThat(held).isEqualTo(10);
        verifyNoInteractions(api);
        assertThat(older.deposit(PLAYER, BigDecimal.ONE)).isEqualTo(EconomyResult.ok());
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void theCurrencyNamesComeFromPlayerPointsAndThereIsNoSymbolAndNoFormattedAmount() {
        EconomyProvider provider = provider(0);
        when(server.isPrimaryThread()).thenReturn(true);

        assertThat(provider.currencyNameSingular()).isEqualTo("Point");
        assertThat(provider.currencyNamePlural()).isEqualTo("Points");
        assertThat(provider.currencySymbol()).isNull();
        assertThat(provider.format(BigDecimal.TEN)).isNull();

        when(api.getCurrencyNameSingular()).thenReturn(" ");
        when(api.getCurrencyNamePlural()).thenThrow(new IllegalStateException("no locale"));
        assertThat(provider.currencyNameSingular()).isNull();
        assertThat(provider.currencyNamePlural()).isNull();
        assertThat(provider.currencyNamePlural()).isNull();
        assertThat(log.lines()).containsExactly("WARNING PlayerPoints threw in getCurrencyNamePlural, so it is read as"
            + " having none (java.lang.IllegalStateException: no locale).");
    }

    @Test
    void aReceiptOfThePlayerPointsEconomyHasNoTransactionId() {
        EconomyProvider provider = provider(0);
        held = 10;

        assertThat(provider.withdrawReceipt(PLAYER, new BigDecimal("4"), () -> true))
            .isEqualTo(new EconomyReceipt(EconomyResult.ok(), null));
        assertThat(provider.depositReceipt(PLAYER, BigDecimal.ONE))
            .isEqualTo(new EconomyReceipt(EconomyResult.ok(), null));
        assertThat(held).isEqualTo(7);
    }

    // The PlayerPoints class names Treasury types that are not on the test classpath, so no instance of it is built
    // here: the factory below stands for the one of the entry point.
    @Test
    void aDetectedProviderIsBoundToThePlayerPointsPluginAndDetectionMovesNoPoints() {
        PluginManager plugins = installed(plugin);
        CapturedLog log = new CapturedLog();
        held = 7;

        Detected<EconomyProvider> found = Detection.run("PlayerPoints", PlayerPointsEconomy.API, true,
            new PluginPresence(plugins, log.logger),
            bound -> new PlayerPointsEconomy(server, bound, api, log.logger, true, true), log.logger,
            new ErrorReports());

        assertThat(found.status()).isEqualTo(new HookStatus("PlayerPoints", HookState.ON, "3.3.5", null));
        assertThat(found.provider()).isInstanceOf(PlayerPointsEconomy.class);
        assertThat(found.bound()).isSameAs(plugin);
        verifyNoInteractions(api);
        assertThat(found.provider().balance(PLAYER)).isEqualTo(new BigDecimal("7"));
        assertThat(log.lines()).isEmpty();

        when(plugin.isEnabled()).thenReturn(false);

        assertThat(found.status()).isEqualTo(new HookStatus("PlayerPoints", HookState.NOT_ENABLED, null, null));
        assertThat(found.provider().deposit(PLAYER, BigDecimal.ONE))
            .isEqualTo(EconomyResult.failed("the economy plugin PlayerPoints is disabled"));
        assertThat(held).isEqualTo(7);
    }

    // The cast is reached only once every method the provider calls was found in the PlayerPoints API.
    @Test
    void aPluginNamedPlayerPointsThatIsNotPlayerPointsIsFailedAndReportedUnderHookPlayerpoints() {
        PluginManager plugins = installed(plugin);
        CapturedLog log = new CapturedLog();
        List<String> reported = new ArrayList<>();
        ErrorReports errors = new ErrorReports();
        errors.install((source, error) -> reported.add(source + " " + error.getClass().getSimpleName()));

        Detected<EconomyProvider> found = EconomyProvider.playerPoints(owner(plugins, log), errors, true);

        assertThat(found.status()).isEqualTo(new HookStatus("PlayerPoints", HookState.FAILED, "3.3.5", null));
        assertThat(found.provider()).isNull();
        assertThat(reported).containsExactly("hook.playerpoints ClassCastException");
        assertThat(log.lines()).hasSize(1).first().asString()
            .startsWith("WARNING PlayerPoints 3.3.5 could not be hooked (java.lang.ClassCastException");
    }

    @Test
    void aMissingOrSwitchedOffPlayerPointsGivesItsStateAndNoProvider() {
        PluginManager none = mock(PluginManager.class);
        CapturedLog log = new CapturedLog();
        ErrorReports errors = new ErrorReports();

        assertThat(EconomyProvider.playerPoints(owner(none, log), errors, true).status())
            .isEqualTo(new HookStatus("PlayerPoints", HookState.NOT_INSTALLED, null, null));

        PluginManager untouched = mock(PluginManager.class);
        Detected<EconomyProvider> off = EconomyProvider.playerPoints(owner(untouched, log), errors, false);

        assertThat(off.status()).isEqualTo(new HookStatus("PlayerPoints", HookState.SWITCHED_OFF, null, null));
        assertThat(off.provider()).isNull();
        verifyNoInteractions(untouched);
        assertThat(log.lines()).isEmpty();
        assertThatThrownBy(() -> EconomyProvider.playerPoints(null, errors, true))
            .isInstanceOf(NullPointerException.class).hasMessage("owner");
        assertThatThrownBy(() -> EconomyProvider.playerPoints(owner(none, log), null, true))
            .isInstanceOf(NullPointerException.class).hasMessage("errors");
    }
}
