package so.alaz.taffy.hooks;

import org.junit.jupiter.api.Test;
import so.alaz.taffy.platform.ThreadTag;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class EconomyProviderTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void theLargestScaleIsFour() {
        assertThat(EconomyProvider.MAX_SCALE).isEqualTo(4);
    }

    @Test
    void hasComparesTheBalanceUnroundedWithTheAmountRoundedToTheScale() {
        Fixed economy = new Fixed(2, new BigDecimal("0.995"));

        assertThat(economy.has(PLAYER, new BigDecimal("0.99"))).isTrue();
        assertThat(economy.has(PLAYER, new BigDecimal("0.994"))).isTrue();
        assertThat(economy.has(PLAYER, new BigDecimal("0.995"))).isFalse();
        assertThat(economy.has(PLAYER, new BigDecimal("1.00"))).isFalse();
        assertThat(economy.has(PLAYER, new BigDecimal("1.004"))).isFalse();
    }

    @Test
    void hasRoundsTheAmountHalfEvenAtScaleZero() {
        Fixed economy = new Fixed(0, new BigDecimal("2.5"));

        assertThat(economy.has(PLAYER, new BigDecimal("2"))).isTrue();
        assertThat(economy.has(PLAYER, new BigDecimal("2.5"))).isTrue();
        assertThat(economy.has(PLAYER, new BigDecimal("2.6"))).isFalse();
    }

    @Test
    void aNegativeBalanceCoversNoPositiveAmount() {
        Fixed economy = new Fixed(2, new BigDecimal("-0.01"));

        assertThat(economy.has(PLAYER, new BigDecimal("0.01"))).isFalse();
    }

    @Test
    void anAmountThatIsNegativeOrRoundsToZeroIsCoveredByABalanceOfZeroOrMore() {
        Fixed empty = new Fixed(2, BigDecimal.ZERO);
        Fixed owing = new Fixed(2, new BigDecimal("-3"));

        assertThat(empty.has(PLAYER, new BigDecimal("0.004"))).isTrue();
        assertThat(empty.has(PLAYER, new BigDecimal("-5"))).isTrue();
        assertThat(owing.has(PLAYER, new BigDecimal("0.004"))).isFalse();
        assertThat(owing.has(PLAYER, new BigDecimal("-5"))).isTrue();
        assertThat(owing.has(PLAYER, new BigDecimal("-2"))).isFalse();
    }

    @Test
    void theTwoArgumentWithdrawPassesAStartThatGoesAhead() {
        Fixed economy = new Fixed(2, BigDecimal.TEN);

        assertThat(economy.withdraw(PLAYER, BigDecimal.ONE)).isEqualTo(EconomyResult.ok());
        assertThat(economy.started).isTrue();
        assertThat(economy.withdrawn).isEqualTo(BigDecimal.ONE);
    }

    @Test
    void anImplementationWithoutTheLaterMethodsAnswersThatItCannot() {
        Fixed economy = new Fixed(2, BigDecimal.TEN);

        assertThat(economy.setBalance(PLAYER, BigDecimal.ONE))
            .isEqualTo(EconomyResult.of(EconomyResult.Status.UNSUPPORTED));
        assertThat(economy.setBalance(PLAYER, new BigDecimal("-1")).moved()).isFalse();
        assertThat(economy.currencyNameSingular()).isNull();
        assertThat(economy.currencyNamePlural()).isNull();
        assertThat(economy.currencySymbol()).isNull();
        assertThat(economy.format(BigDecimal.ONE)).isNull();
        assertThatThrownBy(() -> economy.setBalance(null, BigDecimal.ONE))
            .isInstanceOf(NullPointerException.class).hasMessage("player");
        assertThatThrownBy(() -> economy.setBalance(PLAYER, null))
            .isInstanceOf(NullPointerException.class).hasMessage("amount");
        assertThatThrownBy(() -> economy.format(null)).isInstanceOf(NullPointerException.class).hasMessage("amount");
        assertThat(economy.withdrawn).isNull();
    }

    @Test
    void aReceiptOfAnImplementationWithoutTheLaterMethodsCarriesItsResultAndNoTransactionId() {
        Fixed economy = new Fixed(2, BigDecimal.TEN);

        assertThat(economy.withdrawReceipt(PLAYER, BigDecimal.ONE, () -> true))
            .isEqualTo(new EconomyReceipt(EconomyResult.ok(), null));
        assertThat(economy.withdrawn).isEqualTo(BigDecimal.ONE);
        assertThat(economy.withdrawReceipt(PLAYER, BigDecimal.ONE, () -> false))
            .isEqualTo(new EconomyReceipt(EconomyResult.of(EconomyResult.Status.NOT_STARTED), null));
        assertThat(economy.depositReceipt(PLAYER, BigDecimal.ONE))
            .isEqualTo(new EconomyReceipt(EconomyResult.of(EconomyResult.Status.UNSUPPORTED), null));
    }

    private static final class Fixed implements EconomyProvider {

        private final int scale;
        private final BigDecimal balance;
        private boolean started;
        private BigDecimal withdrawn;

        Fixed(int scale, BigDecimal balance) {
            this.scale = scale;
            this.balance = balance;
        }

        @Override
        public int scale() {
            return scale;
        }

        @Override
        public ThreadTag thread(UUID player) {
            return ThreadTag.GLOBAL;
        }

        @Override
        public BigDecimal balance(UUID player) {
            return balance;
        }

        @Override
        public EconomyResult withdraw(UUID player, BigDecimal amount, BooleanSupplier start) {
            started = start.getAsBoolean();
            if (!started) {
                return EconomyResult.of(EconomyResult.Status.NOT_STARTED);
            }
            withdrawn = amount;
            return EconomyResult.ok();
        }

        @Override
        public EconomyResult deposit(UUID player, BigDecimal amount) {
            return EconomyResult.of(EconomyResult.Status.UNSUPPORTED);
        }

        @Override
        public String probe() {
            return "fixed";
        }
    }
}
