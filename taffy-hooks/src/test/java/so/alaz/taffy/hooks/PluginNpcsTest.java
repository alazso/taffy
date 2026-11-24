package so.alaz.taffy.hooks;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.test.CapturedLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

final class PluginNpcsTest {

    private static final UUID LOADED = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID UNLOADED = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final PluginManager pluginManager = mock(PluginManager.class);
    private final CapturedLog logged = new CapturedLog();
    private final Plugin owner = owner();
    private final Plugin bound = mock(Plugin.class);
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final EntityScheduler scheduler = mock(EntityScheduler.class);
    private final Listener listener = new Listener() { };
    private final Map<String, Location> stands = new HashMap<>();
    private final List<String> clicked = new ArrayList<>();
    private final List<String> reported = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();
    private final ErrorReports errors = new ErrorReports();
    private @Nullable Throwable failure;

    private final NpcBackend backend = new NpcBackend(id -> {
        Throwable thrown = failure;
        if (thrown instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (thrown instanceof Error error) {
            throw error;
        }
        return stands.get(id);
    }, listener);

    @BeforeEach
    void enabled() {
        when(bound.isEnabled()).thenReturn(true);
        when(owner.isEnabled()).thenReturn(true);
        when(world.getUID()).thenReturn(LOADED);
        when(player.getScheduler()).thenReturn(scheduler);
        when(player.getName()).thenReturn("Alex");
        errors.install((source, error) -> {
            reported.add(source);
            failures.add(error);
        });
    }

    private Plugin owner() {
        Plugin owner = mock(Plugin.class);
        Server server = mock(Server.class);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(owner.getServer()).thenReturn(server);
        when(owner.getName()).thenReturn("Slate");
        when(owner.getLogger()).thenReturn(logged.logger);
        return owner;
    }

    private PluginNpcs npcs() {
        return new PluginNpcs(NpcPlugin.ZNPCSPLUS, bound, owner, errors, clicks -> backend);
    }

    private PluginNpcs listening() {
        PluginNpcs npcs = npcs();
        npcs.onClick((who, id) -> clicked.add(who.getName() + " " + id));
        return npcs;
    }

    // Location.isWorldLoaded asks the server for the world.
    private MockedStatic<Bukkit> worlds() {
        MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getWorld(LOADED)).thenReturn(world);
        return bukkit;
    }

