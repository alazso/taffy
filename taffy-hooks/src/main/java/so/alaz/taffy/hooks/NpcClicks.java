package so.alaz.taffy.hooks;

import org.bukkit.entity.Player;

// Where the adapter of one NPC plugin hands the clicks it reads, on the thread the NPC plugin fired its event on.
interface NpcClicks {

    // asynchronous is the event's own flag: true when the event was fired off the thread that owns the player.
    void clicked(Player player, String id, boolean asynchronous);

    // A click event that could not be read.
    void failed(Throwable failure);
}
