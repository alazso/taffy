package so.alaz.taffy.hooks;

import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Pig;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.util.Transformation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.platform.Scheduler;
import so.alaz.taffy.test.CapturedLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs the display provider over the real {@link Scheduler} and a recording {@code RegionScheduler}, so every entity
 * call is seen to wait for the task of its chunk. {@code owned} is whether the calling thread owns the location: true
 * as on Paper's main thread, false as on a Folia thread that does not own the region.
 */
final class EntityHologramsTest {

    private record Queued(World world, int chunkX, int chunkZ, Runnable task) {
    }

    private static final class Spawn {
        final Display entity;
        final Location at;
        final Map<NamespacedKey, String> data = new HashMap<>();
        Component text = Component.empty();
        boolean removed;

        Spawn(Display entity, Location at) {
            this.entity = entity;
            this.at = at;
        }
    }

    private final PluginManager pluginManager = mock(PluginManager.class);
    private final RegionScheduler regionScheduler = mock(RegionScheduler.class);
    private final CapturedLog logged = new CapturedLog();
    private final Server server = mock(Server.class);
    private final Plugin owner = mock(Plugin.class);
    private final World world = world("00000000-0000-0000-0000-000000000001");
    private final Location here = new Location(world, 17.5, 64, -2.5);
    private final List<Queued> queued = new ArrayList<>();
    private final List<Spawn> spawns = new ArrayList<>();
    private final List<String> reported = new ArrayList<>();
    private final ErrorReports errors = new ErrorReports();
    private final Scheduler scheduler;
    private boolean owned = true;
    private boolean chunkLoaded = true;
    private int failSpawnAt = -1;
    private int failRemoveAt = -1;

    EntityHologramsTest() {
        when(owner.getServer()).thenReturn(server);
        when(owner.getName()).thenReturn("Slate");
        when(owner.namespace()).thenReturn("slate");
        when(owner.getLogger()).thenReturn(logged.logger);
        when(owner.isEnabled()).thenReturn(true);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(server.getRegionScheduler()).thenReturn(regionScheduler);
        when(server.getWorlds()).thenReturn(List.of(world));
        when(server.isOwnedByCurrentRegion(any(Location.class))).thenAnswer(call -> owned);
        when(regionScheduler.run(eq(owner), any(World.class), anyInt(), anyInt(), any())).thenAnswer(this::enqueue);
        scheduler = new Scheduler(owner, errors);
    }

    @BeforeEach
    void reports() {
        errors.install((source, error) -> reported.add(source));
    }

    private World world(String id) {
        World made = mock(World.class);
        UUID uid = UUID.fromString(id);
        when(made.getUID()).thenReturn(uid);
        when(made.getLoadedChunks()).thenReturn(new Chunk[0]);
        when(made.isChunkLoaded(anyInt(), anyInt())).thenAnswer(call -> chunkLoaded);
        when(server.getWorld(uid)).thenReturn(made);
        when(made.spawn(any(Location.class), eq(TextDisplay.class), anyConsumer())).thenAnswer(this::spawn);
        when(made.spawn(any(Location.class), eq(ItemDisplay.class), anyConsumer())).thenAnswer(this::spawn);
        return made;
    }

    private static <T> Consumer<T> anyConsumer() {
        return any();
    }

    private ScheduledTask enqueue(InvocationOnMock call) {
        Consumer<ScheduledTask> task = call.getArgument(4);
        ScheduledTask handle = mock(ScheduledTask.class);
        queued.add(new Queued(call.getArgument(1), call.getArgument(2), call.getArgument(3),
            () -> task.accept(handle)));
        return handle;
    }

    private Display spawn(InvocationOnMock call) {
        if (spawns.size() == failSpawnAt) {
            throw new IllegalStateException("spawn refused");
        }
        Class<? extends Display> type = call.getArgument(1);
        Consumer<Display> configure = call.getArgument(2);
        Display entity = mock(type);
        Spawn spawn = new Spawn(entity, call.<Location>getArgument(0).clone());
        tagged(entity, spawn.data);
        when(entity.isValid()).thenAnswer(valid -> !spawn.removed);
        boolean refusesRemoval = spawns.size() == failRemoveAt;
        doAnswer(remove -> {
            if (refusesRemoval) {
                throw new IllegalStateException("remove refused");
            }
            return spawn.removed = true;
        }).when(entity).remove();
        if (entity instanceof TextDisplay text) {
            doAnswer(set -> spawn.text = set.getArgument(0)).when(text).text(any());
        }
        configure.accept(entity);
        spawns.add(spawn);
        return entity;
    }

