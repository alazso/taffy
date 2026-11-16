package so.alaz.taffy.hooks;

import dev.lone.itemsadder.api.CustomStack;
import dev.lone.itemsadder.api.Events.ItemsAdderLoadDataEvent;
import dev.lone.itemsadder.api.ItemsAdder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

// CustomStack.byItemStack reads ItemsAdder's item registry, which is empty until ItemsAdder first loads its items,
// so the provider is ready from ItemsAdderLoadDataEvent, or at once when the load finished before the owner
// started. The flag only goes from false to true, so it needs no lock.
final class ItemsAdderProvider implements CustomItemProvider, Listener {

    private volatile boolean loaded;

    private ItemsAdderProvider() {
    }

    // registerEvents logs and skips a listener whose event class is missing instead of throwing, so the event is
    // linked first. The listener is registered before the loaded check, so a load that ends in between is seen by
    // the event.
    static CustomItemProvider create(Plugin owner) {
        ApiLink.staticMethod(CustomStack.class, "byItemStack", CustomStack.class, ItemStack.class);
        ApiLink.instanceMethod(CustomStack.class, "getNamespacedID", String.class);
        ApiLink.staticMethod(CustomStack.class, "getInstance", CustomStack.class, String.class);
        ApiLink.instanceMethod(CustomStack.class, "getItemStack", ItemStack.class);
        ApiLink.staticMethod(ItemsAdderLoadDataEvent.class, "getHandlerList", HandlerList.class);
        ItemsAdderProvider provider = new ItemsAdderProvider();
        owner.getServer().getPluginManager().registerEvents(provider, owner);
        try {
            if (loadedNow()) {
                provider.loaded = true;
            }
        } catch (LinkageError missing) {
            owner.getLogger().warning("ItemsAdder does not say whether its items are loaded (" + missing
                + "), so ItemsAdder item ids are read only after ItemsAdderLoadDataEvent.");
        } catch (RuntimeException failure) {
            HandlerList.unregisterAll(provider);
            throw failure;
        }
        return provider;
    }

    @SuppressWarnings("deprecation")
    private static boolean loadedNow() {
        return ItemsAdder.areItemsLoaded();
    }

    // May run on any thread.
    @EventHandler(priority = EventPriority.MONITOR)
    public void loaded(ItemsAdderLoadDataEvent event) {
        loaded = true;
    }

    @Override
    public boolean ready() {
        return loaded;
    }

    @Override
    public @Nullable ItemStack create(String id) {
        CustomStack stack = CustomStack.getInstance(id);
        return stack != null ? stack.getItemStack() : null;
    }

    @Override
    public @Nullable String id(ItemStack item) {
        CustomStack stack = CustomStack.byItemStack(item);
        return stack == null ? null : stack.getNamespacedID();
    }
}
