package so.alaz.taffy.hooks;

import org.bukkit.Server;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ThreadTag;
import so.alaz.taffy.platform.ThrowLog;
import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.api.currency.operation.OperationResult;
import su.nightexpress.excellenteconomy.api.event.ChangeBalanceEvent;
import su.nightexpress.excellenteconomy.user.CoinsUser;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

// One currency of ExcellentEconomy 2, the plugin CoinsEngine was renamed to. Its calls that name a UUID load the data
// of a player who is not online from its database on a ForkJoinPool thread and complete a future there, so every call
// waits for that future on an async thread. A balance is a double that is never below zero: a removal stops at zero,
// and an addition has no upper limit. A change fires a ChangeBalanceEvent after the balance changed and before the
// operation answers, a cancelled event puts the old balance back, and the operation still answers SUCCESS, so each
// move reads what it did from the events heard for the player while it ran. The moves of this provider for one player
// hold one of LOCKS from the first read to the last. Loaded only after ExcellentEconomy was found installed, enabled
// and with its API and nightcore present.
final class CoinsEngineEconomy implements EconomyProvider, Listener {

    static final String REFUSED = "ExcellentEconomy refused it";

    static final String UNCHANGED = "ExcellentEconomy left the balance unchanged";

    private static final String SOURCE = "hook.excellenteconomy";

    private static final int LOCKS = 64;

    private final Server server;
    private final Plugin owner;
    private final Plugin plugin;
    private final ExcellentEconomyAPI api;
    private final String currency;
    private final int scale;
    private final ThrowLog throwLog;
    private final Object[] locks = new Object[LOCKS];
    private final Map<UUID, List<Change>> watched = new ConcurrentHashMap<>();
    private boolean listening;

    // owner is the plugin that pays, which the listener is registered for, and plugin is ExcellentEconomy.
    CoinsEngineEconomy(Server server, Plugin owner, Plugin plugin, ExcellentEconomyAPI api, String currency,
                       int scale, Logger logger) {
        this.server = server;
        this.owner = owner;
        this.plugin = plugin;
        this.api = api;
        this.currency = currency;
        this.scale = scale;
        this.throwLog = new ThrowLog(logger);
        for (int i = 0; i < LOCKS; i++) {
            locks[i] = new Object();
        }
    }

    // One balance change of the currency, as its ChangeBalanceEvent gave it.
    private record Change(double before, double after, boolean cancelled) {
    }

    // Checks every method the provider calls and reads the API from the services manager, where ExcellentEconomy
    // registers it while it is enabled.
    static ExcellentEconomyAPI api(Server server) {
        ApiLink.instanceMethod(ExcellentEconomyAPI.class, "hasCurrency", boolean.class, String.class);
        ApiLink.instanceMethod(ExcellentEconomyAPI.class, "getCurrency", ExcellentCurrency.class, String.class);
        ApiLink.instanceMethod(ExcellentEconomyAPI.class, "getCurrencies", Set.class);
        ApiLink.instanceMethod(ExcellentEconomyAPI.class, "getBalanceAsync", CompletableFuture.class, UUID.class,
            String.class);
        ApiLink.instanceMethod(ExcellentEconomyAPI.class, "withdrawAsync", CompletableFuture.class, UUID.class,
            String.class, double.class);
        ApiLink.instanceMethod(ExcellentEconomyAPI.class, "depositAsync", CompletableFuture.class, UUID.class,
            String.class, double.class);
        ApiLink.instanceMethod(ExcellentEconomyAPI.class, "setBalanceAsync", CompletableFuture.class, UUID.class,
            String.class, double.class);
        ApiLink.instanceMethod(ExcellentCurrency.class, "getId", String.class);
        ApiLink.instanceMethod(ExcellentCurrency.class, "getName", String.class);
        ApiLink.instanceMethod(ExcellentCurrency.class, "getSymbol", String.class);
        ApiLink.instanceMethod(ExcellentCurrency.class, "format", String.class, double.class);
        ApiLink.instanceMethod(ExcellentCurrency.class, "isDecimal", boolean.class);
        ApiLink.instanceMethod(ExcellentCurrency.class, "isUnderLimit", boolean.class, double.class);
        ApiLink.instanceMethod(OperationResult.class, "success", boolean.class);
        ApiLink.instanceMethod(ChangeBalanceEvent.class, "getUser", CoinsUser.class);
        ApiLink.instanceMethod(ChangeBalanceEvent.class, "getCurrency", ExcellentCurrency.class);
        ApiLink.instanceMethod(ChangeBalanceEvent.class, "getOldAmount", double.class);
        ApiLink.instanceMethod(ChangeBalanceEvent.class, "getNewAmount", double.class);
        ApiLink.instanceMethod(ChangeBalanceEvent.class, "isCancelled", boolean.class);
        ApiLink.instanceMethod(CoinsUser.class, "getId", UUID.class);
        return Objects.requireNonNull(server.getServicesManager().load(ExcellentEconomyAPI.class),
            "ExcellentEconomy registered no API");
    }

