package so.alaz.taffy.hooks;

import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.platform.ThrowLog;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * The custom item plugins that were on when the owner detected them, in {@link CustomItemPlugin} order: reads the
 * id of an item and builds the item an id names.
 *
 * <p>An id is the plugin's key, a colon and the plugin's own id of the item, as in {@code nexo:ruby} or
 * {@code itemsadder:gems:ruby}. A plugin whose integration is on is still skipped by a call while it is no longer
 * enabled, and while it is still loading its items.
 *
 * <p>Immutable after {@link #detect} and thread-safe. Each call runs the integration plugins on the calling thread,
 * so call on the thread that owns the item. {@link #close} unregisters the listeners detection registered, before the
 * owner detects again.
 */
public final class CustomItems {

    /**
     * The id read from an item.
     *
     * @param id the item's id, or {@code null} when no plugin gave one
     * @param failed whether a plugin that reads ids could not answer: it threw, it was no longer enabled, or it was
     *     still loading its items, and was skipped. A {@code null} id with {@code failed} set means the item may be a
     *     custom item whose id could not be read.
     */
    public record Resolution(@Nullable String id, boolean failed) {
    }

    private record Active(CustomItemPlugin plugin, CustomItemProvider provider, Plugin bound, HookStatus detected,
                          Detected<CustomItemProvider> detection) {
    }

    private static final Resolution NONE_FOUND = new Resolution(null, false);
    private static final Resolution FAILED = new Resolution(null, true);

    private final List<Active> active;
    private final List<HookStatus> statuses;
    private final ThrowLog throwLog;
    private final ErrorReports errors;

    private CustomItems(List<Active> active, List<HookStatus> statuses, ThrowLog throwLog, ErrorReports errors) {
        this.active = List.copyOf(active);
        this.statuses = List.copyOf(statuses);
        this.throwLog = throwLog;
        this.errors = errors;
    }

    /**
     * Detects each custom item plugin once and turns on the integration of each one that is switched on,
     * installed, enabled and serving the API that is read. A plugin not enabled by now stays off until the owner
     * detects again. Logs one line naming the state of every plugin, and a warning for each plugin whose API is
     * missing or not visible, or whose integration could not be built. A plugin whose integration could not be built
     * is also reported.
     *
     * <p>The plugin that calls this lists each custom item plugin it reads in its {@code paper-plugin.yml} under
     * {@code dependencies.server}, with {@code load: BEFORE} and {@code required: false}, so the plugin's API is
     * visible to it and the plugin is enabled first when it is installed. A plugin that is not listed is
     * {@link HookState#NOT_VISIBLE}.
     *
     * <p>Call on the server thread while the owner is enabled, normally from its {@code onEnable}: when ItemsAdder is
     * on, its integration registers a listener for the owner, which the server refuses while the owner is not
     * enabled. That listener stays registered until {@link #close} is called on the result or the owner disables, so
     * a plugin that detects again, such as from its own reload command, closes the result it holds first.
     *
     * @param owner the plugin that uses the integrations: its server's plugin manager is searched, its logger
     *     receives every log line, and the ItemsAdder integration registers its listener for it
     * @param errors where a failure to build an integration or an item is reported, under {@code hook.<key>}, such
     *     as {@code hook.nexo}
     * @param switchedOn the plugins to look for; any other plugin is not looked at and is
     *     {@link HookState#SWITCHED_OFF}
     * @return the integrations that are on
     * @throws NullPointerException if an argument is {@code null}
     */
    public static CustomItems detect(Plugin owner, ErrorReports errors, Set<CustomItemPlugin> switchedOn) {
        Objects.requireNonNull(owner, "owner");
        Map<CustomItemPlugin, Function<Plugin, CustomItemProvider>> factories = new EnumMap<>(CustomItemPlugin.class);
        factories.put(CustomItemPlugin.ITEMSADDER, plugin -> ItemsAdderProvider.create(owner));
        factories.put(CustomItemPlugin.NEXO, plugin -> NexoProvider.create());
        factories.put(CustomItemPlugin.ORAXEN, plugin -> OraxenProvider.create());
        factories.put(CustomItemPlugin.HEADDATABASE, plugin -> HeadDatabaseProvider.create());
        return detect(owner, errors, switchedOn,
            new PluginPresence(owner.getServer().getPluginManager(), owner.getLogger()), factories);
    }

    static CustomItems detect(Plugin owner, ErrorReports errors, Set<CustomItemPlugin> switchedOn, Presence presence,
                              Map<CustomItemPlugin, Function<Plugin, CustomItemProvider>> factories) {
        Objects.requireNonNull(errors, "errors");
        Objects.requireNonNull(switchedOn, "switchedOn");
        Logger logger = owner.getLogger();
        List<Active> active = new ArrayList<>();
        List<HookStatus> statuses = new ArrayList<>();
        for (CustomItemPlugin plugin : CustomItemPlugin.values()) {
            Detected<CustomItemProvider> detected = Detection.run(plugin.plugin(), plugin.probe(),
                switchedOn.contains(plugin), presence, factories.get(plugin), logger, errors);
            CustomItemProvider provider = detected.provider();
            HookStatus status = detected.status();
            if (provider != null) {
                active.add(new Active(plugin, provider, Objects.requireNonNull(detected.bound()), status, detected));
                if (!provider.ready()) {
                    status = new HookStatus(status.plugin(), HookState.LOADING, status.version(), null);
                }
            }
            statuses.add(status);
        }
        logger.info(Detection.summary("Custom item plugins", statuses));
        return new CustomItems(active, statuses, new ThrowLog(logger), errors);
    }

    /**
     * Reads the id of an item: asks each plugin in turn and gives the first id one of them reads. A plugin that
     * throws is warned about, at most once a minute with its stack the first time, and counts as a failure. A plugin
     * that is no longer enabled, or is still loading its items, is not asked and counts as a failure too.
     * HeadDatabase, which only builds items, is never asked and never counts as a failure. A {@code null} or empty
     * item is no custom item: no plugin is asked, and the result has no id and no failure.
     *
     * @param item the item to read
     * @return the id with no failure, or no id and whether any plugin failed
     */
    public Resolution resolve(@Nullable ItemStack item) {
        if (item == null || item.isEmpty()) {
            return NONE_FOUND;
        }
        boolean failed = false;
        for (Active hook : active) {
            if (!hook.provider().readsIds()) {
                continue;
            }
            if (!hook.bound().isEnabled()) {
                failed = true;
                continue;
            }
            if (!hook.provider().ready()) {
                failed = true;
                continue;
            }
            String id;
            try {
                id = hook.provider().id(item);
            } catch (Exception | LinkageError failure) {
                failed = true;
                throwLog.warn("id " + hook.plugin().key(), hook.plugin().plugin() + " could not read the id of a "
                    + item.getType() + " (" + failure + ").", failure);
                continue;
            }
            if (id != null) {
                return new Resolution(hook.plugin().key() + ":" + id, false);
            }
        }
        return failed ? FAILED : NONE_FOUND;
    }

    /**
     * Reads the id of an item as {@link #resolve} does, dropping whether a plugin failed.
     *
     * @param item the item to read
     * @return the item's id, or {@code null} when no plugin gave one
     */
    public @Nullable String idOrNull(@Nullable ItemStack item) {
        return resolve(item).id();
    }

    /**
     * Builds the item a reference names, parsed by {@link CustomItemRef#parse}. A reference with a hint goes only
     * to the plugin the hint names, by its key or an alias, and never to another one. A reference without one is
     * offered to each plugin in turn, and the first item built wins. Each plugin is given the id without the hint.
     * A plugin that is no longer enabled or still loading its items is skipped. A plugin that throws is skipped
     * after a warning, at most once a minute with its stack the first time, and the failure is reported under
     * {@code hook.<key>}.
     *
     * @param reference the reference, such as {@code ia:gems:ruby}, {@code hdb:1234} or {@code ruby}
     * @return the item, or {@code null} when the hint names no plugin or no plugin built one
     * @throws IllegalArgumentException if {@code reference} is {@code null} or its id is blank
     */
    public @Nullable ItemStack create(String reference) {
        CustomItemRef ref = CustomItemRef.parse(reference);
        if (ref.providerHint() != null) {
            CustomItemPlugin named = ref.provider();
            for (Active hook : active) {
                if (hook.plugin() == named) {
                    return create(hook, ref.id());
                }
            }
            return null;
        }
        for (Active hook : active) {
            ItemStack item = create(hook, ref.id());
            if (item != null) {
                return item;
            }
        }
        return null;
    }

    private @Nullable ItemStack create(Active hook, String id) {
        if (!hook.bound().isEnabled() || !hook.provider().ready()) {
            return null;
        }
        try {
            return hook.provider().create(id);
        } catch (Exception | LinkageError failure) {
            throwLog.warn("create " + hook.plugin().key(), hook.plugin().plugin() + " could not create " + id + " ("
                + failure + ").", failure);
            errors.report("hook." + hook.plugin().key(), failure);
            return null;
        }
    }

    /**
     * Whether every plugin whose integration is on has loaded its items. A plugin that is no longer enabled does
     * not count.
     *
     * @return {@code true} when no enabled plugin is still loading its items, and when no integration is on
     */
    public boolean ready() {
        for (Active hook : active) {
            if (!hook.bound().isEnabled()) {
                continue;
            }
            if (!hook.provider().ready()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the plugin a hint names has its integration on and is still enabled.
     *
     * @param hint a key such as {@code itemsadder}, or an alias such as {@code ia} or {@code hdb}, in any case
     * @return {@code true} if the hint names a plugin whose integration is on and the plugin is enabled
     */
    public boolean available(String hint) {
        CustomItemPlugin named = CustomItemPlugin.byKey(hint);
        for (Active hook : active) {
            if (hook.plugin() == named) {
                return hook.bound().isEnabled();
            }
        }
        return false;
    }

    /**
     * The plugins whose integration is on, whether or not each is still enabled, including HeadDatabase, which
     * only builds items.
     *
     * @return the plugins in {@link CustomItemPlugin} order
     */
    public List<CustomItemPlugin> active() {
        return active.stream().map(Active::plugin).toList();
    }

    /**
     * The state of every custom item plugin now, read when called like every status of this package. A plugin whose
     * integration is on is {@link HookState#LOADING} while it is still loading its items, {@link HookState#ON} once
     * it has loaded them, and {@link HookState#NOT_ENABLED} while it is not enabled, with the reason
     * {@link Detected#status()} gives when the server holds another, enabled instance of it. Every other plugin
     * keeps the state it was detected with.
     *
     * @return one status per {@link CustomItemPlugin}, in its order
     */
    public List<HookStatus> statuses() {
        List<HookStatus> now = new ArrayList<>(statuses);
        for (Active hook : active) {
            HookStatus detected = hook.detected();
            HookStatus current;
            if (!hook.bound().isEnabled()) {
                current = hook.detection().notEnabled();
            } else if (hook.provider().ready()) {
                current = detected;
            } else {
                current = new HookStatus(detected.plugin(), HookState.LOADING, detected.version(), null);
            }
            now.set(hook.plugin().ordinal(), current);
        }
        return List.copyOf(now);
    }

    /**
     * Unregisters every listener {@link #detect} registered for the owner to build these integrations, so a plugin
     * that detects again leaves none behind. Call it on the result that is held before each further {@link #detect},
     * on the server thread. The server unregisters the listeners itself when the owner disables, so a plugin that
     * detects once has no need to call it.
     *
     * <p>After the call a plugin that had not loaded its items stays {@link HookState#LOADING} here for good, since
     * the event that ends its load is no longer heard: use the result of the next {@link #detect}. Every other call
     * answers as before. A second call does nothing.
     */
    public void close() {
        for (Active hook : active) {
            if (hook.provider() instanceof Listener listener) {
                HandlerList.unregisterAll(listener);
            }
        }
    }
}
