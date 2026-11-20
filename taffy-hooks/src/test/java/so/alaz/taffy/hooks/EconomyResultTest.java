package so.alaz.taffy.hooks;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class EconomyResultTest {

    @Test
    void okHasNoMessage() {
        assertThat(EconomyResult.ok()).isEqualTo(new EconomyResult(EconomyResult.Status.OK, null));
        assertThat(EconomyResult.ok().message()).isNull();
    }

    @Test
    void ofGivesEveryStatusButFailedWithoutAMessage() {
        for (EconomyResult.Status status : EnumSet.complementOf(EnumSet.of(EconomyResult.Status.FAILED))) {
            EconomyResult result = EconomyResult.of(status);
            assertThat(result.status()).as(status.name()).isEqualTo(status);
            assertThat(result.message()).as(status.name()).isNull();
        }
    }

    @Test
    void failedCarriesItsReason() {
        EconomyResult result = EconomyResult.failed("frozen account");

        assertThat(result.status()).isEqualTo(EconomyResult.Status.FAILED);
        assertThat(result.message()).isEqualTo("frozen account");
    }

    @Test
    void aFailedResultWithoutAReasonIsRefused() {
        assertThatThrownBy(() -> EconomyResult.of(EconomyResult.Status.FAILED))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a FAILED result names its reason");
        assertThatThrownBy(() -> EconomyResult.failed(" "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a FAILED result names its reason");
        assertThatThrownBy(() -> new EconomyResult(EconomyResult.Status.FAILED, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anyOtherStatusMayCarryAMessage() {
        assertThat(new EconomyResult(EconomyResult.Status.UNSUPPORTED, "no offline accounts").message())
            .isEqualTo("no offline accounts");
    }

    @Test
    void theStatusIsRequired() {
        assertThatThrownBy(() -> new EconomyResult(null, "reason"))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("status");
    }

    @Test
    void resultsAreEqualWhenStatusAndMessageAre() {
        assertThat(EconomyResult.failed("a")).isEqualTo(EconomyResult.failed("a"))
            .hasSameHashCodeAs(EconomyResult.failed("a"))
            .isNotEqualTo(EconomyResult.failed("b"));
        assertThat(EconomyResult.of(EconomyResult.Status.INSUFFICIENT))
            .isNotEqualTo(EconomyResult.of(EconomyResult.Status.OVERDRAWN));
    }

    @Test
    void movedIsTrueForTheStatusesWhoseMoneyMoved() {
        for (EconomyResult.Status status : EconomyResult.Status.values()) {
            boolean moved = switch (status) {
                case OK, OVERDRAWN -> true;
                case INSUFFICIENT, UNSUPPORTED, FAILED, NOT_STARTED -> false;
            };
            EconomyResult result = status == EconomyResult.Status.FAILED
                ? EconomyResult.failed("frozen account") : EconomyResult.of(status);

            assertThat(result.moved()).as(status.name()).isEqualTo(moved);
        }
    }

    @Test
    void statusesKeepTheirOrder() {
        assertThat(EconomyResult.Status.values()).containsExactly(EconomyResult.Status.OK,
            EconomyResult.Status.INSUFFICIENT, EconomyResult.Status.UNSUPPORTED, EconomyResult.Status.OVERDRAWN,
            EconomyResult.Status.FAILED, EconomyResult.Status.NOT_STARTED);
    }
}
