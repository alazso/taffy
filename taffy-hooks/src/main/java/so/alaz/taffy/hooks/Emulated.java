package so.alaz.taffy.hooks;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

// A withdraw for an economy that does not refuse an overdraft itself: read the balance, refuse when it is short,
// withdraw, and read the balance again. Runs in one call on the economy's thread. Balances are compared as the economy
// reports them, never rounded to the money scale, so a balance just under the amount is short and one just under zero
// is overdrawn.
final class Emulated {

    private Emulated() {
    }

    // A failed read before the call means nothing moved, so it is FAILED. A read after an OK call that throws a
    // RuntimeException or a LinkageError goes to unchecked and the result stays OK, since the money moved. start runs
    // between the check and the call, and false returns NOT_STARTED with no call.
    static EconomyResult withdraw(Supplier<BigDecimal> balance, Function<BigDecimal, EconomyResult> call,
                                  BigDecimal amount, BooleanSupplier start, Consumer<Throwable> unchecked) {
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
        BigDecimal after;
        try {
            after = balance.get();
        } catch (RuntimeException | LinkageError unreadable) {
            unchecked.accept(unreadable);
            return result;
        }
        return after.signum() < 0 ? EconomyResult.of(EconomyResult.Status.OVERDRAWN) : result;
    }

    static BigDecimal rounded(BigDecimal balance, int scale) {
        return balance.setScale(scale, RoundingMode.HALF_EVEN);
    }
}
