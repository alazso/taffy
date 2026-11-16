package so.alaz.taffy.hooks;

import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import so.alaz.taffy.platform.ErrorReports;

import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Region lookups at a location. A failure inside the region plugin is logged as a warning and reported, not thrown,
 * except an {@link Error} other than a {@link LinkageError}, which propagates. A lookup may run on any thread, since
 * WorldGuard's region index supports concurrent reads.
 */
public interface RegionProvider {

    /**
     * Detects WorldGuard and builds a provider on it. The plugin that calls this lists WorldGuard in its
     * {@code paper-plugin.yml} with {@code load: BEFORE} and {@code required: false}, so WorldGuard is enabled first
     * when it is installed. WorldGuard is read only when it is switched on, installed, enabled and its API classes
     * are visible, and it is {@link HookState#FAILED} when a method the provider calls is missing; otherwise the
     * result carries the state that stopped it and no provider. A WorldGuard that is not visible or lacks the API is
     * warned about; one that could not be hooked is warned about and reported to {@code errors} under
     * {@code hook.worldguard}. A failure inside a later lookup is logged as a warning, at most once a minute with its
     * stack the first time, and reported under {@code hook.worldguard} too.
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
        return Detection.run("WorldGuard", "com.sk89q.worldguard.WorldGuard", switchedOn,
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
}
