package so.alaz.taffy.hooks;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ThreadTag;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

// A Vault 1.7 economy. Vault works in double, so an amount that does not convert to a double and back unchanged, which
// can happen beyond 15 significant digits, is refused. Loaded only when the Vault API is present.
final class VaultEconomy implements EconomyProvider {

    private final Server server;
    private final Economy economy;
    private final @Nullable Integer configured;
    private final Logger logger;
    private volatile int scale = -1;

    VaultEconomy(Server server, Economy economy, @Nullable Integer scale, Logger logger) {
        this.server = server;
        this.economy = economy;
        this.configured = scale;
        this.logger = logger;
    }

    // Reads only the services manager and calls nothing in the economy.
    static @Nullable EconomyProvider lookup(Server server, @Nullable Integer scale, Logger logger) {
        RegisteredServiceProvider<Economy> registration = server.getServicesManager().getRegistration(Economy.class);
        if (registration == null) {
            return null;
        }
        return new VaultEconomy(server, registration.getProvider(), scale, logger);
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
        return ThreadTag.GLOBAL;
    }

    @Override
    public BigDecimal balance(UUID player) {
        return Emulated.rounded(unrounded(player), scale());
    }

    @Override
    public boolean has(UUID player, BigDecimal amount) {
        BigDecimal wanted = amount.setScale(scale(), RoundingMode.HALF_EVEN);
        return unrounded(player).compareTo(wanted) >= 0;
    }

    @Override
    public EconomyResult withdraw(UUID player, BigDecimal amount, BooleanSupplier start) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(start, "start");
        BigDecimal payable = payable(amount);
        OfflinePlayer account = server.getOfflinePlayer(player);
        return Emulated.withdraw(() -> amount(economy.getBalance(account)),
            taken -> result(economy.withdrawPlayer(account, taken.doubleValue())), payable, start);
    }

    @Override
    public EconomyResult deposit(UUID player, BigDecimal amount) {
        Objects.requireNonNull(player, "player");
        BigDecimal payable = payable(amount);
        return result(economy.depositPlayer(server.getOfflinePlayer(player), payable.doubleValue()));
    }

    @Override
    public String probe() {
        int digits = economy.fractionalDigits();
        int places = scale();
        return economy.getName() + " through Vault, " + places + " decimal places"
            + (digits == places ? "" : " (the economy reports " + digits + ")");
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
        Objects.requireNonNull(player, "player");
        return amount(economy.getBalance(server.getOfflinePlayer(player)));
    }

    private static BigDecimal amount(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalStateException("the economy reported a balance of " + value);
        }
        return BigDecimal.valueOf(value);
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
