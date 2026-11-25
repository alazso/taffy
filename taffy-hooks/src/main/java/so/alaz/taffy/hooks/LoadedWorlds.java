package so.alaz.taffy.hooks;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

final class LoadedWorlds {

    private LoadedWorlds() {
    }

    // The location's world when it is the world the server has loaded under that world's id, or null. A Location
    // keeps returning an unloaded World while that object is referenced, and throws only once it has been collected.
    static @Nullable World of(Server server, Location location) {
        World world;
        try {
            world = location.getWorld();
        } catch (IllegalArgumentException unloaded) {
            return null;
        }
        return world != null && server.getWorld(world.getUID()) == world ? world : null;
    }
}
