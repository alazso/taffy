package so.alaz.taffy.hooks;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.platform.Scheduler;
import so.alaz.taffy.platform.ThreadTag;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * An economy to read balances from, charge and pay. Every call is synchronous and runs on the thread
 * {@link #thread(UUID)} names for the player. A withdraw or deposit rounds its amount half-even to {@link #scale()}
 * before it reaches the economy, and refuses with {@link IllegalArgumentException} an amount that is negative, rounds
 * to zero, or cannot reach the economy exactly. The PlayerPoints economy, which holds whole points, refuses an amount
 * with a fraction of a point as one that cannot reach it exactly and rounds none.
 * {@link #has(UUID, BigDecimal)} accepts any amount. A withdraw or
 * deposit that throws has an unknown outcome: money may or may not have moved. A {@code null} argument throws
 * {@link NullPointerException}. An implementation may refuse a call made off the thread {@link #thread(UUID)} names
 * with {@link IllegalStateException}, before anything in the economy is called, as the Vault economy does.
 */
public interface EconomyProvider {

    /** The largest value {@link #scale()} returns; amounts are never sent with more decimal places. */
    int MAX_SCALE = 4;

    /**
     * Looks up the economy registered with Vault. The plugin that calls this lists Vault in its
     * {@code paper-plugin.yml} with {@code load: BEFORE} and {@code required: false}, so the Vault API is visible to
     * it and Vault is enabled first when it is installed. Vault itself need not be enabled: only the API and the
     * registration are read. A lookup reads the registration once and calls nothing in the economy: no balance read,
     * no probe and no metadata call runs. The Vault classes are loaded only when the Vault API is visible to the
     * plugin. Vault carries amounts as {@code double}, so the provider refuses an amount that a {@code double} cannot
     * carry exactly.
     *
     * <p>A balance read from Vault is rounded half-even to 8 decimal places, four more than {@value #MAX_SCALE},
     * before it is compared or rounded to {@link #scale()}, and a balance within 16 units in the last place of the
     * {@code double} of an amount at {@link #scale()} reads as that amount. A stored {@code 0.09999999999999998},
     * which is {@code 1.0 - 0.9}, covers an amount of {@code 0.10}, and a stored {@code 1000000428.0799999}, which
     * is {@code 1000000456.18 - 28.10}, covers {@code 1000000428.08}, while a stored {@code 0.0999} does not cover
     * {@code 0.10}. The balance read after a withdraw is measured in units of the balance read before it. Where one
     * such unit nears the smallest amount at {@link #scale()}, which is from about 1e13 at 2 decimal places and
     * from about 1e11 at 4, a {@code double} no longer holds the amount and a comparison can be off by one unit of
     * {@link #scale()}. A withdraw reads the balance a second time once the
     * economy confirmed it, to tell {@link EconomyResult.Status#OVERDRAWN}. When that read throws an exception or a
     * {@link LinkageError}, the result is {@link EconomyResult.Status#OK}, the overdraft check is skipped and a
     * warning is logged; any other {@link Error} propagates.
     *
     * <p>The provider keeps the plugin that registered the economy. While that plugin is disabled, a withdraw or
     * deposit returns {@link EconomyResult.Status#FAILED} with a message that names the plugin, before the amount is
     * checked and with no call into the economy, so no money moves. {@link #balance(UUID)} and
     * {@link #has(UUID, BigDecimal)} still read the economy, and the status stays as the lookup found it.
     *
     * <p>{@link #balance(UUID)}, {@link #has(UUID, BigDecimal)}, a withdraw and a deposit throw
     * {@link IllegalStateException} when the calling thread is not the one {@link #thread(UUID)} names for the
     * player, with a message that names {@code thread(player)} and {@code Scheduler.dispatch}. For
     * {@link ThreadTag#GLOBAL} the thread is the one {@code Server.isGlobalTickThread()} is {@code true} on, and for
     * an entity tag the one {@code Server.isOwnedByCurrentRegion(player)} is {@code true} on. Under
     * {@link VaultOptions.ThreadRule#ENTITY} a call for a player who is not online is checked against the global
     * region thread, as {@link #thread(UUID)} answers for that player. The check comes after the {@code null} checks
     * and before the amount is checked, the registering plugin's state is read or anything in the economy is called,
     * so no money moves. {@link #scale()}, {@link #thread(UUID)}, {@link #probe()} and the lookup are not checked.
     *
     * <p>The status's plugin is always {@code Vault}, and each status but {@link HookState#SWITCHED_OFF} and
     * {@link HookState#ON} carries a reason for the owner:
     *
     * <ul>
     *   <li>{@link HookState#SWITCHED_OFF} when {@code switchedOn} is {@code false}; nothing is read;</li>
     *   <li>{@link HookState#NOT_INSTALLED} when no Vault plugin is installed;</li>
     *   <li>{@link HookState#NOT_VISIBLE} when Vault holds the API but the plugin does not list it, with a
     *     warning;</li>
     *   <li>{@link HookState#API_MISSING} when the installed Vault lacks the economy API, with a warning;</li>
     *   <li>{@link HookState#NOT_REGISTERED} when Vault is installed and no economy has registered with it;</li>
     *   <li>{@link HookState#FAILED} when the lookup throws an exception or a {@link LinkageError}, which is logged
     *     with a warning and reported to {@code errors} under {@code hook.vault}; any other {@link Error}
     *     propagates;</li>
     *   <li>{@link HookState#ON} with the provider otherwise.</li>
     * </ul>
     *
     * <p>The status is fixed: it does not follow Vault's enabled state, and changes only through a new call. An
     * economy plugin that enables after the caller registers with Vault only then, so the caller looks the economy up
     * again through an {@link EconomyWatcher}, and once more on the first tick, and keeps the latest result in a
     * {@code volatile} field. Every call logs its own warning and makes its own report, so a Vault that stays
     * {@link HookState#NOT_VISIBLE}, {@link HookState#API_MISSING} or {@link HookState#FAILED} is warned about, and a
     * failed lookup reported, once for each call: at least twice at startup, at enable and on the first tick, and
     * once more for each economy that registers or leaves.
     *
     * @param owner the plugin that pays through the economy: its server's plugin manager and services manager are
     *     read, and its logger receives every warning
     * @param errors receives a lookup that failed
     * @param switchedOn whether the owner switched the economy on; {@code false} gives {@link HookState#SWITCHED_OFF}
     *     and reads nothing from the server
     * @param folia whether the server is Folia, which decides what each reason asks the owner to install
     * @param scale decimal places to use instead of the economy's own, from 0 to {@value #MAX_SCALE}, or {@code null}
     *     to use the economy's fractional digits, capped at {@value #MAX_SCALE}, or 2 when it reports none; a scale
     *     finer than the economy keeps is warned about on its first use
     * @return the lookup result, with a provider when the state is {@link HookState#ON}
     * @throws IllegalArgumentException if {@code scale} is outside 0 to {@value #MAX_SCALE}, checked before the switch
     * @throws NullPointerException if {@code owner} or {@code errors} is {@code null}
     */
    static Detected<EconomyProvider> vault(Plugin owner, ErrorReports errors, boolean switchedOn, boolean folia,
                                           @Nullable Integer scale) {
        return vault(VaultOptions.of(owner, errors, folia).switchedOn(switchedOn).scale(scale));
    }

    /**
     * Looks up the economy registered with Vault as {@link #vault(Plugin, ErrorReports, boolean, boolean, Integer)}
     * does, with the owner, the error reports, the switch, the server kind and the scale read from
     * {@code options}, and with the options that entry point has no argument for.
     *
     * <p>{@link VaultOptions#threadRule()} names the thread the economy is called on.
     * {@link VaultOptions.ThreadRule#GLOBAL}, which {@link VaultOptions#of(Plugin, ErrorReports, boolean)} gives
     * and the entry points without options use, makes {@link #thread(UUID)} answer {@link ThreadTag#GLOBAL} for every
     * player. {@link VaultOptions.ThreadRule#ENTITY} is for an economy plugin that is safe to call on region threads:
     * {@link #thread(UUID)} answers {@link ThreadTag#entity(UUID)} for a player who is online, so a plugin that is
     * already on that player's region thread calls the economy inline, and {@link ThreadTag#GLOBAL} for a player who
     * is not online. Under it the calls for one player are ordered on that player's thread and, on Folia, are not
     * ordered against another plugin that moves the same account on the global region thread.
     *
     * <p>{@link VaultOptions#fundsCheck()} names who decides whether a balance covers an amount.
     * {@link VaultOptions.FundsCheck#EMULATED}, which {@link VaultOptions#of(Plugin, ErrorReports, boolean)} gives
     * and the entry points without options use, is the check {@link #has(UUID, BigDecimal)} and
     * {@link #withdraw(UUID, BigDecimal, BooleanSupplier)} describe: the provider compares the balance it reads with
     * the amount and never calls Vault's {@code Economy.has}. Under {@link VaultOptions.FundsCheck#NATIVE},
     * {@link #has(UUID, BigDecimal)} answers what {@code Economy.has} answers, and a withdraw reads no balance and
     * lets {@code withdrawPlayer} decide, so an overdraft or a loan the economy allows goes through. A withdraw the
     * economy refuses then gives {@link EconomyResult.Status#FAILED} with the economy's message, for a short balance
     * too, since a Vault response has no type for insufficient funds, and a withdraw never gives
     * {@link EconomyResult.Status#INSUFFICIENT} or {@link EconomyResult.Status#OVERDRAWN}.
     *
     * @param options what the lookup reads
     * @return the lookup result, with a provider when the state is {@link HookState#ON}
     * @throws NullPointerException if {@code options} is {@code null}
     */
    static Detected<EconomyProvider> vault(VaultOptions options) {
        return EconomyLookup.vault(options);
    }

    /**
     * Looks up the economy registered with Vault as {@link #vault(VaultOptions)} does, and warns and reports only
     * when the status changed since the lookup before. A plugin looks the economy up at enable, on the first tick
     * and on each {@link EconomyWatcher} event, and passes the result it holds to the next lookup, so a Vault that
     * stays {@link HookState#NOT_VISIBLE}, {@link HookState#API_MISSING} or {@link HookState#FAILED} is warned
     * about, and a failed lookup reported, once and not once for each lookup.
     *
     * <p>When the status of this lookup equals {@code previous.status()}, in its plugin, state, version and reason,
     * no warning is logged and nothing is reported to the error reports of {@code options}. The reason of a
     * {@link HookState#FAILED} status names what was thrown, so a lookup that fails in another way is warned about
     * and reported again. Any other status, and every status when {@code previous} is {@code null}, is warned about
     * and reported as {@link #vault(VaultOptions)} does. The result is the same in both cases.
     *
     * @param options what the lookup reads
     * @param previous the result of the lookup before this one, or {@code null} for the first lookup
     * @return the lookup result, with a provider when the state is {@link HookState#ON}
     * @throws NullPointerException if {@code options} is {@code null}
     */
    static Detected<EconomyProvider> vault(VaultOptions options, @Nullable Detected<EconomyProvider> previous) {
        return EconomyLookup.vault(options, previous);
    }

    /**
     * Looks up the economy registered with Vault and keeps looking it up as economies register and leave. The
     * lookup of {@link #vault(VaultOptions)} runs before this returns. While {@code options} are switched on, an
     * {@link EconomyWatcher} is registered for the owner, which looks the economy up again on each event unless the
     * server is stopping, and one more lookup runs on the first tick through {@link Scheduler#global}; when the
     * scheduler refuses that task, a warning is logged. Each of these lookups takes the result before it, as
     * {@link #vault(VaultOptions, Detected)} does, so a status is warned about and a failed lookup reported once,
     * and again only after the status changed. Options that are switched off register nothing and schedule nothing.
     *
     * <p>The plugin calls this in {@code onEnable}, reads {@link VaultWatch#current()} wherever it needs the
     * economy, and closes the watch in {@code onDisable} or before it starts another.
     *
     * @param options what each lookup reads
     * @param scheduler runs the lookup of the first tick
     * @return the watch, whose {@link VaultWatch#current()} gives the latest lookup result
     * @throws NullPointerException if {@code options} or {@code scheduler} is {@code null}
     */
    static VaultWatch watchVault(VaultOptions options, Scheduler scheduler) {
        return VaultWatch.start(options, scheduler);
    }

    /**
     * Detects PlayerPoints and builds a provider on its points. The plugin that calls this lists PlayerPoints in its
     * {@code paper-plugin.yml} with {@code load: BEFORE} and {@code required: false}, so PlayerPoints is enabled
     * first when it is installed. PlayerPoints is read only when it is switched on, installed, enabled and its API
     * classes are visible, and it is {@link HookState#FAILED} when a method the provider calls is missing; otherwise
     * the result carries the state that stopped it and no provider. A PlayerPoints that is not visible or lacks the
     * API is warned about; one that could not be hooked is warned about and reported to {@code errors} under
     * {@code hook.playerpoints}. Detection calls nothing that reads or moves points. Unlike the Vault economy, the
     * result is bound to the PlayerPoints plugin: its status reads {@link HookState#NOT_ENABLED} while PlayerPoints
     * is disabled.
     *
     * <p>Points are whole and PlayerPoints holds them as {@code int}, so {@link #scale()} is 0. A withdraw or
     * deposit is never rounded: an amount with a fraction of a point, such as {@code 2.5}, and one above
     * {@link Integer#MAX_VALUE} cannot reach the economy exactly and throw {@link IllegalArgumentException}, as an
     * amount that is negative or zero does, before anything in PlayerPoints is called. An amount written with
     * decimal zeros, such as {@code 2.00}, is whole. {@link #has(UUID, BigDecimal)} accepts any amount and compares
     * the balance with the amount rounded half-even to whole points.
     *
     * <p>{@link #thread(UUID)} answers {@link ThreadTag#ASYNC} for every player. PlayerPoints loads a balance that
     * is not in its cache from its database on the calling thread, and it supports Folia. {@link #balance(UUID)},
     * {@link #has(UUID, BigDecimal)}, a withdraw and a deposit throw {@link IllegalStateException} on a thread
     * {@code Server.isPrimaryThread()} is {@code true} on, which is the main thread on Paper and every region
     * thread and the global region thread on Folia, with a message that names {@code thread(player)} and
     * {@code Scheduler.dispatch}. The check comes after the {@code null} checks and before the amount is checked or
     * anything in PlayerPoints is called, so no points move. {@link #scale()}, {@link #thread(UUID)},
     * {@link #probe()} and detection are not checked.
     *
     * <p>A withdraw reads the balance, gives {@link EconomyResult.Status#INSUFFICIENT} when it is short, runs
     * {@code start}, and takes the points. The withdraws of this provider for one player run one at a time through
     * these steps, and {@code start} runs while the others for that player wait, so it must not wait for a
     * withdraw of another player. A change another plugin makes to the same account is not held back. A
     * take or a give that PlayerPoints refuses, because a listener cancelled its {@code PlayerPointsChangeEvent},
     * the balance no longer covers the amount, or a deposit would pass {@link Integer#MAX_VALUE}, gives
     * {@link EconomyResult.Status#FAILED} with the message {@code PlayerPoints refused it}, and no points move:
     * PlayerPoints moves the whole amount or nothing. A balance that cannot be read at the start of a withdraw
     * gives {@link EconomyResult.Status#FAILED} too. A withdraw never gives
     * {@link EconomyResult.Status#OVERDRAWN} or {@link EconomyResult.Status#UNSUPPORTED}. A listener that changes
     * the amount of the event changes how many points move, which the provider cannot see, and PlayerPoints
     * answers a balance it could not load as 0.
     *
     * <p>While PlayerPoints is disabled, a withdraw or deposit returns {@link EconomyResult.Status#FAILED} with the
     * message {@code the economy plugin PlayerPoints is disabled}, before the amount is checked and with no call
     * into PlayerPoints, and {@link #probe()} throws {@link IllegalStateException}.
     *
     * @param owner the plugin that pays in points: its server's plugin manager is searched and its logger receives
     *     every warning
     * @param errors receives a failed detection
     * @param switchedOn whether the owner switched PlayerPoints on; {@code false} gives
     *     {@link HookState#SWITCHED_OFF} and looks at nothing
     * @return the detection result, with a provider when detection found PlayerPoints {@link HookState#ON}; its
     *     status is read when asked
     * @throws NullPointerException if {@code owner} or {@code errors} is {@code null}
     */
    static Detected<EconomyProvider> playerPoints(Plugin owner, ErrorReports errors, boolean switchedOn) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(errors, "errors");
        Logger logger = owner.getLogger();
        Server server = owner.getServer();
        return Detection.run("PlayerPoints", PlayerPointsEconomy.API, switchedOn,
            new PluginPresence(server.getPluginManager(), logger),
            plugin -> PlayerPointsEconomy.create(plugin, server), logger, errors);
    }

    /**
     * Looks the economy up as {@link #vault(Plugin, ErrorReports, boolean, boolean, Integer)} does, with the server
     * kind given as a {@link Scheduler.Mode}.
     *
     * @param owner the plugin that pays through the economy
     * @param errors receives a lookup that failed
     * @param switchedOn whether the owner switched the economy on
     * @param mode the server kind
     * @param scale decimal places to use instead of the economy's own, or {@code null}
     * @return the lookup result, with a provider when the state is {@link HookState#ON}
     * @throws IllegalArgumentException if {@code scale} is outside 0 to {@value #MAX_SCALE}
     * @throws NullPointerException if {@code owner}, {@code errors} or {@code mode} is {@code null}
     * @deprecated use {@link #vault(Plugin, ErrorReports, boolean, boolean, Integer)} with
     *     {@code mode == Scheduler.Mode.FOLIA}
     */
    @Deprecated
    static Detected<EconomyProvider> vault(Plugin owner, ErrorReports errors, boolean switchedOn, Scheduler.Mode mode,
                                           @Nullable Integer scale) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(errors, "errors");
        Objects.requireNonNull(mode, "mode");
        return vault(owner, errors, switchedOn, mode == Scheduler.Mode.FOLIA, scale);
    }

    /**
     * Decimal places, from 0 to {@value #MAX_SCALE}.
     *
     * @return the scale
     */
    int scale();

    /**
     * The thread to call this economy on for a player. The answer may change between calls: the Vault economy under
     * {@link VaultOptions.ThreadRule#ENTITY} answers {@link ThreadTag#entity(UUID)} while the player is online and
     * {@link ThreadTag#GLOBAL} while the player is not.
     *
     * @param player the player
     * @return the thread
     */
    ThreadTag thread(UUID player);

    /**
     * The player's balance, rounded half-even to {@link #scale()}.
     *
     * @param player the player
     * @return the balance, which may be negative
     * @throws IllegalStateException if the economy reports a balance that is not a finite number
     */
    BigDecimal balance(UUID player);

    /**
     * Whether the player's balance covers an amount, as a withdraw checks it: the balance as the provider reads it,
     * not rounded to {@link #scale()}, is compared with the amount rounded half-even to {@link #scale()}, so a
     * balance short of the amount by less than one unit of {@link #scale()} does not cover it. Unlike a withdraw, any amount is accepted: one that is negative or rounds to zero is
     * covered by every balance of zero or more.
     *
     * <p>This default compares {@link #balance(UUID)} with the rounded amount. An implementation whose economy keeps
     * more decimal places than {@link #scale()} overrides it to compare the economy's own balance.
     *
     * @param player the player
     * @param amount the amount
     * @return {@code true} if the balance is at least the rounded amount
     * @throws IllegalStateException if the economy reports a balance that is not a finite number
     */
    default boolean has(UUID player, BigDecimal amount) {
        return balance(player).compareTo(amount.setScale(scale(), RoundingMode.HALF_EVEN)) >= 0;
    }

    /**
     * Takes money from a player when the balance covers it, as {@link #has(UUID, BigDecimal)} checks it.
     *
     * @param player the player
     * @param amount a positive amount
     * @return what happened; never {@link EconomyResult.Status#NOT_STARTED}
     * @throws IllegalArgumentException if the amount is negative, rounds to zero, or cannot reach the economy exactly
     */
    default EconomyResult withdraw(UUID player, BigDecimal amount) {
        return withdraw(player, amount, () -> true);
    }

    /**
     * Takes money from a player when the balance covers it. {@code start} runs after the balance check, when the
     * provider makes one, and before the money moves, on the same thread; when it returns {@code false} no money
     * moves.
     *
     * @param player the player
     * @param amount a positive amount
     * @param start decides, once the balance covers the amount, whether the withdraw goes ahead
     * @return what happened; {@link EconomyResult.Status#NOT_STARTED} when {@code start} returned {@code false}
     * @throws IllegalArgumentException if the amount is negative, rounds to zero, or cannot reach the economy exactly
     */
    EconomyResult withdraw(UUID player, BigDecimal amount, BooleanSupplier start);

    /**
     * Gives money to a player.
     *
     * @param player the player
     * @param amount a positive amount
     * @return what happened
     * @throws IllegalArgumentException if the amount is negative, rounds to zero, or cannot reach the economy exactly
     */
    EconomyResult deposit(UUID player, BigDecimal amount);

    /**
     * A self-test that reads what the economy reports about itself and moves no money.
     *
     * @return one line naming what was reached
     * @throws RuntimeException if nothing was reached
     */
    String probe();
}
