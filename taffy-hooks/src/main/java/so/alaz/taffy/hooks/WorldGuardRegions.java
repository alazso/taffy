package so.alaz.taffy.hooks;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.internal.platform.WorldGuardPlatform;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.platform.ThrowLog;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.logging.Logger;

// The static WorldGuard and BukkitAdapter calls sit in the factory's lambdas, so neither class initializes until a
// lookup runs. Linking their methods in create does not initialize them either. WorldGuard.getInstance and
// WorldGuard.getPlatform are not linked: linking verifies the WorldGuard class, which needs classes from
// WorldGuard's own dependencies that its API jar does not hold. The methods of the build check are not linked either,
// so a WorldGuard without one of them still gives its region ids.
final class WorldGuardRegions implements RegionProvider {

    private static final String SOURCE = "hook.worldguard";

    private final Plugin worldGuard;
    private final ThrowLog throwLog;
    private final ErrorReports errors;
    private final Function<World, @Nullable RegionManager> managers;
    private final Function<Location, BlockVector3> vectors;
    private final BiPredicate<Player, World> bypasses;
    private final BiPredicate<Player, Location> builds;

    WorldGuardRegions(Plugin worldGuard, Logger logger, ErrorReports errors,
                      Function<World, @Nullable RegionManager> managers, Function<Location, BlockVector3> vectors,
                      BiPredicate<Player, World> bypasses, BiPredicate<Player, Location> builds) {
        this.worldGuard = worldGuard;
        this.throwLog = new ThrowLog(logger);
        this.errors = errors;
        this.managers = managers;
        this.vectors = vectors;
        this.bypasses = bypasses;
        this.builds = builds;
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
            location -> BukkitAdapter.asBlockVector(location),
            (player, world) -> WorldGuard.getInstance().getPlatform().getSessionManager()
                .hasBypass(WorldGuardPlugin.inst().wrapPlayer(player), BukkitAdapter.adapt(world)),
            (player, location) -> WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                .testBuild(BukkitAdapter.adapt(location), WorldGuardPlugin.inst().wrapPlayer(player)));
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
        World world = worldOf(location);
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

    @Override
    public boolean canBuild(Player player, Location location) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(location, "location");
        if (!worldGuard.isEnabled()) {
            return true;
        }
        World world = worldOf(location);
        if (world == null) {
            return true;
        }
        try {
            return bypasses.test(player, world) || builds.test(player, location);
        } catch (Exception | LinkageError ex) {
            throwLog.warn("build", "WorldGuard could not check whether a player may build at a location (" + ex
                + ").", ex);
            errors.report(SOURCE, ex);
            return true;
        }
    }

    private @Nullable World worldOf(Location location) {
        return LoadedWorlds.of(worldGuard.getServer(), location);
    }
}
