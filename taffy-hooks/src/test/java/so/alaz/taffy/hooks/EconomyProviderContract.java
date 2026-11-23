package so.alaz.taffy.hooks;

import org.junit.jupiter.api.Test;
import so.alaz.taffy.platform.ThreadTag;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The rules every EconomyProvider keeps, run against the provider and the economy behind it that a subclass gives.
// A subclass starts each test on the thread thread(PLAYER) names.
abstract class EconomyProviderContract {

    static final UUID PLAYER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    // The economy behind the provider under test.
    interface Bank {

        void hold(String balance);

        // The balance of PLAYER as the economy holds it, not rounded to the provider's scale.
        BigDecimal held();

        // The next withdraw or deposit is refused with this message and moves no money.
        void refuseNext(String message);

        // The next withdraw or deposit is answered as a call the economy does not support and moves no money.
        void unsupportedNext();

        // The next withdraw takes this much on top of its amount.
        void feeNext(String fee);

        // Whether the calling thread is the one thread(PLAYER) names.
        void onThread(boolean on);
    }

    // A provider with that scale over bank(). Each call gives a provider over the same economy.
    abstract EconomyProvider provider(int scale);

    abstract Bank bank();

    @Test
    void aBalanceIsRoundedHalfEvenToTheScale() {
        bank().hold("12.345");
        assertThat(provider(2).balance(PLAYER)).isEqualTo(new BigDecimal("12.34"));
        assertThat(provider(0).balance(PLAYER)).isEqualTo(new BigDecimal("12"));
        assertThat(provider(4).balance(PLAYER)).isEqualTo(new BigDecimal("12.3450"));

        bank().hold("12.355");
        assertThat(provider(2).balance(PLAYER)).isEqualTo(new BigDecimal("12.36"));
        bank().hold("-0.5");
        assertThat(provider(2).balance(PLAYER)).isEqualTo(new BigDecimal("-0.50"));
        assertThat(provider(2).scale()).isEqualTo(2);
    }

    @Test
    void anAmountIsRoundedHalfEvenToTheScaleBeforeItMoves() {
        EconomyProvider provider = provider(2);
        bank().hold("10");

        assertThat(provider.deposit(PLAYER, new BigDecimal("2.345"))).isEqualTo(EconomyResult.ok());
        assertThat(bank().held()).isEqualByComparingTo("12.34");
        assertThat(provider.deposit(PLAYER, new BigDecimal("2.355"))).isEqualTo(EconomyResult.ok());
        assertThat(bank().held()).isEqualByComparingTo("14.70");
        assertThat(provider.withdraw(PLAYER, new BigDecimal("1.005"))).isEqualTo(EconomyResult.ok());
        assertThat(bank().held()).isEqualByComparingTo("13.70");
        assertThat(provider.withdraw(PLAYER, new BigDecimal("1.015"))).isEqualTo(EconomyResult.ok());
        assertThat(bank().held()).isEqualByComparingTo("12.68");
    }

