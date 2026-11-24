package so.alaz.taffy.hooks;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCClickEvent;
import net.citizensnpcs.api.event.NPCLeftClickEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

// NPCRightClickEvent and NPCLeftClickEvent each hold a HandlerList of their own, so a handler of NPCClickEvent hears
// neither. Citizens fires both synchronously, the right click from the main hand only. An NPC id is a number of one
// registry, and Citizens fires its click events for the NPCs of every registry, so a click on an NPC outside the
// default registry is left out.
final class CitizensNpcBackend implements Listener {

    private final NpcClicks clicks;
    private final Supplier<NPCRegistry> registry;

    CitizensNpcBackend(NpcClicks clicks, Supplier<NPCRegistry> registry) {
        this.clicks = clicks;
        this.registry = registry;
    }

    // registerEvents logs and skips a listener whose event class is missing instead of throwing, so the events are
    // linked first.
    static NpcBackend create(NpcClicks clicks) {
        ApiLink.staticMethod(CitizensAPI.class, "getNPCRegistry", NPCRegistry.class);
        ApiLink.instanceMethod(NPCRegistry.class, "getById", NPC.class, int.class);
        ApiLink.instanceMethod(NPC.class, "getId", int.class);
        ApiLink.instanceMethod(NPC.class, "getOwningRegistry", NPCRegistry.class);
        ApiLink.instanceMethod(NPC.class, "isSpawned", boolean.class);
        ApiLink.instanceMethod(NPC.class, "getStoredLocation", Location.class);
        ApiLink.instanceMethod(NPCClickEvent.class, "getNPC", NPC.class);
        ApiLink.instanceMethod(NPCClickEvent.class, "getClicker", Player.class);
        ApiLink.staticMethod(NPCRightClickEvent.class, "getHandlerList", HandlerList.class);
        ApiLink.staticMethod(NPCLeftClickEvent.class, "getHandlerList", HandlerList.class);
        return create(clicks, () -> CitizensAPI.getNPCRegistry());
    }

    static NpcBackend create(NpcClicks clicks, Supplier<NPCRegistry> registry) {
        CitizensNpcBackend backend = new CitizensNpcBackend(clicks, registry);
        return new NpcBackend(backend::location, backend);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRightClick(NPCRightClickEvent event) {
        pass(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeftClick(NPCLeftClickEvent event) {
        pass(event);
    }

    private void pass(NPCClickEvent event) {
        try {
            NPC npc = event.getNPC();
            if (npc.getOwningRegistry() != registry.get()) {
                return;
            }
            clicks.clicked(event.getClicker(), Integer.toString(npc.getId()), event.isAsynchronous());
        } catch (Exception | LinkageError ex) {
            clicks.failed(ex);
        }
    }

    // NPC.getStoredLocation gives the entity's location while the NPC is spawned.
    private @Nullable Location location(String id) {
        int number = number(id);
        if (number < 0) {
            return null;
        }
        NPC npc = registry.get().getById(number);
        return npc == null || !npc.isSpawned() ? null : npc.getStoredLocation();
    }

    // The number an id writes, or -1 for an id that is not a number from 0 up as Integer.toString writes it.
    static int number(String id) {
        if (id.isEmpty() || id.length() > 10 || id.length() > 1 && id.charAt(0) == '0') {
            return -1;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
        }
        long value = Long.parseLong(id);
        return value > Integer.MAX_VALUE ? -1 : (int) value;
    }
}
