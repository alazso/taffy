package so.alaz.taffy.gui;

import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The asks of every {@link Prompts} of one plugin: at most one ask per player waits for its answer or for its
 * {@link Answer.Later}, whichever prompts asked it, and a chat line answers at most one ask, however many prompt
 * listeners the line's events reach.
 */
final class Asks {

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Pending> judging = new ConcurrentHashMap<>();
    private final Map<UUID, Line> lines = new ConcurrentHashMap<>();

    /**
     * Gives the asks of the plugin's prompts: those of a prompt listener the server holds for the plugin, and new
     * asks when it holds none. The server forgets the listeners of a plugin it disables, so prompts built after the
     * plugin was enabled again start with new asks.
     *
     * @param plugin the plugin the prompts' menus belong to
     * @return the asks the prompts built next for the plugin use
     */
    static Asks of(Plugin plugin) {
        for (RegisteredListener registered : HandlerList.getRegisteredListeners(plugin)) {
            if (registered.getListener() instanceof PaperPromptListener listener) {
                return listener.asks();
            }
        }
        return new Asks();
    }

    /** The asks that wait for an answer, by player. */
    Map<UUID, Pending> pending() {
        return pending;
    }

    /** The asks that wait for their {@link Answer.Later}, by player. */
    Map<UUID, Pending> judging() {
        return judging;
    }

    /**
     * Tells whether the line {@code event} carries was taken as an answer already: by another listener from the same
     * event, or from the legacy event of the line when {@code event} is the first modern event after it. Any other
     * event is a new line, which forgets the line taken before.
     *
     * @param player the id of the player who typed the line
     * @param event the chat event that carries the line
     * @param legacy whether {@code event} is the legacy chat event
     * @return {@code true} if no ask may take the line
     */
    boolean taken(UUID player, Object event, boolean legacy) {
        boolean[] taken = new boolean[1];
        lines.computeIfPresent(player, (id, line) -> {
            if (line.event().refersTo(event) || line.modern() != null && line.modern().refersTo(event)) {
                taken[0] = true;
                return line;
            }
            if (!legacy && line.legacy() && line.modern() == null) {
                taken[0] = true;
                return new Line(line.event(), true, new WeakReference<>(event));
            }
            return null;
        });
        return taken[0];
    }

    /**
     * Remembers that an ask took the line {@code event} carries.
     *
     * @param player the id of the player who typed the line
     * @param event the chat event the line was taken from
     * @param legacy whether {@code event} is the legacy chat event
     */
    void took(UUID player, Object event, boolean legacy) {
        lines.put(player, new Line(new WeakReference<>(event), legacy, null));
    }

    /** Forgets the line taken from a player who quit. */
    void forgetLine(UUID player) {
        lines.remove(player);
    }

    /**
     * A line taken as an answer: the event it was taken from, whether that was the legacy event, and the modern event
     * that followed a legacy one.
     */
    private record Line(WeakReference<Object> event, boolean legacy, @Nullable WeakReference<Object> modern) {
    }
}
