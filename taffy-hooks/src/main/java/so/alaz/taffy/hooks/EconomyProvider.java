package so.alaz.taffy.hooks;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.platform.Scheduler;
import so.alaz.taffy.platform.ThreadTag;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * An economy to read balances from, charge and pay. Every call is synchronous and runs on the thread
 * {@link #thread(UUID)} names for the player. A withdraw or deposit rounds its amount half-even to {@link #scale()}
 * before it reaches the economy, and refuses with {@link IllegalArgumentException} an amount that is negative, rounds
 * to zero, or cannot reach the economy exactly. {@link #has(UUID, BigDecimal)} accepts any amount. A withdraw or
 * deposit that throws has an unknown outcome: money may or may not have moved. A {@code null} argument throws
 * {@link NullPointerException}.
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
     * @param mode the server kind, which decides what each reason asks the owner to install
     * @param scale decimal places to use instead of the economy's own, from 0 to {@value #MAX_SCALE}, or {@code null}
     *     to use the economy's fractional digits, capped at {@value #MAX_SCALE}, or 2 when it reports none; a scale
     *     finer than the economy keeps is warned about on its first use
     * @return the lookup result, with a provider when the state is {@link HookState#ON}
     * @throws IllegalArgumentException if {@code scale} is outside 0 to {@value #MAX_SCALE}, checked before the switch
     * @throws NullPointerException if {@code owner}, {@code errors} or {@code mode} is {@code null}
     */
    static Detected<EconomyProvider> vault(Plugin owner, ErrorReports errors, boolean switchedOn, Scheduler.Mode mode,
                                           @Nullable Integer scale) {
        return EconomyLookup.vault(owner, errors, switchedOn, mode, scale);
    }

    /**
     * Decimal places, from 0 to {@value #MAX_SCALE}.
     *
     * @return the scale
     */
    int scale();

    /**
     * The thread to call this economy on for a player.
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
     * Whether the player's balance covers an amount, as a withdraw checks it: the balance as the economy holds it,
     * not rounded, is compared with the amount rounded half-even to {@link #scale()}, so a balance just under the
     * amount never covers it. Unlike a withdraw, any amount is accepted: one that is negative or rounds to zero is
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
     * Takes money from a player when the balance covers it. {@code start} runs after the balance check and before
     * the money moves, on the same thread; when it returns {@code false} no money moves.
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
