package so.alaz.taffy.hooks;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.internal.platform.WorldGuardPlatform;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.platform.ThrowLog;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;

// The static WorldGuard and BukkitAdapter calls sit in the factory's lambdas, so neither class initializes until a
// lookup runs. Linking their methods in create does not initialize them either. WorldGuard.getInstance and
// WorldGuard.getPlatform are not linked: linking verifies the WorldGuard class, which needs classes from
// WorldGuard's own dependencies that its API jar does not hold.
final class WorldGuardRegions implements RegionProvider {

    private static final String SOURCE = "hook.worldguard";

    private final Plugin worldGuard;
    private final ThrowLog throwLog;
    private final ErrorReports errors;
    private final Function<World, @Nullable RegionManager> managers;
    private final Function<Location, BlockVector3> vectors;

    WorldGuardRegions(Plugin worldGuard, Logger logger, ErrorReports errors,
                      Function<World, @Nullable RegionManager> managers, Function<Location, BlockVector3> vectors) {
        this.worldGuard = worldGuard;
        this.throwLog = new ThrowLog(logger);
        this.errors = errors;
        this.managers = managers;
        this.vectors = vectors;
    }

    static RegionProvider create(Plugin plugin, Logger logger, ErrorReports errors) {
        ApiLink.instanceMethod(WorldGuardPlatform.class, "getRegionContainer", RegionContainer.class);
        ApiLink.instanceMethod(RegionContainer.class, "get", RegionManager.class,
            com.sk89q.worldedit.world.World.class);
        ApiLink.staticMethod(BukkitAdapter.class, "adapt", com.sk89q.worldedit.world.World.class, World.class);
        ApiLink.staticMethod(BukkitAdapter.class, "asBlockVector", BlockVector3.class, Location.class);
        ApiLink.instanceMethod(RegionManager.class, "getApplicableRegions", ApplicableRegionSet.class,
            BlockVector3.class);
        ApiLink.instanceMethod(ApplicableRegionSet.class, "getRegions", Set.class);
        ApiLink.instanceMethod(ProtectedRegion.class, "getId", String.class);
        return new WorldGuardRegions(plugin, logger, errors,
            world -> WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world)),
            location -> BukkitAdapter.asBlockVector(location));
    }

    @Override
    public boolean available() {
        return worldGuard.isEnabled();
    }

    @Override
    public List<String> regionsAt(Location location) {
        Objects.requireNonNull(location, "location");
        if (!worldGuard.isEnabled()) {
            return List.of();
        }
        World world;
        try {
            world = location.getWorld();
        } catch (IllegalArgumentException unloaded) {
            // Location.getWorld throws when the world it referred to has unloaded.
            return List.of();
        }
        if (world == null) {
            return List.of();
        }
        try {
            RegionManager manager = managers.apply(world);
            if (manager == null) {
                return List.of();
            }
            return manager.getApplicableRegions(vectors.apply(location)).getRegions().stream()
                .map(ProtectedRegion::getId)
                .toList();
        } catch (Exception | LinkageError ex) {
            throwLog.warn("regions", "WorldGuard could not look up the regions at a location (" + ex + ").", ex);
            errors.report(SOURCE, ex);
            return List.of();
        }
    }
}
