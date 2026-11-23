package so.alaz.taffy.hooks;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.internal.platform.WorldGuardPlatform;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import com.sk89q.worldguard.session.SessionManager;
import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.test.CapturedLog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

final class WorldGuardRegionsTest {

    private final Plugin plugin = mock(Plugin.class);
    private final World world = mock(World.class);
    private final RegionManager manager = mock(RegionManager.class);
    private final ApplicableRegionSet applicable = mock(ApplicableRegionSet.class);
    @SuppressWarnings("unchecked")
    private final Function<World, RegionManager> managers = mock(Function.class);
    @SuppressWarnings("unchecked")
    private final Function<Location, BlockVector3> vectors = mock(Function.class);
    @SuppressWarnings("unchecked")
    private final BiPredicate<Player, World> bypasses = mock(BiPredicate.class);
    @SuppressWarnings("unchecked")
    private final BiPredicate<Player, Location> builds = mock(BiPredicate.class);
    private final Player player = mock(Player.class);
    private final Location location = new Location(world, 10.5, 64, -3.2);
    private final List<String> reported = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();
    private final ErrorReports errors = new ErrorReports();
    private final CapturedLog logged = new CapturedLog();

    @BeforeEach
    void worldGuard() {
        when(plugin.isEnabled()).thenReturn(true);
        when(managers.apply(world)).thenReturn(manager);
        when(manager.getApplicableRegions(ArgumentMatchers.<BlockVector3>any())).thenReturn(applicable);
        errors.install((source, error) -> {
            reported.add(source);
            failures.add(error);
        });
    }

    private WorldGuardRegions regions() {
        return new WorldGuardRegions(plugin, logged.logger, errors, managers, vectors, bypasses, builds);
    }

    private static ProtectedRegion region(String id) {
        ProtectedRegion region = mock(ProtectedRegion.class);
        when(region.getId()).thenReturn(id);
        return region;
    }

    @Test
    void availabilityFollowsTheWorldGuardPlugin() {
        WorldGuardRegions regions = regions();

        assertThat(regions.available()).isTrue();
        when(plugin.isEnabled()).thenReturn(false);
        assertThat(regions.available()).isFalse();
    }

    @Test
    void aDisabledWorldGuardGivesNoRegionsWithoutALookup() {
        when(plugin.isEnabled()).thenReturn(false);

        assertThat(regions().regionsAt(location)).isEmpty();
        verifyNoInteractions(managers, vectors);
        assertThat(reported).isEmpty();
    }

    @Test
    void aLocationWithoutAWorldGivesNoRegionsWithoutAskingForAManager() {
        assertThat(regions().regionsAt(new Location(null, 0, 64, 0))).isEmpty();
        verifyNoInteractions(managers, vectors);
        assertThat(reported).isEmpty();
    }

    @Test
    void aLocationWhoseWorldUnloadedGivesNoRegionsAndNoReport() {
        Location stale = mock(Location.class);
        when(stale.getWorld()).thenThrow(new IllegalArgumentException("World unloaded"));

        assertThat(regions().regionsAt(stale)).isEmpty();
        verifyNoInteractions(managers, vectors);
        assertThat(reported).isEmpty();
    }

