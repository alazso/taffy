package so.alaz.taffy.hooks;

import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import so.alaz.taffy.platform.ThreadTag;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

// PlayerPoints 3 points, which are whole and held as int. look loads a balance that is not cached from the database
// on the calling thread, and give and take fire a PlayerPointsChangeEvent that is asynchronous off a tick thread, so
// every call runs on an async thread. give and take check the balance and queue the change in two steps, so a
// withdraw holds one of LOCKS for its player from its balance read to its take. Loaded only after PlayerPoints was
// found installed, enabled and with its API present.
final class PlayerPointsEconomy implements EconomyProvider {

    static final String API = "org.black_ixx.playerpoints.PlayerPointsAPI";

    static final String REFUSED = "PlayerPoints refused it";

    private static final int LOCKS = 64;

    private final Server server;
    private final Plugin plugin;
    private final PlayerPointsAPI api;
    private final Object[] locks = new Object[LOCKS];

    PlayerPointsEconomy(Server server, Plugin plugin, PlayerPointsAPI api) {
        this.server = server;
        this.plugin = plugin;
        this.api = api;
        for (int i = 0; i < LOCKS; i++) {
            locks[i] = new Object();
        }
    }

    static EconomyProvider create(Plugin plugin, Server server) {
        ApiLink.instanceMethod(PlayerPoints.class, "getAPI", PlayerPointsAPI.class);
        ApiLink.instanceMethod(PlayerPointsAPI.class, "look", int.class, UUID.class);
        ApiLink.instanceMethod(PlayerPointsAPI.class, "give", boolean.class, UUID.class, int.class);
        ApiLink.instanceMethod(PlayerPointsAPI.class, "take", boolean.class, UUID.class, int.class);
        PlayerPointsAPI api = Objects.requireNonNull(((PlayerPoints) plugin).getAPI(), "PlayerPoints gave no API");
        return new PlayerPointsEconomy(server, plugin, api);
    }

    @Override
    public int scale() {
        return 0;
    }

    @Override
    public ThreadTag thread(UUID player) {
        return ThreadTag.ASYNC;
    }

    @Override
    public BigDecimal balance(UUID player) {
        Objects.requireNonNull(player, "player");
        offTickThread(player);
        return BigDecimal.valueOf(api.look(player));
    }

    @Override
    public boolean has(UUID player, BigDecimal amount) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(amount, "amount");
        offTickThread(player);
        return BigDecimal.valueOf(api.look(player)).compareTo(amount.setScale(0, RoundingMode.HALF_EVEN)) >= 0;
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
        int points = points(amount);
        synchronized (locks[Math.floorMod(player.hashCode(), LOCKS)]) {
            int held;
            try {
                held = api.look(player);
            } catch (RuntimeException | LinkageError unreadable) {
                String reason = unreadable.getMessage();
                return EconomyResult.failed("the balance could not be read: "
                    + (reason == null ? unreadable.toString() : reason));
            }
            if (held < points) {
                return EconomyResult.of(EconomyResult.Status.INSUFFICIENT);
            }
            if (!start.getAsBoolean()) {
                return EconomyResult.of(EconomyResult.Status.NOT_STARTED);
            }
            return api.take(player, points) ? EconomyResult.ok() : EconomyResult.failed(REFUSED);
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
        return api.give(player, points(amount)) ? EconomyResult.ok() : EconomyResult.failed(REFUSED);
    }

    @Override
    public String probe() {
        if (!plugin.isEnabled()) {
            throw new IllegalStateException("PlayerPoints is disabled");
        }
        return "PlayerPoints " + plugin.getPluginMeta().getVersion() + ", whole points";
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

    private static EconomyResult disabled() {
        return EconomyResult.failed("the economy plugin PlayerPoints is disabled");
    }

    private static int points(BigDecimal amount) {
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("an amount is positive, not " + amount);
        }
        if (amount.signum() == 0) {
            throw new IllegalArgumentException(amount + " is zero");
        }
        try {
            return amount.intValueExact();
        } catch (ArithmeticException inexact) {
            throw new IllegalArgumentException(amount + " cannot reach PlayerPoints exactly: an amount is a whole"
                + " number of points up to " + Integer.MAX_VALUE, inexact);
        }
    }
}
