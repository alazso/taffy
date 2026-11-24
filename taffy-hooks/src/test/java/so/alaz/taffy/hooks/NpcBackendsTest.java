package so.alaz.taffy.hooks;

import de.oliver.fancynpcs.api.NpcData;
import de.oliver.fancynpcs.api.actions.ActionTrigger;
import lol.pyr.znpcsplus.api.interaction.InteractionType;
import lol.pyr.znpcsplus.api.npc.NpcEntry;
import lol.pyr.znpcsplus.api.npc.NpcRegistry;
import lol.pyr.znpcsplus.util.NpcLocation;
import net.citizensnpcs.api.event.NPCLeftClickEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

final class NpcBackendsTest {

    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final List<String> clicked = new ArrayList<>();
    private final List<Throwable> unread = new ArrayList<>();
    private final NpcClicks clicks = new NpcClicks() {
        @Override
        public void clicked(Player who, String id, boolean asynchronous) {
            assertThat(who).isSameAs(player);
            clicked.add(id + (asynchronous ? " asynchronous" : " synchronous"));
        }

        @Override
        public void failed(Throwable failure) {
            unread.add(failure);
        }
    };

    private static List<Class<?>> handled(Listener listener) {
        List<Class<?>> events = new ArrayList<>();
        for (Method method : listener.getClass().getDeclaredMethods()) {
            EventHandler handler = method.getAnnotation(EventHandler.class);
            if (handler != null) {
                assertThat(handler.priority()).as(method.getName()).isEqualTo(EventPriority.MONITOR);
                assertThat(handler.ignoreCancelled()).as(method.getName()).isTrue();
                assertThat(method.getParameterTypes()).as(method.getName()).hasSize(1);
                assertThat(Event.class).as(method.getName()).isAssignableFrom(method.getParameterTypes()[0]);
                events.add(method.getParameterTypes()[0]);
            }
        }
        return events;
    }

    // Citizens

    private final NPCRegistry citizensRegistry = mock(NPCRegistry.class);

    private NPC citizensNpc(int id, NPCRegistry registry) {
        NPC npc = mock(NPC.class);
        when(npc.getId()).thenReturn(id);
        when(npc.getOwningRegistry()).thenReturn(registry);
        when(npc.isSpawned()).thenReturn(true);
        return npc;
    }

    @Test
    void theCitizensMethodsAndEventsThatAreReadLinkAgainstItsApi() {
        NpcBackend backend = CitizensNpcBackend.create(clicks);

        assertThat(handled(backend.listener()))
            .containsExactlyInAnyOrder(NPCRightClickEvent.class, NPCLeftClickEvent.class);
    }

    @Test
    void aCitizensIdIsTheNumberOfTheNpcAsIntegerToStringWritesIt() {
        assertThat(CitizensNpcBackend.number("0")).isZero();
        assertThat(CitizensNpcBackend.number("12")).isEqualTo(12);
        assertThat(CitizensNpcBackend.number("2147483647")).isEqualTo(Integer.MAX_VALUE);
        for (String refused : Arrays.asList("", "-1", "+1", "007", "00", "1.0", " 1", "1 ", "twelve", "2147483648",
            "99999999999", "١")) {
            assertThat(CitizensNpcBackend.number(refused)).as(refused).isEqualTo(-1);
        }
    }

    @Test
    void citizensGivesTheLocationOfASpawnedNpcOfTheDefaultRegistry() {
        NPC npc = citizensNpc(12, citizensRegistry);
        Location stands = new Location(world, 4.5, 70, -8.5);
        when(npc.getStoredLocation()).thenReturn(stands);
        when(citizensRegistry.getById(12)).thenReturn(npc);
        NpcBackend backend = CitizensNpcBackend.create(clicks, () -> citizensRegistry);

        assertThat(backend.locations().apply("12")).isSameAs(stands);
        assertThat(backend.locations().apply("13")).isNull();
    }

    @Test
    void citizensGivesNoLocationForADespawnedNpcOrAnIdThatIsNoNumber() {
        NPC npc = citizensNpc(12, citizensRegistry);
        when(npc.isSpawned()).thenReturn(false);
        when(citizensRegistry.getById(12)).thenReturn(npc);
        NpcBackend backend = CitizensNpcBackend.create(clicks, () -> citizensRegistry);

        assertThat(backend.locations().apply("12")).isNull();
        verify(npc, never()).getStoredLocation();
        assertThat(backend.locations().apply("shopkeeper")).isNull();
        assertThat(backend.locations().apply("012")).isNull();
        verify(citizensRegistry).getById(12);
        verifyNoMoreInteractions(citizensRegistry);
    }

