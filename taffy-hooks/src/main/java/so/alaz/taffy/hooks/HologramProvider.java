package so.alaz.taffy.hooks;

import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ErrorReports;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Temporary text holograms, each named by an id the owner chooses. A provider from {@link #detect} places them
 * through a hologram plugin.
 *
 * <p>An id is 1 to 48 of {@code a-z}, {@code 0-9}, {@code _} and {@code -}. A hologram is stored under its id after a
 * prefix made from the owner's name, in lower case with every character outside {@code a-z}, {@code 0-9} and
 * {@code -} written as {@code -}, and a {@code _}, as in {@code slate_crate-1}. Two plugins share a prefix only when
 * their names differ in nothing but characters written as {@code -}. Every call but {@link #create} reaches only a
 * hologram this provider placed, and {@link #create} refuses a name another hologram holds, such as one a server
 * owner made with the hologram plugin's own commands or another plugin placed under the same prefix.
 *
 * <p>Every hologram is temporary: it is not saved, so it is gone after a restart and the owner places it again. A
 * hologram is also removed when its world unloads. A line is MiniMessage, as in {@code <gold>Daily crate}; the
 * provider converts it for a hologram plugin that reads legacy colour codes. A PlaceholderAPI placeholder in a line
 * is filled in for each viewer by the hologram plugins whose {@link HologramPlugin#placeholdersPerViewer()} is
 * {@code true}.
 *
 * <p>A failure inside the hologram plugin is logged as a warning, at most once a minute with its stack the first
 * time, and reported under {@code hook.<plugin key>}, such as {@code hook.decentholograms}; the call then answers as
 * if it found nothing. An {@link Error} other than a {@link LinkageError} propagates.
 *
 * <p>Call every method on the server's global thread: the main thread on Paper and the global region thread on Folia.
 * The provider's own records are concurrent, but it calls the hologram plugin on the calling thread, and a hologram
 * plugin is to be called only from the global thread.
 */
public interface HologramProvider {

    /**
     * Detects a hologram plugin and builds a provider on it. The plugin that calls this lists the hologram plugin in
     * its {@code paper-plugin.yml} under {@code dependencies.server} with {@code load: BEFORE} and
     * {@code required: false}, so the hologram plugin is enabled first when it is installed. The hologram plugin is
     * read only when it is switched on, installed, enabled and its API classes are visible, and it is
     * {@link HookState#FAILED} when a method the provider calls is missing; otherwise the result carries the state that
     * stopped it and no provider. A hologram plugin that is not visible or lacks the API is warned about; one that
     * could not be hooked is warned about and reported under {@code hook.<plugin key>}.
     *
     * <p>Call on the server thread while the owner is enabled, normally from its {@code onEnable}: the provider
     * registers a listener for the owner that removes a hologram when its world unloads, so each call adds one more
     * listener until the owner disables.
     *
     * @param owner the plugin that places holograms: its server's plugin manager is searched, its logger receives
     *     every warning, its name prefixes every hologram it places, and the provider's listener is registered for it
     * @param errors receives a failed detection and failures inside the hologram plugin
     * @param switchedOn whether the owner switched the hologram plugin on; {@code false} gives
     *     {@link HookState#SWITCHED_OFF} and looks at nothing
     * @param plugin the hologram plugin to place holograms through
     * @return the detection result, with a provider when detection found the hologram plugin {@link HookState#ON};
     *     its status is read when asked
     * @throws NullPointerException if {@code owner}, {@code errors} or {@code plugin} is {@code null}
     */
    static Detected<HologramProvider> detect(Plugin owner, ErrorReports errors, boolean switchedOn,
                                             HologramPlugin plugin) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(plugin, "plugin");
        return PluginHolograms.detect(owner, errors, switchedOn, plugin,
            new PluginPresence(owner.getServer().getPluginManager(), owner.getLogger()),
            bound -> switch (plugin) {
                case DECENTHOLOGRAMS -> DecentHologramsBackend.create();
                case FANCYHOLOGRAMS -> FancyHologramsBackend.create();
                case HOLOGRAMS -> NextLvlHologramsBackend.create();
                case HOLOGRAMLIB -> HologramLibBackend.create(bound);
            });
    }

    /**
     * The hologram plugin this provider places holograms through.
     *
     * @return the hologram plugin, or {@code null} for a provider bound to no hologram plugin
     */
    @Nullable HologramPlugin plugin();

    /**
     * Whether calls can reach the holograms: for a provider bound to a hologram plugin, whether that plugin is enabled.
     *
     * @return {@code true} if calls can place, change and remove holograms
     */
    boolean available();

    /**
     * Places a temporary hologram.
     *
     * @param id the hologram's id
     * @param location where the hologram stands; the provider keeps a copy
     * @param lines the hologram's lines in MiniMessage, top first; may be empty
     * @return {@code true} if the hologram was placed, or the placement was accepted to be done on the location's
     *     region thread a little later; {@code false} if the provider is not {@linkplain #available() available}, the
     *     location has no world or its world has unloaded, any hologram already holds the prefixed name, or the
     *     hologram plugin failed
     * @throws NullPointerException if an argument or a line is {@code null}
     * @throws IllegalArgumentException if {@code id} is not a valid id
     */
    boolean create(String id, Location location, List<String> lines);

    /**
     * Replaces every line of a hologram.
     *
     * @param id the hologram's id
     * @param lines the new lines in MiniMessage, top first; may be empty
     * @return {@code true} if the lines were replaced, or the replacement was accepted to be done on the region
     *     thread of the hologram's location a little later; {@code false} if the provider is not
     *     {@linkplain #available() available}, no hologram this provider placed with this id stands, or the hologram
     *     plugin failed
     * @throws NullPointerException if an argument or a line is {@code null}
     * @throws IllegalArgumentException if {@code id} is not a valid id
     */
    boolean update(String id, List<String> lines);

    /**
     * Moves a hologram.
     *
     * @param id the hologram's id
     * @param location where the hologram stands from now; the provider keeps a copy
     * @return {@code true} if the move was done, or was accepted to be done on the location's region thread a
     *     little later; {@code false} if the provider is not {@linkplain #available() available}, the location has no
     *     world or its world has unloaded, no hologram this provider placed with this id stands, or the hologram
     *     plugin failed. Holograms moves the hologram on the location's region thread a little later, where another
     *     plugin may still refuse the move, and HologramLib moves a hologram placed this tick two ticks later.
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code id} is not a valid id
     */
    boolean move(String id, Location location);

    /**
     * Removes a hologram.
     *
     * @param id the hologram's id
     * @return {@code true} if a hologram this provider placed with this id stood and was removed, or its removal was
     *     accepted to be done on the region thread of its location a little later; {@code false} if the provider is
     *     not {@linkplain #available() available}, no such hologram stands, the hologram plugin or another plugin
     *     refused the removal, or the hologram plugin failed. A hologram that was not removed stays in
     *     {@link #ids()}.
     * @throws NullPointerException if {@code id} is {@code null}
     * @throws IllegalArgumentException if {@code id} is not a valid id
     */
    boolean remove(String id);

    /**
     * Whether a hologram with an id stands.
     *
     * @param id the hologram's id
     * @return {@code true} if a hologram this provider placed with this id stands
     * @throws NullPointerException if {@code id} is {@code null}
     * @throws IllegalArgumentException if {@code id} is not a valid id
     */
    boolean exists(String id);

    /**
     * The ids of the holograms this provider placed that still stand. A hologram the hologram plugin dropped, such as
     * on its own reload or disable, is no longer listed.
     *
     * @return the ids in an unmodifiable set, empty when the provider is not {@linkplain #available() available}
     */
    Set<String> ids();

    /**
     * Removes every hologram this provider placed that still stands, such as from the owner's {@code onDisable}.
     *
     * @return how many holograms were removed
     */
    int removeAll();
}
