package so.alaz.taffy.hooks;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * The outcome of an {@link EconomyProvider#withdrawReceipt} or {@link EconomyProvider#depositReceipt}: the result a
 * withdraw or deposit gives, with the id the economy gave the transaction. An economy that gives its transactions no
 * id, as the Vault and PlayerPoints economies do not, has a {@code null} id on every receipt.
 *
 * @param result what happened
 * @param transactionId the economy's id for the transaction, or {@code null} when the economy gave none or no money
 *     moved
 * @since 0.4.13
 */
public record EconomyReceipt(EconomyResult result, @Nullable String transactionId) {

    /**
     * Creates a receipt.
     *
     * @throws NullPointerException if {@code result} is {@code null}
     * @throws IllegalArgumentException if {@code transactionId} is blank, or is set for a result whose
     *     {@link EconomyResult#moved()} is {@code false}
     */
    public EconomyReceipt {
        Objects.requireNonNull(result, "result");
        if (transactionId != null && transactionId.isBlank()) {
            throw new IllegalArgumentException("a transaction id is not blank");
        }
        if (transactionId != null && !result.moved()) {
            throw new IllegalArgumentException("a " + result.status() + " result moved no money and has no"
                + " transaction id");
        }
    }

    /**
     * A receipt with no transaction id.
     *
     * @param result what happened
     * @return the receipt
     * @throws NullPointerException if {@code result} is {@code null}
     */
    public static EconomyReceipt of(EconomyResult result) {
        return new EconomyReceipt(result, null);
    }
}
