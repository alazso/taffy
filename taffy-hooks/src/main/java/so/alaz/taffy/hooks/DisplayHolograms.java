package so.alaz.taffy.hooks;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Holograms made of display entities, with no hologram plugin: one {@code TextDisplay} for each line and an optional
 * {@code ItemDisplay} above them. {@link HologramProvider#displays} builds one, and {@link #plugin()} answers
 * {@code null}. A plugin does not implement this interface: a later version may add methods to it.
 *
 * <p><b>Layout.</b> The location is the bottom centre of the text. The lines stack upward from it, the last line
 * lowest, {@value #LINE_SPACING} blocks apart. The item, when there is one, is centred {@value #ITEM_RISE} blocks
 * above the bottom of the top line, or above the location when there are no lines, at {@value #ITEM_SCALE} of its
 * size with the {@code FIXED} item transform. Every entity turns to face the viewer ({@code Billboard.CENTER}). A
 * line is centred, shadowed, not see-through, on the default background, and never wraps. The view range is Paper's
 * default of {@code 1.0}, about 64 blocks. Every player in range sees the hologram: there is no per-player
 * visibility, and no text is filled in for each viewer.
 *
 * <p><b>Text.</b> The methods of this interface take each line as an Adventure {@link Component} the caller built.
 * Text a player or another untrusted source supplied goes in as {@code Component.text(name)}, never through
 * MiniMessage. The methods of {@link HologramProvider} take each line as MiniMessage, parsed once when the call is
 * made, with untrusted text put through {@link HologramProvider#literal}. A PlaceholderAPI placeholder in a line
 * shows as written. A line MiniMessage refuses, such as one holding a section sign colour code, makes
 * {@link #create(String, Location, List)} and {@link #update(String, List)} answer {@code false}.
 *
 * <p><b>Ids.</b> An id is 1 to 48 of {@code a-z}, {@code 0-9}, {@code _} and {@code -}, and is not prefixed: the
 * holograms belong to this provider alone. {@link #create(String, Location, List)} answers {@code false} for an id
 * that is in use and leaves that hologram as it is, and {@link #update(String, List)}, {@link #move} and
 * {@link #remove} answer {@code false} for an id that is not in use.
 *
 * <p><b>Threads.</b> Every method may be called from any thread, on Paper and on Folia. A call changes only the
 * provider's records and answers at once. Each spawn, text change, move and removal of an entity then runs on the
 * region thread of the hologram's location, through the owner's {@code Scheduler}: the main thread on Paper, a tick
 * later at the earliest. A removal made on the thread that owns the location is done before the call returns. A
 * {@code true} from {@link #create(String, Location, List)}, {@link #update(String, List)} or {@link #move} means
 * the change was accepted, and {@link #exists} and {@link #ids()} answer from the records, before the entities
 * stand. Several changes to one hologram made before its region thread runs are shown as one. A move removes the
 * entities and spawns them again at the new location.
 *
 * <p><b>Chunks and worlds.</b> The entities stand only while the chunk of the location is loaded. A hologram created
 * or moved into a chunk that is not loaded is kept in the records and spawned when the chunk loads, and its
 * entities are removed when the chunk unloads, so {@link #exists} stays {@code true} throughout. A location with
 * no world, or whose world has unloaded, makes {@link #create(String, Location, List)} and {@link #move} answer
 * {@code false}. When a world unloads, the provider removes its holograms from the world and from the records, as
 * every {@link HologramProvider} does, and the owner places them again from its own {@code WorldLoadEvent}
 * listener. An entity something else removed, such as with {@code /kill}, is spawned again at the next
 * {@link #update(String, List)} of its hologram or the next load of its chunk.
 *
 * <p><b>Nothing is saved, and nothing is left.</b> Every entity is non-persistent, so the server never writes it to
 * a world file and none survives a restart or a crash: the owner places its holograms again at enable. Every
 * entity carries the owner's persistent data key {@code <owner namespace>:taffy_hologram} with the hologram's id.
 * An entity with that key that this provider does not hold is removed when the provider is built, in every loaded
 * chunk, and whenever the entities of a chunk load. So an owner holds one display provider at a time and closes it
 * before it builds another. A spawn that fails removes the entities it had spawned, forgets the hologram, logs a
 * warning at most once a minute and reports the failure under {@code hook.displays}.
 *
 * <p><b>Disable.</b> The provider removes every hologram when the owner's {@code PluginDisableEvent} fires, before
 * the owner's {@code onDisable}, and {@link #close()} does the same. On Paper both run on the main thread and the
 * entities are removed before the call returns. On Folia a disabling plugin can schedule nothing, so only the
 * entities whose location the disabling thread owns are removed. The others stay in the world until their chunk
 * unloads or the server stops, are never saved, and are removed by the next display provider the owner builds.
 */
public interface DisplayHolograms extends HologramProvider {

    /** The blocks between the bottom of one line and the bottom of the line above it. */
    double LINE_SPACING = 0.28;

    /** The blocks from the bottom of the top line up to the centre of the item. */
    double ITEM_RISE = 0.45;

    /** The scale of the item. */
    float ITEM_SCALE = 0.75f;

    /**
     * Places a hologram of text lines and an optional item.
     *
     * @param id the hologram's id
     * @param location where the hologram stands, at the bottom centre of its text; the provider keeps a copy
     * @param lines the hologram's lines, top first; may be empty
     * @param item the item shown above the lines, of which the provider keeps a copy, or {@code null} for none
     * @return {@code true} if the hologram was accepted to be spawned on the region thread of its location;
     *     {@code false} if the provider is not {@linkplain #available() available}, the id is in use, or the
     *     location has no world or its world has unloaded
     * @throws NullPointerException if {@code id}, {@code location}, {@code lines} or a line is {@code null}
     * @throws IllegalArgumentException if {@code id} is not a valid id
     */
    boolean create(String id, Location location, List<Component> lines, @Nullable ItemStack item);

    /**
     * Replaces every line of a hologram and its item. Text is changed in place when the number of lines stays the
     * same and an item neither appears nor goes; otherwise the entities are removed and spawned again.
     *
     * @param id the hologram's id
     * @param lines the new lines, top first; may be empty
     * @param item the item shown above the lines, of which the provider keeps a copy, or {@code null} for none
     * @return {@code true} if the change was accepted to be shown on the region thread of the hologram's location;
     *     {@code false} if the provider is not {@linkplain #available() available} or the id is not in use
     * @throws NullPointerException if {@code id}, {@code lines} or a line is {@code null}
     * @throws IllegalArgumentException if {@code id} is not a valid id
     */
    boolean update(String id, List<Component> lines, @Nullable ItemStack item);

    /**
     * Places a hologram of text lines with no item.
     *
     * @return as {@link #create(String, Location, List, ItemStack)} answers, and {@code false} when MiniMessage
     *     refuses a line
     */
    @Override
    boolean create(String id, Location location, List<String> lines);

    /**
     * Replaces every line of a hologram and keeps its item.
     *
     * @return as {@link #update(String, List, ItemStack)} answers, and {@code false} when MiniMessage refuses a line
     */
    @Override
    boolean update(String id, List<String> lines);

    /**
     * Removes every hologram and stops the provider, as {@link HologramProvider#close()} states. The entities are
     * removed as the class description states for disable.
     */
    @Override
    void close();
}