    // The provider, or the ids of the currencies ExcellentEconomy has when it has none of that id. A currency that
    // holds decimals has a scale of 2, and one that holds whole units a scale of 0.
    static CoinsEngineLookup.Found find(Plugin owner, Plugin plugin, Server server, String currency,
                                        Logger logger) {
        ExcellentEconomyAPI api = api(server);
        ExcellentCurrency found = api.hasCurrency(currency) ? api.getCurrency(currency) : null;
        if (found == null) {
            return new CoinsEngineLookup.Found(null, currencies(api).stream().map(EconomyCurrency::id).toList());
        }
        return new CoinsEngineLookup.Found(
            new CoinsEngineEconomy(server, owner, plugin, api, currency, found.isDecimal() ? 2 : 0, logger),
            List.of());
    }

    static List<EconomyCurrency> currencies(Server server) {
        return currencies(api(server));
    }

    static List<EconomyCurrency> currencies(ExcellentEconomyAPI api) {
        List<EconomyCurrency> found = new ArrayList<>();
        Set<ExcellentCurrency> registered = api.getCurrencies();
        if (registered != null) {
            for (ExcellentCurrency each : registered) {
                String id = each.getId();
                if (id != null && !id.isBlank()) {
                    found.add(new EconomyCurrency(id, each.getName(), each.getSymbol()));
                }
            }
        }
        return List.copyOf(found);
    }

    @Override
    public int scale() {
        return scale;
    }

    @Override
    public ThreadTag thread(UUID player) {
        return ThreadTag.ASYNC;
    }

    @Override
    public BigDecimal balance(UUID player) {
        Objects.requireNonNull(player, "player");
        offTickThread(player);
        return Emulated.rounded(unrounded(player), scale);
    }