    @Test
    void anAmountThatIsNegativeOrRoundsToZeroIsRefusedAndNoMoneyMoves() {
        EconomyProvider provider = provider(2);
        bank().hold("10");
        List<String> started = new ArrayList<>();

        for (String refused : List.of("-1", "-0.01", "0", "0.004", "0.005")) {
            BigDecimal amount = new BigDecimal(refused);
            assertThatThrownBy(() -> provider.withdraw(PLAYER, amount))
                .as(refused).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> provider.withdraw(PLAYER, amount, () -> started.add(refused)))
                .as(refused).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> provider.deposit(PLAYER, amount))
                .as(refused).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(started).isEmpty();
        assertThat(bank().held()).isEqualByComparingTo("10");
    }

    @Test
    void hasComparesTheBalanceWithTheRoundedAmountAndAcceptsAnyAmount() {
        EconomyProvider provider = provider(2);
        bank().hold("10");

        assertThat(provider.has(PLAYER, BigDecimal.TEN)).isTrue();
        assertThat(provider.has(PLAYER, new BigDecimal("10.004"))).isTrue();
        assertThat(provider.has(PLAYER, new BigDecimal("10.006"))).isFalse();
        assertThat(provider.has(PLAYER, new BigDecimal("10.01"))).isFalse();
        assertThat(provider.has(PLAYER, new BigDecimal("0.004"))).isTrue();
        assertThat(provider.has(PLAYER, new BigDecimal("-5"))).isTrue();
        assertThat(bank().held()).isEqualByComparingTo("10");
    }

    @Test
    void aWithdrawTakesTheAmountWhenTheBalanceCoversItAndRefusesWithInsufficientWhenItDoesNot() {
        EconomyProvider provider = provider(2);
        bank().hold("10");
        List<String> started = new ArrayList<>();

        assertThat(provider.withdraw(PLAYER, new BigDecimal("10.01"), () -> started.add("short")))
            .isEqualTo(EconomyResult.of(EconomyResult.Status.INSUFFICIENT));
        assertThat(started).isEmpty();
        assertThat(bank().held()).isEqualByComparingTo("10");

        assertThat(provider.withdraw(PLAYER, new BigDecimal("4"), () -> started.add("covered")))
            .isEqualTo(EconomyResult.ok());
        assertThat(started).containsExactly("covered");
        assertThat(bank().held()).isEqualByComparingTo("6");

        assertThat(provider.withdraw(PLAYER, new BigDecimal("6"))).isEqualTo(EconomyResult.ok());
        assertThat(bank().held()).isEqualByComparingTo("0");
        assertThat(provider.withdraw(PLAYER, new BigDecimal("0.01")).status())
            .isEqualTo(EconomyResult.Status.INSUFFICIENT);
    }

    @Test
    void aStartThatDeclinesGivesNotStartedAndNoMoneyMoves() {
        EconomyProvider provider = provider(2);
        bank().hold("10");

        assertThat(provider.withdraw(PLAYER, BigDecimal.ONE, () -> false))
            .isEqualTo(EconomyResult.of(EconomyResult.Status.NOT_STARTED));
        assertThat(bank().held()).isEqualByComparingTo("10");
        assertThat(provider.withdraw(PLAYER, BigDecimal.ONE).status()).isNotEqualTo(EconomyResult.Status.NOT_STARTED);
    }

    @Test
    void aDepositGivesTheAmountAndARefusedOneMovesNoMoney() {
        EconomyProvider provider = provider(2);
        bank().hold("10");

        assertThat(provider.deposit(PLAYER, new BigDecimal("2.50"))).isEqualTo(EconomyResult.ok());
        assertThat(bank().held()).isEqualByComparingTo("12.50");

        bank().refuseNext("Account is locked");
        assertThat(provider.deposit(PLAYER, BigDecimal.ONE)).isEqualTo(EconomyResult.failed("Account is locked"));
        bank().unsupportedNext();
        assertThat(provider.deposit(PLAYER, BigDecimal.ONE).status()).isEqualTo(EconomyResult.Status.UNSUPPORTED);
        assertThat(bank().held()).isEqualByComparingTo("12.50");
    }

    @Test
    void movedIsTrueForEveryStatusWhoseMoneyLeftOrReachedTheAccountAndFalseForTheRest() {
        EconomyProvider provider = provider(2);
        for (EconomyResult.Status status : EconomyResult.Status.values()) {
            bank().hold("10");
            EconomyResult result = switch (status) {
                case OK -> provider.withdraw(PLAYER, BigDecimal.ONE);
                case INSUFFICIENT -> provider.withdraw(PLAYER, new BigDecimal("10.01"));
                case UNSUPPORTED -> {
                    bank().unsupportedNext();
                    yield provider.withdraw(PLAYER, BigDecimal.ONE);
                }
                case OVERDRAWN -> {
                    bank().feeNext("0.50");
                    yield provider.withdraw(PLAYER, BigDecimal.TEN);
                }
                case FAILED -> {
                    bank().refuseNext("Account is locked");
                    yield provider.withdraw(PLAYER, BigDecimal.ONE);
                }
                case NOT_STARTED -> provider.withdraw(PLAYER, BigDecimal.ONE, () -> false);
            };
            boolean changed = bank().held().compareTo(BigDecimal.TEN) != 0;

            assertThat(result.status()).isEqualTo(status);
            assertThat(result.moved()).as(status.name()).isEqualTo(changed);
            assertThat(changed).as(status.name())
                .isEqualTo(status == EconomyResult.Status.OK || status == EconomyResult.Status.OVERDRAWN);
        }
    }

    @Test
    void aNullArgumentIsRefused() {
        EconomyProvider provider = provider(2);

        assertThatThrownBy(() -> provider.balance(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> provider.has(null, BigDecimal.ONE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> provider.has(PLAYER, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> provider.withdraw(null, BigDecimal.ONE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> provider.withdraw(PLAYER, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> provider.withdraw(PLAYER, BigDecimal.ONE, null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> provider.deposit(null, BigDecimal.ONE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> provider.deposit(PLAYER, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theThreadForAPlayerIsTheGlobalThreadAnAsyncThreadOrThatPlayersEntityThread() {
        assertThat(provider(2).thread(PLAYER)).isIn(ThreadTag.GLOBAL, ThreadTag.ASYNC, ThreadTag.entity(PLAYER));
    }

    @Test
    void aCallOffTheThreadTheProviderNamesThrowsAndNoMoneyMoves() {
        EconomyProvider provider = provider(2);
        bank().hold("10");
        List<String> started = new ArrayList<>();

        bank().onThread(false);

        assertThatThrownBy(() -> provider.balance(PLAYER)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("thread(player)").hasMessageContaining("Scheduler.dispatch");
        assertThatThrownBy(() -> provider.has(PLAYER, BigDecimal.ONE)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("thread(player)").hasMessageContaining("Scheduler.dispatch");
        assertThatThrownBy(() -> provider.withdraw(PLAYER, BigDecimal.ONE)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("thread(player)").hasMessageContaining("Scheduler.dispatch");
        assertThatThrownBy(() -> provider.withdraw(PLAYER, BigDecimal.ONE, () -> started.add("start")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("thread(player)").hasMessageContaining("Scheduler.dispatch");
        assertThatThrownBy(() -> provider.deposit(PLAYER, BigDecimal.ONE)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("thread(player)").hasMessageContaining("Scheduler.dispatch");
        assertThat(started).isEmpty();
        assertThat(bank().held()).isEqualByComparingTo("10");

        bank().onThread(true);
        assertThat(provider.withdraw(PLAYER, BigDecimal.ONE)).isEqualTo(EconomyResult.ok());
        assertThat(provider.deposit(PLAYER, BigDecimal.ONE)).isEqualTo(EconomyResult.ok());
        assertThat(provider.balance(PLAYER)).isEqualByComparingTo("10");
        assertThat(provider.has(PLAYER, BigDecimal.TEN)).isTrue();
    }

    @Test
    void theScaleTheThreadAndTheProbeAnswerOffTheThreadTheProviderNames() {
        EconomyProvider provider = provider(2);

        bank().onThread(false);

        assertThat(provider.scale()).isEqualTo(2);
        assertThatCode(() -> provider.thread(PLAYER)).doesNotThrowAnyException();
        assertThat(provider.probe()).isNotBlank();
    }
}
