package so.alaz.taffy.hooks;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ErrorReports;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Group lookups and permission writes for a player. Plain permission checks go through Bukkit and are not part of
 * this interface.
 *
 * <p>Reads come from the permission plugin's cache: a player whose data is not loaded, usually one who is offline,
 * gives {@code null} or an empty list. Writes load the player's data when it is not cached, then block until the
 * change is saved, so they run off the server thread. A write returns {@code true} only when the data changed and was
 * saved, and {@code false} when nothing changed, the plugin is not available, or the write failed. A failure inside
 * the permission plugin is logged as a warning and reported, not thrown, except an {@link Error} other than a
 * {@link LinkageError}, which propagates. A {@code null} argument throws {@link NullPointerException}.
 */
public interface PermissionProvider {

    /**
     * Detects LuckPerms and builds a provider on it. The plugin that calls this lists LuckPerms in its
     * {@code paper-plugin.yml} with {@code load: BEFORE} and {@code required: false}, so LuckPerms is enabled first
     * when it is installed. LuckPerms is read only when it is switched on, installed, enabled and its API classes
     * are visible, and it is {@link HookState#FAILED} when a method the provider calls is missing; otherwise the
     * result carries the state that stopped it and no provider. A LuckPerms that is not visible or lacks the API is
     * warned about; one that could not be hooked is warned about and reported to {@code errors} under
     * {@code hook.luckperms}. A failure inside a later call is logged as a warning, at most once a minute for each
     * kind of call with its stack the first time, and reported under {@code hook.luckperms} too.
     *
     * @param owner the plugin that uses LuckPerms: its server's plugin manager is searched and its logger receives
     *     every warning
     * @param errors receives a failed detection and failures inside LuckPerms calls
     * @param switchedOn whether the owner switched LuckPerms on; {@code false} gives {@link HookState#SWITCHED_OFF}
     *     and looks at nothing
     * @return the detection result, with a provider when detection found LuckPerms {@link HookState#ON}; its status
     *     is read when asked
     * @throws NullPointerException if {@code owner} or {@code errors} is {@code null}
     */
    static Detected<PermissionProvider> luckPerms(Plugin owner, ErrorReports errors, boolean switchedOn) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(errors, "errors");
        Logger logger = owner.getLogger();
        return Detection.run("LuckPerms", "net.luckperms.api.LuckPerms", switchedOn,
            new PluginPresence(owner.getServer().getPluginManager(), logger),
            plugin -> LuckPermsPermissions.create(plugin, logger, errors), logger, errors);
    }

    /**
     * Whether the permission plugin is enabled and has finished loading.
     *
     * @return {@code true} if calls can reach the permission plugin
     */
    boolean available();

    /**
     * The player's primary group.
     *
     * @param player the player's unique id
     * @return the group name, or {@code null} if the player's data is not loaded or cannot be read
     */
    @Nullable
    String primaryGroup(UUID player);

    /**
     * Every group the player inherits, directly or through other groups, without context.
     *
     * @param player the player's unique id
     * @return the group names, empty if the player's data is not loaded or cannot be read
     */
    List<String> groups(UUID player);

    /**
     * Adds the player to a group permanently.
     *
     * @param player the player's unique id
     * @param group the group name
     * @return {@code true} if the player was not already in the group and the change was saved
     */
    boolean addGroup(UUID player, String group);

    /**
     * Adds the player to a group until a time from now. The duration is kept in whole seconds, rounded down. When the
     * player already belongs to the group until an earlier time, that membership is moved to the new time. When the
     * player already belongs to it until the new time or later, nothing changes. A permanent membership is left as it
     * is and the temporary one is added beside it.
     *
     * @param player the player's unique id
     * @param group the group name
     * @param duration how long the player stays in the group, at least one second
     * @return {@code true} if the data changed and was saved
     * @throws IllegalArgumentException if {@code duration} is under one second, or would end after
     *     {@link java.time.Instant#MAX}
     */
    boolean addTempGroup(UUID player, String group, Duration duration);

    /**
     * Removes the player from a group.
     *
     * @param player the player's unique id
     * @param group the group name
     * @return {@code true} if the player was in the group and the change was saved
     */
    boolean removeGroup(UUID player, String group);

    /**
     * Sets a permission node on the player, without context.
     *
     * @param player the player's unique id
     * @param node the permission node
     * @param value {@code true} to grant the node, {@code false} to deny it
     * @return {@code true} if the data changed and was saved
     */
    boolean setPermission(UUID player, String node, boolean value);

    /**
     * Clears a permission node set directly on the player, whatever its value or context. Only saved data is changed:
     * a node held for the session only is left in place.
     *
     * @param player the player's unique id
     * @param node the permission node
     * @return {@code true} if the node was set and the change was saved
     */
    boolean unsetPermission(UUID player, String node);
}
