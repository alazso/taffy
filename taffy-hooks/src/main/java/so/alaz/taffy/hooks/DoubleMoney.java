package so.alaz.taffy.hooks;

import java.math.BigDecimal;
import java.math.RoundingMode;

// Amounts for an economy that works in double. Names no class of another plugin.
final class DoubleMoney {

    // The decimal places a balance is first read at. That rounding changes a balance by at most half a unit of this
    // scale, which is 20000 times smaller than the finest amount at MAX_SCALE.
    static final int GUARD_SCALE = EconomyProvider.MAX_SCALE + 4;

    // The distance, in units in the last place of a double, within which a balance reads as the nearest amount at
    // the provider's scale.
    static final int NOISE_ULPS = 16;

    private DoubleMoney() {
    }

    // Rounded half-even to GUARD_SCALE, so 0.09999999999999998, which is 1.0 - 0.9 in double, reads as 0.1.
    static BigDecimal amount(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalStateException("the economy reported a balance of " + value);
        }
        return BigDecimal.valueOf(value).setScale(GUARD_SCALE, RoundingMode.HALF_EVEN);
    }

    // amount(value), or the nearest amount at the given scale when the double lies within NOISE_ULPS units in the
    // last place of magnitude of it, so 1000000428.0799999, which is 1000000456.18 - 28.10 in double, reads as
    // 1000000428.08. The error of a subtraction is measured in units of the larger operand, so the balance read
    // after a withdraw takes the balance read before it as magnitude. One unit in the last place of a double passes
    // half a unit of GUARD_SCALE from 2^25, about 33.5 million.
    static BigDecimal amount(double value, int scale, double magnitude) {
        BigDecimal read = amount(value);
        BigDecimal nearest = read.setScale(scale, RoundingMode.HALF_EVEN);
        BigDecimal off = new BigDecimal(value).subtract(nearest).abs();
        BigDecimal noise = new BigDecimal(Math.ulp(magnitude)).multiply(BigDecimal.valueOf(NOISE_ULPS));
        return off.compareTo(noise) <= 0 ? nearest.setScale(GUARD_SCALE) : read;
    }

    // The amount of a withdraw or a deposit, rounded half-even to the scale.
    static BigDecimal payable(BigDecimal amount, int scale) {
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("an amount is positive, not " + amount);
        }
        BigDecimal rounded = amount.setScale(scale, RoundingMode.HALF_EVEN);
        if (rounded.signum() == 0) {
            throw new IllegalArgumentException(amount + " rounds to zero at " + scale + " decimal places");
        }
        return exact(amount, rounded);
    }

    // Throws unless the rounded amount converts to a double and back unchanged.
    static BigDecimal exact(BigDecimal amount, BigDecimal rounded) {
        double sent = rounded.doubleValue();
        if (!Double.isFinite(sent) || BigDecimal.valueOf(sent).compareTo(rounded) != 0) {
            throw new IllegalArgumentException(amount + " cannot reach the economy exactly as a double");
        }
        return rounded;
    }
}