    @Override
    public boolean has(UUID player, BigDecimal amount) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(amount, "amount");
        offTickThread(player);
        return unrounded(player).compareTo(amount.setScale(scale, RoundingMode.HALF_EVEN)) >= 0;
    }

    // Runs on the thread ExcellentEconomy changes the balance on, while the thread that asked for the move waits.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChange(ChangeBalanceEvent event) {
        List<Change> heard = watched.get(event.getUser().getId());
        if (heard != null && currency.equalsIgnoreCase(event.getCurrency().getId())) {
            heard.add(new Change(event.getOldAmount(), event.getNewAmount(), event.isCancelled()));
        }
    }

    @Override
    public EconomyResult withdraw(UUID player, BigDecimal amount, BooleanSupplier start) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(start, "start");
        offTickThread(player);
        if (!plugin.isEnabled()) {
            return disabled();
        }
        BigDecimal payable = DoubleMoney.payable(amount, scale);
        EconomyResult stopped = unreachable();
        if (stopped != null) {
            return stopped;
        }
        synchronized (locks[Math.floorMod(player.hashCode(), LOCKS)]) {
            double before;
            try {
                before = held(player);
            } catch (RuntimeException | LinkageError unreadable) {
                return unreadable(unreadable);
            }
            if (DoubleMoney.amount(before, scale, before).compareTo(payable) < 0) {
                return EconomyResult.of(EconomyResult.Status.INSUFFICIENT);
            }
            if (!start.getAsBoolean()) {
                return EconomyResult.of(EconomyResult.Status.NOT_STARTED);
            }
            List<Change> heard = heard(player, () -> api.withdrawAsync(player, currency, payable.doubleValue()));
            if (heard == null) {
                return EconomyResult.failed(REFUSED);
            }
            BigDecimal taken = moved(heard, "withdraw", (held, left) -> {
                BigDecimal gone = held.subtract(left);
                boolean stoppedAtZero = left.signum() == 0 && held.compareTo(payable) < 0;
                return gone.compareTo(payable) == 0 || stoppedAtZero ? gone : null;
            });
            if (taken == null) {
                return EconomyResult.failed(UNCHANGED);
            }
            if (taken.compareTo(payable) == 0) {
                return EconomyResult.ok();
            }
            giveBack(player, taken, payable);
            return EconomyResult.of(EconomyResult.Status.INSUFFICIENT);
        }
    }

    @Override
    public EconomyResult deposit(UUID player, BigDecimal amount) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(amount, "amount");
        offTickThread(player);
        if (!plugin.isEnabled()) {
            return disabled();
        }
        BigDecimal payable = DoubleMoney.payable(amount, scale);
        EconomyResult stopped = unreachable();
        if (stopped != null) {
            return stopped;
        }
        synchronized (locks[Math.floorMod(player.hashCode(), LOCKS)]) {
            List<Change> heard = heard(player, () -> api.depositAsync(player, currency, payable.doubleValue()));
            if (heard == null) {
                return EconomyResult.failed(REFUSED);
            }
            return moved(heard, "deposit", added(payable)) == null
                ? EconomyResult.failed(UNCHANGED) : EconomyResult.ok();
        }
    }

    @Override
    public EconomyResult setBalance(UUID player, BigDecimal amount) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(amount, "amount");
        offTickThread(player);
        if (!plugin.isEnabled()) {
            return disabled();
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("a balance is zero or more, not " + amount);
        }
        BigDecimal wanted = DoubleMoney.exact(amount, amount.setScale(scale, RoundingMode.HALF_EVEN));
        ExcellentCurrency found = api.hasCurrency(currency) ? api.getCurrency(currency) : null;
        if (found == null) {
            return unknown();
        }
        double sent = wanted.doubleValue();
        if (!found.isUnderLimit(sent)) {
            return EconomyResult.failed(wanted.toPlainString() + " is above the limit ExcellentEconomy sets for "
                + currency);
        }
        if (!listening()) {
            return deaf();
        }
        synchronized (locks[Math.floorMod(player.hashCode(), LOCKS)]) {
            List<Change> heard = heard(player, () -> api.setBalanceAsync(player, currency, sent));
            if (heard == null) {
                return EconomyResult.failed(REFUSED);
            }
            return moved(heard, "balance set", (held, left) -> left.compareTo(wanted) == 0 ? wanted : null) == null
                ? EconomyResult.failed(UNCHANGED) : EconomyResult.ok();
        }
    }

    // ExcellentEconomy has one name for a currency.
    @Override
    public @Nullable String currencyNameSingular() {
        return told("getName", ExcellentCurrency::getName);
    }

    @Override
    public @Nullable String currencyNamePlural() {
        return told("getName", ExcellentCurrency::getName);
    }

    @Override
    public @Nullable String currencySymbol() {
        return told("getSymbol", ExcellentCurrency::getSymbol);
    }

    @Override
    public @Nullable String format(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        double sent = amount.setScale(scale, RoundingMode.HALF_EVEN).doubleValue();
        if (!Double.isFinite(sent)) {
            throw new IllegalArgumentException(amount + " is beyond the range of a double");
        }
        return told("format", found -> found.format(sent));
    }

    @Override
    public String probe() {
        if (!plugin.isEnabled()) {
            throw new IllegalStateException("ExcellentEconomy is disabled");
        }
        return "ExcellentEconomy " + plugin.getPluginMeta().getVersion() + ", currency " + currency + ", " + scale
            + " decimal places";
    }

    // Server.isPrimaryThread is true on the main thread on Paper and on every region thread and the global region
    // thread on Folia.
    private void offTickThread(UUID player) {
        if (server.isPrimaryThread()) {
            throw new IllegalStateException("an economy call for " + player + " runs on the thread thread(player)"
                + " names, " + ThreadTag.ASYNC + ", not on " + Thread.currentThread().getName()
                + "; run it through Scheduler.dispatch(economy.thread(player), ...)");
        }
    }

    private BigDecimal unrounded(UUID player) {
        double read = held(player);
        return DoubleMoney.amount(read, scale, read);
    }

    private double held(UUID player) {
        CompletableFuture<Double> reading = api.getBalanceAsync(player, currency);
        Double read = reading == null ? null : reading.join();
        if (read == null) {
            throw new IllegalStateException("ExcellentEconomy reported no balance");
        }
        if (!Double.isFinite(read)) {
            throw new IllegalStateException("the economy reported a balance of " + read);
        }
        return read;
    }

    // A missing answer throws, so its outcome counts as unknown.
    private static boolean done(@Nullable CompletableFuture<OperationResult> running) {
        OperationResult result = running == null ? null : running.join();
        if (result == null) {
            throw new IllegalStateException("ExcellentEconomy returned no result");
        }
        return result.success();
    }

    // The server registers a listener only for a plugin that is enabled, and keeps it through that plugin's
    // onDisable.
    private synchronized boolean listening() {
        if (!listening && owner.isEnabled()) {
            server.getPluginManager().registerEvents(this, owner);
            listening = true;
        }
        return listening;
    }

    // Runs one operation and gives the balance changes heard for the player while it ran, or null when
    // ExcellentEconomy refused it. The caller holds the player's lock, so one operation is heard at a time.
    private @Nullable List<Change> heard(UUID player, Supplier<@Nullable CompletableFuture<OperationResult>> run) {
        List<Change> heard = new CopyOnWriteArrayList<>();
        watched.put(player, heard);
        try {
            return done(run.get()) ? heard : null;
        } finally {
            watched.remove(player);
        }
    }

    private interface Shape {

        // The amount a change from held to left moved when the operation can have made it, else null.
        @Nullable BigDecimal moved(BigDecimal held, BigDecimal left);
    }

    private static Shape added(BigDecimal payable) {
        return (held, left) -> left.subtract(held).compareTo(payable) == 0 ? payable : null;
    }

    // The amount the operation moved, or null when a listener cancelled its change, which puts the old balance back.
    // Another plugin can change the same balance while the operation runs, so only a change of the operation's shape
    // counts, and no such change, or two with different outcomes, throws: the outcome is unknown.
    private @Nullable BigDecimal moved(List<Change> heard, String what, Shape shape) {
        boolean found = false;
        BigDecimal moved = null;
        for (Change change : heard) {
            BigDecimal held = DoubleMoney.amount(change.before(), scale, change.before());
            BigDecimal left = DoubleMoney.amount(change.after(), scale, change.before());
            BigDecimal shaped = shape.moved(held, left);
            if (shaped == null) {
                continue;
            }
            BigDecimal outcome = change.cancelled() && held.compareTo(left) != 0 ? null : shaped;
            if (found && (outcome == null ? moved != null : moved == null || outcome.compareTo(moved) != 0)) {
                throw new IllegalStateException("ExcellentEconomy answered the " + what + " with a success, and two"
                    + " balance changes that fit it were heard with different outcomes, so what it did is unknown");
            }
            found = true;
            moved = outcome;
        }
        if (!found) {
            throw new IllegalStateException("ExcellentEconomy answered the " + what + " with a success, and no"
                + " balance change that fits it was heard, so what it did is unknown");
        }
        return moved;
    }

    // Gives back what a withdraw took of a balance that no longer covered the amount.
    private void giveBack(UUID player, BigDecimal taken, BigDecimal payable) {
        if (taken.signum() == 0) {
            return;
        }
        List<Change> heard = heard(player, () -> api.depositAsync(player, currency, taken.doubleValue()));
        if (heard == null || moved(heard, "deposit", added(taken)) == null) {
            throw new IllegalStateException("ExcellentEconomy took " + taken.stripTrailingZeros().toPlainString()
                + " of " + payable.toPlainString() + " from a balance that no longer covered it, and did not give"
                + " it back");
        }
    }

    // The reason no money can move now, or null when ExcellentEconomy has the currency and its balance changes are
    // heard.
    private @Nullable EconomyResult unreachable() {
        if (!api.hasCurrency(currency)) {
            return unknown();
        }
        return listening() ? null : deaf();
    }

    private EconomyResult deaf() {
        return EconomyResult.failed("the balance changes of ExcellentEconomy cannot be heard while "
            + owner.getName() + " is not enabled");
    }

    private EconomyResult unknown() {
        return EconomyResult.failed("ExcellentEconomy has no currency named " + currency);
    }

    private static EconomyResult unreadable(Throwable failure) {
        String reason = failure.getMessage();
        return EconomyResult.failed("the balance could not be read: "
            + (reason == null ? failure.toString() : reason));
    }

    private static EconomyResult disabled() {
        return EconomyResult.failed("the economy plugin ExcellentEconomy is disabled");
    }

    private interface Told {

        @Nullable String by(ExcellentCurrency currency);
    }

    // What ExcellentEconomy says about the currency, or null for a null or blank answer, for a currency it no longer
    // has and for a call that throws.
    private @Nullable String told(String call, Told read) {
        String text;
        try {
            ExcellentCurrency found = api.getCurrency(currency);
            text = found == null ? null : read.by(found);
        } catch (RuntimeException | LinkageError failure) {
            throwLog.warn(SOURCE + "." + call, "ExcellentEconomy threw in " + call + ", so it is read as having none ("
                + failure + ").", failure);
            return null;
        }
        return text == null || text.isBlank() ? null : text;
    }
}
