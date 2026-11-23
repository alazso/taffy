package so.alaz.taffy.hooks;

import me.arcaniax.hdb.api.DatabaseLoadEvent;
import me.arcaniax.hdb.api.HeadDatabaseAPI;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

// Builds heads only: no item is read back as a head id. HeadDatabase knows no id until it has loaded its database,
// shortly after startup, so the provider builds from DatabaseLoadEvent. HeadDatabase-API has no query for whether
// the database is loaded. A DatabaseLoadEvent seen by any provider is remembered for the server run, so a provider
// created later builds at once. A provider created after a load that no provider saw asks for a random head and
// builds at once when it gets one. A database that loaded with no head at all gives none, so that provider waits for
// the next DatabaseLoadEvent. Both flags only go from false to true, so they need no lock.
final class HeadDatabaseProvider implements CustomItemProvider, Listener {

    private static volatile boolean seenLoad;

    private final Runnable itemsLoaded;

    private volatile boolean loaded;

    private HeadDatabaseProvider(Runnable itemsLoaded) {
        this.itemsLoaded = itemsLoaded;
    }

    // registerEvents logs and skips a listener whose event class is missing instead of throwing, so the event is
    // linked first. The listener is registered before the random head is asked for, so a load that ends in between
    // is seen by the event.
    static CustomItemProvider create(Plugin owner, Runnable itemsLoaded) {
        ApiLink.constructor(HeadDatabaseAPI.class);
        ApiLink.instanceMethod(HeadDatabaseAPI.class, "isHead", boolean.class, String.class);
        ApiLink.instanceMethod(HeadDatabaseAPI.class, "getItemHead", ItemStack.class, String.class);
        ApiLink.instanceMethod(HeadDatabaseAPI.class, "getRandomHead", ItemStack.class);
        ApiLink.staticMethod(DatabaseLoadEvent.class, "getHandlerList", HandlerList.class);
        HeadDatabaseProvider provider = new HeadDatabaseProvider(itemsLoaded);
        owner.getServer().getPluginManager().registerEvents(provider, owner);
        if (seenLoad || holdsAHead()) {
            provider.loaded = true;
        }
        return provider;
    }

    static void forgetLoad() {
        seenLoad = false;
    }

    // The API does not say what getRandomHead does before the database is loaded, so a call that throws counts as
    // no head.
    private static boolean holdsAHead() {
        try {
            return new HeadDatabaseAPI().getRandomHead() != null;
        } catch (RuntimeException | LinkageError unanswered) {
            return false;
        }
    }

    // May run on any thread.
    @EventHandler(priority = EventPriority.MONITOR)
    public void loaded(DatabaseLoadEvent event) {
        seenLoad = true;
        loaded = true;
        itemsLoaded.run();
    }

    @Override
    public boolean ready() {
        return loaded;
    }

    @Override
    public @Nullable ItemStack create(String id) {
        HeadDatabaseAPI api = new HeadDatabaseAPI();
        // getItemHead returns a placeholder head for an unknown id.
        return api.isHead(id) ? api.getItemHead(id) : null;
    }

    @Override
    public boolean readsIds() {
        return false;
    }

    @Override
    public @Nullable String id(ItemStack item) {
        return null;
    }
}
