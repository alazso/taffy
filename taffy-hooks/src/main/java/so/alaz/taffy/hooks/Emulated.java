package so.alaz.taffy.hooks;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

// A withdraw for an economy that does not refuse an overdraft itself: read the balance, refuse when it is short,
// withdraw, and read the balance again. Runs in one call on the economy's thread. Balances are compared as the economy
// reports them, never rounded, so a balance just under the amount is short and one just under zero is overdrawn.
final class Emulated {

    private Emulated() {
    }

    // A failed read before the call means nothing moved, so it is FAILED; a failed read after it propagates, since
    // the money moved and the balance cannot be checked. start runs between the check and the call, and false
    // returns NOT_STARTED with no call.
    static EconomyResult withdraw(Supplier<BigDecimal> balance, Function<BigDecimal, EconomyResult> call,
                                  BigDecimal amount, BooleanSupplier start) {
        BigDecimal before;
        try {
            before = balance.get();
        } catch (RuntimeException | LinkageError unreadable) {
            String reason = unreadable.getMessage();
            return EconomyResult.failed("the balance could not be read: "
                + (reason == null ? unreadable.toString() : reason));
        }
        if (before.compareTo(amount) < 0) {
            return EconomyResult.of(EconomyResult.Status.INSUFFICIENT);
        }
        if (!start.getAsBoolean()) {
            return EconomyResult.of(EconomyResult.Status.NOT_STARTED);
        }
        EconomyResult result = call.apply(amount);
        if (result.status() != EconomyResult.Status.OK) {
            return result;
        }
        if (balance.get().signum() < 0) {
            return EconomyResult.of(EconomyResult.Status.OVERDRAWN);
        }
        return result;
    }

    static BigDecimal rounded(BigDecimal balance, int scale) {
        return balance.setScale(scale, RoundingMode.HALF_EVEN);
    }
}
