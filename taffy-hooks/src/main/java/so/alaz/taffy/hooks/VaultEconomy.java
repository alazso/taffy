package so.alaz.taffy.hooks;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ThreadTag;
import so.alaz.taffy.platform.ThrowLog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

// A Vault 1.7 economy. Vault works in double, so an amount that does not convert to a double and back unchanged, which
// can happen beyond 15 significant digits, is refused. Loaded only when the Vault API is present.
final class VaultEconomy implements EconomyProvider {

    // The decimal places a balance is first read at. That rounding changes a balance by at most half a unit of this
    // scale, which is 20000 times smaller than the finest amount at MAX_SCALE.
    static final int GUARD_SCALE = MAX_SCALE + 4;

    // The distance, in units in the last place of a double, within which a balance reads as the nearest amount at
    // scale().
    static final int NOISE_ULPS = 16;

    private static final String SOURCE = "hook.vault";

    private final Server server;
    private final Economy economy;
    private final Plugin registrant;
    private final @Nullable Integer configured;
    private final Logger logger;
    private final ThrowLog throwLog;
    private final VaultOptions.ThreadRule threadRule;
    private final VaultOptions.FundsCheck fundsCheck;
    private volatile int scale = -1;

    VaultEconomy(Server server, Economy economy, Plugin registrant, @Nullable Integer scale, Logger logger,
                 VaultOptions.ThreadRule threadRule, VaultOptions.FundsCheck fundsCheck) {
        this.server = server;
        this.economy = economy;
        this.registrant = registrant;
        this.configured = scale;
        this.logger = logger;
        this.throwLog = new ThrowLog(logger);
        this.threadRule = threadRule;
        this.fundsCheck = fundsCheck;
    }

    // Reads only the services manager and calls nothing in the economy.
    static @Nullable EconomyProvider lookup(Server server, @Nullable Integer scale, Logger logger,
                                            VaultOptions.ThreadRule threadRule, VaultOptions.FundsCheck fundsCheck) {
        RegisteredServiceProvider<Economy> registration = server.getServicesManager().getRegistration(Economy.class);
        if (registration == null) {
            return null;
        }
        return new VaultEconomy(server, registration.getProvider(), registration.getPlugin(), scale, logger,
            threadRule, fundsCheck);
    }

    // Worked out on first use, never at lookup: the configured scale when set, else the economy's fractional digits
    // when 0 or more, else 2, capped at MAX_SCALE.
    @Override
    public int scale() {
        int known = scale;
        if (known >= 0) {
            return known;
        }
        synchronized (this) {
            if (scale < 0) {
                int digits = economy.fractionalDigits();
                if (configured == null) {
                    scale = digits >= 0 ? Math.min(digits, MAX_SCALE) : 2;
                } else {
                    if (digits >= 0 && configured > digits) {
                        logger.warning("scale " + configured + " is finer than the " + digits + " decimal places "
                            + economy.getName() + " keeps, so the economy may round amounts sent to it.");
                    }
                    scale = configured;
                }
            }
            return scale;
        }
    }

    @Override
    public ThreadTag thread(UUID player) {
        if (threadRule == VaultOptions.ThreadRule.GLOBAL) {
            return ThreadTag.GLOBAL;
        }
        Objects.requireNonNull(player, "player");
        return server.getPlayer(player) == null ? ThreadTag.GLOBAL : ThreadTag.entity(player);
    }

    @Override
    public BigDecimal balance(UUID player) {
        Objects.requireNonNull(player, "player");
        onThread(player);
        return Emulated.rounded(unrounded(player), scale());
    }

