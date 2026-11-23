package so.alaz.taffy.hooks;

import net.Indyuce.mmoitems.MMOItems;
import net.Indyuce.mmoitems.api.event.MMOItemsReloadEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiFunction;
import java.util.function.Function;

// MMOItems reads its item types and templates while it loads and enables, so it builds items from the moment it is
// enabled and the provider is always ready. MMOItems.getTypeName and MMOItems.getID read the type and the id from
// the item's own NBT. MMOItems has no event for the end of a load: it fires MMOItemsReloadEvent when its reload
// command starts, before it reads its files again, and has read them by the time the command returns. MMOItems
// schedules through the Bukkit scheduler, so it runs on Paper only and its calls belong on the server thread.
final class MMOItemsProvider implements CustomItemProvider, Listener {

    private final BiFunction<String, String, @Nullable ItemStack> build;
    private final Function<ItemStack, @Nullable String> typeOf;
    private final Function<ItemStack, @Nullable String> idOf;
    private final Runnable itemsLoaded;

    MMOItemsProvider(BiFunction<String, String, @Nullable ItemStack> build,
                     Function<ItemStack, @Nullable String> typeOf, Function<ItemStack, @Nullable String> idOf,
                     Runnable itemsLoaded) {
        this.build = build;
        this.typeOf = typeOf;
        this.idOf = idOf;
        this.itemsLoaded = itemsLoaded;
    }

    // registerEvents logs and skips a listener whose event class is missing instead of throwing, so the event is
    // linked first.
    static CustomItemProvider create(Plugin owner, Runnable itemsLoaded) {
        ApiLink.staticField(MMOItems.class, "plugin", MMOItems.class);
        ApiLink.instanceMethod(MMOItems.class, "getItem", ItemStack.class, String.class, String.class);
        ApiLink.staticMethod(MMOItems.class, "getTypeName", String.class, ItemStack.class);
        ApiLink.staticMethod(MMOItems.class, "getID", String.class, ItemStack.class);
        ApiLink.staticMethod(MMOItemsReloadEvent.class, "getHandlerList", HandlerList.class);
        MMOItemsProvider provider = new MMOItemsProvider(
            (type, id) -> MMOItems.plugin.getItem(type, id),
            item -> MMOItems.getTypeName(item),
            item -> MMOItems.getID(item),
            itemsLoaded);
        owner.getServer().getPluginManager().registerEvents(provider, owner);
        return provider;
    }

    // A synchronous event on the server thread, fired before MMOItems reads its files again.
    @EventHandler(priority = EventPriority.MONITOR)
    public void reloading(MMOItemsReloadEvent event) {
        itemsLoaded.run();
    }

    // An id is <type>:<id>. MMOItems.getItem gives null for a type or an id it does not know.
    @Override
    public @Nullable ItemStack create(String id) {
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1) {
            return null;
        }
        return build.apply(id.substring(0, colon), id.substring(colon + 1));
    }

    // An item without the type tag gives a null type, and one without the id tag a null or empty id.
    @Override
    public @Nullable String id(ItemStack item) {
        String type = typeOf.apply(item);
        if (type == null || type.isEmpty()) {
            return null;
        }
        String id = idOf.apply(item);
        return id == null || id.isEmpty() ? null : type + ":" + id;
    }
}
