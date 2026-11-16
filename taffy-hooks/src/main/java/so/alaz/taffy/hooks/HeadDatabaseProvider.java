package so.alaz.taffy.hooks;

import me.arcaniax.hdb.api.HeadDatabaseAPI;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

// Builds heads only: no item is read back as a head id. HeadDatabase knows no id until it has loaded its database,
// shortly after startup, so create gives null until then.
final class HeadDatabaseProvider implements CustomItemProvider {

    private HeadDatabaseProvider() {
    }

    static CustomItemProvider create() {
        ApiLink.constructor(HeadDatabaseAPI.class);
        ApiLink.instanceMethod(HeadDatabaseAPI.class, "isHead", boolean.class, String.class);
        ApiLink.instanceMethod(HeadDatabaseAPI.class, "getItemHead", ItemStack.class, String.class);
        return new HeadDatabaseProvider();
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
