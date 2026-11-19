package so.alaz.taffy.hooks;

import com.maximde.hologramlib.HologramLib;
import com.maximde.hologramlib.hologram.TextHologram;
import de.oliver.fancyholograms.api.data.HologramData;
import de.oliver.fancyholograms.api.data.TextHologramData;
import eu.decentsoftware.holograms.api.DHAPI;
import eu.decentsoftware.holograms.api.DecentHolograms;
import eu.decentsoftware.holograms.api.DecentHologramsAPI;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.thenextlvl.hologram.line.TextHologramLine;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class HologramWiringTest {

    private final World world = mock(World.class);
    private final Location here = new Location(world, 1, 64, 1);
    private final Location there = new Location(world, 5, 70, 5);

    @Test
    void decentHologramsPlacesUpdatesMovesAndDestroysThroughDhapi() {
        try (MockedStatic<DecentHologramsAPI> running = mockStatic(DecentHologramsAPI.class);
             MockedStatic<DHAPI> dhapi = mockStatic(DHAPI.class)) {
            running.when(DecentHologramsAPI::get).thenReturn(mock(DecentHolograms.class));
            eu.decentsoftware.holograms.api.holograms.Hologram hologram =
                mock(eu.decentsoftware.holograms.api.holograms.Hologram.class);
            dhapi.when(() -> DHAPI.createHologram(eq("slate_a"), same(here), eq(false), anyList()))
                .thenReturn(hologram);
            HologramBackend backend = DecentHologramsBackend.create();

            backend.create("slate_a", here, List.of("<gold>Crate"));
            dhapi.verify(() -> DHAPI.createHologram("slate_a", here, false, List.of("&6Crate")));
            dhapi.when(() -> DHAPI.getHologram("slate_a")).thenReturn(hologram);
            assertThat(backend.taken("slate_a")).isTrue();
            assertThat(backend.has("slate_a")).isTrue();

            backend.update("slate_a", List.of("<red>Open"));
            dhapi.verify(() -> DHAPI.setHologramLines(hologram, List.of("&cOpen")));
            backend.move("slate_a", there);
            dhapi.verify(() -> DHAPI.moveHologram(hologram, there));

            assertThat(backend.remove("slate_a")).isTrue();
            verify(hologram).destroy();
            dhapi.verify(() -> DHAPI.removeHologram(anyString()), never());
            assertThat(backend.has("slate_a")).isFalse();
            assertThat(backend.remove("slate_a")).isFalse();
        }
    }

    @Test
    void decentHologramsDisablesAHologramAnotherReplacedAndLeavesTheReplacement() {
        try (MockedStatic<DecentHologramsAPI> running = mockStatic(DecentHologramsAPI.class);
             MockedStatic<DHAPI> dhapi = mockStatic(DHAPI.class)) {
            running.when(DecentHologramsAPI::get).thenReturn(mock(DecentHolograms.class));
            eu.decentsoftware.holograms.api.holograms.Hologram mine =
                mock(eu.decentsoftware.holograms.api.holograms.Hologram.class);
            eu.decentsoftware.holograms.api.holograms.Hologram owners =
                mock(eu.decentsoftware.holograms.api.holograms.Hologram.class);
            dhapi.when(() -> DHAPI.createHologram(eq("slate_a"), same(here), eq(false), anyList())).thenReturn(mine);
            HologramBackend backend = DecentHologramsBackend.create();
            backend.create("slate_a", here, List.of("x"));
            dhapi.when(() -> DHAPI.getHologram("slate_a")).thenReturn(owners);

            backend.update("slate_a", List.of("y"));

            verify(mine).disable(eu.decentsoftware.holograms.api.holograms.DisableCause.API);
            dhapi.verify(() -> DHAPI.setHologramLines(any(), anyList()), never());
            assertThat(backend.remove("slate_a")).isFalse();
            verify(owners, never()).destroy();
        }
    }

    @Test
    void fancyHologramsHidesAHologramAnotherReplacedFromItsViewers() {
        de.oliver.fancyholograms.api.HologramManager manager = mock(de.oliver.fancyholograms.api.HologramManager.class);
        de.oliver.fancyholograms.api.hologram.Hologram mine =
            mock(de.oliver.fancyholograms.api.hologram.Hologram.class);
        de.oliver.fancyholograms.api.hologram.Hologram owners =
            mock(de.oliver.fancyholograms.api.hologram.Hologram.class);
        java.util.UUID viewer = java.util.UUID.randomUUID();
        org.bukkit.entity.Player player = mock(org.bukkit.entity.Player.class);
        when(manager.create(any())).thenReturn(mine);
        when(mine.getViewers()).thenReturn(java.util.Set.of(viewer));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(viewer)).thenReturn(player);
            HologramBackend backend = FancyHologramsBackend.create(() -> manager);
            backend.create("slate_a", here, List.of("x"));
            when(manager.getHologram("slate_a")).thenReturn(Optional.of(owners));

            assertThat(backend.has("slate_a")).isFalse();

            verify(mine).forceHideHologram(player);
            assertThat(backend.remove("slate_a")).isFalse();
            verify(manager, never()).removeHologram(any());
        }
    }

    @Test
    void fancyHologramsPlacesATemporaryTextHologramAndRemovesItUnlessTheServerStops() {
        de.oliver.fancyholograms.api.HologramManager manager = mock(de.oliver.fancyholograms.api.HologramManager.class);
        de.oliver.fancyholograms.api.hologram.Hologram hologram =
            mock(de.oliver.fancyholograms.api.hologram.Hologram.class);
        when(manager.create(any())).thenReturn(hologram);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            HologramBackend backend = FancyHologramsBackend.create(() -> manager);

            backend.create("slate_a", here, List.of("<gold>Crate", "%slate_keys%"));
            ArgumentCaptor<HologramData> created = ArgumentCaptor.forClass(HologramData.class);
            InOrder order = inOrder(manager);
            order.verify(manager).create(created.capture());
            order.verify(manager).addHologram(hologram);
            TextHologramData data = (TextHologramData) created.getValue();
            assertThat(data.getName()).isEqualTo("slate_a");
            assertThat(data.getLocation()).isEqualTo(here);
            assertThat(data.isPersistent()).isFalse();
            assertThat(data.getText()).containsExactly("<gold>Crate", "%slate_keys%");
            assertThat(data.getTextUpdateInterval()).isEqualTo(FancyHologramsBackend.PLACEHOLDER_REFRESH_MILLIS);

            when(manager.getHologram("slate_a")).thenReturn(Optional.of(hologram));
            when(hologram.getData()).thenReturn(data);
            assertThat(backend.taken("slate_a")).isTrue();
            assertThat(backend.has("slate_a")).isTrue();
            backend.update("slate_a", List.of("<red>Open"));
            assertThat(data.getText()).containsExactly("<red>Open");
            assertThat(data.getTextUpdateInterval()).isEqualTo(FancyHologramsBackend.NO_REFRESH);
            backend.move("slate_a", there);
            assertThat(data.getLocation()).isEqualTo(there);
            verify(hologram, times(2)).queueUpdate();

            assertThat(backend.remove("slate_a")).isTrue();
            verify(manager).removeHologram(hologram);
            assertThat(backend.has("slate_a")).isFalse();

            backend.create("slate_a", here, List.of("x"));
            bukkit.when(Bukkit::isStopping).thenReturn(true);
            assertThat(backend.remove("slate_a")).isTrue();
            verify(manager, times(1)).removeHologram(hologram);
            assertThat(backend.has("slate_a")).isFalse();
        }
    }

    @Test
    void hologramsSpawnsATemporaryHologramWithItsLinesAndKeepsOneThePluginWouldNotDelete() {
        net.thenextlvl.hologram.HologramProvider api = mock(net.thenextlvl.hologram.HologramProvider.class);
        List<String> shown = new ArrayList<>();
        net.thenextlvl.hologram.Hologram hologram = nextLvl(shown);
        when(api.spawnHologram(eq("slate_a"), same(here), any())).thenAnswer(call -> {
            Consumer<net.thenextlvl.hologram.Hologram> setup = call.getArgument(2);
            setup.accept(hologram);
            return hologram;
        });
        try (MockedStatic<net.thenextlvl.hologram.HologramProvider> provider =
                 mockStatic(net.thenextlvl.hologram.HologramProvider.class)) {
            provider.when(net.thenextlvl.hologram.HologramProvider::instance).thenReturn(api);
            HologramBackend backend = NextLvlHologramsBackend.create();

            backend.create("slate_a", here, List.of("one", "two"));
            verify(hologram).setPersistent(false);
            assertThat(shown).containsExactly("one", "two");

            when(api.hasHologram("slate_a")).thenReturn(true);
            when(api.getHologram("slate_a")).thenReturn(Optional.of(hologram));
            assertThat(backend.taken("slate_a")).isTrue();
            assertThat(backend.has("slate_a")).isTrue();
            backend.update("slate_a", List.of("solo"));
            assertThat(shown).containsExactly("solo");
            backend.move("slate_a", there);
            verify(hologram).teleportAsync(there);

            assertThat(backend.remove("slate_a")).isFalse();
            assertThat(backend.has("slate_a")).isTrue();
            when(api.deleteHologram(hologram)).thenReturn(true);
            assertThat(backend.remove("slate_a")).isTrue();
            assertThat(backend.has("slate_a")).isFalse();
        }
    }

    private static net.thenextlvl.hologram.Hologram nextLvl(List<String> shown) {
        net.thenextlvl.hologram.Hologram hologram = mock(net.thenextlvl.hologram.Hologram.class);
        when(hologram.getLineCount()).thenAnswer(call -> shown.size());
        when(hologram.getLine(anyInt(), eq(TextHologramLine.class)))
            .thenAnswer(call -> Optional.of(line(shown, call.getArgument(0))));
        when(hologram.removeLine(anyInt())).thenAnswer(call -> {
            shown.remove((int) call.getArgument(0));
            return true;
        });
        when(hologram.addTextLine()).thenAnswer(call -> {
            shown.add("");
            return line(shown, shown.size() - 1);
        });
        return hologram;
    }

    private static TextHologramLine line(List<String> shown, int index) {
        TextHologramLine line = mock(TextHologramLine.class);
        when(line.setUnparsedText(anyString())).thenAnswer(call -> {
            shown.set(index, call.getArgument(0));
            return true;
        });
        return line;
    }

    @Test
    void hologramLibSpawnsOneTextAndMovesAHologramThatHasNotSpawnedAfterTheSpawn() {
        Plugin hologramLib = mock(Plugin.class);
        com.maximde.hologramlib.hologram.HologramManager manager =
            mock(com.maximde.hologramlib.hologram.HologramManager.class);
        GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        List<Consumer<ScheduledTask>> later = new ArrayList<>();
        when(scheduler.runDelayed(same(hologramLib), any(), eq(HologramLibBackend.AFTER_SPAWN_TICKS)))
            .thenAnswer(call -> {
                later.add(call.getArgument(1));
                return null;
            });
        doAnswer(call -> call.getArgument(0)).when(manager).spawn(any(TextHologram.class), any(Location.class));
        List<Object> names = new ArrayList<>();
        try (MockedStatic<HologramLib> lib = mockStatic(HologramLib.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedConstruction<TextHologram> built = mockConstruction(TextHologram.class,
                 (hologram, context) -> names.add(context.arguments().getFirst()))) {
            lib.when(HologramLib::getManager).thenReturn(Optional.of(manager));
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            HologramBackend backend = HologramLibBackend.create(hologramLib);

            backend.create("slate_a", here, List.of("<gold>Crate", "two"));
            TextHologram hologram = built.constructed().getFirst();
            assertThat(names).containsExactly("slate_a");
            InOrder order = inOrder(hologram, manager);
            order.verify(hologram).setPlaceholderApiEnabled(true);
            order.verify(hologram).setMiniMessageText("<gold>Crate\ntwo");
            order.verify(manager).spawn(hologram, here);

            when(manager.hologramExists("slate_a")).thenReturn(true);
            when(manager.getHologram("slate_a")).thenReturn(Optional.of(hologram));
            assertThat(backend.taken("slate_a")).isTrue();
            assertThat(backend.has("slate_a")).isTrue();
            backend.move("slate_a", there);
            verify(hologram, never()).teleport(any());
            assertThat(later).hasSize(1);
            when(hologram.getLocation()).thenReturn(here);
            later.getFirst().accept(mock(ScheduledTask.class));
            verify(hologram).teleport(there);

            backend.update("slate_a", List.of("new"));
            verify(hologram).setMiniMessageText("new");
            verify(hologram).update();
            when(manager.remove("slate_a")).thenReturn(true);
            assertThat(backend.remove("slate_a")).isTrue();
            assertThat(backend.has("slate_a")).isFalse();
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void hologramLibBuildsNoHologramForTextMiniMessageRejectsAndKillsOneThatFailsToSpawn() {
        com.maximde.hologramlib.hologram.HologramManager manager =
            mock(com.maximde.hologramlib.hologram.HologramManager.class);
        IllegalStateException refused = new IllegalStateException("no spawn");
        doAnswer(call -> {
            throw refused;
        }).when(manager).spawn(any(TextHologram.class), any(Location.class));
        try (MockedStatic<HologramLib> lib = mockStatic(HologramLib.class);
             MockedConstruction<TextHologram> built = mockConstruction(TextHologram.class)) {
            lib.when(HologramLib::getManager).thenReturn(Optional.of(manager));
            HologramBackend backend = HologramLibBackend.create(mock(Plugin.class));

            assertThatThrownBy(() -> backend.create("slate_a", here, List.of("\u00a7cCrate")))
                .isInstanceOf(RuntimeException.class);
            assertThat(built.constructed()).isEmpty();

            assertThatThrownBy(() -> backend.create("slate_a", here, List.of("Crate"))).isSameAs(refused);
            verify(built.constructed().getFirst()).kill();
            assertThat(backend.has("slate_a")).isFalse();
        }
    }
}
