package so.alaz.taffy.hooks;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class EmulatedTest {

    private final List<BigDecimal> withdrawn = new ArrayList<>();

    @Test
    void aCoveredWithdrawRunsAndChecksTheBalanceAfterwards() {
        EconomyResult result = withdraw(balances("10.00", "4.00"), ok(), new BigDecimal("6.00"));

        assertThat(result).isEqualTo(EconomyResult.ok());
        assertThat(withdrawn).containsExactly(new BigDecimal("6.00"));
    }

    @Test
    void aBalanceAndAPriceBothOfTenCentsPassTheCheck() {
        EconomyResult result = withdraw(() -> BigDecimal.valueOf(0.1), ok(), new BigDecimal("0.10"));

        assertThat(result).isEqualTo(EconomyResult.ok());
    }

    @Test
    void aCallStoppedAfterTheCheckMovesNothing() {
        List<String> order = new ArrayList<>();
        EconomyResult result = Emulated.withdraw(() -> {
            order.add("balance");
            return BigDecimal.TEN;
        }, ok(), new BigDecimal("6.00"), () -> {
            order.add("start");
            return false;
        });

        assertThat(result).isEqualTo(EconomyResult.of(EconomyResult.Status.NOT_STARTED));
        assertThat(order).containsExactly("balance", "start");
        assertThat(withdrawn).isEmpty();
    }

    @Test
    void aShortBalanceIsInsufficientAndNeitherStartNorTheCallRuns() {
        List<String> started = new ArrayList<>();
        EconomyResult result = Emulated.withdraw(balances("5.99"), ok(), new BigDecimal("6.00"), () -> {
            started.add("start");
            return true;
        });

        assertThat(result.status()).isEqualTo(EconomyResult.Status.INSUFFICIENT);
        assertThat(started).isEmpty();
        assertThat(withdrawn).isEmpty();
    }

    @Test
    void aBalanceBelowZeroAfterTheCallIsOverdrawn() {
        EconomyResult result = withdraw(balances("10.00", "-2.00"), ok(), new BigDecimal("10.00"));

        assertThat(result).isEqualTo(EconomyResult.of(EconomyResult.Status.OVERDRAWN));
        assertThat(withdrawn).containsExactly(new BigDecimal("10.00"));
    }

    @Test
    void aRefusedCallPassesThroughWithoutASecondRead() {
        Deque<BigDecimal> reads = new ArrayDeque<>(List.of(new BigDecimal("10")));
        EconomyResult result = withdraw(reads::pop, amount -> EconomyResult.failed("frozen account"), BigDecimal.ONE);

        assertThat(result).isEqualTo(EconomyResult.failed("frozen account"));
        assertThat(reads).isEmpty();
    }

    @Test
    void anUnreadableBalanceBeforeTheCallIsAFailureAndAfterItPropagates() {
        EconomyResult before = withdraw(() -> {
            throw new IllegalStateException("no account");
        }, ok(), BigDecimal.ONE);
        assertThat(before.status()).isEqualTo(EconomyResult.Status.FAILED);
        assertThat(before.message()).isEqualTo("the balance could not be read: no account");
        assertThat(withdrawn).isEmpty();

        Deque<BigDecimal> reads = new ArrayDeque<>(List.of(BigDecimal.TEN));
        assertThatThrownBy(() -> withdraw(() -> {
            if (reads.isEmpty()) {
                throw new IllegalStateException("economy went away");
            }
            return reads.pop();
        }, ok(), BigDecimal.ONE)).hasMessage("economy went away");
        assertThat(withdrawn).containsExactly(BigDecimal.ONE);
    }

    @Test
    void aLinkageErrorReadingTheBalanceBeforeTheCallIsAFailure() {
        EconomyResult message = withdraw(() -> {
            throw new AbstractMethodError("getBalance(OfflinePlayer)");
        }, ok(), BigDecimal.ONE);
        EconomyResult noMessage = withdraw(() -> {
            throw new NoSuchMethodError();
        }, ok(), BigDecimal.ONE);

        assertThat(message).isEqualTo(EconomyResult.failed("the balance could not be read: getBalance(OfflinePlayer)"));
        assertThat(noMessage)
            .isEqualTo(EconomyResult.failed("the balance could not be read: java.lang.NoSuchMethodError"));
        assertThat(withdrawn).isEmpty();
    }

    @Test
    void aBalanceJustUnderTheAmountIsShortAlthoughItRoundsUpToIt() {
        assertThat(withdraw(balances("0.995"), ok(), new BigDecimal("1.00")))
            .isEqualTo(EconomyResult.of(EconomyResult.Status.INSUFFICIENT));
        assertThat(withdraw(balances("99.5"), ok(), new BigDecimal("100")))
            .isEqualTo(EconomyResult.of(EconomyResult.Status.INSUFFICIENT));
        assertThat(withdrawn).isEmpty();
    }

    @Test
    void aBalanceJustUnderZeroAfterTheCallIsOverdrawnAlthoughItRoundsToZero() {
        assertThat(withdraw(balances("1.00", "-0.004"), ok(), new BigDecimal("1.00")))
            .isEqualTo(EconomyResult.of(EconomyResult.Status.OVERDRAWN));
    }

    @Test
    void roundingIsHalfEven() {
        assertThat(Emulated.rounded(new BigDecimal("0.125"), 2)).isEqualTo(new BigDecimal("0.12"));
        assertThat(Emulated.rounded(new BigDecimal("0.135"), 2)).isEqualTo(new BigDecimal("0.14"));
        assertThat(Emulated.rounded(new BigDecimal("-0.125"), 2)).isEqualTo(new BigDecimal("-0.12"));
        assertThat(Emulated.rounded(BigDecimal.ONE, 2)).isEqualTo(new BigDecimal("1.00"));
    }

    private EconomyResult withdraw(Supplier<BigDecimal> balance, Function<BigDecimal, EconomyResult> call,
                                   BigDecimal amount) {
        return Emulated.withdraw(balance, call, amount, () -> true);
    }

    private Supplier<BigDecimal> balances(String... values) {
        Deque<BigDecimal> reads = new ArrayDeque<>();
        for (String value : values) {
            reads.add(new BigDecimal(value));
        }
        return reads::pop;
    }

    private Function<BigDecimal, EconomyResult> ok() {
        return amount -> {
            withdrawn.add(amount);
            return EconomyResult.ok();
        };
    }
}
