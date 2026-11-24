package so.alaz.taffy.hooks;

import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ThreadTag;
import so.alaz.taffy.platform.ThrowLog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

// One currency of a VaultUnlocked 2 economy. The economy works in BigDecimal and names an account by UUID. Each call
// that names a currency also names a world, which is the server's first world. Loaded only when the VaultUnlocked
// economy API is present.
final class VaultUnlockedEconomy implements EconomyProvider {

    static final String SOURCE = "hook.vaultunlocked";

    private final Server server;
    private final Economy economy;
    private final Plugin registrant;
    private final String caller;
    private final String currency;
    private final @Nullable Integer configured;
    private final Links links;
    private final Logger logger;
    private final ThrowLog throwLog;
    private volatile int scale = -1;

    // caller is the name of the plugin that pays, which VaultUnlocked passes to the economy with every call.
    VaultUnlockedEconomy(Server server, Economy economy, Plugin registrant, String caller, String currency,
                         @Nullable Integer scale, Links links, Logger logger) {
        this.server = server;
        this.economy = economy;
        this.registrant = registrant;
        this.caller = caller;
        this.currency = currency;
        this.configured = scale;
        this.links = links;
        this.logger = logger;
        this.throwLog = new ThrowLog(logger);
    }

    // The methods of Economy that VaultUnlocked added after its currencies and that the installed one may lack: set,
    // and fractionalDigits for a currency.
    record Links(boolean sets, boolean currencyDigits) {
    }

    // What a lookup found: the provider, or the ids of the currencies the economy has when it lacks the one asked
    // for.
    record Found(@Nullable EconomyProvider provider, String economy, List<String> currencies) {
    }

    // Reads the services manager and asks the economy for its currencies. Gives null when no economy is registered.
    static @Nullable Found lookup(Server server, String caller, String currency, @Nullable Integer scale,
                                  Logger logger) {
        RegisteredServiceProvider<Economy> registration = server.getServicesManager().getRegistration(Economy.class);
        if (registration == null) {
            return null;
        }
        Links links = linked();
        Economy economy = registration.getProvider();
        String name = String.valueOf(economy.getName());
        if (!economy.hasCurrency(currency)) {
            return new Found(null, name, ids(economy));
        }
        return new Found(new VaultUnlockedEconomy(server, economy, registration.getPlugin(), caller, currency, scale,
            links, logger), name, List.of());
    }

    // The currencies of the registered economy, or an empty list when none is registered. Only the default
    // currency has a name in the VaultUnlocked API, and no currency has a symbol.
    static List<EconomyCurrency> currencies(Server server, String caller) {
        RegisteredServiceProvider<Economy> registration = server.getServicesManager().getRegistration(Economy.class);
        if (registration == null) {
            return List.of();
        }
        Economy economy = registration.getProvider();
        String standard = economy.getDefaultCurrency(caller);
        List<EconomyCurrency> found = new ArrayList<>();
        for (String id : ids(economy)) {
            String name = id.equals(standard) ? text(economy.defaultCurrencyNameSingular(caller)) : null;
            found.add(new EconomyCurrency(id, name, null));
        }
        return List.copyOf(found);
    }

    // Throws a NoSuchMethodError when the installed VaultUnlocked lacks a method the provider calls, other than the
    // two of Links.
    private static Links linked() {
        Class<?> text = String.class;
        ApiLink.instanceMethod(Economy.class, "getName", text);
        ApiLink.instanceMethod(Economy.class, "hasCurrency", boolean.class, text);
        ApiLink.instanceMethod(Economy.class, "currencies", Collection.class);
        ApiLink.instanceMethod(Economy.class, "getDefaultCurrency", text, text);
        ApiLink.instanceMethod(Economy.class, "defaultCurrencyNameSingular", text, text);
        ApiLink.instanceMethod(Economy.class, "defaultCurrencyNamePlural", text, text);
        ApiLink.instanceMethod(Economy.class, "fractionalDigits", int.class, text);
        ApiLink.instanceMethod(Economy.class, "format", text, text, BigDecimal.class, text);
        ApiLink.instanceMethod(Economy.class, "balance", BigDecimal.class, text, UUID.class, text, text);
        for (String move : List.of("withdraw", "deposit")) {
            ApiLink.instanceMethod(Economy.class, move, EconomyResponse.class, text, UUID.class, text, text,
                BigDecimal.class);
        }
        ApiLink.instanceMethod(EconomyResponse.class, "transactionSuccess", boolean.class);
        return new Links(
            linked("set", EconomyResponse.class, text, UUID.class, text, text, BigDecimal.class),
            linked("fractionalDigits", int.class, text, text));
    }

    private static boolean linked(String name, Class<?> returns, Class<?>... parameters) {
        try {
            ApiLink.instanceMethod(Economy.class, name, returns, parameters);
            return true;
        } catch (NoSuchMethodError missing) {
            return false;
        }
    }

