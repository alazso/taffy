package so.alaz.taffy.hooks;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

// CraftEngineItems.getCustomItemId reads the id from the item's own data, so it answers before CraftEngine has
// loaded its items. CraftEngineItems.byId reads CraftEngine's item registry, and CraftEngine finishes its first load
// after the server has started, so the provider builds from CraftEngineReloadEvent, which CraftEngine fires at the
// end of its first load and of every reload, or at once when CraftEngine says it is fully loaded. CraftEngine sets
// that flag after its first CraftEngineReloadEvent, and CraftEngine 26.6 has no such flag, so a
// CraftEngineReloadEvent seen by any provider is remembered for the server run and a provider created later builds
// at once. On a server both flags only go from false to true, so they need no lock.
final class CraftEngineProvider implements CustomItemProvider, Listener {

    private static volatile boolean seenLoad;

    private final BiFunction<String, String, @Nullable ItemStack> build;
    private final Function<ItemStack, @Nullable String> idOf;
    private final Runnable itemsLoaded;

    private volatile boolean loaded;

    CraftEngineProvider(BiFunction<String, String, @Nullable ItemStack> build,
                        Function<ItemStack, @Nullable String> idOf, Runnable itemsLoaded) {
        this.build = build;
        this.idOf = idOf;
        this.itemsLoaded = itemsLoaded;
    }

    // registerEvents logs and skips a listener whose event class is missing instead of throwing, so the event is
    // linked first.
    static CustomItemProvider create(Plugin owner, Runnable itemsLoaded) {
        ApiLink.staticMethod(CraftEngineItems.class, "byId", BukkitItemDefinition.class, Key.class);
        ApiLink.staticMethod(CraftEngineItems.class, "getCustomItemId", Key.class, ItemStack.class);
        ApiLink.instanceMethod(BukkitItemDefinition.class, "buildBukkitItem", ItemStack.class);
        ApiLink.staticMethod(Key.class, "of", Key.class, String.class, String.class);
        ApiLink.instanceMethod(Key.class, "namespace", String.class);
        ApiLink.instanceMethod(Key.class, "value", String.class);
        ApiLink.staticMethod(CraftEngineReloadEvent.class, "getHandlerList", HandlerList.class);
        CraftEngineProvider provider = new CraftEngineProvider(
            (namespace, value) -> {
                BukkitItemDefinition definition = CraftEngineItems.byId(Key.of(namespace, value));
                return definition == null ? null : definition.buildBukkitItem();
            },
            item -> {
                Key key = CraftEngineItems.getCustomItemId(item);
                return key == null ? null : key.namespace() + ":" + key.value();
            },
            itemsLoaded);
        return start(owner, provider, () -> CraftEngine.instance().isFullyLoaded());
    }

    // The listener is registered before the flag is read, so a load that ends in between is seen by the event.
    static CraftEngineProvider start(Plugin owner, CraftEngineProvider provider, BooleanSupplier fullyLoaded) {
        owner.getServer().getPluginManager().registerEvents(provider, owner);
        if (seenLoad) {
            provider.loaded = true;
            return provider;
        }
        try {
            if (fullyLoaded.getAsBoolean()) {
                provider.loaded = true;
            }
        } catch (LinkageError missing) {
            owner.getLogger().warning("CraftEngine does not say whether it is fully loaded (" + missing
                + "), so CraftEngine items are built only after CraftEngineReloadEvent.");
        } catch (RuntimeException failure) {
            HandlerList.unregisterAll(provider);
            throw failure;
        }
        return provider;
    }

    static void forgetLoad() {
        seenLoad = false;
    }

    // A synchronous event: the server thread on Paper, a ticking thread on Folia.
    @EventHandler(priority = EventPriority.MONITOR)
    public void loaded(CraftEngineReloadEvent event) {
        seenLoad = true;
        loaded = true;
        itemsLoaded.run();
    }

    @Override
    public boolean ready() {
        return loaded;
    }

    @Override
    public boolean idsReady() {
        return true;
    }

    // An id is <namespace>:<id>.
    @Override
    public @Nullable ItemStack create(String id) {
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1) {
            return null;
        }
        return build.apply(id.substring(0, colon), id.substring(colon + 1));
    }

    @Override
    public @Nullable String id(ItemStack item) {
        return idOf.apply(item);
    }
}
