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

    static final int GUARD_SCALE = DoubleMoney.GUARD_SCALE;

    static final int NOISE_ULPS = DoubleMoney.NOISE_ULPS;

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
        if (!Double.isFinite(amount.doubleValue())) {
            throw new IllegalArgumentException(amount + " is beyond the range of a double");
        }
        return told("format",
            () -> economy.format(amount.setScale(scale(), RoundingMode.HALF_EVEN).doubleValue()));
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
        return DoubleMoney.payable(amount, scale());
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

    static BigDecimal amount(double value) {
        return DoubleMoney.amount(value);
    }

    static BigDecimal amount(double value, int scale, double magnitude) {
        return DoubleMoney.amount(value, scale, magnitude);
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
