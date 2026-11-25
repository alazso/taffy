package so.alaz.taffy.hooks;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Server;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.test.CapturedLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class PluginHologramsTest {

    private final PluginManager pluginManager = mock(PluginManager.class);
    private final CapturedLog logged = new CapturedLog();
    private final World world = mock(World.class);
    private final Set<World> loaded = new HashSet<>(Set.of(world));
    private final Server server = server();
    private final Plugin owner = owner();
    private final Plugin bound = mock(Plugin.class);
    private final Location here = new Location(world, 1.5, 64, -2);
    private final Map<String, List<String>> lines = new HashMap<>();
    private final Map<String, Location> places = new HashMap<>();
    private final List<String> calls = new ArrayList<>();
    private final List<String> reported = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();
    private final ErrorReports errors = new ErrorReports();
    private @Nullable Throwable failure;
    private boolean refuseRemoval;
    private final Set<String> foreign = new HashSet<>();

    private final HologramBackend backend = HologramBackend.of(
        name -> {
            fail();
            return lines.containsKey(name);
        },
        name -> {
            fail();
            return lines.containsKey(name) && !foreign.contains(name);
        },
        (name, location, text) -> {
            fail();
            calls.add("create " + name);
            lines.put(name, text);
            places.put(name, location);
        },
        (name, text) -> {
            fail();
            calls.add("update " + name);
            lines.put(name, text);
        },
        (name, location) -> {
            fail();
            calls.add("move " + name);
            places.put(name, location);
        },
        name -> {
            fail();
            calls.add("remove " + name);
            if (refuseRemoval) {
                return false;
            }
            lines.remove(name);
            places.remove(name);
            return true;
        });

    @BeforeEach
    void enabled() {
        when(bound.isEnabled()).thenReturn(true);
        when(world.getUID()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        errors.install((source, error) -> {
            reported.add(source);
            failures.add(error);
        });
    }

    private void fail() {
        Throwable thrown = failure;
        if (thrown instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (thrown instanceof Error error) {
            throw error;
        }
    }

    private PluginHolograms holograms() {
        return new PluginHolograms(HologramPlugin.DECENTHOLOGRAMS, bound, owner, backend, errors);
    }

    private static PluginDisableEvent disabling(Plugin plugin) {
        PluginDisableEvent event = mock(PluginDisableEvent.class);
        when(event.getPlugin()).thenReturn(plugin);
        return event;
    }

    @Test
    void aHologramIsPlacedUnderTheOwnersPrefixAtACopyOfTheLocation() {
        PluginHolograms holograms = holograms();

        assertThat(holograms.create("crate-1", here, List.of("<gold>Daily crate", "%slate_keys%"))).isTrue();
        here.setX(99);

        assertThat(lines).containsOnlyKeys("slate_crate-1");
        assertThat(lines.get("slate_crate-1")).containsExactly("<gold>Daily crate", "%slate_keys%");
        assertThat(places.get("slate_crate-1").getX()).isEqualTo(1.5);
        assertThat(places.get("slate_crate-1")).isNotSameAs(here);
        assertThat(holograms.exists("crate-1")).isTrue();
        assertThat(holograms.ids()).containsExactly("crate-1");
    }

    @Test
    void theLinesAreCopiedAndANullLineIsRefused() {
        PluginHolograms holograms = holograms();
        List<String> text = new ArrayList<>(List.of("one"));

        holograms.create("a", here, text);
        text.add("two");

        assertThat(lines.get("slate_a")).containsExactly("one");
        assertThatThrownBy(() -> holograms.update("a", Arrays.asList("one", null)))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> holograms.create("b", null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> holograms.create("b", here, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aHologramThatStandsIsNotPlacedAgainWhoeverPlacedIt() {
        PluginHolograms holograms = holograms();
        lines.put("slate_owned", List.of("placed by the server owner"));
        foreign.add("slate_owned");

        assertThat(holograms.create("a", here, List.of("first"))).isTrue();
        assertThat(holograms.create("a", here, List.of("second"))).isFalse();
        assertThat(holograms.create("owned", here, List.of("mine"))).isFalse();

        assertThat(lines.get("slate_a")).containsExactly("first");
        assertThat(lines.get("slate_owned")).containsExactly("placed by the server owner");
        assertThat(holograms.ids()).containsExactly("a");
    }

    @Test
    void aHologramTheProviderDidNotPlaceIsNeverReached() {
        PluginHolograms holograms = holograms();
        lines.put("slate_owned", List.of("placed by the server owner"));

        assertThat(holograms.exists("owned")).isFalse();
        assertThat(holograms.update("owned", List.of("mine"))).isFalse();
        assertThat(holograms.move("owned", here)).isFalse();
        assertThat(holograms.remove("owned")).isFalse();
        assertThat(holograms.removeAll()).isZero();

        assertThat(lines.get("slate_owned")).containsExactly("placed by the server owner");
        assertThat(calls).isEmpty();
    }

    @Test
    void aPlacedHologramReplacedUnderItsNameIsNoLongerReached() {
        PluginHolograms holograms = holograms();
        holograms.create("a", here, List.of("mine"));
        foreign.add("slate_a");
        calls.clear();

        assertThat(holograms.exists("a")).isFalse();
        assertThat(holograms.update("a", List.of("again"))).isFalse();
        assertThat(holograms.remove("a")).isFalse();
        assertThat(holograms.ids()).isEmpty();
        assertThat(calls).isEmpty();
    }

    @Test
    void anIdOutsideTheRuleIsRefusedBeforeAnythingIsCalled() {
        PluginHolograms holograms = holograms();

        for (String bad : List.of("", "Crate", "a b", "a.b", "a:b", "x".repeat(49))) {
            assertThatThrownBy(() -> holograms.create(bad, here, List.of()))
                .as(bad)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid hologram id '" + bad + "': expected 1 to 48 of a-z, 0-9, _ and -");
            assertThatThrownBy(() -> holograms.remove(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> holograms.exists(null)).isInstanceOf(NullPointerException.class);
        assertThat(holograms.create("x".repeat(48), here, List.of())).isTrue();
        assertThat(calls).containsExactly("create slate_" + "x".repeat(48));
    }

    @Test
    void thePrefixIsTheOwnersNameInLowerCaseWithOtherCharactersAsDashesThenAnUnderscore() {
        assertThat(PluginHolograms.prefix("Slate")).isEqualTo("slate_");
        assertThat(PluginHolograms.prefix("ITEMS")).isEqualTo("items_");
        assertThat(PluginHolograms.prefix("My Plugin.2")).isEqualTo("my-plugin-2_");
        assertThat(PluginHolograms.prefix("pro-vouchers_x")).isEqualTo("pro-vouchers-x_");
        assertThat(PluginHolograms.prefix("a_b") + "c").isNotEqualTo(PluginHolograms.prefix("a") + "b_c");
    }

    @Test
    void updateAndMoveReachOnlyAHologramThatStands() {
        PluginHolograms holograms = holograms();

        assertThat(holograms.update("a", List.of("new"))).isFalse();
        assertThat(holograms.move("a", here)).isFalse();
        assertThat(calls).isEmpty();

        holograms.create("a", here, List.of("old"));
        Location there = new Location(world, 7, 70, 7);
        assertThat(holograms.update("a", List.of("new", "lines"))).isTrue();
        assertThat(holograms.move("a", there)).isTrue();

        assertThat(lines.get("slate_a")).containsExactly("new", "lines");
        assertThat(places.get("slate_a")).isEqualTo(there).isNotSameAs(there);
        assertThat(calls).containsExactly("create slate_a", "update slate_a", "move slate_a");
    }

    @Test
    void aLocationWithoutAWorldOrWithAnUnloadedWorldPlacesAndMovesNothing() {
        PluginHolograms holograms = holograms();
        Location unloaded = mock(Location.class);
        when(unloaded.getWorld()).thenThrow(new IllegalArgumentException("World unloaded"));
        Location nowhere = new Location(null, 0, 0, 0);

        assertThat(holograms.create("a", nowhere, List.of())).isFalse();
        assertThat(holograms.create("a", unloaded, List.of())).isFalse();
        holograms.create("b", here, List.of());
        assertThat(holograms.move("b", nowhere)).isFalse();
        assertThat(holograms.move("b", unloaded)).isFalse();

        assertThat(calls).containsExactly("create slate_b");
    }

    @Test
    void aLocationKeepingAnUnloadedWorldPlacesNothing() {
        PluginHolograms holograms = holograms();
        loaded.remove(world);

        assertThat(holograms.create("a", here, List.of("stale world"))).isFalse();

        assertThat(calls).isEmpty();
        assertThat(holograms.ids()).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    void aLocationKeepingAnUnloadedWorldMovesNothing() {
        PluginHolograms holograms = holograms();
        World other = mock(World.class);
        when(other.getUID()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        holograms.create("a", here, List.of());

        assertThat(holograms.move("a", new Location(other, 0, 70, 0))).isFalse();

        assertThat(calls).containsExactly("create slate_a");
        assertThat(places.get("slate_a")).isEqualTo(here);
        assertThat(reported).isEmpty();
    }

    @Test
    void aWorldLoadedAgainUnderTheSameIdIsNotReachedThroughALocationOfTheOldWorld() {
        PluginHolograms holograms = holograms();
        World again = mock(World.class);
        when(again.getUID()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        loaded.remove(world);
        loaded.add(again);

        assertThat(holograms.create("a", here, List.of())).isFalse();
        assertThat(holograms.create("a", new Location(again, 1.5, 64, -2), List.of())).isTrue();

        assertThat(calls).containsExactly("create slate_a");
    }

    @Test
    void aHologramIsRemovedOnceAndThenNoLongerStands() {
        PluginHolograms holograms = holograms();
        holograms.create("a", here, List.of());

        assertThat(holograms.remove("a")).isTrue();
        assertThat(holograms.exists("a")).isFalse();
        assertThat(holograms.remove("a")).isFalse();
        assertThat(holograms.ids()).isEmpty();
    }

    @Test
    void aRefusedRemovalKeepsTheHologramListed() {
        PluginHolograms holograms = holograms();
        holograms.create("a", here, List.of());
        refuseRemoval = true;

        assertThat(holograms.remove("a")).isFalse();
        assertThat(holograms.removeAll()).isZero();
        assertThat(holograms.ids()).containsExactly("a");

        refuseRemoval = false;
        assertThat(holograms.removeAll()).isEqualTo(1);
        assertThat(holograms.ids()).isEmpty();
    }

    @Test
    void aFailedRemovalKeepsTheHologramListed() {
        PluginHolograms holograms = holograms();
        holograms.create("a", here, List.of());
        failure = new IllegalStateException("busy");

        assertThat(holograms.remove("a")).isFalse();

        failure = null;
        assertThat(holograms.ids()).containsExactly("a");
    }

    @Test
    void theHologramsOfAnUnloadingWorldAreRemovedAndOthersStay() {
        PluginHolograms holograms = holograms();
        World other = mock(World.class);
        when(other.getUID()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        loaded.add(other);
        holograms.create("here", here, List.of());
        holograms.create("there", new Location(other, 0, 70, 0), List.of());
        holograms.create("moved", new Location(other, 0, 70, 0), List.of());
        holograms.move("moved", here);

        holograms.onWorldUnload(new WorldUnloadEvent(world));

        assertThat(holograms.ids()).containsExactly("there");
        assertThat(lines).containsOnlyKeys("slate_there");
    }

    @Test
    void theOwnersDisableRemovesTheHologramsItPlacedBeforeOnDisable() {
        PluginHolograms holograms = holograms();
        holograms.create("a", here, List.of());
        holograms.create("b", here, List.of());
        lines.put("slate_c", List.of());
        lines.put("other_a", List.of());

        holograms.onPluginDisable(disabling(mock(Plugin.class)));
        assertThat(holograms.ids()).containsExactly("a", "b");

        holograms.onPluginDisable(disabling(owner));

        assertThat(lines).containsOnlyKeys("slate_c", "other_a");
        assertThat(holograms.ids()).isEmpty();
        assertThat(holograms.removeAll()).isZero();
    }

    @Test
    void theHologramPluginsDisableForgetsTheHologramsPlacedThroughIt() {
        PluginHolograms holograms = holograms();
        holograms.create("a", here, List.of());
        calls.clear();

        holograms.onPluginDisable(disabling(bound));

        assertThat(holograms.exists("a")).isFalse();
        assertThat(holograms.update("a", List.of("again"))).isFalse();
        assertThat(holograms.removeAll()).isZero();
        assertThat(holograms.ids()).isEmpty();
        assertThat(calls).isEmpty();
        assertThat(lines).containsOnlyKeys("slate_a");
    }

    @Test
    void closeRemovesTheHologramsPlacedAndThenEveryCallAnswersNothing() {
        PluginHolograms holograms = holograms();
        holograms.create("a", here, List.of());
        holograms.create("b", here, List.of());
        lines.put("slate_c", List.of());

        holograms.close();

        assertThat(lines).containsOnlyKeys("slate_c");
        calls.clear();
        assertThat(holograms.available()).isFalse();
        assertThat(holograms.create("d", here, List.of())).isFalse();
        assertThat(holograms.update("a", List.of())).isFalse();
        assertThat(holograms.move("a", here)).isFalse();
        assertThat(holograms.exists("a")).isFalse();
        assertThat(holograms.ids()).isEmpty();
        assertThat(holograms.remove("a")).isFalse();
        assertThat(holograms.removeAll()).isZero();
        holograms.close();
        holograms.onPluginDisable(disabling(owner));
        assertThat(calls).isEmpty();
    }

    @Test
    void closeUnregistersTheListenerSoDetectingAgainLeavesOne() {
        doAnswer(call -> {
            Listener listener = call.getArgument(0);
            Plugin plugin = call.getArgument(1);
            PluginDisableEvent.getHandlerList().register(new RegisteredListener(listener, (each, event) -> { },
                EventPriority.MONITOR, plugin, false));
            return null;
        }).when(pluginManager).registerEvents(any(Listener.class), any(Plugin.class));
        try {
            HologramProvider first = PluginHolograms.detect(owner, errors, true, HologramPlugin.FANCYHOLOGRAMS,
                new FakePresence(), plugin -> backend).provider();
            assertThat(first).isNotNull();
            first.create("a", here, List.of());

            first.close();
            HologramProvider second = PluginHolograms.detect(owner, errors, true, HologramPlugin.FANCYHOLOGRAMS,
                new FakePresence(), plugin -> backend).provider();

            assertThat(HandlerList.getRegisteredListeners(owner)).extracting(RegisteredListener::getListener)
                .containsExactly((Listener) second);
            assertThat(lines).isEmpty();
        } finally {
            HandlerList.unregisterAll(owner);
        }
    }

    @Test
    void theIdsAreThosePlacedThatStillStandAndCannotBeChanged() {
        PluginHolograms holograms = holograms();
        holograms.create("b", here, List.of());
        holograms.create("a", here, List.of());
        lines.put("slate_c", List.of());
        lines.remove("slate_b");

        assertThat(holograms.ids()).containsExactly("a");
        assertThatThrownBy(() -> holograms.ids().add("z")).isInstanceOf(UnsupportedOperationException.class);
        lines.put("slate_b", List.of());
        assertThat(holograms.ids()).containsExactly("a");
    }

    @Test
    void removeAllRemovesWhatTheProviderPlacedAndLeavesTheRest() {
        PluginHolograms holograms = holograms();
        holograms.create("a", here, List.of());
        holograms.create("b", here, List.of());
        lines.put("slate_c", List.of());
        lines.put("other_a", List.of());

        assertThat(holograms.removeAll()).isEqualTo(2);

        assertThat(lines).containsOnlyKeys("slate_c", "other_a");
        assertThat(holograms.ids()).isEmpty();
        assertThat(holograms.removeAll()).isZero();
    }

    @Test
    void whileTheHologramPluginIsDisabledEveryCallAnswersFalseAndCallsNothing() {
        PluginHolograms holograms = holograms();
        holograms.create("a", here, List.of());
        calls.clear();
        when(bound.isEnabled()).thenReturn(false);

        assertThat(holograms.available()).isFalse();
        assertThat(holograms.create("b", here, List.of())).isFalse();
        assertThat(holograms.update("a", List.of())).isFalse();
        assertThat(holograms.move("a", here)).isFalse();
        assertThat(holograms.exists("a")).isFalse();
        assertThat(holograms.ids()).isEmpty();
        assertThat(holograms.remove("a")).isFalse();
        assertThat(holograms.removeAll()).isZero();

        assertThat(calls).isEmpty();
        when(bound.isEnabled()).thenReturn(true);
        assertThat(holograms.ids()).containsExactly("a");
    }

    @Test
    void aFailureInsideTheHologramPluginIsWarnedReportedAndAnsweredAsNothingFound() {
        PluginHolograms holograms = holograms();
        holograms.create("a", here, List.of());
        IllegalStateException broken = new IllegalStateException("not loaded");
        failure = broken;

        assertThat(holograms.create("b", here, List.of())).isFalse();
        assertThat(holograms.update("a", List.of())).isFalse();
        assertThat(holograms.move("a", here)).isFalse();
        assertThat(holograms.exists("a")).isFalse();
        assertThat(holograms.ids()).isEmpty();

        assertThat(reported).hasSize(5).containsOnly("hook.decentholograms");
        assertThat(failures).containsOnly(broken);
        assertThat(logged.lines()).containsExactly(
            "WARNING DecentHolograms could not create a hologram (java.lang.IllegalStateException: not loaded).",
            "WARNING DecentHolograms could not update a hologram (java.lang.IllegalStateException: not loaded).",
            "WARNING DecentHolograms could not move a hologram (java.lang.IllegalStateException: not loaded).",
            "WARNING DecentHolograms could not find a hologram (java.lang.IllegalStateException: not loaded).");
        assertThat(logged.records.getFirst().getThrown()).isSameAs(broken);

        failure = null;
        assertThat(holograms.ids()).containsExactly("a");
    }

    @Test
    void aLinkageErrorIsCaughtAndAnyOtherErrorPropagates() {
        PluginHolograms holograms = holograms();
        holograms.create("a", here, List.of());
        failure = new NoClassDefFoundError("eu/decentsoftware/holograms/api/DHAPI");

        assertThat(holograms.remove("a")).isFalse();
        assertThat(reported).containsExactly("hook.decentholograms");
        assertThat(logged.lines()).containsExactly("WARNING DecentHolograms could not remove a hologram"
            + " (java.lang.NoClassDefFoundError: eu/decentsoftware/holograms/api/DHAPI).");

        failure = new OutOfMemoryError("heap");
        assertThatThrownBy(() -> holograms.exists("a")).isInstanceOf(OutOfMemoryError.class);
    }

    @Test
    void theProviderNamesItsHologramPluginAndFollowsItsAvailability() {
        PluginHolograms holograms = holograms();

        assertThat(holograms.plugin()).isEqualTo(HologramPlugin.DECENTHOLOGRAMS);
        assertThat(holograms.available()).isTrue();
        when(bound.isEnabled()).thenReturn(false);
        assertThat(holograms.available()).isFalse();
    }

    // Paper's Server.getWorld answers only for a loaded world, while a Location keeps answering with the World
    // object it was given for as long as that object is referenced.
    private Server server() {
        Server server = mock(Server.class);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(server.getWorld(any(UUID.class))).thenAnswer(call -> loaded.stream()
            .filter(candidate -> candidate.getUID().equals(call.getArgument(0)))
            .findFirst()
            .orElse(null));
        return server;
    }

    private Plugin owner() {
        Plugin owner = mock(Plugin.class);
        when(owner.getServer()).thenReturn(server);
        when(owner.getName()).thenReturn("Slate");
        when(owner.getLogger()).thenReturn(logged.logger);
        return owner;
    }

    @Test
    void detectionBuildsAProviderOnAnEnabledHologramPlugin() {
        FakePresence presence = new FakePresence();
        List<Plugin> built = new ArrayList<>();

        Detected<HologramProvider> detected = PluginHolograms.detect(owner(), errors, true,
            HologramPlugin.FANCYHOLOGRAMS, presence, plugin -> {
                built.add(plugin);
                return backend;
            });

        assertThat(detected.status().state()).isEqualTo(HookState.ON);
        assertThat(detected.status().plugin()).isEqualTo("FancyHolograms");
        assertThat(built).containsExactly(presence.plugins.get("FancyHolograms"));
        assertThat(presence.probes).containsExactly("de.oliver.fancyholograms.api.FancyHologramsPlugin");
        HologramProvider provider = detected.provider();
        assertThat(provider).isNotNull();
        assertThat(provider.plugin()).isEqualTo(HologramPlugin.FANCYHOLOGRAMS);
        assertThat(provider.create("a", here, List.of())).isTrue();
        assertThat(lines).containsOnlyKeys("slate_a");
        verify(pluginManager).registerEvents(same((PluginHolograms) provider), any(Plugin.class));
    }

    @Test
    void detectionSwitchedOffOrWithoutThePluginBuildsNothing() {
        FakePresence presence = new FakePresence();
        presence.missing.add("Holograms");
        List<Plugin> built = new ArrayList<>();

        Detected<HologramProvider> off = PluginHolograms.detect(owner(), errors, false, HologramPlugin.HOLOGRAMS,
            presence, plugin -> {
                built.add(plugin);
                return backend;
            });
        Detected<HologramProvider> missing = PluginHolograms.detect(owner(), errors, true, HologramPlugin.HOLOGRAMS,
            presence, plugin -> {
                built.add(plugin);
                return backend;
            });

        assertThat(off.status().state()).isEqualTo(HookState.SWITCHED_OFF);
        assertThat(missing.status().state()).isEqualTo(HookState.NOT_INSTALLED);
        assertThat(off.provider()).isNull();
        assertThat(missing.provider()).isNull();
        assertThat(built).isEmpty();
    }

    @Test
    void aBackendThatCannotBeBuiltFailsDetectionAndIsReported() {
        FakePresence presence = new FakePresence();
        NoSuchMethodError missing = new NoSuchMethodError("com.maximde.hologramlib.HologramLib.getManager()");

        Detected<HologramProvider> detected = PluginHolograms.detect(owner(), errors, true,
            HologramPlugin.HOLOGRAMLIB, presence, plugin -> {
                throw missing;
            });

        assertThat(detected.status().state()).isEqualTo(HookState.FAILED);
        assertThat(detected.provider()).isNull();
        assertThat(reported).containsExactly("hook.hologramlib");
        assertThat(failures).containsExactly(missing);
    }

    @Test
    void detectRefusesNullArguments() {
        FakePresence presence = new FakePresence();

        assertThatThrownBy(() -> HologramProvider.detect(null, errors, true, HologramPlugin.HOLOGRAMS))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> HologramProvider.detect(owner(), errors, true, null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PluginHolograms.detect(owner(), null, true, HologramPlugin.HOLOGRAMS, presence,
            plugin -> backend)).isInstanceOf(NullPointerException.class);
    }
}