    private static List<String> ids(Economy economy) {
        Collection<String> ids = economy.currencies();
        List<String> found = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                if (id != null && !id.isBlank()) {
                    found.add(id);
                }
            }
        }
        return found;
    }

    // Worked out on first use, never at lookup: the configured scale when set, else the currency's fractional digits
    // when 0 or more, else 2, capped at MAX_SCALE.
    @Override
    public int scale() {
        int known = scale;
        if (known >= 0) {
            return known;
        }
        synchronized (this) {
            if (scale < 0) {
                int digits = digits();
                if (configured == null) {
                    scale = digits >= 0 ? Math.min(digits, MAX_SCALE) : 2;
                } else {
                    if (digits >= 0 && configured > digits) {
                        logger.warning("scale " + configured + " is finer than the " + digits + " decimal places "
                            + economy.getName() + " keeps for " + currency + ", so the economy may round amounts sent"
                            + " to it.");
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
        Objects.requireNonNull(player, "player");
        onThread(player);
        return Emulated.rounded(held(player, world()), scale());
    }

    @Override
    public boolean has(UUID player, BigDecimal amount) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(amount, "amount");
        onThread(player);
        return held(player, world()).compareTo(amount.setScale(scale(), RoundingMode.HALF_EVEN)) >= 0;
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
        EconomyResult stopped = unreachable();
        if (stopped != null) {
            return stopped;
        }
        String world = world();
        return Emulated.withdraw(() -> held(player, world),
            taken -> result(economy.withdraw(caller, player, world, currency, taken)), payable, start,
            this::unchecked);
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
        EconomyResult stopped = unreachable();
        if (stopped != null) {
            return stopped;
        }
        return result(economy.deposit(caller, player, world(), currency, payable));
    }

    // Economy.set is a default method that reads the balance and withdraws or deposits the difference, unless the
    // economy overrides it, so the balance is read once more to tell whether it is the amount.
    @Override
    public EconomyResult setBalance(UUID player, BigDecimal amount) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(amount, "amount");
        onThread(player);
        if (!registrant.isEnabled()) {
            return disabled();
        }
        if (!links.sets()) {
            return EconomyResult.of(EconomyResult.Status.UNSUPPORTED);
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("a balance is zero or more, not " + amount);
        }
        BigDecimal wanted = amount.setScale(scale(), RoundingMode.HALF_EVEN);
        EconomyResult stopped = unreachable();
        if (stopped != null) {
            return stopped;
        }
        String world = world();
        EconomyResult result = result(economy.set(caller, player, world, currency, wanted));
        if (result.status() != EconomyResult.Status.OK) {
            return result;
        }
        BigDecimal after;
        try {
            after = held(player, world);
        } catch (RuntimeException | LinkageError unreadable) {
            throwLog.warn(SOURCE, "The balance could not be read after a balance set that went through, so it was"
                + " not checked against the amount (" + unreadable + ").", unreadable);
            return result;
        }
        if (after.compareTo(wanted) != 0) {
            throw new IllegalStateException("the economy " + economy.getName() + " answered the balance set with a"
                + " success and holds " + after.toPlainString() + ", not " + wanted.toPlainString());
        }
        return result;
    }

    @Override
    public @Nullable String currencyNameSingular() {
        return told("defaultCurrencyNameSingular",
            () -> standard() ? economy.defaultCurrencyNameSingular(caller) : null);
    }

    @Override
    public @Nullable String currencyNamePlural() {
        return told("defaultCurrencyNamePlural", () -> standard() ? economy.defaultCurrencyNamePlural(caller) : null);
    }

    @Override
    public @Nullable String format(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        return told("format",
            () -> economy.format(caller, amount.setScale(scale(), RoundingMode.HALF_EVEN), currency));
    }

    @Override
    public String probe() {
        int digits = digits();
        int places = scale();
        return economy.getName() + " through VaultUnlocked, currency " + currency + ", " + places + " decimal places"
            + (digits == places ? "" : " (the economy reports " + digits + ")");
    }

    // Server.isGlobalTickThread is true on the main thread on Paper and on the global region thread on Folia.
    private void onThread(UUID player) {
        if (!server.isGlobalTickThread()) {
            throw new IllegalStateException("an economy call for " + player + " runs on the thread thread(player)"
                + " names, " + ThreadTag.GLOBAL + ", not on " + Thread.currentThread().getName()
                + "; run it through Scheduler.dispatch(economy.thread(player), ...)");
        }
    }

    private EconomyResult disabled() {
        return EconomyResult.failed("the economy plugin " + registrant.getName() + " is disabled");
    }

    // The reason no money can move now, or null when the economy has the currency and a world is loaded.
    private @Nullable EconomyResult unreachable() {
        if (!economy.hasCurrency(currency)) {
            return EconomyResult.failed("the economy " + economy.getName() + " has no currency named " + currency);
        }
        if (server.getWorlds().isEmpty()) {
            return EconomyResult.failed("no world is loaded yet");
        }
        return null;
    }

    private String world() {
        List<World> worlds = server.getWorlds();
        if (worlds.isEmpty()) {
            throw new IllegalStateException("no world is loaded yet, and a VaultUnlocked balance is read in the"
                + " server's first world");
        }
        return worlds.getFirst().getName();
    }

    private BigDecimal held(UUID player, String world) {
        BigDecimal read = economy.balance(caller, player, world, currency);
        if (read == null) {
            throw new IllegalStateException("the economy reported no balance");
        }
        return read;
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
        return rounded;
    }

    private int digits() {
        return links.currencyDigits() ? economy.fractionalDigits(caller, currency) : economy.fractionalDigits(caller);
    }

    private boolean standard() {
        return currency.equals(economy.getDefaultCurrency(caller));
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
        return text(text);
    }

    private static @Nullable String text(@Nullable String text) {
        return text == null || text.isBlank() ? null : text;
    }

    private void unchecked(Throwable failure) {
        throwLog.warn(SOURCE, "The balance could not be read after a withdraw that went through, so it was not checked"
            + " for an overdraft (" + failure + ").", failure);
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
