package so.alaz.taffy.hooks;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ErrorReports;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * The NPCs of an NPC plugin: where an NPC stands, and the clicks players make on NPCs. An NPC is named by its id,
 * which is text whose form depends on the NPC plugin: {@link NpcPlugin} states it for each. The provider makes,
 * changes and removes no NPC.
 *
 * <p>A click is a left or a right click of a player on an NPC, and the listener is not told which. One right click
 * is passed once: Citizens and FancyNpcs fire for the main hand only, and ZNPCsPlus lets one interaction through for
 * a player in 100 milliseconds, which leaves out the off hand. Citizens fires a left click only for a hit that did
 * no damage, as on a protected NPC, which a Citizens NPC is unless its owner changed that. FancyNpcs fires after its
 * own interaction cooldown of the NPC let the click through. A click another plugin cancelled in the NPC plugin's
 * event is not passed.
 *
 * <p>The listener is called on the thread that owns the player: the main thread on Paper, and on Folia the region
 * thread of the player. Citizens and FancyNpcs fire their events on that thread, and the listener runs inside the
 * event. ZNPCsPlus fires its event on a network thread, so the provider hands the click to the player's scheduler
 * for the owner, and the listener runs on the player's next tick; a click of a player who left before that tick is
 * dropped.
 *
 * <p>A failure inside the NPC plugin is logged as a warning, at most once a minute for each kind of call with its
 * stack the first time, and reported under {@code hook.<plugin key>}, such as {@code hook.citizens}; the call then
 * answers as if it found nothing. A listener that throws is logged and reported in the same way, and goes on
 * receiving clicks. An {@link Error} other than a {@link LinkageError} propagates.
 *
 * @since 0.4.12
 */
public interface NpcProvider {

    /**
     * Detects an NPC plugin and builds a provider on it. The plugin that calls this lists the NPC plugin in its
     * {@code paper-plugin.yml} under {@code dependencies.server} with {@code load: BEFORE} and
     * {@code required: false}, so the NPC plugin is enabled first when it is installed. The NPC plugin is read only
     * when it is switched on, installed, enabled and its API classes are visible, and it is {@link HookState#FAILED}
     * when a method or an event the provider reads is missing; otherwise the result carries the state that stopped it
     * and no provider. An NPC plugin that is not visible or lacks the API is warned about; one that could not be
     * hooked is warned about and reported under {@code hook.<plugin key>}.
     *
     * <p>Call on the server thread while the owner is enabled, normally from its {@code onEnable}: the provider
     * registers one listener of the NPC plugin's click events for the owner, once, whether or not a click listener
     * is set. The server unregisters it when the owner disables, and {@link #close()} unregisters it earlier. To
     * detect again, such as when the owner reloads its settings, close the held provider, then detect again.
     *
     * @param owner the plugin that reads NPCs: its server's plugin manager is searched, its logger receives every
     *     warning, and the provider's listener and the hand-over of a click to a player's thread are registered for
     *     it
     * @param errors receives a failed detection, failures inside the NPC plugin and a click listener that throws
     * @param switchedOn whether the owner switched the NPC plugin on; {@code false} gives
     *     {@link HookState#SWITCHED_OFF} and looks at nothing
     * @param plugin the NPC plugin to read NPCs through
     * @return the detection result, with a provider when detection found the NPC plugin {@link HookState#ON}; its
     *     status is read when asked
     * @throws NullPointerException if {@code owner}, {@code errors} or {@code plugin} is {@code null}
     */
    static Detected<NpcProvider> detect(Plugin owner, ErrorReports errors, boolean switchedOn, NpcPlugin plugin) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(plugin, "plugin");
        return PluginNpcs.detect(owner, errors, switchedOn, plugin,
            new PluginPresence(owner.getServer().getPluginManager(), owner.getLogger()),
            clicks -> switch (plugin) {
                case CITIZENS -> CitizensNpcBackend.create(clicks);
                case FANCYNPCS -> FancyNpcsBackend.create(clicks);
                case ZNPCSPLUS -> ZnpcsPlusBackend.create(clicks);
            });
    }

    /**
     * The NPC plugin this provider reads NPCs through.
     *
     * @return the NPC plugin
     */
    NpcPlugin plugin();

    /**
     * Whether calls can reach the NPCs: the provider is not {@linkplain #close() closed} and the NPC plugin is
     * enabled.
     *
     * @return {@code true} if a location can be looked up and clicks are passed
     */
    boolean available();

    /**
     * Where an NPC stands now. Call it on the main thread on Paper. On Folia call it on the global region thread,
     * and for Citizens, whose NPC is an entity, on the region thread that owns the NPC.
     *
     * <p>An NPC that is not spawned has no location: a Citizens NPC that is despawned, a FancyNpcs NPC whose
     * {@code spawnEntity} setting is off, and a ZNPCsPlus NPC that is not enabled. FancyNpcs loads its NPCs five
     * seconds after it is enabled, so its NPCs are not found from an {@code onEnable}.
     *
     * @param id the NPC's id, in the form {@link NpcPlugin} states for the NPC plugin
     * @return a location of the caller's own, or {@code null} if the provider is not {@linkplain #available()
     *     available}, the id is not of the NPC plugin's form or names no NPC, the NPC is not spawned, the world it
     *     stands in is not loaded, or the lookup fails; only a failed lookup is logged and reported
     * @throws NullPointerException if {@code id} is {@code null}
     */
    @Nullable Location location(String id);

    /**
     * Sets the listener that receives every click on an NPC of the NPC plugin, in place of the listener set before.
     * It receives the player and the NPC's id in the form {@link NpcPlugin} states, on the thread that owns the
     * player, and a click on any NPC of the NPC plugin, also one the owner does not use. It may be called from any
     * thread. No listener is called after {@link #close()} or after the owner disabled.
     *
     * @param listener receives the player who clicked and the id of the NPC
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    void onClick(BiConsumer<Player, String> listener);

    /**
     * Stops the provider: its listener of the NPC plugin's click events is unregistered and the click listener is
     * dropped, {@link #available()} then answers {@code false} and {@link #location(String)} {@code null}. A second
     * call does nothing. The owner calls it before detecting again; at disable it is not needed, since the server
     * unregisters the listener and cancels a click still on its way to a player's thread.
     */
    void close();
}
