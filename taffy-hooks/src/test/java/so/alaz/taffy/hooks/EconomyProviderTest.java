package so.alaz.taffy.hooks;

import org.junit.jupiter.api.Test;
import so.alaz.taffy.platform.ThreadTag;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

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
