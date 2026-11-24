package so.alaz.taffy.hooks;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.platform.Scheduler;
import so.alaz.taffy.platform.ThreadTag;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
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
 *
 * <p>{@link #setBalance(UUID, BigDecimal)}, {@link #currencyNameSingular()}, {@link #currencyNamePlural()},
 * {@link #currencySymbol()}, {@link #format(BigDecimal)}, {@link #withdrawReceipt(UUID, BigDecimal, BooleanSupplier)}
 * and {@link #depositReceipt(UUID, BigDecimal)} are default methods, so an implementation that has none of them
 * compiles and answers that it cannot: {@link EconomyResult.Status#UNSUPPORTED} for a balance to set, {@code null}
 * for a name, a symbol and a formatted amount, and a receipt with no transaction id.
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
     * {@link EconomyWatcher} is registered for the owner, which looks the economy up again when an economy
     * registers, and when one leaves unless the server is stopping, and one more lookup runs on the first tick
     * through {@link Scheduler#global}; when the scheduler refuses that task, a warning is logged. Each of these
     * lookups takes the result before it, as {@link #vault(VaultOptions, Detected)} does, so a status is warned
     * about and a failed lookup reported once, and again only after the status changed. Options that are switched
     * off register nothing and schedule nothing.
     *
     * <p>The plugin calls this in {@code onEnable}, reads {@link VaultWatch#current()} wherever it needs the
     * economy, and closes the watch in {@code onDisable} or before it starts another. The server registers a
     * listener only for a plugin that is enabled, so a call with options that are switched on before the owner is
     * enabled, or once it is disabled, throws before anything is looked up, logged or registered.
     *
     * @param options what each lookup reads
     * @param scheduler runs the lookup of the first tick
     * @return the watch, whose {@link VaultWatch#current()} gives the latest lookup result
     * @throws IllegalStateException if {@code options} are switched on and their owner is not enabled
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
            plugin -> PlayerPointsEconomy.create(plugin, server, logger), logger, errors);
    }

    /**
     * Looks up one currency of the economy registered with the VaultUnlocked economy API
     * ({@code net.milkbowl.vault2.economy.Economy}), which carries amounts as {@link BigDecimal}, names an account
     * by {@link UUID} and has several currencies. {@link #vault} keeps reading the Vault API
     * ({@code net.milkbowl.vault.economy.Economy}), which VaultUnlocked serves too, and is not changed by this
     * entry point. The VaultUnlocked plugin is named {@code Vault}: the plugin that calls this lists {@code Vault}
     * in its {@code paper-plugin.yml} with {@code load: BEFORE} and {@code required: false}. Built against
     * VaultUnlockedAPI 2.20. An installed VaultUnlocked whose {@code Economy} lacks
     * {@code set(String, UUID, String, String, BigDecimal)} or {@code fractionalDigits(String, String)}, the two
     * methods of those the provider calls that VaultUnlocked added in 2025, is still read: without the first,
     * {@link #setBalance(UUID, BigDecimal)} answers {@link EconomyResult.Status#UNSUPPORTED} once the thread is
     * checked, and without the second the decimal places are those {@code Economy.fractionalDigits(String)} gives
     * for the economy. One that lacks any other method the provider calls is {@link HookState#FAILED}.
     *
     * <p>The lookup reads the registration and asks the economy whether it has the currency, through
     * {@code Economy.hasCurrency(String)}; when it has not, it also reads {@code Economy.currencies()} for the
     * reason. No balance is read and no money moves. The VaultUnlocked classes are loaded only when that API is
     * visible to the plugin. The status's plugin is always {@code VaultUnlocked}, with the version of the installed
     * {@code Vault} plugin, and each status but {@link HookState#SWITCHED_OFF} and {@link HookState#ON} carries a
     * reason for the owner:
     *
     * <ul>
     *   <li>{@link HookState#SWITCHED_OFF} when {@code switchedOn} is {@code false}; nothing is read;</li>
     *   <li>{@link HookState#NOT_INSTALLED} when no plugin named {@code Vault} is installed;</li>
     *   <li>{@link HookState#NOT_VISIBLE} when the installed plugin holds the API but the plugin does not list
     *     {@code Vault}, with a warning;</li>
     *   <li>{@link HookState#API_MISSING} when the installed {@code Vault} is not VaultUnlocked, with a
     *     warning;</li>
     *   <li>{@link HookState#NOT_REGISTERED} when no economy has registered with the VaultUnlocked economy API, and
     *     when the registered economy has no currency of that id, with a reason that names the economy and the ids
     *     it has. For that currency {@link HookStatus#describe()} still reads
     *     {@code VaultUnlocked <version> has nothing registered with it}, so {@link HookStatus#line()} is such as
     *     {@code VaultUnlocked 2.20.3 has nothing registered with it. TNE has no currency named tokens. It has
     *     dollars, gems.};</li>
     *   <li>{@link HookState#FAILED} when the lookup throws an exception or a {@link LinkageError}, which is logged
     *     with a warning and reported to {@code errors} under {@code hook.vaultunlocked}; any other {@link Error}
     *     propagates;</li>
     *   <li>{@link HookState#ON} with the provider otherwise.</li>
     * </ul>
     *
     * <p>The status is fixed, as the one of {@link #vault} is. An economy plugin that enables later registers only
     * then, so the caller looks the economy up again from an
     * {@link EconomyWatcher#vaultUnlocked(Runnable, BooleanSupplier)} watcher, and once more on the first tick. A
     * watcher made by the {@link EconomyWatcher} constructor does not hear this registration. Every call logs its
     * own warning and makes its own report.
     *
     * <p>{@link #thread(UUID)} answers {@link ThreadTag#GLOBAL} for every player. {@link #balance(UUID)},
     * {@link #has(UUID, BigDecimal)}, a withdraw, a deposit and {@link #setBalance(UUID, BigDecimal)} throw
     * {@link IllegalStateException} on a thread {@code Server.isGlobalTickThread()} is {@code false} on, with a
     * message that names {@code thread(player)} and {@code Scheduler.dispatch}, after the {@code null} checks and
     * before anything in the economy is called. The asynchronous API of VaultUnlocked is not used.
     *
     * <p>Every call names the plugin that pays, by the owner's name, the currency, and a world, which is the name of
     * the first world of {@code Server.getWorlds()}, since the VaultUnlocked calls that name a currency also name a
     * world. While no world is loaded, a balance read throws {@link IllegalStateException} and a withdraw, a deposit
     * and a balance set give {@link EconomyResult.Status#FAILED}. Amounts reach the economy as {@link BigDecimal},
     * rounded half-even to {@link #scale()} and never as a {@code double}, so no amount is refused for its size. A
     * withdraw reads the balance, gives {@link EconomyResult.Status#INSUFFICIENT} when it is short, runs
     * {@code start}, withdraws and reads the balance again to tell {@link EconomyResult.Status#OVERDRAWN}, as
     * {@link VaultOptions.FundsCheck#EMULATED} does for the Vault economy. A response of the type
     * {@code NOT_IMPLEMENTED} gives {@link EconomyResult.Status#UNSUPPORTED}, and any other response that is not a
     * success gives {@link EconomyResult.Status#FAILED} with the economy's message.
     *
     * <p>Before a withdraw, a deposit or a balance set reaches the economy, the provider asks
     * {@code Economy.hasCurrency(String)} again, and gives {@link EconomyResult.Status#FAILED} with a message that
     * names the currency when the economy no longer has it, so no money moves in another currency.
     * {@link #balance(UUID)} and {@link #has(UUID, BigDecimal)} do not ask and give what the economy answers. While
     * the plugin that registered the economy is disabled, a withdraw, a deposit and a balance set give
     * {@link EconomyResult.Status#FAILED} with a message that names the plugin, with no call into the economy.
     *
     * <p>{@link #setBalance(UUID, BigDecimal)} calls {@code Economy.set}, which in VaultUnlockedAPI 2.20 is a
     * default method that reads the balance and withdraws or deposits the difference unless the economy overrides
     * it. After a set the economy answered as a success, the provider reads the balance again. When it is not the
     * amount, as after a fee the economy took or a change another plugin made in between, the call throws
     * {@link IllegalStateException} with a message that names the balance and the amount: the balance changed and
     * is not what was asked for. When that read throws an exception or a {@link LinkageError}, the result is
     * {@link EconomyResult.Status#OK} and a warning is logged.
     *
     * <p>{@link #currencyNameSingular()} and {@link #currencyNamePlural()} answer
     * {@code Economy.defaultCurrencyNameSingular} and {@code defaultCurrencyNamePlural} when the currency is the
     * one {@code Economy.getDefaultCurrency} names, and {@code null} for every other currency, since the API names
     * only its default currency. {@link #currencySymbol()} answers {@code null}: the API has no symbol.
     * {@link #format(BigDecimal)} answers {@code Economy.format(String, BigDecimal, String)} for the amount rounded
     * half-even to {@link #scale()}. A receipt has no transaction id: a VaultUnlocked response carries none.
     *
     * @param owner the plugin that pays through the economy: its name is passed to the economy with every call, its
     *     server's plugin manager and services manager are read, and its logger receives every warning
     * @param errors receives a lookup that failed
     * @param switchedOn whether the owner switched the economy on; {@code false} gives {@link HookState#SWITCHED_OFF}
     *     and reads nothing from the server
     * @param folia whether the server is Folia, which decides what each reason asks the owner to install
     * @param currency the id of the currency, as {@link #vaultUnlockedCurrencies} lists it
     * @param scale decimal places to use instead of the currency's own, from 0 to {@value #MAX_SCALE}, or
     *     {@code null} to use {@code Economy.fractionalDigits(String, String)}, or
     *     {@code Economy.fractionalDigits(String)} where the installed VaultUnlocked lacks it, capped at
     *     {@value #MAX_SCALE}, or 2 when it reports none; a scale finer than the economy keeps is warned about on its
     *     first use
     * @return the lookup result, with a provider when the state is {@link HookState#ON}
     * @throws IllegalArgumentException if {@code currency} is blank or {@code scale} is outside 0 to
     *     {@value #MAX_SCALE}, checked before the switch
     * @throws NullPointerException if {@code owner}, {@code errors} or {@code currency} is {@code null}
     * @since 0.4.13
     */
    static Detected<EconomyProvider> vaultUnlocked(Plugin owner, ErrorReports errors, boolean switchedOn,
                                                   boolean folia, String currency, @Nullable Integer scale) {
        return EconomyLookup.vaultUnlocked(owner, errors, switchedOn, folia, currency, scale);
    }

    /**
     * Lists the currencies of the economy registered with the VaultUnlocked economy API, in the order
     * {@code Economy.currencies()} gives them. The API names only its default currency, so that one carries the
     * name {@code Economy.defaultCurrencyNameSingular} gives, and every other currency has no name. No currency has
     * a symbol.
     *
     * @param owner the plugin that asks: its name is passed to the economy, its server's services manager is read,
     *     and its logger receives a warning
     * @param errors receives a listing that failed, under {@code hook.vaultunlocked}
     * @return the currencies, which cannot be changed: an empty list when the VaultUnlocked economy API is not
     *     visible to the plugin, when no economy is registered with it, and when the economy throws an exception or
     *     a {@link LinkageError}, which is logged with a warning and reported
     * @throws NullPointerException if {@code owner} or {@code errors} is {@code null}
     * @since 0.4.13
     */
    static List<EconomyCurrency> vaultUnlockedCurrencies(Plugin owner, ErrorReports errors) {
        return EconomyLookup.vaultUnlockedCurrencies(owner, errors);
    }

    /**
     * Detects ExcellentEconomy, the plugin CoinsEngine was renamed to, and builds a provider on one of its
     * currencies. The plugin that calls this lists {@code ExcellentEconomy} and {@code nightcore}, which the
     * ExcellentEconomy API is read through, in its {@code paper-plugin.yml} with {@code load: BEFORE} and
     * {@code required: false}. Built against ExcellentEconomy 2.8.0 and nightcore 2.16.7. A plugin named
     * {@code CoinsEngine}, the name of the releases before the rename, is not read: with it and no
     * {@code ExcellentEconomy} the status is {@link HookState#NOT_INSTALLED} with a reason that says so.
     *
     * <p>ExcellentEconomy is read only when it is switched on, installed, enabled, and its API and nightcore are
     * visible; otherwise the result carries the state that stopped it and no provider. It is
     * {@link HookState#FAILED}, logged with a warning and reported to {@code errors} under
     * {@code hook.excellenteconomy}, when a method the provider calls is missing or ExcellentEconomy has registered
     * no API with the services manager, and {@link HookState#NOT_REGISTERED}, with a reason that names the ids it
     * has, when it has no currency of that id. For that currency {@link HookStatus#describe()} still reads
     * {@code ExcellentEconomy <version> has nothing registered with it}, so {@link HookStatus#line()} is such as
     * {@code ExcellentEconomy 2.8.0 has nothing registered with it. ExcellentEconomy has no currency named gems. It
     * has coins.} Detection asks ExcellentEconomy for the currency and reads whether
     * it holds decimals; no balance is read and no money moves. The result is bound to the ExcellentEconomy plugin,
     * as the one of {@link #playerPoints} is to PlayerPoints: its status reads {@link HookState#NOT_ENABLED} while
     * ExcellentEconomy is disabled. ExcellentEconomy registers nothing an {@link EconomyWatcher} hears, and needs
     * none: it is enabled before the plugin that lists it.
     *
     * <p>{@link #scale()} is 2 for a currency that holds decimals and 0 for one that holds whole units, as detection
     * found it. ExcellentEconomy holds a balance as a {@code double}, so a withdraw, a deposit and a balance set
     * refuse with {@link IllegalArgumentException} an amount that, rounded half-even to {@link #scale()}, does not
     * convert to a {@code double} and back unchanged. Every whole amount up to 2^53, which is 9007199254740992,
     * and every amount of up to 15 significant digits is exact. A balance read is rounded as {@link #vault}
     * describes for a {@code double}.
     *
     * <p>{@link #thread(UUID)} answers {@link ThreadTag#ASYNC} for every player. The provider calls the methods of
     * {@code ExcellentEconomyAPI} that name a {@link UUID}, which also reach a player who is not online:
     * ExcellentEconomy loads that player's data from its database on another thread and completes a future, and
     * the provider waits for it. {@link #balance(UUID)}, {@link #has(UUID, BigDecimal)}, a withdraw, a deposit and
     * {@link #setBalance(UUID, BigDecimal)} therefore throw {@link IllegalStateException} on a thread
     * {@code Server.isPrimaryThread()} is {@code true} on, with a message that names {@code thread(player)} and
     * {@code Scheduler.dispatch}, after the {@code null} checks and before anything in ExcellentEconomy is called.
     * A player ExcellentEconomy has no data for has a balance of 0, and a move for that player gives
     * {@link EconomyResult.Status#FAILED}.
     *
     * <p>A withdraw reads the balance, gives {@link EconomyResult.Status#INSUFFICIENT} when it is short, runs
     * {@code start} and takes the amount; a balance that cannot be read gives
     * {@link EconomyResult.Status#FAILED} before {@code start} runs. A deposit and a balance set read no balance.
     * ExcellentEconomy answers a change it applied and a change a listener of its {@code ChangeBalanceEvent}
     * cancelled alike, and a removal from a balance that does not cover it stops at zero. The provider therefore
     * registers a listener of that event for the owner, at {@code EventPriority.MONITOR}, with the first withdraw,
     * deposit or balance set, and reads what each of them did from the change heard for the player and the currency
     * while it ran, never from a second balance read. A change a listener cancelled gives
     * {@link EconomyResult.Status#FAILED} with the message {@code ExcellentEconomy left the balance unchanged}. A
     * withdraw that took less than the amount, because another plugin lowered the balance after the check, gives
     * back what it took and gives {@link EconomyResult.Status#INSUFFICIENT}, with {@code start} already run. The
     * server registers a listener only for a plugin that is enabled and keeps it through that plugin's
     * {@code onDisable}: a move made while the owner is not enabled, by a provider that has not moved money before,
     * gives {@link EconomyResult.Status#FAILED} before anything moves.
     *
     * <p>The moves of this provider for one player run one at a time, and {@code start} runs while the others for
     * that player wait. A change another plugin makes to the same account is not held back, and does not change the
     * result of a move: it is told from the provider's own change by its size. A move throws
     * {@link IllegalStateException}, with an unknown outcome, when ExcellentEconomy answered a success and no
     * change of the move's size was heard, when two such changes were heard and only one of them was cancelled, and
     * when what a short withdraw took could not be given back. An operation ExcellentEconomy refuses, which it
     * does while its operations are switched off and for a player it has no data for, gives
     * {@link EconomyResult.Status#FAILED} with the message {@code ExcellentEconomy refused it}. A withdraw never
     * gives {@link EconomyResult.Status#OVERDRAWN} or {@link EconomyResult.Status#UNSUPPORTED}: ExcellentEconomy
     * holds no balance below zero. A currency ExcellentEconomy no longer has, and ExcellentEconomy being disabled,
     * give {@link EconomyResult.Status#FAILED} before anything moves.
     *
     * <p>{@link #setBalance(UUID, BigDecimal)} gives {@link EconomyResult.Status#FAILED} for an amount above the
     * limit of the currency, which ExcellentEconomy would lower to that limit. {@link #currencyNameSingular()} and
     * {@link #currencyNamePlural()} both answer {@code ExcellentCurrency.getName()}, the one name a currency has,
     * {@link #currencySymbol()} answers {@code getSymbol()}, and {@link #format(BigDecimal)} answers
     * {@code ExcellentCurrency.format(double)} for the amount rounded half-even to {@link #scale()}, with the tags
     * the owner wrote into the format of the currency. A receipt has no transaction id: ExcellentEconomy gives
     * none.
     *
     * @param owner the plugin that pays in the currency: its server's plugin manager and services manager are read,
     *     the listener of {@code ChangeBalanceEvent} is registered for it, and its logger receives every warning
     * @param errors receives a failed detection
     * @param switchedOn whether the owner switched the economy on; {@code false} gives
     *     {@link HookState#SWITCHED_OFF} and looks at nothing
     * @param currency the id of the currency, as {@link #coinsEngineCurrencies} lists it
     * @return the detection result, with a provider when detection found the currency {@link HookState#ON}; its
     *     status is read when asked
     * @throws IllegalArgumentException if {@code currency} is blank, checked before the switch
     * @throws NullPointerException if {@code owner}, {@code errors} or {@code currency} is {@code null}
     * @since 0.4.13
     */
    static Detected<EconomyProvider> coinsEngine(Plugin owner, ErrorReports errors, boolean switchedOn,
                                                 String currency) {
        return CoinsEngineLookup.detect(owner, errors, switchedOn, currency);
    }

    /**
     * Lists the currencies of ExcellentEconomy, the plugin CoinsEngine was renamed to, each with the id, the name
     * and the symbol ExcellentEconomy holds for it, in the order ExcellentEconomy gives them.
     *
     * @param owner the plugin that asks: its server's plugin manager and services manager are read, and its logger
     *     receives a warning
     * @param errors receives a listing that failed, under {@code hook.excellenteconomy}
     * @return the currencies, which cannot be changed: an empty list when ExcellentEconomy is not installed, not
     *     enabled or not visible to the plugin, and when it throws an exception or a {@link LinkageError}, which is
     *     logged with a warning and reported
     * @throws NullPointerException if {@code owner} or {@code errors} is {@code null}
     * @since 0.4.13
     */
    static List<EconomyCurrency> coinsEngineCurrencies(Plugin owner, ErrorReports errors) {
        return CoinsEngineLookup.currencies(owner, errors);
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
     * Sets a player's balance to an amount, where the economy has a call that sets a balance. The amount is rounded
     * half-even to {@link #scale()} before it reaches the economy, and zero is an amount. The call runs on the thread
     * {@link #thread(UUID)} names, as a withdraw does.
     *
     * <p>{@link EconomyResult.Status#OK} tells that the economy set the balance, also when the balance already was
     * the amount. {@link EconomyResult.Status#UNSUPPORTED} tells that this economy cannot set a balance, and
     * {@link EconomyResult.Status#FAILED} that it can and refused this call; in both the balance is unchanged. A
     * call that throws has an unknown outcome.
     *
     * <p>This default checks its arguments for {@code null} and answers {@link EconomyResult.Status#UNSUPPORTED}. The
     * Vault economy answers {@link EconomyResult.Status#UNSUPPORTED} for every amount, once the thread is checked:
     * the Vault API has no call that sets a balance, and a withdraw or deposit of the difference is not ordered
     * against a change another plugin makes between the balance read and the move. The PlayerPoints economy sets the
     * points through {@code PlayerPointsAPI.set(UUID, int)}: it refuses an amount with a fraction of a point or
     * above {@link Integer#MAX_VALUE} with {@link IllegalArgumentException}, answers
     * {@link EconomyResult.Status#FAILED} while PlayerPoints is disabled or when PlayerPoints refuses, which it does
     * when a listener cancelled its {@code PlayerPointsChangeEvent}, and answers
     * {@link EconomyResult.Status#UNSUPPORTED} when the installed PlayerPoints lacks that method. It runs in turn
     * with the withdraws of this provider for the player.
     *
     * @param player the player
     * @param amount the balance to set, zero or more
     * @return what happened: {@link EconomyResult.Status#OK}, {@link EconomyResult.Status#UNSUPPORTED} or
     *     {@link EconomyResult.Status#FAILED}
     * @throws IllegalArgumentException if the economy can set a balance and the amount is negative or cannot reach
     *     the economy exactly
     * @since 0.4.13
     */
    default EconomyResult setBalance(UUID player, BigDecimal amount) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(amount, "amount");
        return EconomyResult.of(EconomyResult.Status.UNSUPPORTED);
    }

    /**
     * The name of one unit of the currency, such as {@code Dollar}. The call names no player and is not checked
     * against a thread, as {@link #scale()} is not.
     *
     * <p>This default answers {@code null}. The Vault economy answers {@code Economy.currencyNameSingular()} and the
     * PlayerPoints economy {@code PlayerPointsAPI.getCurrencyNameSingular()}. Both answer {@code null} for a name
     * that is {@code null} or blank, and when the call throws an exception or a {@link LinkageError}, which is
     * logged with a warning; the PlayerPoints economy also answers {@code null} when the installed PlayerPoints
     * lacks that method.
     *
     * <p>The name is the text the economy gave, unchanged, as {@link #format(BigDecimal)} describes.
     *
     * @return the name, or {@code null} when the economy has none to give
     * @since 0.4.13
     */
    default @Nullable String currencyNameSingular() {
        return null;
    }

    /**
     * The name of several units of the currency, such as {@code Dollars}. It answers as
     * {@link #currencyNameSingular()} does, for the Vault economy from {@code Economy.currencyNamePlural()} and for
     * the PlayerPoints economy from {@code PlayerPointsAPI.getCurrencyNamePlural()}. The name is the text the
     * economy gave, unchanged, as {@link #format(BigDecimal)} describes.
     *
     * @return the name, or {@code null} when the economy has none to give
     * @since 0.4.13
     */
    default @Nullable String currencyNamePlural() {
        return null;
    }

    /**
     * The symbol of the currency, such as {@code $}. The call names no player and is not checked against a thread.
     *
     * <p>This default answers {@code null}, and so do the Vault economy and the PlayerPoints economy: neither API
     * has a call that gives a symbol. A symbol is the text the economy gave, unchanged, as
     * {@link #format(BigDecimal)} describes.
     *
     * @return the symbol, or {@code null} when the economy has none to give
     * @since 0.4.13
     */
    default @Nullable String currencySymbol() {
        return null;
    }

    /**
     * An amount as the economy writes it for a player to read, such as {@code $1,250.50}. Any amount is accepted,
     * a negative one too. The call names no player and is not checked against a thread.
     *
     * <p>This default checks {@code amount} for {@code null} and answers {@code null}, and so does the PlayerPoints
     * economy. The Vault economy rounds the amount half-even to {@link #scale()} and answers
     * {@code Economy.format(double)} for the {@code double} nearest to it, so beyond 15 significant digits the text
     * can differ from the amount in its last digits. It answers {@code null} for a text that is {@code null} or
     * blank, and when the economy throws an exception or a {@link LinkageError}, in {@code Economy.format} or while
     * {@link #scale()} is worked out, which is logged with a warning.
     *
     * <p>The text is returned as the economy gave it: it is not parsed and nothing is taken out of it. It can hold
     * legacy colour codes, which start with a section sign, or the tags of the economy plugin's own format, which
     * are not MiniMessage. A plugin puts it into a component as literal text, as {@code Component.text} does, and
     * never into {@code MiniMessage.deserialize}, unless it converts the text on purpose. The same holds for
     * {@link #currencyNameSingular()}, {@link #currencyNamePlural()}, {@link #currencySymbol()} and the name and
     * symbol of an {@link EconomyCurrency}.
     *
     * @param amount the amount
     * @return the text, or {@code null} when the economy formats no amount
     * @throws IllegalArgumentException if the economy takes a {@code double} and the amount is beyond its range
     * @since 0.4.13
     */
    default @Nullable String format(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        return null;
    }

    /**
     * Takes money from a player as {@link #withdraw(UUID, BigDecimal, BooleanSupplier)} does, and gives the id the
     * economy gave the transaction. This default gives the result of that withdraw with no id, as the Vault and
     * PlayerPoints economies do: neither API gives a transaction an id.
     *
     * @param player the player
     * @param amount a positive amount
     * @param start decides, once the balance covers the amount, whether the withdraw goes ahead
     * @return the result of the withdraw, with the transaction id when the economy gave one and the money moved
     * @throws IllegalArgumentException if the amount is negative, rounds to zero, or cannot reach the economy exactly
     * @since 0.4.13
     */
    default EconomyReceipt withdrawReceipt(UUID player, BigDecimal amount, BooleanSupplier start) {
        return EconomyReceipt.of(withdraw(player, amount, start));
    }

    /**
     * Gives money to a player as {@link #deposit(UUID, BigDecimal)} does, and gives the id the economy gave the
     * transaction. This default gives the result of that deposit with no id, as the Vault and PlayerPoints economies
     * do.
     *
     * @param player the player
     * @param amount a positive amount
     * @return the result of the deposit, with the transaction id when the economy gave one and the money moved
     * @throws IllegalArgumentException if the amount is negative, rounds to zero, or cannot reach the economy exactly
     * @since 0.4.13
     */
    default EconomyReceipt depositReceipt(UUID player, BigDecimal amount) {
        return EconomyReceipt.of(deposit(player, amount));
    }

    /**
     * A self-test that reads what the economy reports about itself and moves no money.
     *
     * @return one line naming what was reached
     * @throws RuntimeException if nothing was reached
     */
    String probe();
}