    @Test
    void citizensPassesARightAndALeftClickWithTheNumberOfTheNpc() {
        NPC npc = citizensNpc(12, citizensRegistry);
        CitizensNpcBackend listener = (CitizensNpcBackend) CitizensNpcBackend
            .create(clicks, () -> citizensRegistry).listener();

        listener.onRightClick(new NPCRightClickEvent(npc, player));
        listener.onLeftClick(new NPCLeftClickEvent(npc, player));

        assertThat(clicked).containsExactly("12 synchronous", "12 synchronous");
        assertThat(unread).isEmpty();
    }

    @Test
    void citizensLeavesOutAClickOnAnNpcOfAnotherRegistry() {
        NPC temporary = citizensNpc(12, mock(NPCRegistry.class));
        CitizensNpcBackend listener = (CitizensNpcBackend) CitizensNpcBackend
            .create(clicks, () -> citizensRegistry).listener();

        listener.onRightClick(new NPCRightClickEvent(temporary, player));
        listener.onLeftClick(new NPCLeftClickEvent(temporary, player));

        assertThat(clicked).isEmpty();
        assertThat(unread).isEmpty();
    }

    @Test
    void aCitizensClickThatCannotBeReadIsHandedOverAsAFailure() {
        NPC npc = citizensNpc(12, citizensRegistry);
        IllegalStateException broken = new IllegalStateException("Citizens is reloading");
        when(npc.getOwningRegistry()).thenThrow(broken);
        NoSuchMethodError changed = new NoSuchMethodError("NPC.getId");
        NPC other = citizensNpc(13, citizensRegistry);
        when(other.getId()).thenThrow(changed);
        CitizensNpcBackend listener = (CitizensNpcBackend) CitizensNpcBackend
            .create(clicks, () -> citizensRegistry).listener();

        listener.onRightClick(new NPCRightClickEvent(npc, player));
        listener.onLeftClick(new NPCLeftClickEvent(other, player));

        assertThat(clicked).isEmpty();
        assertThat(unread).containsExactly(broken, changed);
    }

    // FancyNpcs

    private final Map<String, NpcData> fancyNpcs = new HashMap<>();
    private final Map<Event, NpcData> fancyClicked = new HashMap<>();

    private NpcBackend fancyBackend() {
        return FancyNpcsBackend.create(clicks, fancyNpcs::get, fancyClicked::get);
    }

    private NpcData fancyNpc(String name, boolean spawnEntity) {
        NpcData data = mock(NpcData.class);
        when(data.getName()).thenReturn(name);
        when(data.isSpawnEntity()).thenReturn(spawnEntity);
        fancyNpcs.put(name, data);
        return data;
    }

    // An Npc cannot be made without FancyNpcs running, so the event carries none and the data is looked up by event.
    private de.oliver.fancynpcs.api.events.NpcInteractEvent fancyClick(NpcData data, ActionTrigger trigger) {
        de.oliver.fancynpcs.api.events.NpcInteractEvent event =
            new de.oliver.fancynpcs.api.events.NpcInteractEvent(null, null, List.of(), player, trigger);
        fancyClicked.put(event, data);
        return event;
    }

    @Test
    void theFancyNpcsMethodsAndEventThatAreReadLinkAgainstItsApi() {
        NpcBackend backend = FancyNpcsBackend.create(clicks);

        assertThat(handled(backend.listener()))
            .containsExactly(de.oliver.fancynpcs.api.events.NpcInteractEvent.class);
    }

    @Test
    void fancyNpcsGivesTheLocationOfAnNpcByItsName() {
        Location stands = new Location(world, 1, 64, 1);
        when(fancyNpc("shopkeeper", true).getLocation()).thenReturn(stands);

        assertThat(fancyBackend().locations().apply("shopkeeper")).isSameAs(stands);
        assertThat(fancyBackend().locations().apply("banker")).isNull();
    }

    @Test
    void fancyNpcsGivesNoLocationForAnNpcWhoseEntityIsNotSpawned() {
        NpcData data = fancyNpc("shopkeeper", false);

        assertThat(fancyBackend().locations().apply("shopkeeper")).isNull();
        verify(data, never()).getLocation();
    }

    @Test
    void fancyNpcsPassesALeftAndARightClickWithTheNameAndNoOtherTrigger() {
        NpcData data = fancyNpc("Shopkeeper", true);
        FancyNpcsBackend listener = (FancyNpcsBackend) fancyBackend().listener();

        listener.onInteract(fancyClick(data, ActionTrigger.RIGHT_CLICK));
        listener.onInteract(fancyClick(data, ActionTrigger.LEFT_CLICK));
        listener.onInteract(fancyClick(data, ActionTrigger.CUSTOM));
        listener.onInteract(fancyClick(data, ActionTrigger.ANY_CLICK));

        assertThat(clicked).containsExactly("Shopkeeper synchronous", "Shopkeeper synchronous");
        assertThat(unread).isEmpty();
    }

