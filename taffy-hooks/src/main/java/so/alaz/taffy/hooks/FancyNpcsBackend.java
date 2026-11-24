package so.alaz.taffy.hooks;

import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcData;
import de.oliver.fancynpcs.api.NpcManager;
import de.oliver.fancynpcs.api.actions.ActionTrigger;
import de.oliver.fancynpcs.api.events.NpcInteractEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

// FancyNpcs fires NpcInteractEvent synchronously from Paper's PlayerUseUnknownEntityEvent, for the main hand only and
// after its own interaction cooldown let the click through. Another plugin's Npc.interact fires the same event with
// the trigger CUSTOM, which is not a click. The Npc class reads FancyNpcsPlugin.get() when it initializes, so every
// call on an Npc sits in the two functions create passes.
final class FancyNpcsBackend implements Listener {

    private final NpcClicks clicks;
    private final Function<String, @Nullable NpcData> named;
    private final Function<NpcInteractEvent, NpcData> clicked;

    FancyNpcsBackend(NpcClicks clicks, Function<String, @Nullable NpcData> named,
                     Function<NpcInteractEvent, NpcData> clicked) {
        this.clicks = clicks;
        this.named = named;
        this.clicked = clicked;
    }

    // registerEvents logs and skips a listener whose event class is missing instead of throwing, so the event is
    // linked first.
    static NpcBackend create(NpcClicks clicks) {
        ApiLink.staticMethod(FancyNpcsPlugin.class, "get", FancyNpcsPlugin.class);
        ApiLink.instanceMethod(FancyNpcsPlugin.class, "getNpcManager", NpcManager.class);
        ApiLink.instanceMethod(NpcManager.class, "getNpc", Npc.class, String.class);
        ApiLink.instanceMethod(Npc.class, "getData", NpcData.class);
        ApiLink.instanceMethod(NpcData.class, "getName", String.class);
        ApiLink.instanceMethod(NpcData.class, "isSpawnEntity", boolean.class);
        ApiLink.instanceMethod(NpcData.class, "getLocation", Location.class);
        ApiLink.staticMethod(NpcInteractEvent.class, "getHandlerList", HandlerList.class);
        ApiLink.instanceMethod(NpcInteractEvent.class, "getNpc", Npc.class);
        ApiLink.instanceMethod(NpcInteractEvent.class, "getPlayer", Player.class);
        ApiLink.instanceMethod(NpcInteractEvent.class, "getInteractionType", ActionTrigger.class);
        ApiLink.staticField(ActionTrigger.class, "LEFT_CLICK", ActionTrigger.class);
        ApiLink.staticField(ActionTrigger.class, "RIGHT_CLICK", ActionTrigger.class);
        return create(clicks,
            name -> {
                Npc npc = FancyNpcsPlugin.get().getNpcManager().getNpc(name);
                return npc == null ? null : npc.getData();
            },
            event -> event.getNpc().getData());
    }

    static NpcBackend create(NpcClicks clicks, Function<String, @Nullable NpcData> named,
                             Function<NpcInteractEvent, NpcData> clicked) {
        FancyNpcsBackend backend = new FancyNpcsBackend(clicks, named, clicked);
        return new NpcBackend(backend::location, backend);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(NpcInteractEvent event) {
        try {
            ActionTrigger trigger = event.getInteractionType();
            if (trigger != ActionTrigger.LEFT_CLICK && trigger != ActionTrigger.RIGHT_CLICK) {
                return;
            }
            clicks.clicked(event.getPlayer(), clicked.apply(event).getName(), event.isAsynchronous());
        } catch (Exception | LinkageError ex) {
            clicks.failed(ex);
        }
    }

    private @Nullable Location location(String id) {
        NpcData data = named.apply(id);
        return data == null || !data.isSpawnEntity() ? null : data.getLocation();
    }
}
