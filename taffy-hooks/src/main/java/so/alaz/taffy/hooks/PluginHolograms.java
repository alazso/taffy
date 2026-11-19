package so.alaz.taffy.hooks;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.platform.ThrowLog;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Pattern;

// The owner part of a name holds no _, so the first _ ends it and no owner part and id run into another pair.
final class PluginHolograms implements HologramProvider, Listener {

    static final String EXPECTED = "1 to 48 of a-z, 0-9, _ and -";

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,48}");
    private static final Pattern OUTSIDE_OWNER = Pattern.compile("[^a-z0-9-]");
    // The vanilla client font gives U+200C no width and no glyph; U+200B falls through to Unifont, which draws a box.
    static final String ZERO_WIDTH_NON_JOINER = Character.toString(0x200C);

    private final HologramPlugin plugin;
    private final Plugin bound;
    private final Plugin owner;
    private final String prefix;
    private final HologramBackend backend;
    private final ThrowLog throwLog;
    private final ErrorReports errors;
    private final String source;
    private final Map<String, UUID> placed = new ConcurrentHashMap<>();
    private volatile boolean closed;

    PluginHolograms(HologramPlugin plugin, Plugin bound, Plugin owner, HologramBackend backend, ErrorReports errors) {
        this.plugin = plugin;
        this.bound = bound;
        this.owner = owner;
        this.prefix = prefix(owner.getName());
        this.backend = backend;
        this.throwLog = new ThrowLog(owner.getLogger());
        this.errors = errors;
        this.source = Detection.source(plugin.plugin());
    }

    static Detected<HologramProvider> detect(Plugin owner, ErrorReports errors, boolean switchedOn,
                                             HologramPlugin plugin, Presence presence,
                                             Function<Plugin, HologramBackend> backends) {
        Objects.requireNonNull(errors, "errors");
        Logger logger = owner.getLogger();
        return Detection.run(plugin.plugin(), plugin.probe(), switchedOn, presence, bound -> {
            PluginHolograms holograms = new PluginHolograms(plugin, bound, owner, backends.apply(bound), errors);
            owner.getServer().getPluginManager().registerEvents(holograms, owner);
            return holograms;
        }, logger, errors);
    }

    static String prefix(String owner) {
        return OUTSIDE_OWNER.matcher(owner.toLowerCase(Locale.ROOT)).replaceAll("-") + "_";
    }

    // Each listed character starts a MiniMessage tag, a colour code or a placeholder in a supported hologram plugin,
    // and a zero-width non-joiner after it stops that.
    static String literal(String text) {
        Objects.requireNonNull(text, "text");
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '<', '&', '\u00a7', '%', '{' -> out.append(c).append(ZERO_WIDTH_NON_JOINER);
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    @Override
    public HologramPlugin plugin() {
        return plugin;
    }

    @Override
    public boolean available() {
        return !closed && bound.isEnabled();
    }

    @Override
    public boolean create(String id, Location location, List<String> lines) {
        String name = name(id);
        Objects.requireNonNull(location, "location");
        List<String> text = List.copyOf(lines);
        if (!available()) {
            return false;
        }
        World world = world(location);
        if (world == null) {
            return false;
        }
        try {
            if (backend.taken(name)) {
                return false;
            }
            backend.create(name, location.clone(), text);
            placed.put(id, world.getUID());
            return true;
        } catch (Exception | LinkageError ex) {
            fail("create", ex);
            return false;
        }
    }

    @Override
    public boolean update(String id, List<String> lines) {
        String name = name(id);
        List<String> text = List.copyOf(lines);
        if (!available() || !placed.containsKey(id)) {
            return false;
        }
        try {
            if (!backend.has(name)) {
                return false;
            }
            backend.update(name, text);
            return true;
        } catch (Exception | LinkageError ex) {
            fail("update", ex);
            return false;
        }
    }

    @Override
    public boolean move(String id, Location location) {
        String name = name(id);
        Objects.requireNonNull(location, "location");
        if (!available() || !placed.containsKey(id)) {
            return false;
        }
        World world = world(location);
        if (world == null) {
            return false;
        }
        try {
            if (!backend.has(name)) {
                return false;
            }
            backend.move(name, location.clone());
            placed.replace(id, world.getUID());
            return true;
        } catch (Exception | LinkageError ex) {
            fail("move", ex);
            return false;
        }
    }

    @Override
    public boolean remove(String id) {
        String name = name(id);
        if (!available() || !placed.containsKey(id)) {
            return false;
        }
        try {
            if (!backend.has(name)) {
                placed.remove(id);
                return false;
            }
            if (!backend.remove(name)) {
                return false;
            }
            placed.remove(id);
            return true;
        } catch (Exception | LinkageError ex) {
            fail("remove", ex);
            return false;
        }
    }

    @Override
    public boolean exists(String id) {
        String name = name(id);
        if (!available() || !placed.containsKey(id)) {
            return false;
        }
        try {
            return backend.has(name);
        } catch (Exception | LinkageError ex) {
            fail("find", ex);
            return false;
        }
    }

    @Override
    public Set<String> ids() {
        if (!available()) {
            return Set.of();
        }
        Set<String> standing = new TreeSet<>();
        for (String id : Set.copyOf(placed.keySet())) {
            boolean stands;
            try {
                stands = backend.has(prefix + id);
            } catch (Exception | LinkageError ex) {
                fail("find", ex);
                continue;
            }
            if (stands) {
                standing.add(id);
            } else {
                placed.remove(id);
            }
        }
        return Collections.unmodifiableSet(standing);
    }

    @Override
    public int removeAll() {
        int removed = 0;
        for (String id : Set.copyOf(placed.keySet())) {
            if (remove(id)) {
                removed++;
            }
        }
        return removed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        removeAll();
        HandlerList.unregisterAll(this);
        closed = true;
    }

    // Removes the holograms in a world while it is still loaded: FancyHolograms keeps a temporary hologram of an
    // unloaded world and its visibility task then stops for every hologram on the server.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        UUID world = event.getWorld().getUID();
        for (Map.Entry<String, UUID> entry : Set.copyOf(placed.entrySet())) {
            if (entry.getValue().equals(world)) {
                remove(entry.getKey());
            }
        }
    }

    // Paper fires a plugin's PluginDisableEvent before its onDisable and before its listeners are unregistered. The
    // ids placed through the hologram plugin are forgotten when it disables, so none survives a reload of it.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        Plugin disabling = event.getPlugin();
        if (disabling == owner) {
            removeAll();
        } else if (disabling == bound) {
            placed.clear();
        }
    }

    private String name(String id) {
        Objects.requireNonNull(id, "id");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid hologram id '" + id + "': expected " + EXPECTED);
        }
        return prefix + id;
    }

    // The location's world, or null when it has none or its world has unloaded.
    private static @Nullable World world(Location location) {
        try {
            return location.getWorld();
        } catch (IllegalArgumentException unloaded) {
            // Location.getWorld throws when the world it referred to has unloaded.
            return null;
        }
    }

    private void fail(String action, Throwable failure) {
        throwLog.warn(action, plugin.plugin() + " could not " + action + " a hologram (" + failure + ").", failure);
        errors.report(source, failure);
    }
}
