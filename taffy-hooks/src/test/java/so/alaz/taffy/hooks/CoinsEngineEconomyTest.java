package so.alaz.taffy.hooks;

import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.platform.ThreadTag;
import so.alaz.taffy.test.CapturedLog;
import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.api.currency.operation.OperationResult;
import su.nightexpress.excellenteconomy.api.event.ChangeBalanceEvent;
import su.nightexpress.excellenteconomy.user.CoinsUser;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// ExcellentEconomy answers no call as unsupported, takes no fee and gives one message for a refusal, so the contract
// tests that need those are replaced here.
final class CoinsEngineEconomyTest extends EconomyProviderContract {

    private static final String GEMS = "gems";

    private final Server server = mock(Server.class);
    private final ServicesManager services = mock(ServicesManager.class);
    private final PluginManager plugins = mock(PluginManager.class);
    private final Plugin owner = mock(Plugin.class);
    private final Plugin plugin = mock(Plugin.class);
    private final PluginMeta meta = mock(PluginMeta.class);
    private final ExcellentEconomyAPI api = mock(ExcellentEconomyAPI.class);
    private final ExcellentCurrency gems = mock(ExcellentCurrency.class);
    private final CapturedLog log = new CapturedLog();
    private final List<String> reported = new ArrayList<>();
    private final ErrorReports errors = new ErrorReports();
    private final List<String> calls = new ArrayList<>();
    private final List<CoinsEngineEconomy> providers = new ArrayList<>();
    private double held;
    private boolean refuse;
    private boolean cancel;
    private boolean silent;
    private Runnable inside;

    @BeforeEach
    void excellentEconomy() {
        when(server.isPrimaryThread()).thenReturn(false);
        when(server.getServicesManager()).thenReturn(services);
        when(server.getPluginManager()).thenReturn(plugins);
        when(owner.isEnabled()).thenReturn(true);
        when(owner.getName()).thenReturn("Shop");
        when(owner.getServer()).thenReturn(server);
        when(owner.getLogger()).thenReturn(log.logger);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getPluginMeta()).thenReturn(meta);
        when(meta.getVersion()).thenReturn("2.8.0");
        errors.install((source, error) -> reported.add(source + " " + error.getMessage()));

