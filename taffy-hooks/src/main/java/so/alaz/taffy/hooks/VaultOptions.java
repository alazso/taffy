package so.alaz.taffy.hooks;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.platform.Scheduler;
import so.alaz.taffy.platform.ThreadTag;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * What {@link EconomyProvider#vault(VaultOptions)} looks the Vault economy up with. Build the options with
 * {@link #of(Plugin, ErrorReports, boolean)} and the methods that take a value, such as
 * {@code VaultOptions.of(plugin, errors, folia).scale(2).threadRule(VaultOptions.ThreadRule.ENTITY)}; each gives a
 * copy and leaves these options unchanged.
 */
public final class VaultOptions {

    /** The thread the Vault economy is called on, which {@link EconomyProvider#thread(UUID)} answers. */
    public enum ThreadRule {

        /** The global region thread for every player, {@link ThreadTag#GLOBAL}; the main thread on Paper. */
        GLOBAL,

        /**
         * The region thread that owns the player, for an economy plugin that is safe to call on region threads.
         * {@link EconomyProvider#thread(UUID)} answers {@link ThreadTag#entity(UUID)} for a player who is online and
         * {@link ThreadTag#GLOBAL} for one who is not, since no region owns a player who is offline. The answer is
         * read from the server on each call and from any thread, as {@link Scheduler#dispatch} looks a player up.
         *
         * <p>The calls for one online player are ordered on that player's thread. On Folia they are not ordered
         * against another plugin that moves the same account on the global region thread, nor against a call made
         * for the player on the global region thread while the player was offline: a balance check there and a
         * withdraw here can interleave. On Paper every one of these threads is the main thread.
         *
         * <p>A player who logs in between the {@link EconomyProvider#thread(UUID)} call and a task dispatched to
         * {@link ThreadTag#GLOBAL} makes that task's call throw {@link IllegalStateException} on Folia, where the
         * global region thread does not own the player. The future of the dispatch carries the exception and no
         * money has moved, so the caller reads {@link EconomyProvider#thread(UUID)} again and dispatches once more.
         */
        ENTITY
    }

    /** Who decides whether a balance covers an amount, in {@code has} and in a withdraw of the Vault economy. */
    public enum FundsCheck {

        /**
         * The provider decides. {@link EconomyProvider#has(UUID, BigDecimal)} compares the balance read from the
         * economy with the amount, and a withdraw reads the balance, refuses with
         * {@link EconomyResult.Status#INSUFFICIENT} when it is short without asking the economy, withdraws, and reads
         * the balance again to tell {@link EconomyResult.Status#OVERDRAWN}.
         */
        EMULATED,

        /**
         * The economy decides, for an economy that allows an overdraft or a loan, or that keeps rules of its own.
         * {@link EconomyProvider#has(UUID, BigDecimal)} answers what Vault's {@code Economy.has} answers for the
         * amount rounded half-even to {@link EconomyProvider#scale()} and sent as a {@code double}. A withdraw reads
         * no balance, before or after: {@code start} runs first, and {@code withdrawPlayer} decides.
         *
         * <p>A withdraw the economy refuses, for a short balance too, gives {@link EconomyResult.Status#FAILED} with
         * the economy's message, or {@code the economy refused it} when it gives none, since a Vault response has no
         * type for insufficient funds. One the economy answers as not implemented gives
         * {@link EconomyResult.Status#UNSUPPORTED}. A withdraw never gives
         * {@link EconomyResult.Status#INSUFFICIENT} or {@link EconomyResult.Status#OVERDRAWN}: one that leaves the
         * balance below zero gives {@link EconomyResult.Status#OK}.
         */
        NATIVE
    }

    private final Plugin owner;
    private final ErrorReports errors;
    private final boolean folia;
    private final boolean switchedOn;
    private final @Nullable Integer scale;
    private final ThreadRule threadRule;
    private final FundsCheck fundsCheck;

    private VaultOptions(Plugin owner, ErrorReports errors, boolean folia, boolean switchedOn, @Nullable Integer scale,
                         ThreadRule threadRule, FundsCheck fundsCheck) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.errors = Objects.requireNonNull(errors, "errors");
        if (scale != null && (scale < 0 || scale > EconomyProvider.MAX_SCALE)) {
            throw new IllegalArgumentException(
                "a scale runs from 0 to " + EconomyProvider.MAX_SCALE + ", not " + scale);
        }
        this.folia = folia;
        this.switchedOn = switchedOn;
        this.scale = scale;
        this.threadRule = Objects.requireNonNull(threadRule, "threadRule");
        this.fundsCheck = Objects.requireNonNull(fundsCheck, "fundsCheck");
    }

    /**
     * Gives the options of a lookup that is switched on, uses the economy's own decimal places, calls the economy on
     * {@link ThreadRule#GLOBAL} and checks a balance with {@link FundsCheck#EMULATED}.
     *
     * @param owner the plugin that pays through the economy: its server's plugin manager and services manager are
     *     read, and its logger receives every warning
     * @param errors receives a lookup that failed
     * @param folia whether the server is Folia, which decides what each reason asks the owner to install
     * @return the options
     * @throws NullPointerException if {@code owner} or {@code errors} is {@code null}
     */
    public static VaultOptions of(Plugin owner, ErrorReports errors, boolean folia) {
        return new VaultOptions(owner, errors, folia, true, null, ThreadRule.GLOBAL, FundsCheck.EMULATED);
    }

    /**
     * Gives the plugin that pays through the economy.
     *
     * @return the owner
     */
    public Plugin owner() {
        return owner;
    }

    /**
     * Gives where a lookup that failed is reported.
     *
     * @return the error reports
     */
    public ErrorReports errors() {
        return errors;
    }

    /**
     * Tells whether the server is Folia.
     *
     * @return {@code true} on Folia
     */
    public boolean folia() {
        return folia;
    }

    /**
     * Tells whether the owner switched the economy on.
     *
     * @return {@code false} for a lookup that gives {@link HookState#SWITCHED_OFF}
     */
    public boolean switchedOn() {
        return switchedOn;
    }

    /**
     * Gives the decimal places to use instead of the economy's own.
     *
     * @return the scale, from 0 to {@value EconomyProvider#MAX_SCALE}, or {@code null} for the economy's own
     */
    public @Nullable Integer scale() {
        return scale;
    }

    /**
     * Gives the thread the economy is called on.
     *
     * @return the thread rule
     */
    public ThreadRule threadRule() {
        return threadRule;
    }

    /**
     * Gives who decides whether a balance covers an amount.
     *
     * @return the funds check
     */
    public FundsCheck fundsCheck() {
        return fundsCheck;
    }

    /**
     * Gives these options with the economy switched on or off.
     *
     * @param switchedOn whether the owner switched the economy on; {@code false} gives
     *     {@link HookState#SWITCHED_OFF} and reads nothing from the server
     * @return a copy of these options with {@code switchedOn} replaced
     */
    public VaultOptions switchedOn(boolean switchedOn) {
        return new VaultOptions(owner, errors, folia, switchedOn, scale, threadRule, fundsCheck);
    }

    /**
     * Gives these options with another scale.
     *
     * @param scale decimal places to use instead of the economy's own, from 0 to {@value EconomyProvider#MAX_SCALE},
     *     or {@code null} to use the economy's fractional digits, capped at {@value EconomyProvider#MAX_SCALE}, or 2
     *     when it reports none; a scale finer than the economy keeps is warned about on its first use
     * @return a copy of these options with {@code scale} replaced
     * @throws IllegalArgumentException if {@code scale} is outside 0 to {@value EconomyProvider#MAX_SCALE}
     */
    public VaultOptions scale(@Nullable Integer scale) {
        return new VaultOptions(owner, errors, folia, switchedOn, scale, threadRule, fundsCheck);
    }

    /**
     * Gives these options with another thread rule.
     *
     * @param threadRule the thread the economy is called on
     * @return a copy of these options with {@code threadRule} replaced
     * @throws NullPointerException if {@code threadRule} is {@code null}
     */
    public VaultOptions threadRule(ThreadRule threadRule) {
        return new VaultOptions(owner, errors, folia, switchedOn, scale, threadRule, fundsCheck);
    }

    /**
     * Gives these options with another funds check.
     *
     * @param fundsCheck who decides whether a balance covers an amount
     * @return a copy of these options with {@code fundsCheck} replaced
     * @throws NullPointerException if {@code fundsCheck} is {@code null}
     */
    public VaultOptions fundsCheck(FundsCheck fundsCheck) {
        return new VaultOptions(owner, errors, folia, switchedOn, scale, threadRule, fundsCheck);
    }

    @Override
    public String toString() {
        return "VaultOptions[owner=" + owner.getName() + ", folia=" + folia + ", switchedOn=" + switchedOn
            + ", scale=" + scale + ", threadRule=" + threadRule + ", fundsCheck=" + fundsCheck + "]";
    }
}
