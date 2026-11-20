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
// the database is loaded, so a provider created after the load asks for a random head and builds at once when it
// gets one. A database that loaded with no head at all gives none, so a provider created after that load waits for
// the next DatabaseLoadEvent. The flag only goes from false to true, so it needs no lock.
final class HeadDatabaseProvider implements CustomItemProvider, Listener {

    private volatile boolean loaded;

    private HeadDatabaseProvider() {
    }

    // registerEvents logs and skips a listener whose event class is missing instead of throwing, so the event is
    // linked first. The listener is registered before the random head is asked for, so a load that ends in between
    // is seen by the event.
    static CustomItemProvider create(Plugin owner) {
        ApiLink.constructor(HeadDatabaseAPI.class);
        ApiLink.instanceMethod(HeadDatabaseAPI.class, "isHead", boolean.class, String.class);
        ApiLink.instanceMethod(HeadDatabaseAPI.class, "getItemHead", ItemStack.class, String.class);
        ApiLink.instanceMethod(HeadDatabaseAPI.class, "getRandomHead", ItemStack.class);
        ApiLink.staticMethod(DatabaseLoadEvent.class, "getHandlerList", HandlerList.class);
        HeadDatabaseProvider provider = new HeadDatabaseProvider();
        owner.getServer().getPluginManager().registerEvents(provider, owner);
        if (holdsAHead()) {
            provider.loaded = true;
        }
        return provider;
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
        loaded = true;
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