    private Runnable handedOver() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<ScheduledTask>> task = ArgumentCaptor.forClass(Consumer.class);
        verify(scheduler).run(same(owner), task.capture(), isNull());
        return () -> task.getValue().accept(mock(ScheduledTask.class));
    }

    @Test
    void theProviderNamesItsNpcPluginAndFollowsItsAvailability() {
        PluginNpcs npcs = npcs();

        assertThat(npcs.plugin()).isEqualTo(NpcPlugin.ZNPCSPLUS);
        assertThat(npcs.available()).isTrue();
        when(bound.isEnabled()).thenReturn(false);
        assertThat(npcs.available()).isFalse();
    }

    @Test
    void aLocationIsACopyOfWhereTheNpcStands() {
        Location held = new Location(world, 4.5, 70, -8.5, 90f, 0f);
        stands.put("shopkeeper", held);
        try (MockedStatic<Bukkit> _ = worlds()) {
            Location found = npcs().location("shopkeeper");

            assertThat(found).isEqualTo(held).isNotSameAs(held);
        }
        assertThat(reported).isEmpty();
    }

    @Test
    void anUnknownNpcAndOneWhoseWorldIsNotLoadedHaveNoLocation() {
        World gone = mock(World.class);
        when(gone.getUID()).thenReturn(UNLOADED);
        stands.put("far", new Location(gone, 0, 64, 0));
        stands.put("nowhere", new Location(null, 0, 64, 0));
        try (MockedStatic<Bukkit> _ = worlds()) {
            PluginNpcs npcs = npcs();

            assertThat(npcs.location("banker")).isNull();
            assertThat(npcs.location("far")).isNull();
            assertThat(npcs.location("nowhere")).isNull();
        }
        assertThat(reported).isEmpty();
        assertThat(logged.lines()).isEmpty();
    }

    @Test
    void aDisabledNpcPluginAndAClosedProviderGiveNoLocationWithoutALookup() {
        stands.put("shopkeeper", new Location(world, 0, 64, 0));
        failure = new AssertionError("looked up");
        PluginNpcs npcs = npcs();

        when(bound.isEnabled()).thenReturn(false);
        assertThat(npcs.location("shopkeeper")).isNull();
        when(bound.isEnabled()).thenReturn(true);
        npcs.close();
        assertThat(npcs.location("shopkeeper")).isNull();
        assertThat(npcs.available()).isFalse();
    }

    @Test
    void aFailingLookupGivesNoLocationAndIsLoggedOnceAMinuteAndReportedEachTime() {
        IllegalStateException broken = new IllegalStateException("ZNPCsPlus plugin isn't enabled yet!");
        failure = broken;
        PluginNpcs npcs = npcs();

        assertThat(npcs.location("shopkeeper")).isNull();
        assertThat(npcs.location("shopkeeper")).isNull();

        assertThat(reported).containsExactly("hook.znpcsplus", "hook.znpcsplus");
        assertThat(failures).containsExactly(broken, broken);
        assertThat(logged.lines()).containsExactly("WARNING ZNPCsPlus could not look up where an NPC stands"
            + " (java.lang.IllegalStateException: ZNPCsPlus plugin isn't enabled yet!).");
        assertThat(logged.records.getFirst().getThrown()).isSameAs(broken);
    }

    @Test
    void aLinkageErrorInALookupIsReportedAndAnotherErrorPropagates() {
        PluginNpcs npcs = npcs();

        failure = new NoSuchMethodError("NpcRegistry.getById");
        assertThat(npcs.location("shopkeeper")).isNull();
        assertThat(reported).containsExactly("hook.znpcsplus");

        failure = new OutOfMemoryError("heap");
        assertThatThrownBy(() -> npcs.location("shopkeeper")).isInstanceOf(OutOfMemoryError.class);
    }

    @Test
    void aNullIdOrListenerIsRefused() {
        PluginNpcs npcs = npcs();

        assertThatThrownBy(() -> npcs.location(null)).isInstanceOf(NullPointerException.class).hasMessage("id");
        assertThatThrownBy(() -> npcs.onClick(null)).isInstanceOf(NullPointerException.class).hasMessage("listener");
    }

    @Test
    void aSynchronousClickReachesTheListenerInsideTheEvent() {
        PluginNpcs npcs = listening();

        npcs.clicked(player, "12", false);

        assertThat(clicked).containsExactly("Alex 12");
        verifyNoInteractions(scheduler);
    }

    @Test
    void anAsynchronousClickIsHandedToThePlayersSchedulerForTheOwner() {
        PluginNpcs npcs = listening();

        npcs.clicked(player, "shopkeeper", true);

        assertThat(clicked).isEmpty();
        handedOver().run();
        assertThat(clicked).containsExactly("Alex shopkeeper");
        assertThat(reported).isEmpty();
    }

    @Test
    void anAsynchronousClickIsDroppedWhileTheOwnerIsDisabled() {
        PluginNpcs npcs = listening();
        when(owner.isEnabled()).thenReturn(false);

        npcs.clicked(player, "shopkeeper", true);

        verifyNoInteractions(scheduler);
        assertThat(clicked).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    void aClickThatCannotBeHandedOverIsLoggedAndReported() {
        PluginNpcs npcs = listening();
        IllegalPluginAccessException refused = new IllegalPluginAccessException("Plugin attempted to register task"
            + " while disabled");
        when(scheduler.run(any(), any(), any())).thenThrow(refused);

        npcs.clicked(player, "shopkeeper", true);

        assertThat(clicked).isEmpty();
        assertThat(reported).containsExactly("hook.znpcsplus");
        assertThat(failures).containsExactly(refused);
        assertThat(logged.lines()).containsExactly("WARNING A click on a ZNPCsPlus NPC could not be handed to the"
            + " player's thread (org.bukkit.plugin.IllegalPluginAccessException: Plugin attempted to register task"
            + " while disabled).");
    }

    @Test
    void aClickWithNoListenerSetIsDropped() {
        PluginNpcs npcs = npcs();

        npcs.clicked(player, "12", false);
        npcs.clicked(player, "12", true);

        verifyNoInteractions(scheduler);
        assertThat(reported).isEmpty();
    }

    @Test
    void aNewListenerReplacesTheOneSetBefore() {
        PluginNpcs npcs = listening();
        List<String> second = new ArrayList<>();

        npcs.onClick((who, id) -> second.add(id));
        npcs.clicked(player, "12", false);

        assertThat(clicked).isEmpty();
        assertThat(second).containsExactly("12");
    }

    @Test
    void aListenerThatThrowsIsLoggedAndReportedAndGoesOnReceivingClicks() {
        PluginNpcs npcs = npcs();
        IllegalStateException broken = new IllegalStateException("crate missing");
        npcs.onClick((who, id) -> {
            clicked.add(id);
            throw broken;
        });

        npcs.clicked(player, "12", false);
        npcs.clicked(player, "13", false);
        npcs.clicked(player, "14", true);
        handedOver().run();

        assertThat(clicked).containsExactly("12", "13", "14");
        assertThat(reported).containsExactly("hook.znpcsplus", "hook.znpcsplus", "hook.znpcsplus");
        assertThat(failures).containsExactly(broken, broken, broken);
        assertThat(logged.lines()).containsExactly("WARNING The listener of clicks on ZNPCsPlus NPCs failed"
            + " (java.lang.IllegalStateException: crate missing).");
        assertThat(logged.records.getFirst().getThrown()).isSameAs(broken);
    }

    @Test
    void anErrorOfTheListenerThatIsNoLinkageErrorPropagates() {
        PluginNpcs npcs = npcs();
        npcs.onClick((who, id) -> {
            throw new OutOfMemoryError("heap");
        });

        assertThatThrownBy(() -> npcs.clicked(player, "12", false)).isInstanceOf(OutOfMemoryError.class);
        assertThat(reported).isEmpty();
    }

    @Test
    void aClickThatCouldNotBeReadIsLoggedOnceAMinuteAndReportedEachTime() {
        PluginNpcs npcs = listening();
        NoSuchMethodError changed = new NoSuchMethodError("NpcEntry.getId");

        npcs.failed(changed);
        npcs.failed(changed);

        assertThat(reported).containsExactly("hook.znpcsplus", "hook.znpcsplus");
        assertThat(logged.lines()).containsExactly("WARNING ZNPCsPlus fired a click on an NPC that could not be read"
            + " (java.lang.NoSuchMethodError: NpcEntry.getId).");
        assertThat(clicked).isEmpty();
    }

    @Test
    void noClickReachesTheListenerAfterCloseAlsoOneAlreadyHandedOver() {
        PluginNpcs npcs = listening();
        npcs.clicked(player, "early", true);
        Runnable onItsWay = handedOver();

        npcs.close();
        npcs.close();
        onItsWay.run();
        npcs.clicked(player, "late", false);
        npcs.onClick((who, id) -> clicked.add(id));
        npcs.clicked(player, "later", false);

        assertThat(clicked).isEmpty();
        assertThat(reported).isEmpty();
    }

    private void registering() {
        doAnswer(call -> {
            Listener registered = call.getArgument(0);
            Plugin plugin = call.getArgument(1);
            PluginEnableEvent.getHandlerList().register(new RegisteredListener(registered, (each, event) -> { },
                EventPriority.MONITOR, plugin, false));
            return null;
        }).when(pluginManager).registerEvents(any(Listener.class), any(Plugin.class));
    }

    @Test
    void closeUnregistersTheListenerSoDetectingAgainLeavesOne() {
        registering();
        Listener again = new Listener() { };
        try {
            NpcProvider first = PluginNpcs.detect(owner, errors, true, NpcPlugin.CITIZENS, new FakePresence(),
                clicks -> backend).provider();
            assertThat(first).isNotNull();
            assertThat(HandlerList.getRegisteredListeners(owner)).extracting(RegisteredListener::getListener)
                .containsExactly(listener);

            first.close();
            NpcProvider second = PluginNpcs.detect(owner, errors, true, NpcPlugin.CITIZENS, new FakePresence(),
                clicks -> new NpcBackend(stands::get, again)).provider();

            assertThat(second).isNotNull();
            assertThat(HandlerList.getRegisteredListeners(owner)).extracting(RegisteredListener::getListener)
                .containsExactly(again);
        } finally {
            HandlerList.unregisterAll(owner);
        }
    }

    @Test
    void detectionBuildsAProviderOnAnEnabledNpcPluginAndRegistersItsListenerOnce() {
        FakePresence presence = new FakePresence();
        List<NpcClicks> built = new ArrayList<>();

        Detected<NpcProvider> detected = PluginNpcs.detect(owner, errors, true, NpcPlugin.FANCYNPCS, presence,
            clicks -> {
                built.add(clicks);
                return backend;
            });

        assertThat(detected.status().state()).isEqualTo(HookState.ON);
        assertThat(detected.status().plugin()).isEqualTo("FancyNpcs");
        assertThat(detected.status().version()).isEqualTo("1.0");
        assertThat(presence.probes).containsExactly("de.oliver.fancynpcs.api.FancyNpcsPlugin");
        NpcProvider provider = detected.provider();
        assertThat(provider).isNotNull();
        assertThat(provider.plugin()).isEqualTo(NpcPlugin.FANCYNPCS);
        assertThat(built).containsExactly((NpcClicks) provider);
        verify(pluginManager).registerEvents(same(listener), same(owner));
        provider.onClick((who, id) -> clicked.add(id));
        provider.onClick((who, id) -> clicked.add(id));
        verify(pluginManager).registerEvents(any(Listener.class), any(Plugin.class));
    }

    @Test
    void theStatusFollowsTheNpcPluginAfterDetection() {
        FakePresence presence = new FakePresence();
        Detected<NpcProvider> detected = PluginNpcs.detect(owner, errors, true, NpcPlugin.CITIZENS, presence,
            clicks -> backend);

        when(presence.plugins.get("Citizens").isEnabled()).thenReturn(false);

        assertThat(detected.status().state()).isEqualTo(HookState.NOT_ENABLED);
        assertThat(detected.provider()).isNotNull();
        assertThat(detected.provider().available()).isFalse();
    }

    @Test
    void detectionSwitchedOffOrWithoutThePluginBuildsNothing() {
        FakePresence presence = new FakePresence();
        presence.missing.add("Citizens");
        presence.disabled.add("FancyNpcs");
        presence.absentClasses.add(NpcPlugin.ZNPCSPLUS.probe());
        List<NpcClicks> built = new ArrayList<>();

        Detected<NpcProvider> off = PluginNpcs.detect(owner, errors, false, NpcPlugin.CITIZENS, presence, clicks -> {
            built.add(clicks);
            return backend;
        });
        Detected<NpcProvider> missing = PluginNpcs.detect(owner, errors, true, NpcPlugin.CITIZENS, presence,
            clicks -> {
                built.add(clicks);
                return backend;
            });
        Detected<NpcProvider> disabled = PluginNpcs.detect(owner, errors, true, NpcPlugin.FANCYNPCS, presence,
            clicks -> {
                built.add(clicks);
                return backend;
            });
        Detected<NpcProvider> lacking = PluginNpcs.detect(owner, errors, true, NpcPlugin.ZNPCSPLUS, presence,
            clicks -> {
                built.add(clicks);
                return backend;
            });

        assertThat(off.status().state()).isEqualTo(HookState.SWITCHED_OFF);
        assertThat(missing.status().state()).isEqualTo(HookState.NOT_INSTALLED);
        assertThat(disabled.status().state()).isEqualTo(HookState.NOT_ENABLED);
        assertThat(lacking.status().state()).isEqualTo(HookState.API_MISSING);
        assertThat(List.of(off, missing, disabled, lacking)).allMatch(each -> each.provider() == null);
        assertThat(built).isEmpty();
        verify(pluginManager, never()).registerEvents(any(Listener.class), any(Plugin.class));
        assertThat(logged.lines())
            .containsExactly("WARNING ZNPCsPlus 1.0 lacks the API that is read, so it stays off.");
        assertThat(reported).isEmpty();
    }

    @Test
    void anNpcPluginThePluginCannotSeeIsNotVisible() {
        FakePresence presence = new FakePresence();
        presence.invisibleClasses.add(NpcPlugin.CITIZENS.probe());

        Detected<NpcProvider> detected = PluginNpcs.detect(owner, errors, true, NpcPlugin.CITIZENS, presence,
            clicks -> backend);

        assertThat(detected.status().state()).isEqualTo(HookState.NOT_VISIBLE);
        assertThat(detected.status().reason()).isEqualTo("Citizens is installed, but this plugin cannot see it, because"
            + " its paper-plugin.yml does not list Citizens under dependencies.server.");
        assertThat(detected.provider()).isNull();
        assertThat(reported).isEmpty();
    }

    @Test
    void aBackendThatCannotBeBuiltFailsDetectionAndIsReportedAndRegistersNothing() {
        NoSuchMethodError missing = new NoSuchMethodError("lol.pyr.znpcsplus.api.npc.NpcEntry.getId()");

        Detected<NpcProvider> detected = PluginNpcs.detect(owner, errors, true, NpcPlugin.ZNPCSPLUS,
            new FakePresence(), clicks -> {
                throw missing;
            });

        assertThat(detected.status().state()).isEqualTo(HookState.FAILED);
        assertThat(detected.provider()).isNull();
        assertThat(reported).containsExactly("hook.znpcsplus");
        assertThat(failures).containsExactly(missing);
        verify(pluginManager, never()).registerEvents(any(Listener.class), any(Plugin.class));
    }

    @Test
    void aListenerThatCannotBeRegisteredFailsDetection() {
        IllegalPluginAccessException refused = new IllegalPluginAccessException("Plugin attempted to register"
            + " listener while not enabled");
        doAnswer(call -> {
            throw refused;
        }).when(pluginManager).registerEvents(any(Listener.class), any(Plugin.class));

        Detected<NpcProvider> detected = PluginNpcs.detect(owner, errors, true, NpcPlugin.CITIZENS,
            new FakePresence(), clicks -> backend);

        assertThat(detected.status().state()).isEqualTo(HookState.FAILED);
        assertThat(reported).containsExactly("hook.citizens");
        assertThat(failures).containsExactly(refused);
    }

    @Test
    void detectRefusesNullArguments() {
        FakePresence presence = new FakePresence();

        assertThatThrownBy(() -> NpcProvider.detect(null, errors, true, NpcPlugin.CITIZENS))
            .isInstanceOf(NullPointerException.class).hasMessage("owner");
        assertThatThrownBy(() -> NpcProvider.detect(owner, errors, true, null))
            .isInstanceOf(NullPointerException.class).hasMessage("plugin");
        assertThatThrownBy(() -> PluginNpcs.detect(owner, null, true, NpcPlugin.CITIZENS, presence,
            clicks -> backend)).isInstanceOf(NullPointerException.class).hasMessage("errors");
    }
}
