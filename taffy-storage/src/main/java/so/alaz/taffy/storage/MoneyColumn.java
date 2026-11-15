package so.alaz.taffy.storage;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Encodes an amount of money as a whole number for a {@code BIGINT} column: the amount at {@value #SCALE}
 * decimal places with the decimal point removed, so {@code 12.5} is stored as {@code 125000}. Stored values sort
 * in numeric order on every database.
 */
public final class MoneyColumn {

    /** The number of decimal places an amount keeps when it is stored. */
    public static final int SCALE = 4;

    /** {@link Long#MAX_VALUE} at {@value #SCALE} decimal places: the largest amount a scaled column holds. */
    public static final BigDecimal MAX = BigDecimal.valueOf(Long.MAX_VALUE, SCALE);

    private static final BigDecimal SMALLEST = BigDecimal.valueOf(1, SCALE);

    private MoneyColumn() {
    }

    /**
     * Encodes an amount that must be between 0 and {@link #MAX}, rounded half even to {@value #SCALE} decimal
     * places. An amount whose magnitude is below the smallest step of {@code 0.0001} is stored as 0 whatever its
     * sign, without rounding.
     *
     * @param amount the amount
     * @return the stored whole number
     * @throws MoneyOutOfRangeException if the amount is {@code -0.0001} or less, or above {@link #MAX}
     */
    public static long toStored(BigDecimal amount) {
        Long stored = toStoredOrNull(amount);
        if (stored == null || stored < 0) {
            throw new MoneyOutOfRangeException(amount);
        }
        return stored;
    }

    /**
     * Encodes a signed amount, such as a balance that may be negative, rounded half even to {@value #SCALE}
     * decimal places. An amount whose magnitude is below the smallest step of {@code 0.0001} is stored as 0
     * whatever its sign, without rounding. The magnitude is compared with {@link #MAX} before the amount is
     * rescaled, so an amount such as {@code 1e999999999} is refused without building its digits.
     *
     * @param amount the amount
     * @return the stored whole number, or {@code null} if the magnitude of the amount is above {@link #MAX}
     */
    public static @Nullable Long toStoredOrNull(BigDecimal amount) {
        BigDecimal magnitude = amount.abs();
        if (magnitude.compareTo(MAX) > 0) {
            return null;
        }
        if (magnitude.compareTo(SMALLEST) < 0) {
            return 0L;
        }
        return amount.setScale(SCALE, RoundingMode.HALF_EVEN).unscaledValue().longValueExact();
    }

    /**
     * Decodes a stored whole number.
     *
     * @param stored the stored whole number
     * @return the amount, at {@value #SCALE} decimal places
     */
    public static BigDecimal fromStored(long stored) {
        return BigDecimal.valueOf(stored, SCALE);
    }
}
