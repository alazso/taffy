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
 * change is saved, so they run off the server thread: a write called on a server thread throws
 * {@link IllegalStateException}. A server thread is one {@link org.bukkit.Server#isPrimaryThread()} is {@code true}
 * on, which is the main thread on Paper and every region thread and the global region thread on Folia. The check
 * comes after the arguments are checked and before the permission plugin is asked, so nothing is loaded or changed,
 * and it is made whether or not the plugin is available. Reads are not checked. A write from {@code onDisable} is
 * refused the same way: {@code Scheduler.async} returns {@code false} there and never runs the task, and
 * {@code Scheduler.asyncOrRun} runs it on the calling server thread, where the write throws, so such a write is made
 * before the plugin disables. A write returns {@code true} only when the data changed and was saved, and
 * {@code false} when nothing changed, the plugin is not available, or the write failed. Once a change is saved it is pushed through the permission plugin's messaging service, when one is
 * set up, so the other servers of a network apply it without waiting for their next sync. A push that fails is
 * logged and reported like any other failure and the write still returns {@code true}, since the change is saved;
 * with no messaging service nothing is pushed. A failure inside the permission plugin is logged as a warning and
 * reported, not thrown, except an {@link Error} other than a {@link LinkageError}, which propagates. A {@code null}
 * argument throws {@link NullPointerException}.
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
            new PluginPresence(owner.getServer().getPluginManager()),
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
     * @throws IllegalStateException if called on a server thread
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
     * @throws IllegalStateException if called on a server thread
     */
    boolean addTempGroup(UUID player, String group, Duration duration);

    /**
     * Removes the player from a group: every membership of the group set directly on the player, permanent or
     * temporary, in every context. The group name is matched without regard to case. Only saved data is changed: a
     * membership held for the session only is left in place, and so is one the player inherits through another group.
     *
     * @param player the player's unique id
     * @param group the group name
     * @return {@code true} if the player held at least one such membership and the change was saved
     * @throws IllegalStateException if called on a server thread
     */
    boolean removeGroup(UUID player, String group);

    /**
     * Sets a permission node on the player, without context and without an expiry. A node of the same name already
     * set directly on the player without context is replaced, whatever its value or expiry: a granted node becomes
     * denied, a denied one granted, and a temporary one permanent. A node of the same name set in a context is left as
     * it is. Only a plain permission node is counted as held and replaced: a node the permission plugin keeps as
     * another kind under that name, such as the group membership {@code group.vip}, is left as it is. Only saved data
     * is changed: a node held for the session only is left in place. When the permission plugin refuses the new node
     * once the held ones are cleared, that is logged and reported as a failure and the write still saves and returns
     * {@code true}, since the held nodes are gone.
     *
     * @param player the player's unique id
     * @param node the permission node
     * @param value {@code true} to grant the node, {@code false} to deny it
     * @return {@code true} if the data changed and was saved, {@code false} if the player already holds the node
     *     without context and without an expiry at this value
     * @throws IllegalStateException if called on a server thread
     */
    boolean setPermission(UUID player, String node, boolean value);

    /**
     * Clears a permission node set directly on the player, whatever its value or context. Only saved data is changed:
     * a node held for the session only is left in place.
     *
     * @param player the player's unique id
     * @param node the permission node
     * @return {@code true} if the node was set and the change was saved
     * @throws IllegalStateException if called on a server thread
     */
    boolean unsetPermission(UUID player, String node);
}
