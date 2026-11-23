package so.alaz.taffy.hooks;

import io.th0rgal.oraxen.api.OraxenItems;
import io.th0rgal.oraxen.api.events.OraxenItemsLoadedEvent;
import io.th0rgal.oraxen.items.ItemBuilder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

// OraxenItems.getIdByItem reads the id from the item's own PDC, so it answers before Oraxen has loaded its items.
// OraxenItems.exists and getItemById read Oraxen's item registry, which is empty until Oraxen first loads its items,
// so the provider builds from OraxenItemsLoadedEvent, or at once when Oraxen already knows an item name. An
// OraxenItemsLoadedEvent seen by any provider is remembered for the server run, so a provider created later builds
// at once. An Oraxen that loaded no item at all knows no name, so a provider created after a load that no provider
// saw waits for the next OraxenItemsLoadedEvent. Both flags only go from false to true, so they need no lock.
// OraxenItems has a static initializer that needs the Oraxen plugin, and the class initializes when its item names
// are first read.
final class OraxenProvider implements CustomItemProvider, Listener {

    private final Predicate<String> exists;
    private final Function<String, @Nullable ItemStack> build;
    private final Function<ItemStack, @Nullable String> idOf;
    private final Runnable itemsLoaded;
    private static volatile boolean seenLoad;

    private volatile boolean loaded;

    OraxenProvider(Predicate<String> exists, Function<String, @Nullable ItemStack> build,
                   Function<ItemStack, @Nullable String> idOf, Runnable itemsLoaded) {
        this.exists = exists;
        this.build = build;
        this.idOf = idOf;
        this.itemsLoaded = itemsLoaded;
    }

    // registerEvents logs and skips a listener whose event class is missing instead of throwing, so the event is
    // linked first.
    static CustomItemProvider create(Plugin owner, Runnable itemsLoaded) {
        ApiLink.staticMethod(OraxenItems.class, "getIdByItem", String.class, ItemStack.class);
        ApiLink.staticMethod(OraxenItems.class, "exists", boolean.class, String.class);
        ApiLink.staticMethod(OraxenItems.class, "getItemById", ItemBuilder.class, String.class);
        ApiLink.staticMethod(OraxenItems.class, "getNames", Set.class);
        ApiLink.instanceMethod(ItemBuilder.class, "build", ItemStack.class);
        ApiLink.staticMethod(OraxenItemsLoadedEvent.class, "getHandlerList", HandlerList.class);
        OraxenProvider provider = new OraxenProvider(
            id -> OraxenItems.exists(id),
            id -> {
                ItemBuilder builder = OraxenItems.getItemById(id);
                return builder == null ? null : builder.build();
            },
            item -> OraxenItems.getIdByItem(item),
            itemsLoaded);
        return start(owner, provider, () -> !OraxenItems.getNames().isEmpty());
    }

    // The listener is registered before the item names are read, so a load that ends in between is seen by the
    // event.
    static OraxenProvider start(Plugin owner, OraxenProvider provider, BooleanSupplier knowsItems) {
        owner.getServer().getPluginManager().registerEvents(provider, owner);
        if (seenLoad) {
            provider.loaded = true;
            return provider;
        }
        try {
            if (knowsItems.getAsBoolean()) {
                provider.loaded = true;
            }
        } catch (LinkageError missing) {
            owner.getLogger().warning("Oraxen does not say which items it has loaded (" + missing
                + "), so Oraxen items are built only after OraxenItemsLoadedEvent.");
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
    public void loaded(OraxenItemsLoadedEvent event) {
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

    @Override
    public @Nullable ItemStack create(String id) {
        return exists.test(id) ? build.apply(id) : null;
    }

    @Override
    public @Nullable String id(ItemStack item) {
        return idOf.apply(item);
    }
}
