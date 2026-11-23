package so.alaz.taffy.hooks;

import com.nexomc.nexo.api.NexoItems;
import com.nexomc.nexo.api.events.NexoItemsLoadedEvent;
import com.nexomc.nexo.items.ItemBuilder;
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

// NexoItems.idFromItem reads the id from the item's own PDC, so it answers before Nexo has loaded its items.
// NexoItems.exists and itemFromId read Nexo's item registry, which is empty until Nexo first loads its items, so the
// provider builds from NexoItemsLoadedEvent, or at once when Nexo already knows an item name. A NexoItemsLoadedEvent
// seen by any provider is remembered for the server run, so a provider created later builds at once. A Nexo that
// loaded no item at all knows no name, so a provider created after a load that no provider saw waits for the next
// NexoItemsLoadedEvent. Both flags only go from false to true, so they need no lock.
final class NexoProvider implements CustomItemProvider, Listener {

    private final Predicate<String> exists;
    private final Function<String, @Nullable ItemStack> build;
    private final Function<ItemStack, @Nullable String> idOf;
    private final Runnable itemsLoaded;
    private static volatile boolean seenLoad;

    private volatile boolean loaded;

    NexoProvider(Predicate<String> exists, Function<String, @Nullable ItemStack> build,
                 Function<ItemStack, @Nullable String> idOf, Runnable itemsLoaded) {
        this.exists = exists;
        this.build = build;
        this.idOf = idOf;
        this.itemsLoaded = itemsLoaded;
    }

    // registerEvents logs and skips a listener whose event class is missing instead of throwing, so the event is
    // linked first.
    static CustomItemProvider create(Plugin owner, Runnable itemsLoaded) {
        ApiLink.staticMethod(NexoItems.class, "idFromItem", String.class, ItemStack.class);
        ApiLink.staticMethod(NexoItems.class, "exists", boolean.class, String.class);
        ApiLink.staticMethod(NexoItems.class, "itemFromId", ItemBuilder.class, String.class);
        ApiLink.staticMethod(NexoItems.class, "itemNames", Set.class);
        ApiLink.instanceMethod(ItemBuilder.class, "build", ItemStack.class);
        ApiLink.staticMethod(NexoItemsLoadedEvent.class, "getHandlerList", HandlerList.class);
        NexoProvider provider = new NexoProvider(
            id -> NexoItems.exists(id),
            id -> {
                ItemBuilder builder = NexoItems.itemFromId(id);
                return builder == null ? null : builder.build();
            },
            item -> NexoItems.idFromItem(item),
            itemsLoaded);
        return start(owner, provider, () -> !NexoItems.itemNames().isEmpty());
    }

    // The listener is registered before the item names are read, so a load that ends in between is seen by the
    // event.
    static NexoProvider start(Plugin owner, NexoProvider provider, BooleanSupplier knowsItems) {
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
            owner.getLogger().warning("Nexo does not say which items it has loaded (" + missing
                + "), so Nexo items are built only after NexoItemsLoadedEvent.");
        } catch (RuntimeException failure) {
            HandlerList.unregisterAll(provider);
            throw failure;
        }
        return provider;
    }

    static void forgetLoad() {
        seenLoad = false;
    }

    // May run on any thread.
    @EventHandler(priority = EventPriority.MONITOR)
    public void loaded(NexoItemsLoadedEvent event) {
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
