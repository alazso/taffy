package so.alaz.taffy.hooks;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import so.alaz.taffy.platform.ErrorReports;

import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Region lookups at a location. A failure inside the region plugin is logged as a warning and reported, not thrown,
 * except an {@link Error} other than a {@link LinkageError}, which propagates. A lookup of region ids may run on any
 * thread, since WorldGuard's region index supports concurrent reads. A build check runs on the thread that owns the
 * player.
 */
public interface RegionProvider {

    /**
     * Detects WorldGuard and builds a provider on it. The plugin that calls this lists WorldGuard, and also
     * WorldEdit, in its {@code paper-plugin.yml} with {@code load: BEFORE} and {@code required: false}, so WorldGuard
     * is enabled first when it is installed. WorldGuard is read only when it is switched on, installed, enabled and
     * its API classes are visible, and it is {@link HookState#FAILED} when a method {@link #regionsAt(Location)}
     * calls is missing; otherwise the result carries the state that stopped it and no provider. A lookup turns a
     * location into a WorldEdit position through WorldEdit's {@code BukkitAdapter}, so a WorldGuard that is visible
     * while that class is not is {@link HookState#NOT_VISIBLE}, with a reason that names the missing WorldEdit entry.
     * A WorldGuard that is not visible or lacks the API is warned about; one that could not be hooked is warned about
     * and reported to {@code errors} under {@code hook.worldguard}. A failure inside a later lookup or build check is
     * logged as a warning, at most once a minute for each of the two with its stack the first time, and reported
     * under {@code hook.worldguard} too. The methods {@link #canBuild(Player, Location)} calls are not looked for at
     * detection: with a WorldGuard that lacks one, every build check fails in that way.
     *
     * @param owner the plugin that uses WorldGuard: its server's plugin manager is searched and its logger receives
     *     every warning
     * @param errors receives a failed detection and failures inside WorldGuard calls
     * @param switchedOn whether the owner switched WorldGuard on; {@code false} gives {@link HookState#SWITCHED_OFF}
     *     and looks at nothing
     * @return the detection result, with a provider when detection found WorldGuard {@link HookState#ON}; its status
     *     is read when asked
     * @throws NullPointerException if {@code owner} or {@code errors} is {@code null}
     */
    static Detected<RegionProvider> worldGuard(Plugin owner, ErrorReports errors, boolean switchedOn) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(errors, "errors");
        Logger logger = owner.getLogger();
        return Detection.run("WorldGuard", "com.sk89q.worldguard.WorldGuard",
            new Detection.Through("WorldEdit", "com.sk89q.worldedit.bukkit.BukkitAdapter"), switchedOn,
            new PluginPresence(owner.getServer().getPluginManager(), logger),
            plugin -> WorldGuardRegions.create(plugin, logger, errors), logger, errors);
    }

    /**
     * Whether the region plugin is enabled.
     *
     * @return {@code true} if calls can reach the region plugin
     */
    boolean available();

    /**
     * The ids of every region covering the block at a location, as the region plugin stores them. The region plugin
     * may keep ids in a different case from the one an owner typed, and WorldGuard lowers them in the server's default
     * locale, which on a Turkish server turns {@code I} into a dotless i. Callers therefore compare ids with
     * {@link String#equalsIgnoreCase(String)}, which matches both forms, and not after lowering them in a fixed
     * locale.
     *
     * @param location the location to look up
     * @return the region ids in an unmodifiable list, empty when the region plugin is not available, the location has
     *     no world or its world has unloaded, the region plugin keeps no regions for that world, or the lookup fails;
     *     only a failed lookup is logged and reported
     * @throws NullPointerException if {@code location} is {@code null}
     */
    List<String> regionsAt(Location location);

    /**
     * Whether the region plugin lets a player build at a location. The WorldGuard provider answers {@code true} for a
     * player WorldGuard's own listeners let through, which is one with the permission
     * {@code worldguard.region.bypass.<world>} for the location's world who has not turned the bypass off, and a
     * player another plugin marked as an NPC while WorldGuard's {@code fake-player-build-override} is on. For every
     * other player it answers what WorldGuard's region query gives for the {@code build} flag at the location: the
     * regions covering it, their members and owners, their priorities and the global region. A world WorldGuard's
     * regions are turned off in answers {@code true}, and a world whose region data WorldGuard could not load answers
     * {@code false}. A player who has left the server is asked about in the same way and has no bypass. The flags
     * WorldGuard's listeners read beside {@code build}, such as {@code block-break} and {@code block-place}, are not
     * read.
     *
     * <p>The call is made on the thread that owns the player: the main thread on Paper, the player's region thread on
     * Folia. It asks the region plugin on the calling thread.
     *
     * <p>A provider that has no build check answers {@code true}, which is what this method does unless it is
     * overridden.
     *
     * @param player the player who builds
     * @param location the location of the block
     * @return {@code false} only when the region plugin refuses; {@code true} when it allows, and also when the region
     *     plugin is not available, the location has no world or its world has unloaded, or the check fails; only a
     *     failed check is logged and reported
     * @throws NullPointerException if {@code player} or {@code location} is {@code null}
     * @since 0.4.12
     */
    default boolean canBuild(Player player, Location location) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(location, "location");
        return true;
    }
}
