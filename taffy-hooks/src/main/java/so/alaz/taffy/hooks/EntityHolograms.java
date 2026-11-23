package so.alaz.taffy.hooks;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.platform.PdcKey;
import so.alaz.taffy.platform.Scheduler;
import so.alaz.taffy.platform.ThrowLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

// The records change under the lock, on any thread. An entity is touched only on the thread that owns its location.
final class EntityHolograms implements DisplayHolograms, Listener {

    static final String SOURCE = "hook.displays";
    static final String TAG = "taffy_hologram";

    private static final Spawned NOTHING = new Spawned(List.of(), null);

    private final Plugin owner;
    private final Server server;
    private final Scheduler scheduler;
    private final ErrorReports errors;
    private final ThrowLog throwLog;
    private final PdcKey<String, String> tag;
    private final Object lock = new Object();
    private final Map<String, Hologram> holograms = new ConcurrentHashMap<>();
    private final Map<ChunkKey, Set<Hologram>> byChunk = new ConcurrentHashMap<>();
    private final Set<UUID> live = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    private record Content(List<Component> lines, @Nullable ItemStack item) {
    }

    private record Spawned(List<TextDisplay> lines, @Nullable ItemDisplay item) {

        boolean fits(Content content) {
            if (lines.size() != content.lines().size() || (item == null) != (content.item() == null)) {
                return false;
            }
            return (item == null || item.isValid()) && lines.stream().allMatch(Entity::isValid);
        }

        List<Display> entities() {
            List<Display> all = new ArrayList<>(lines);
            if (item != null) {
                all.add(item);
            }
            return all;
        }
    }

    private record ChunkKey(UUID world, int x, int z) {

        static ChunkKey of(World world, Location location) {
            return new ChunkKey(world.getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        }
    }

    private static final class Hologram {

        final String id;
        final World world;
        final Location location;
        final ChunkKey chunk;
        final AtomicBoolean queued = new AtomicBoolean();
        volatile Content content;
        volatile boolean gone;
        volatile Spawned spawned = NOTHING;

        Hologram(String id, World world, Location location, Content content) {
            this.id = id;
            this.world = world;
            this.location = location;
            this.chunk = ChunkKey.of(world, location);
            this.content = content;
        }
    }

    private EntityHolograms(Plugin owner, Scheduler scheduler, ErrorReports errors) {
        this.owner = owner;
        this.server = owner.getServer();
        this.scheduler = scheduler;
        this.errors = errors;
        this.throwLog = new ThrowLog(owner.getLogger());
        this.tag = new PdcKey<>(new NamespacedKey(owner, TAG), PersistentDataType.STRING);
    }

    static EntityHolograms create(Plugin owner, Scheduler scheduler, ErrorReports errors) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(errors, "errors");
        EntityHolograms holograms = new EntityHolograms(owner, scheduler, errors);
        owner.getServer().getPluginManager().registerEvents(holograms, owner);
        holograms.sweepLoadedChunks();
        return holograms;
    }

    @Override
    public @Nullable HologramPlugin plugin() {
        return null;
    }

    @Override
    public boolean available() {
        return !closed && owner.isEnabled();
    }

    @Override
    public boolean create(String id, Location location, List<String> lines) {
        PluginHolograms.requireId(id);
        Objects.requireNonNull(location, "location");
        List<String> text = List.copyOf(lines);
        List<Component> parsed = parse("create", text);
        return parsed != null && place(id, location, new Content(parsed, null));
    }

    @Override
    public boolean create(String id, Location location, List<Component> lines, @Nullable ItemStack item) {
        PluginHolograms.requireId(id);
        Objects.requireNonNull(location, "location");
        return place(id, location, new Content(List.copyOf(lines), item == null ? null : item.clone()));
    }

    @Override
    public boolean update(String id, List<String> lines) {
        PluginHolograms.requireId(id);
        List<String> text = List.copyOf(lines);
        List<Component> parsed = parse("update", text);
        return parsed != null && replace(id, parsed, false, null);
    }

    @Override
    public boolean update(String id, List<Component> lines, @Nullable ItemStack item) {
        PluginHolograms.requireId(id);
        return replace(id, List.copyOf(lines), true, item == null ? null : item.clone());
    }

    @Override
    public boolean move(String id, Location location) {
        PluginHolograms.requireId(id);
        Objects.requireNonNull(location, "location");
        if (!available()) {
            return false;
        }
        World world = loadedWorld(location);
        if (world == null) {
            return false;
        }
        Hologram old;
        Hologram moved;
        synchronized (lock) {
            old = holograms.get(id);
            if (old == null) {
                return false;
            }
            moved = new Hologram(id, world, location.clone(), old.content);
            unlist(old);
            holograms.put(id, moved);
            list(moved);
        }
        discard(old, false);
        return request(moved) || forget(moved);
    }

