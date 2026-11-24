package so.alaz.taffy.hooks;

import lol.pyr.znpcsplus.api.NpcApi;
import lol.pyr.znpcsplus.api.NpcApiProvider;
import lol.pyr.znpcsplus.api.event.NpcInteractEvent;
import lol.pyr.znpcsplus.api.npc.Npc;
import lol.pyr.znpcsplus.api.npc.NpcEntry;
import lol.pyr.znpcsplus.api.npc.NpcRegistry;
import lol.pyr.znpcsplus.util.NpcLocation;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

// ZNPCsPlus fires NpcInteractEvent as an asynchronous event on the network thread that read the player's packet. It
// fires for the attack packet and for both interact packets of either hand, and lets at most one through for a
// player in 100 milliseconds, which leaves out the second packet and the off hand of one right click.
final class ZnpcsPlusBackend implements Listener {

    private final NpcClicks clicks;
    private final Supplier<NpcRegistry> registry;

    ZnpcsPlusBackend(NpcClicks clicks, Supplier<NpcRegistry> registry) {
        this.clicks = clicks;
        this.registry = registry;
    }

    // registerEvents logs and skips a listener whose event class is missing instead of throwing, so the event is
    // linked first.
    static NpcBackend create(NpcClicks clicks) {
        ApiLink.staticMethod(NpcApiProvider.class, "get", NpcApi.class);
        ApiLink.instanceMethod(NpcApi.class, "getNpcRegistry", NpcRegistry.class);
        ApiLink.instanceMethod(NpcRegistry.class, "getById", NpcEntry.class, String.class);
        ApiLink.instanceMethod(NpcEntry.class, "getId", String.class);
        ApiLink.instanceMethod(NpcEntry.class, "getNpc", Npc.class);
        ApiLink.instanceMethod(Npc.class, "isEnabled", boolean.class);
        ApiLink.instanceMethod(Npc.class, "getWorld", World.class);
        ApiLink.instanceMethod(Npc.class, "getLocation", NpcLocation.class);
        ApiLink.instanceMethod(NpcLocation.class, "toBukkitLocation", Location.class, World.class);
        ApiLink.staticMethod(NpcInteractEvent.class, "getHandlerList", HandlerList.class);
        ApiLink.instanceMethod(NpcInteractEvent.class, "getPlayer", Player.class);
        ApiLink.instanceMethod(NpcInteractEvent.class, "getEntry", NpcEntry.class);
        return create(clicks, () -> NpcApiProvider.get().getNpcRegistry());
    }

    static NpcBackend create(NpcClicks clicks, Supplier<NpcRegistry> registry) {
        ZnpcsPlusBackend backend = new ZnpcsPlusBackend(clicks, registry);
        return new NpcBackend(backend::location, backend);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(NpcInteractEvent event) {
        try {
            clicks.clicked(event.getPlayer(), event.getEntry().getId(), event.isAsynchronous());
        } catch (Exception | LinkageError ex) {
            clicks.failed(ex);
        }
    }

    // Npc.getWorld gives null while the world the NPC stands in is not loaded.
    private @Nullable Location location(String id) {
        NpcEntry entry = registry.get().getById(id);
        if (entry == null) {
            return null;
        }
        Npc npc = entry.getNpc();
        if (!npc.isEnabled()) {
            return null;
        }
        World world = npc.getWorld();
        return world == null ? null : npc.getLocation().toBukkitLocation(world);
    }
}
