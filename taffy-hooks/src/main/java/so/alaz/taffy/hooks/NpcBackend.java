package so.alaz.taffy.hooks;

import org.bukkit.Location;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

// What the adapter of one NPC plugin gives. locations answers null for an id the NPC plugin does not know and for an
// NPC that is not spawned, and may give a location the NPC plugin still holds or one whose world is not loaded.
// listener holds the handlers of the NPC plugin's click events.
record NpcBackend(Function<String, @Nullable Location> locations, Listener listener) {
}