        when(api.hasCurrency(GEMS)).thenReturn(true);
        when(api.getCurrency(GEMS)).thenReturn(gems);
        when(gems.getId()).thenReturn(GEMS);
        when(gems.getName()).thenReturn("Gems");
        when(gems.getSymbol()).thenReturn("G");
        when(gems.isDecimal()).thenReturn(true);
        when(gems.isUnderLimit(anyDouble())).thenReturn(true);
        when(gems.format(anyDouble())).thenAnswer(call -> "G" + call.getArgument(0, Double.class));
        when(api.getBalanceAsync(PLAYER, GEMS)).thenAnswer(call -> {
            calls.add("balance");
            return CompletableFuture.completedFuture(held);
        });
        when(api.withdrawAsync(eq(PLAYER), eq(GEMS), anyDouble()))
            .thenAnswer(call -> change("withdraw", call.getArgument(2, Double.class),
                Math.max(0, held - call.getArgument(2, Double.class))));
        when(api.depositAsync(eq(PLAYER), eq(GEMS), anyDouble()))
            .thenAnswer(call -> change("deposit", call.getArgument(2, Double.class),
                held + call.getArgument(2, Double.class)));
        when(api.setBalanceAsync(eq(PLAYER), eq(GEMS), anyDouble()))
            .thenAnswer(call -> change("set", call.getArgument(2, Double.class), call.getArgument(2, Double.class)));
    }

    // As ExcellentEconomy does, fires the event of a change before it answers, and answers SUCCESS for a change a
    // listener cancelled, which leaves the balance as it was. inside runs before the answer, as a change another
    // plugin makes while the operation runs does.
    private CompletableFuture<OperationResult> change(String call, double amount, double result) {
        calls.add(call + " " + amount);
        if (refuse) {
            refuse = false;
            return CompletableFuture.completedFuture(OperationResult.FAILURE);
        }
        double before = held;
        boolean cancelled = cancel;
        cancel = false;
        if (!cancelled) {
            held = result;
        }
        if (!silent) {
            fire(PLAYER, gems, before, result, cancelled);
        }
        Runnable then = inside;
        inside = null;
        if (then != null) {
            then.run();
        }
        return CompletableFuture.completedFuture(OperationResult.SUCCESS);
    }

    private void fire(UUID player, ExcellentCurrency currency, double before, double after, boolean cancelled) {
        CoinsUser user = mock(CoinsUser.class);
        when(user.getId()).thenReturn(player);
        ChangeBalanceEvent event = mock(ChangeBalanceEvent.class);
        when(event.getUser()).thenReturn(user);
        when(event.getCurrency()).thenReturn(currency);
        when(event.getOldAmount()).thenReturn(before);
        when(event.getNewAmount()).thenReturn(after);
        when(event.isCancelled()).thenReturn(cancelled);
        for (CoinsEngineEconomy each : List.copyOf(providers)) {
            each.onChange(event);
        }
    }

    // A change another plugin makes to the player's gems.
    private void foreign(double change) {
        double before = held;
        held = Math.max(0, held + change);
        fire(PLAYER, gems, before, held, false);
    }

    @Override
    EconomyProvider provider(int scale) {
        CoinsEngineEconomy provider = new CoinsEngineEconomy(server, owner, plugin, api, GEMS, scale, log.logger);
        providers.add(provider);
        return provider;
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
                held = Double.parseDouble(balance);
            }

            @Override
            public BigDecimal held() {
                return DoubleMoney.amount(held, EconomyProvider.MAX_SCALE, held);
            }

            @Override
            public void refuseNext(String message) {
                refuse = true;
            }

            @Override
            public void unsupportedNext() {
                throw new UnsupportedOperationException("ExcellentEconomy answers no call as unsupported");
            }

            @Override
            public void feeNext(String fee) {
                throw new UnsupportedOperationException("ExcellentEconomy takes no fee");
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
        held = 12.345;
        assertThat(provider(2).balance(PLAYER)).isEqualTo(new BigDecimal("12.34"));
        assertThat(provider(0).balance(PLAYER)).isEqualTo(new BigDecimal("12"));

        held = 12.355;
        assertThat(provider(2).balance(PLAYER)).isEqualTo(new BigDecimal("12.36"));
        held = 1.0 - 0.9;
        assertThat(provider(2).balance(PLAYER)).isEqualTo(new BigDecimal("0.10"));
        assertThat(provider(2).has(PLAYER, new BigDecimal("0.10"))).isTrue();
        assertThat(provider(2).scale()).isEqualTo(2);
    }

    @Test
    @Override
    void aDepositGivesTheAmountAndARefusedOneMovesNoMoney() {
        EconomyProvider provider = provider(2);
        held = 10;

        assertThat(provider.deposit(PLAYER, new BigDecimal("2.50"))).isEqualTo(EconomyResult.ok());
        assertThat(held).isEqualTo(12.5);

        refuse = true;
        assertThat(provider.deposit(PLAYER, BigDecimal.ONE))
            .isEqualTo(EconomyResult.failed("ExcellentEconomy refused it"));
        assertThat(held).isEqualTo(12.5);
        assertThat(calls).containsExactly("deposit 2.5", "deposit 1.0");
    }

    @Test
    @Override
    void movedIsTrueForEveryStatusWhoseMoneyLeftOrReachedTheAccountAndFalseForTheRest() {
        EconomyProvider provider = provider(2);
        for (EconomyResult.Status status : List.of(EconomyResult.Status.OK, EconomyResult.Status.INSUFFICIENT,
            EconomyResult.Status.FAILED, EconomyResult.Status.NOT_STARTED)) {
            held = 10;
            EconomyResult result = switch (status) {
                case OK -> provider.withdraw(PLAYER, BigDecimal.ONE);
                case INSUFFICIENT -> provider.withdraw(PLAYER, new BigDecimal("10.01"));
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
    void aChangeAListenerCancelledFailsThoughExcellentEconomyAnsweredSuccess() {
        EconomyProvider provider = provider(2);
        held = 10;
        EconomyResult unchanged = EconomyResult.failed("ExcellentEconomy left the balance unchanged");
        List<String> started = new ArrayList<>();

        cancel = true;
        assertThat(provider.withdraw(PLAYER, new BigDecimal("4"), () -> started.add("start"))).isEqualTo(unchanged);
        cancel = true;
        assertThat(provider.deposit(PLAYER, new BigDecimal("4"))).isEqualTo(unchanged);
        cancel = true;
        assertThat(provider.setBalance(PLAYER, new BigDecimal("4"))).isEqualTo(unchanged);

        assertThat(started).containsExactly("start");
        assertThat(held).isEqualTo(10);
        cancel = true;
        assertThat(provider.setBalance(PLAYER, BigDecimal.TEN)).isEqualTo(EconomyResult.ok());
        assertThat(provider.withdrawReceipt(PLAYER, new BigDecimal("4"), () -> true))
            .isEqualTo(new EconomyReceipt(EconomyResult.ok(), null));
        assertThat(held).isEqualTo(6);
    }

    @Test
    void aBalanceThatCannotBeReadFailsAWithdrawBeforeItStartsAndADepositAndABalanceSetReadNone() {
        EconomyProvider provider = provider(2);
        held = 10;
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("the database is closed")))
            .when(api).getBalanceAsync(PLAYER, GEMS);
        List<String> started = new ArrayList<>();

        assertThat(provider.withdraw(PLAYER, BigDecimal.ONE, () -> started.add("start")).status())
            .isEqualTo(EconomyResult.Status.FAILED);
        assertThat(started).isEmpty();
        assertThat(calls).isEmpty();

        assertThat(provider.deposit(PLAYER, BigDecimal.ONE)).isEqualTo(EconomyResult.ok());
        assertThat(provider.setBalance(PLAYER, new BigDecimal("3"))).isEqualTo(EconomyResult.ok());
        assertThat(held).isEqualTo(3);
        assertThat(calls).containsExactly("deposit 1.0", "set 3.0");
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aWithdrawFromABalanceAnotherPluginLoweredSinceTheCheckGivesBackWhatItTookAndIsInsufficient() {
        EconomyProvider provider = provider(2);
        held = 100;

        EconomyResult part = provider.withdraw(PLAYER, new BigDecimal("100"), () -> {
            foreign(-60);
            return true;
        });

        assertThat(part).isEqualTo(EconomyResult.of(EconomyResult.Status.INSUFFICIENT));
        assertThat(part.moved()).isFalse();
        assertThat(held).isEqualTo(40);
        assertThat(calls).containsExactly("balance", "withdraw 100.0", "deposit 40.0");

        calls.clear();
        EconomyResult none = provider.withdraw(PLAYER, new BigDecimal("40"), () -> {
            foreign(-40);
            return true;
        });

        assertThat(none).isEqualTo(EconomyResult.of(EconomyResult.Status.INSUFFICIENT));
        assertThat(held).isZero();
        assertThat(calls).containsExactly("balance", "withdraw 40.0");
    }

    @Test
    void aWithdrawThatCannotGiveBackWhatItTookOfAShortBalanceThrows() {
        EconomyProvider provider = provider(2);
        held = 100;
        inside = () -> refuse = true;

        assertThatThrownBy(() -> provider.withdraw(PLAYER, new BigDecimal("100"), () -> {
            foreign(-60);
            return true;
        })).isInstanceOf(IllegalStateException.class).hasMessage("ExcellentEconomy took 40 of 100.00 from a balance"
            + " that no longer covered it, and did not give it back");
        assertThat(held).isZero();
    }

    @Test
    void aMoveIsReadFromItsOwnChangeWhateverAnotherPluginDoesToTheBalanceWhileItRuns() {
        EconomyProvider provider = provider(2);
        ExcellentCurrency coins = mock(ExcellentCurrency.class);
        when(coins.getId()).thenReturn("coins");
        held = 100;

        inside = () -> foreign(-10);
        assertThat(provider.deposit(PLAYER, BigDecimal.TEN)).isEqualTo(EconomyResult.ok());
        assertThat(held).isEqualTo(100);

        inside = () -> foreign(10);
        assertThat(provider.withdraw(PLAYER, BigDecimal.TEN)).isEqualTo(EconomyResult.ok());
        assertThat(held).isEqualTo(100);

        inside = () -> foreign(5);
        assertThat(provider.setBalance(PLAYER, new BigDecimal("50"))).isEqualTo(EconomyResult.ok());
        assertThat(held).isEqualTo(55);

        inside = () -> {
            fire(UUID.randomUUID(), gems, 55, 45, true);
            fire(PLAYER, coins, 55, 45, true);
        };
        assertThat(provider.withdraw(PLAYER, BigDecimal.TEN)).isEqualTo(EconomyResult.ok());
        assertThat(held).isEqualTo(45);
    }

    @Test
    void aMoveThrowsWhenNoChangeThatFitsItWasHeardOrTwoThatFitItDiffer() {
        EconomyProvider provider = provider(2);
        held = 100;

        inside = () -> fire(PLAYER, gems, 90, 80, true);
        assertThatThrownBy(() -> provider.withdraw(PLAYER, BigDecimal.TEN))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("ExcellentEconomy answered the withdraw with a success, and two balance changes that fit it"
                + " were heard with different outcomes, so what it did is unknown");

        silent = true;
        assertThatThrownBy(() -> provider.deposit(PLAYER, BigDecimal.ONE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("ExcellentEconomy answered the deposit with a success, and no balance change that fits it"
                + " was heard, so what it did is unknown");
        assertThatThrownBy(() -> provider.setBalance(PLAYER, BigDecimal.ONE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageStartingWith("ExcellentEconomy answered the balance set with a success, and no balance");
    }

    @Test
    void theListenerIsRegisteredForTheOwnerOnTheFirstMoveAndAMoveFailsWhileItCannotBe() {
        EconomyProvider provider = provider(2);
        held = 10;
        when(owner.isEnabled()).thenReturn(false);
        EconomyResult deaf = EconomyResult.failed("the balance changes of ExcellentEconomy cannot be heard while Shop"
            + " is not enabled");

        assertThat(provider.balance(PLAYER)).isEqualTo(new BigDecimal("10.00"));
        assertThat(provider.withdraw(PLAYER, BigDecimal.ONE)).isEqualTo(deaf);
        assertThat(provider.deposit(PLAYER, BigDecimal.ONE)).isEqualTo(deaf);
        assertThat(provider.setBalance(PLAYER, BigDecimal.ONE)).isEqualTo(deaf);
        assertThat(calls).containsExactly("balance");
        verifyNoInteractions(plugins);

        when(owner.isEnabled()).thenReturn(true);
        assertThat(provider.deposit(PLAYER, BigDecimal.ONE)).isEqualTo(EconomyResult.ok());
        when(owner.isEnabled()).thenReturn(false);
        assertThat(provider.withdraw(PLAYER, BigDecimal.ONE)).isEqualTo(EconomyResult.ok());

        verify(plugins).registerEvents((CoinsEngineEconomy) provider, owner);
        assertThat(held).isEqualTo(10);
    }

    @Test
    void anAmountADoubleCannotCarryExactlyIsRefusedAndWholeAmountsAreExactUpToTwoToThePowerOfFiftyThree() {
        EconomyProvider whole = provider(0);
        held = 0;

        assertThat(whole.deposit(PLAYER, new BigDecimal("9007199254740992"))).isEqualTo(EconomyResult.ok());
        assertThat(whole.balance(PLAYER)).isEqualTo(new BigDecimal("9007199254740992"));
        assertThatThrownBy(() -> whole.deposit(PLAYER, new BigDecimal("9007199254740993")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("9007199254740993 cannot reach the economy exactly as a double");
        assertThatThrownBy(() -> provider(2).withdraw(PLAYER, new BigDecimal("12345678901234567.89")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider(2).setBalance(PLAYER, new BigDecimal("12345678901234567.89")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider(2).format(new BigDecimal("1E+400")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("1E+400 is beyond the range of a double");
        assertThat(calls).containsExactly("deposit 9.007199254740992E15", "balance");
    }

    @Test
    void aBalanceSetAboveTheLimitOfTheCurrencyFailsAndNothingIsCalled() {
        EconomyProvider provider = provider(2);
        held = 10;
        when(gems.isUnderLimit(500.0)).thenReturn(false);

        assertThat(provider.setBalance(PLAYER, new BigDecimal("500")))
            .isEqualTo(EconomyResult.failed("500.00 is above the limit ExcellentEconomy sets for gems"));
        assertThat(calls).isEmpty();
        assertThat(held).isEqualTo(10);
    }

    @Test
    void aMoveForACurrencyExcellentEconomyNoLongerHasOrWhileItIsDisabledFailsBeforeAnythingMoves() {
        EconomyProvider provider = provider(2);
        held = 10;
        when(api.hasCurrency(GEMS)).thenReturn(false);
        EconomyResult gone = EconomyResult.failed("ExcellentEconomy has no currency named gems");

        assertThat(provider.withdraw(PLAYER, BigDecimal.ONE)).isEqualTo(gone);
        assertThat(provider.deposit(PLAYER, BigDecimal.ONE)).isEqualTo(gone);
        assertThat(provider.setBalance(PLAYER, BigDecimal.ONE)).isEqualTo(gone);

        when(plugin.isEnabled()).thenReturn(false);
        EconomyResult disabled = EconomyResult.failed("the economy plugin ExcellentEconomy is disabled");
        assertThat(provider.withdraw(PLAYER, new BigDecimal("-1"))).isEqualTo(disabled);
        assertThat(provider.deposit(PLAYER, BigDecimal.ZERO)).isEqualTo(disabled);
        assertThat(provider.setBalance(PLAYER, new BigDecimal("-1"))).isEqualTo(disabled);
        assertThatThrownBy(provider::probe).isInstanceOf(IllegalStateException.class)
            .hasMessage("ExcellentEconomy is disabled");
        assertThat(calls).isEmpty();
        assertThat(held).isEqualTo(10);
    }

    @Test
    void aCallOnATickThreadNamesTheAsyncThreadAndCallsNothing() {
        when(server.isPrimaryThread()).thenReturn(true);

        assertThatThrownBy(() -> provider(2).balance(PLAYER)).isInstanceOf(IllegalStateException.class)
            .hasMessage("an economy call for " + PLAYER + " runs on the thread thread(player) names, ASYNC, not on "
                + Thread.currentThread().getName() + "; run it through Scheduler.dispatch(economy.thread(player),"
                + " ...)");
        assertThat(provider(2).thread(PLAYER)).isSameAs(ThreadTag.ASYNC);
        assertThat(provider(2).thread(UUID.randomUUID())).isSameAs(ThreadTag.ASYNC);
        assertThat(provider(2).probe()).isEqualTo("ExcellentEconomy 2.8.0, currency gems, 2 decimal places");
        verifyNoInteractions(api, gems);
    }

    @Test
    void theNameTheSymbolAndAFormattedAmountComeFromTheCurrency() {
        EconomyProvider provider = provider(2);
        when(server.isPrimaryThread()).thenReturn(true);

        assertThat(provider.currencyNameSingular()).isEqualTo("Gems");
        assertThat(provider.currencyNamePlural()).isEqualTo("Gems");
        assertThat(provider.currencySymbol()).isEqualTo("G");
        assertThat(provider.format(new BigDecimal("1250.505"))).isEqualTo("G1250.5");
        assertThat(provider.format(new BigDecimal("1250.515"))).isEqualTo("G1250.52");

        when(gems.getSymbol()).thenReturn(" ");
        when(gems.getName()).thenThrow(new IllegalStateException("no locale"));
        assertThat(provider.currencySymbol()).isNull();
        assertThat(provider.currencyNameSingular()).isNull();
        assertThat(provider.currencyNamePlural()).isNull();
        assertThat(log.lines()).containsExactly("WARNING ExcellentEconomy threw in getName, so it is read as having"
            + " none (java.lang.IllegalStateException: no locale).");

        when(api.getCurrency(GEMS)).thenReturn(null);
        assertThat(provider.currencySymbol()).isNull();
        assertThat(provider.format(BigDecimal.ONE)).isNull();
    }

    @Test
    void twoWithdrawsForOnePlayerRunOneAfterTheOtherFromTheBalanceReadToTheChange() throws Exception {
        EconomyProvider provider = provider(2);
        held = 10;
        CountDownLatch started = new CountDownLatch(1);

        CompletableFuture<EconomyResult> first = CompletableFuture.supplyAsync(() ->
            provider.withdraw(PLAYER, BigDecimal.TEN, () -> {
                started.countDown();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException interrupted) {
                    throw new IllegalStateException(interrupted);
                }
                return true;
            }));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<EconomyResult> second =
            CompletableFuture.supplyAsync(() -> provider.withdraw(PLAYER, BigDecimal.TEN));

        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(EconomyResult.ok());
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(EconomyResult.of(EconomyResult.Status.INSUFFICIENT));
        assertThat(held).isZero();
        assertThat(calls).containsExactly("balance", "withdraw 10.0", "balance");
    }

    private FakePresence installed() {
        FakePresence presence = new FakePresence();
        presence.missing.add(CoinsEngineLookup.OLD_PLUGIN);
        presence.plugins.put(CoinsEngineLookup.PLUGIN, plugin);
        when(services.load(ExcellentEconomyAPI.class)).thenReturn(api);
        return presence;
    }

    @Test
    void detectionFindsTheCurrencyReadsNoBalanceAndBindsTheProviderToThePlugin() {
        FakePresence presence = installed();
        when(gems.isDecimal()).thenReturn(false);
        held = 7;

        Detected<EconomyProvider> found = CoinsEngineLookup.detect(presence, owner, GEMS, errors);

        assertThat(found.status()).isEqualTo(new HookStatus("ExcellentEconomy", HookState.ON, "1.0", null));
        assertThat(found.provider()).isInstanceOf(CoinsEngineEconomy.class);
        assertThat(found.provider().scale()).isZero();
        assertThat(found.bound()).isSameAs(plugin);
        assertThat(calls).isEmpty();
        assertThat(presence.probes).containsExactly(CoinsEngineLookup.API, CoinsEngineLookup.NIGHTCORE_PROBE);
        assertThat(found.provider().balance(PLAYER)).isEqualTo(new BigDecimal("7"));

        when(plugin.isEnabled()).thenReturn(false);
        assertThat(found.status()).isEqualTo(new HookStatus("ExcellentEconomy", HookState.NOT_ENABLED, null, null));
        assertThat(log.lines()).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    void detectionForACurrencyExcellentEconomyLacksIsNotRegisteredAndNamesTheCurrenciesItHas() {
        FakePresence presence = installed();
        ExcellentCurrency coins = mock(ExcellentCurrency.class);
        when(coins.getId()).thenReturn("coins");
        Set<ExcellentCurrency> registered = new LinkedHashSet<>(List.of(coins, gems));
        when(api.getCurrencies()).thenReturn(registered);

        Detected<EconomyProvider> found = CoinsEngineLookup.detect(presence, owner, "tokens", errors);

        assertThat(found.provider()).isNull();
        assertThat(found.status()).isEqualTo(new HookStatus("ExcellentEconomy", HookState.NOT_REGISTERED, "1.0",
            "ExcellentEconomy has no currency named tokens. It has coins, gems."));
        assertThat(log.lines()).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    void detectionWithNoApiRegisteredIsFailedAndReportedUnderHookExcellenteconomy() {
        FakePresence presence = installed();
        when(services.load(ExcellentEconomyAPI.class)).thenReturn(null);

        Detected<EconomyProvider> found = CoinsEngineLookup.detect(presence, owner, GEMS, errors);

        assertThat(found.status()).isEqualTo(new HookStatus("ExcellentEconomy", HookState.FAILED, "1.0", null));
        assertThat(found.provider()).isNull();
        assertThat(reported).containsExactly("hook.excellenteconomy ExcellentEconomy registered no API");
        assertThat(log.lines()).hasSize(1).first().asString()
            .startsWith("WARNING ExcellentEconomy 1.0 could not be hooked (java.lang.NullPointerException");
    }

    @Test
    void detectionStopsWhereNightcoreIsNotVisibleAndNamesTheOldPluginNameWhereOnlyThatIsInstalled() {
        FakePresence hidden = installed();
        hidden.invisibleClasses.add(CoinsEngineLookup.NIGHTCORE_PROBE);

        Detected<EconomyProvider> unseen = CoinsEngineLookup.detect(hidden, owner, GEMS, errors);

        assertThat(unseen.status()).isEqualTo(new HookStatus("ExcellentEconomy", HookState.NOT_VISIBLE, "1.0",
            "ExcellentEconomy is installed, but this plugin cannot see nightcore, which ExcellentEconomy is read"
                + " through, because its paper-plugin.yml does not list nightcore under dependencies.server."));
        assertThat(log.lines()).hasSize(1);

        FakePresence old = new FakePresence();
        old.missing.add(CoinsEngineLookup.PLUGIN);
        assertThat(CoinsEngineLookup.detect(old, owner, GEMS, errors).status())
            .isEqualTo(new HookStatus("ExcellentEconomy", HookState.NOT_INSTALLED, null, "CoinsEngine is installed"
                + " under its old name, whose API is not read. Install ExcellentEconomy, the name CoinsEngine has"
                + " now."));
        old.missing.add(CoinsEngineLookup.OLD_PLUGIN);
        assertThat(CoinsEngineLookup.detect(old, owner, GEMS, errors).status())
            .isEqualTo(new HookStatus("ExcellentEconomy", HookState.NOT_INSTALLED, null, null));
        verifyNoInteractions(api, services);
        assertThat(reported).isEmpty();
    }

    @Test
    void aSwitchedOffDetectionLooksAtNothingAndABlankCurrencyIsRefused() {
        Plugin owner = mock(Plugin.class);

        Detected<EconomyProvider> off = EconomyProvider.coinsEngine(owner, errors, false, GEMS);

        assertThat(off.status()).isEqualTo(new HookStatus("ExcellentEconomy", HookState.SWITCHED_OFF, null, null));
        assertThat(off.provider()).isNull();
        verifyNoInteractions(owner, server);
        assertThatThrownBy(() -> EconomyProvider.coinsEngine(owner, errors, false, ""))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("a currency id is not blank");
        assertThatThrownBy(() -> EconomyProvider.coinsEngine(null, errors, true, GEMS))
            .isInstanceOf(NullPointerException.class).hasMessage("owner");
        assertThatThrownBy(() -> EconomyProvider.coinsEngine(owner, null, true, GEMS))
            .isInstanceOf(NullPointerException.class).hasMessage("errors");
        assertThatThrownBy(() -> EconomyProvider.coinsEngine(owner, errors, true, null))
            .isInstanceOf(NullPointerException.class).hasMessage("currency");
    }

    @Test
    void theCurrenciesAreListedWithTheirIdNameAndSymbol() {
        FakePresence presence = installed();
        ExcellentCurrency coins = mock(ExcellentCurrency.class);
        when(coins.getId()).thenReturn("coins");
        when(coins.getName()).thenReturn("Coins");
        when(coins.getSymbol()).thenReturn(" ");
        Set<ExcellentCurrency> registered = new LinkedHashSet<>(List.of(coins, gems));
        when(api.getCurrencies()).thenReturn(registered);

        List<EconomyCurrency> listed = CoinsEngineLookup.currencies(presence, server, log.logger, errors);

        assertThat(listed).containsExactly(new EconomyCurrency("coins", "Coins", null),
            new EconomyCurrency(GEMS, "Gems", "G"));
        assertThatThrownBy(() -> listed.add(new EconomyCurrency("x", null, null)))
            .isInstanceOf(UnsupportedOperationException.class);

        when(api.getCurrencies()).thenThrow(new IllegalStateException("registry closed"));
        assertThat(CoinsEngineLookup.currencies(presence, server, log.logger, errors)).isEmpty();
        assertThat(reported).containsExactly("hook.excellenteconomy registry closed");

        presence.disabled.add(CoinsEngineLookup.PLUGIN);
        assertThat(CoinsEngineLookup.currencies(presence, server, log.logger, errors)).isEmpty();
        assertThat(reported).hasSize(1);
    }
}