    @Test
    void aNullLocationThrowsWithoutAReport() {
        WorldGuardRegions regions = regions();

        assertThatThrownBy(() -> regions.regionsAt(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("location");
        assertThat(reported).isEmpty();
    }

    @Test
    void theRegionIdsAreUnmodifiable() {
        ProtectedRegion spawn = region("spawn");
        when(applicable.getRegions()).thenReturn(Set.of(spawn));

        List<String> ids = regions().regionsAt(location);

        assertThatThrownBy(() -> ids.add("other")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aWorldWithoutARegionManagerGivesNoRegions() {
        when(managers.apply(world)).thenReturn(null);

        assertThat(regions().regionsAt(location)).isEmpty();
        verifyNoInteractions(vectors);
        assertThat(reported).isEmpty();
    }

    @Test
    void theIdsOfEveryRegionCoveringTheBlockAreGivenInTheOrderWorldGuardGives() {
        Set<ProtectedRegion> covering = new LinkedHashSet<>(List.of(region("Spawn"), region("__global__"),
            region("harbour_east")));
        when(applicable.getRegions()).thenReturn(covering);

        assertThat(regions().regionsAt(location)).containsExactly("Spawn", "__global__", "harbour_east");
        verify(vectors).apply(location);
        assertThat(reported).isEmpty();
    }

    @Test
    void aBlockOutsideEveryRegionGivesNoRegions() {
        when(applicable.getRegions()).thenReturn(Set.of());

        assertThat(regions().regionsAt(location)).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    void aFailingLookupGivesNoRegionsAndOneReport() {
        IllegalStateException broken = new IllegalStateException("index rebuilding");
        when(manager.getApplicableRegions(ArgumentMatchers.<BlockVector3>any())).thenThrow(broken);

        assertThat(regions().regionsAt(location)).isEmpty();
        assertThat(reported).containsExactly("hook.worldguard");
        assertThat(failures).containsExactly(broken);
        assertThat(logged.lines()).containsExactly("WARNING WorldGuard could not look up the regions at a location"
            + " (java.lang.IllegalStateException: index rebuilding).");
        assertThat(logged.records.getFirst().getThrown()).isSameAs(broken);
    }

    @Test
    void repeatedFailuresAreLoggedOnceAMinuteAndEachIsReported() {
        IllegalStateException broken = new IllegalStateException("index rebuilding");
        when(manager.getApplicableRegions(ArgumentMatchers.<BlockVector3>any())).thenThrow(broken);
        WorldGuardRegions regions = regions();

        regions.regionsAt(location);
        regions.regionsAt(location);
        regions.regionsAt(location);

        assertThat(reported).containsExactly("hook.worldguard", "hook.worldguard", "hook.worldguard");
        assertThat(logged.lines()).hasSize(1);
    }

    @Test
    void aLinkageErrorInTheLookupGivesNoRegionsAndOneReport() {
        when(managers.apply(world)).thenThrow(new NoClassDefFoundError("com/sk89q/worldguard/WorldGuard"));

        assertThat(regions().regionsAt(location)).isEmpty();
        assertThat(reported).containsExactly("hook.worldguard");
    }

    @Test
    void anErrorOtherThanALinkageErrorIsRethrownAndNotReported() {
        when(managers.apply(world)).thenThrow(new OutOfMemoryError("heap"));
        WorldGuardRegions regions = regions();

        assertThatThrownBy(() -> regions.regionsAt(location)).isInstanceOf(OutOfMemoryError.class);
        assertThat(reported).isEmpty();
        assertThat(logged.lines()).isEmpty();
    }

    @Test
    void aPlayerWorldGuardRefusesMayNotBuild() {
        assertThat(regions().canBuild(player, location)).isFalse();
        verify(bypasses).test(player, world);
        verify(builds).test(player, location);
        assertThat(reported).isEmpty();
        assertThat(logged.lines()).isEmpty();
    }

    @Test
    void aPlayerTheBuildFlagAllowsMayBuild() {
        when(builds.test(player, location)).thenReturn(true);

        assertThat(regions().canBuild(player, location)).isTrue();
        assertThat(reported).isEmpty();
    }

    @Test
    void aPlayerWithTheBypassMayBuildWithoutARegionQuery() {
        when(bypasses.test(player, world)).thenReturn(true);

        assertThat(regions().canBuild(player, location)).isTrue();
        verifyNoInteractions(builds);
        assertThat(reported).isEmpty();
    }

    @Test
    void aDisabledWorldGuardLetsAPlayerBuildWithoutACheck() {
        when(plugin.isEnabled()).thenReturn(false);

        assertThat(regions().canBuild(player, location)).isTrue();
        verifyNoInteractions(bypasses, builds);
        assertThat(reported).isEmpty();
    }

    @Test
    void aLocationWithoutAWorldLetsAPlayerBuildWithoutACheck() {
        assertThat(regions().canBuild(player, new Location(null, 0, 64, 0))).isTrue();
        verifyNoInteractions(bypasses, builds);
        assertThat(reported).isEmpty();
    }

    @Test
    void aLocationWhoseWorldUnloadedLetsAPlayerBuildWithoutAReport() {
        Location stale = mock(Location.class);
        when(stale.getWorld()).thenThrow(new IllegalArgumentException("World unloaded"));

        assertThat(regions().canBuild(player, stale)).isTrue();
        verifyNoInteractions(bypasses, builds);
        assertThat(reported).isEmpty();
    }

    @Test
    void aBuildCheckRefusesAMissingPlayerOrLocationWithoutAReport() {
        WorldGuardRegions regions = regions();

        assertThatThrownBy(() -> regions.canBuild(null, location))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("player");
        assertThatThrownBy(() -> regions.canBuild(player, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("location");
        verifyNoInteractions(bypasses, builds);
        assertThat(reported).isEmpty();
    }

    @Test
    void aFailingBuildCheckLetsThePlayerBuildWithOneReport() {
        IllegalStateException broken = new IllegalStateException("no session");
        when(bypasses.test(player, world)).thenThrow(broken);

        assertThat(regions().canBuild(player, location)).isTrue();
        assertThat(reported).containsExactly("hook.worldguard");
        assertThat(failures).containsExactly(broken);
        assertThat(logged.lines()).containsExactly("WARNING WorldGuard could not check whether a player may build at"
            + " a location (java.lang.IllegalStateException: no session).");
        assertThat(logged.records.getFirst().getThrown()).isSameAs(broken);
    }

    @Test
    void repeatedBuildCheckFailuresAreLoggedOnceAMinuteApartFromFailedLookups() {
        when(builds.test(player, location)).thenThrow(new IllegalStateException("query"));
        when(manager.getApplicableRegions(ArgumentMatchers.<BlockVector3>any()))
            .thenThrow(new IllegalStateException("index rebuilding"));
        WorldGuardRegions regions = regions();

        regions.canBuild(player, location);
        regions.canBuild(player, location);
        regions.regionsAt(location);

        assertThat(reported).containsExactly("hook.worldguard", "hook.worldguard", "hook.worldguard");
        assertThat(logged.lines()).containsExactly(
            "WARNING WorldGuard could not check whether a player may build at a location"
                + " (java.lang.IllegalStateException: query).",
            "WARNING WorldGuard could not look up the regions at a location"
                + " (java.lang.IllegalStateException: index rebuilding).");
    }

    @Test
    void aLinkageErrorInTheBuildCheckLetsThePlayerBuildWithOneReport() {
        when(builds.test(player, location)).thenThrow(new NoSuchMethodError("RegionQuery.testBuild"));

        assertThat(regions().canBuild(player, location)).isTrue();
        assertThat(reported).containsExactly("hook.worldguard");
    }

    @Test
    void anErrorOtherThanALinkageErrorInTheBuildCheckIsRethrownAndNotReported() {
        when(builds.test(player, location)).thenThrow(new OutOfMemoryError("heap"));
        WorldGuardRegions regions = regions();

        assertThatThrownBy(() -> regions.canBuild(player, location)).isInstanceOf(OutOfMemoryError.class);
        assertThat(reported).isEmpty();
        assertThat(logged.lines()).isEmpty();
    }

    @Test
    void aProviderWithoutABuildCheckLetsEveryPlayerBuild() {
        RegionProvider idsOnly = new RegionProvider() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public List<String> regionsAt(Location at) {
                return List.of("spawn");
            }
        };

        assertThat(idsOnly.canBuild(player, location)).isTrue();
        assertThatThrownBy(() -> idsOnly.canBuild(null, location))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("player");
        assertThatThrownBy(() -> idsOnly.canBuild(player, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("location");
    }

    @Test
    void theBuildCheckCallsMethodsThePinnedWorldGuardAndWorldEditHold() {
        Class<?> position = com.sk89q.worldedit.util.Location.class;
        Class<?> weWorld = com.sk89q.worldedit.world.World.class;

        ApiLink.instanceMethod(WorldGuardPlatform.class, "getSessionManager", SessionManager.class);
        ApiLink.instanceMethod(SessionManager.class, "hasBypass", boolean.class, LocalPlayer.class, weWorld);
        ApiLink.instanceMethod(WorldGuardPlatform.class, "getRegionContainer", RegionContainer.class);
        ApiLink.instanceMethod(RegionContainer.class, "createQuery", RegionQuery.class);
        ApiLink.instanceMethod(RegionQuery.class, "testBuild", boolean.class, position, LocalPlayer.class,
            StateFlag[].class);
        ApiLink.staticMethod(BukkitAdapter.class, "adapt", position, Location.class);
        ApiLink.staticMethod(BukkitAdapter.class, "adapt", weWorld, World.class);
    }

    @Test
    void theFactoryBuildsAProviderWithoutCallingWorldGuard() {
        RegionProvider provider = WorldGuardRegions.create(plugin, logged.logger, errors);

        assertThat(provider).isInstanceOf(WorldGuardRegions.class);
        assertThat(provider.available()).isTrue();
        assertThat(provider.regionsAt(new Location(null, 0, 64, 0))).isEmpty();
        assertThat(provider.canBuild(player, new Location(null, 0, 64, 0))).isTrue();
        assertThat(reported).isEmpty();
    }

    private static PluginManager pluginManager(boolean installed) {
        PluginManager plugins = mock(PluginManager.class);
        if (installed) {
            Plugin worldGuard = mock(Plugin.class);
            PluginMeta meta = mock(PluginMeta.class);
            when(meta.getVersion()).thenReturn("7.0.20+e1ba8a4");
            when(worldGuard.getPluginMeta()).thenReturn(meta);
            when(worldGuard.isEnabled()).thenReturn(true);
            when(plugins.getPlugin("WorldGuard")).thenReturn(worldGuard);
            when(plugins.isPluginEnabled("WorldGuard")).thenReturn(true);
        }
        return plugins;
    }

    private static Plugin owner(PluginManager plugins, CapturedLog log) {
        Server server = mock(Server.class);
        when(server.getPluginManager()).thenReturn(plugins);
        Plugin owner = mock(Plugin.class);
        when(owner.getServer()).thenReturn(server);
        when(owner.getLogger()).thenReturn(log.logger);
        return owner;
    }

    @Test
    void detectingAnEnabledWorldGuardTurnsItOn() {
        CapturedLog log = new CapturedLog();

        Detected<RegionProvider> detected = RegionProvider.worldGuard(owner(pluginManager(true), log), errors, true);

        assertThat(detected.status()).isEqualTo(new HookStatus("WorldGuard", HookState.ON, "7.0.20+e1ba8a4", null));
        assertThat(detected.provider()).isInstanceOf(WorldGuardRegions.class);
        assertThat(detected.provider().available()).isTrue();
        assertThat(log.lines()).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    void anOnWorldGuardReadsNotEnabledWhileItIsDisabled() {
        PluginManager plugins = pluginManager(true);
        Plugin worldGuard = plugins.getPlugin("WorldGuard");
        Detected<RegionProvider> detected = RegionProvider.worldGuard(owner(plugins, new CapturedLog()), errors,
            true);

        when(worldGuard.isEnabled()).thenReturn(false);

        assertThat(detected.status()).isEqualTo(new HookStatus("WorldGuard", HookState.NOT_ENABLED, null, null));
        assertThat(detected.provider()).isInstanceOf(WorldGuardRegions.class);
        assertThat(detected.provider().available()).isFalse();

        when(worldGuard.isEnabled()).thenReturn(true);
        assertThat(detected.status()).isEqualTo(new HookStatus("WorldGuard", HookState.ON, "7.0.20+e1ba8a4", null));
    }

    @Test
    void detectingWithoutWorldGuardLeavesItOff() {
        CapturedLog log = new CapturedLog();

        Detected<RegionProvider> detected = RegionProvider.worldGuard(owner(pluginManager(false), log), errors, true);

        assertThat(detected.status()).isEqualTo(new HookStatus("WorldGuard", HookState.NOT_INSTALLED, null, null));
        assertThat(detected.provider()).isNull();
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aSwitchedOffWorldGuardIsNotLookedAt() {
        CapturedLog log = new CapturedLog();
        PluginManager plugins = mock(PluginManager.class);

        Detected<RegionProvider> detected = RegionProvider.worldGuard(owner(plugins, log), errors, false);

        assertThat(detected.status()).isEqualTo(new HookStatus("WorldGuard", HookState.SWITCHED_OFF, null, null));
        assertThat(detected.provider()).isNull();
        verify(plugins, never()).getPlugin(any());
        verify(plugins, never()).isPluginEnabled(any(String.class));
        assertThat(log.lines()).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    void detectingRefusesAMissingOwnerOrErrorSink() {
        Plugin owner = owner(pluginManager(true), new CapturedLog());

        assertThatThrownBy(() -> RegionProvider.worldGuard(null, errors, true))
            .isInstanceOf(NullPointerException.class).hasMessage("owner");
        assertThatThrownBy(() -> RegionProvider.worldGuard(owner, null, true))
            .isInstanceOf(NullPointerException.class).hasMessage("errors");
    }
}