    private static void tagged(Entity entity, Map<NamespacedKey, String> data) {
        PersistentDataContainer container = mock(PersistentDataContainer.class);
        UUID id = UUID.randomUUID();
        when(entity.getUniqueId()).thenReturn(id);
        when(entity.getPersistentDataContainer()).thenReturn(container);
        when(container.has(any(NamespacedKey.class), eq(PersistentDataType.STRING)))
            .thenAnswer(call -> data.containsKey(call.<NamespacedKey>getArgument(0)));
        doAnswer(call -> data.put(call.getArgument(0), call.getArgument(2)))
            .when(container).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), any(String.class));
    }

    private void runQueued() {
        List<Queued> tasks = List.copyOf(queued);
        queued.clear();
        tasks.forEach(task -> task.task().run());
    }

    private List<Spawn> standing() {
        return spawns.stream().filter(spawn -> !spawn.removed).toList();
    }

    private List<String> standingText() {
        return standing().stream().filter(spawn -> spawn.entity instanceof TextDisplay)
            .map(spawn -> PlainTextComponentSerializer.plainText().serialize(spawn.text)).toList();
    }

    private EntityHolograms holograms() {
        return EntityHolograms.create(owner, scheduler, errors);
    }

    private Chunk chunk(int x, int z) {
        Chunk chunk = mock(Chunk.class);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getX()).thenReturn(x);
        when(chunk.getZ()).thenReturn(z);
        when(chunk.isLoaded()).thenReturn(true);
        when(chunk.getEntities()).thenReturn(new Entity[0]);
        return chunk;
    }

    private static PluginDisableEvent disabling(Plugin plugin) {
        PluginDisableEvent event = mock(PluginDisableEvent.class);
        when(event.getPlugin()).thenReturn(plugin);
        return event;
    }

    private static ItemStack item() {
        ItemStack stack = mock(ItemStack.class);
        ItemStack copy = mock(ItemStack.class);
        when(stack.clone()).thenReturn(copy);
        when(copy.clone()).thenReturn(copy);
        return stack;
    }

    @Test
    void theEntryPointGivesAProviderBoundToNoHologramPlugin() {
        DisplayHolograms provider = HologramProvider.displays(owner, scheduler, errors);

        assertThat(provider.plugin()).isNull();
        assertThat(provider.available()).isTrue();
        verify(pluginManager).registerEvents((Listener) provider, owner);
        assertThatThrownBy(() -> HologramProvider.displays(null, scheduler, errors))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> HologramProvider.displays(owner, null, errors))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> HologramProvider.displays(owner, scheduler, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aHologramSpawnsOnlyInTheTaskOfItsChunk() {
        EntityHolograms holograms = holograms();

        assertThat(holograms.create("crate", here, List.of("<gold>Daily crate"))).isTrue();

        assertThat(spawns).isEmpty();
        assertThat(holograms.exists("crate")).isTrue();
        assertThat(queued).singleElement().satisfies(task -> {
            assertThat(task.world()).isSameAs(world);
            assertThat(task.chunkX()).isEqualTo(1);
            assertThat(task.chunkZ()).isEqualTo(-1);
        });
        runQueued();
        assertThat(standingText()).containsExactly("Daily crate");
    }

    @Test
    void linesStackUpwardFromTheLocationWithTheLastLineLowest() {
        EntityHolograms holograms = holograms();

        holograms.create("crate", here, List.of(Component.text("top"), Component.text("middle"),
            Component.text("bottom")), null);
        runQueued();

        assertThat(standingText()).containsExactly("top", "middle", "bottom");
        assertThat(spawns).extracting(spawn -> spawn.at.getY())
            .containsExactly(64 + 2 * DisplayHolograms.LINE_SPACING, 64 + DisplayHolograms.LINE_SPACING, 64.0);
        assertThat(spawns).allSatisfy(spawn -> {
            assertThat(spawn.at.getX()).isEqualTo(17.5);
            assertThat(spawn.at.getZ()).isEqualTo(-2.5);
        });
    }

    @Test
    void everyEntityIsNonPersistentTaggedWithTheOwnersKeyAndFacesTheViewer() {
        EntityHolograms holograms = holograms();

        holograms.create("crate", here, List.of(Component.text("line")), item());
        runQueued();

        assertThat(spawns).hasSize(2).allSatisfy(spawn -> {
            verify(spawn.entity).setPersistent(false);
            verify(spawn.entity).setBillboard(Display.Billboard.CENTER);
            assertThat(spawn.data).containsExactly(Map.entry(new NamespacedKey("slate", "taffy_hologram"), "crate"));
        });
        TextDisplay line = (TextDisplay) spawns.get(0).entity;
        verify(line).setShadowed(true);
        verify(line).setSeeThrough(false);
        verify(line).setAlignment(TextDisplay.TextAlignment.CENTER);
        verify(line).setLineWidth(Integer.MAX_VALUE);
        verify(line, never()).setViewRange(any(Float.class));
    }

    @Test
    void theItemStandsAboveTheTopLineAsACopyAtItsScale() {
        EntityHolograms holograms = holograms();
        ItemStack stack = item();

        holograms.create("crate", here, List.of(Component.text("top"), Component.text("bottom")), stack);
        runQueued();

        Spawn spawn = spawns.get(2);
        ItemDisplay display = (ItemDisplay) spawn.entity;
        assertThat(spawn.at.getY()).isEqualTo(64 + DisplayHolograms.LINE_SPACING + DisplayHolograms.ITEM_RISE);
        verify(display).setItemStack(stack.clone());
        verify(display).setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        ArgumentCaptor<Transformation> shape = ArgumentCaptor.forClass(Transformation.class);
        verify(display).setTransformation(shape.capture());
        assertThat(shape.getValue().getScale().x()).isEqualTo(DisplayHolograms.ITEM_SCALE);
    }

    @Test
    void anItemWithNoLinesStandsAboveTheLocation() {
        EntityHolograms holograms = holograms();

        holograms.create("crate", here, List.of(), item());
        runQueued();

        assertThat(spawns).singleElement()
            .satisfies(spawn -> assertThat(spawn.at.getY()).isEqualTo(64 + DisplayHolograms.ITEM_RISE));
    }

    @Test
    void aStringLineIsMiniMessageAndLiteralTextShowsAsWritten() {
        EntityHolograms holograms = holograms();

        holograms.create("crate", here, List.of("<gold>Opened by " + HologramProvider.literal("<red>Ann")));
        runQueued();

        assertThat(spawns.getFirst().text.color()).isEqualTo(NamedTextColor.GOLD);
        assertThat(standingText()).singleElement().satisfies(text ->
            assertThat(text.replace(PluginHolograms.ZERO_WIDTH_NON_JOINER, "")).isEqualTo("Opened by <red>Ann"));
    }

    @Test
    void aLineMiniMessageRefusesFailsTheCallAndPlacesNothing() {
        EntityHolograms holograms = holograms();

        assertThat(holograms.create("crate", here, List.of("§6Daily crate"))).isFalse();

        assertThat(holograms.exists("crate")).isFalse();
        assertThat(queued).isEmpty();
        assertThat(reported).containsExactly("hook.displays");
    }

    @Test
    void anIdInUseIsRefusedAndItsHologramIsLeftAsItIs() {
        EntityHolograms holograms = holograms();
        holograms.create("crate", here, List.of("first"));
        runQueued();

        assertThat(holograms.create("crate", here, List.of("second"))).isFalse();
        assertThat(holograms.create("crate", here, List.of(Component.text("third")), null)).isFalse();
        runQueued();

        assertThat(standingText()).containsExactly("first");
        assertThat(spawns).hasSize(1);
    }

    @Test
    void anIdThatIsNotInUseAnswersFalseAndAnInvalidIdThrows() {
        EntityHolograms holograms = holograms();

        assertThat(holograms.update("none", List.of("line"))).isFalse();
        assertThat(holograms.update("none", List.of(Component.text("line")), null)).isFalse();
        assertThat(holograms.move("none", here)).isFalse();
        assertThat(holograms.remove("none")).isFalse();
        assertThat(holograms.exists("none")).isFalse();
        assertThat(queued).isEmpty();
        assertThatThrownBy(() -> holograms.create("Not Valid", here, List.of(), null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(PluginHolograms.EXPECTED);
        assertThatThrownBy(() -> holograms.update("Not Valid", List.of(), null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> holograms.exists("Not Valid")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUpdateWithTheSameShapeChangesTheTextInPlace() {
        EntityHolograms holograms = holograms();
        holograms.create("crate", here, List.of("one", "two"));
        runQueued();

        assertThat(holograms.update("crate", List.of("three", "four"))).isTrue();

        assertThat(standingText()).containsExactly("one", "two");
        runQueued();
        assertThat(standingText()).containsExactly("three", "four");
        assertThat(spawns).hasSize(2);
    }

    @Test
    void anUpdateWithAnotherNumberOfLinesRemovesTheEntitiesAndSpawnsThemAgain() {
        EntityHolograms holograms = holograms();
        holograms.create("crate", here, List.of("one", "two"));
        runQueued();

        holograms.update("crate", List.of("only"));
        runQueued();

        assertThat(standingText()).containsExactly("only");
        assertThat(spawns).hasSize(3);
        assertThat(spawns.subList(0, 2)).allSatisfy(spawn -> assertThat(spawn.removed).isTrue());
    }

    @Test
    void aTextUpdateKeepsTheItemAndAComponentUpdateReplacesIt() {
        EntityHolograms holograms = holograms();
        holograms.create("crate", here, List.of(Component.text("one")), item());
        runQueued();

        holograms.update("crate", List.of("two"));
        runQueued();
        assertThat(standing()).hasSize(2);
        assertThat(spawns).hasSize(2);

        holograms.update("crate", List.of(Component.text("three")), null);
        runQueued();
        assertThat(standing()).singleElement()
            .satisfies(spawn -> assertThat(spawn.entity).isInstanceOf(TextDisplay.class));
        assertThat(standingText()).containsExactly("three");
    }

    @Test
    void anEntitySomethingElseRemovedIsSpawnedAgainAtTheNextUpdate() {
        EntityHolograms holograms = holograms();
        holograms.create("crate", here, List.of("one"));
        runQueued();
        spawns.getFirst().removed = true;

        holograms.update("crate", List.of("two"));
        runQueued();

        assertThat(standingText()).containsExactly("two");
    }

    @Test
    void changesMadeBeforeTheRegionThreadRunsAreShownByOneTask() {
        EntityHolograms holograms = holograms();

        holograms.create("crate", here, List.of("one"));
        holograms.update("crate", List.of("two"));
        holograms.update("crate", List.of(Component.text("three")), item());

        assertThat(queued).hasSize(1);
        runQueued();
        assertThat(standingText()).containsExactly("three");
        assertThat(spawns).hasSize(2);
    }

    @Test
    void aMoveRemovesTheEntitiesInTheOldChunksTaskAndSpawnsThemInTheNewOne() {
        EntityHolograms holograms = holograms();
        holograms.create("crate", here, List.of("line"));
        runQueued();
        Location there = new Location(world, 100.5, 70, 100.5);

        assertThat(holograms.move("crate", there)).isTrue();

        assertThat(standing()).hasSize(1);
        assertThat(queued).extracting(Queued::chunkX).containsExactly(1, 6);
        runQueued();
        assertThat(spawns.getFirst().removed).isTrue();
        assertThat(standing()).singleElement().satisfies(spawn -> assertThat(spawn.at).isEqualTo(there));
        assertThat(holograms.ids()).containsExactly("crate");
    }

    @Test
    void aRemovalOnTheThreadThatOwnsTheLocationIsDoneBeforeTheCallReturns() {
        EntityHolograms holograms = holograms();
        holograms.create("crate", here, List.of("line"));
        runQueued();

        assertThat(holograms.remove("crate")).isTrue();

        assertThat(standing()).isEmpty();
        assertThat(queued).isEmpty();
        assertThat(holograms.exists("crate")).isFalse();
        assertThat(holograms.remove("crate")).isFalse();
    }

    @Test
    void aRemovalFromAnotherThreadWaitsForTheTaskOfTheChunk() {
        EntityHolograms holograms = holograms();
        holograms.create("crate", here, List.of("line"));
        runQueued();
        owned = false;

        assertThat(holograms.remove("crate")).isTrue();

        assertThat(holograms.exists("crate")).isFalse();
        assertThat(standing()).hasSize(1);
        assertThat(queued).singleElement().satisfies(task -> assertThat(task.chunkX()).isEqualTo(1));
        runQueued();
        assertThat(standing()).isEmpty();
    }

    @Test
    void aHologramRemovedBeforeItsTaskRanSpawnsNothing() {
        EntityHolograms holograms = holograms();
        owned = false;
        holograms.create("crate", here, List.of("line"));

        holograms.remove("crate");
        runQueued();

        assertThat(spawns).isEmpty();
    }

    @Test
    void aHologramInAChunkThatIsNotLoadedIsKeptAndSpawnedWhenTheChunkLoads() {
        EntityHolograms holograms = holograms();
        chunkLoaded = false;

        assertThat(holograms.create("crate", here, List.of("line"))).isTrue();
        runQueued();
        assertThat(spawns).isEmpty();
        assertThat(holograms.exists("crate")).isTrue();

        chunkLoaded = true;
        holograms.onChunkLoad(new ChunkLoadEvent(chunk(0, 0), false));
        assertThat(queued).isEmpty();
        holograms.onChunkLoad(new ChunkLoadEvent(chunk(1, -1), false));
        assertThat(spawns).isEmpty();
        runQueued();
        assertThat(standingText()).containsExactly("line");
    }

    @Test
    void aChunkUnloadRemovesTheEntitiesAndAReloadSpawnsOneSet() {
        EntityHolograms holograms = holograms();
        holograms.create("crate", here, List.of("line"));
        runQueued();

        holograms.onChunkUnload(new ChunkUnloadEvent(chunk(1, -1)));
        assertThat(standing()).isEmpty();
        assertThat(holograms.exists("crate")).isTrue();

        holograms.onChunkLoad(new ChunkLoadEvent(chunk(1, -1), false));
        holograms.onChunkLoad(new ChunkLoadEvent(chunk(1, -1), false));
        runQueued();
        assertThat(standing()).hasSize(1);
        assertThat(spawns).hasSize(2);
    }

    @Test
    void aLocationWithNoWorldOrAnUnloadedWorldIsRefused() {
        EntityHolograms holograms = holograms();
        World unloaded = mock(World.class);
        when(unloaded.getUID()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        holograms.create("crate", here, List.of("line"));

        assertThat(holograms.create("a", new Location(null, 0, 64, 0), List.of("line"))).isFalse();
        assertThat(holograms.create("b", new Location(unloaded, 0, 64, 0), List.of("line"))).isFalse();
        assertThat(holograms.move("crate", new Location(null, 0, 64, 0))).isFalse();
        assertThat(holograms.move("crate", new Location(unloaded, 0, 64, 0))).isFalse();

        assertThat(holograms.ids()).containsExactly("crate");
        assertThat(queued).hasSize(1);
    }

    @Test
    void aWorldUnloadRemovesItsHologramsFromTheWorldAndTheRecords() {
        EntityHolograms holograms = holograms();
        World other = world("00000000-0000-0000-0000-000000000003");
        holograms.create("crate", here, List.of("line"));
        holograms.create("other", new Location(other, 0, 64, 0), List.of("kept"));
        runQueued();

        holograms.onWorldUnload(new WorldUnloadEvent(world));

        assertThat(standingText()).containsExactly("kept");
        assertThat(holograms.ids()).containsExactly("other");
        assertThat(holograms.update("crate", List.of("again"))).isFalse();
    }

    @Test
    void aWorldThatUnloadedBeforeTheTaskRanSpawnsNothingAndForgetsTheHologram() {
        EntityHolograms holograms = holograms();
        holograms.create("crate", here, List.of("line"));
        when(server.getWorld(world.getUID())).thenReturn(null);

        runQueued();

        assertThat(spawns).isEmpty();
        assertThat(holograms.ids()).isEmpty();
    }

    @Test
    void aFailedSpawnRemovesWhatItSpawnedForgetsTheHologramAndIsReported() {
        EntityHolograms holograms = holograms();
        failSpawnAt = 1;

        assertThat(holograms.create("crate", here, List.of("one", "two"))).isTrue();
        runQueued();

        assertThat(spawns).singleElement().satisfies(spawn -> assertThat(spawn.removed).isTrue());
        assertThat(holograms.exists("crate")).isFalse();
        assertThat(reported).containsExactly("hook.displays");
        assertThat(logged.lines()).singleElement().asString().contains("could not show a hologram");
    }

    @Test
    void theOwnersDisableEventRemovesEveryHologramOnPaper() {
        EntityHolograms holograms = holograms();
        Plugin other = mock(Plugin.class);
        holograms.create("a", here, List.of("one"));
        holograms.create("b", here, List.of("two"));
        runQueued();

        holograms.onPluginDisable(disabling(other));
        assertThat(standing()).hasSize(2);

        holograms.onPluginDisable(disabling(owner));
        assertThat(standing()).isEmpty();
        assertThat(holograms.ids()).isEmpty();
        assertThat(queued).isEmpty();
    }

    @Test
    void aDisableOnAThreadThatOwnsNoRegionForgetsTheHologramsAndThrowsNothing() {
        EntityHolograms holograms = holograms();
        holograms.create("crate", here, List.of("line"));
        runQueued();
        owned = false;
        when(regionScheduler.run(eq(owner), any(World.class), anyInt(), anyInt(), any()))
            .thenThrow(new IllegalPluginAccessException("disabling"));

        holograms.close();

        assertThat(holograms.available()).isFalse();
        assertThat(standing()).hasSize(1);
        verify(standing().getFirst().entity).setPersistent(false);
    }

    // The real Scheduler, reading Folia as it does on a Folia server.
    private Scheduler foliaScheduler() {
        Scheduler folia = spy(new Scheduler(owner, errors));
        doReturn(Scheduler.Mode.FOLIA).when(folia).mode();
        return folia;
    }

    // At a Folia stop the Region shutdown thread owns every location, the server answers isStopping, and Folia's
    // Entity.remove throws because that thread holds no region's world data.
    private void stopLikeFolia() {
        when(server.isStopping()).thenReturn(true);
        owned = true;
        for (Spawn spawn : spawns) {
            doThrow(new NullPointerException("the return value of \"ServerLevel.getCurrentWorldData()\" is null"))
                .when(spawn.entity).remove();
        }
    }

    @Test
    void aFoliaServerStopForgetsEveryHologramWithoutRemovingOrReportingAnything() {
        EntityHolograms holograms = EntityHolograms.create(owner, foliaScheduler(), errors);

        holograms.create("a", here, List.of("one", "two"));
        holograms.create("b", here, List.of(Component.text("three")), item());
        runQueued();
        stopLikeFolia();

        holograms.onPluginDisable(disabling(owner));
        holograms.close();

        assertThat(holograms.ids()).isEmpty();
        assertThat(spawns).hasSize(4);
        for (Spawn spawn : spawns) {
            verify(spawn.entity, never()).remove();
        }
        assertThat(reported).isEmpty();
        assertThat(logged.lines()).isEmpty();
        assertThat(queued).isEmpty();
    }

    @Test
    void aFoliaServerStopForgetsTheEntitiesOfARemovalThatWaitsForItsTask() {
        EntityHolograms holograms = EntityHolograms.create(owner, foliaScheduler(), errors);

        holograms.create("crate", here, List.of("line"));
        runQueued();
        owned = false;
        holograms.remove("crate");
        stopLikeFolia();

        holograms.close();
        runQueued();

        verify(spawns.getFirst().entity, never()).remove();
        assertThat(reported).isEmpty();
        assertThat(logged.lines()).isEmpty();
    }

    @Test
    void aFoliaDisableWhileTheServerRunsStillRemovesTheEntities() {
        EntityHolograms holograms = EntityHolograms.create(owner, foliaScheduler(), errors);

        holograms.create("crate", here, List.of("one", "two"));
        runQueued();

        holograms.onPluginDisable(disabling(owner));

        assertThat(standing()).isEmpty();
        assertThat(holograms.ids()).isEmpty();
    }

    @Test
    void aPaperServerStopStillRemovesTheEntities() {
        EntityHolograms holograms = holograms();
        holograms.create("crate", here, List.of("one", "two"));
        runQueued();
        when(server.isStopping()).thenReturn(true);

        holograms.onPluginDisable(disabling(owner));

        assertThat(standing()).isEmpty();
        assertThat(holograms.ids()).isEmpty();
    }

    @Test
    void closeRemovesEveryHologramAndStopsTheProvider() {
        EntityHolograms holograms = holograms();
        holograms.create("crate", here, List.of("line"));
        runQueued();

        holograms.close();
        holograms.close();

        assertThat(standing()).isEmpty();
        assertThat(holograms.available()).isFalse();
        assertThat(holograms.create("crate", here, List.of("line"))).isFalse();
        assertThat(holograms.create("crate", here, List.of(Component.text("line")), null)).isFalse();
        assertThat(holograms.update("crate", List.of("line"))).isFalse();
        assertThat(holograms.move("crate", here)).isFalse();
        assertThat(holograms.remove("crate")).isFalse();
        assertThat(holograms.exists("crate")).isFalse();
        assertThat(holograms.ids()).isEmpty();
        assertThat(holograms.removeAll()).isZero();
        assertThat(queued).isEmpty();
    }

    @Test
    void aDisableRemovesTheEntitiesOfAMoveWhoseTaskHasNotRun() {
        EntityHolograms holograms = holograms();
        holograms.create("crate", here, List.of("line"));
        runQueued();
        holograms.move("crate", new Location(world, 100.5, 70, 100.5));

        holograms.onPluginDisable(disabling(owner));

        assertThat(standing()).isEmpty();
        runQueued();
        assertThat(spawns).hasSize(1);
        assertThat(holograms.ids()).isEmpty();
    }

    @Test
    void aDisableRemovesTheEntitiesOfARemovalThatWaitsForItsTask() {
        EntityHolograms holograms = holograms();
        holograms.create("crate", here, List.of("line"));
        runQueued();
        owned = false;
        holograms.remove("crate");
        assertThat(standing()).hasSize(1);
        owned = true;

        holograms.onPluginDisable(disabling(owner));

        assertThat(standing()).isEmpty();
    }

    @Test
    void closeRemovesTheEntitiesOfARemovalThatWaitsForItsTask() {
        EntityHolograms holograms = holograms();
        holograms.create("crate", here, List.of("line"));
        runQueued();
        owned = false;
        holograms.remove("crate");
        owned = true;

        holograms.close();

        assertThat(standing()).isEmpty();
    }

    @Test
    void aMoveThatRacesCloseAnswersFalseAndLeavesNoEntity() throws InterruptedException {
        EntityHolograms holograms = holograms();
        holograms.create("a", here, List.of("one"));
        holograms.create("b", here, List.of("two"));
        runQueued();
        CountDownLatch inMove = new CountDownLatch(1);
        CountDownLatch closing = new CountDownLatch(1);
        AtomicBoolean moved = new AtomicBoolean(true);
        Thread mover = new Thread(() -> moved.set(holograms.move("b", new Location(world, 100.5, 70, 100.5))));
        when(server.getWorld(world.getUID())).thenAnswer(call -> {
            if (Thread.currentThread() == mover) {
                inMove.countDown();
                closing.await(10, TimeUnit.SECONDS);
            }
            return world;
        });
        AtomicBoolean paused = new AtomicBoolean();
        for (Spawn spawn : spawns) {
            doAnswer(remove -> {
                if (paused.compareAndSet(false, true)) {
                    closing.countDown();
                    mover.join(10_000);
                }
                return spawn.removed = true;
            }).when(spawn.entity).remove();
        }
        mover.start();
        assertThat(inMove.await(10, TimeUnit.SECONDS)).isTrue();

        holograms.close();
        mover.join(10_000);
        runQueued();

        assertThat(moved).isFalse();
        assertThat(standing()).isEmpty();
        assertThat(spawns).hasSize(2);
    }

    @Test
    void anEntityThatRefusesItsRemovalIsReportedAndTheOthersAreRemoved() {
        doAnswer(call -> {
            Listener listener = call.getArgument(0);
            Plugin plugin = call.getArgument(1);
            PluginDisableEvent.getHandlerList().register(new RegisteredListener(listener, (each, event) -> { },
                EventPriority.MONITOR, plugin, false));
            return null;
        }).when(pluginManager).registerEvents(any(Listener.class), any(Plugin.class));
        try {
            EntityHolograms holograms = holograms();
            failRemoveAt = 0;
            holograms.create("a", here, List.of("one", "two"));
            holograms.create("b", here, List.of("three"));
            runQueued();

            holograms.close();

            assertThat(standing()).containsExactly(spawns.getFirst());
            assertThat(reported).containsExactly("hook.displays");
            assertThat(logged.lines()).singleElement().asString().contains("could not remove a hologram");
            assertThat(HandlerList.getRegisteredListeners(owner)).isEmpty();
        } finally {
            HandlerList.unregisterAll(owner);
        }
    }

    @Test
    void aLineMiniMessageRefusesIsNotReportedWhenTheCallAnswersFalseWithoutIt() {
        EntityHolograms holograms = holograms();
        List<String> refused = List.of("§6Daily crate");
        holograms.create("crate", here, List.of("line"));

        assertThat(holograms.update("none", refused)).isFalse();
        assertThat(holograms.create("crate", here, refused)).isFalse();
        assertThat(holograms.create("a", new Location(null, 0, 64, 0), refused)).isFalse();
        when(owner.isEnabled()).thenReturn(false);
        assertThat(holograms.create("b", here, refused)).isFalse();
        assertThat(holograms.update("crate", refused)).isFalse();
        when(owner.isEnabled()).thenReturn(true);
        holograms.close();
        assertThat(holograms.create("c", here, refused)).isFalse();
        assertThat(holograms.update("crate", refused)).isFalse();

        assertThat(reported).isEmpty();
        assertThat(logged.lines()).isEmpty();
    }

    @Test
    void removeAllCountsTheHologramsItRemoved() {
        EntityHolograms holograms = holograms();
        holograms.create("a", here, List.of("one"));
        holograms.create("b", here, List.of("two"));
        runQueued();

        assertThat(holograms.removeAll()).isEqualTo(2);
        assertThat(standing()).isEmpty();
        assertThat(holograms.removeAll()).isZero();
    }

    @Test
    void closeUnregistersTheListenerSoBuildingAgainLeavesOne() {
        doAnswer(call -> {
            Listener listener = call.getArgument(0);
            Plugin plugin = call.getArgument(1);
            PluginDisableEvent.getHandlerList().register(new RegisteredListener(listener, (each, event) -> { },
                EventPriority.MONITOR, plugin, false));
            return null;
        }).when(pluginManager).registerEvents(any(Listener.class), any(Plugin.class));
        try {
            EntityHolograms first = holograms();
            first.close();
            EntityHolograms second = holograms();

            assertThat(HandlerList.getRegisteredListeners(owner)).extracting(RegisteredListener::getListener)
                .containsExactly((Listener) second);
        } finally {
            HandlerList.unregisterAll(owner);
        }
    }

    @Test
    void buildingTheProviderRemovesTheTaggedEntitiesAnEarlierOneLeftInLoadedChunks() {
        NamespacedKey key = new NamespacedKey("slate", "taffy_hologram");
        TextDisplay leftover = mock(TextDisplay.class);
        tagged(leftover, new HashMap<>(Map.of(key, "crate")));
        TextDisplay foreign = mock(TextDisplay.class);
        tagged(foreign, new HashMap<>(Map.of(new NamespacedKey("other", "taffy_hologram"), "crate")));
        Pig pig = mock(Pig.class);
        tagged(pig, new HashMap<>(Map.of(key, "crate")));
        Chunk chunk = chunk(3, 4);
        when(chunk.getEntities()).thenReturn(new Entity[] {leftover, foreign, pig});
        when(world.getLoadedChunks()).thenReturn(new Chunk[] {chunk});

        holograms();

        verify(leftover, never()).remove();
        assertThat(queued).singleElement().satisfies(task -> {
            assertThat(task.chunkX()).isEqualTo(3);
            assertThat(task.chunkZ()).isEqualTo(4);
        });
        runQueued();
        verify(leftover).remove();
        verify(foreign, never()).remove();
        verify(pig, never()).remove();
    }

    @Test
    void loadedEntitiesWithTheTagAreRemovedUnlessTheProviderHoldsThem() {
        EntityHolograms holograms = holograms();
        holograms.create("crate", here, List.of("line"));
        runQueued();
        TextDisplay leftover = mock(TextDisplay.class);
        tagged(leftover, new HashMap<>(Map.of(new NamespacedKey("slate", "taffy_hologram"), "crate")));

        holograms.onEntitiesLoad(new EntitiesLoadEvent(chunk(1, -1), List.of(leftover, spawns.getFirst().entity)));

        verify(leftover).remove();
        assertThat(standing()).hasSize(1);
    }

    @Test
    void aCallWhileTheOwnerIsNotEnabledAnswersFalse() {
        EntityHolograms holograms = holograms();
        when(owner.isEnabled()).thenReturn(false);

        assertThat(holograms.available()).isFalse();
        assertThat(holograms.create("crate", here, List.of("line"))).isFalse();
        assertThat(holograms.ids()).isEmpty();
        assertThat(queued).isEmpty();
    }
}
