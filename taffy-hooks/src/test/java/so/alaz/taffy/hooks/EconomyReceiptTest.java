package so.alaz.taffy.hooks;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class EconomyReceiptTest {

    @Test
    void aReceiptCarriesItsResultAndTheTransactionIdOfMoneyThatMoved() {
        EconomyReceipt receipt = new EconomyReceipt(EconomyResult.ok(), "tx-41");

        assertThat(receipt.result()).isEqualTo(EconomyResult.ok());
        assertThat(receipt.transactionId()).isEqualTo("tx-41");
        assertThat(new EconomyReceipt(EconomyResult.of(EconomyResult.Status.OVERDRAWN), "tx-42").transactionId())
            .isEqualTo("tx-42");
    }

    @Test
    void ofGivesAReceiptWithNoTransactionIdForEveryStatus() {
        for (EconomyResult.Status status : EconomyResult.Status.values()) {
            EconomyResult result = status == EconomyResult.Status.FAILED
                ? EconomyResult.failed("Account is locked") : EconomyResult.of(status);

            assertThat(EconomyReceipt.of(result)).isEqualTo(new EconomyReceipt(result, null));
            assertThat(EconomyReceipt.of(result).transactionId()).isNull();
        }
    }

    @Test
    void aTransactionIdIsRefusedForAResultThatMovedNoMoney() {
        for (EconomyResult.Status status : EconomyResult.Status.values()) {
            EconomyResult result = status == EconomyResult.Status.FAILED
                ? EconomyResult.failed("Account is locked") : EconomyResult.of(status);
            if (result.moved()) {
                continue;
            }

            assertThatThrownBy(() -> new EconomyReceipt(result, "tx-41")).as(status.name())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a " + status + " result moved no money and has no transaction id");
        }
    }

    @Test
    void aBlankTransactionIdAndAMissingResultAreRefused() {
        assertThatThrownBy(() -> new EconomyReceipt(EconomyResult.ok(), " "))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("a transaction id is not blank");
        assertThatThrownBy(() -> new EconomyReceipt(EconomyResult.ok(), ""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EconomyReceipt(null, "tx-41"))
            .isInstanceOf(NullPointerException.class).hasMessage("result");
        assertThatThrownBy(() -> EconomyReceipt.of(null))
            .isInstanceOf(NullPointerException.class).hasMessage("result");
    }
}
