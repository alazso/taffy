package so.alaz.taffy.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class MoneyColumnTest {

    @Test
    void storesFourDecimalPlacesAsAWholeNumber() {
        assertThat(MoneyColumn.toStored(new BigDecimal("12.5"))).isEqualTo(125_000L);
        assertThat(MoneyColumn.toStored(new BigDecimal("0.0001"))).isEqualTo(1L);
        assertThat(MoneyColumn.fromStored(125_000L)).isEqualByComparingTo("12.5").hasScaleOf(4);
    }

    @Test
    void aNegativeAmountIsRefusedButANegativeBalanceIsKeptAsEvidence() {
        assertThatThrownBy(() -> MoneyColumn.toStored(new BigDecimal("-3.25")))
            .isInstanceOf(MoneyOutOfRangeException.class)
            .hasMessage("amount -3.25 is outside 0 to 922337203685477.5807");
        assertThat(MoneyColumn.toStoredOrNull(new BigDecimal("-3.25"))).isEqualTo(-32_500L);
        assertThat(MoneyColumn.fromStored(-32_500L)).isEqualByComparingTo("-3.25");
    }

    @Test
    void roundsHalfEvenAtTheFifthDecimal() {
        assertThat(MoneyColumn.toStored(new BigDecimal("1.00005"))).isEqualTo(10_000L);
        assertThat(MoneyColumn.toStored(new BigDecimal("1.00015"))).isEqualTo(10_002L);
    }

    @Test
    void anAmountBelowTheSmallestStepIsZeroWithoutRounding() {
        assertThat(MoneyColumn.toStored(new BigDecimal("0.00009"))).isZero();
        assertThat(MoneyColumn.toStored(new BigDecimal("-0.00009"))).isZero();
        assertThat(MoneyColumn.toStored(new BigDecimal("1e-200000000"))).isZero();
        assertThat(MoneyColumn.toStoredOrNull(new BigDecimal("0.00009"))).isZero();
        assertThat(MoneyColumn.toStoredOrNull(new BigDecimal("-0.00009"))).isZero();
        assertThatThrownBy(() -> MoneyColumn.toStored(new BigDecimal("-0.0001")))
            .isInstanceOf(MoneyOutOfRangeException.class);
    }

    @Test
    void theCapAndItsNegativeFitExactly() {
        assertThat(MoneyColumn.MAX).isEqualByComparingTo("922337203685477.5807");
        assertThat(MoneyColumn.toStored(MoneyColumn.MAX)).isEqualTo(Long.MAX_VALUE);
        assertThat(MoneyColumn.toStoredOrNull(MoneyColumn.MAX.negate())).isEqualTo(-Long.MAX_VALUE);
    }

    @Test
    void beyondTheCapIsRefused() {
        BigDecimal over = new BigDecimal("922337203685477.5808");

        assertThatThrownBy(() -> MoneyColumn.toStored(over))
            .isInstanceOf(MoneyOutOfRangeException.class)
            .hasMessage("amount 922337203685477.5808 is outside 0 to 922337203685477.5807");
        assertThat(MoneyColumn.toStoredOrNull(over)).isNull();
        assertThat(MoneyColumn.toStoredOrNull(over.negate())).isNull();
    }

    @Test
    void aSignedAmountIsStoredUpToTheCapInMagnitudeAndRefusedBeyondIt() {
        BigDecimal over = new BigDecimal("922337203685477.5808");

        assertThat(MoneyColumn.toStoredSigned(new BigDecimal("-3.25"))).isEqualTo(-32_500L);
        assertThat(MoneyColumn.toStoredSigned(new BigDecimal("1.00015"))).isEqualTo(10_002L);
        assertThat(MoneyColumn.toStoredSigned(new BigDecimal("-0.00009"))).isZero();
        assertThat(MoneyColumn.toStoredSigned(MoneyColumn.MAX)).isEqualTo(Long.MAX_VALUE);
        assertThat(MoneyColumn.toStoredSigned(MoneyColumn.MAX.negate())).isEqualTo(-Long.MAX_VALUE);
        assertThatThrownBy(() -> MoneyColumn.toStoredSigned(over))
            .isInstanceOf(MoneyOutOfRangeException.class)
            .hasMessage("amount 922337203685477.5808 is outside -922337203685477.5807 to 922337203685477.5807");
        assertThatThrownBy(() -> MoneyColumn.toStoredSigned(over.negate()))
            .isInstanceOf(MoneyOutOfRangeException.class)
            .hasMessage("amount -922337203685477.5808 is outside -922337203685477.5807 to 922337203685477.5807");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void hugeExponentsAreRefusedWithoutBuildingTheirDigits() {
        assertThatThrownBy(() -> MoneyColumn.toStored(new BigDecimal("1e999999999")))
            .isInstanceOf(MoneyOutOfRangeException.class)
            .hasMessageContaining("1E+999999999");
        assertThat(MoneyColumn.toStoredOrNull(new BigDecimal("-1e200000000"))).isNull();
        assertThatThrownBy(() -> MoneyColumn.toStoredSigned(new BigDecimal("-1e999999999")))
            .isInstanceOf(MoneyOutOfRangeException.class)
            .hasMessageContaining("-1E+999999999");
    }
}
