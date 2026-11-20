package so.alaz.taffy.storage;

import java.math.BigDecimal;

/**
 * An amount of money that cannot be stored because it is {@code -0.0001} or less, or above {@link MoneyColumn#MAX},
 * or a signed amount that cannot be stored because its magnitude is above {@link MoneyColumn#MAX}. An amount whose
 * magnitude is below {@code 0.0001} is stored as 0 whatever its sign, so it is not refused.
 */
public final class MoneyOutOfRangeException extends IllegalArgumentException {

    /**
     * A refusal of the given amount, with a message that names it and the range it is outside.
     *
     * @param amount the refused amount
     */
    public MoneyOutOfRangeException(BigDecimal amount) {
        this("amount " + amount + " is outside 0 to " + MoneyColumn.MAX.toPlainString());
    }

    private MoneyOutOfRangeException(String message) {
        super(message);
    }

    static MoneyOutOfRangeException signed(BigDecimal amount) {
        String max = MoneyColumn.MAX.toPlainString();
        return new MoneyOutOfRangeException("amount " + amount + " is outside -" + max + " to " + max);
    }
}