    @Override
    public boolean remove(String id) {
        PluginHolograms.requireId(id);
        if (!available()) {
            return false;
        }
        Hologram hologram;
        synchronized (lock) {
            hologram = holograms.remove(id);
            if (hologram == null) {
                return false;
            }
            unlist(hologram);
        }
        discard(hologram, true);
        return true;
    }

    @Override
    public boolean exists(String id) {
        PluginHolograms.requireId(id);
        return available() && holograms.containsKey(id);
    }

    @Override
    public Set<String> ids() {
        if (!available()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new TreeSet<>(holograms.keySet()));
    }

    @Override
    public int removeAll() {
        return available() ? retireAll() : 0;
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        retireAll();
        HandlerList.unregisterAll(this);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        UUID world = event.getWorld().getUID();
        for (Hologram hologram : List.copyOf(holograms.values())) {
            if (hologram.chunk.world().equals(world)) {
                forget(hologram);
            }
        }
    }

    // Paper fires a plugin's PluginDisableEvent before its onDisable, while its scheduled tasks can still be queued
    // but may never run.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() == owner) {
            retireAll();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        for (Hologram hologram : at(event.getWorld(), chunk)) {
            request(hologram);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        for (Hologram hologram : at(event.getWorld(), chunk)) {
            despawn(hologram);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        sweep(event.getEntities());
    }

    private @Nullable List<Component> parse(String action, List<String> lines) {
        MiniMessage miniMessage = MiniMessage.miniMessage();
        try {
            return lines.stream().map(miniMessage::deserialize).toList();
        } catch (Exception ex) {
            fail(action, ex);
            return null;
        }
    }

    private boolean place(String id, Location location, Content content) {
        if (!available()) {
            return false;
        }
        World world = loadedWorld(location);
        if (world == null) {
            return false;
        }
        Hologram hologram = new Hologram(id, world, location.clone(), content);
        synchronized (lock) {
            if (closed || holograms.putIfAbsent(id, hologram) != null) {
                return false;
            }
            list(hologram);
        }
        return request(hologram) || forget(hologram);
    }

    private boolean replace(String id, List<Component> lines, boolean withItem, @Nullable ItemStack item) {
        if (!available()) {
            return false;
        }
        Hologram hologram;
        synchronized (lock) {
            hologram = holograms.get(id);
            if (hologram == null) {
                return false;
            }
            hologram.content = new Content(lines, withItem ? item : hologram.content.item());
        }
        return request(hologram);
    }

    private int retireAll() {
        int removed = 0;
        for (Hologram hologram : List.copyOf(holograms.values())) {
            boolean held;
            synchronized (lock) {
                held = holograms.remove(hologram.id, hologram);
                if (held) {
                    unlist(hologram);
                }
            }
            if (held) {
                discard(hologram, true);
                removed++;
            }
        }
        return removed;
    }

    // Drops the record of a hologram and its entities, and answers false.
    private boolean forget(Hologram hologram) {
        synchronized (lock) {
            if (holograms.remove(hologram.id, hologram)) {
                unlist(hologram);
            }
        }
        discard(hologram, true);
        return false;
    }

    private void list(Hologram hologram) {
        byChunk.computeIfAbsent(hologram.chunk, key -> ConcurrentHashMap.newKeySet()).add(hologram);
    }

    private void unlist(Hologram hologram) {
        byChunk.computeIfPresent(hologram.chunk, (key, held) -> {
            held.remove(hologram);
            return held.isEmpty() ? null : held;
        });
    }

    private List<Hologram> at(World world, Chunk chunk) {
        Set<Hologram> held = byChunk.get(new ChunkKey(world.getUID(), chunk.getX(), chunk.getZ()));
        return held == null ? List.of() : List.copyOf(held);
    }

    // Queues one run of show for the hologram on the region thread of its location, unless one is queued already.
    private boolean request(Hologram hologram) {
        if (!hologram.queued.compareAndSet(false, true)) {
            return true;
        }
        boolean accepted = region(hologram, () -> {
            hologram.queued.set(false);
            show(hologram);
        });
        if (!accepted) {
            hologram.queued.set(false);
        }
        return accepted;
    }

    private boolean region(Hologram hologram, Runnable task) {
        try {
            return scheduler.region(hologram.location, task) != null;
        } catch (IllegalArgumentException | NullPointerException unloaded) {
            // The location's world has unloaded.
            return false;
        }
    }

    // Removes the entities of a hologram that no longer shows. A removal that waits for the region thread keeps a
    // move from blinking: on Paper it runs in the tick the new entities spawn.
    private void discard(Hologram hologram, boolean atOnce) {
        hologram.gone = true;
        if (atOnce && owned(hologram)) {
            despawn(hologram);
        } else if (!region(hologram, () -> despawn(hologram)) && owned(hologram)) {
            despawn(hologram);
        }
    }

    private boolean owned(Hologram hologram) {
        try {
            return server.isOwnedByCurrentRegion(hologram.location);
        } catch (RuntimeException unloaded) {
            return false;
        }
    }

    // Runs on the thread that owns the hologram's location.
    private void show(Hologram hologram) {
        if (hologram.gone) {
            despawn(hologram);
            return;
        }
        if (server.getWorld(hologram.world.getUID()) != hologram.world) {
            forget(hologram);
            return;
        }
        if (!hologram.world.isChunkLoaded(hologram.chunk.x(), hologram.chunk.z())) {
            despawn(hologram);
            return;
        }
        Content content = hologram.content;
        try {
            Spawned spawned = hologram.spawned;
            if (spawned != NOTHING && spawned.fits(content)) {
                write(spawned, content);
            } else {
                despawn(hologram);
                spawn(hologram, content);
            }
        } catch (Exception | LinkageError ex) {
            forget(hologram);
            despawn(hologram);
            fail("show", ex);
        }
        if (hologram.gone) {
            despawn(hologram);
        }
    }

    private static void write(Spawned spawned, Content content) {
        for (int line = 0; line < spawned.lines().size(); line++) {
            spawned.lines().get(line).text(content.lines().get(line));
        }
        ItemDisplay item = spawned.item();
        ItemStack stack = content.item();
        if (item != null && stack != null) {
            item.setItemStack(stack.clone());
        }
    }

    private void spawn(Hologram hologram, Content content) {
        List<TextDisplay> lines = new ArrayList<>();
        ItemDisplay item = null;
        try {
            int count = content.lines().size();
            for (int line = 0; line < count; line++) {
                Component text = content.lines().get(line);
                Location at = hologram.location.clone().add(0, (count - 1 - line) * LINE_SPACING, 0);
                TextDisplay entity = hologram.world.spawn(at, TextDisplay.class, display -> {
                    mark(display, hologram);
                    display.text(text);
                    display.setAlignment(TextDisplay.TextAlignment.CENTER);
                    display.setShadowed(true);
                    display.setSeeThrough(false);
                    display.setLineWidth(Integer.MAX_VALUE);
                });
                live.add(entity.getUniqueId());
                lines.add(entity);
            }
            ItemStack stack = content.item();
            if (stack != null) {
                Location at = hologram.location.clone().add(0, Math.max(0, count - 1) * LINE_SPACING + ITEM_RISE, 0);
                item = hologram.world.spawn(at, ItemDisplay.class, display -> {
                    mark(display, hologram);
                    display.setItemStack(stack.clone());
                    display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                    display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                        new Vector3f(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE), new Quaternionf()));
                });
                live.add(item.getUniqueId());
            }
        } finally {
            hologram.spawned = new Spawned(List.copyOf(lines), item);
        }
    }

    // A non-persistent entity is not written to the chunk's entity file, at a save or when the chunk unloads.
    private void mark(Display display, Hologram hologram) {
        display.setPersistent(false);
        display.setBillboard(Display.Billboard.CENTER);
        tag.set(display, hologram.id);
    }

    // Runs on the thread that owns the hologram's location.
    private void despawn(Hologram hologram) {
        Spawned spawned = hologram.spawned;
        hologram.spawned = NOTHING;
        for (Display entity : spawned.entities()) {
            live.remove(entity.getUniqueId());
            entity.remove();
        }
    }

    private void sweepLoadedChunks() {
        for (World world : server.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                Location corner = new Location(world, chunk.getX() << 4, 0, chunk.getZ() << 4);
                scheduler.region(corner, () -> {
                    if (chunk.isLoaded()) {
                        sweep(List.of(chunk.getEntities()));
                    }
                });
            }
        }
    }

    // Runs on the thread that owns the entities.
    private void sweep(List<Entity> entities) {
        for (Entity entity : entities) {
            if (entity instanceof Display && tag.has(entity) && !live.contains(entity.getUniqueId())) {
                entity.remove();
            }
        }
    }

    // The location's world when it is one the server has loaded, or null.
    private @Nullable World loadedWorld(Location location) {
        World world = PluginHolograms.world(location);
        return world != null && server.getWorld(world.getUID()) == world ? world : null;
    }

    private void fail(String action, Throwable failure) {
        throwLog.warn(action, "Display holograms could not " + action + " a hologram (" + failure + ").", failure);
        errors.report(SOURCE, failure);
    }
}