    @Override
    public boolean has(UUID player, BigDecimal amount) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(amount, "amount");
        onThread(player);
        BigDecimal wanted = amount.setScale(scale(), RoundingMode.HALF_EVEN);
        if (fundsCheck == VaultOptions.FundsCheck.NATIVE) {
            return economy.has(server.getOfflinePlayer(player), wanted.doubleValue());
        }
        return unrounded(player).compareTo(wanted) >= 0;
    }

    @Override
    public EconomyResult withdraw(UUID player, BigDecimal amount, BooleanSupplier start) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(start, "start");
        onThread(player);
        if (!registrant.isEnabled()) {
            return disabled();
        }
        BigDecimal payable = payable(amount);
        OfflinePlayer account = server.getOfflinePlayer(player);
        if (fundsCheck == VaultOptions.FundsCheck.NATIVE) {
            if (!start.getAsBoolean()) {
                return EconomyResult.of(EconomyResult.Status.NOT_STARTED);
            }
            return result(economy.withdrawPlayer(account, payable.doubleValue()));
        }
        double[] largest = {0};
        return Emulated.withdraw(() -> {
            double read = economy.getBalance(account);
            largest[0] = Math.max(largest[0], Math.abs(read));
            return amount(read, scale(), largest[0]);
        }, taken -> result(economy.withdrawPlayer(account, taken.doubleValue())), payable, start, this::unchecked);
    }

    @Override
    public EconomyResult deposit(UUID player, BigDecimal amount) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(amount, "amount");
        onThread(player);
        if (!registrant.isEnabled()) {
            return disabled();
        }
        BigDecimal payable = payable(amount);
        return result(economy.depositPlayer(server.getOfflinePlayer(player), payable.doubleValue()));
    }

    // The Vault 1.7 Economy has no call that sets a balance.
    @Override
    public EconomyResult setBalance(UUID player, BigDecimal amount) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(amount, "amount");
        onThread(player);
        return EconomyResult.of(EconomyResult.Status.UNSUPPORTED);
    }

    @Override
    public @Nullable String currencyNameSingular() {
        return told("currencyNameSingular", economy::currencyNameSingular);
    }

    @Override
    public @Nullable String currencyNamePlural() {
        return told("currencyNamePlural", economy::currencyNamePlural);
    }

    @Override
    public @Nullable String format(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        double sent = amount.setScale(scale(), RoundingMode.HALF_EVEN).doubleValue();
        if (!Double.isFinite(sent)) {
            throw new IllegalArgumentException(amount + " is beyond the range of a double");
        }
        return told("format", () -> economy.format(sent));
    }

    @Override
    public String probe() {
        int digits = economy.fractionalDigits();
        int places = scale();
        return economy.getName() + " through Vault, " + places + " decimal places"
            + (digits == places ? "" : " (the economy reports " + digits + ")");
    }

    // Throws unless the calling thread is the one thread(player) names. On Paper both server checks are true on the
    // main thread only.
    private void onThread(UUID player) {
        Player online = threadRule == VaultOptions.ThreadRule.ENTITY ? server.getPlayer(player) : null;
        boolean here = online == null ? server.isGlobalTickThread() : server.isOwnedByCurrentRegion(online);
        if (!here) {
            ThreadTag named = online == null ? ThreadTag.GLOBAL : ThreadTag.entity(player);
            throw new IllegalStateException("an economy call for " + player + " runs on the thread thread(player)"
                + " names, " + named + ", not on " + Thread.currentThread().getName()
                + "; run it through Scheduler.dispatch(economy.thread(player), ...)");
        }
    }

    private EconomyResult disabled() {
        return EconomyResult.failed("the economy plugin " + registrant.getName() + " is disabled");
    }

    private BigDecimal payable(BigDecimal amount) {
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("an amount is positive, not " + amount);
        }
        int places = scale();
        BigDecimal rounded = amount.setScale(places, RoundingMode.HALF_EVEN);
        if (rounded.signum() == 0) {
            throw new IllegalArgumentException(amount + " rounds to zero at " + places + " decimal places");
        }
        double sent = rounded.doubleValue();
        if (!Double.isFinite(sent) || BigDecimal.valueOf(sent).compareTo(rounded) != 0) {
            throw new IllegalArgumentException(amount + " cannot reach the economy exactly as a double");
        }
        return rounded;
    }

    private BigDecimal unrounded(UUID player) {
        double read = economy.getBalance(server.getOfflinePlayer(player));
        return amount(read, scale(), read);
    }

    // What the economy says about its currency, or null for a null or blank answer and for a call that throws.
    private @Nullable String told(String call, Supplier<@Nullable String> read) {
        String text;
        try {
            text = read.get();
        } catch (RuntimeException | LinkageError failure) {
            throwLog.warn(SOURCE + "." + call, "The economy threw in " + call + ", so it is read as having none ("
                + failure + ").", failure);
            return null;
        }
        return text == null || text.isBlank() ? null : text;
    }

    private void unchecked(Throwable failure) {
        throwLog.warn(SOURCE, "The balance could not be read after a withdraw that went through, so it was not checked"
            + " for an overdraft (" + failure + ").", failure);
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

    // A missing response throws, so its outcome counts as unknown.
    private static EconomyResult result(@Nullable EconomyResponse response) {
        if (response == null) {
            throw new IllegalStateException("the economy returned no response");
        }
        if (response.transactionSuccess()) {
            return EconomyResult.ok();
        }
        if (response.type == EconomyResponse.ResponseType.NOT_IMPLEMENTED) {
            return EconomyResult.of(EconomyResult.Status.UNSUPPORTED);
        }
        String message = response.errorMessage;
        return EconomyResult.failed(message == null || message.isBlank() ? "the economy refused it" : message);
    }
}