    @Test
    void aFancyNpcsClickThatCannotBeReadIsHandedOverAsAFailure() {
        NpcData data = fancyNpc("shopkeeper", true);
        IllegalStateException broken = new IllegalStateException("no data");
        when(data.getName()).thenThrow(broken);
        FancyNpcsBackend listener = (FancyNpcsBackend) fancyBackend().listener();

        listener.onInteract(fancyClick(data, ActionTrigger.RIGHT_CLICK));

        assertThat(clicked).isEmpty();
        assertThat(unread).containsExactly(broken);
    }

    // ZNPCsPlus

    private final NpcRegistry znpcsRegistry = mock(NpcRegistry.class);

    private static NpcEntry znpcsEntry(String id, boolean enabled, World in) {
        lol.pyr.znpcsplus.api.npc.Npc npc = mock(lol.pyr.znpcsplus.api.npc.Npc.class);
        when(npc.isEnabled()).thenReturn(enabled);
        when(npc.getWorld()).thenReturn(in);
        when(npc.getLocation()).thenReturn(new NpcLocation(2.5, 80, 7.5, 90f, 10f));
        NpcEntry entry = mock(NpcEntry.class);
        when(entry.getId()).thenReturn(id);
        when(entry.getNpc()).thenReturn(npc);
        return entry;
    }

    @Test
    void theZnpcsPlusMethodsAndEventThatAreReadLinkAgainstItsApi() {
        NpcBackend backend = ZnpcsPlusBackend.create(clicks);

        assertThat(handled(backend.listener()))
            .containsExactly(lol.pyr.znpcsplus.api.event.NpcInteractEvent.class);
    }

    @Test
    void znpcsPlusGivesTheLocationOfAnEnabledNpcInItsWorld() {
        NpcEntry entry = znpcsEntry("shopkeeper", true, world);
        when(znpcsRegistry.getById("shopkeeper")).thenReturn(entry);
        NpcBackend backend = ZnpcsPlusBackend.create(clicks, () -> znpcsRegistry);

        Location found = backend.locations().apply("shopkeeper");

        assertThat(found).isEqualTo(new Location(world, 2.5, 80, 7.5, 90f, 10f));
        assertThat(backend.locations().apply("banker")).isNull();
    }

    @Test
    void znpcsPlusGivesNoLocationForADisabledNpcOrOneWhoseWorldIsNotLoaded() {
        NpcEntry disabled = znpcsEntry("off", false, world);
        NpcEntry elsewhere = znpcsEntry("far", true, null);
        when(znpcsRegistry.getById("off")).thenReturn(disabled);
        when(znpcsRegistry.getById("far")).thenReturn(elsewhere);
        NpcBackend backend = ZnpcsPlusBackend.create(clicks, () -> znpcsRegistry);

        assertThat(backend.locations().apply("off")).isNull();
        assertThat(backend.locations().apply("far")).isNull();
    }

    @Test
    void znpcsPlusPassesEveryClickWithTheIdAsAnAsynchronousOne() {
        NpcEntry entry = znpcsEntry("shopkeeper", true, world);
        ZnpcsPlusBackend listener = (ZnpcsPlusBackend) ZnpcsPlusBackend.create(clicks, () -> znpcsRegistry).listener();

        listener.onInteract(new lol.pyr.znpcsplus.api.event.NpcInteractEvent(player, entry,
            InteractionType.RIGHT_CLICK));
        listener.onInteract(new lol.pyr.znpcsplus.api.event.NpcInteractEvent(player, entry,
            InteractionType.LEFT_CLICK));

        assertThat(clicked).containsExactly("shopkeeper asynchronous", "shopkeeper asynchronous");
        assertThat(unread).isEmpty();
    }

    @Test
    void aZnpcsPlusClickThatCannotBeReadIsHandedOverAsAFailure() {
        NpcEntry entry = znpcsEntry("shopkeeper", true, world);
        IllegalStateException broken = new IllegalStateException("entry removed");
        when(entry.getId()).thenThrow(broken);
        ZnpcsPlusBackend listener = (ZnpcsPlusBackend) ZnpcsPlusBackend.create(clicks, () -> znpcsRegistry).listener();

        listener.onInteract(new lol.pyr.znpcsplus.api.event.NpcInteractEvent(player, entry,
            InteractionType.RIGHT_CLICK));

        assertThat(clicked).isEmpty();
        assertThat(unread).containsExactly(broken);
    }
}
