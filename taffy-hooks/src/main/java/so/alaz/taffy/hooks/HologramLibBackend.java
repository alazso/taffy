package so.alaz.taffy.hooks;

import com.maximde.hologramlib.HologramLib;
import com.maximde.hologramlib.hologram.Hologram;
import com.maximde.hologramlib.hologram.HologramManager;
import com.maximde.hologramlib.hologram.TextHologram;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

// HologramLib draws one text display per hologram, so the lines are one text joined by line breaks. It spawns a
// hologram on the next tick and gives it a location only then, and a hologram removed before that tick is still
// spawned, untracked. So until a hologram has a location, new text waits for the spawn, and a move or a removal is
// done again two ticks later on the hologram object itself. Those tasks run for HologramLib's plugin, which stays
// enabled while the owner disables. A TextHologram starts a timer when it is built, so text is parsed before one is,
// and one that fails to spawn is killed. Its names are unique and never replaced.
final class HologramLibBackend {

    static final long AFTER_SPAWN_TICKS = 2;

    private HologramLibBackend() {
    }

    static HologramBackend create(Plugin hologramLib) {
        ApiLink.staticMethod(HologramLib.class, "getManager", Optional.class);
        ApiLink.instanceMethod(HologramManager.class, "hologramExists", boolean.class, String.class);
        ApiLink.instanceMethod(HologramManager.class, "getHologram", Optional.class, String.class);
        ApiLink.instanceMethod(HologramManager.class, "spawn", Hologram.class, Hologram.class, Location.class);
        ApiLink.instanceMethod(HologramManager.class, "remove", boolean.class, String.class);
        ApiLink.instanceMethod(HologramManager.class, "remove", boolean.class, Hologram.class);
        ApiLink.constructor(TextHologram.class, String.class);
        ApiLink.instanceMethod(TextHologram.class, "setMiniMessageText", TextHologram.class, String.class);
        ApiLink.instanceMethod(Hologram.class, "update", Hologram.class);
        ApiLink.instanceMethod(Hologram.class, "teleport", Hologram.class, Location.class);
        ApiLink.instanceMethod(Hologram.class, "getLocation", Location.class);
        ApiLink.instanceMethod(Hologram.class, "kill", void.class);
        Supplier<HologramManager> managers = HologramLibBackend::manager;
        Consumer<Runnable> later = task -> Bukkit.getGlobalRegionScheduler()
            .runDelayed(hologramLib, scheduled -> task.run(), AFTER_SPAWN_TICKS);
        Placements<Hologram<?>> placements = new Placements<>(name -> manager().getHologram(name).orElse(null),
            displaced -> { });
        return HologramBackend.of(
            name -> manager().hologramExists(name),
            name -> placements.standing(name) != null,
            (name, location, lines) -> placements.add(name, spawn(manager(), name, location, parsed(text(lines)))),
            (name, lines) -> {
                String text = parsed(text(lines));
                Hologram<?> hologram = placements.standing(name);
                if (hologram != null) {
                    retext(hologram, text);
                }
            },
            (name, location) -> {
                Hologram<?> hologram = placements.standing(name);
                if (hologram != null) {
                    relocate(managers, name, hologram, location, later);
                }
            },
            name -> {
                Hologram<?> hologram = placements.standing(name);
                if (hologram == null || !remove(manager(), name, hologram, later)) {
                    return false;
                }
                placements.forget(name, hologram);
                return true;
            });
    }

    private static HologramManager manager() {
        return HologramLib.getManager().orElseThrow(() -> new IllegalStateException("HologramLib has no manager"));
    }

    private static TextHologram spawn(HologramManager manager, String name, Location location, String text) {
        TextHologram hologram = new TextHologram(name);
        try {
            hologram.setMiniMessageText(text);
            return manager.spawn(hologram, location);
        } catch (RuntimeException | LinkageError failure) {
            try {
                kill(hologram);
            } catch (RuntimeException | LinkageError cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    // Hologram.kill acts on the object, where HologramManager.remove(Hologram) removes whatever holds its name.
    @SuppressWarnings("deprecation")
    static void kill(Hologram<?> hologram) {
        hologram.kill();
    }

    // The text, after checking that MiniMessage parses it as HologramLib will.
    static String parsed(String text) {
        MiniMessage.miniMessage().deserialize(text);
        return text;
    }

    static String text(List<String> lines) {
        return String.join("\n", lines);
    }

    static void retext(Hologram<?> hologram, String text) {
        if (hologram instanceof TextHologram textHologram) {
            textHologram.setMiniMessageText(text);
            if (textHologram.getLocation() != null) {
                textHologram.update();
            }
        }
    }

    static void relocate(Supplier<HologramManager> managers, String name, Hologram<?> hologram, Location location,
                         Consumer<Runnable> later) {
        if (hologram.getLocation() != null) {
            hologram.teleport(location);
            return;
        }
        later.accept(() -> managers.get().getHologram(name)
            .filter(standing -> standing == hologram && standing.getLocation() != null)
            .ifPresent(standing -> standing.teleport(location)));
    }

    static boolean remove(HologramManager manager, String name, Hologram<?> hologram, Consumer<Runnable> later) {
        boolean waiting = hologram.getLocation() == null;
        boolean removed = manager.remove(name);
        if (waiting) {
            later.accept(() -> kill(hologram));
        }
        return removed;
    }
}
